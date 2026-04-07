package spn.stdui.mode;

/**
 * A mode that responds to the SUBMIT control signal (Ctrl+Space).
 * The {@link ModeManager} calls {@link #onSubmit()} when the signal fires.
 */
public interface SubmittableMode extends Mode {
    /** Called by ModeManager when the SUBMIT control signal fires. */
    void onSubmit();
}
