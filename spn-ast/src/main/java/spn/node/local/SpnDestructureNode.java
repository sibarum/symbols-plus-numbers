package spn.node.local;

import spn.language.SpnTypeName;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import spn.node.SpnExpressionNode;
import spn.node.SpnStatementNode;
import spn.type.SpnArrayValue;
import spn.type.SpnStructValue;
import spn.type.SpnProductValue;
import spn.type.SpnTupleValue;

/**
 * Destructures a struct, product, tuple, or array value into local variable slots.
 *
 * <pre>
 *   let Complex(real, imag) = expr   // struct
 *   let (a, b) = someTuple           // tuple
 *   let (x, y) = someArray           // array
 * </pre>
 *
 * Evaluates the expression, extracts fields/elements by index, and writes each
 * to the corresponding frame slot. Slots with value -1 are skipped (_).
 */
public final class SpnDestructureNode extends SpnStatementNode {

    @Child private SpnExpressionNode valueNode;

    @CompilationFinal(dimensions = 1)
    private final int[] bindingSlots;

    public SpnDestructureNode(SpnExpressionNode valueNode, int[] bindingSlots) {
        this.valueNode = valueNode;
        this.bindingSlots = bindingSlots;
    }

    @Override
    @ExplodeLoop
    public void executeVoid(VirtualFrame frame) {
        Object value = valueNode.executeGeneric(frame);

        Object[] fields;
        if (value instanceof SpnStructValue sv) {
            fields = sv.getFields();
        } else if (value instanceof SpnProductValue pv) {
            fields = pv.getComponents();
        } else if (value instanceof SpnTupleValue tv) {
            fields = tv.getElements();
        } else if (value instanceof SpnArrayValue av) {
            fields = av.getElements();
        } else {
            throw new IllegalArgumentException(
                    "Cannot destructure value of type " + SpnTypeName.of(value));
        }

        for (int i = 0; i < bindingSlots.length && i < fields.length; i++) {
            if (bindingSlots[i] >= 0) {
                frame.setObject(bindingSlots[i], fields[i]);
            }
        }
    }
}
