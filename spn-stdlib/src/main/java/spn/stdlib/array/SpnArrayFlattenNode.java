package spn.stdlib.array;

import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.language.SpnException;
import spn.node.SpnExpressionNode;
import spn.node.builtin.SpnBuiltin;
import spn.type.FieldType;
import spn.type.SpnArrayValue;

import java.util.ArrayList;

/**
 * Flattens a nested array one level: flatten([[1, 2], [3]]) -> [1, 2, 3]
 */
@SpnBuiltin(name = "flatten", module = "Array", params = {"array"}, returns = "Array")
@NodeChild("array")
@NodeInfo(shortName = "flatten")
public abstract class SpnArrayFlattenNode extends SpnExpressionNode {

    @Specialization
    protected SpnArrayValue flatten(SpnArrayValue array) {
        var result = new ArrayList<>();
        for (Object element : array.getElements()) {
            if (element instanceof SpnArrayValue inner) {
                for (Object e : inner.getElements()) {
                    result.add(e);
                }
            } else {
                result.add(element);
            }
        }
        return new SpnArrayValue(FieldType.UNTYPED, result.toArray());
    }

    @Fallback
    protected Object typeError(Object value) {
        throw new SpnException("flatten expects an array, got: "
                + value.getClass().getSimpleName(), this);
    }
}
