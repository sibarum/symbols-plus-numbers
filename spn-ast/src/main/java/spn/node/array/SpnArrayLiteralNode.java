package spn.node.array;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.language.SpnException;
import spn.node.SpnExpressionNode;
import spn.type.FieldType;
import spn.type.SpnArrayValue;

/**
 * Constructs an immutable SpnArrayValue from element expression nodes.
 *
 * If the element type is concrete (not Untyped), each element is validated
 * at construction time. The needsValidation flag is @CompilationFinal.
 *
 * <pre>
 *   // Typed: [1, 2, 3] : Array<Long>
 *   new SpnArrayLiteralNode(FieldType.LONG,
 *       new SpnLongLiteralNode(1),
 *       new SpnLongLiteralNode(2),
 *       new SpnLongLiteralNode(3));
 *
 *   // Untyped: [1, "hello", true]
 *   new SpnArrayLiteralNode(FieldType.UNTYPED,
 *       new SpnLongLiteralNode(1),
 *       new SpnStringLiteralNode("hello"),
 *       new SpnBooleanLiteralNode(true));
 * </pre>
 */
@NodeInfo(shortName = "arrayLiteral")
public final class SpnArrayLiteralNode extends SpnExpressionNode {

    @Children private final SpnExpressionNode[] elementNodes;

    @CompilationFinal
    private final FieldType elementType;

    @CompilationFinal
    private final boolean needsValidation;

    public SpnArrayLiteralNode(FieldType elementType, SpnExpressionNode... elementNodes) {
        this.elementType = elementType;
        this.elementNodes = elementNodes;
        this.needsValidation = !(elementType instanceof FieldType.Untyped);
    }

    @Override
    @ExplodeLoop
    public Object executeGeneric(VirtualFrame frame) {
        Object[] elements = new Object[elementNodes.length];
        for (int i = 0; i < elementNodes.length; i++) {
            elements[i] = elementNodes[i].executeGeneric(frame);
        }

        if (needsValidation) {
            validateElements(elements);
        }

        return new SpnArrayValue(elementType, elements);
    }

    @ExplodeLoop
    private void validateElements(Object[] elements) {
        for (int i = 0; i < elements.length; i++) {
            if (elementType.accepts(elements[i])) continue;

            // Implicit Long → Double widening, matching the same rule applied to
            // function args (SpnFunctionRootNode) and struct fields (SpnStructConstructNode).
            if (elementType == spn.type.FieldType.DOUBLE && elements[i] instanceof Long l) {
                elements[i] = (double) l;
                continue;
            }

            throw new SpnException(
                    "Array element at index " + i + " expects " + elementType.describe()
                            + ", got " + spn.language.SpnTypeName.of(elements[i]),
                    this);
        }
    }
}
