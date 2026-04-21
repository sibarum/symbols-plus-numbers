package spn.canvasgui.node;

import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.canvasgui.spn.GuiCmd;
import spn.node.SpnExpressionNode;

/**
 * {@code cmd.selected(bool) -> GuiCmd} — marks a Button as selected (renders
 * with the selected palette). No-op on non-Button commands: mutual-exclusion
 * among button groups is modeled in user state, not a framework group entity.
 */
@NodeChild("cmd")
@NodeChild("value")
@NodeInfo(shortName = "guiSelected")
public abstract class SpnGuiSelectedNode extends SpnExpressionNode {
    @Specialization
    protected GuiCmd doSelected(GuiCmd cmd, boolean value) {
        if (cmd instanceof GuiCmd.Button b) return b.withSelected(value);
        return cmd;
    }
}
