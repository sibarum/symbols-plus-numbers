package spn.node.stateful;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.node.SpnExpressionNode;
import spn.type.SpnStatefulInstance;

/**
 * Executes a {@code T(args) { body }} block: allocate a stateful instance,
 * initialize declared fields from {@code args}, store it in a local slot,
 * run the body, then mark the instance dead.
 *
 * <p>The body is run in try/finally; the kill signal fires even on
 * exceptional exit, so leaked closures always get the "out of scope"
 * error rather than silently succeeding against a dead frame.
 */
@NodeInfo(shortName = "statefulBlock")
public final class SpnStatefulBlockNode extends SpnExpressionNode {

    private final String typeName;
    @com.oracle.truffle.api.CompilerDirectives.CompilationFinal(dimensions = 1)
    private final String[] declaredFieldNames;
    @Children private final SpnExpressionNode[] initArgNodes;
    private final int thisSlot;
    @Child private SpnExpressionNode body;

    public SpnStatefulBlockNode(String typeName,
                                String[] declaredFieldNames,
                                SpnExpressionNode[] initArgNodes,
                                int thisSlot,
                                SpnExpressionNode body) {
        this.typeName = typeName;
        this.declaredFieldNames = declaredFieldNames;
        this.initArgNodes = initArgNodes;
        this.thisSlot = thisSlot;
        this.body = body;
    }

    @Override
    @ExplodeLoop
    public Object executeGeneric(VirtualFrame frame) {
        SpnStatefulInstance instance = new SpnStatefulInstance(typeName);
        for (int i = 0; i < declaredFieldNames.length; i++) {
            Object v = initArgNodes[i].executeGeneric(frame);
            instance.init(declaredFieldNames[i], v);
        }
        frame.setObject(thisSlot, instance);
        try {
            return body.executeGeneric(frame);
        } finally {
            instance.kill();
        }
    }
}
