package spn.gui;

import spn.fonts.SdfFontRenderer;
import spn.gui.lang.ContextDetector;
import spn.gui.lang.EditorContext;
import spn.gui.lang.Suggestion;
import spn.gui.lang.SuggestionProvider;
import spn.lang.SpnParser;
import spn.node.SpnRootNode;
import spn.type.SpnSymbolTable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.system.MemoryUtil.*;

/**
 * A single editor window: owns its GLFW window handle and all UI components
 * (TextArea, Scrollbars, HUD). Shares the GL context and font renderer with
 * other windows via GLFW context sharing.
 */
public class EditorWindow {

    private static final float SCROLLBAR_SIZE = 12f;

    private final long handle;
    private SdfFontRenderer font;
    private TextArea textArea;
    private Scrollbar vScroll;
    private Scrollbar hScroll;
    private Hud hud;

    private Path currentFile;
    private boolean initialized;

    private ContextDetector contextDetector;
    private SuggestionProvider suggestionProvider;
    private List<Suggestion> currentSuggestions = List.of();

    /**
     * Creates the GLFW window. Call {@link #initComponents(SdfFontRenderer)} after
     * the GL context and font renderer are ready.
     *
     * @param shareWith GLFW window handle to share GL context with, or NULL for the first window
     */
    EditorWindow(long shareWith) {
        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_RESIZABLE, GLFW_TRUE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_COMPAT_PROFILE);

        handle = glfwCreateWindow(1280, 720, "untitled - Symbols+Numbers", NULL, shareWith);
        if (handle == NULL) throw new RuntimeException("Failed to create GLFW window");
    }

    /** Initialise UI components once the shared font renderer is available. */
    void initComponents(SdfFontRenderer font) {
        if (initialized) return;
        this.font = font;
        initialized = true;

        setupCallbacks();

        ScrollbarTheme sbTheme = ScrollbarTheme.dark();

        textArea = new TextArea(font);
        textArea.setClipboard(new TextArea.ClipboardHandler() {
            @Override public void set(String text) { glfwSetClipboardString(handle, text); }
            @Override public String get() { return glfwGetClipboardString(handle); }
        });

        vScroll = new Scrollbar(font, Scrollbar.Orientation.VERTICAL);
        vScroll.setTheme(sbTheme);
        vScroll.setOnChange(v -> textArea.setScrollRow(v));

        hScroll = new Scrollbar(font, Scrollbar.Orientation.HORIZONTAL);
        hScroll.setTheme(sbTheme);
        hScroll.setOnChange(v -> textArea.setScrollCol(v));

        hud = new Hud(font);
        hud.setText(BASE_SHORTCUTS);

        contextDetector = new ContextDetector();
        suggestionProvider = new SuggestionProvider();
    }

    long getHandle() { return handle; }

    void show() {
        glfwShowWindow(handle);
    }

    boolean shouldClose() {
        return glfwWindowShouldClose(handle);
    }

    boolean isFocused() {
        return glfwGetWindowAttrib(handle, GLFW_FOCUSED) != 0;
    }

    void makeCurrent() {
        glfwMakeContextCurrent(handle);
    }

    void render() {
        int[] w = new int[1], h = new int[1];
        glfwGetFramebufferSize(handle, w, h);
        glViewport(0, 0, w[0], h[0]);
        glClear(GL_COLOR_BUFFER_BIT);

        float sw = w[0], sh = h[0];
        float hudH = hud.preferredHeight();
        float bottomBar = hudH + SCROLLBAR_SIZE;

        textArea.setBounds(0, 0, sw - SCROLLBAR_SIZE, sh - bottomBar);
        vScroll.setBounds(sw - SCROLLBAR_SIZE, 0, SCROLLBAR_SIZE, sh - bottomBar);
        hud.setBounds(0, sh - bottomBar, sw, hudH);
        hScroll.setBounds(0, sh - SCROLLBAR_SIZE, sw - SCROLLBAR_SIZE, SCROLLBAR_SIZE);

        font.beginText(w[0], h[0]);
        textArea.render();

        vScroll.setContent(textArea.getContentRows(), textArea.getVisibleRows());
        vScroll.setValue(textArea.getScrollRow());
        hScroll.setContent(textArea.getContentCols(), textArea.getVisibleCols());
        hScroll.setValue(textArea.getScrollCol());

        vScroll.render();
        hScroll.render();
        updateHud();
        hud.render();
        font.endText();

        glfwSwapBuffers(handle);
    }

    void destroy() {
        glfwDestroyWindow(handle);
    }

    // ---- HUD ------------------------------------------------------------

    private static final String BASE_SHORTCUTS =
            "F1 Shapes | F2 Plot | F5 Run | Ctrl+N New | Ctrl+O Open | Ctrl+S Save";

    // ---- Sample scripts -------------------------------------------------

    private record Sample(int key, String label, String resource) {}
    private static final Sample[] SAMPLES = {
            new Sample(GLFW_KEY_F1, "Shapes",   "/samples/canvas_shapes.spn"),
            new Sample(GLFW_KEY_F2, "Plot",     "/samples/canvas_grid.spn"),
    };

    private void openSample(Sample sample) {
        try (InputStream in = getClass().getResourceAsStream(sample.resource())) {
            if (in == null) {
                hud.flash("Sample not found: " + sample.resource(), true);
                return;
            }
            String content = new String(in.readAllBytes(), StandardCharsets.UTF_8);

            if (currentFile == null && textArea.getText().isBlank()) {
                // Load into current window
                textArea.setText(content);
                currentFile = null;
                glfwSetWindowTitle(handle, sample.label() + " - Symbols+Numbers");
            } else {
                // Open in a new window
                EditorWindow w = Main.instance.spawnWindow();
                w.textArea.setText(content);
                glfwSetWindowTitle(w.handle, sample.label() + " - Symbols+Numbers");
            }
        } catch (IOException e) {
            hud.flash("Error loading sample: " + e.getMessage(), true);
        }
    }

    private void updateHud() {
        // Detect semantic context and update available suggestions
        EditorContext ctx = contextDetector.detect(
                textArea.getBuffer(), textArea.getHighlightCache(), textArea.getCursorRow());
        currentSuggestions = suggestionProvider.getSuggestions(ctx);

        StringBuilder sb = new StringBuilder();

        // Show numbered contextual suggestions on blank lines
        if (textArea.isCurrentLineBlank() && !textArea.hasSelection()
                && !currentSuggestions.isEmpty()) {
            for (int i = 0; i < currentSuggestions.size(); i++) {
                if (i > 0) sb.append(" | ");
                sb.append(i + 1).append(' ').append(currentSuggestions.get(i).label());
            }
            sb.append(" | ").append(BASE_SHORTCUTS);
            hud.setText(sb.toString());
            return;
        }

        // Undo state summary
        UndoManager.Info info = textArea.getUndoInfo();
        if (info.depth() > 0 || info.branches() > 0) {
            sb.append("History depth ").append(info.depth());
            if (info.branches() > 1) {
                sb.append(" | Branch ").append(info.activeBranch())
                  .append(" of ").append(info.branches())
                  .append(" | Ctrl+[ Prev | Ctrl+] Next");
            } else if (info.branches() == 1) {
                sb.append(" | Ctrl+Z Undo | Ctrl+Y Redo");
            }
            if (info.canUndo() && info.branches() <= 1) {
                // already shown
            } else if (!info.canUndo()) {
                sb.append(" | (at root)");
            }
            sb.append(" | ").append(BASE_SHORTCUTS);
        } else {
            sb.append(BASE_SHORTCUTS);
        }

        hud.setText(sb.toString());
    }

    // ---- Contextual shortcuts -------------------------------------------

    /**
     * Attempts to handle a contextual shortcut (Ctrl+1..9 on a blank line).
     * Returns true if the key was consumed.
     */
    private boolean tryContextShortcut(int key) {
        int index = key - GLFW_KEY_1;
        if (index < 0 || index > 8) return false;
        if (index >= currentSuggestions.size()) return false;
        textArea.insertSnippet(currentSuggestions.get(index).snippet());
        return true;
    }

    // ---- Build & Run ----------------------------------------------------

    private void runCurrentFile() {
        String source = textArea.getText();
        if (source.isBlank()) {
            hud.flash("Nothing to run", true);
            return;
        }

        spn.canvas.CanvasState canvasState = new spn.canvas.CanvasState();
        spn.canvas.CanvasState.set(canvasState);
        try {
            SpnSymbolTable symbolTable = new SpnSymbolTable();
            spn.language.SpnModuleRegistry moduleRegistry = new spn.language.SpnModuleRegistry();
            registerStdlibModules(moduleRegistry);
            spn.canvas.CanvasBuiltins.registerModule(moduleRegistry);
            moduleRegistry.addLoader(new spn.lang.ClasspathModuleLoader(null, symbolTable));
            SpnParser parser = new SpnParser(source, null, symbolTable, moduleRegistry);
            SpnRootNode root = parser.parse();
            Object result = root.getCallTarget().call();

            if (canvasState.isCanvasRequested()) {
                spn.canvas.CanvasWindow cw = new spn.canvas.CanvasWindow();
                cw.open(canvasState.getWidth(), canvasState.getHeight(), handle, font);
                try {
                    if (canvasState.getAnimateCallback() != null) {
                        cw.showAnimated(canvasState.getAnimateFps(),
                                        canvasState.getAnimateCallback());
                    } else {
                        cw.showStatic(canvasState.getCommands());
                    }
                } finally {
                    cw.close();
                    // Restore editor GL context
                    makeCurrent();
                }
            } else {
                String display = result == null ? "(no result)" : result.toString();
                hud.flash("=> " + display, false);
            }
        } catch (Exception e) {
            String msg = e.getMessage();
            if (msg == null) msg = e.getClass().getSimpleName();
            hud.flash("Error: " + msg, true);
        } finally {
            spn.canvas.CanvasState.clear();
        }
    }

    // ---- File operations ------------------------------------------------

    void loadFile(Path path) throws IOException {
        String content = Files.readString(path);
        textArea.setText(content);
        currentFile = path;
        updateTitle();
    }

    private static void registerStdlibModules(spn.language.SpnModuleRegistry registry) {
        // Group stdlib builtins by module name and register each as an SpnModule
        var byModule = new java.util.LinkedHashMap<String, spn.language.SpnModule.Builder>();
        for (var entry : spn.stdlib.gen.SpnStdlibRegistry.allBuiltins()) {
            byModule.computeIfAbsent(entry.module(), spn.language.SpnModule::builder)
                    .function(entry.name(), entry.callTarget());
        }
        byModule.forEach((name, builder) -> registry.register(name, builder.build()));
    }

    void openFile() {
        String defaultPath = currentFile != null ? currentFile.toString() : "";
        String path = org.lwjgl.util.tinyfd.TinyFileDialogs.tinyfd_openFileDialog(
                "Open File", defaultPath, Main.SPN_FILTER, "SPN / Text files", false);
        if (path == null) return;
        try {
            loadFile(Path.of(path));
        } catch (IOException e) {
            org.lwjgl.util.tinyfd.TinyFileDialogs.tinyfd_messageBox(
                    "Error", "Could not read file:\n" + e.getMessage(), "ok", "error", true);
        }
    }

    void saveFile(boolean saveAs) {
        Path target = currentFile;
        if (target == null || saveAs) {
            String defaultPath = target != null ? target.toString() : "";
            String path = org.lwjgl.util.tinyfd.TinyFileDialogs.tinyfd_saveFileDialog(
                    "Save File", defaultPath, Main.SPN_FILTER, "SPN / Text files");
            if (path == null) return;
            target = Path.of(path);
        }
        try {
            Files.writeString(target, textArea.getText());
            currentFile = target;
            updateTitle();
        } catch (IOException e) {
            org.lwjgl.util.tinyfd.TinyFileDialogs.tinyfd_messageBox(
                    "Error", "Could not save file:\n" + e.getMessage(), "ok", "error", true);
        }
    }

    private void updateTitle() {
        String name = currentFile != null ? currentFile.getFileName().toString() : "untitled";
        glfwSetWindowTitle(handle, name + " - Symbols+Numbers");
    }

    // ---- Callbacks ------------------------------------------------------

    private void setupCallbacks() {
        glfwSetCharCallback(handle, (win, codepoint) -> textArea.onCharInput(codepoint));

        glfwSetKeyCallback(handle, (win, key, scancode, action, mods) -> {
            if (action == GLFW_PRESS || action == GLFW_REPEAT) {
                boolean ctrl = (mods & GLFW_MOD_CONTROL) != 0;
                // Sample shortcuts (F1, F2, ...)
                if (!ctrl && action == GLFW_PRESS) {
                    boolean handled = false;
                    for (Sample s : SAMPLES) {
                        if (key == s.key()) { openSample(s); handled = true; break; }
                    }
                    if (handled) return;
                }
                if (ctrl && key == GLFW_KEY_N && action == GLFW_PRESS) {
                    Main.instance.spawnWindow();
                } else if (ctrl && key == GLFW_KEY_O && action == GLFW_PRESS) {
                    openFile();
                } else if (ctrl && key == GLFW_KEY_S && action == GLFW_PRESS) {
                    saveFile((mods & GLFW_MOD_SHIFT) != 0);
                } else if ((key == GLFW_KEY_F5 || (ctrl && key == GLFW_KEY_R))
                        && action == GLFW_PRESS) {
                    runCurrentFile();
                } else if (ctrl && action == GLFW_PRESS
                        && textArea.isCurrentLineBlank()
                        && !textArea.hasSelection()
                        && tryContextShortcut(key)) {
                    // handled by tryContextShortcut
                } else {
                    textArea.onKey(key, mods);
                }
            }
        });

        glfwSetScrollCallback(handle, (win, xoff, yoff) -> textArea.onScroll(xoff, yoff));

        glfwSetMouseButtonCallback(handle, (win, button, action, mods) -> {
            double[] mx = new double[1], my = new double[1];
            glfwGetCursorPos(win, mx, my);
            vScroll.onMouseButton(button, action, mods, mx[0], my[0]);
            hScroll.onMouseButton(button, action, mods, mx[0], my[0]);
            if (!vScroll.isDragging() && !hScroll.isDragging()) {
                textArea.onMouseButton(button, action, mods, mx[0], my[0]);
            }
        });

        glfwSetCursorPosCallback(handle, (win, xpos, ypos) -> {
            vScroll.onCursorPos(xpos, ypos);
            hScroll.onCursorPos(xpos, ypos);
            if (!vScroll.isDragging() && !hScroll.isDragging()) {
                textArea.onCursorPos(xpos, ypos);
            }
        });

        glfwSetCursorEnterCallback(handle, (win, entered) -> textArea.onCursorEnter(entered));

        glfwSetWindowCloseCallback(handle, win -> {
            glfwSetWindowShouldClose(win, false);
            boolean save = org.lwjgl.util.tinyfd.TinyFileDialogs.tinyfd_messageBox(
                    "Quit", "Save before quitting?", "yesno", "question", true);
            if (save) saveFile(false);
            glfwSetWindowShouldClose(win, true);
        });

        glfwSetWindowFocusCallback(handle, (win, focused) -> Main.instance.onWindowFocusChanged());

        glfwSetWindowRefreshCallback(handle, win -> {
            makeCurrent();
            render();
        });
    }
}
