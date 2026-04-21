package spn.canvasgui.node;

import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.canvasgui.spn.GuiCmd;
import spn.node.SpnExpressionNode;

/** {@code cmd.italic(bool) -> GuiCmd} — only meaningful on Text; no-op elsewhere. */
@NodeChild("cmd")
@NodeChild("value")
@NodeInfo(shortName = "guiItalic")
public abstract class SpnGuiItalicNode extends SpnExpressionNode {
    @Specialization
    protected GuiCmd doItalic(GuiCmd cmd, boolean value) {
        if (cmd instanceof GuiCmd.Text t) return t.withItalic(value);
        return cmd;
    }
}
