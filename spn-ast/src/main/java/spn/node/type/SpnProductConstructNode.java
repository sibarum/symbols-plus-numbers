package spn.node.type;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.node.SpnExpressionNode;
import spn.type.SpnProductValue;
import spn.type.SpnTypeDescriptor;

/**
 * Constructs a SpnProductValue from N child expression nodes.
 *
 * Each child evaluates to one component of the product, in component order.
 * The result is a new SpnProductValue tagged with the given type descriptor.
 *
 * Used when SPN code constructs a product literal or initializer:
 *   Complex(3.0, 4.0)   →  SpnProductConstructNode(complexType,
 *                              [DoubleLiteralNode(3.0), DoubleLiteralNode(4.0)])
 *
 * @ExplodeLoop unrolls the component evaluation loop since the number of
 * components is fixed at AST construction time.
 */
@NodeInfo(shortName = "productConstruct")
public final class SpnProductConstructNode extends SpnExpressionNode {

    @Children private final SpnExpressionNode[] componentNodes;

    private final SpnTypeDescriptor typeDescriptor;

    public SpnProductConstructNode(SpnTypeDescriptor typeDescriptor,
                                   SpnExpressionNode... componentNodes) {
        this.typeDescriptor = typeDescriptor;
        this.componentNodes = componentNodes;
    }

    @Override
    @ExplodeLoop
    public Object executeGeneric(VirtualFrame frame) {
        Object[] components = new Object[componentNodes.length];
        for (int i = 0; i < componentNodes.length; i++) {
            components[i] = componentNodes[i].executeGeneric(frame);
        }
        return new SpnProductValue(typeDescriptor, components);
    }
}
