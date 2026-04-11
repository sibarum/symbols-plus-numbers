package spn.stdlib.json;

import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.node.SpnExpressionNode;
import spn.node.builtin.SpnBuiltin;

/**
 * Serializes any SPN value to a pretty-printed JSON string (2-space indent).
 */
@SpnBuiltin(name = "toJsonPretty", module = "Json", params = {"value"}, returns = "String")
@NodeChild("value")
@NodeInfo(shortName = "toJsonPretty")
public abstract class SpnToJsonPrettyNode extends SpnExpressionNode {

    @Specialization
    protected String toJsonPretty(Object value) {
        return JsonSerializer.serializePretty(value);
    }
}
