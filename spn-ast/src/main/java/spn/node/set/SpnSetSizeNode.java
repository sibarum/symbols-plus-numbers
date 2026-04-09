package spn.node.set;

import spn.language.SpnTypeName;
import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.language.SpnException;
import spn.node.SpnExpressionNode;
import spn.type.SpnSetValue;

/**
 * Returns the size of a set as a long.
 */
@NodeChild("set")
@NodeInfo(shortName = "setSize")
public abstract class SpnSetSizeNode extends SpnExpressionNode {

    @Specialization
    protected long size(SpnSetValue set) {
        return set.size();
    }

    @Fallback
    protected Object typeError(Object value) {
        throw new SpnException("Expected a set, got: "
                + SpnTypeName.of(value), this);
    }
}
