package spn.node;

/**
 * Factory for creating builtin AST nodes from parsed argument expressions.
 * Used by the parser to resolve stdlib and canvas functions without
 * creating a direct dependency on those modules.
 */
@FunctionalInterface
public interface BuiltinFactory {
    SpnExpressionNode create(SpnExpressionNode[] args);
}
