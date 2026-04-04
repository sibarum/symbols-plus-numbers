package spn.canvas.node;

import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.canvas.CanvasState;
import spn.canvas.DrawCommand;
import spn.language.SpnException;
import spn.node.SpnExpressionNode;

@NodeChild("x")
@NodeChild("y")
@NodeChild("w")
@NodeChild("h")
@NodeInfo(shortName = "rect")
public abstract class SpnCanvasRectNode extends SpnExpressionNode {

    @Specialization
    protected long doRect(double x, double y, double w, double h) {
        CanvasState state = CanvasState.get();
        if (state == null) throw new SpnException("rect() called outside canvas context", this);
        state.addCommand(new DrawCommand.FillRect((float) x, (float) y, (float) w, (float) h));
        return 0L;
    }
}
