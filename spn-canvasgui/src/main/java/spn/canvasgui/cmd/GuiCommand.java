package spn.canvasgui.cmd;

import spn.canvas.DrawCommand;
import spn.fonts.SdfFontRenderer;

/**
 * GUI layer command ADT, distinct from and richer than {@link DrawCommand}.
 *
 * <p>Widgets emit {@code GuiCommand}s during paint. The {@code Painter} lowers
 * them to {@link DrawCommand}s plus direct GL scissor calls for clipping.
 * The GUI layer does not extend {@link DrawCommand}; it wraps it via {@link Draw}.
 *
 * <p>Sealed by design: no {@code Custom} escape hatch. Build custom widgets by
 * composing {@link Draw}, {@link TextRun}, offset/clip stacks, and input events.
 */
public sealed interface GuiCommand {

    /** Pass-through of a raw canvas draw command. Translated by the current offset stack. */
    record Draw(DrawCommand inner) implements GuiCommand {}

    /** Push a cumulative translation onto the offset stack. */
    record PushOffset(float dx, float dy) implements GuiCommand {}

    /** Pop the most recent offset. */
    record PopOffset() implements GuiCommand {}

    /** Push a clip rect (in current-offset coordinates). Lowered to GL scissor. */
    record PushClip(float x, float y, float w, float h) implements GuiCommand {}

    /** Pop the most recent clip. */
    record PopClip() implements GuiCommand {}

    /**
     * Single-color text run. {@code font} is the already-resolved renderer
     * for this run (widget picks the right family/weight/style variant and
     * emits the renderer directly). {@code null} means "use the registry's
     * default" — the paint renderer substitutes.
     *
     * <p>Styled spans come in a later phase.
     */
    record TextRun(float x, float y, String text, float scale,
                   float r, float g, float b, SdfFontRenderer font) implements GuiCommand {
        public TextRun(float x, float y, String text, float scale, float r, float g, float b) {
            this(x, y, text, scale, r, g, b, null);
        }
    }
}
