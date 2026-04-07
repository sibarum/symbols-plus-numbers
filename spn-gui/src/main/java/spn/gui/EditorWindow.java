package spn.gui;

import spn.fonts.SdfFontRenderer;
import spn.lang.SpnParser;
import spn.node.SpnRootNode;
import spn.stdui.input.ControlSignal;
import spn.stdui.input.InputEvent;
import spn.stdui.input.Key;
import spn.stdui.input.Mod;
import spn.stdui.widget.ScrollbarTheme;
import spn.stdui.window.Clipboard;
import spn.stdui.window.WindowFrame;
import spn.type.SpnSymbolTable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.system.MemoryUtil.*;

/**
 * A single editor window: owns its GLFW window handle, bridges GLFW events
 * to spn-stdui's {@link WindowFrame}, and handles SPN-specific file/run ops.
 */
public class EditorWindow {

    private final long handle;
    private SdfFontRenderer font;
    private TextArea textArea;   // legacy TextArea (used by EditorMode/templates)
    private Hud legacyHud;       // legacy Hud (used by legacy modes for flash)

    // spn-stdui window frame — owns ModeManager and new Hud
    private WindowFrame frame;

    private final ActionRegistry actionRegistry = new ActionRegistry();

    private Path currentFile;
    private String savedContent = "";  // snapshot of content at last save/load
    private boolean initialized;

    // ---- Sample scripts -------------------------------------------------

    record Sample(int key, String label, String resource) {}
    static final Sample[] SAMPLES = {
            new Sample(GLFW_KEY_F1, "Shapes",   "/samples/canvas_shapes.spn"),
            new Sample(GLFW_KEY_F2, "Plot",     "/samples/canvas_grid.spn"),
    };

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

        // Create clipboard bridge
        Clipboard clipboard = new Clipboard() {
            @Override public void set(String text) { glfwSetClipboardString(handle, text); }
            @Override public String get() { return glfwGetClipboardString(handle); }
        };

        // Create spn-stdui WindowFrame
        frame = new WindowFrame(
                Main.instance.getWindowManager().buffers(),
                clipboard,
                Main.instance.getWindowManager().actions()
        );

        // Handle MODE_MENU signal → push action palette
        frame.getModeManager().setSignalHandler((signal, active) -> {
            if (signal == ControlSignal.MODE_MENU) {
                pushLegacyMode(new ActionMenuMode(this, actionRegistry));
                return true;
            }
            return false;
        });

        // Legacy components (still used by EditorMode and template system)
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

        legacyHud = new Hud(font);
        legacyHud.setText(EditorMode.BASE_SHORTCUTS);

        // EditorMode (legacy) is always at the bottom — suppress SUBMIT/CANCEL
        // so Ctrl+Space and Ctrl+Backspace reach it as normal keystrokes
        EditorMode editorMode = new EditorMode(this, textArea, vScroll, hScroll);
        frame.getModeManager().push(new LegacyModeAdapter(
                editorMode, this::getSize,
                java.util.Set.of(ControlSignal.SUBMIT, ControlSignal.CANCEL)));

        registerActions();
        setupCallbacks();
    }

    // ---- Mode stack (bridged to spn-stdui ModeManager) ------------------

    /** Push a legacy Mode onto the spn-stdui mode stack via adapter. */
    public void pushLegacyMode(Mode mode) {
        frame.getModeManager().push(new LegacyModeAdapter(mode, this::getSize));
    }

    /** Pop the active mode. Never pops the bottom (EditorMode). */
    public void popMode() {
        frame.getModeManager().pop();
    }

    private float[] getSize() {
        int[] w = new int[1], h = new int[1];
        glfwGetFramebufferSize(handle, w, h);
        return new float[]{ w[0], h[0] };
    }

    // ---- Action registration --------------------------------------------

    private void registerActions() {
        actionRegistry.register("New",           "File", "Ctrl+N",       () -> pushLegacyMode(new NewMenuMode(this)));
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
        actionRegistry.register("Action Menu",   "View",   "Ctrl+P",
                () -> pushLegacyMode(new ActionMenuMode(this, actionRegistry)));
        actionRegistry.register("Create Module", "File",   "Ctrl+M",
                () -> pushLegacyMode(new PlaceholderMode(this, "Create Module")));
    }

    // ---- Accessors -------------------------------------------------------

    public long getHandle() { return handle; }

    /** Legacy Hud — used by legacy modes for flash messages. */
    public Hud getHud() { return legacyHud; }

    public ActionRegistry getActionRegistry() { return actionRegistry; }

    public TextArea getTextArea() { return textArea; }

    public SdfFontRenderer getFont() { return font; }

    WindowFrame getFrame() { return frame; }

    String getSavedContent() { return savedContent; }

    /** Reset the editor to a blank, untitled state. */
    void clearForNewFile() {
        clearForNewFile("");
    }

    /** Reset the editor with the given initial content (treated as clean). */
    void clearForNewFile(String content) {
        textArea.setText(content);
        currentFile = null;
        savedContent = content;
        glfwSetWindowTitle(handle, "untitled - Symbols+Numbers");
    }

    void show() { glfwShowWindow(handle); }

    boolean shouldClose() { return glfwWindowShouldClose(handle); }

    boolean isFocused() { return glfwGetWindowAttrib(handle, GLFW_FOCUSED) != 0; }

    void makeCurrent() { glfwMakeContextCurrent(handle); }

    /** Called by the GLFW close callback and by ConfirmExitMode after a decision. */
    void requestClose() {
        glfwSetWindowShouldClose(handle, true);
    }

    /**
     * Stack-aware close behavior:
     * - If modes are stacked above the base editor, pop one.
     * - If only the base editor remains and content is clean, close.
     * - Otherwise push a ConfirmExitMode.
     */
    private void handleCloseRequest() {
        // If there are modes above the base editor, pop one instead of closing
        if (frame.getModeManager().depth() > 1) {
            frame.getModeManager().pop();
            return;
        }

        // Base editor is the only mode — check if content is dirty
        boolean dirty = !textArea.getText().equals(savedContent);
        if (!dirty) {
            requestClose();
            return;
        }

        // Unsaved changes — push confirmation prompt
        pushLegacyMode(new ConfirmExitMode(this));
    }

    // ---- Rendering (delegates to WindowFrame) ----------------------------

    void render() {
        int[] w = new int[1], h = new int[1];
        glfwGetFramebufferSize(handle, w, h);
        glViewport(0, 0, w[0], h[0]);
        glClear(GL_COLOR_BUFFER_BIT);

        double now = glfwGetTime();

        font.beginFrame(w[0], h[0]);
        frame.render(font, w[0], h[0], now);
        font.endFrame();

        glfwSwapBuffers(handle);
    }

    void destroy() { glfwDestroyWindow(handle); }

    // ---- Sample loading -------------------------------------------------

    void openSample(Sample sample) {
        try (InputStream in = getClass().getResourceAsStream(sample.resource())) {
            if (in == null) {
                legacyHud.flash("Sample not found: " + sample.resource(), true);
                return;
            }
            String content = new String(in.readAllBytes(), StandardCharsets.UTF_8);

            if (currentFile == null && textArea.getText().isBlank()) {
                textArea.setText(content);
                savedContent = content;
                currentFile = null;
                glfwSetWindowTitle(handle, sample.label() + " - Symbols+Numbers");
            } else {
                EditorWindow ew = Main.instance.spawnWindow();
                ew.textArea.setText(content);
                glfwSetWindowTitle(ew.handle, sample.label() + " - Symbols+Numbers");
            }
        } catch (IOException e) {
            legacyHud.flash("Error loading sample: " + e.getMessage(), true);
        }
    }

    // ---- Build & Run ----------------------------------------------------

    void runCurrentFile() {
        String source = textArea.getText();
        if (source.isBlank()) {
            legacyHud.flash("Nothing to run", true);
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
                legacyHud.flash("=> " + display, false);
            }
        } catch (Exception e) {
            String msg = e.getMessage();
            if (msg == null) msg = e.getClass().getSimpleName();
            legacyHud.flash("Error: " + msg, true);
        } finally {
            spn.canvas.CanvasState.clear();
        }
    }

    // ---- File operations ------------------------------------------------

    void loadFile(Path path) throws IOException {
        String content = Files.readString(path);

        // .spnt files get a dialog: instantiate or edit
        if (spn.gui.template.SpntParser.isTemplate(path.toString())) {
            pushLegacyMode(new spn.gui.template.TemplateOpenMode(this, path, content));
            return;
        }

        loadFileDirectly(path, content);
    }

    /** Load file content into the editor without any .spnt interception. */
    public void loadFileDirectly(Path path, String content) {
        textArea.setText(content);
        currentFile = path;
        savedContent = content;
        updateTitle();
    }

    /** Parse a .spnt template and enter instantiation mode. */
    public void loadTemplateForInstantiation(Path path, String content) {
        // Set the file context (so title shows the template name)
        currentFile = path;
        updateTitle();
        // Push instantiation mode — it loads editable text into textArea
        pushLegacyMode(new spn.gui.template.TemplateInstantiationMode(this, content));
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
            String content = textArea.getText();
            Files.writeString(target, content);
            currentFile = target;
            savedContent = content;
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

    // ---- Callbacks — translate GLFW to InputEvent, dispatch to frame -----

    private void setupCallbacks() {
        glfwSetCharCallback(handle, (win, codepoint) ->
                frame.dispatch(new InputEvent.CharInput(codepoint)));

        glfwSetKeyCallback(handle, (win, key, scancode, action, mods) -> {
            Key k = Key.fromGlfw(key);
            int m = Mod.fromGlfw(mods);
            InputEvent event = switch (action) {
                case GLFW_PRESS   -> new InputEvent.KeyPress(k, m);
                case GLFW_REPEAT  -> new InputEvent.KeyRepeat(k, m);
                case GLFW_RELEASE -> new InputEvent.KeyRelease(k, m);
                default -> null;
            };
            if (event != null) frame.dispatch(event);
        });

        glfwSetScrollCallback(handle, (win, xoff, yoff) ->
                frame.dispatch(new InputEvent.MouseScroll(xoff, yoff)));

        glfwSetMouseButtonCallback(handle, (win, button, action, mods) -> {
            double[] mx = new double[1], my = new double[1];
            glfwGetCursorPos(win, mx, my);
            int m = Mod.fromGlfw(mods);
            if (action == GLFW_PRESS) {
                frame.dispatch(new InputEvent.MousePress(button, m, mx[0], my[0]));
            } else if (action == GLFW_RELEASE) {
                frame.dispatch(new InputEvent.MouseRelease(button, m, mx[0], my[0]));
            }
        });

        glfwSetCursorPosCallback(handle, (win, xpos, ypos) ->
                frame.dispatch(new InputEvent.MouseMove(xpos, ypos)));

        glfwSetCursorEnterCallback(handle, (win, entered) ->
                frame.dispatch(new InputEvent.MouseEnter(entered)));

        glfwSetWindowCloseCallback(handle, win -> {
            glfwSetWindowShouldClose(win, false);
            handleCloseRequest();
        });

        glfwSetWindowFocusCallback(handle, (win, focused) ->
                Main.instance.onWindowFocusChanged());

        glfwSetWindowRefreshCallback(handle, win -> {
            makeCurrent();
            render();
        });
    }
}
