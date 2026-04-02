package spn.node;

import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.RootNode;
import spn.language.SpnLanguage;

/**
 * The root of an SPN AST -- wraps a body expression into an executable unit.
 *
 * KEY TRUFFLE CONCEPT: RootNode
 * A RootNode is the entry point for execution. It corresponds roughly to a "function"
 * or "program." Truffle compiles each RootNode independently -- it's the unit of
 * JIT compilation. When a RootNode is executed enough times, Graal compiles the entire
 * subtree rooted here into optimized machine code.
 *
 * KEY TRUFFLE CONCEPT: FrameDescriptor
 * The FrameDescriptor defines the layout of local variables in this scope.
 * Each local variable gets an integer slot index. The FrameDescriptor is created
 * before the AST is built (typically during parsing) and passed to the RootNode.
 * At runtime, each invocation gets a VirtualFrame with this layout.
 *
 * To create a FrameDescriptor:
 *   var builder = FrameDescriptor.newBuilder();
 *   int xSlot = builder.addSlot(FrameSlotKind.Illegal, "x", null);  // initially unset
 *   int ySlot = builder.addSlot(FrameSlotKind.Illegal, "y", null);
 *   FrameDescriptor descriptor = builder.build();
 */
public final class SpnRootNode extends RootNode {

    @SuppressWarnings("FieldMayBeFinal")
    @Child private SpnExpressionNode body;

    private final String name;

    public SpnRootNode(SpnLanguage language, FrameDescriptor frameDescriptor,
                       SpnExpressionNode body, String name) {
        super(language, frameDescriptor);
        this.body = body;
        this.name = name;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        return body.executeGeneric(frame);
    }

    @Override
    public String getName() {
        return name;
    }
}
