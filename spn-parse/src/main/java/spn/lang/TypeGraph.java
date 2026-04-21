package spn.lang;

import com.oracle.truffle.api.CallTarget;
import spn.source.SourceRange;
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
        BUILTIN,        // stdlib built-in function (Java-implemented)
        LOCAL_BINDING,  // let NAME = ... (and destructuring elements)
        PARAMETER,      // function/method parameter
        FIELD           // component of a struct type: `type Point(x: float, ...)`
                        // stored with composite name `TypeName.fieldName`
    }

    /** True if {@code kind} is a declaration that opens a new local scope —
     *  i.e., walking backward across a node of this kind means we've left the
     *  previous function's body. Used by local-scope lookup. */
    public static boolean opensScope(Kind kind) {
        return kind == Kind.FUNCTION || kind == Kind.METHOD
                || kind == Kind.OPERATOR || kind == Kind.FACTORY
                || kind == Kind.MACRO;
    }

    /** True if {@code kind} is a local binding kind (let, param, destructure). */
    public static boolean isLocal(Kind kind) {
        return kind == Kind.LOCAL_BINDING || kind == Kind.PARAMETER;
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

        // Source location. `file` is the absolute path (or null for builtins).
        // `nameRange` covers the declaration's NAME token — where a
        // go-to-def click should land. Convention: 1-based line, 0-based col.
        private final String file;
        private final SourceRange nameRange;

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
            this.nameRange = b.nameRange != null ? b.nameRange : SourceRange.UNKNOWN;
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
        /** Where the declaration's NAME identifier lives in the source file.
         *  Returns {@link SourceRange#UNKNOWN} if no position was captured. */
        public SourceRange nameRange()        { return nameRange; }
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
            return kind + " " + name + " @ " + file + ":" + nameRange.startLine();
        }

        // ── Builder ─────────────────────────────────────────────────────────

        public static Builder builder(String name, Kind kind) {
            return new Builder(name, kind);
        }

        public static final class Builder {
            private final String name;
            private final Kind kind;
            private String file;
            private SourceRange nameRange;
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
            /** Position of the declaration's NAME token. Parser convention
             *  (1-based line, 0-based col). */
            public Builder nameRange(SourceRange range)           { this.nameRange = range; return this; }
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

    // Tertiary index: file+line → nodes on that line, in col order. For
    // "what declaration is at this position?", incremental invalidation, and
    // local-scope walks (multiple locals can share a line).
    private final Map<String, TreeMap<Integer, List<Node>>> byFileLine = new LinkedHashMap<>();

    // Quaternary index: CallTarget → node. Lets the IDE map a resolved dispatch
    // (which carries only a CallTarget) back to the declaration's source location.
    private final IdentityHashMap<CallTarget, Node> byCallTarget = new IdentityHashMap<>();

    // ── Mutation ─────────────────────────────────────────────────────────────

    /** Add a node to the graph. */
    public void add(Node node) {
        byName.computeIfAbsent(node.name(), k -> new ArrayList<>()).add(node);
        byKind.computeIfAbsent(node.kind(), k -> new ArrayList<>()).add(node);
        if (node.file() != null && node.nameRange().isKnown()) {
            TreeMap<Integer, List<Node>> lines = byFileLine.computeIfAbsent(
                    node.file(), k -> new TreeMap<>());
            List<Node> onLine = lines.computeIfAbsent(
                    node.nameRange().startLine(), k -> new ArrayList<>());
            // Keep within-line order by starting column.
            int col = node.nameRange().startCol();
            int ins = 0;
            while (ins < onLine.size()
                    && onLine.get(ins).nameRange().startCol() <= col) ins++;
            onLine.add(ins, node);
        }
        if (node.callTarget() != null) {
            byCallTarget.put(node.callTarget(), node);
        }
    }

    /**
     * Remove all nodes from the given file at or after the given line.
     * Used for incremental re-parse: invalidate from the edit point onward,
     * then re-populate from the parser.
     */
    public void invalidateFrom(String file, int fromLine) {
        TreeMap<Integer, List<Node>> fileNodes = byFileLine.get(file);
        if (fileNodes == null) return;

        // Collect nodes to remove (at or after fromLine)
        NavigableMap<Integer, List<Node>> tail = fileNodes.tailMap(fromLine, true);
        List<Node> toRemove = new ArrayList<>();
        for (List<Node> onLine : tail.values()) toRemove.addAll(onLine);
        tail.clear();

        // Remove from other indices
        for (Node node : toRemove) {
            List<Node> nameList = byName.get(node.name());
            if (nameList != null) nameList.remove(node);
            List<Node> kindList = byKind.get(node.kind());
            if (kindList != null) kindList.remove(node);
            if (node.callTarget() != null) {
                byCallTarget.remove(node.callTarget());
            }
        }
    }

    /** Remove all nodes. */
    public void clear() {
        byName.clear();
        byKind.clear();
        byFileLine.clear();
        byCallTarget.clear();
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

    /** All nodes in a file, ordered by (line, col). */
    public Collection<Node> byFile(String file) {
        TreeMap<Integer, List<Node>> fileNodes = byFileLine.get(file);
        if (fileNodes == null) return List.of();
        List<Node> all = new ArrayList<>();
        for (List<Node> onLine : fileNodes.values()) all.addAll(onLine);
        return all;
    }

    /** The declaration that owns a given CallTarget. Populated when the parser
     *  registers functions/operators/methods with both. Returns null for
     *  builtins and other nodes without a CallTarget (types, structs, etc.). */
    public Node byCallTarget(CallTarget ct) {
        return ct == null ? null : byCallTarget.get(ct);
    }

    /** The node at or just before the given line in a file (for "go to
     *  definition"). Prefers the last node on {@code line} if any exist. */
    public Node atOrBefore(String file, int line) {
        TreeMap<Integer, List<Node>> fileNodes = byFileLine.get(file);
        if (fileNodes == null) return null;
        Map.Entry<Integer, List<Node>> entry = fileNodes.floorEntry(line);
        if (entry == null || entry.getValue().isEmpty()) return null;
        List<Node> onLine = entry.getValue();
        return onLine.get(onLine.size() - 1);
    }

    /**
     * Find a local binding (let or parameter) named {@code name} that is in
     * scope at the given position.
     *
     * <p>Walks backward through the file's nodes by (line, col). Returns the
     * first local/parameter node with a matching name. Stops (returns null)
     * if a scope-opening declaration (function, method, operator, factory,
     * macro) is encountered before the match — that means the click row is
     * outside the local's scope.
     *
     * <p>Positions are parser convention (1-based line, 0-based col).
     */
    public Node findLocalInScope(String file, int line, int col, String name) {
        TreeMap<Integer, List<Node>> fileNodes = byFileLine.get(file);
        if (fileNodes == null) return null;
        // Walk lines from `line` down to 0.
        for (var entry : fileNodes.headMap(line, true).descendingMap().entrySet()) {
            int entryLine = entry.getKey();
            List<Node> onLine = entry.getValue();
            // On the click's own line, only consider nodes whose col is at or
            // before the click (a local declared later on the same line can't
            // be referenced here).
            for (int i = onLine.size() - 1; i >= 0; i--) {
                Node n = onLine.get(i);
                if (entryLine == line && n.nameRange().startCol() > col) continue;
                if (opensScope(n.kind())) {
                    // Crossing a function boundary — not in scope. But params
                    // on the function header line ARE in scope for the body,
                    // so they're separate nodes we would've already returned.
                    return null;
                }
                if (isLocal(n.kind()) && n.name().equals(name)) return n;
            }
        }
        return null;
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
