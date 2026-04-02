package spn.node.local;

import com.oracle.truffle.api.dsl.NodeField;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.node.SpnExpressionNode;

/**
 * Reads a local variable from the current frame.
 *
 * KEY TRUFFLE CONCEPT: Frame-based variable access with type specialization
 *
 * Local variables live in the VirtualFrame. Each variable has an integer slot index
 * (assigned when building the FrameDescriptor during parsing). The frame tracks a
 * "tag" for each slot indicating its current type (long, double, boolean, object, etc.).
 *
 * We specialize on the frame slot's tag:
 *   - If the slot holds a long, read it as a raw long (no boxing)
 *   - If it holds a double, read it as a raw double
 *   - If it holds a boolean, read it as a raw boolean
 *   - Otherwise, fall back to reading as Object (boxed)
 *
 * KEY TRUFFLE CONCEPT: @NodeField
 * Declares a field that's set at construction time and is compilation-final.
 * The DSL generates the field, the getter, and includes it in the factory method:
 *   SpnReadLocalVariableNodeGen.create(slotIndex)
 *
 * The "guards" parameter uses a frame method to check the slot tag at runtime.
 * These guards are cheap: in compiled code, after the first few reads establish
 * a stable type, the guard becomes a no-op because Graal proves it always true.
 */
@NodeField(name = "slot", type = int.class)
@NodeInfo(shortName = "readLocal")
public abstract class SpnReadLocalVariableNode extends SpnExpressionNode {

    protected abstract int getSlot();

    @Specialization(guards = "frame.isLong(getSlot())")
    protected long readLong(VirtualFrame frame) {
        return frame.getLong(getSlot());
    }

    @Specialization(guards = "frame.isDouble(getSlot())")
    protected double readDouble(VirtualFrame frame) {
        return frame.getDouble(getSlot());
    }

    @Specialization(guards = "frame.isBoolean(getSlot())")
    protected boolean readBoolean(VirtualFrame frame) {
        return frame.getBoolean(getSlot());
    }

    @Specialization(replaces = {"readLong", "readDouble", "readBoolean"})
    protected Object readObject(VirtualFrame frame) {
        return frame.getObject(getSlot());
    }
}
