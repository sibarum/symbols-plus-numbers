package spn.canvas.node;

import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.canvas.CanvasState;
import spn.canvas.DrawCommand;
import spn.language.SpnException;
import spn.node.SpnExpressionNode;

@NodeChild("r")
@NodeChild("g")
@NodeChild("b")
@NodeInfo(shortName = "clear")
public abstract class SpnCanvasClearNode extends SpnExpressionNode {

    @Specialization
    protected long doClear(double r, double g, double b) {
        CanvasState state = CanvasState.get();
        if (state == null) throw new SpnException("clear() called outside canvas context", this);
        state.addCommand(new DrawCommand.Clear((float) r, (float) g, (float) b));
        return 0L;
    }
}
