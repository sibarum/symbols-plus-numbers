package spn.stdlib.dict;

import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.language.SpnException;
import spn.node.SpnExpressionNode;
import spn.node.builtin.SpnBuiltin;
import spn.type.SpnDictionaryValue;

/**
 * Returns the number of entries in a dictionary.
 *
 * Named dictSize (not size) so it doesn't collide with Set's size in the
 * stdlib registry, which keys factory methods by bare name.
 */
@SpnBuiltin(name = "dictSize", module = "Dict", params = {"dict"}, returns = "Long", receiver = "UntypedDict", method = "size")
@NodeChild("dict")
@NodeInfo(shortName = "dictSize")
public abstract class SpnDictSizeNode extends SpnExpressionNode {

    @Specialization
    protected long size(SpnDictionaryValue dict) {
        return dict.size();
    }

    @Fallback
    protected Object typeError(Object value) {
        throw new SpnException("dictSize expects a Dict, got: "
                + value.getClass().getSimpleName(), this);
    }
}
