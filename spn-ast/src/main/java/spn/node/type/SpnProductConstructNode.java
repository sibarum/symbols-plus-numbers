package spn.node.type;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.language.SpnException;
import spn.node.SpnExpressionNode;
import spn.type.ComponentDescriptor;
import spn.type.SpnProductValue;
import spn.type.SpnTypeDescriptor;

/**
 * Constructs a SpnProductValue from N child expression nodes, with per-component
 * type and constraint validation.
 *
 * If the type descriptor has typed or constrained components, each value is validated
 * during construction. Untyped components skip validation. The needsValidation flag
 * is @CompilationFinal, so Graal eliminates the validation branch entirely for
 * fully untyped product types (like the existing Complex, Vec2, etc.).
 *
 * <pre>
 *   // ColoredNumber(n: Double, color: Symbol where oneOf(:red, :blue))
 *   var type = SpnTypeDescriptor.builder("ColoredNumber")
 *       .component("n", FieldType.DOUBLE)
 *       .component("color", FieldType.SYMBOL, Constraint.SymbolOneOf.of(red, blue))
 *       .build();
 *
 *   new SpnProductConstructNode(type,
 *       new SpnDoubleLiteralNode(3.14),
 *       new SpnSymbolLiteralNode(red));
 * </pre>
 */
@NodeInfo(shortName = "productConstruct")
public final class SpnProductConstructNode extends SpnExpressionNode {

    @Children private final SpnExpressionNode[] componentNodes;

    private final SpnTypeDescriptor typeDescriptor;

    @CompilationFinal(dimensions = 1)
    private final ComponentDescriptor[] componentDescriptors;

    @CompilationFinal
    private final boolean needsValidation;

    public SpnTypeDescriptor getDescriptor() { return typeDescriptor; }

    public SpnProductConstructNode(SpnTypeDescriptor typeDescriptor,
                                   SpnExpressionNode... componentNodes) {
        this.typeDescriptor = typeDescriptor;
        this.componentNodes = componentNodes;
        this.componentDescriptors = typeDescriptor.getComponentDescriptors();
        this.needsValidation = typeDescriptor.hasComponentValidation();
    }

    @Override
    @ExplodeLoop
    public Object executeGeneric(VirtualFrame frame) {
        Object[] components = new Object[componentNodes.length];
        for (int i = 0; i < componentNodes.length; i++) {
            components[i] = componentNodes[i].executeGeneric(frame);
        }

        if (needsValidation) {
            validateComponents(components);
        }

        return new SpnProductValue(typeDescriptor, components);
    }

    @ExplodeLoop
    private void validateComponents(Object[] components) {
        for (int i = 0; i < componentDescriptors.length; i++) {
            String violation = componentDescriptors[i].validate(components[i]);
            if (violation != null) {
                throw new SpnException(
                        typeDescriptor.getName() + ": " + violation, this);
            }
        }
    }
}
