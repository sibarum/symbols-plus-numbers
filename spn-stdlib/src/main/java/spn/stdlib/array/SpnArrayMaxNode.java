package spn.stdlib.array;

import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.language.SpnException;
import spn.node.SpnExpressionNode;
import spn.node.builtin.SpnBuiltin;
import spn.type.SpnArrayValue;

@SpnBuiltin(name = "arrayMax", module = "Array", params = {"array"}, receiver = "Array", method = "max")
@NodeChild("array")
@NodeInfo(shortName = "arrayMax")
public abstract class SpnArrayMaxNode extends SpnExpressionNode {

    @Specialization
    protected Object max(SpnArrayValue array) {
        Object[] elems = array.getElements();
        if (elems.length == 0) throw new SpnException("arrayMax: empty array", this);
        double best = toDouble(elems[0]);
        Object bestObj = elems[0];
        for (int i = 1; i < elems.length; i++) {
            double v = toDouble(elems[i]);
            if (v > best) { best = v; bestObj = elems[i]; }
        }
        return bestObj;
    }

    private double toDouble(Object o) {
        if (o instanceof Long l) return l;
        if (o instanceof Double d) return d;
        throw new SpnException("arrayMax: non-numeric element " + o, this);
    }

    @Fallback
    protected Object typeError(Object value) {
        throw new SpnException("arrayMax expects an array", this);
    }
}
