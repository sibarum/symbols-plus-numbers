package spn.gui.diagnostic;

/**
 * A diagnostic attached to the editor with visual state tracking.
 * Marks transition from ACTIVE (solid red underline) to STALE (faint haze)
 * when the affected line is edited, then either return to ACTIVE or are
 * removed on the next re-parse.
 */
public class DiagnosticMark {

    public enum State { ACTIVE, STALE }

    private final Diagnostic diagnostic;
    private State state;

    public DiagnosticMark(Diagnostic diagnostic) {
        this.diagnostic = diagnostic;
        this.state = State.ACTIVE;
    }

    public Diagnostic diagnostic() { return diagnostic; }
    public State state() { return state; }
    public int row() { return diagnostic.row(); }

    public void markStale() { state = State.STALE; }
    public void markActive() { state = State.ACTIVE; }
    public boolean isActive() { return state == State.ACTIVE; }
    public boolean isStale() { return state == State.STALE; }
}
