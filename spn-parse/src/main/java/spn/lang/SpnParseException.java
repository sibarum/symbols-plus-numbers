package spn.lang;

/**
 * Thrown when the parser or tokenizer encounters invalid syntax.
 * Carries an optional source file name and location for error reporting.
 */
public class SpnParseException extends RuntimeException {

    private final String sourceName;
    private final int line;
    private final int col;

    public SpnParseException(String message) {
        super(message);
        this.sourceName = null;
        this.line = -1;
        this.col = -1;
    }

    public SpnParseException(String message, String sourceName, int line, int col) {
        super(message);
        this.sourceName = sourceName;
        this.line = line;
        this.col = col;
    }

    public String getSourceName() { return sourceName; }
    public int getLine() { return line; }
    public int getCol() { return col; }

    /**
     * Formatted error message with source location.
     */
    public String formatMessage() {
        if (sourceName != null && line >= 0) {
            return sourceName + ":" + line + ":" + col + ": " + getMessage();
        }
        return getMessage();
    }
}
