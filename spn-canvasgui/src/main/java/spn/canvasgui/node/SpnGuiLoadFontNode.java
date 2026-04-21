package spn.canvasgui.node;

import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.canvasgui.spn.GuiSpnState;
import spn.language.SpnException;
import spn.node.SpnExpressionNode;
import spn.type.SpnSymbol;

import java.nio.file.Path;

/**
 * {@code guiLoadFont(:symbol, "relative/path.ttf") -> int}
 *
 * <p>Registers a TTF under the given symbol. The path is resolved relative
 * to the calling .spn file's nearest module root (same convention as
 * source imports) — never above that root. If no module root is set, the
 * path is treated as absolute / CWD-relative.
 *
 * <p>SPN runs before the GL context is live, so the actual load is queued
 * on {@link GuiSpnState} and processed by {@code GuiHost} after it opens
 * the window.
 */
@NodeChild("symbol")
@NodeChild("path")
@NodeInfo(shortName = "guiLoadFont")
public abstract class SpnGuiLoadFontNode extends SpnExpressionNode {
    @Specialization
    protected long doLoadFont(SpnSymbol symbol, String path) {
        GuiSpnState state = GuiSpnState.get();
        if (state == null) throw new SpnException(
                "guiLoadFont() called outside gui context", this);

        Path resolved;
        if (state.moduleRoot() != null) {
            Path p = Path.of(path);
            if (p.isAbsolute()) {
                throw new SpnException(
                        "guiLoadFont() path must be relative to the module root: " + path,
                        this);
            }
            Path candidate = state.moduleRoot().resolve(p).normalize();
            // Forbid escape above the module root with ../ tricks.
            if (!candidate.startsWith(state.moduleRoot().normalize())) {
                throw new SpnException(
                        "guiLoadFont() path escapes the module root: " + path, this);
            }
            resolved = candidate;
        } else {
            resolved = Path.of(path);
        }

        state.queueFontLoad(symbol.name(), resolved.toString());
        return 0L;
    }
}
