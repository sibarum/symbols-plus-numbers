package spn.canvasgui.node;

import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.canvasgui.spn.GuiSpnState;
import spn.language.SpnException;
import spn.node.SpnExpressionNode;

/** {@code gui_window(title, width, height) -> int} — configures the window. */
@NodeChild("title")
@NodeChild("width")
@NodeChild("height")
@NodeInfo(shortName = "gui_window")
public abstract class SpnGuiWindowNode extends SpnExpressionNode {
    @Specialization
    protected long doWindow(String title, long width, long height) {
        GuiSpnState state = GuiSpnState.get();
        if (state == null) throw new SpnException("gui_window() called outside gui context", this);
        state.requestWindow((int) width, (int) height, title);
        return 0L;
    }
}
