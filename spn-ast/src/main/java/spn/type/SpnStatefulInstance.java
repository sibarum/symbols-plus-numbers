package spn.type;

import spn.language.SpnException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A heap-allocated, mutable instance of a {@code stateful type}.
 *
 * <p>Created when a {@code T(args) { body }} block executes; destroyed (marked
 * dead) when the block returns. Any access after the block exits throws a
 * clear runtime error — enforcing the block-scoped lifetime without a GC.
 *
 * <p>Fields may be added ad-hoc at parse time during the block body (the
 * parser tracks the shape sequentially); at runtime, this class simply
 * stores name → value pairs with no schema enforcement. Compile-time
 * shape tracking prevents inconsistent access.
 *
 * <p>Typically referenced by {@code do()} closures, which hold a pointer to
 * the instance for the duration of their validity.
 */
public final class SpnStatefulInstance {

    private final String typeName;
    private final Map<String, Object> fields;
    private boolean alive = true;

    public SpnStatefulInstance(String typeName) {
        this.typeName = typeName;
        this.fields = new LinkedHashMap<>();
    }

    public String getTypeName() { return typeName; }

    public boolean isAlive() { return alive; }

    /** Called by the block-exit path to invalidate the instance. */
    public void kill() { this.alive = false; }

    public Object get(String name) {
        checkAlive("read");
        if (!fields.containsKey(name)) {
            throw new SpnException("stateful " + typeName
                    + " has no field '" + name + "'", null);
        }
        return fields.get(name);
    }

    public void set(String name, Object value) {
        checkAlive("write");
        fields.put(name, value);
    }

    /** Initialize a declared field during block construction (before body runs). */
    public void init(String name, Object value) {
        fields.put(name, value);
    }

    private void checkAlive(String op) {
        if (!alive) {
            throw new SpnException("stateful " + typeName
                    + " instance is out of scope — cannot " + op
                    + " after its block has exited", null);
        }
    }

    @Override
    public String toString() {
        return typeName + fields + (alive ? "" : " [dead]");
    }
}
