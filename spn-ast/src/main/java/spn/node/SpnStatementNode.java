package spn.node;

import com.oracle.truffle.api.frame.VirtualFrame;

/**
 * Base class for all SPN statement nodes.
 *
 * Statements execute for their side effects and produce no value.
 * The single abstract method executeVoid(frame) is the "execute" entry point
 * that Truffle's compiled code will call.
 *
 * KEY TRUFFLE CONCEPT: VirtualFrame
 * The frame is a stack frame that holds local variables. Truffle virtualizes it:
 * in interpreted mode it's a real object, but after Graal compiles the AST,
 * the frame is eliminated entirely -- locals become registers or stack slots.
 * This is why Truffle can match hand-written compiler performance.
 */
public abstract class SpnStatementNode extends SpnNode {

    public abstract void executeVoid(VirtualFrame frame);
}
