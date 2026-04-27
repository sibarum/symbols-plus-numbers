package spn.stdlib.clifford;

import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.clifford.TractionRotor;
import spn.language.SpnException;
import spn.node.SpnExpressionNode;
import spn.node.builtin.SpnBuiltin;

@SpnBuiltin(name = "rotorHasAngles", module = "Clifford",
        params = {"rotor"}, returns = "Boolean",
        receiver = "TractionRotor", method = "hasAngles")
@NodeChild("rotor")
@NodeInfo(shortName = "rotorHasAngles")
public abstract class SpnTractionRotorHasAnglesNode extends SpnExpressionNode {

    @Specialization
    protected boolean hasAngles(TractionRotor rotor) {
        return rotor.hasAngles();
    }

    @Fallback
    protected Object typeError(Object rotor) {
        throw new SpnException("hasAngles expects a TractionRotor", this);
    }
}
