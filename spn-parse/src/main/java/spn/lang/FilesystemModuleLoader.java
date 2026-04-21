package spn.lang;

import spn.language.ModuleLoader;
import spn.language.SpnLanguage;
import spn.language.SpnModule;
import spn.language.SpnModuleRegistry;
import spn.type.SpnSymbolTable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Loads SPN modules from the local filesystem, relative to a module root directory.
 *
 * <p>Maps dotted namespaces to file paths relative to the root:
 * <ul>
 *   <li>{@code "numerics.rational"} → {@code <root>/numerics/rational.spn}</li>
 *   <li>{@code "numerics.rational"} → {@code <root>/numerics/rational/index.spn} (fallback)</li>
 * </ul>
 *
 * <p>The root is typically the directory containing {@code module.spn}.
 * The module's own namespace prefix is stripped before resolution.
 */
public final class FilesystemModuleLoader implements ModuleLoader {

    private final Path root;
    private final String moduleNamespace; // e.g., "sibarum.spn.traction"
    private final SpnLanguage language;
    private final SpnSymbolTable symbolTable;

    public FilesystemModuleLoader(Path root, String moduleNamespace,
                                   SpnLanguage language, SpnSymbolTable symbolTable) {
        this.root = root;
        this.moduleNamespace = moduleNamespace;
        this.language = language;
        this.symbolTable = symbolTable;
    }

    @Override
    public Optional<SpnModule> load(String namespace, SpnModuleRegistry registry) {
        // Try the namespace as-is relative to root
        Path resolved = tryResolve(namespace);

        // If the namespace starts with the module's own prefix, try stripping it
        if (resolved == null && moduleNamespace != null && namespace.startsWith(moduleNamespace + ".")) {
            String relative = namespace.substring(moduleNamespace.length() + 1);
            resolved = tryResolve(relative);
        }

        // Also try the raw namespace without any prefix (for sibling imports)
        if (resolved == null) {
            resolved = tryResolve(namespace);
        }

        if (resolved == null) return Optional.empty();

        // Cycle detection
        if (!registry.beginLoading(namespace)) {
            throw new RuntimeException("Circular import detected: " + namespace
                    + " (loading chain: " + registry.getLoadingChain() + ")");
        }

        try {
            String source = Files.readString(resolved, StandardCharsets.UTF_8);
            SpnParser parser = new SpnParser(source, resolved.toString(), language, symbolTable, registry);
            parser.parse();

            SpnModule module = SpnModule.builder(namespace)
                    .functions(parser.getFunctionRegistry())
                    .builtinFactories(parser.getBuiltinRegistry())
                    .types(parser.getTypeRegistry())
                    .structs(parser.getStructRegistry())
                    .variants(parser.getVariantRegistry())
                    .extra("methods", parser.getMethodRegistry())
                    .extra("factories", parser.getFactoryRegistry())
                    .extra("operators", parser.getOperatorRegistry())
                    .extra("descriptors", parser.getFunctionDescriptorRegistry())
                    .extra("constants", parser.getConstantRegistry())
                    .extra("macros", parser.getMacroRegistry())
                    .extra("promotions", parser.getPromotionRegistry())
                    .extra("signatures", parser.getSignatureRegistry())
                    .extra("qualifiedKeys", parser.getQualifiedKeyRegistry())
                    // Type-declaration positions for IDE go-to-def on types
                    // imported from this module.
                    .extra("typeDeclarations", parser.buildTypeDeclarations())
                    .build();

            return Optional.of(module);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read module: " + resolved, e);
        } finally {
            registry.finishLoading(namespace);
        }
    }

    private Path tryResolve(String namespace) {
        String relativePath = namespace.replace('.', '/');
        Path file = root.resolve(relativePath + ".spn");
        if (Files.isRegularFile(file)) return file;
        Path indexFile = root.resolve(relativePath + "/index.spn");
        if (Files.isRegularFile(indexFile)) return indexFile;
        return null;
    }
}
