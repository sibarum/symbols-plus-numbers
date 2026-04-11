package spn.stdlib.general;

import com.oracle.truffle.api.nodes.NodeInfo;
import spn.node.SpnExpressionNode;
import spn.node.builtin.SpnBuiltin;

import java.util.UUID;

/**
 * Generates a random UUID v4 string.
 */
@SpnBuiltin(name = "uuid", module = "General", params = {}, returns = "String")
@NodeInfo(shortName = "uuid")
public class SpnUuidNode extends SpnExpressionNode {

    @Override
    public Object executeGeneric(com.oracle.truffle.api.frame.VirtualFrame frame) {
        return UUID.randomUUID().toString();
    }
}
