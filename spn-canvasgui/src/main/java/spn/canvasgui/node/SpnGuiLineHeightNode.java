package spn.canvasgui.node;

import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.canvasgui.spn.GuiCmd;
import spn.node.SpnExpressionNode;

/** {@code cmd.lineHeight(mult) -> GuiCmd} — multiplier over the font's
 *  natural line height; no-op on non-Text commands. */
@NodeChild("cmd")
@NodeChild("value")
@NodeInfo(shortName = "guiLineHeight")
public abstract class SpnGuiLineHeightNode extends SpnExpressionNode {
    @Specialization
    protected GuiCmd doLineHeight(GuiCmd cmd, double value) {
        if (cmd instanceof GuiCmd.Text t) return t.withLineHeight((float) value);
        return cmd;
    }
}
