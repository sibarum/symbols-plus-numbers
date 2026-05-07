package spn.canvas.node;

import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.canvas.CanvasState;
import spn.canvas.DrawCommand;
import spn.canvas.SpnImage;
import spn.language.SpnException;
import spn.node.SpnExpressionNode;

/**
 * {@code drawImage(image, x, y)} — queues the image to be drawn at the
 * given top-left position when the canvas window replays draw commands.
 * The actual GL texture upload happens in {@code CanvasRenderer}.
 */
@NodeChild("image")
@NodeChild("x")
@NodeChild("y")
@NodeInfo(shortName = "drawImage")
public abstract class SpnCanvasDrawImageNode extends SpnExpressionNode {

    @Specialization
    protected long doDraw(SpnImage img, double x, double y) {
        CanvasState state = CanvasState.get();
        if (state == null) throw new SpnException("drawImage() called outside canvas context", this);
        state.addCommand(new DrawCommand.DrawImage(img, (float) x, (float) y));
        return 0L;
    }

    @Fallback
    protected long typeError(Object img, Object x, Object y) {
        throw new SpnException("drawImage expects (Image, double, double); got "
                + img.getClass().getSimpleName(), this);
    }
}
