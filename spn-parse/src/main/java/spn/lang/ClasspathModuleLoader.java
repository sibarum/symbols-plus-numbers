package spn.lang;

import spn.language.ModuleLoader;
import spn.language.SpnLanguage;
import spn.language.SpnModule;
import spn.language.SpnModuleRegistry;
import spn.type.SpnSymbolTable;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

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
        // Map namespace to resource path, with index.spn fallback
        String basePath = "/" + namespace.replace('.', '/');
        InputStream stream = getClass().getResourceAsStream(basePath + ".spn");
        if (stream == null) {
            stream = getClass().getResourceAsStream(basePath + "/index.spn");
        }
        if (stream == null) return Optional.empty();

        // Cycle detection
        if (!registry.beginLoading(namespace)) {
            throw new RuntimeException("Circular import detected: " + namespace
                    + " (loading chain: " + registry.getLoadingChain() + ")");
        }

        try {
            String source = new String(stream.readAllBytes(), StandardCharsets.UTF_8);

            // Parse with a fresh parser sharing the same module registry
            SpnParser parser = new SpnParser(source, namespace, language, symbolTable, registry);
            parser.parse();

            // Extract all exports into a module
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
                    .build();

            return Optional.of(module);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read module resource: " + basePath, e);
        } finally {
            registry.finishLoading(namespace);
        }
    }

    /**
     * Discovers all .spn module namespaces available on the classpath.
     * Scans classpath roots for .spn files and derives dotted namespaces
     * from their paths (e.g., spn/canvas/draw.spn → spn.canvas.draw).
     *
     * This is a best-effort discovery — modules in unusual classpath
     * configurations may not be found. Results are sorted alphabetically.
     */
    public List<String> discoverModules() {
        Set<String> namespaces = new TreeSet<>();
        try {
            // Get all classpath root URLs by asking for the empty resource
            Enumeration<URL> roots = getClass().getClassLoader().getResources("");
            while (roots.hasMoreElements()) {
                URL root = roots.nextElement();
                if ("file".equals(root.getProtocol())) {
                    scanDirectory(Path.of(root.toURI()), Path.of(root.toURI()), namespaces);
                }
            }
            // Also scan JAR entries on the classpath
            String cp = System.getProperty("java.class.path", "");
            for (String entry : cp.split(System.getProperty("path.separator", ":"))) {
                Path p = Path.of(entry);
                if (entry.endsWith(".jar") && Files.isRegularFile(p)) {
                    scanJar(p, namespaces);
                }
            }
        } catch (Exception e) {
            // Best-effort: return what we found so far
        }
        return new ArrayList<>(namespaces);
    }

    private void scanDirectory(Path root, Path dir, Set<String> namespaces) {
        try (var stream = Files.list(dir)) {
            stream.forEach(path -> {
                if (Files.isDirectory(path)) {
                    scanDirectory(root, path, namespaces);
                } else if (path.toString().endsWith(".spn")) {
                    String relative = root.relativize(path).toString().replace('\\', '/');
                    if (relative.endsWith(".spn")) {
                        relative = relative.substring(0, relative.length() - 4);
                    }
                    namespaces.add(relative.replace('/', '.'));
                }
            });
        } catch (IOException e) {
            // Skip unreadable directories
        }
    }

    private void scanJar(Path jarPath, Set<String> namespaces) {
        try (var fs = FileSystems.newFileSystem(jarPath, (ClassLoader) null)) {
            Path root = fs.getPath("/");
            Files.walk(root)
                    .filter(p -> p.toString().endsWith(".spn"))
                    .forEach(p -> {
                        String relative = root.relativize(p).toString().replace('\\', '/');
                        if (relative.endsWith(".spn")) {
                            relative = relative.substring(0, relative.length() - 4);
                        }
                        namespaces.add(relative.replace('/', '.'));
                    });
        } catch (IOException e) {
            // Skip unreadable JARs
        }
    }
}
