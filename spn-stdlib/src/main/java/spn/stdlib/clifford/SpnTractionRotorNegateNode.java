package spn.stdlib.clifford;

import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.clifford.TractionRotor;
import spn.language.SpnException;
import spn.node.SpnExpressionNode;
import spn.node.builtin.SpnBuiltin;

@SpnBuiltin(name = "rotorNegate", module = "Clifford",
        params = {"rotor"}, returns = "TractionRotor",
        receiver = "TractionRotor", method = "negate")
@NodeChild("rotor")
@NodeInfo(shortName = "rotorNegate")
public abstract class SpnTractionRotorNegateNode extends SpnExpressionNode {

    @Specialization
    protected TractionRotor negate(TractionRotor rotor) {
        return rotor.negate();
    }

    @Fallback
    protected Object typeError(Object rotor) {
        throw new SpnException("negate expects a TractionRotor", this);
    }
}
