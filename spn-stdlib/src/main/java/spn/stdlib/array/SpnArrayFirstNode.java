package spn.stdlib.array;

import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.language.SpnException;
import spn.node.SpnExpressionNode;
import spn.node.builtin.SpnBuiltin;
import spn.type.SpnArrayValue;

@SpnBuiltin(name = "first", module = "Array", params = {"array"}, receiver = "Array")
@NodeChild("array")
@NodeInfo(shortName = "first")
public abstract class SpnArrayFirstNode extends SpnExpressionNode {

    @Specialization
    protected Object first(SpnArrayValue array) {
        if (array.length() == 0) throw new SpnException("first: empty array", this);
        return array.get(0);
    }

    @Fallback
    protected Object typeError(Object value) {
        throw new SpnException("first expects an array", this);
    }
}
