package spn.canvasgui.node;

import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.canvasgui.spn.GuiCmd;
import spn.node.SpnExpressionNode;

/** {@code cmd.border(widthRem, r, g, b) -> GuiCmd} */
@NodeChild("cmd")
@NodeChild("width")
@NodeChild("r")
@NodeChild("g")
@NodeChild("b")
@NodeInfo(shortName = "guiBorder")
public abstract class SpnGuiBorderNode extends SpnExpressionNode {
    @Specialization
    protected GuiCmd doBorder(GuiCmd cmd, double width, double r, double g, double b) {
        return GuiCmd.ensureBox(cmd).withBorder((float) width,
                (float) r, (float) g, (float) b);
    }
}
