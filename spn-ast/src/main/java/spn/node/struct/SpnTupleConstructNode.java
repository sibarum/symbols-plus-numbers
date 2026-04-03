package spn.node.struct;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.language.SpnException;
import spn.node.SpnExpressionNode;
import spn.type.FieldType;
import spn.type.SpnTupleDescriptor;
import spn.type.SpnTupleValue;

/**
 * Constructs a SpnTupleValue from positional expression nodes, with type validation.
 *
 * Each child evaluates to one element of the tuple. If the tuple descriptor
 * specifies types for any position, the values are validated at runtime.
 *
 * <pre>
 *   // (Long, _, Double) tuple
 *   var desc = new SpnTupleDescriptor(FieldType.LONG, FieldType.UNTYPED, FieldType.DOUBLE);
 *   var node = new SpnTupleConstructNode(desc,
 *       new SpnLongLiteralNode(42),
 *       new SpnStringLiteralNode("any"),
 *       new SpnDoubleLiteralNode(3.14));
 * </pre>
 */
@NodeInfo(shortName = "tupleConstruct")
public final class SpnTupleConstructNode extends SpnExpressionNode {

    @Children private final SpnExpressionNode[] elementNodes;
    private final SpnTupleDescriptor descriptor;

    @CompilationFinal(dimensions = 1)
    private final FieldType[] elementTypes;

    @CompilationFinal
    private final boolean needsValidation;

    public SpnTupleConstructNode(SpnTupleDescriptor descriptor,
                                 SpnExpressionNode... elementNodes) {
        this.descriptor = descriptor;
        this.elementNodes = elementNodes;
        this.elementTypes = descriptor.getElementTypes();
        this.needsValidation = descriptor.hasTypedPositions();
    }

    @Override
    @ExplodeLoop
    public Object executeGeneric(VirtualFrame frame) {
        Object[] values = new Object[elementNodes.length];
        for (int i = 0; i < elementNodes.length; i++) {
            values[i] = elementNodes[i].executeGeneric(frame);
        }

        if (needsValidation) {
            validateElements(values);
        }

        return new SpnTupleValue(descriptor, values);
    }

    @ExplodeLoop
    private void validateElements(Object[] values) {
        for (int i = 0; i < elementTypes.length; i++) {
            if (!elementTypes[i].accepts(values[i])) {
                throw new SpnException(
                        "Tuple position " + i + " expects " + elementTypes[i].describe()
                                + ", got " + values[i].getClass().getSimpleName()
                                + " (" + values[i] + ")",
                        this);
            }
        }
    }
}
