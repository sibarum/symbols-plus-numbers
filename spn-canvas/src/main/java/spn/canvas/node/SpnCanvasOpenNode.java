package spn.canvas.node;

import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.canvas.CanvasState;
import spn.language.SpnException;
import spn.node.SpnExpressionNode;

@NodeChild("width")
@NodeChild("height")
@NodeInfo(shortName = "canvas")
public abstract class SpnCanvasOpenNode extends SpnExpressionNode {

    @Specialization
    protected long doOpen(long width, long height) {
        CanvasState state = CanvasState.get();
        if (state == null) throw new SpnException("canvas() called outside canvas context", this);
        state.requestCanvas((int) width, (int) height);
        return 0L;
    }
}
