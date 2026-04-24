package spn.canvasgui.node;

import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.canvasgui.spn.GuiCmd;
import spn.node.SpnExpressionNode;
import spn.type.SpnArrayValue;

import java.util.Arrays;
import java.util.List;

/**
 * {@code guiCanvas(w : int, h : int, cmds : Array) -> GuiCmd}
 *
 * <p>Wraps the SPN cmd array as an opaque {@code List<Object>} — the
 * widget matches on {@link spn.type.SpnStructValue} descriptor names at
 * paint time. No coercion here; unknown values just get skipped.
 */
@NodeChild("w")
@NodeChild("h")
@NodeChild("cmds")
@NodeInfo(shortName = "guiCanvas")
public abstract class SpnCmdCanvasNode extends SpnExpressionNode {
    @Specialization
    protected GuiCmd doCanvas(long w, long h, SpnArrayValue cmds) {
        // Fixed-size view over the SPN array's elements — the widget
        // iterates read-only, so no defensive copy needed.
        List<Object> list = Arrays.asList(cmds.getElements());
        return new GuiCmd.Canvas((int) w, (int) h, list);
    }
}
