package spn.stdlib.option;

import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.node.SpnExpressionNode;
import spn.node.builtin.SpnBuiltin;
import spn.type.SpnStructValue;

@SpnBuiltin(name = "isSome", module = "Option", params = {"option"}, returns = "Boolean")
@NodeChild("option")
@NodeInfo(shortName = "isSome")
public abstract class SpnIsSomeNode extends SpnExpressionNode {

    @Specialization
    protected boolean isSome(SpnStructValue sv) {
        return sv.getDescriptor() == SpnOptionDescriptors.SOME;
    }

    @Fallback
    protected boolean notOption(Object value) {
        return false;
    }
}
