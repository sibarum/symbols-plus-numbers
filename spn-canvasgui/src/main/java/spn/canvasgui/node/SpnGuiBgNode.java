package spn.canvasgui.node;

import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.canvasgui.spn.GuiCmd;
import spn.node.SpnExpressionNode;

/** {@code cmd.bg(r, g, b) -> GuiCmd} — solid background fill. */
@NodeChild("cmd")
@NodeChild("r")
@NodeChild("g")
@NodeChild("b")
@NodeInfo(shortName = "guiBg")
public abstract class SpnGuiBgNode extends SpnExpressionNode {
    @Specialization
    protected GuiCmd doBg(GuiCmd cmd, double r, double g, double b) {
        return GuiCmd.ensureBox(cmd).withBg((float) r, (float) g, (float) b);
    }
}
