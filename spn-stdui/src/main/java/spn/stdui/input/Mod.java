package spn.stdui.input;

/**
 * Modifier key flags, platform-neutral.
 * The window driver translates platform modifier masks into these.
 */
public final class Mod {
    public static final int NONE  = 0;
    public static final int SHIFT = 1;
    public static final int CTRL  = 2;
    public static final int ALT   = 4;
    public static final int SUPER = 8;

    public static boolean shift(int mods) { return (mods & SHIFT) != 0; }
    public static boolean ctrl(int mods)  { return (mods & CTRL)  != 0; }
    public static boolean alt(int mods)   { return (mods & ALT)   != 0; }

    /** Translate from GLFW modifier bitmask (GLFW_MOD_SHIFT=1, CTRL=2, ALT=4, SUPER=8). */
    public static int fromGlfw(int glfwMods) {
        // GLFW uses the same bit positions, so this is identity.
        return glfwMods & 0xF;
    }

    private Mod() {}
}
