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

import java.util.*;

/**
 * Groups array elements by a key function.
 * groupBy([1, 2, 3, 4], isEven) -> {:true [2, 4], :false [1, 3]}
 */
@SpnBuiltin(name = "groupBy", module = "Array", params = {"array"}, returns = "Array", receiver = "UntypedArray")
@SpnParamHint(name = "keyFn", function = true)
@NodeChild("array")
@NodeInfo(shortName = "groupBy")
public abstract class SpnArrayGroupByNode extends SpnExpressionNode {

    @Child private DirectCallNode callNode;

    protected SpnArrayGroupByNode(CallTarget keyFn) {
        this.callNode = Truffle.getRuntime().createDirectCallNode(keyFn);
    }

    @Specialization
    protected SpnArrayValue groupBy(SpnArrayValue array) {
        var groups = new LinkedHashMap<Object, List<Object>>();
        for (Object element : array.getElements()) {
            Object key = callNode.call(element);
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(element);
        }
        // Return array of [key, elements] pairs
        Object[] pairs = new Object[groups.size()];
        int i = 0;
        for (var entry : groups.entrySet()) {
            pairs[i++] = new SpnArrayValue(FieldType.UNTYPED,
                    entry.getKey(), new SpnArrayValue(FieldType.UNTYPED, entry.getValue().toArray()));
        }
        return new SpnArrayValue(FieldType.UNTYPED, pairs);
    }

    @Fallback
    protected Object typeError(Object value) {
        throw new SpnException("groupBy expects an array", this);
    }
}
