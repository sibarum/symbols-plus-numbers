package spn.stdlib.json;

import spn.type.*;

import java.util.Map;

/**
 * Serializes SPN values to JSON strings.
 *
 * Mapping:
 *   SpnDictionaryValue → JSON object
 *   SpnArrayValue      → JSON array
 *   SpnTupleValue      → JSON array
 *   String             → JSON string
 *   Long               → JSON integer
 *   Double             → JSON float
 *   Boolean            → JSON boolean
 *   SpnStructValue     → JSON object with "type" + fields
 *   None (Option)      → null
 */
final class JsonSerializer {

    static String serialize(Object value) {
        StringBuilder sb = new StringBuilder();
        write(sb, value);
        return sb.toString();
    }

    static String serializePretty(Object value) {
        StringBuilder sb = new StringBuilder();
        writePretty(sb, value, 0);
        return sb.toString();
    }

    private static void write(StringBuilder sb, Object value) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof String s) {
            writeString(sb, s);
        } else if (value instanceof Long l) {
            sb.append(l);
        } else if (value instanceof Double d) {
            if (d.isInfinite() || d.isNaN()) sb.append("null");
            else sb.append(d);
        } else if (value instanceof Boolean b) {
            sb.append(b);
        } else if (value instanceof SpnDictionaryValue dict) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<SpnSymbol, Object> entry : dict.getEntries().entrySet()) {
                if (!first) sb.append(',');
                first = false;
                writeString(sb, entry.getKey().name());
                sb.append(':');
                write(sb, entry.getValue());
            }
            sb.append('}');
        } else if (value instanceof SpnArrayValue arr) {
            sb.append('[');
            Object[] elems = arr.getElements();
            for (int i = 0; i < elems.length; i++) {
                if (i > 0) sb.append(',');
                write(sb, elems[i]);
            }
            sb.append(']');
        } else if (value instanceof SpnTupleValue tuple) {
            sb.append('[');
            for (int i = 0; i < tuple.arity(); i++) {
                if (i > 0) sb.append(',');
                write(sb, tuple.get(i));
            }
            sb.append(']');
        } else if (value instanceof SpnStructValue sv) {
            // Check for None (Option ADT)
            if (sv.getDescriptor() == spn.stdlib.option.SpnOptionDescriptors.NONE) {
                sb.append("null");
            } else if (sv.getDescriptor() == spn.stdlib.option.SpnOptionDescriptors.SOME) {
                write(sb, sv.getFields()[0]);
            } else {
                // Generic struct → {"_type": "Name", "field1": val, ...}
                sb.append('{');
                writeString(sb, "_type");
                sb.append(':');
                writeString(sb, sv.getDescriptor().getName());
                String[] names = sv.getDescriptor().getFieldNames();
                Object[] fields = sv.getFields();
                for (int i = 0; i < fields.length; i++) {
                    sb.append(',');
                    writeString(sb, names[i]);
                    sb.append(':');
                    write(sb, fields[i]);
                }
                sb.append('}');
            }
        } else if (value instanceof SpnSymbol sym) {
            writeString(sb, sym.name());
        } else {
            // Fallback: stringify
            writeString(sb, value.toString());
        }
    }

    private static void writePretty(StringBuilder sb, Object value, int indent) {
        if (value instanceof SpnDictionaryValue dict) {
            sb.append("{\n");
            boolean first = true;
            for (Map.Entry<SpnSymbol, Object> entry : dict.getEntries().entrySet()) {
                if (!first) sb.append(",\n");
                first = false;
                writeIndent(sb, indent + 1);
                writeString(sb, entry.getKey().name());
                sb.append(": ");
                writePretty(sb, entry.getValue(), indent + 1);
            }
            sb.append('\n');
            writeIndent(sb, indent);
            sb.append('}');
        } else if (value instanceof SpnArrayValue arr) {
            Object[] elems = arr.getElements();
            if (elems.length == 0) { sb.append("[]"); return; }
            sb.append("[\n");
            for (int i = 0; i < elems.length; i++) {
                if (i > 0) sb.append(",\n");
                writeIndent(sb, indent + 1);
                writePretty(sb, elems[i], indent + 1);
            }
            sb.append('\n');
            writeIndent(sb, indent);
            sb.append(']');
        } else {
            write(sb, value);
        }
    }

    private static void writeString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\t' -> sb.append("\\t");
                case '\r' -> sb.append("\\r");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        sb.append('"');
    }

    private static void writeIndent(StringBuilder sb, int level) {
        for (int i = 0; i < level; i++) sb.append("  ");
    }
}
