package spn.canvas.node;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.canvas.CanvasState;
import spn.language.SpnException;
import spn.node.SpnExpressionNode;

/**
 * animate(fps, drawFn) — registers an animation callback.
 * The draw function is received as a runtime value (CallTarget).
 * The actual animation loop is run by CanvasWindow after SPN execution returns.
 */
@NodeChild("fps")
@NodeChild("drawFn")
@NodeInfo(shortName = "animate")
public abstract class SpnCanvasAnimateNode extends SpnExpressionNode {

    @Specialization
    protected long doAnimate(double fps, CallTarget drawFn) {
        CanvasState state = CanvasState.get();
        if (state == null) throw new SpnException("animate() called outside canvas context", this);
        state.setAnimateCallback(drawFn, fps);
        return 0L;
    }
}
