package spn.canvas.node;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.IndirectCallNode;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.canvas.SpnImage;
import spn.language.SpnException;
import spn.node.SpnExpressionNode;
import spn.type.SpnBoundClosure;
import spn.type.SpnTupleValue;

/**
 * {@code mapPixels(image, fn)} — fills every pixel of {@code image} by
 * calling {@code fn(x, y)} which must return a 3-tuple {@code (r, g, b)}
 * of doubles in [0,1].
 *
 * <p>Accepts any {@link CallTarget}: a pure {@code (x, y) -> (r, g, b)}
 * lambda for stateless fills, or a {@code do(x, y) { ... }} closure (a
 * {@code SpnBoundClosure}, which also implements {@link CallTarget}) for
 * stateful fills that read or mutate the enclosing stateful instance's
 * fields. Iteration is sequential in row-major order, so do-closures can
 * safely accumulate state across pixels.
 *
 * <p>The Java loop avoids per-pixel SPN dispatch overhead — only the
 * callback invocation crosses the boundary.
 */
@NodeChild("image")
@NodeChild("fn")
@NodeInfo(shortName = "mapPixels")
public abstract class SpnCanvasMapPixelsNode extends SpnExpressionNode {

    @Child private IndirectCallNode callNode = Truffle.getRuntime().createIndirectCallNode();

    /**
     * Stateful {@code do(x, y) { ... }} closure: unwrap to the underlying
     * Truffle target and prepend the bound {@code this} ourselves. We can't
     * pass a {@link SpnBoundClosure} directly to {@link IndirectCallNode}
     * because Truffle casts the target to its native call-target type for
     * dispatch and {@code SpnBoundClosure} is not one of those.
     */
    @Specialization
    protected long doMapBound(SpnImage img, SpnBoundClosure fn) {
        CallTarget target = fn.underlyingTarget();
        Object boundThis = fn.boundThis();
        int w = img.width();
        int h = img.height();
        Object[] args = new Object[3];
        args[0] = boundThis;
        for (int y = 0; y < h; y++) {
            args[2] = (long) y;
            for (int x = 0; x < w; x++) {
                args[1] = (long) x;
                writePixel(img, x, y, callNode.call(target, args));
            }
        }
        return 0L;
    }

    @Specialization
    protected long doMap(SpnImage img, CallTarget fn) {
        int w = img.width();
        int h = img.height();
        Object[] args = new Object[2];
        for (int y = 0; y < h; y++) {
            args[1] = (long) y;
            for (int x = 0; x < w; x++) {
                args[0] = (long) x;
                writePixel(img, x, y, callNode.call(fn, args));
            }
        }
        return 0L;
    }

    private void writePixel(SpnImage img, int x, int y, Object result) {
        if (!(result instanceof SpnTupleValue tuple) || tuple.arity() != 3) {
            throw new SpnException(
                    "mapPixels callback must return a 3-tuple (r, g, b); got "
                            + (result == null ? "null" : result.getClass().getSimpleName()),
                    this);
        }
        double r = asDouble(tuple.get(0));
        double g = asDouble(tuple.get(1));
        double b = asDouble(tuple.get(2));
        img.setPixelArgb(x, y, SpnImage.packArgb(r, g, b));
    }

    @Fallback
    protected long typeError(Object img, Object fn) {
        throw new SpnException("mapPixels expects (Image, function); got ("
                + img.getClass().getSimpleName() + ", "
                + fn.getClass().getSimpleName() + ")", this);
    }

    private static double asDouble(Object v) {
        if (v instanceof Double d) return d;
        if (v instanceof Long l) return l.doubleValue();
        throw new ClassCastException("expected numeric pixel channel, got "
                + (v == null ? "null" : v.getClass().getSimpleName()));
    }
}
