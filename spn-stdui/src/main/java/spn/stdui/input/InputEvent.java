package spn.stdui.input;

/**
 * Platform-neutral input event. The window driver translates platform events
 * (e.g., GLFW callbacks) into these before dispatching to modes.
 */
public sealed interface InputEvent {
    record KeyPress(Key key, int mods)   implements InputEvent {}
    record KeyRepeat(Key key, int mods)  implements InputEvent {}
    record KeyRelease(Key key, int mods) implements InputEvent {}
    record CharInput(int codepoint)      implements InputEvent {}

    record MousePress(int button, int mods, double x, double y)   implements InputEvent {}
    record MouseRelease(int button, int mods, double x, double y) implements InputEvent {}
    record MouseMove(double x, double y)     implements InputEvent {}
    record MouseScroll(double xOff, double yOff) implements InputEvent {}
    record MouseEnter(boolean entered)       implements InputEvent {}
}
