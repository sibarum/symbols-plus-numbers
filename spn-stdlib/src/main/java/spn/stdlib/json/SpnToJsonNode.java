package spn.stdlib.json;

import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.node.SpnExpressionNode;
import spn.node.builtin.SpnBuiltin;

/**
 * Serializes any SPN value to a JSON string.
 */
@SpnBuiltin(name = "toJson", module = "Json", params = {"value"}, returns = "String")
@NodeChild("value")
@NodeInfo(shortName = "toJson")
public abstract class SpnToJsonNode extends SpnExpressionNode {

    @Specialization
    protected String toJson(Object value) {
        return JsonSerializer.serialize(value);
    }
}
