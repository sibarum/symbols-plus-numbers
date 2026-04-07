package spn.stdui.mode;

import spn.stdui.input.ControlBinding;
import spn.stdui.input.ControlSignal;
import spn.stdui.input.InputEvent;
import spn.stdui.render.Renderer;
import spn.stdui.widget.HudSegment;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * The terminal driver. Manages the mode stack, intercepts control bindings,
 * and dispatches input to the active (foreground) mode.
 *
 * <p>Control sequences (Ctrl+Space, Ctrl+Backspace, Ctrl+P) are caught here
 * before they reach modes. Modes never need to handle submit/cancel themselves.
 */
public class ModeManager {

    /**
     * External handler for control signals that need app-level behavior
     * (e.g., MODE_MENU opens the action palette).
     */
    @FunctionalInterface
    public interface SignalHandler {
        boolean onSignal(ControlSignal signal, Mode activeMode);
    }

    private final Deque<Mode> stack = new ArrayDeque<>();
    private final List<ControlBinding> bindings;
    private ModeContext context;
    private SignalHandler signalHandler;

    public ModeManager(ModeContext context) {
        this(context, ControlBinding.defaults());
    }

    public ModeManager(ModeContext context, List<ControlBinding> bindings) {
        this.context = context;
        this.bindings = new ArrayList<>(bindings);
    }

    /** Update the context (used by WindowFrame during construction). */
    public void setContext(ModeContext context) {
        this.context = context;
    }

    public void setSignalHandler(SignalHandler handler) {
        this.signalHandler = handler;
    }

    /** Push a mode onto the stack. Calls onDetach on current, onAttach on new. */
    public void push(Mode mode) {
        Mode current = stack.peek();
        if (current != null) current.onDetach();
        stack.push(mode);
        mode.onAttach(context);
    }

    /** Pop the active mode. Never pops the last mode. Returns the popped mode or null. */
    public Mode pop() {
        if (stack.size() <= 1) return null;
        Mode popped = stack.pop();
        popped.onDetach();
        Mode revealed = stack.peek();
        if (revealed != null) revealed.onAttach(context);
        return popped;
    }

    /** The active (foreground) mode. */
    public Mode active() { return stack.peek(); }

    /** Number of modes on the stack. */
    public int depth() { return stack.size(); }

    /** Read-only snapshot of the mode stack (top first). */
    public List<Mode> stack() { return List.copyOf(stack); }

    /**
     * Route an input event. Control bindings are checked first on key presses;
     * if none match (or the active mode suppresses the signal), the event
     * goes to the active mode.
     */
    public boolean dispatch(InputEvent event) {
        Mode active = stack.peek();
        if (active == null) return false;

        // Only intercept key presses for control bindings
        if (event instanceof InputEvent.KeyPress kp) {
            for (ControlBinding binding : bindings) {
                if (binding.matches(kp.key(), kp.mods())) {
                    // Check if the active mode suppresses this signal
                    if (active.suppressedSignals().contains(binding.signal())) {
                        break; // fall through to mode input
                    }
                    return handleSignal(binding.signal());
                }
            }
        }

        return active.onInput(event);
    }

    /** Render the active mode. */
    public void render(Renderer renderer, float width, float height, double now) {
        Mode active = stack.peek();
        if (active != null) {
            active.render(renderer, width, height, now);
        }
    }

    /** Get HUD segments from the active mode. */
    public List<HudSegment> hudSegments() {
        Mode active = stack.peek();
        return active != null ? active.hudSegments() : List.of();
    }

    private boolean handleSignal(ControlSignal signal) {
        Mode active = stack.peek();
        if (active == null) return false;

        // Let external handler try first
        if (signalHandler != null && signalHandler.onSignal(signal, active)) {
            return true;
        }

        // Default behavior
        return switch (signal) {
            case SUBMIT -> {
                if (active instanceof SubmittableMode sm) {
                    sm.onSubmit();
                    yield true;
                }
                yield false;
            }
            case CANCEL -> {
                if (stack.size() > 1) {
                    pop();
                    yield true;
                }
                yield false;
            }
            case MODE_MENU, SUSPEND -> false;
        };
    }
}
