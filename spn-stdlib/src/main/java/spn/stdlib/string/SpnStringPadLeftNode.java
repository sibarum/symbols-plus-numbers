package spn.stdlib.string;

import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.language.SpnException;
import spn.node.SpnExpressionNode;
import spn.node.builtin.SpnBuiltin;

@SpnBuiltin(name = "padLeft", module = "String", params = {"str", "width", "fill"}, returns = "String")
@NodeChild("str")
@NodeChild("width")
@NodeChild("fill")
@NodeInfo(shortName = "padLeft")
public abstract class SpnStringPadLeftNode extends SpnExpressionNode {

    @Specialization
    protected String padLeft(String str, long width, String fill) {
        int w = (int) width;
        if (str.length() >= w) return str;
        String pad = fill.isEmpty() ? " " : fill;
        StringBuilder sb = new StringBuilder();
        while (sb.length() + str.length() < w) sb.append(pad);
        sb.append(str);
        return sb.substring(sb.length() - w);
    }

    @Fallback
    protected Object typeError(Object str, Object width, Object fill) {
        throw new SpnException("padLeft expects (string, int, string)", this);
    }
}
