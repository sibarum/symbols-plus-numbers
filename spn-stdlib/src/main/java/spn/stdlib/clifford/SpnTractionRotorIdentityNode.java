package spn.stdlib.clifford;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.clifford.TractionRotor;
import spn.node.SpnExpressionNode;
import spn.node.builtin.SpnBuiltin;

@SpnBuiltin(name = "tractionRotorIdentity", module = "Clifford",
        params = {}, returns = "TractionRotor")
@NodeInfo(shortName = "tractionRotorIdentity")
public class SpnTractionRotorIdentityNode extends SpnExpressionNode {

    @Override
    public Object executeGeneric(VirtualFrame frame) {
        return TractionRotor.identity();
    }
}
