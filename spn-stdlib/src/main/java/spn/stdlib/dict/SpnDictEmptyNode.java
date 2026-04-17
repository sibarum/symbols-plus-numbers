package spn.stdlib.dict;

import com.oracle.truffle.api.nodes.NodeInfo;
import spn.node.SpnExpressionNode;
import spn.node.builtin.SpnBuiltin;
import spn.type.FieldType;
import spn.type.SpnDictionaryValue;

/**
 * Returns a fresh empty dictionary. Needed because SPN has no literal
 * syntax for an empty dict ({@code []} always parses as an empty array).
 */
@SpnBuiltin(name = "emptyDict", module = "Dict", params = {}, returns = "Dict")
@NodeInfo(shortName = "emptyDict")
public class SpnDictEmptyNode extends SpnExpressionNode {

    @Override
    public Object executeGeneric(com.oracle.truffle.api.frame.VirtualFrame frame) {
        return SpnDictionaryValue.empty(FieldType.UNTYPED);
    }
}
