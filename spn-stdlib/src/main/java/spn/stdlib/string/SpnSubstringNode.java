package spn.stdlib.string;

import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.language.SpnException;
import spn.node.SpnExpressionNode;
import spn.node.builtin.SpnBuiltin;

/**
 * Extracts a substring: substring("hello", 1, 4) -> "ell"
 */
@SpnBuiltin(name = "substring", module = "String", params = {"string", "start", "end"}, returns = "String")
@NodeChild("string")
@NodeChild("start")
@NodeChild("end")
@NodeInfo(shortName = "substring")
public abstract class SpnSubstringNode extends SpnExpressionNode {

    @Specialization
    protected String substring(String string, long start, long end) {
        int s = (int) Math.max(0, Math.min(start, string.length()));
        int e = (int) Math.max(s, Math.min(end, string.length()));
        return string.substring(s, e);
    }

    @Fallback
    protected Object typeError(Object string, Object start, Object end) {
        throw new SpnException("substring expects (String, Long, Long)", this);
    }
}
