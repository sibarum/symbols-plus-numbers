package spn.canvasgui.input;

import spn.stdui.input.Key;

/**
 * Routed GUI event, distinct from the platform-level {@code InputEvent}:
 * carries component-local coordinates and a phase for pointer interactions.
 */
public sealed interface GuiEvent {

    enum PointerPhase { ENTER, EXIT, PRESS, RELEASE, MOVE, CLICK }

    /** Pointer event with component-local coordinates. */
    record Pointer(PointerPhase phase, float localX, float localY,
                   int button, int mods) implements GuiEvent {}

    /** Scroll event with component-local coordinates. */
    record Scroll(float localX, float localY, double xOff, double yOff) implements GuiEvent {}

    /** Key pressed or repeated. */
    record KeyDown(Key key, int mods, boolean repeat) implements GuiEvent {}

    /** Key released. */
    record KeyUp(Key key, int mods) implements GuiEvent {}

    /** Character input (post-IME codepoint). */
    record Char(int codepoint) implements GuiEvent {}

    /** Focus gained or lost. */
    record Focus(boolean gained) implements GuiEvent {}
}
