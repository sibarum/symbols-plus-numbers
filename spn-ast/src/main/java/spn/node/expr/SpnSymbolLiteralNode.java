package spn.node.expr;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.node.SpnExpressionNode;
import spn.type.SpnSymbol;

/**
 * Produces a constant SpnSymbol value.
 *
 * The symbol is interned at AST construction time (by the parser/AST builder),
 * so this node just returns the pre-interned singleton. Zero allocation at runtime.
 *
 * <pre>
 *   var table = new SpnSymbolTable();
 *   var red = table.intern("red");
 *   var node = new SpnSymbolLiteralNode(red);
 *   // execute → :red
 * </pre>
 */
@NodeInfo(shortName = "symbol")
public final class SpnSymbolLiteralNode extends SpnExpressionNode {

    private final SpnSymbol symbol;

    public SpnSymbolLiteralNode(SpnSymbol symbol) {
        this.symbol = symbol;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame) {
        return symbol;
    }
}
