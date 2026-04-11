package spn.stdlib.string;

import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.node.SpnExpressionNode;
import spn.node.builtin.SpnBuiltin;

@SpnBuiltin(name = "endsWith", module = "String", params = {"str", "suffix"}, returns = "Boolean")
@NodeChild("str")
@NodeChild("suffix")
@NodeInfo(shortName = "endsWith")
public abstract class SpnStringEndsWithNode extends SpnExpressionNode {

    @Specialization
    protected boolean endsWith(String str, String suffix) {
        return str.endsWith(suffix);
    }
}
