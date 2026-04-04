package spn.canvas.node;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.canvas.CanvasState;
import spn.language.SpnException;
import spn.node.SpnExpressionNode;

/**
 * animate(fps, drawFn) — stores the callback and FPS in CanvasState.
 * The actual animation loop is run by CanvasWindow after SPN execution returns.
 *
 * The CallTarget for the draw function is passed via the constructor (same
 * pattern as SpnArrayMapNode).
 */
@NodeChild("fps")
@NodeInfo(shortName = "animate")
public abstract class SpnCanvasAnimateNode extends SpnExpressionNode {

    private final CallTarget drawCallback;

    protected SpnCanvasAnimateNode(CallTarget drawCallback) {
        this.drawCallback = drawCallback;
    }

    @Specialization
    protected long doAnimate(double fps) {
        CanvasState state = CanvasState.get();
        if (state == null) throw new SpnException("animate() called outside canvas context", this);
        state.setAnimateCallback(drawCallback, fps);
        return 0L;
    }
}
