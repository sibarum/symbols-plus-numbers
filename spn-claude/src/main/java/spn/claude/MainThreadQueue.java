package spn.claude;

import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Single-producer / single-consumer dispatch queue. The Claude worker
 * thread {@link #post(Runnable) posts} a runnable; the IDE main thread
 * {@link #drain() drains} it at the top of each render frame.
 *
 * <p>Same pattern the diagnostic engine uses for background re-parses,
 * but exposed as an explicit queue so the worker doesn't need to know
 * which thread is the main thread — only that it can post to this queue.
 */
public final class MainThreadQueue {

    private final ConcurrentLinkedQueue<Runnable> tasks = new ConcurrentLinkedQueue<>();

    public void post(Runnable r) {
        tasks.offer(r);
    }

    /** Run every queued task, in submission order. Called on the main thread. */
    public void drain() {
        Runnable r;
        while ((r = tasks.poll()) != null) {
            try {
                r.run();
            } catch (RuntimeException e) {
                // Don't let a single bad callback wedge the queue or crash the
                // IDE — log to stderr and keep going.
                System.err.println("[MainThreadQueue] task failed: " + e);
                e.printStackTrace(System.err);
            }
        }
    }
}
