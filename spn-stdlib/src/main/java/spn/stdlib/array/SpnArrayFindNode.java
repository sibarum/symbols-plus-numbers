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
import spn.type.SpnStructValue;
import spn.stdlib.option.SpnOptionDescriptors;

/**
 * Finds the first element satisfying a predicate. Returns Option: Some(value) or None.
 *
 * <pre>
 *   find([1, 2, 3], isEven) -> Some(2)
 *   find([1, 3, 5], isEven) -> None
 * </pre>
 */
@SpnBuiltin(name = "find", module = "Array", params = {"array"}, returns = "Option", receiver = "Array")
@SpnParamHint(name = "predicate", function = true)
@NodeChild("array")
@NodeInfo(shortName = "find")
public abstract class SpnArrayFindNode extends SpnExpressionNode {

    @Child private DirectCallNode callNode;

    protected SpnArrayFindNode(CallTarget predicate) {
        this.callNode = Truffle.getRuntime().createDirectCallNode(predicate);
    }

    @Specialization
    protected SpnStructValue find(SpnArrayValue array) {
        for (Object element : array.getElements()) {
            Object result = callNode.call(element);
            if (result instanceof Boolean b && b) {
                return SpnOptionDescriptors.some(element);
            }
        }
        return SpnOptionDescriptors.none();
    }

    @Fallback
    protected Object typeError(Object value) {
        throw new SpnException("find expects an array, got: "
                + value.getClass().getSimpleName(), this);
    }
}
