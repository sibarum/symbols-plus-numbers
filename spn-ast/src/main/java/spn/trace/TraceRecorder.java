package spn.trace;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * Records execution trace events during a traced run.
 *
 * <p>Thread-local singleton: set via {@link #begin()}, access via {@link #current()},
 * finalize via {@link #end()}. When no recorder is active, instrumented code
 * skips tracing with zero overhead (null check on thread-local).
 *
 * <p>The recorder maintains a call stack to track parent-child relationships
 * between events. Each CALL pushes, each RETURN/ERROR pops.
 */
public final class TraceRecorder {

    private static final ThreadLocal<TraceRecorder> CURRENT = new ThreadLocal<>();

    private final List<TraceEvent> events = new ArrayList<>();
    private final Deque<Long> callStack = new ArrayDeque<>(); // stack of CALL sequence IDs
    private long nextSequence = 0;

    private TraceRecorder() {}

    /** Start recording on the current thread. Returns the new recorder. */
    public static TraceRecorder begin() {
        TraceRecorder recorder = new TraceRecorder();
        CURRENT.set(recorder);
        return recorder;
    }

    /** Get the active recorder for the current thread, or null if not tracing. */
    public static TraceRecorder current() {
        return CURRENT.get();
    }

    /** Stop recording and detach from the current thread. */
    public static void end() {
        CURRENT.remove();
    }

    /** Record a function call (entry). Returns the sequence ID for pairing with RETURN. */
    public long recordCall(String location, boolean pure, Object[] inputs) {
        long seq = nextSequence++;
        long parent = callStack.isEmpty() ? -1 : callStack.peek();
        events.add(new TraceEvent(seq, TraceEvent.Kind.CALL, location, pure,
                snapshot(inputs), null, null, parent, 0));
        callStack.push(seq);
        return seq;
    }

    /** Record a function return (normal exit). */
    public void recordReturn(long callSequence, String location, boolean pure,
                              Object[] inputs, Object output, long durationNanos) {
        if (!callStack.isEmpty()) callStack.pop();
        long parent = callStack.isEmpty() ? -1 : callStack.peek();
        events.add(new TraceEvent(nextSequence++, TraceEvent.Kind.RETURN, location, pure,
                snapshot(inputs), output, null, callSequence, durationNanos));
    }

    /** Record a local variable assignment. */
    public void recordAssign(String variableName, Object value) {
        long parent = callStack.isEmpty() ? -1 : callStack.peek();
        events.add(new TraceEvent(nextSequence++, TraceEvent.Kind.ASSIGN, variableName, false,
                null, value, null, parent, 0));
    }

    /** Record a function error (exceptional exit). */
    public void recordError(long callSequence, String location, boolean pure,
                             Object[] inputs, String error, long durationNanos) {
        if (!callStack.isEmpty()) callStack.pop();
        events.add(new TraceEvent(nextSequence++, TraceEvent.Kind.ERROR, location, pure,
                snapshot(inputs), null, error, callSequence, durationNanos));
    }

    /** Get all recorded events. */
    public List<TraceEvent> getEvents() {
        return events;
    }

    /** Number of recorded events. */
    public int size() {
        return events.size();
    }

    /** Snapshot argument values to prevent mutation after recording. */
    private static Object[] snapshot(Object[] args) {
        if (args == null) return null;
        Object[] copy = new Object[args.length];
        for (int i = 0; i < args.length; i++) {
            // For immutable SPN values (Long, Double, String, SpnStructValue, etc.),
            // a reference copy is sufficient. Mutable collections would need deep copy,
            // but SPN values are generally immutable.
            copy[i] = args[i];
        }
        return copy;
    }
}
