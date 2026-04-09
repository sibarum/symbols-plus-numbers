package spn.pkg;

import spn.lang.SpnParseException;
import spn.lang.SpnParser;
import spn.language.SpnLanguage;
import spn.language.SpnModule;
import spn.language.SpnModuleRegistry;
import spn.type.SpnSymbolTable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Loads SPN modules from .spn source files on demand.
 *
 * When an import references a fully-qualified namespace that isn't a registered
 * native module, this loader:
 *   1. Uses ImportResolver to find the source file
 *   2. Reads and parses it with a fresh SpnParser
 *   3. Extracts the parsed registries into an SpnModule
 *   4. Caches the module in SpnModuleRegistry
 *
 * Circular imports are detected via the registry's loading set.
 */
public final class SpnModuleLoader {

    private final SpnModuleRegistry registry;
    private final ImportResolver importResolver;
    private final SpnLanguage language;
    private final SpnSymbolTable symbolTable;

    public SpnModuleLoader(SpnModuleRegistry registry, ImportResolver importResolver,
                           SpnLanguage language, SpnSymbolTable symbolTable) {
        this.registry = registry;
        this.importResolver = importResolver;
        this.language = language;
        this.symbolTable = symbolTable;
    }

    /**
     * Loads a module by its fully-qualified namespace. Returns the cached
     * module if already loaded; otherwise resolves, parses, and caches it.
     *
     * @param namespace the fully-qualified namespace (e.g., "spn.mylib.utils")
     * @return the loaded module
     * @throws SpnParseException if the module can't be found or has errors
     */
    public SpnModule load(String namespace) {
        // Already loaded?
        Optional<SpnModule> cached = registry.lookup(namespace);
        if (cached.isPresent()) return cached.get();

        // Circular import detection
        if (!registry.beginLoading(namespace)) {
            throw new SpnParseException("Circular import detected: " + namespace
                    + " (loading chain: " + registry.getLoadingChain() + ")");
        }

        try {
            // Resolve the namespace to a file
            var resolved = importResolver.resolve(namespace);
            if (resolved.isEmpty()) {
                throw new SpnParseException("Cannot resolve module: " + namespace);
            }

            var result = resolved.get();
            if (result instanceof ImportResolver.ResolvedImport.LocalFile local) {
                return loadFromFile(namespace, local.path());
            } else if (result instanceof ImportResolver.ResolvedImport.ExternalDependency ext) {
                throw new SpnParseException("External dependency not yet supported: "
                        + ext.dependency().coordinate() + " for namespace " + namespace);
            }

            throw new SpnParseException("Unexpected resolve result for: " + namespace);
        } finally {
            registry.finishLoading(namespace);
        }
    }

    private SpnModule loadFromFile(String namespace, Path path) {
        String source;
        try {
            source = Files.readString(path);
        } catch (IOException e) {
            throw new SpnParseException("Cannot read module file: " + path
                    + " (" + e.getMessage() + ")");
        }

        // Parse with a fresh parser that shares the module registry and symbol table
        SpnParser parser = new SpnParser(source, path.toString(), language, symbolTable, registry);
        parser.parse();

        // Extract the parsed module's exports
        SpnModule.Builder builder = SpnModule.builder(namespace);

        parser.getFunctionRegistry().forEach(builder::function);
        parser.getTypeRegistry().forEach(builder::type);
        parser.getStructRegistry().forEach(builder::struct);
        parser.getVariantRegistry().forEach(builder::variant);

        SpnModule module = builder.build();
        registry.register(namespace, module);
        return module;
    }
}
