package spn.stdlib.io;

import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.node.SpnExpressionNode;
import spn.node.builtin.SpnBuiltin;

@SpnBuiltin(name = "println", module = "IO", params = {"value"}, returns = "Long", pure = false)
@NodeChild("value")
@NodeInfo(shortName = "println")
public abstract class SpnPrintlnNode extends SpnExpressionNode {

    @Specialization
    protected long println(Object value) {
        System.out.println(value);
        return 0L;
    }
}
