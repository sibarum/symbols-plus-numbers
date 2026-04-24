package spn.node.expr;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.node.SpnExpressionNode;
import spn.type.SpnSymbol;

/**
 * Interpolated string literal: {@code "hello ${name}, you are ${age}"}.
 *
 * <p>Invariant: {@code literals.length == exprs.length + 1}. The result is
 * {@code literals[0] + show(exprs[0]) + literals[1] + ... + literals[N]}.
 */
@NodeInfo(shortName = "stringTemplate")
public final class SpnStringTemplateNode extends SpnExpressionNode {

    @CompilationFinal(dimensions = 1)
    private final String[] literals;

    @Children
    private final SpnExpressionNode[] exprs;

    public SpnStringTemplateNode(String[] literals, SpnExpressionNode[] exprs) {
        if (literals.length != exprs.length + 1) {
            throw new IllegalArgumentException(
                    "literals.length must be exprs.length + 1");
        }
        this.literals = literals;
        this.exprs = exprs;
    }

    @Override
    @ExplodeLoop
    public Object executeGeneric(VirtualFrame frame) {
        StringBuilder sb = new StringBuilder(literals[0]);
        for (int i = 0; i < exprs.length; i++) {
            sb.append(show(exprs[i].executeGeneric(frame)));
            sb.append(literals[i + 1]);
        }
        return sb.toString();
    }

    private static String show(Object value) {
        if (value instanceof String s) return s;
        if (value instanceof SpnSymbol sym) return sym.toString();
        return String.valueOf(value);
    }
}
