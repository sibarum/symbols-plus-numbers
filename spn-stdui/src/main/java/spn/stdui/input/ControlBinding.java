package spn.stdui.input;

import java.util.List;

/**
 * Maps a key combination to a system-level {@link ControlSignal}.
 * These are intercepted by the ModeManager before reaching modes.
 *
 * @param key          the key to match
 * @param requiredMods modifier flags that must be present
 * @param signal       the signal to fire on match
 */
public record ControlBinding(Key key, int requiredMods, ControlSignal signal) {

    /** Default system bindings. */
    public static List<ControlBinding> defaults() {
        return List.of(
                new ControlBinding(Key.SPACE,     Mod.CTRL, ControlSignal.SUBMIT),
                new ControlBinding(Key.BACKSPACE, Mod.CTRL, ControlSignal.CANCEL),
                new ControlBinding(Key.P,         Mod.CTRL, ControlSignal.MODE_MENU)
        );
    }

    /** Check if this binding matches a key event. */
    public boolean matches(Key key, int mods) {
        return this.key == key && (mods & requiredMods) == requiredMods;
    }
}
