package spn.traction;

import org.junit.jupiter.api.Test;
import spn.language.SpnException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that runtime argument-type errors point at the CALL site, and
 * include the callee's declaration in the message text. Prior to this,
 * the error reported only the declaration's line/col, which was useless
 * for finding the bug.
 */
class ErrorLocationTest extends TractionTestBase {

    @Test
    void argumentTypeErrorPointsAtCallSite() {
        // Rational(float, int) violates the Long-first-arg contract. The
        // error should surface the caller's position, not rational.spn's.
        SpnException e = assertThrows(SpnException.class, () -> run("""
                import numerics.rational
                Rational(3.14, 2)
                """));

        // Caller location: line 2 (the call) of the source snippet.
        assertEquals(2, e.getLine(), "should point at call site line");
        // Message should still mention the callee's declared location so
        // the user knows which overload was intended.
        assertTrue(e.getMessage().contains("rational.spn"),
                "message should reference declaration: " + e.getMessage());
        assertTrue(e.getMessage().contains("Rational/2"),
                "message should name the function: " + e.getMessage());
    }
}
