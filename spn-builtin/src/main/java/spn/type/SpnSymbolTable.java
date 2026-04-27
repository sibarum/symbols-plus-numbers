package spn.type;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The intern pool for SPN symbols.
 *
 * A symbol table maps string names to unique SpnSymbol instances. Interning the
 * same name twice returns the exact same object, enabling reference equality (==)
 * for symbol comparison.
 *
 * Thread-safe: multiple threads can intern symbols concurrently. IDs are assigned
 * atomically and monotonically (0, 1, 2, ...). The ID can be used as a dense array
 * index for O(1) dictionary lookup.
 *
 * Typically there is one SpnSymbolTable per SPN language context (SpnContext).
 * All symbols in a program share the same table.
 *
 * <pre>
 *   var table = new SpnSymbolTable();
 *   var red   = table.intern("red");    // id=0
 *   var green = table.intern("green");  // id=1
 *   var blue  = table.intern("blue");   // id=2
 *   var red2  = table.intern("red");    // same object as red
 *
 *   assert red == red2;
 *   assert red.id() == 0;
 *   assert table.size() == 3;
 * </pre>
 */
public final class SpnSymbolTable {

    private final ConcurrentHashMap<String, SpnSymbol> table = new ConcurrentHashMap<>();
    private final AtomicLong nextId = new AtomicLong(0);

    /**
     * Returns the interned symbol for the given name. If the name has not been
     * seen before, a new SpnSymbol is created with a unique ID. If it has been
     * seen, the existing SpnSymbol is returned.
     */
    public SpnSymbol intern(String name) {
        return table.computeIfAbsent(name, n -> new SpnSymbol(n, nextId.getAndIncrement()));
    }

    /**
     * Returns the interned symbol for the given name, or null if it has not
     * been interned yet.
     */
    public SpnSymbol lookup(String name) {
        return table.get(name);
    }

    /**
     * Returns true if a symbol with the given name has been interned.
     */
    public boolean contains(String name) {
        return table.containsKey(name);
    }

    /**
     * Returns the number of interned symbols.
     */
    public int size() {
        return table.size();
    }

    /**
     * Convenience: intern multiple names and return them as an array.
     * Useful for defining symbol sets.
     */
    public SpnSymbol[] internAll(String... names) {
        SpnSymbol[] symbols = new SpnSymbol[names.length];
        for (int i = 0; i < names.length; i++) {
            symbols[i] = intern(names[i]);
        }
        return symbols;
    }
}
