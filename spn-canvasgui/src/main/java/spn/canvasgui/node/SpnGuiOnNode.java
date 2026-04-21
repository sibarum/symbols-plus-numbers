package spn.canvasgui.node;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.canvasgui.spn.GuiCmd;
import spn.node.SpnExpressionNode;
import spn.type.SpnSymbol;

/** {@code guiOn(cmd, :event, handlerFn) -> GuiCmd} — attaches an event handler. */
@NodeChild("cmd")
@NodeChild("event")
@NodeChild("handler")
@NodeInfo(shortName = "guiOn")
public abstract class SpnGuiOnNode extends SpnExpressionNode {
    @Specialization
    protected GuiCmd doOn(GuiCmd cmd, SpnSymbol event, CallTarget handler) {
        return cmd.withHandler(event, handler);
    }
}
