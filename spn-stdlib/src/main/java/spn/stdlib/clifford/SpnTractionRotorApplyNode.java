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

@SpnBuiltin(name = "rotorApply", module = "Clifford",
        params = {"rotor", "v"}, returns = "Array",
        receiver = "TractionRotor", method = "apply")
@NodeChild("rotor")
@NodeChild("v")
@NodeInfo(shortName = "rotorApply")
public abstract class SpnTractionRotorApplyNode extends SpnExpressionNode {

    @Specialization
    protected SpnArrayValue apply(TractionRotor rotor, SpnArrayValue v) {
        if (v.length() != 3) {
            throw new SpnException("apply expects a 3-vector, got length " + v.length(), this);
        }
        double[] in = new double[] {
                ((Number) v.get(0)).doubleValue(),
                ((Number) v.get(1)).doubleValue(),
                ((Number) v.get(2)).doubleValue(),
        };
        double[] out = rotor.apply(in);
        return new SpnArrayValue(FieldType.DOUBLE, out[0], out[1], out[2]);
    }

    @Fallback
    protected Object typeError(Object rotor, Object v) {
        throw new SpnException("apply expects (TractionRotor, Array)", this);
    }
}
