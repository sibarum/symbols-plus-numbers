package spn.stdlib.clifford;

import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.clifford.TractionRotor;
import spn.language.SpnException;
import spn.node.SpnExpressionNode;
import spn.node.builtin.SpnBuiltin;

@SpnBuiltin(name = "rotorAdd", module = "Clifford",
        params = {"rotor", "other"}, returns = "TractionRotor",
        receiver = "TractionRotor", method = "add")
@NodeChild("rotor")
@NodeChild("other")
@NodeInfo(shortName = "rotorAdd")
public abstract class SpnTractionRotorAddNode extends SpnExpressionNode {

    @Specialization
    protected TractionRotor add(TractionRotor rotor, TractionRotor other) {
        return rotor.add(other);
    }

    @Fallback
    protected Object typeError(Object rotor, Object other) {
        throw new SpnException("add expects (TractionRotor, TractionRotor)", this);
    }
}
