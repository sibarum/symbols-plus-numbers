package spn.canvasgui.node;

import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.canvasgui.spn.GuiCmd;
import spn.language.SpnException;
import spn.node.SpnExpressionNode;

/** {@code guiScrollable(child : GuiCmd) -> GuiCmd} */
@NodeChild("child")
@NodeInfo(shortName = "guiScrollable")
public abstract class SpnCmdScrollableNode extends SpnExpressionNode {
    @Specialization
    protected GuiCmd doScrollable(Object child) {
        if (!(child instanceof GuiCmd c)) {
            throw new SpnException("guiScrollable child must be a GuiCmd, got "
                    + (child == null ? "null" : child.getClass().getSimpleName()), this);
        }
        return new GuiCmd.Scrollable(c);
    }
}
