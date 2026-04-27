package spn.stdlib.clifford;

import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.clifford.TractionRotor;
import spn.language.SpnException;
import spn.node.SpnExpressionNode;
import spn.node.builtin.SpnBuiltin;

@SpnBuiltin(name = "rotorMultiply", module = "Clifford",
        params = {"rotor", "other"}, returns = "TractionRotor",
        receiver = "TractionRotor", method = "multiply")
@NodeChild("rotor")
@NodeChild("other")
@NodeInfo(shortName = "rotorMultiply")
public abstract class SpnTractionRotorMultiplyNode extends SpnExpressionNode {

    @Specialization
    protected TractionRotor multiply(TractionRotor rotor, TractionRotor other) {
        return rotor.multiply(other);
    }

    @Fallback
    protected Object typeError(Object rotor, Object other) {
        throw new SpnException("multiply expects (TractionRotor, TractionRotor)", this);
    }
}
