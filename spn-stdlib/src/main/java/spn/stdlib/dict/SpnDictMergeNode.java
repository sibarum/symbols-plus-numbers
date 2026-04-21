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
 * Merges two dictionaries. Right-hand values win on key conflict.
 *
 * <pre>
 *   merge({:a 1}, {:b 2}) -> {:a 1, :b 2}
 * </pre>
 */
@SpnBuiltin(name = "merge", module = "Dict", params = {"left", "right"}, returns = "Dict", receiver = "Dict")
@NodeChild("left")
@NodeChild("right")
@NodeInfo(shortName = "merge")
public abstract class SpnDictMergeNode extends SpnExpressionNode {

    @Specialization
    protected SpnDictionaryValue merge(SpnDictionaryValue left, SpnDictionaryValue right) {
        return left.merge(right);
    }

    @Fallback
    protected Object typeError(Object left, Object right) {
        throw new SpnException("merge expects two Dicts", this);
    }
}
