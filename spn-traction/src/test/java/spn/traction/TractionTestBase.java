package spn.traction;

import org.junit.jupiter.api.BeforeEach;
import spn.lang.ClasspathModuleLoader;
import spn.lang.FilesystemModuleLoader;
import spn.lang.SpnParser;
import spn.language.SpnModuleRegistry;
import spn.node.SpnRootNode;
import spn.type.SpnSymbolTable;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Shared setup for spn-traction tests.
 *
 * <p>Each traction test wires up the same module resolution chain:
 * the generated stdlib registry, a classpath loader, and a filesystem
 * loader rooted at {@code spn-traction/} so local modules like
 * {@code numerics.rational} or {@code kuramoto.network} resolve.
 *
 * <p>Subclasses inherit {@link #symbolTable}, {@link #registry}, and
 * {@link #run(String)} — the only protected surface a test needs.
 */
abstract class TractionTestBase {

    protected SpnSymbolTable symbolTable;
    protected SpnModuleRegistry registry;

    @BeforeEach
    void setUpBase() {
        symbolTable = new SpnSymbolTable();
        registry = new SpnModuleRegistry();
        spn.stdlib.gen.StdlibModuleLoader.registerAll(registry);
        registry.addLoader(new ClasspathModuleLoader(null, symbolTable));
        Path root = Path.of(System.getProperty("user.dir"));
        if (!Files.isRegularFile(root.resolve("module.spn")))
            root = root.resolve("spn-traction");
        registry.addLoader(new FilesystemModuleLoader(
                root, "sibarum.spn.traction", null, symbolTable));
    }

    /** Parse and execute a single SPN source snippet, returning the final value. */
    protected Object run(String source) {
        SpnParser parser = new SpnParser(source, null, null, symbolTable, registry);
        SpnRootNode root = parser.parse();
        return root.getCallTarget().call();
    }
}
