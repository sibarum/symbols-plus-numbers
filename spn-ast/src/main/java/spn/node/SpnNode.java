package spn.node;

import com.oracle.truffle.api.dsl.TypeSystemReference;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.type.SpnTypes;

/**
 * Base class for all SPN AST nodes.
 *
 * KEY TRUFFLE CONCEPT: @TypeSystemReference
 * This annotation connects every SPN node to our type system. The Truffle DSL code
 * generator uses it to know which types to specialize on and how implicit casts work.
 * Because it's on the base class, all subclasses inherit it automatically.
 *
 * KEY TRUFFLE CONCEPT: Node
 * Every Truffle AST node extends Node. The Node class provides:
 *   - Parent/child tracking (the @Child and @Children annotations)
 *   - Source section association (for debugger/error reporting)
 *   - Node replacement/rewriting (the self-modifying AST mechanism)
 */
@TypeSystemReference(SpnTypes.class)
@NodeInfo(language = "SPN", description = "The abstract base node for all SPN nodes")
public abstract class SpnNode extends Node {
}
