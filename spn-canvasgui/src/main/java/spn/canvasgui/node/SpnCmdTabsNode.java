package spn.canvasgui.node;

import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.canvasgui.spn.GuiCmd;
import spn.language.SpnException;
import spn.node.SpnExpressionNode;
import spn.type.SpnArrayValue;

import java.util.ArrayList;
import java.util.List;

/** {@code guiTabs(activeIdx : int, labels : Array<string>, pages : Array<GuiCmd>) -> GuiCmd} */
@NodeChild("activeIdx")
@NodeChild("labels")
@NodeChild("pages")
@NodeInfo(shortName = "guiTabs")
public abstract class SpnCmdTabsNode extends SpnExpressionNode {
    @Specialization
    protected GuiCmd doTabs(long activeIdx, SpnArrayValue labels, SpnArrayValue pages) {
        List<String> labelList = new ArrayList<>(labels.getElements().length);
        for (Object o : labels.getElements()) {
            if (!(o instanceof String s)) {
                throw new SpnException("guiTabs labels must be strings, got "
                        + (o == null ? "null" : o.getClass().getSimpleName()), this);
            }
            labelList.add(s);
        }
        List<GuiCmd> pageList = SpnCmdHBoxNode.coerceChildren(pages, this);
        return new GuiCmd.Tabs((int) activeIdx, labelList, pageList);
    }
}
