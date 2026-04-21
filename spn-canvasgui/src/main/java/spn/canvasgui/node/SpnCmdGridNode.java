package spn.canvasgui.node;

import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.canvasgui.spn.GuiCmd;
import spn.node.SpnExpressionNode;
import spn.type.SpnArrayValue;

/** {@code guiGrid(rows : int, cols : int, children : Array) -> GuiCmd} */
@NodeChild("rows")
@NodeChild("cols")
@NodeChild("children")
@NodeInfo(shortName = "guiGrid")
public abstract class SpnCmdGridNode extends SpnExpressionNode {
    @Specialization
    protected GuiCmd doGrid(long rows, long cols, SpnArrayValue children) {
        return new GuiCmd.Grid((int) rows, (int) cols,
                SpnCmdHBoxNode.coerceChildren(children, this));
    }
}
