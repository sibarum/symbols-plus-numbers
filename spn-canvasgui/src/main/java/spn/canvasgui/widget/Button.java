package spn.canvasgui.widget;

import spn.canvas.DrawCommand;
import spn.canvasgui.cmd.GuiCommand;
import spn.canvasgui.component.Component;
import spn.canvasgui.component.Constraints;
import spn.canvasgui.component.Size;
import spn.canvasgui.input.GuiEvent;
import spn.canvasgui.theme.Theme;
import spn.canvasgui.unit.GuiContext;

import java.util.List;

/**
 * Auto-sized text button with focus/hover/pressed visual states.
 *
 * <p>Strict click: fires the {@code onClick} callback only when a press and
 * release both happen inside the button without the pointer leaving in between.
 * Press/release bookkeeping is handled by the {@code InputRouter}; this widget
 * just observes {@link GuiEvent.Pointer} with phase {@code CLICK}.
 */
public class Button extends Component {

    private final String label;
    private final Theme theme;
    private Runnable onClick = () -> {};
    private boolean selected;

    public Button(String label, Theme theme) {
        this.label = label;
        this.theme = theme;
    }

    public Button onClick(Runnable action) {
        this.onClick = action;
        return this;
    }

    public Button setSelected(boolean s) {
        if (selected != s) {
            selected = s;
            invalidate();
        }
        return this;
    }

    public boolean isSelected() { return selected; }

    public String label() { return label; }

    @Override
    public boolean focusable() { return true; }

    @Override
    public Size measure(Constraints c, GuiContext ctx) {
        float textW = ctx.font().getTextWidth(label, theme.fontScale);
        float textH = ctx.font().getLineHeight(theme.fontScale);
        float w = textW + 2 * ctx.rem(theme.buttonPadXRem);
        float h = textH + 2 * ctx.rem(theme.buttonPadYRem);
        return new Size(c.clampW(w), c.clampH(h));
    }

    @Override
    public void paint(List<GuiCommand> out, GuiContext ctx) {
        float w = bounds().w();
        float h = bounds().h();

        float br, bg, bb;
        if (pressed()) { br = theme.buttonPressR; bg = theme.buttonPressG; bb = theme.buttonPressB; }
        else if (selected) { br = theme.buttonSelectedR; bg = theme.buttonSelectedG; bb = theme.buttonSelectedB; }
        else if (hovered()) { br = theme.buttonHoverR; bg = theme.buttonHoverG; bb = theme.buttonHoverB; }
        else { br = theme.buttonR; bg = theme.buttonG; bb = theme.buttonB; }

        out.add(new GuiCommand.Draw(new DrawCommand.SetFill(br, bg, bb)));
        out.add(new GuiCommand.Draw(new DrawCommand.FillRect(0, 0, w, h)));

        if (focused()) {
            float ringW = ctx.rem(theme.focusRingWidthRem);
            out.add(new GuiCommand.Draw(new DrawCommand.SetFill(
                    theme.focusRingR, theme.focusRingG, theme.focusRingB)));
            // 4 thin bars forming a rectangle outline
            out.add(new GuiCommand.Draw(new DrawCommand.FillRect(0, 0, w, ringW)));
            out.add(new GuiCommand.Draw(new DrawCommand.FillRect(0, h - ringW, w, ringW)));
            out.add(new GuiCommand.Draw(new DrawCommand.FillRect(0, 0, ringW, h)));
            out.add(new GuiCommand.Draw(new DrawCommand.FillRect(w - ringW, 0, ringW, h)));
        }

        float textW = ctx.font().getTextWidth(label, theme.fontScale);
        float textH = ctx.font().getLineHeight(theme.fontScale);
        float tx = (w - textW) * 0.5f;
        float baseline = (h - textH) * 0.5f + textH * 0.8f;
        out.add(new GuiCommand.TextRun(tx, baseline, label,
                theme.fontScale, theme.textR, theme.textG, theme.textB));

        clearDirty();
    }

    @Override
    public boolean onEvent(GuiEvent e) {
        if (e instanceof GuiEvent.Pointer p) {
            switch (p.phase()) {
                case CLICK -> { onClick.run(); return true; }
                case ENTER, EXIT, PRESS, RELEASE, MOVE -> { return true; }
            }
        }
        // Keyboard activation: Enter or Space when focused.
        if (e instanceof GuiEvent.KeyDown k && focused()) {
            if (k.key() == spn.stdui.input.Key.ENTER || k.key() == spn.stdui.input.Key.SPACE) {
                onClick.run();
                return true;
            }
        }
        return false;
    }
}
