package spn.stdui.widget;

/**
 * One segment of HUD content.
 *
 * @param keyHint shortcut key text (e.g. "Ctrl+S"), rendered in accent color. May be empty.
 * @param label   description text (e.g. "Save"), rendered in muted color.
 */
public record HudSegment(String keyHint, String label) {
    /** Convenience for label-only segments (no key hint). */
    public static HudSegment label(String text) {
        return new HudSegment("", text);
    }
}
