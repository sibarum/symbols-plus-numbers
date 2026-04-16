package spn.lang;

import com.oracle.truffle.api.CallTarget;
import spn.type.FieldDescriptor;
import spn.type.FieldType;
import spn.type.SpnFunctionDescriptor;
import spn.type.SpnStructDescriptor;
import spn.type.SpnTypeDescriptor;
import spn.type.SpnVariantSet;

import java.util.*;

/**
 * Unified symbol graph for the SPN language.
 *
 * Every declaration in an SPN program — types, structs, functions, operators,
 * methods, promotions, macros, constants — is represented as a {@link Node}
 * in this graph. Nodes carry rich metadata: source location, parameter types,
 * return type, purity, the set of declarations they reference in their body,
 * and a kind tag for filtering.
 *
 * <h3>Three consumers, one data structure</h3>
 * <ul>
 *   <li><b>Parser</b>: populates the graph during the structural pass. Each
 *       {@code type}, {@code pure}, {@code promote}, etc. declaration
 *       creates a Node.</li>
 *   <li><b>Inference pass</b> (future): walks the graph to resolve operator
 *       dispatch, insert promotions, check exhaustiveness, and assign types
 *       to expressions. Enriches nodes with inferred metadata.</li>
 *   <li><b>GUI</b>: reads the graph for the module browser, import browser,
 *       Trace Mode cross-file links, diagnostic underlines, hover info, and
 *       "go to definition" navigation.</li>
 * </ul>
 *
 * <h3>Incrementality</h3>
 * SPN's "definition order matters" rule means a change at line N can only
 * affect nodes at line N or later. The graph supports efficient invalidation:
 * {@link #invalidateFrom(String, int)} removes all nodes from a given file
 * at or after a line number, so the parser can re-populate just the changed
 * portion.
 *
 * <h3>Replaces</h3>
 * This class replaces the ~14 separate registries previously scattered across
 * SpnParser (structRegistry, typeRegistry, variantRegistry, functionRegistry,
 * functionDescriptorRegistry, operatorRegistry, promotionRegistry,
 * methodRegistry, factoryRegistry, macroRegistry, constantRegistry, etc.).
 */
public final class TypeGraph {

    // ── Node kinds ──────────────────────────────────────────────────────────

    public enum Kind {
        TYPE,           // type Rational(int, int) where ...
        STRUCT,         // type Point(x: float, y: float)  [struct-style, named fields]
        VARIANT,        // data Shape = Circle | Rect | Triangle
        FUNCTION,       // pure add(int, int) -> int = ...
        OPERATOR,       // pure +(Rational, Rational) -> Rational = ...
        METHOD,         // pure Rational.inv() -> Rational = ...
        PROMOTION,      // promote int -> Rational = ...
        MACRO,          // macro deriveDouble(T) = { ... }
        CONSTANT,       // const Rational.one = Rational(1, 1)
        FACTORY,        // constructor overload: pure Rational(int) -> Rational = ...
        BUILTIN         // stdlib built-in function (Java-implemented)
    }

    // ── Graph node ──────────────────────────────────────────────────────────

    /**
     * A single declaration in the program. Immutable after construction
     * (except for {@link #references}, which may be populated lazily by
     * the inference pass).
     */
    public static final class Node {
        private final String name;
        private final Kind kind;

        // Source location
        private final String file;
        private final int line;
        private final int col;
        private final int endLine;
        private final int endCol;

        // Type metadata (null when not applicable)
        private final FieldType[] paramTypes;
        private final FieldType returnType;
        private final boolean isPure;

        // Executable target (null for type/struct/variant/macro declarations)
        private final CallTarget callTarget;

        // Descriptors (at most one is non-null, depending on kind)
        private final SpnStructDescriptor structDescriptor;
        private final SpnTypeDescriptor typeDescriptor;
        private final SpnVariantSet variantSet;
        private final SpnFunctionDescriptor functionDescriptor;

        // Macro body (only for MACRO kind)
        private final List<SpnParseToken> macroBody;
        private final List<String> macroParams;

        // References: names of other declarations this node's body uses.
        // Populated by the inference pass for dependency tracking.
        private Set<String> references;

        private Node(Builder b) {
            this.name = b.name;
            this.kind = b.kind;
            this.file = b.file;
            this.line = b.line;
            this.col = b.col;
            this.endLine = b.endLine;
            this.endCol = b.endCol;
            this.paramTypes = b.paramTypes;
            this.returnType = b.returnType;
            this.isPure = b.isPure;
            this.callTarget = b.callTarget;
            this.structDescriptor = b.structDescriptor;
            this.typeDescriptor = b.typeDescriptor;
            this.variantSet = b.variantSet;
            this.functionDescriptor = b.functionDescriptor;
            this.macroBody = b.macroBody;
            this.macroParams = b.macroParams;
            this.references = b.references;
        }

        // ── Accessors ───────────────────────────────────────────────────────

        public String name()                  { return name; }
        public Kind kind()                    { return kind; }
        public String file()                  { return file; }
        public int line()                     { return line; }
        public int col()                      { return col; }
        public int endLine()                  { return endLine; }
        public int endCol()                   { return endCol; }
        public FieldType[] paramTypes()       { return paramTypes; }
        public FieldType returnType()         { return returnType; }
        public boolean isPure()               { return isPure; }
        public CallTarget callTarget()        { return callTarget; }
        public SpnStructDescriptor structDescriptor()   { return structDescriptor; }
        public SpnTypeDescriptor typeDescriptor()       { return typeDescriptor; }
        public SpnVariantSet variantSet()               { return variantSet; }
        public SpnFunctionDescriptor functionDescriptor() { return functionDescriptor; }
        public List<SpnParseToken> macroBody()          { return macroBody; }
        public List<String> macroParams()               { return macroParams; }
        public Set<String> references()       { return references != null ? references : Set.of(); }

        public void setReferences(Set<String> refs) { this.references = refs; }

        @Override
        public String toString() {
            return kind + " " + name + " @ " + file + ":" + line;
        }

        // ── Builder ─────────────────────────────────────────────────────────

        public static Builder builder(String name, Kind kind) {
            return new Builder(name, kind);
        }

        public static final class Builder {
            private final String name;
            private final Kind kind;
            private String file;
            private int line, col, endLine, endCol;
            private FieldType[] paramTypes;
            private FieldType returnType;
            private boolean isPure;
            private CallTarget callTarget;
            private SpnStructDescriptor structDescriptor;
            private SpnTypeDescriptor typeDescriptor;
            private SpnVariantSet variantSet;
            private SpnFunctionDescriptor functionDescriptor;
            private List<SpnParseToken> macroBody;
            private List<String> macroParams;
            private Set<String> references;

            private Builder(String name, Kind kind) {
                this.name = name;
                this.kind = kind;
            }

            public Builder file(String file)                     { this.file = file; return this; }
            public Builder line(int line)                         { this.line = line; return this; }
            public Builder col(int col)                           { this.col = col; return this; }
            public Builder endLine(int endLine)                   { this.endLine = endLine; return this; }
            public Builder endCol(int endCol)                     { this.endCol = endCol; return this; }
            public Builder paramTypes(FieldType... types)         { this.paramTypes = types; return this; }
            public Builder returnType(FieldType type)             { this.returnType = type; return this; }
            public Builder pure(boolean isPure)                   { this.isPure = isPure; return this; }
            public Builder callTarget(CallTarget ct)              { this.callTarget = ct; return this; }
            public Builder structDescriptor(SpnStructDescriptor d){ this.structDescriptor = d; return this; }
            public Builder typeDescriptor(SpnTypeDescriptor d)    { this.typeDescriptor = d; return this; }
            public Builder variantSet(SpnVariantSet vs)           { this.variantSet = vs; return this; }
            public Builder functionDescriptor(SpnFunctionDescriptor d) { this.functionDescriptor = d; return this; }
            public Builder macroBody(List<SpnParseToken> tokens)  { this.macroBody = tokens; return this; }
            public Builder macroParams(List<String> params)       { this.macroParams = params; return this; }
            public Builder references(Set<String> refs)           { this.references = refs; return this; }

            public Node build() { return new Node(this); }
        }
    }

    // ── Graph storage ───────────────────────────────────────────────────────

    // Primary index: name → node(s). Most names have one node, but operators
    // and factories can have multiple overloads under the same name.
    private final Map<String, List<Node>> byName = new LinkedHashMap<>();

    // Secondary index: kind → nodes. For fast "give me all operators" queries.
    private final Map<Kind, List<Node>> byKind = new LinkedHashMap<>();

    // Tertiary index: file+line → node. For "what declaration is at this position?"
    // and for incremental invalidation.
    private final Map<String, TreeMap<Integer, Node>> byFileLine = new LinkedHashMap<>();

    // ── Mutation ─────────────────────────────────────────────────────────────

    /** Add a node to the graph. */
    public void add(Node node) {
        byName.computeIfAbsent(node.name(), k -> new ArrayList<>()).add(node);
        byKind.computeIfAbsent(node.kind(), k -> new ArrayList<>()).add(node);
        if (node.file() != null) {
            byFileLine.computeIfAbsent(node.file(), k -> new TreeMap<>())
                       .put(node.line(), node);
        }
    }

    /**
     * Remove all nodes from the given file at or after the given line.
     * Used for incremental re-parse: invalidate from the edit point onward,
     * then re-populate from the parser.
     */
    public void invalidateFrom(String file, int fromLine) {
        TreeMap<Integer, Node> fileNodes = byFileLine.get(file);
        if (fileNodes == null) return;

        // Collect nodes to remove (at or after fromLine)
        NavigableMap<Integer, Node> tail = fileNodes.tailMap(fromLine, true);
        List<Node> toRemove = new ArrayList<>(tail.values());
        tail.clear();

        // Remove from other indices
        for (Node node : toRemove) {
            List<Node> nameList = byName.get(node.name());
            if (nameList != null) nameList.remove(node);
            List<Node> kindList = byKind.get(node.kind());
            if (kindList != null) kindList.remove(node);
        }
    }

    /** Remove all nodes. */
    public void clear() {
        byName.clear();
        byKind.clear();
        byFileLine.clear();
    }

    // ── Queries ─────────────────────────────────────────────────────────────

    /** All nodes with the given name (may be empty). */
    public List<Node> lookup(String name) {
        return byName.getOrDefault(name, List.of());
    }

    /** First node with the given name, or null. */
    public Node lookupFirst(String name) {
        List<Node> nodes = byName.get(name);
        return nodes != null && !nodes.isEmpty() ? nodes.getFirst() : null;
    }

    /** All nodes of a given kind. */
    public List<Node> byKind(Kind kind) {
        return byKind.getOrDefault(kind, List.of());
    }

    /** All nodes in a file, ordered by line number. */
    public Collection<Node> byFile(String file) {
        TreeMap<Integer, Node> fileNodes = byFileLine.get(file);
        return fileNodes != null ? fileNodes.values() : List.of();
    }

    /** The node at or just before the given line in a file (for "go to definition"). */
    public Node atOrBefore(String file, int line) {
        TreeMap<Integer, Node> fileNodes = byFileLine.get(file);
        if (fileNodes == null) return null;
        Map.Entry<Integer, Node> entry = fileNodes.floorEntry(line);
        return entry != null ? entry.getValue() : null;
    }

    /** All operator overloads for a given operator symbol. */
    public List<Node> operators(String opSymbol) {
        List<Node> all = byName.getOrDefault(opSymbol, List.of());
        return all.stream().filter(n -> n.kind() == Kind.OPERATOR).toList();
    }

    /** All promotions in the graph. */
    public List<Node> promotions() {
        return byKind.getOrDefault(Kind.PROMOTION, List.of());
    }

    /** All nodes that reference the given declaration name. */
    public List<Node> referencesTo(String name) {
        List<Node> result = new ArrayList<>();
        for (List<Node> nodes : byName.values()) {
            for (Node n : nodes) {
                if (n.references().contains(name)) result.add(n);
            }
        }
        return result;
    }

    /** Total number of nodes in the graph. */
    public int size() {
        int count = 0;
        for (List<Node> nodes : byName.values()) count += nodes.size();
        return count;
    }

    // ── Convenience: typed lookups for common queries ────────────────────────

    /** Look up a struct descriptor by type name. */
    public SpnStructDescriptor struct(String name) {
        Node n = lookupFirst(name);
        return n != null ? n.structDescriptor() : null;
    }

    /** Look up a type descriptor by type name. */
    public SpnTypeDescriptor type(String name) {
        Node n = lookupFirst(name);
        return n != null ? n.typeDescriptor() : null;
    }

    /** Look up a variant set by union name. */
    public SpnVariantSet variant(String name) {
        Node n = lookupFirst(name);
        return n != null ? n.variantSet() : null;
    }

    /** Look up a function's CallTarget by name. */
    public CallTarget function(String name) {
        Node n = lookupFirst(name);
        return n != null ? n.callTarget() : null;
    }

    /** Look up a function descriptor by name. */
    public SpnFunctionDescriptor functionDescriptor(String name) {
        Node n = lookupFirst(name);
        return n != null ? n.functionDescriptor() : null;
    }
}
