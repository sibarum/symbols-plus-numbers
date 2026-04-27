package spn.clifford;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link CliffordIncompatibleArithmeticException}: pin down both
 * branches of its message construction (null operand vs class-mismatch).
 */
class CliffordIncompatibleArithmeticExceptionTest {

    @Test
    void messageWithBothNonNullNamesBothTypes() {
        var ex = new CliffordIncompatibleArithmeticException(
                new CliffordInteger(1),
                new CliffordElement(CliffordInteger.ONE, CliffordInteger.ONE));
        assertTrue(ex.getMessage().contains("CliffordInteger"));
        assertTrue(ex.getMessage().contains("CliffordElement"));
    }

    @Test
    void messageWhenFirstIsNull() {
        var ex = new CliffordIncompatibleArithmeticException(null, new CliffordInteger(1));
        assertEquals("Cannot perform arithmetic on null", ex.getMessage());
    }

    @Test
    void messageWhenSecondIsNull() {
        var ex = new CliffordIncompatibleArithmeticException(new CliffordInteger(1), null);
        assertEquals("Cannot perform arithmetic on null", ex.getMessage());
    }

    @Test
    void messageWhenBothNull() {
        var ex = new CliffordIncompatibleArithmeticException(null, null);
        assertEquals("Cannot perform arithmetic on null", ex.getMessage());
    }
}
