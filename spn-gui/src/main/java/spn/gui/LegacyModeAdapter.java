package spn.gui;

import spn.stdui.input.ControlSignal;
import spn.stdui.input.InputEvent;
import spn.stdui.input.Key;
import spn.stdui.input.Mod;
import spn.stdui.render.Renderer;
import spn.stdui.widget.HudSegment;

import java.util.List;
import java.util.Set;

import static org.lwjgl.glfw.GLFW.*;

/**
 * Adapts the legacy {@link Mode} interface to the new {@link spn.stdui.mode.Mode}.
 * Bridges GLFW-specific callbacks to platform-neutral InputEvents during migration.
 */
public class LegacyModeAdapter implements spn.stdui.mode.Mode {

    private final Mode legacy;
    private final SizeProvider sizeProvider;
    private final Set<ControlSignal> suppressed;

    @FunctionalInterface
    interface SizeProvider {
        float[] getSize(); // [width, height]
    }

    LegacyModeAdapter(Mode legacy, SizeProvider sizeProvider) {
        this(legacy, sizeProvider, Set.of());
    }

    LegacyModeAdapter(Mode legacy, SizeProvider sizeProvider, Set<ControlSignal> suppressed) {
        this.legacy = legacy;
        this.sizeProvider = sizeProvider;
        this.suppressed = suppressed;
    }

    Mode getLegacy() { return legacy; }

    @Override
    public boolean onInput(InputEvent event) {
        return switch (event) {
            case InputEvent.KeyPress kp ->
                    legacy.onKey(toGlfwKey(kp.key()), 0, GLFW_PRESS, toGlfwMods(kp.mods()));
            case InputEvent.KeyRepeat kr ->
                    legacy.onKey(toGlfwKey(kr.key()), 0, GLFW_REPEAT, toGlfwMods(kr.mods()));
            case InputEvent.KeyRelease kr ->
                    legacy.onKey(toGlfwKey(kr.key()), 0, GLFW_RELEASE, toGlfwMods(kr.mods()));
            case InputEvent.CharInput ci ->
                    legacy.onChar(ci.codepoint());
            case InputEvent.MousePress mp ->
                    legacy.onMouseButton(mp.button(), GLFW_PRESS, toGlfwMods(mp.mods()), mp.x(), mp.y());
            case InputEvent.MouseRelease mr ->
                    legacy.onMouseButton(mr.button(), GLFW_RELEASE, 0, mr.x(), mr.y());
            case InputEvent.MouseMove mm ->
                    legacy.onCursorPos(mm.x(), mm.y());
            case InputEvent.MouseScroll ms ->
                    legacy.onScroll(ms.xOff(), ms.yOff());
            case InputEvent.MouseEnter me ->
                    { legacy.onCursorEnter(me.entered()); yield true; }
        };
    }

    @Override
    public void render(Renderer renderer, float width, float height, double now) {
        legacy.render(width, height);
    }

    @Override
    public List<HudSegment> hudSegments() {
        // Parse the old "Key1 Label1 | Key2 Label2" format
        String text = legacy.hudText();
        if (text == null || text.isEmpty()) return List.of();
        String[] segments = text.split(" \\| ");
        return java.util.Arrays.stream(segments).map(seg -> {
            seg = seg.trim();
            int space = seg.indexOf(' ');
            if (space > 0) {
                return new HudSegment(seg.substring(0, space), seg.substring(space + 1));
            }
            return HudSegment.label(seg);
        }).toList();
    }

    @Override
    public String name() {
        return legacy.getClass().getSimpleName();
    }

    @Override
    public Set<ControlSignal> suppressedSignals() {
        return suppressed;
    }

    // ---- GLFW translation (reverse of Key.fromGlfw) ----

    private static int toGlfwKey(Key key) {
        return switch (key) {
            case A -> GLFW_KEY_A; case B -> GLFW_KEY_B; case C -> GLFW_KEY_C;
            case D -> GLFW_KEY_D; case E -> GLFW_KEY_E; case F -> GLFW_KEY_F;
            case G -> GLFW_KEY_G; case H -> GLFW_KEY_H; case I -> GLFW_KEY_I;
            case J -> GLFW_KEY_J; case K -> GLFW_KEY_K; case L -> GLFW_KEY_L;
            case M -> GLFW_KEY_M; case N -> GLFW_KEY_N; case O -> GLFW_KEY_O;
            case P -> GLFW_KEY_P; case Q -> GLFW_KEY_Q; case R -> GLFW_KEY_R;
            case S -> GLFW_KEY_S; case T -> GLFW_KEY_T; case U -> GLFW_KEY_U;
            case V -> GLFW_KEY_V; case W -> GLFW_KEY_W; case X -> GLFW_KEY_X;
            case Y -> GLFW_KEY_Y; case Z -> GLFW_KEY_Z;
            case NUM_0 -> GLFW_KEY_0; case NUM_1 -> GLFW_KEY_1; case NUM_2 -> GLFW_KEY_2;
            case NUM_3 -> GLFW_KEY_3; case NUM_4 -> GLFW_KEY_4; case NUM_5 -> GLFW_KEY_5;
            case NUM_6 -> GLFW_KEY_6; case NUM_7 -> GLFW_KEY_7; case NUM_8 -> GLFW_KEY_8;
            case NUM_9 -> GLFW_KEY_9;
            case F1 -> GLFW_KEY_F1; case F2 -> GLFW_KEY_F2; case F3 -> GLFW_KEY_F3;
            case F4 -> GLFW_KEY_F4; case F5 -> GLFW_KEY_F5; case F6 -> GLFW_KEY_F6;
            case F7 -> GLFW_KEY_F7; case F8 -> GLFW_KEY_F8; case F9 -> GLFW_KEY_F9;
            case F10 -> GLFW_KEY_F10; case F11 -> GLFW_KEY_F11; case F12 -> GLFW_KEY_F12;
            case ENTER -> GLFW_KEY_ENTER; case ESCAPE -> GLFW_KEY_ESCAPE;
            case BACKSPACE -> GLFW_KEY_BACKSPACE; case TAB -> GLFW_KEY_TAB;
            case SPACE -> GLFW_KEY_SPACE; case DELETE -> GLFW_KEY_DELETE;
            case LEFT -> GLFW_KEY_LEFT; case RIGHT -> GLFW_KEY_RIGHT;
            case UP -> GLFW_KEY_UP; case DOWN -> GLFW_KEY_DOWN;
            case HOME -> GLFW_KEY_HOME; case END -> GLFW_KEY_END;
            case PAGE_UP -> GLFW_KEY_PAGE_UP; case PAGE_DOWN -> GLFW_KEY_PAGE_DOWN;
            case LEFT_BRACKET -> GLFW_KEY_LEFT_BRACKET;
            case RIGHT_BRACKET -> GLFW_KEY_RIGHT_BRACKET;
            case MINUS -> GLFW_KEY_MINUS; case EQUAL -> GLFW_KEY_EQUAL;
            case COMMA -> GLFW_KEY_COMMA; case PERIOD -> GLFW_KEY_PERIOD;
            case SLASH -> GLFW_KEY_SLASH; case SEMICOLON -> GLFW_KEY_SEMICOLON;
            case APOSTROPHE -> GLFW_KEY_APOSTROPHE;
            case GRAVE_ACCENT -> GLFW_KEY_GRAVE_ACCENT;
            case BACKSLASH -> GLFW_KEY_BACKSLASH;
            case UNKNOWN -> GLFW_KEY_UNKNOWN;
        };
    }

    private static int toGlfwMods(int mods) {
        // spn.stdui.input.Mod uses same bit layout as GLFW
        return mods;
    }
}
