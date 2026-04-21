package spn.canvasgui.node;

import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.canvasgui.spn.GuiCmd;
import spn.node.SpnExpressionNode;
import spn.type.SpnArrayValue;

/** {@code CmdVBox(children : Array) -> GuiCmd} */
@NodeChild("children")
@NodeInfo(shortName = "CmdVBox")
public abstract class SpnCmdVBoxNode extends SpnExpressionNode {
    @Specialization
    protected GuiCmd doVBox(SpnArrayValue children) {
        return new GuiCmd.VBox(SpnCmdHBoxNode.coerceChildren(children, this));
    }
}
