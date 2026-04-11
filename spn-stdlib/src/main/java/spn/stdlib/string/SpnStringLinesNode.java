package spn.stdlib.string;

import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.node.SpnExpressionNode;
import spn.node.builtin.SpnBuiltin;
import spn.type.FieldType;
import spn.type.SpnArrayValue;

@SpnBuiltin(name = "lines", module = "String", params = {"str"}, returns = "Array")
@NodeChild("str")
@NodeInfo(shortName = "lines")
public abstract class SpnStringLinesNode extends SpnExpressionNode {

    @Specialization
    protected SpnArrayValue lines(String str) {
        String[] parts = str.split("\n", -1);
        Object[] result = new Object[parts.length];
        System.arraycopy(parts, 0, result, 0, parts.length);
        return new SpnArrayValue(FieldType.STRING, result);
    }
}
