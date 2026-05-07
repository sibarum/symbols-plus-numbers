package spn.canvas.node;

import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.canvas.SpnImage;
import spn.node.SpnExpressionNode;

/**
 * {@code image(width, height) -> Image} — allocates a writable image
 * of the given dimensions. Independent of canvas() — works without a
 * canvas window, so images can be generated and saved headlessly.
 */
@NodeChild("width")
@NodeChild("height")
@NodeInfo(shortName = "image")
public abstract class SpnCanvasImageCreateNode extends SpnExpressionNode {

    @Specialization
    protected SpnImage doCreate(long width, long height) {
        return new SpnImage((int) width, (int) height);
    }
}
