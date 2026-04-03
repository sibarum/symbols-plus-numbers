package spn.node.dict;

import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.language.SpnException;
import spn.node.SpnExpressionNode;
import spn.type.SpnDictionaryValue;

/**
 * Returns the number of entries in a dictionary as a long.
 */
@NodeChild("dict")
@NodeInfo(shortName = "dictSize")
public abstract class SpnDictionarySizeNode extends SpnExpressionNode {

    @Specialization
    protected long size(SpnDictionaryValue dict) {
        return dict.size();
    }

    @Fallback
    protected Object typeError(Object value) {
        throw new SpnException("Expected a dictionary, got: "
                + value.getClass().getSimpleName(), this);
    }
}
