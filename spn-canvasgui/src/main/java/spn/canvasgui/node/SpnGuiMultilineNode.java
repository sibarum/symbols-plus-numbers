package spn.canvasgui.node;

import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.canvasgui.spn.GuiCmd;
import spn.node.SpnExpressionNode;

/** {@code cmd.multiline(bool) -> GuiCmd} — no-op on non-Text commands. */
@NodeChild("cmd")
@NodeChild("value")
@NodeInfo(shortName = "guiMultiline")
public abstract class SpnGuiMultilineNode extends SpnExpressionNode {
    @Specialization
    protected GuiCmd doMultiline(GuiCmd cmd, boolean value) {
        if (cmd instanceof GuiCmd.Text t) return t.withMultiline(value);
        return cmd;
    }
}
