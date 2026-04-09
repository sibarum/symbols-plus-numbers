package spn.node.type;

import spn.language.SpnTypeName;
import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.NodeField;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.language.SpnException;
import spn.node.SpnExpressionNode;
import spn.type.SpnProductValue;

/**
 * Extracts a single typed component from a SpnProductValue.
 *
 * Used when SPN code accesses a named component of a product value, e.g.:
 *   z.real   → SpnComponentAccessNode(child=readZ, componentIndex=0)
 *   z.imag   → SpnComponentAccessNode(child=readZ, componentIndex=1)
 *
 * Type-specialized unwrapping:
 * Guards check the runtime type of the component at the given index. If a complex
 * number always stores doubles, the guard becomes a no-op after compilation.
 *
 * Usage:
 * <pre>
 *   var accessReal = SpnComponentAccessNodeGen.create(readZ, 0);
 *   var accessImag = SpnComponentAccessNodeGen.create(readZ, 1);
 * </pre>
 */
@NodeChild("product")
@NodeField(name = "componentIndex", type = int.class)
@NodeInfo(shortName = "componentAccess")
public abstract class SpnComponentAccessNode extends SpnExpressionNode {

    protected abstract int getComponentIndex();

    protected static boolean isLong(SpnProductValue product, int index) {
        return product.get(index) instanceof Long;
    }

    protected static boolean isDouble(SpnProductValue product, int index) {
        return product.get(index) instanceof Double;
    }

    @Specialization(guards = "isLong(product, getComponentIndex())")
    protected long accessLong(SpnProductValue product) {
        return (long) product.get(getComponentIndex());
    }

    @Specialization(guards = "isDouble(product, getComponentIndex())")
    protected double accessDouble(SpnProductValue product) {
        return (double) product.get(getComponentIndex());
    }

    @Specialization(replaces = {"accessLong", "accessDouble"})
    protected Object accessGeneric(SpnProductValue product) {
        return product.get(getComponentIndex());
    }

    @Fallback
    protected Object notProduct(Object value) {
        throw new SpnException("Expected a product value, got: "
                + SpnTypeName.of(value), this);
    }
}
