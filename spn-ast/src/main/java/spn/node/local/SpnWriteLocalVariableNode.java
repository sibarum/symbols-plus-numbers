package spn.node.local;

import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.NodeField;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.node.SpnExpressionNode;

/**
 * Writes a value to a local variable in the current frame.
 * The write is also an expression -- it evaluates to the written value (like x = 5 returning 5).
 *
 * KEY TRUFFLE CONCEPT: Combined @NodeChild + @NodeField
 *
 * @NodeChild("valueNode") - the expression whose result we're storing
 * @NodeField("slot")      - the frame slot index to write to
 *
 * The generated factory method is:
 *   SpnWriteLocalVariableNodeGen.create(valueExpressionNode, slotIndex)
 *
 * Type specialization on writes:
 * The DSL dispatches based on the runtime type of the value produced by the child.
 * If the child produces a long, we call frame.setLong() which avoids boxing.
 * The frame's internal tag for that slot is updated accordingly, so subsequent
 * reads (SpnReadLocalVariableNode) will see the correct tag in their guards.
 *
 * VirtualFrame as a specialization parameter:
 * Even with @NodeChild, you can include VirtualFrame as the first parameter of any
 * @Specialization method. The DSL recognizes it and passes the current frame through
 * rather than treating it as a child value.
 *
 * NOTE: In a production implementation, you would add guards like
 *   @Specialization(guards = "isLongOrIllegal(frame, getSlot())")
 * to prevent writing a long to a slot that was previously widened to double,
 * which would cause unnecessary deoptimization on the read side. Omitted here
 * for clarity.
 */
@NodeChild("valueNode")
@NodeField(name = "slot", type = int.class)
@NodeInfo(shortName = "writeLocal")
public abstract class SpnWriteLocalVariableNode extends SpnExpressionNode {

    protected abstract int getSlot();

    @Specialization
    protected long writeLong(VirtualFrame frame, long value) {
        frame.setLong(getSlot(), value);
        return value;
    }

    @Specialization
    protected double writeDouble(VirtualFrame frame, double value) {
        frame.setDouble(getSlot(), value);
        return value;
    }

    @Specialization
    protected boolean writeBoolean(VirtualFrame frame, boolean value) {
        frame.setBoolean(getSlot(), value);
        return value;
    }

    @Specialization(replaces = {"writeLong", "writeDouble", "writeBoolean"})
    protected Object writeObject(VirtualFrame frame, Object value) {
        frame.setObject(getSlot(), value);
        return value;
    }
}
