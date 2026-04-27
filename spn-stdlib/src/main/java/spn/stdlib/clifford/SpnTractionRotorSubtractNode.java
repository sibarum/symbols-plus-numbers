package spn.stdlib.clifford;

import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.clifford.TractionRotor;
import spn.language.SpnException;
import spn.node.SpnExpressionNode;
import spn.node.builtin.SpnBuiltin;

@SpnBuiltin(name = "rotorSubtract", module = "Clifford",
        params = {"rotor", "other"}, returns = "TractionRotor",
        receiver = "TractionRotor", method = "subtract")
@NodeChild("rotor")
@NodeChild("other")
@NodeInfo(shortName = "rotorSubtract")
public abstract class SpnTractionRotorSubtractNode extends SpnExpressionNode {

    @Specialization
    protected TractionRotor subtract(TractionRotor rotor, TractionRotor other) {
        return rotor.subtract(other);
    }

    @Fallback
    protected Object typeError(Object rotor, Object other) {
        throw new SpnException("subtract expects (TractionRotor, TractionRotor)", this);
    }
}
