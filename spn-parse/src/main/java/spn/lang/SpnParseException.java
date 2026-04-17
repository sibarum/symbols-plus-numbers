package spn.lang;

/**
 * Thrown when the parser or tokenizer encounters invalid syntax.
 * Carries an optional source file name and location for error reporting.
 */
public class SpnParseException extends RuntimeException {

    private final String sourceName;
    private final int line;
    private final int col;

    /** Macro expansion stack: each entry is "macroName at file:line". */
    private java.util.List<String> macroStack;

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

    /** Add a macro expansion frame to the stack trace. */
    public void pushMacroFrame(String macroName, String file, int invocationLine) {
        if (macroStack == null) macroStack = new java.util.ArrayList<>();
        String frame = macroName + "(" + (file != null ? file : "?") + ":" + invocationLine + ")";
        macroStack.add(frame);
    }

    /** Returns the macro expansion stack, or null if not inside a macro. */
    public java.util.List<String> getMacroStack() { return macroStack; }

    /**
     * Formatted error message with source location and macro expansion trace.
     */
    public String formatMessage() {
        var sb = new StringBuilder();
        if (sourceName != null && line >= 0) {
            sb.append(sourceName).append(":").append(line).append(":").append(col).append(": ");
        }
        sb.append(getMessage());
        if (macroStack != null && !macroStack.isEmpty()) {
            for (String frame : macroStack) {
                sb.append("\n  in macro ").append(frame);
            }
        }
        return sb.toString();
    }
}
