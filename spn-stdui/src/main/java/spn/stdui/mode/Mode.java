package spn.stdui.mode;

import spn.stdui.input.ControlSignal;
import spn.stdui.input.InputEvent;
import spn.stdui.render.Renderer;
import spn.stdui.widget.HudSegment;

import java.util.List;
import java.util.Set;

/**
 * A full-screen interaction mode. Like a foreground process in a terminal:
 * receives structured input, renders to a surface, reports status to the HUD.
 *
 * <p>Lifecycle: {@link #onAttach} (entering foreground) → input/render loop
 * → {@link #onDetach} (leaving foreground).
 *
 * <p>Modes never see control sequences (Ctrl+Space, Ctrl+Backspace, Ctrl+P) —
 * those are intercepted by the {@link ModeManager} before reaching the mode.
 */
public interface Mode {

    /**
     * Handle an input event.
     * @return true if the event was consumed
     */
    boolean onInput(InputEvent event);

    /**
     * Render this mode's content.
     * @param renderer the rendering backend
     * @param width    available width in pixels
     * @param height   available height in pixels (excludes HUD)
     * @param now      current time in seconds (for cursor blink, animations)
     */
    void render(Renderer renderer, float width, float height, double now);

    /**
     * HUD segments to display while this mode is active.
     */
    List<HudSegment> hudSegments();

    /**
     * Called when this mode becomes the foreground mode.
     */
    default void onAttach(ModeContext ctx) {}

    /**
     * Called when this mode leaves the foreground.
     */
    default void onDetach() {}

    /**
     * Display name for the mode (shown in mode stack listing).
     */
    default String name() { return getClass().getSimpleName(); }

    /**
     * Control signals this mode opts out of. The ModeManager will not
     * intercept these signals while this mode is active, letting the
     * mode handle (or ignore) the underlying key event itself.
     *
     * <p>For example, the base editor mode suppresses CANCEL so that
     * Ctrl+Backspace doesn't close it.
     */
    default Set<ControlSignal> suppressedSignals() { return Set.of(); }
}
