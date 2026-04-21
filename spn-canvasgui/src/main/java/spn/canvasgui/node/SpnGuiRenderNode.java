package spn.canvasgui.node;

import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.canvasgui.spn.GuiCmd;
import spn.canvasgui.spn.GuiSpnState;
import spn.language.SpnException;
import spn.node.SpnExpressionNode;

/** {@code gui_render(cmd) -> int} — submits the next GuiCmd tree for reconciliation. */
@NodeChild("tree")
@NodeInfo(shortName = "gui_render")
public abstract class SpnGuiRenderNode extends SpnExpressionNode {
    @Specialization
    protected long doRender(GuiCmd tree) {
        GuiSpnState state = GuiSpnState.get();
        if (state == null) throw new SpnException("gui_render() called outside gui context", this);
        state.submitTree(tree);
        return 0L;
    }
}
