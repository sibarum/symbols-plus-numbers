package spn.canvas.node;

import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.canvas.SpnImage;
import spn.language.SpnException;
import spn.node.SpnExpressionNode;

/**
 * {@code setPixel(image, x, y, r, g, b)} — writes a single pixel.
 * RGB channels are doubles in [0,1]; out-of-range values are clamped,
 * out-of-bounds coordinates are silently ignored.
 *
 * <p>Per-call dispatch cost makes this fine for sparse writes; for dense
 * fills use {@code mapPixels} which loops in Java.
 */
@NodeChild("image")
@NodeChild("x")
@NodeChild("y")
@NodeChild("r")
@NodeChild("g")
@NodeChild("b")
@NodeInfo(shortName = "setPixel")
public abstract class SpnCanvasSetPixelNode extends SpnExpressionNode {

    @Specialization
    protected long doSet(SpnImage img, long x, long y, double r, double g, double b) {
        img.setPixel((int) x, (int) y, r, g, b);
        return 0L;
    }

    @Fallback
    protected long typeError(Object img, Object x, Object y, Object r, Object g, Object b) {
        throw new SpnException("setPixel expects (Image, long, long, double, double, double); got "
                + img.getClass().getSimpleName(), this);
    }
}
