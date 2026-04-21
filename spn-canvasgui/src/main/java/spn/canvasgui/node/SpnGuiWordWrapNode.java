package spn.canvasgui.node;

import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.canvasgui.spn.GuiCmd;
import spn.node.SpnExpressionNode;

/** {@code cmd.wordWrap(bool) -> GuiCmd} — no-op on non-Text commands. */
@NodeChild("cmd")
@NodeChild("value")
@NodeInfo(shortName = "guiWordWrap")
public abstract class SpnGuiWordWrapNode extends SpnExpressionNode {
    @Specialization
    protected GuiCmd doWordWrap(GuiCmd cmd, boolean value) {
        if (cmd instanceof GuiCmd.Text t) return t.withWordWrap(value);
        return cmd;
    }
}
