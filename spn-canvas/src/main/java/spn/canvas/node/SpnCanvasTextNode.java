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
@NodeChild("text")
@NodeChild("scale")
@NodeInfo(shortName = "text")
public abstract class SpnCanvasTextNode extends SpnExpressionNode {

    @Specialization
    protected long doText(double x, double y, String text, double scale) {
        CanvasState state = CanvasState.get();
        if (state == null) throw new SpnException("text() called outside canvas context", this);
        state.addCommand(new DrawCommand.Text((float) x, (float) y, text, (float) scale));
        return 0L;
    }
}
