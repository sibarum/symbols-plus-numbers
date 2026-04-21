package spn.stdlib.array;

import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.language.SpnException;
import spn.node.SpnExpressionNode;
import spn.node.builtin.SpnBuiltin;
import spn.type.SpnArrayValue;

@SpnBuiltin(name = "last", module = "Array", params = {"array"}, receiver = "Array")
@NodeChild("array")
@NodeInfo(shortName = "last")
public abstract class SpnArrayLastNode extends SpnExpressionNode {

    @Specialization
    protected Object last(SpnArrayValue array) {
        if (array.length() == 0) throw new SpnException("last: empty array", this);
        return array.get((int) array.length() - 1);
    }

    @Fallback
    protected Object typeError(Object value) {
        throw new SpnException("last expects an array", this);
    }
}
