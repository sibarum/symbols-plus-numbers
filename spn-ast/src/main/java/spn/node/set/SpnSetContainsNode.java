package spn.node.set;

import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.language.SpnException;
import spn.node.SpnExpressionNode;
import spn.type.SpnSetValue;

/**
 * Tests whether a set contains a given element. Returns boolean.
 */
@NodeChild("set")
@NodeChild("element")
@NodeInfo(shortName = "setContains")
public abstract class SpnSetContainsNode extends SpnExpressionNode {

    @Specialization
    protected boolean contains(SpnSetValue set, Object element) {
        return set.contains(element);
    }

    @Fallback
    protected Object typeError(Object set, Object element) {
        throw new SpnException("Expected a set, got: "
                + set.getClass().getSimpleName(), this);
    }
}
