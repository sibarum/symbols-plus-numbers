package spn.traction;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class KuramotoTest extends TractionTestBase {

    @Test void networkLoadsAndSingleStepRuns() {
        // Import the network module, instantiate 3 nodes, run one step,
        // confirm the resulting array has 3 elements.
        assertEquals(3L, run("""
            import kuramoto.network

            let n0 = Node(0.0, 1.0, 0.5, 10.0, 10.0, 1.0)
            let n1 = Node(1.5, 1.1, 0.5, 10.0, 10.0, 1.0)
            let n2 = Node(3.0, 0.9, 0.5, 10.0, 10.0, 1.0)
            let nodes = NodeArray().push(n0).push(n1).push(n2)

            -- Each node connected to the other two
            let adj = Adjacency()
              .push(IntArray().push(1).push(2))
              .push(IntArray().push(0).push(2))
              .push(IntArray().push(0).push(1))

            let next = stepNetwork(nodes, adj, 0.01)
            next.length()
            """));
    }

    @Test void orderParameterOfAlignedNodesIsOne() {
        // If all nodes share the same phase, ρ should be exactly 1.
        assertEquals(true, run("""
            import kuramoto.network

            let n0 = Node(0.5, 1.0, 0.5, 10.0, 10.0, 1.0)
            let n1 = Node(0.5, 1.0, 0.5, 10.0, 10.0, 1.0)
            let n2 = Node(0.5, 1.0, 0.5, 10.0, 10.0, 1.0)
            let nodes = NodeArray().push(n0).push(n1).push(n2)

            let rho = orderParameter(nodes)
            rho > 0.999 && rho < 1.001
            """));
    }
}
