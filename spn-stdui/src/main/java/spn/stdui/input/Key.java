package spn.stdui.input;

/**
 * Platform-neutral key identifiers.
 * The window driver translates platform key codes (e.g., GLFW) into these.
 */
public enum Key {
    A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U, V, W, X, Y, Z,
    NUM_0, NUM_1, NUM_2, NUM_3, NUM_4, NUM_5, NUM_6, NUM_7, NUM_8, NUM_9,
    F1, F2, F3, F4, F5, F6, F7, F8, F9, F10, F11, F12,
    ENTER, ESCAPE, BACKSPACE, TAB, SPACE, DELETE,
    LEFT, RIGHT, UP, DOWN, HOME, END, PAGE_UP, PAGE_DOWN,
    LEFT_BRACKET, RIGHT_BRACKET, MINUS, EQUAL, COMMA, PERIOD, SLASH, SEMICOLON,
    APOSTROPHE, GRAVE_ACCENT, BACKSLASH,
    UNKNOWN;

    private static final Key[] GLFW_MAP = new Key[512];

    static {
        // Letters (GLFW_KEY_A = 65 .. GLFW_KEY_Z = 90)
        for (int i = 0; i < 26; i++) GLFW_MAP[65 + i] = values()[i];
        // Digits (GLFW_KEY_0 = 48 .. GLFW_KEY_9 = 57)
        for (int i = 0; i < 10; i++) GLFW_MAP[48 + i] = values()[26 + i];
        // Function keys (GLFW_KEY_F1 = 290 .. GLFW_KEY_F12 = 301)
        for (int i = 0; i < 12; i++) GLFW_MAP[290 + i] = values()[36 + i];
        // Special keys
        GLFW_MAP[257] = ENTER;       // GLFW_KEY_ENTER
        GLFW_MAP[256] = ESCAPE;      // GLFW_KEY_ESCAPE
        GLFW_MAP[259] = BACKSPACE;   // GLFW_KEY_BACKSPACE
        GLFW_MAP[258] = TAB;         // GLFW_KEY_TAB
        GLFW_MAP[32]  = SPACE;       // GLFW_KEY_SPACE
        GLFW_MAP[261] = DELETE;      // GLFW_KEY_DELETE
        GLFW_MAP[263] = LEFT;        // GLFW_KEY_LEFT
        GLFW_MAP[262] = RIGHT;       // GLFW_KEY_RIGHT
        GLFW_MAP[265] = UP;          // GLFW_KEY_UP
        GLFW_MAP[264] = DOWN;        // GLFW_KEY_DOWN
        GLFW_MAP[268] = HOME;        // GLFW_KEY_HOME
        GLFW_MAP[269] = END;         // GLFW_KEY_END
        GLFW_MAP[266] = PAGE_UP;     // GLFW_KEY_PAGE_UP
        GLFW_MAP[267] = PAGE_DOWN;   // GLFW_KEY_PAGE_DOWN
        GLFW_MAP[91]  = LEFT_BRACKET;  // GLFW_KEY_LEFT_BRACKET
        GLFW_MAP[93]  = RIGHT_BRACKET; // GLFW_KEY_RIGHT_BRACKET
        GLFW_MAP[45]  = MINUS;       // GLFW_KEY_MINUS
        GLFW_MAP[61]  = EQUAL;       // GLFW_KEY_EQUAL
        GLFW_MAP[44]  = COMMA;       // GLFW_KEY_COMMA
        GLFW_MAP[46]  = PERIOD;      // GLFW_KEY_PERIOD
        GLFW_MAP[47]  = SLASH;       // GLFW_KEY_SLASH
        GLFW_MAP[59]  = SEMICOLON;   // GLFW_KEY_SEMICOLON
        GLFW_MAP[39]  = APOSTROPHE;  // GLFW_KEY_APOSTROPHE
        GLFW_MAP[96]  = GRAVE_ACCENT; // GLFW_KEY_GRAVE_ACCENT
        GLFW_MAP[92]  = BACKSLASH;   // GLFW_KEY_BACKSLASH
    }

    /** Translate from a GLFW key code. Returns {@link #UNKNOWN} for unmapped keys. */
    public static Key fromGlfw(int glfwKey) {
        if (glfwKey >= 0 && glfwKey < GLFW_MAP.length && GLFW_MAP[glfwKey] != null) {
            return GLFW_MAP[glfwKey];
        }
        return UNKNOWN;
    }
}
