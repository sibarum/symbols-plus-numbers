package spn.lang;

import spn.language.ModuleLoader;
import spn.language.SpnLanguage;
import spn.language.SpnModule;
import spn.language.SpnModuleRegistry;
import spn.type.SpnSymbolTable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Loads SPN modules from classpath resources.
 *
 * Maps a dotted namespace to a resource path:
 *   "spn.canvas.plot" → "/spn/canvas/plot.spn"
 *
 * The loaded source is parsed with a fresh SpnParser that shares the
 * same module registry (so transitive imports like "import Canvas" resolve).
 * All functions, types, structs, and variants defined in the source become
 * the module's exports.
 */
public final class ClasspathModuleLoader implements ModuleLoader {

    private final SpnLanguage language;
    private final SpnSymbolTable symbolTable;

    public ClasspathModuleLoader(SpnLanguage language, SpnSymbolTable symbolTable) {
        this.language = language;
        this.symbolTable = symbolTable;
    }

    @Override
    public Optional<SpnModule> load(String namespace, SpnModuleRegistry registry) {
        // Map namespace to resource path
        String resourcePath = "/" + namespace.replace('.', '/') + ".spn";
        InputStream stream = getClass().getResourceAsStream(resourcePath);
        if (stream == null) return Optional.empty();

        // Cycle detection
        if (!registry.beginLoading(namespace)) {
            throw new RuntimeException("Circular import detected: " + namespace
                    + " (loading chain: " + registry.getLoadingChain() + ")");
        }

        try {
            String source = new String(stream.readAllBytes(), StandardCharsets.UTF_8);

            // Parse with a fresh parser sharing the same module registry
            SpnParser parser = new SpnParser(source, language, symbolTable, registry);
            parser.parse();

            // Extract all exports into a module
            SpnModule module = SpnModule.builder(namespace)
                    .functions(parser.getFunctionRegistry())
                    .builtinFactories(parser.getBuiltinRegistry())
                    .types(parser.getTypeRegistry())
                    .structs(parser.getStructRegistry())
                    .variants(parser.getVariantRegistry())
                    .build();

            return Optional.of(module);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read module resource: " + resourcePath, e);
        } finally {
            registry.finishLoading(namespace);
        }
    }
}
