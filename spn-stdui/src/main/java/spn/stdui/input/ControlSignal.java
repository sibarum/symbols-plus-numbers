package spn.stdui.input;

/**
 * System-level signals intercepted by the {@link spn.stdui.mode.ModeManager}
 * before they reach the active mode. Analogous to terminal signals
 * (SIGINT, SIGTSTP) in the TTY model.
 */
public enum ControlSignal {
    /** Ctrl+Space — the active mode should submit/complete its work. */
    SUBMIT,
    /** Ctrl+Backspace — cancel the active mode (pop from stack). */
    CANCEL,
    /** Ctrl+P — open the mode/action palette. */
    MODE_MENU,
    /** Reserved for future use: suspend the active mode. */
    SUSPEND
}
