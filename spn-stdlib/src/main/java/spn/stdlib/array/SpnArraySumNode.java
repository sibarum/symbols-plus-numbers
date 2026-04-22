package spn.stdlib.array;

import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.language.SpnException;
import spn.node.SpnExpressionNode;
import spn.node.builtin.SpnBuiltin;
import spn.type.SpnArrayValue;

@SpnBuiltin(name = "sum", module = "Array", params = {"array"}, returns = "Double", receiver = "UntypedArray")
@NodeChild("array")
@NodeInfo(shortName = "sum")
public abstract class SpnArraySumNode extends SpnExpressionNode {

    @Specialization
    protected double sum(SpnArrayValue array) {
        double total = 0;
        for (Object e : array.getElements()) {
            if (e instanceof Long l) total += l;
            else if (e instanceof Double d) total += d;
            else throw new SpnException("sum: non-numeric element " + e, this);
        }
        return total;
    }

    @Fallback
    protected Object typeError(Object value) {
        throw new SpnException("sum expects an array", this);
    }
}
