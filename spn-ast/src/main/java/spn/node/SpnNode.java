package spn.node;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.dsl.TypeSystemReference;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.type.FieldType;
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

    static {
        FieldType.installCallableTest(v -> v instanceof CallTarget);
    }

    // ── Source location (set by parser, read by SpnException and IDE) ──────

    private String sourceFile;
    private int sourceLine = -1;
    private int sourceCol = -1;
    private int sourceEndLine = -1;
    private int sourceEndCol = -1;

    public void setSourcePosition(String file, int line, int col) {
        this.sourceFile = file;
        this.sourceLine = line;
        this.sourceCol = col;
    }

    public void setSourceSpan(String file, int line, int col, int endLine, int endCol) {
        this.sourceFile = file;
        this.sourceLine = line;
        this.sourceCol = col;
        this.sourceEndLine = endLine;
        this.sourceEndCol = endCol;
    }

    public String getSourceFile() { return sourceFile; }
    public int getSourceLine() { return sourceLine; }
    public int getSourceCol() { return sourceCol; }
    public int getSourceEndLine() { return sourceEndLine; }
    public int getSourceEndCol() { return sourceEndCol; }
    public boolean hasSourcePosition() { return sourceLine >= 0; }
    public boolean hasSourceSpan() { return sourceEndLine >= 0; }
}
