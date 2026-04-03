package spn.type;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * An immutable, symbol-keyed dictionary.
 *
 * Keys are SpnSymbol instances (interned to unique longs). Values are any SPN value.
 * All "mutating" operations return new dictionaries, preserving immutability for
 * the pure function model.
 *
 * Since keys are symbols (reference equality, long IDs), lookup is efficient:
 * the backing LinkedHashMap uses SpnSymbol identity, and a future optimization
 * could use the symbol's long ID as a direct array index for small dictionaries.
 *
 * <pre>
 *   var table = new SpnSymbolTable();
 *   var name = table.intern("name");
 *   var age  = table.intern("age");
 *
 *   var dict = SpnDictionaryValue.of(FieldType.UNTYPED,
 *       name, "Alice",
 *       age, 30L);
 *
 *   dict.get(name)              // "Alice"
 *   dict.set(age, 31L)          // new dict with age=31, original unchanged
 *   dict.remove(age)            // new dict without age key
 *   dict.containsKey(name)      // true
 * </pre>
 */
public final class SpnDictionaryValue {

    private final FieldType valueType;
    private final Map<SpnSymbol, Object> entries;

    private SpnDictionaryValue(FieldType valueType, Map<SpnSymbol, Object> entries) {
        this.valueType = valueType;
        this.entries = entries;
    }

    /**
     * Creates a dictionary from alternating key-value pairs.
     * <pre>
     *   SpnDictionaryValue.of(FieldType.UNTYPED, nameSymbol, "Alice", ageSymbol, 30L)
     * </pre>
     */
    public static SpnDictionaryValue of(FieldType valueType, Object... keysAndValues) {
        var map = new LinkedHashMap<SpnSymbol, Object>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            map.put((SpnSymbol) keysAndValues[i], keysAndValues[i + 1]);
        }
        return new SpnDictionaryValue(valueType, map);
    }

    /** Creates an empty dictionary. */
    public static SpnDictionaryValue empty(FieldType valueType) {
        return new SpnDictionaryValue(valueType, new LinkedHashMap<>());
    }

    /** Creates from an existing map (takes ownership). */
    public static SpnDictionaryValue wrap(FieldType valueType, Map<SpnSymbol, Object> entries) {
        return new SpnDictionaryValue(valueType, entries);
    }

    // ── Queries ─────────────────────────────────────────────────────────────

    public FieldType getValueType() {
        return valueType;
    }

    public Object get(SpnSymbol key) {
        return entries.get(key);
    }

    public boolean containsKey(SpnSymbol key) {
        return entries.containsKey(key);
    }

    public int size() {
        return entries.size();
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    /** Returns all keys as an array. */
    public SpnSymbol[] keys() {
        return entries.keySet().toArray(new SpnSymbol[0]);
    }

    /** Returns all values as an array. */
    public Object[] values() {
        return entries.values().toArray();
    }

    /** Returns the backing map (unmodifiable view). */
    public Map<SpnSymbol, Object> getEntries() {
        return Collections.unmodifiableMap(entries);
    }

    // ── Functional operations (return new dictionaries) ─────────────────────

    /** Returns a new dictionary with the key set to value. */
    public SpnDictionaryValue set(SpnSymbol key, Object value) {
        var newMap = new LinkedHashMap<>(entries);
        newMap.put(key, value);
        return new SpnDictionaryValue(valueType, newMap);
    }

    /** Returns a new dictionary with the key removed. */
    public SpnDictionaryValue remove(SpnSymbol key) {
        var newMap = new LinkedHashMap<>(entries);
        newMap.remove(key);
        return new SpnDictionaryValue(valueType, newMap);
    }

    /** Returns a new dictionary merging other into this (other's values win on conflict). */
    public SpnDictionaryValue merge(SpnDictionaryValue other) {
        var newMap = new LinkedHashMap<>(entries);
        newMap.putAll(other.entries);
        return new SpnDictionaryValue(valueType, newMap);
    }

    // ── Object methods ──────────────────────────────────────────────────────

    @Override
    public String toString() {
        var sb = new StringBuilder("{");
        boolean first = true;
        for (var entry : entries.entrySet()) {
            if (!first) sb.append(", ");
            sb.append(entry.getKey()).append(": ").append(entry.getValue());
            first = false;
        }
        return sb.append("}").toString();
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof SpnDictionaryValue other)) return false;
        return entries.equals(other.entries);
    }

    @Override
    public int hashCode() {
        return entries.hashCode();
    }
}
