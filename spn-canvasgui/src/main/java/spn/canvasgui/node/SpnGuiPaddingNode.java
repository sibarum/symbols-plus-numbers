package spn.canvasgui.node;

import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.canvasgui.component.Insets;
import spn.canvasgui.spn.GuiCmd;
import spn.node.SpnExpressionNode;

/** {@code cmd.padding(rem) -> GuiCmd} — uniform padding on all sides. */
@NodeChild("cmd")
@NodeChild("rem")
@NodeInfo(shortName = "guiPadding")
public abstract class SpnGuiPaddingNode extends SpnExpressionNode {
    @Specialization
    protected GuiCmd doPadding(GuiCmd cmd, double rem) {
        return GuiCmd.ensureBox(cmd).withPaddingRem(Insets.uniform((float) rem));
    }
}
