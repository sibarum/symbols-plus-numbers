package spn.stdui.mode;

import spn.stdui.action.ActionRegistry;
import spn.stdui.buffer.BufferRegistry;
import spn.stdui.widget.Hud;
import spn.stdui.window.Clipboard;

/**
 * Shared state accessible to all modes within a window frame.
 * Passed to modes via {@link Mode#onAttach(ModeContext)}.
 *
 * <p>Like environment variables for processes in the TTY metaphor.
 */
public record ModeContext(
        BufferRegistry buffers,
        Clipboard clipboard,
        ActionRegistry actions,
        Hud hud,
        ModeManager modeManager
) {}
