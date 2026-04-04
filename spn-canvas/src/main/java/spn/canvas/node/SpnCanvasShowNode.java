package spn.canvas.node;

import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.canvas.CanvasState;
import spn.language.SpnException;
import spn.node.SpnExpressionNode;

@NodeInfo(shortName = "show")
public abstract class SpnCanvasShowNode extends SpnExpressionNode {

    @Specialization
    protected long doShow() {
        CanvasState state = CanvasState.get();
        if (state == null) throw new SpnException("show() called outside canvas context", this);
        return 0L;
    }
}
