package spn.stdlib.clifford;

import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.clifford.TractionRotor;
import spn.language.SpnException;
import spn.node.SpnExpressionNode;
import spn.node.builtin.SpnBuiltin;

@SpnBuiltin(name = "rotorDot", module = "Clifford",
        params = {"rotor", "other"}, returns = "Double",
        receiver = "TractionRotor", method = "dot")
@NodeChild("rotor")
@NodeChild("other")
@NodeInfo(shortName = "rotorDot")
public abstract class SpnTractionRotorDotNode extends SpnExpressionNode {

    @Specialization
    protected double dot(TractionRotor rotor, TractionRotor other) {
        return rotor.dot(other);
    }

    @Fallback
    protected Object typeError(Object rotor, Object other) {
        throw new SpnException("dot expects (TractionRotor, TractionRotor)", this);
    }
}
