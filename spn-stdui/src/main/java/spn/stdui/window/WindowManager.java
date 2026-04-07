package spn.stdui.window;

import spn.stdui.action.ActionRegistry;
import spn.stdui.buffer.BufferRegistry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Manages multiple {@link WindowFrame}s with shared state.
 *
 * <p>The {@link BufferRegistry} and {@link ActionRegistry} are global:
 * opening the same file in two windows shares the same buffer,
 * and actions registered once are available everywhere.
 */
public class WindowManager {

    private final BufferRegistry globalBuffers = new BufferRegistry();
    private final ActionRegistry globalActions = new ActionRegistry();
    private final List<WindowFrame> frames = new ArrayList<>();
    private WindowFrame focused;

    /** Create a new window frame with the given platform clipboard. */
    public WindowFrame createFrame(Clipboard clipboard) {
        WindowFrame frame = new WindowFrame(globalBuffers, clipboard, globalActions);
        frames.add(frame);
        if (focused == null) focused = frame;
        return frame;
    }

    /** Remove a window frame. */
    public void removeFrame(WindowFrame frame) {
        frames.remove(frame);
        if (focused == frame) {
            focused = frames.isEmpty() ? null : frames.getLast();
        }
    }

    /** Set the currently focused frame. */
    public void setFocused(WindowFrame frame) { this.focused = frame; }

    public WindowFrame getFocused() { return focused; }
    public List<WindowFrame> frames() { return Collections.unmodifiableList(frames); }
    public BufferRegistry buffers() { return globalBuffers; }
    public ActionRegistry actions() { return globalActions; }
}
