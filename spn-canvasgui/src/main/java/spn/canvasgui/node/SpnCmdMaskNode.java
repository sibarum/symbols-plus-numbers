package spn.canvasgui.node;

import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.canvasgui.spn.GuiCmd;
import spn.language.SpnException;
import spn.node.SpnExpressionNode;

/** {@code guiMask(child : GuiCmd, w : float, h : float) -> GuiCmd} */
@NodeChild("child")
@NodeChild("w")
@NodeChild("h")
@NodeInfo(shortName = "guiMask")
public abstract class SpnCmdMaskNode extends SpnExpressionNode {
    @Specialization
    protected GuiCmd doMask(Object child, double w, double h) {
        if (!(child instanceof GuiCmd c)) {
            throw new SpnException("guiMask child must be a GuiCmd, got "
                    + (child == null ? "null" : child.getClass().getSimpleName()), this);
        }
        return new GuiCmd.Mask(c, (float) w, (float) h);
    }
}
