package spn.stdlib.clifford;

import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.clifford.TractionRotor;
import spn.language.SpnException;
import spn.node.SpnExpressionNode;
import spn.node.builtin.SpnBuiltin;

@SpnBuiltin(name = "rotorToTractionExpression", module = "Clifford",
        params = {"rotor"}, returns = "String",
        receiver = "TractionRotor", method = "toTractionExpression")
@NodeChild("rotor")
@NodeInfo(shortName = "rotorToTractionExpression")
public abstract class SpnTractionRotorToTractionExpressionNode extends SpnExpressionNode {

    @Specialization
    protected String toTractionExpression(TractionRotor rotor) {
        return rotor.toTractionExpression();
    }

    @Fallback
    protected Object typeError(Object rotor) {
        throw new SpnException("toTractionExpression expects a TractionRotor", this);
    }
}
