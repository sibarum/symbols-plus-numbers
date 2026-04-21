package spn.canvasgui.node;

import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.canvasgui.spn.GuiCmd;
import spn.node.SpnExpressionNode;

/** {@code guiDial(min : float, max : float, value : float) -> GuiCmd} */
@NodeChild("min")
@NodeChild("max")
@NodeChild("value")
@NodeInfo(shortName = "guiDial")
public abstract class SpnCmdDialNode extends SpnExpressionNode {
    @Specialization
    protected GuiCmd doDial(double min, double max, double value) {
        return new GuiCmd.Dial(min, max, value);
    }
}
