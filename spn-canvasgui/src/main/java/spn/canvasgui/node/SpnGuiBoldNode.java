package spn.canvasgui.node;

import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.canvasgui.spn.GuiCmd;
import spn.node.SpnExpressionNode;

/** {@code cmd.bold(bool) -> GuiCmd} — only meaningful on Text; no-op elsewhere. */
@NodeChild("cmd")
@NodeChild("value")
@NodeInfo(shortName = "guiBold")
public abstract class SpnGuiBoldNode extends SpnExpressionNode {
    @Specialization
    protected GuiCmd doBold(GuiCmd cmd, boolean value) {
        if (cmd instanceof GuiCmd.Text t) return t.withBold(value);
        return cmd;
    }
}
