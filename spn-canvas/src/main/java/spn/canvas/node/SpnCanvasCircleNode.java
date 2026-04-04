package spn.canvas.node;

import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.canvas.CanvasState;
import spn.canvas.DrawCommand;
import spn.language.SpnException;
import spn.node.SpnExpressionNode;

@NodeChild("cx")
@NodeChild("cy")
@NodeChild("r")
@NodeInfo(shortName = "circle")
public abstract class SpnCanvasCircleNode extends SpnExpressionNode {

    @Specialization
    protected long doCircle(double cx, double cy, double r) {
        CanvasState state = CanvasState.get();
        if (state == null) throw new SpnException("circle() called outside canvas context", this);
        state.addCommand(new DrawCommand.FillCircle((float) cx, (float) cy, (float) r));
        return 0L;
    }
}
