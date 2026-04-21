package spn.canvasgui.node;

import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.canvasgui.component.Insets;
import spn.canvasgui.spn.GuiCmd;
import spn.node.SpnExpressionNode;

/** {@code cmd.margin(rem) -> GuiCmd} — uniform margin on all sides. */
@NodeChild("cmd")
@NodeChild("rem")
@NodeInfo(shortName = "guiMargin")
public abstract class SpnGuiMarginNode extends SpnExpressionNode {
    @Specialization
    protected GuiCmd doMargin(GuiCmd cmd, double rem) {
        return GuiCmd.ensureBox(cmd).withMarginRem(Insets.uniform((float) rem));
    }
}
