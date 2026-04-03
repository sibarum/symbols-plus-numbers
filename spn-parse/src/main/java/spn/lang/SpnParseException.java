package spn.lang;

/**
 * Thrown when the parser encounters invalid syntax.
 */
public class SpnParseException extends RuntimeException {
    public SpnParseException(String message) {
        super(message);
    }
}
