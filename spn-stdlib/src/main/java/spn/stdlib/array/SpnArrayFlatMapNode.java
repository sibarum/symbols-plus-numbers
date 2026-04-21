package spn.stdlib.array;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.language.SpnException;
import spn.node.SpnExpressionNode;
import spn.node.builtin.SpnBuiltin;
import spn.node.builtin.SpnParamHint;
import spn.type.FieldType;
import spn.type.SpnArrayValue;

import java.util.ArrayList;

/**
 * Maps each element to an array, then flattens one level.
 * flatMap([1, 2, 3], fn) where fn(x) -> [x, x*2] gives [1, 2, 2, 4, 3, 6]
 */
@SpnBuiltin(name = "arrayFlatMap", module = "Array", params = {"array"}, returns = "Array", receiver = "Array", method = "flatMap")
@SpnParamHint(name = "function", function = true)
@NodeChild("array")
@NodeInfo(shortName = "flatMap")
public abstract class SpnArrayFlatMapNode extends SpnExpressionNode {

    @Child private DirectCallNode callNode;

    protected SpnArrayFlatMapNode(CallTarget function) {
        this.callNode = Truffle.getRuntime().createDirectCallNode(function);
    }

    @Specialization
    protected SpnArrayValue flatMap(SpnArrayValue array) {
        var result = new ArrayList<>();
        for (Object element : array.getElements()) {
            Object mapped = callNode.call(element);
            if (mapped instanceof SpnArrayValue inner) {
                for (Object e : inner.getElements()) result.add(e);
            } else {
                result.add(mapped);
            }
        }
        return new SpnArrayValue(FieldType.UNTYPED, result.toArray());
    }

    @Fallback
    protected Object typeError(Object value) {
        throw new SpnException("flatMap expects an array", this);
    }
}
