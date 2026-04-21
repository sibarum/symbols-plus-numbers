package spn.canvasgui.node;

import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.canvasgui.spn.GuiCmd;
import spn.node.SpnExpressionNode;
import spn.type.SpnSymbol;

/** {@code cmd.font(:sym) -> GuiCmd} — only meaningful on Text; no-op elsewhere. */
@NodeChild("cmd")
@NodeChild("symbol")
@NodeInfo(shortName = "guiFont")
public abstract class SpnGuiFontNode extends SpnExpressionNode {
    @Specialization
    protected GuiCmd doFont(GuiCmd cmd, SpnSymbol symbol) {
        if (cmd instanceof GuiCmd.Text t) return t.withFont(symbol);
        return cmd;
    }
}
