package spn.language;

import spn.type.*;

/**
 * Maps runtime values to user-friendly SPN type names.
 * Used in error messages instead of Java class names.
 */
public final class SpnTypeName {

    private SpnTypeName() {}

    /** Returns the SPN type name for a runtime value. */
    public static String of(Object value) {
        if (value == null) return "null";
        if (value instanceof Long) return "int";
        if (value instanceof Double) return "float";
        if (value instanceof String) return "string";
        if (value instanceof Boolean) return "bool";
        if (value instanceof SpnSymbol s) return "Symbol(:" + s.name() + ")";
        if (value instanceof SpnStructValue sv) return sv.getDescriptor().getName();
        if (value instanceof SpnProductValue pv) return pv.getType().getName();
        if (value instanceof SpnConstrainedValue cv) return cv.getType().getName();
        if (value instanceof SpnTupleValue tv) return "Tuple(" + tv.getElements().length + ")";
        if (value instanceof SpnArrayValue) return "Array";
        if (value instanceof SpnSetValue) return "Set";
        if (value instanceof SpnDictionaryValue) return "Dict";
        if (value instanceof com.oracle.truffle.api.CallTarget) return "Function";
        return value.getClass().getSimpleName();
    }
}
