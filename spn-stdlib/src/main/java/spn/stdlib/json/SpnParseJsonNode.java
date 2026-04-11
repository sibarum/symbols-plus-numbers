package spn.stdlib.json;

import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.language.SpnException;
import spn.node.SpnExpressionNode;
import spn.node.builtin.SpnBuiltin;

/**
 * Parses a JSON string into SPN values.
 * Objects become Dict, arrays become Array, etc.
 */
@SpnBuiltin(name = "parseJson", module = "Json", params = {"json"})
@NodeChild("json")
@NodeInfo(shortName = "parseJson")
public abstract class SpnParseJsonNode extends SpnExpressionNode {

    @Specialization
    protected Object parseJson(String json) {
        try {
            return new JsonParser(json).parse();
        } catch (RuntimeException e) {
            throw new SpnException("parseJson: " + e.getMessage(), this);
        }
    }

    @Fallback
    protected Object typeError(Object json) {
        throw new SpnException("parseJson expects a string", this);
    }
}
