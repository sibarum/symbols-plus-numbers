package spn.trace;

/**
 * A single event in an execution trace.
 *
 * Events form a tree via parentSequence: each CALL has RETURN/ERROR as children,
 * and nested calls reference their parent. This allows reconstructing the full
 * call tree for interactive exploration.
 *
 * @param sequence       monotonic event ID (0, 1, 2, ...)
 * @param kind           event type
 * @param location       human-readable location ("Rational.inv", "+(Rational,Rational)")
 * @param pure           whether the function is pure (enables replay)
 * @param inputs         snapshotted argument values at call time
 * @param output         return value (RETURN only, null for others)
 * @param error          error message (ERROR only, null for others)
 * @param parentSequence sequence ID of the enclosing CALL (-1 for top-level)
 * @param durationNanos  wall-clock duration in nanoseconds (RETURN/ERROR only)
 * @param sourceFile     absolute path of the file that declared this function
 *                       (null for top-level and non-file-backed code)
 */
public record TraceEvent(
        long sequence,
        Kind kind,
        String location,
        boolean pure,
        Object[] inputs,
        Object output,
        String error,
        long parentSequence,
        long durationNanos,
        String sourceFile
) {
    public enum Kind {
        CALL,       // function entry
        RETURN,     // function exit (normal)
        ERROR,      // function exit (exception)
        ASSIGN      // local variable assignment (location = var name, output = value)
    }

    /** Compact display of inputs for UI rendering. */
    public String inputsSummary() {
        if (inputs == null || inputs.length == 0) return "()";
        var sb = new StringBuilder("(");
        for (int i = 0; i < inputs.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(summarize(inputs[i]));
        }
        return sb.append(")").toString();
    }

    /** Compact display of the output value. */
    public String outputSummary() {
        if (output == null) return "null";
        return summarize(output);
    }

    public static String summarizeValue(Object val) { return summarize(val); }

    private static String summarize(Object val) {
        if (val == null) return "null";
        String s = val.toString();
        return s.length() > 60 ? s.substring(0, 57) + "..." : s;
    }
}
