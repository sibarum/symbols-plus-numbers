package spn.type;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SpnTypeDescriptorTest {

    @Test
    void simpleConstructorCreatesConstraintsOnly() {
        var type = new SpnTypeDescriptor("Natural",
                new Constraint.GreaterThanOrEqual(0),
                new Constraint.ModuloEquals(1, 0));

        assertEquals("Natural", type.getName());
        assertEquals(2, type.getConstraints().length);
        assertEquals(0, type.getElements().length);
        assertEquals(0, type.getRules().length);
        assertFalse(type.hasRules());
        assertFalse(type.hasElements());
    }

    @Test
    void builderCreatesFullType() {
        var omega = new SpnDistinguishedElement("Omega");
        var type = SpnTypeDescriptor.builder("ExtNat")
                .constraint(new Constraint.GreaterThanOrEqual(0))
                .element(omega)
                .rule(new AlgebraicRule(Operation.DIV,
                        new OperandPattern.Any(),
                        new OperandPattern.ExactLong(0),
                        omega))
                .build();

        assertEquals(1, type.getConstraints().length);
        assertEquals(1, type.getElements().length);
        assertEquals(1, type.getRules().length);
        assertTrue(type.hasRules());
        assertTrue(type.hasElements());
    }

    @Test
    void findViolationReturnsNullWhenAllPass() {
        var type = new SpnTypeDescriptor("Natural",
                new Constraint.GreaterThanOrEqual(0),
                new Constraint.ModuloEquals(1, 0));

        assertNull(type.findViolation(42L));
        assertNull(type.findViolation(0L));
    }

    @Test
    void findViolationReturnsFirstFailure() {
        var type = new SpnTypeDescriptor("Natural",
                new Constraint.GreaterThanOrEqual(0),
                new Constraint.ModuloEquals(1, 0));

        // -1 fails GreaterThanOrEqual(0)
        Constraint violation = type.findViolation(-1L);
        assertNotNull(violation);
        assertInstanceOf(Constraint.GreaterThanOrEqual.class, violation);

        // 3.5 passes >= 0 but fails % 1 == 0
        violation = type.findViolation(3.5);
        assertNotNull(violation);
        assertInstanceOf(Constraint.ModuloEquals.class, violation);
    }

    @Test
    void distinguishedElementBypassesConstraints() {
        var omega = new SpnDistinguishedElement("Omega");
        var type = SpnTypeDescriptor.builder("ExtNat")
                .constraint(new Constraint.GreaterThanOrEqual(0))
                .element(omega)
                .build();

        // Omega bypasses the >= 0 constraint
        assertNull(type.findViolation(omega));
    }

    @Test
    void unknownElementThrows() {
        var omega = new SpnDistinguishedElement("Omega");
        var foreignElement = new SpnDistinguishedElement("Foreign");
        var type = SpnTypeDescriptor.builder("ExtNat")
                .element(omega)
                .build();

        assertThrows(IllegalArgumentException.class,
                () -> type.findViolation(foreignElement));
    }

    @Test
    void hasElementUsesReferenceEquality() {
        var omega = new SpnDistinguishedElement("Omega");
        var otherOmega = new SpnDistinguishedElement("Omega"); // same name, different object
        var type = SpnTypeDescriptor.builder("T")
                .element(omega)
                .build();

        assertTrue(type.hasElement(omega));
        assertFalse(type.hasElement(otherOmega));
    }

    @Test
    void getElementByName() {
        var omega = new SpnDistinguishedElement("Omega");
        var zero = new SpnDistinguishedElement("Zero");
        var type = SpnTypeDescriptor.builder("T")
                .element(omega)
                .element(zero)
                .build();

        assertSame(omega, type.getElement("Omega"));
        assertSame(zero, type.getElement("Zero"));
        assertNull(type.getElement("Missing"));
    }

    @Test
    void toStringWithConstraintsAndElements() {
        var omega = new SpnDistinguishedElement("Omega");
        var type = SpnTypeDescriptor.builder("ExtNat")
                .constraint(new Constraint.GreaterThanOrEqual(0))
                .constraint(new Constraint.ModuloEquals(1, 0))
                .element(omega)
                .build();

        String s = type.toString();
        assertTrue(s.contains("ExtNat"));
        assertTrue(s.contains("n >= 0"));
        assertTrue(s.contains("n % 1 == 0"));
        assertTrue(s.contains("Omega"));
    }
}
