package spn.traction;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import spn.lang.ClasspathModuleLoader;
import spn.lang.FilesystemModuleLoader;
import spn.lang.SpnParser;
import spn.language.SpnModuleRegistry;
import spn.node.SpnRootNode;
import spn.type.SpnSymbolTable;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class KuramotoTest {

    private SpnSymbolTable symbolTable;
    private SpnModuleRegistry registry;

    @BeforeEach
    void setUp() {
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

    private Object run(String source) {
        SpnParser parser = new SpnParser(source, null, null, symbolTable, registry);
        SpnRootNode root = parser.parse();
        return root.getCallTarget().call();
    }

    @Test void networkLoadsAndSingleStepRuns() {
        // Import the network module, instantiate 3 nodes, run one step,
        // confirm the resulting array has 3 elements.
        assertEquals(3L, run("""
            import kuramoto.network
            import Array (length, append)

            let n0 = Node(0.0, 1.0, 0.5, 10.0, 10.0, 1.0)
            let n1 = Node(1.5, 1.1, 0.5, 10.0, 10.0, 1.0)
            let n2 = Node(3.0, 0.9, 0.5, 10.0, 10.0, 1.0)
            let nodes = append(append(append([], n0), n1), n2)

            -- Each node connected to the other two
            let adj = append(append(append([],
                append(append([], 1), 2)),
                append(append([], 0), 2)),
                append(append([], 0), 1))

            let next = stepNetwork(nodes, adj, 0.01)
            length(next)
            """));
    }

    @Test void orderParameterOfAlignedNodesIsOne() {
        // If all nodes share the same phase, ρ should be exactly 1.
        assertEquals(true, run("""
            import kuramoto.network
            import Array (append)

            let n0 = Node(0.5, 1.0, 0.5, 10.0, 10.0, 1.0)
            let n1 = Node(0.5, 1.0, 0.5, 10.0, 10.0, 1.0)
            let n2 = Node(0.5, 1.0, 0.5, 10.0, 10.0, 1.0)
            let nodes = append(append(append([], n0), n1), n2)

            let rho = orderParameter(nodes)
            rho > 0.999 && rho < 1.001
            """));
    }
}
