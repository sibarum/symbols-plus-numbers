package spn.gui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A lightweight index of all available functions for autocomplete.
 * Built once at startup from stdlib and canvas builtins.
 */
public class FunctionIndex {

    /** A function entry with its name, module, and parameter signature. */
    public record Entry(String name, String module, String signature) {
        @Override public String toString() { return name; }
    }

    private final List<Entry> entries = new ArrayList<>();

    /** Register a function. */
    public void add(String name, String module, String signature) {
        entries.add(new Entry(name, module, signature));
    }

    /** All registered entries. */
    public List<Entry> all() {
        return Collections.unmodifiableList(entries);
    }

    /**
     * Find entries whose name starts with the given prefix (case-insensitive).
     */
    public List<Entry> matchPrefix(String prefix) {
        if (prefix == null || prefix.isEmpty()) return all();
        String lower = prefix.toLowerCase();
        List<Entry> result = new ArrayList<>();
        for (Entry e : entries) {
            if (e.name().toLowerCase().startsWith(lower)) {
                result.add(e);
            }
        }
        return result;
    }

    /**
     * Build the index from stdlib and canvas builtins.
     */
    public static FunctionIndex buildDefault() {
        FunctionIndex index = new FunctionIndex();

        // Stdlib functions
        for (var entry : spn.stdlib.gen.SpnStdlibRegistry.allBuiltins()) {
            String sig = formatDescriptor(entry.descriptor());
            index.add(entry.name(), entry.module(), sig);
        }

        // Canvas functions (no SpnFunctionDescriptor, use hardcoded signatures)
        index.add("canvas", "Canvas", "canvas(w, h)");
        index.add("show", "Canvas", "show()");
        index.add("clear", "Canvas", "clear(r, g, b)");
        index.add("fill", "Canvas", "fill(r, g, b)");
        index.add("stroke", "Canvas", "stroke(r, g, b)");
        index.add("strokeWeight", "Canvas", "strokeWeight(w)");
        index.add("rect", "Canvas", "rect(x, y, w, h)");
        index.add("circle", "Canvas", "circle(x, y, r)");
        index.add("line", "Canvas", "line(x0, y0, x1, y1)");
        index.add("text", "Canvas", "text(x, y, str, scale)");
        index.add("animate", "Canvas", "animate(fn, duration)");

        return index;
    }

    private static String formatDescriptor(spn.type.SpnFunctionDescriptor desc) {
        if (desc == null) return "";
        StringBuilder sb = new StringBuilder();
        sb.append(desc.getName()).append('(');
        var params = desc.getParams();
        for (int i = 0; i < params.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(params[i].name());
        }
        sb.append(')');
        return sb.toString();
    }
}
