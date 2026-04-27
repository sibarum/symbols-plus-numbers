package spn.stdlib.clifford;

import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.clifford.TractionRotor;
import spn.language.SpnException;
import spn.node.SpnExpressionNode;
import spn.node.builtin.SpnBuiltin;

@SpnBuiltin(name = "rotorApproxEquals", module = "Clifford",
        params = {"rotor", "other", "eps"}, returns = "Boolean",
        receiver = "TractionRotor", method = "approxEquals")
@NodeChild("rotor")
@NodeChild("other")
@NodeChild("eps")
@NodeInfo(shortName = "rotorApproxEquals")
public abstract class SpnTractionRotorApproxEqualsNode extends SpnExpressionNode {

    @Specialization
    protected boolean approxEquals(TractionRotor rotor, TractionRotor other, double eps) {
        return rotor.approxEquals(other, eps);
    }

    @Fallback
    protected Object typeError(Object rotor, Object other, Object eps) {
        throw new SpnException("approxEquals expects (TractionRotor, TractionRotor, Double)", this);
    }
}
