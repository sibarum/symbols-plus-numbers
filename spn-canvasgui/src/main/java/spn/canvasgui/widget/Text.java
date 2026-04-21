package spn.canvasgui.widget;

import spn.canvasgui.cmd.GuiCommand;
import spn.canvasgui.component.Component;
import spn.canvasgui.component.Constraints;
import spn.canvasgui.component.Size;
import spn.canvasgui.theme.Theme;
import spn.canvasgui.unit.GuiContext;

import java.util.List;

/**
 * Unified text component. Phase 0 ships read-only, single-line, single-color.
 * Later phases will enable {@code editable}, {@code selectable}, {@code multiline},
 * {@code wordWrap}, and styled spans on this same class — not subclasses.
 */
public class Text extends Component {

    private String text;
    private float scale;
    private float r, g, b;

    public Text(String text, Theme theme) {
        this.text = text;
        this.scale = theme.fontScale;
        this.r = theme.textR;
        this.g = theme.textG;
        this.b = theme.textB;
    }

    public String text() { return text; }

    public Text setText(String t) {
        if (!t.equals(this.text)) {
            this.text = t;
            invalidate();
        }
        return this;
    }

    public Text setColor(float r, float g, float b) {
        this.r = r; this.g = g; this.b = b;
        invalidate();
        return this;
    }

    public Text setScale(float s) {
        this.scale = s;
        invalidate();
        return this;
    }

    @Override
    public Size measure(Constraints c, GuiContext ctx) {
        float w = ctx.font().getTextWidth(text, scale);
        float h = ctx.font().getLineHeight(scale);
        return new Size(c.clampW(w), c.clampH(h));
    }

    @Override
    public void paint(List<GuiCommand> out, GuiContext ctx) {
        // SdfFontRenderer draws text with y = baseline; approximate baseline from line height.
        float baseline = ctx.font().getLineHeight(scale) * 0.8f;
        out.add(new GuiCommand.TextRun(0, baseline, text, scale, r, g, b));
        clearDirty();
    }
}
