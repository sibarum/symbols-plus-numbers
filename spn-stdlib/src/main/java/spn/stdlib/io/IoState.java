package spn.stdlib.io;

import java.util.function.Consumer;

/**
 * Thread-local I/O sink for SPN execution. The host (CLI, GUI, tests) installs
 * a fresh IoState before running SPN code; impure builtins like println read
 * from it. Default fallback when no state is set: System.out.
 *
 * Mirrors CanvasState's per-run isolation pattern.
 */
public final class IoState {

    private static final ThreadLocal<IoState> CURRENT = new ThreadLocal<>();

    public static IoState get() { return CURRENT.get(); }
    public static void set(IoState state) { CURRENT.set(state); }
    public static void clear() { CURRENT.remove(); }

    private Consumer<String> printlnSink = System.out::println;

    public Consumer<String> getPrintlnSink() { return printlnSink; }

    public void setPrintlnSink(Consumer<String> sink) {
        this.printlnSink = sink;
    }
}
