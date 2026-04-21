package spn.canvasgui.layout;

import spn.canvasgui.cmd.GuiCommand;
import spn.canvasgui.component.Component;
import spn.canvasgui.component.Constraints;
import spn.canvasgui.component.Size;
import spn.canvasgui.unit.GuiContext;

import java.util.List;

/**
 * Zero-content component used as filler. Combined with a flex weight on the
 * parent's {@code add(child, flex)} call, it expands to consume remaining
 * space along the parent's main axis.
 *
 * <p>Standalone usage (no flex) measures to zero on both axes.
 */
public class Spacer extends Component {

    @Override
    public Size measure(Constraints c, GuiContext ctx) {
        return new Size(c.minW(), c.minH());
    }

    @Override
    public void paint(List<GuiCommand> out, GuiContext ctx) {
        clearDirty();
    }
}
