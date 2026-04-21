package spn.stdlib.option;

import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.node.SpnExpressionNode;
import spn.node.builtin.SpnBuiltin;
import spn.type.SpnStructValue;

@SpnBuiltin(name = "isNone", module = "Option", params = {"option"}, returns = "Boolean", receiver = "Option")
@NodeChild("option")
@NodeInfo(shortName = "isNone")
public abstract class SpnIsNoneNode extends SpnExpressionNode {

    @Specialization
    protected boolean isNone(SpnStructValue sv) {
        return sv.getDescriptor() == SpnOptionDescriptors.NONE;
    }

    @Fallback
    protected boolean notOption(Object value) {
        return false;
    }
}
