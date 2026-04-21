package spn.canvasgui.node;

import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.canvasgui.spn.GuiCmd;
import spn.language.SpnException;
import spn.node.SpnExpressionNode;
import spn.type.SpnArrayValue;

import java.util.ArrayList;
import java.util.List;

/** {@code CmdHBox(children : Array) -> GuiCmd} */
@NodeChild("children")
@NodeInfo(shortName = "CmdHBox")
public abstract class SpnCmdHBoxNode extends SpnExpressionNode {
    @Specialization
    protected GuiCmd doHBox(SpnArrayValue children) {
        return new GuiCmd.HBox(coerceChildren(children, this));
    }

    static List<GuiCmd> coerceChildren(SpnArrayValue children, SpnExpressionNode ctx) {
        Object[] els = children.getElements();
        List<GuiCmd> out = new ArrayList<>(els.length);
        for (Object el : els) {
            if (!(el instanceof GuiCmd c)) {
                throw new SpnException(
                        "Container child must be a GuiCmd, got " + el.getClass().getSimpleName(),
                        ctx);
            }
            out.add(c);
        }
        return out;
    }
}
