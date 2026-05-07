package spn.canvas.node;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.canvas.SpnImage;
import spn.language.SpnException;
import spn.node.SpnExpressionNode;

import javax.imageio.ImageIO;
import java.io.File;
import java.io.IOException;

/**
 * {@code savePng(image, path)} — writes the image to the given file path
 * as PNG via {@link ImageIO}. Returns 0 on success, throws on I/O error.
 */
@NodeChild("image")
@NodeChild("path")
@NodeInfo(shortName = "savePng")
public abstract class SpnCanvasSavePngNode extends SpnExpressionNode {

    @Specialization
    @CompilerDirectives.TruffleBoundary
    protected long doSave(SpnImage img, String path) {
        try {
            File out = new File(path);
            File parent = out.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            if (!ImageIO.write(img.buffer(), "png", out)) {
                throw new SpnException("ImageIO found no PNG writer (this should not happen)", this);
            }
            return 0L;
        } catch (IOException e) {
            throw new SpnException("savePng failed: " + e.getMessage(), this);
        }
    }

    @Fallback
    protected long typeError(Object img, Object path) {
        throw new SpnException("savePng expects (Image, string); got ("
                + img.getClass().getSimpleName() + ", "
                + path.getClass().getSimpleName() + ")", this);
    }
}
