package spn.canvasgui.node;

import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.canvasgui.spn.GuiCmd;
import spn.node.SpnExpressionNode;

/** {@code guiSlider(min : float, max : float, value : float) -> GuiCmd} */
@NodeChild("min")
@NodeChild("max")
@NodeChild("value")
@NodeInfo(shortName = "guiSlider")
public abstract class SpnCmdSliderNode extends SpnExpressionNode {
    @Specialization
    protected GuiCmd doSlider(double min, double max, double value) {
        return new GuiCmd.Slider(min, max, value);
    }
}
