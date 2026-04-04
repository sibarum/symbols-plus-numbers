package spn.canvas.node;

import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.canvas.CanvasState;
import spn.language.SpnException;
import spn.node.SpnExpressionNode;

@NodeChild("r")
@NodeChild("g")
@NodeChild("b")
@NodeInfo(shortName = "fill")
public abstract class SpnCanvasFillNode extends SpnExpressionNode {

    @Specialization
    protected long doFill(double r, double g, double b) {
        CanvasState state = CanvasState.get();
        if (state == null) throw new SpnException("fill() called outside canvas context", this);
        state.setFill((float) r, (float) g, (float) b);
        return 0L;
    }
}
