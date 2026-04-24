package spn.canvasgui.widget;

import spn.canvas.DrawCommand;
import spn.canvasgui.cmd.GuiCommand;
import spn.canvasgui.component.Component;
import spn.canvasgui.component.Constraints;
import spn.canvasgui.component.Size;
import spn.canvasgui.unit.GuiContext;
import spn.type.SpnStructValue;

import java.util.List;

/**
 * Fixed-size drawing surface that lets SPN programs embed raw spn-canvas
 * commands inside a canvasgui layout.
 *
 * <p>Input is a list of SPN struct values produced by the {@code Cmd*}
 * constructors in {@code spn.canvas.draw} ({@code CmdLine}, {@code CmdCircle},
 * {@code CmdFill}, ...). Geometry commands lower to {@link GuiCommand.Draw}
 * records; {@code CmdText} lowers to {@link GuiCommand.TextRun} — the canvasgui
 * paint pipeline renders text via {@code FontRegistry}, not through
 * {@code CanvasRenderer}, so a {@code Draw(DrawCommand.Text)} would silently
 * drop. We track the current fill color across commands and bake it into
 * each {@code TextRun}.
 *
 * <p>V1 is non-interactive — no pointer or keyboard events surface to SPN.
 */
public class Canvas extends Component {

    private int widthPx;
    private int heightPx;
    private List<Object> cmds;

    public Canvas(int width, int height, List<Object> cmds) {
        this.widthPx = width;
        this.heightPx = height;
        this.cmds = cmds;
    }

    public Canvas setSize(int width, int height) {
        if (widthPx != width || heightPx != height) {
            widthPx = width;
            heightPx = height;
            invalidate();
        }
        return this;
    }

    public Canvas setCmds(List<Object> cmds) {
        this.cmds = cmds;
        invalidate();
        return this;
    }

    @Override
    public Size measure(Constraints c, GuiContext ctx) {
        return new Size(c.clampW(widthPx), c.clampH(heightPx));
    }

    @Override
    public void paint(List<GuiCommand> out, GuiContext ctx) {
        // Current fill color, carried across commands so CmdText can bake it
        // into its TextRun (canvasgui's text pipeline takes color per-run,
        // not via SetFill state).
        float fillR = 1f, fillG = 1f, fillB = 1f;

        for (Object o : cmds) {
            if (!(o instanceof SpnStructValue sv)) continue;
            String name = sv.getDescriptor().getName();
            switch (name) {
                case "CmdClear" -> out.add(new GuiCommand.Draw(
                        new DrawCommand.Clear(f(sv, 0), f(sv, 1), f(sv, 2))));
                case "CmdFill" -> {
                    fillR = f(sv, 0); fillG = f(sv, 1); fillB = f(sv, 2);
                    out.add(new GuiCommand.Draw(
                            new DrawCommand.SetFill(fillR, fillG, fillB)));
                }
                case "CmdStroke" -> out.add(new GuiCommand.Draw(
                        new DrawCommand.SetStroke(f(sv, 0), f(sv, 1), f(sv, 2))));
                case "CmdStrokeWeight" -> out.add(new GuiCommand.Draw(
                        new DrawCommand.SetStrokeWeight(f(sv, 0))));
                case "CmdRect" -> out.add(new GuiCommand.Draw(
                        new DrawCommand.FillRect(f(sv, 0), f(sv, 1), f(sv, 2), f(sv, 3))));
                case "CmdCircle" -> out.add(new GuiCommand.Draw(
                        new DrawCommand.FillCircle(f(sv, 0), f(sv, 1), f(sv, 2))));
                case "CmdLine" -> out.add(new GuiCommand.Draw(
                        new DrawCommand.StrokeLine(f(sv, 0), f(sv, 1), f(sv, 2), f(sv, 3))));
                case "CmdText" -> out.add(new GuiCommand.TextRun(
                        f(sv, 0), f(sv, 1), (String) sv.get(2), f(sv, 3),
                        fillR, fillG, fillB));
                default -> { /* unknown draw command — silently skip */ }
            }
        }
        clearDirty();
    }

    private static float f(SpnStructValue sv, int idx) {
        Object v = sv.get(idx);
        return v instanceof Number n ? n.floatValue() : 0f;
    }
}
