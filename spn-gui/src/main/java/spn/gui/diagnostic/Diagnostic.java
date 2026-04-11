package spn.gui.diagnostic;

/**
 * A single diagnostic (error, warning, or info) attached to a source location.
 *
 * @param row      0-based line number
 * @param startCol 0-based start column (inclusive)
 * @param endCol   0-based end column (exclusive), or -1 for end-of-line
 * @param message  human-readable description
 * @param severity error level
 */
public record Diagnostic(int row, int startCol, int endCol, String message, Severity severity) {

    public enum Severity { ERROR, WARNING, INFO }
}
