package spn.canvasgui.node;

import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.canvasgui.spn.GuiCmd;
import spn.node.SpnExpressionNode;

/** {@code cmd.editable(bool) -> GuiCmd} — no-op on non-Text commands. */
@NodeChild("cmd")
@NodeChild("value")
@NodeInfo(shortName = "guiEditable")
public abstract class SpnGuiEditableNode extends SpnExpressionNode {
    @Specialization
    protected GuiCmd doEditable(GuiCmd cmd, boolean value) {
        if (cmd instanceof GuiCmd.Text t) return t.withEditable(value);
        return cmd;
    }
}
