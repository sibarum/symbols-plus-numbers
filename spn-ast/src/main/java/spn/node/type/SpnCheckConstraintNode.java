package spn.node.type;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.language.SpnException;
import spn.node.SpnExpressionNode;
import spn.type.Constraint;
import spn.type.SpnConstrainedValue;
import spn.type.SpnDistinguishedElement;
import spn.type.SpnTypeDescriptor;

/**
 * Evaluates a child expression, validates the result against a type's constraints,
 * and wraps it in a SpnConstrainedValue.
 *
 * This is the runtime enforcement point for constrained types. Every time a value
 * enters a constrained type (construction, assignment, operation result), it passes
 * through this node.
 *
 * KEY TRUFFLE CONCEPT: @ExplodeLoop with @CompilationFinal(dimensions = 1)
 *
 * The constraint-checking loop is annotated with @ExplodeLoop, and the constraints
 * array is @CompilationFinal(dimensions = 1). Together, this means:
 *
 *   1. Graal unrolls the loop at compile time (one iteration per constraint)
 *   2. Each constraint object is a compilation constant
 *   3. Each constraint.check() call is devirtualized and inlined
 *
 * So for a type "Natural where n >= 0, n % 1 == 0", the compiled code becomes:
 *
 *   long value = <child>;
 *   if (value < 0) throw constraintViolation(...);
 *   if (value % 1 != 0) throw constraintViolation(...);
 *   return new SpnConstrainedValue(value, NATURAL);  // often scalar-replaced away
 *
 * No loop, no virtual dispatch, no array access. Just two cheap checks.
 *
 * Usage (what a parser/AST builder would produce):
 * <pre>
 *   // Wrapping a literal:  let x: Natural = 42
 *   var literal = new SpnLongLiteralNode(42);
 *   var checked = new SpnCheckConstraintNode(literal, naturalType);
 *
 *   // Wrapping an operation result:  x + y (both Natural)
 *   var add = SpnAddNodeGen.create(unwrapX, unwrapY);
 *   var checked = new SpnCheckConstraintNode(add, naturalType);
 * </pre>
 */
@NodeInfo(shortName = "checkConstraint")
public final class SpnCheckConstraintNode extends SpnExpressionNode {

    @Child private SpnExpressionNode valueNode;

    private final SpnTypeDescriptor typeDescriptor;

    @CompilationFinal(dimensions = 1)
    private final Constraint[] constraints;

    @CompilationFinal(dimensions = 1)
    private final SpnDistinguishedElement[] elements;

    public SpnCheckConstraintNode(SpnExpressionNode valueNode, SpnTypeDescriptor typeDescriptor) {
        this.valueNode = valueNode;
        this.typeDescriptor = typeDescriptor;
        this.constraints = typeDescriptor.getConstraints();
        this.elements = typeDescriptor.getElements();
    }

    @Override
    @ExplodeLoop
    public Object executeGeneric(VirtualFrame frame) {
        Object value = valueNode.executeGeneric(frame);

        // Distinguished elements bypass constraint checking entirely.
        // They are axiomatically members of the type, regardless of constraints.
        if (value instanceof SpnDistinguishedElement element) {
            if (isKnownElement(element)) {
                return new SpnConstrainedValue(value, typeDescriptor);
            }
            throw new SpnException(
                    "Element '" + element.getName() + "' is not a member of type "
                            + typeDescriptor.getName(),
                    this);
        }

        for (Constraint constraint : constraints) {
            if (!constraint.check(value)) {
                throw new SpnException(
                        "Value " + value + " violates constraint '"
                                + typeDescriptor.describeConstraint(constraint)
                                + "' of type " + typeDescriptor.getName(),
                        this);
            }
        }

        return new SpnConstrainedValue(value, typeDescriptor);
    }

    @ExplodeLoop
    private boolean isKnownElement(SpnDistinguishedElement element) {
        for (SpnDistinguishedElement e : elements) {
            if (e == element) {
                return true;
            }
        }
        return false;
    }

    public SpnTypeDescriptor getTypeDescriptor() {
        return typeDescriptor;
    }
}
