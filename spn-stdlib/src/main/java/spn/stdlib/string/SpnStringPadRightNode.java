package spn.stdlib.string;

import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.language.SpnException;
import spn.node.SpnExpressionNode;
import spn.node.builtin.SpnBuiltin;

@SpnBuiltin(name = "padRight", module = "String", params = {"str", "width", "fill"}, returns = "String")
@NodeChild("str")
@NodeChild("width")
@NodeChild("fill")
@NodeInfo(shortName = "padRight")
public abstract class SpnStringPadRightNode extends SpnExpressionNode {

    @Specialization
    protected String padRight(String str, long width, String fill) {
        int w = (int) width;
        if (str.length() >= w) return str;
        String pad = fill.isEmpty() ? " " : fill;
        StringBuilder sb = new StringBuilder(str);
        while (sb.length() < w) sb.append(pad);
        return sb.substring(0, w);
    }

    @Fallback
    protected Object typeError(Object str, Object width, Object fill) {
        throw new SpnException("padRight expects (string, int, string)", this);
    }
}
