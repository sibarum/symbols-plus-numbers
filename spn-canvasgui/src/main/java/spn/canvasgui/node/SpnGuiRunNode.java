package spn.canvasgui.node;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.canvasgui.spn.GuiHost;
import spn.canvasgui.spn.GuiSpnState;
import spn.language.SpnException;
import spn.node.SpnExpressionNode;

/**
 * {@code guiRun(fps, renderFn) -> int}
 * Registers the frame loop. {@code renderFn} is called once per frame with
 * no arguments; it's expected to invoke {@code guiRender(tree)} internally.
 * State lives in the enclosing stateful block's {@code this}, captured by
 * {@code do()} closures — no explicit state threading.
 */
@NodeChild("fps")
@NodeChild("renderFn")
@NodeInfo(shortName = "guiRun")
public abstract class SpnGuiRunNode extends SpnExpressionNode {
    @Specialization
    protected long doRun(double fps, CallTarget renderFn) {
        GuiSpnState state = GuiSpnState.get();
        if (state == null) throw new SpnException("guiRun() called outside gui context", this);
        state.requestRun(fps, renderFn);
        // Run the loop INLINE so the enclosing stateful block remains alive
        // while do() closures fire. When the window closes, control returns
        // here, the block body finishes, and the instance is destroyed.
        if (state.onRunEnter() != null) state.onRunEnter().run();
        try {
            GuiHost.run(state, state.shareWith());
        } finally {
            if (state.onRunExit() != null) state.onRunExit().run();
        }
        return 0L;
    }
}
