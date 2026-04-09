package spn.node.set;

import spn.language.SpnTypeName;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.language.SpnException;
import spn.node.SpnExpressionNode;
import spn.type.FieldType;
import spn.type.SpnSetValue;

import java.util.LinkedHashSet;

/**
 * Constructs an immutable SpnSetValue from element expression nodes.
 * Deduplicates automatically (set semantics). Validates element types if typed.
 */
@NodeInfo(shortName = "setLiteral")
public final class SpnSetLiteralNode extends SpnExpressionNode {

    @Children private final SpnExpressionNode[] elementNodes;

    @CompilationFinal
    private final FieldType elementType;

    @CompilationFinal
    private final boolean needsValidation;

    public SpnSetLiteralNode(FieldType elementType, SpnExpressionNode... elementNodes) {
        this.elementType = elementType;
        this.elementNodes = elementNodes;
        this.needsValidation = !(elementType instanceof FieldType.Untyped);
    }

    @Override
    @ExplodeLoop
    public Object executeGeneric(VirtualFrame frame) {
        var set = new LinkedHashSet<>(elementNodes.length);
        for (int i = 0; i < elementNodes.length; i++) {
            Object value = elementNodes[i].executeGeneric(frame);
            if (needsValidation && !elementType.accepts(value)) {
                throw new SpnException(
                        "Set element at position " + i + " expects " + elementType.describe()
                                + ", got " + SpnTypeName.of(value),
                        this);
            }
            set.add(value);
        }
        return SpnSetValue.wrap(elementType, set);
    }
}
