package spn.node.struct;

import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.NodeField;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.language.SpnException;
import spn.node.SpnExpressionNode;
import spn.type.SpnStructValue;

/**
 * Reads a field from a SpnStructValue by index, with type specialization.
 *
 * Usage (what a parser would produce for "myCircle.radius"):
 * <pre>
 *   // circleDesc.fieldIndex("radius") == 0
 *   var accessRadius = SpnFieldAccessNodeGen.create(readMyCircle, 0);
 * </pre>
 *
 * Type-specialized paths avoid boxing when the field type is stable:
 * if a Circle's radius is always a Double, the compiled code reads it
 * as a raw double without any type checking overhead.
 */
@NodeChild("struct")
@NodeField(name = "fieldIndex", type = int.class)
@NodeInfo(shortName = "fieldAccess")
public abstract class SpnFieldAccessNode extends SpnExpressionNode {

    protected abstract int getFieldIndex();

    protected static boolean isLong(SpnStructValue sv, int index) {
        return sv.get(index) instanceof Long;
    }

    protected static boolean isDouble(SpnStructValue sv, int index) {
        return sv.get(index) instanceof Double;
    }

    protected static boolean isBoolean(SpnStructValue sv, int index) {
        return sv.get(index) instanceof Boolean;
    }

    @Specialization(guards = "isLong(struct, getFieldIndex())")
    protected long accessLong(SpnStructValue struct) {
        return (long) struct.get(getFieldIndex());
    }

    @Specialization(guards = "isDouble(struct, getFieldIndex())")
    protected double accessDouble(SpnStructValue struct) {
        return (double) struct.get(getFieldIndex());
    }

    @Specialization(guards = "isBoolean(struct, getFieldIndex())")
    protected boolean accessBoolean(SpnStructValue struct) {
        return (boolean) struct.get(getFieldIndex());
    }

    @Specialization(replaces = {"accessLong", "accessDouble", "accessBoolean"})
    protected Object accessGeneric(SpnStructValue struct) {
        return struct.get(getFieldIndex());
    }

    @Fallback
    protected Object notStruct(Object value) {
        throw new SpnException("Expected a struct value, got: "
                + value.getClass().getSimpleName(), this);
    }
}
