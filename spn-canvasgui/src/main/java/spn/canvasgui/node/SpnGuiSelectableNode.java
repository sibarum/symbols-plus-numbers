package spn.canvasgui.node;

import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.canvasgui.spn.GuiCmd;
import spn.node.SpnExpressionNode;

/** {@code cmd.selectable(bool) -> GuiCmd} — no-op on non-Text commands. */
@NodeChild("cmd")
@NodeChild("value")
@NodeInfo(shortName = "guiSelectable")
public abstract class SpnGuiSelectableNode extends SpnExpressionNode {
    @Specialization
    protected GuiCmd doSelectable(GuiCmd cmd, boolean value) {
        if (cmd instanceof GuiCmd.Text t) return t.withSelectable(value);
        return cmd;
    }
}
