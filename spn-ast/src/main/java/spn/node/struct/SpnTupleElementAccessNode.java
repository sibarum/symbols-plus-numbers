package spn.node.struct;

import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.NodeField;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.language.SpnException;
import spn.node.SpnExpressionNode;
import spn.type.SpnTupleValue;

/**
 * Reads an element from a SpnTupleValue by position, with type specialization.
 *
 * <pre>
 *   // Access element 0 of a tuple
 *   var access = SpnTupleElementAccessNodeGen.create(readTuple, 0);
 * </pre>
 */
@NodeChild("tuple")
@NodeField(name = "elementIndex", type = int.class)
@NodeInfo(shortName = "tupleAccess")
public abstract class SpnTupleElementAccessNode extends SpnExpressionNode {

    protected abstract int getElementIndex();

    protected static boolean isLong(SpnTupleValue tv, int index) {
        return tv.get(index) instanceof Long;
    }

    protected static boolean isDouble(SpnTupleValue tv, int index) {
        return tv.get(index) instanceof Double;
    }

    @Specialization(guards = "isLong(tuple, getElementIndex())")
    protected long accessLong(SpnTupleValue tuple) {
        return (long) tuple.get(getElementIndex());
    }

    @Specialization(guards = "isDouble(tuple, getElementIndex())")
    protected double accessDouble(SpnTupleValue tuple) {
        return (double) tuple.get(getElementIndex());
    }

    @Specialization(replaces = {"accessLong", "accessDouble"})
    protected Object accessGeneric(SpnTupleValue tuple) {
        return tuple.get(getElementIndex());
    }

    @Fallback
    protected Object notTuple(Object value) {
        throw new SpnException("Expected a tuple value, got: "
                + value.getClass().getSimpleName(), this);
    }
}
