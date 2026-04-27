package spn.type;

/**
 * A named special value that belongs to a constrained type.
 *
 * Distinguished elements are values that exist within the type's algebra but may not
 * satisfy the type's regular constraints. They are "axiomatically" members of the type.
 *
 * Example: In a type "ExtendedNatural where n >= 0, n % 1 == 0", the element Omega
 * represents the result of division by zero. Omega doesn't satisfy n >= 0 (it's not
 * a number at all), but it's still a valid member of ExtendedNatural by definition.
 *
 * Distinguished elements:
 *   - Bypass constraint checking in SpnCheckConstraintNode
 *   - Can be matched in algebraic rules via OperandPattern.IsElement
 *   - Can be the result of algebraic rules (e.g., rule: n / 0 = Omega)
 *   - Are stored as the raw value inside SpnConstrainedValue
 *
 * Identity: elements use reference equality. Two elements are the same only if they
 * are the exact same object. This is important because an element named "Omega" in
 * type A is NOT the same as an element named "Omega" in type B.
 */
public final class SpnDistinguishedElement {

    private final String name;

    public SpnDistinguishedElement(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    @Override
    public String toString() {
        return name;
    }
}
