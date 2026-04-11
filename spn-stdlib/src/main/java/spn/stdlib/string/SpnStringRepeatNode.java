package spn.stdlib.string;

import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.language.SpnException;
import spn.node.SpnExpressionNode;
import spn.node.builtin.SpnBuiltin;

@SpnBuiltin(name = "repeatStr", module = "String", params = {"str", "count"}, returns = "String")
@NodeChild("str")
@NodeChild("count")
@NodeInfo(shortName = "repeatStr")
public abstract class SpnStringRepeatNode extends SpnExpressionNode {

    @Specialization
    protected String repeat(String str, long count) {
        return str.repeat((int) Math.max(0, count));
    }

    @Fallback
    protected Object typeError(Object str, Object count) {
        throw new SpnException("repeatStr expects (string, int)", this);
    }
}
