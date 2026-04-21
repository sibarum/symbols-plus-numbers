package spn.canvasgui.node;

import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.canvasgui.spn.GuiCmd;
import spn.node.SpnExpressionNode;

/** {@code CmdButton(label) -> GuiCmd} */
@NodeChild("label")
@NodeInfo(shortName = "CmdButton")
public abstract class SpnCmdButtonNode extends SpnExpressionNode {
    @Specialization
    protected GuiCmd doButton(String label) {
        return new GuiCmd.Button(label);
    }
}
