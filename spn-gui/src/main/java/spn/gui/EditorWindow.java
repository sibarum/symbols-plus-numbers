package spn.gui;

import spn.fonts.SdfFontRenderer;
import spn.lang.SpnParser;
import spn.node.SpnRootNode;
import spn.type.SpnSymbolTable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.system.MemoryUtil.*;

/**
 * A single editor window: owns its GLFW window handle and all UI components.
 * Delegates input and rendering to the active {@link Mode} on the mode stack.
 */
public class EditorWindow {

    private final long handle;
    private SdfFontRenderer font;
    private TextArea textArea;
    private Hud hud;

    private final Deque<Mode> modeStack = new ArrayDeque<>();
    private final ActionRegistry actionRegistry = new ActionRegistry();

    private Path currentFile;
    private boolean initialized;

    // ---- Sample scripts -------------------------------------------------

    record Sample(int key, String label, String resource) {}
    static final Sample[] SAMPLES = {
            new Sample(GLFW_KEY_F1, "Shapes",   "/samples/canvas_shapes.spn"),
            new Sample(GLFW_KEY_F2, "Plot",     "/samples/canvas_grid.spn"),
    };

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

        ScrollbarTheme sbTheme = ScrollbarTheme.dark();

        textArea = new TextArea(font);
        textArea.setClipboard(new TextArea.ClipboardHandler() {
            @Override public void set(String text) { glfwSetClipboardString(handle, text); }
            @Override public String get() { return glfwGetClipboardString(handle); }
        });

        Scrollbar vScroll = new Scrollbar(font, Scrollbar.Orientation.VERTICAL);
        vScroll.setTheme(sbTheme);
        vScroll.setOnChange(v -> textArea.setScrollRow(v));

        Scrollbar hScroll = new Scrollbar(font, Scrollbar.Orientation.HORIZONTAL);
        hScroll.setTheme(sbTheme);
        hScroll.setOnChange(v -> textArea.setScrollCol(v));

        hud = new Hud(font);
        hud.setText(EditorMode.BASE_SHORTCUTS);

        // EditorMode is always at the bottom of the stack
        EditorMode editorMode = new EditorMode(this, textArea, vScroll, hScroll);
        modeStack.push(editorMode);

        registerActions();
        setupCallbacks();
    }

    // ---- Mode stack -----------------------------------------------------

    /** Push a new mode onto the stack — it becomes the active mode. */
    void pushMode(Mode mode) {
        modeStack.push(mode);
    }

    /** Pop the current mode, returning to the one below. Never pops EditorMode. */
    void popMode() {
        if (modeStack.size() > 1) {
            modeStack.pop();
        }
    }

    /** The active mode (top of stack). */
    private Mode activeMode() {
        return modeStack.peek();
    }

    // ---- Action registration --------------------------------------------

    private void registerActions() {
        actionRegistry.register("New Window",    "File", "Ctrl+N",       () -> Main.instance.spawnWindow());
        actionRegistry.register("Open File",     "File", "Ctrl+O",       this::openFile);
        actionRegistry.register("Save File",     "File", "Ctrl+S",       () -> saveFile(false));
        actionRegistry.register("Save As",       "File", "Ctrl+Shift+S", () -> saveFile(true));
        actionRegistry.register("Run",           "Run",  "F5",           this::runCurrentFile);
        actionRegistry.register("Undo",          "Edit", "Ctrl+Z",       () -> textArea.performUndo());
        actionRegistry.register("Redo",          "Edit", "Ctrl+Y",       () -> textArea.performRedo());
        actionRegistry.register("Zoom In",       "View", "Ctrl+=",       () -> textArea.zoomIn());
        actionRegistry.register("Zoom Out",      "View", "Ctrl+-",       () -> textArea.zoomOut());
        actionRegistry.register("Zoom Reset",    "View", "Ctrl+0",       () -> textArea.zoomReset());
        actionRegistry.register("Shapes Sample", "Sample", "F1",         () -> openSample(SAMPLES[0]));
        actionRegistry.register("Plot Sample",   "Sample", "F2",         () -> openSample(SAMPLES[1]));
        actionRegistry.register("Action Menu",   "View",   "Ctrl+P",     () -> pushMode(new ActionMenuMode(this, actionRegistry)));

        // Template actions (insert keyword then activate template)
        for (String kw : spn.gui.template.TemplateCatalog.keywords()) {
            actionRegistry.register("Template: " + kw, "Template", "Ctrl+,",
                    () -> {
                        textArea.insertSnippet(kw);
                        // Simulate Ctrl+Comma to activate the template
                        ((EditorMode) modeStack.peekLast()).activateTemplateForKeyword(kw);
                    });
        }
    }

    // ---- Accessors for EditorMode / other modes -------------------------

    long getHandle() { return handle; }

    Hud getHud() { return hud; }

    ActionRegistry getActionRegistry() { return actionRegistry; }

    TextArea getTextArea() { return textArea; }

    SdfFontRenderer getFont() { return font; }

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

    // ---- Rendering ------------------------------------------------------

    void render() {
        int[] w = new int[1], h = new int[1];
        glfwGetFramebufferSize(handle, w, h);
        glViewport(0, 0, w[0], h[0]);
        glClear(GL_COLOR_BUFFER_BIT);

        float sw = w[0], sh = h[0];
        float hudH = hud.preferredHeight();
        float scrollbarSize = 12f;

        hud.setBounds(0, sh - hudH - scrollbarSize, sw, hudH);

        font.beginText(w[0], h[0]);

        activeMode().render(sw, sh);

        hud.setText(activeMode().hudText());
        hud.render();

        font.endText();

        glfwSwapBuffers(handle);
    }

    void destroy() {
        glfwDestroyWindow(handle);
    }

    // ---- Sample loading -------------------------------------------------

    void openSample(Sample sample) {
        try (InputStream in = getClass().getResourceAsStream(sample.resource())) {
            if (in == null) {
                hud.flash("Sample not found: " + sample.resource(), true);
                return;
            }
            String content = new String(in.readAllBytes(), StandardCharsets.UTF_8);

            if (currentFile == null && textArea.getText().isBlank()) {
                textArea.setText(content);
                currentFile = null;
                glfwSetWindowTitle(handle, sample.label() + " - Symbols+Numbers");
            } else {
                EditorWindow w = Main.instance.spawnWindow();
                w.textArea.setText(content);
                glfwSetWindowTitle(w.handle, sample.label() + " - Symbols+Numbers");
            }
        } catch (IOException e) {
            hud.flash("Error loading sample: " + e.getMessage(), true);
        }
    }

    // ---- Build & Run ----------------------------------------------------

    void runCurrentFile() {
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

    // ---- Callbacks — delegate to active mode ----------------------------

    private void setupCallbacks() {
        glfwSetCharCallback(handle, (win, codepoint) ->
                activeMode().onChar(codepoint));

        glfwSetKeyCallback(handle, (win, key, scancode, action, mods) ->
                activeMode().onKey(key, scancode, action, mods));

        glfwSetScrollCallback(handle, (win, xoff, yoff) ->
                activeMode().onScroll(xoff, yoff));

        glfwSetMouseButtonCallback(handle, (win, button, action, mods) -> {
            double[] mx = new double[1], my = new double[1];
            glfwGetCursorPos(win, mx, my);
            activeMode().onMouseButton(button, action, mods, mx[0], my[0]);
        });

        glfwSetCursorPosCallback(handle, (win, xpos, ypos) ->
                activeMode().onCursorPos(xpos, ypos));

        glfwSetCursorEnterCallback(handle, (win, entered) ->
                activeMode().onCursorEnter(entered));

        glfwSetWindowCloseCallback(handle, win -> {
            glfwSetWindowShouldClose(win, false);
            boolean save = org.lwjgl.util.tinyfd.TinyFileDialogs.tinyfd_messageBox(
                    "Quit", "Save before quitting?", "yesno", "question", true);
            if (save) saveFile(false);
            glfwSetWindowShouldClose(win, true);
        });

        glfwSetWindowFocusCallback(handle, (win, focused) ->
                Main.instance.onWindowFocusChanged());

        glfwSetWindowRefreshCallback(handle, win -> {
            makeCurrent();
            render();
        });
    }
}
