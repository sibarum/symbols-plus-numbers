package spn.canvas.node;

import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.canvas.CanvasState;
import spn.language.SpnException;
import spn.node.SpnExpressionNode;

@NodeChild("weight")
@NodeInfo(shortName = "strokeWeight")
public abstract class SpnCanvasStrokeWeightNode extends SpnExpressionNode {

    @Specialization
    protected long doWeight(double weight) {
        CanvasState state = CanvasState.get();
        if (state == null) throw new SpnException("strokeWeight() called outside canvas context", this);
        state.setStrokeWeight((float) weight);
        return 0L;
    }
}
