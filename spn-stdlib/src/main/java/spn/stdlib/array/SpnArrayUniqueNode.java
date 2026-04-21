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
import java.util.LinkedHashSet;

/**
 * Returns an array with duplicate elements removed, preserving first occurrence order.
 */
@SpnBuiltin(name = "unique", module = "Array", params = {"array"}, returns = "Array", receiver = "Array")
@NodeChild("array")
@NodeInfo(shortName = "unique")
public abstract class SpnArrayUniqueNode extends SpnExpressionNode {

    @Specialization
    protected SpnArrayValue unique(SpnArrayValue array) {
        var seen = new LinkedHashSet<>();
        for (Object e : array.getElements()) seen.add(e);
        return new SpnArrayValue(FieldType.UNTYPED, seen.toArray());
    }

    @Fallback
    protected Object typeError(Object value) {
        throw new SpnException("unique expects an array", this);
    }
}
