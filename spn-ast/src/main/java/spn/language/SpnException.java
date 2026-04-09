package spn.language;

import com.oracle.truffle.api.exception.AbstractTruffleException;
import com.oracle.truffle.api.nodes.Node;
import spn.node.SpnNode;

/**
 * Base exception for SPN runtime errors.
 *
 * Extends AbstractTruffleException so the Truffle framework can properly handle it.
 * Carries optional source location for error reporting.
 */
public final class SpnException extends AbstractTruffleException {

    private final String sourceName;
    private final int line;
    private final int col;

    public SpnException(String message) {
        super(message);
        this.sourceName = null;
        this.line = -1;
        this.col = -1;
    }

    public SpnException(String message, Node location) {
        super(message, location);
        if (location instanceof SpnNode spn && spn.hasSourcePosition()) {
            this.sourceName = spn.getSourceFile();
            this.line = spn.getSourceLine();
            this.col = spn.getSourceCol();
        } else {
            this.sourceName = null;
            this.line = -1;
            this.col = -1;
        }
    }

    public SpnException(String message, String sourceName, int line, int col) {
        super(message);
        this.sourceName = sourceName;
        this.line = line;
        this.col = col;
    }

    public String getSourceName() { return sourceName; }
    public int getLine() { return line; }
    public int getCol() { return col; }
    public boolean hasLocation() { return line >= 0; }

    /**
     * Formatted error message with source location.
     */
    public String formatMessage() {
        StringBuilder sb = new StringBuilder();
        if (sourceName != null) sb.append(sourceName).append(":");
        if (hasLocation()) sb.append(line).append(":").append(col).append(": ");
        sb.append(getMessage());
        return sb.toString();
    }
}
