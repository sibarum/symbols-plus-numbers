package spn.stdlib.string;

import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.node.SpnExpressionNode;
import spn.node.builtin.SpnBuiltin;
import spn.type.FieldType;
import spn.type.SpnArrayValue;

@SpnBuiltin(name = "chars", module = "String", params = {"str"}, returns = "Array")
@NodeChild("str")
@NodeInfo(shortName = "chars")
public abstract class SpnStringCharsNode extends SpnExpressionNode {

    @Specialization
    protected SpnArrayValue chars(String str) {
        Object[] result = new Object[str.length()];
        for (int i = 0; i < str.length(); i++) {
            result[i] = String.valueOf(str.charAt(i));
        }
        return new SpnArrayValue(FieldType.STRING, result);
    }
}
