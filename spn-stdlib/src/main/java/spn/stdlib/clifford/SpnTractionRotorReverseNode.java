package spn.stdlib.clifford;

import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.clifford.TractionRotor;
import spn.language.SpnException;
import spn.node.SpnExpressionNode;
import spn.node.builtin.SpnBuiltin;

@SpnBuiltin(name = "rotorReverse", module = "Clifford",
        params = {"rotor"}, returns = "TractionRotor",
        receiver = "TractionRotor", method = "reverse")
@NodeChild("rotor")
@NodeInfo(shortName = "rotorReverse")
public abstract class SpnTractionRotorReverseNode extends SpnExpressionNode {

    @Specialization
    protected TractionRotor reverse(TractionRotor rotor) {
        return rotor.reverse();
    }

    @Fallback
    protected Object typeError(Object rotor) {
        throw new SpnException("reverse expects a TractionRotor", this);
    }
}
