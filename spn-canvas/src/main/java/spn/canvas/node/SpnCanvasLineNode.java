package spn.canvas.node;

import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.canvas.CanvasState;
import spn.canvas.DrawCommand;
import spn.language.SpnException;
import spn.node.SpnExpressionNode;

@NodeChild("x1")
@NodeChild("y1")
@NodeChild("x2")
@NodeChild("y2")
@NodeInfo(shortName = "line")
public abstract class SpnCanvasLineNode extends SpnExpressionNode {

    @Specialization
    protected long doLine(double x1, double y1, double x2, double y2) {
        CanvasState state = CanvasState.get();
        if (state == null) throw new SpnException("line() called outside canvas context", this);
        state.addCommand(new DrawCommand.StrokeLine((float) x1, (float) y1, (float) x2, (float) y2));
        return 0L;
    }
}
