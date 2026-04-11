package spn.stdlib.math;

import com.oracle.truffle.api.nodes.NodeInfo;
import spn.node.SpnExpressionNode;
import spn.node.builtin.SpnBuiltin;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Returns a random double in [0.0, 1.0).
 */
@SpnBuiltin(name = "random", module = "Math", params = {}, returns = "Double")
@NodeInfo(shortName = "random")
public class SpnRandomNode extends SpnExpressionNode {

    @Override
    public Object executeGeneric(com.oracle.truffle.api.frame.VirtualFrame frame) {
        return ThreadLocalRandom.current().nextDouble();
    }
}
