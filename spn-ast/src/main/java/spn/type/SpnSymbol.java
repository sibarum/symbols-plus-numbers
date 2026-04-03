package spn.type;

/**
 * An interned symbol -- a named atom with a unique integer identity.
 *
 * Symbols are the fundamental atoms of symbolic computation in SPN.
 * Each symbol has a human-readable name (e.g., "red") and a unique long ID
 * assigned by the SpnSymbolTable during interning. Two symbols with the
 * same name from the same table are always the same object (reference equality).
 *
 * Performance:
 *   - Comparison is pointer equality (==), which is a single machine instruction
 *   - The long ID can be used as an array index for O(1) dictionary lookup
 *   - Symbols are immutable and allocation-free after interning
 *
 * Symbols participate in the constraint system: a constrained symbol type
 * restricts which symbols are valid (e.g., Color = Symbol where oneOf(:red, :green, :blue)).
 *
 * <pre>
 *   SpnSymbolTable table = new SpnSymbolTable();
 *   SpnSymbol red   = table.intern("red");
 *   SpnSymbol green = table.intern("green");
 *
 *   red == table.intern("red")   // true -- same object
 *   red.id() != green.id()       // true -- unique IDs
 * </pre>
 */
public final class SpnSymbol {

    private final String name;
    private final long id;

    SpnSymbol(String name, long id) {
        this.name = name;
        this.id = id;
    }

    /** The human-readable name of this symbol. */
    public String name() {
        return name;
    }

    /** The unique integer identity, assigned during interning. */
    public long id() {
        return id;
    }

    @Override
    public String toString() {
        return ":" + name;
    }

    // Identity is reference equality -- interned symbols are singletons.
    // We intentionally do NOT override equals/hashCode. Two SpnSymbol objects
    // are the same symbol if and only if they are the same object (==).
}
