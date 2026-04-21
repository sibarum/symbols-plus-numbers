package spn.canvasgui.node;

import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.canvasgui.spn.GuiCmd;
import spn.node.SpnExpressionNode;

/** {@code CmdText(content) -> GuiCmd} */
@NodeChild("content")
@NodeInfo(shortName = "CmdText")
public abstract class SpnCmdTextNode extends SpnExpressionNode {
    @Specialization
    protected GuiCmd doText(String content) {
        return new GuiCmd.Text(content);
    }
}
