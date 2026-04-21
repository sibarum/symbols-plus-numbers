package spn.canvasgui.node;

import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.canvasgui.spn.GuiCmd;
import spn.node.SpnExpressionNode;

/** {@code guiSpacer() -> GuiCmd} */
@NodeInfo(shortName = "guiSpacer")
public abstract class SpnCmdSpacerNode extends SpnExpressionNode {
    @Specialization
    protected GuiCmd doSpacer() {
        return new GuiCmd.Spacer();
    }
}
