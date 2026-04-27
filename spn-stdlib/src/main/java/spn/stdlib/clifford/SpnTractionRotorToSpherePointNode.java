package spn.stdlib.clifford;

import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.clifford.TractionRotor;
import spn.language.SpnException;
import spn.node.SpnExpressionNode;
import spn.node.builtin.SpnBuiltin;
import spn.type.FieldType;
import spn.type.SpnArrayValue;

@SpnBuiltin(name = "rotorToSpherePoint", module = "Clifford",
        params = {"rotor"}, returns = "Array",
        receiver = "TractionRotor", method = "toSpherePoint")
@NodeChild("rotor")
@NodeInfo(shortName = "rotorToSpherePoint")
public abstract class SpnTractionRotorToSpherePointNode extends SpnExpressionNode {

    @Specialization
    protected SpnArrayValue toSpherePoint(TractionRotor rotor) {
        double[] xyz = rotor.toSpherePoint();
        return new SpnArrayValue(FieldType.DOUBLE, xyz[0], xyz[1], xyz[2]);
    }

    @Fallback
    protected Object typeError(Object rotor) {
        throw new SpnException("toSpherePoint expects a TractionRotor", this);
    }
}
