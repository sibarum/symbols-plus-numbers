package spn.gui;

import spn.fonts.SdfFontRenderer;
import spn.lang.SpnParser;
import spn.node.SpnRootNode;
import spn.stdui.action.ActionRegistry;
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

    // spn-stdui window frame — owns ModeManager and new Hud
    private WindowFrame frame;

    // Tab system — replaces single TextArea/file state
    private TabView tabView;

    private final ActionRegistry actionRegistry = new ActionRegistry();
    private final LogBuffer logBuffer = new LogBuffer();
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

        // Tab system — first tab is an empty editor
        tabView = new TabView(font);
        tabView.addTab(new EditorTab(this));

        // TabViewMode sits at the bottom of the mode stack — suppress SUBMIT/CANCEL
        TabViewMode tabViewMode = new TabViewMode(this, tabView);
        frame.getModeManager().push(new LegacyModeAdapter(
                tabViewMode, this::getSize,
                java.util.Set.of(ControlSignal.SUBMIT, ControlSignal.CANCEL)));

        registerActions();
        setupCallbacks();

        // Show splash screen when starting with no file
        pushLegacyMode(new SplashMode(this));
    }

    // ---- Mode stack (bridged to spn-stdui ModeManager) ------------------

    /** Push a legacy Mode onto the spn-stdui mode stack via adapter. */
    public void pushLegacyMode(Mode mode) {
        frame.getModeManager().push(new LegacyModeAdapter(mode, this::getSize));
    }

    /** Pop the active mode. Never pops the bottom (TabViewMode). */
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
        actionRegistry.register("New",           "File",   "Ctrl+N",       "Open a new empty editor tab.",                          this::openNewTab);
        actionRegistry.register("Open File",     "File",   "Ctrl+O",       "Open a file from disk in a new tab. Switches to it if already open.", this::openFile);
        actionRegistry.register("Save File",     "File",   "Ctrl+S",       "Save the active editor tab to disk.",                   () -> saveFile(false));
        actionRegistry.register("Save As",       "File",   "Ctrl+Shift+S", "Save the active editor tab to a new file path.",        () -> saveFile(true));
        actionRegistry.register("Run",           "Run",    "F5",           "Parse and execute the active tab's SPN source. Canvas output opens a separate window.", this::runCurrentFile);
        actionRegistry.register("Undo",          "Edit",   "Ctrl+Z",       "Undo the last edit in the active editor.",              () -> { TextArea ta = getTextArea(); if (ta != null) ta.performUndo(); });
        actionRegistry.register("Redo",          "Edit",   "Ctrl+Y",       "Redo a previously undone edit.",                        () -> { TextArea ta = getTextArea(); if (ta != null) ta.performRedo(); });
        actionRegistry.register("Zoom In",       "View",   "Ctrl+=",       "Increase editor font size.",                            () -> { TextArea ta = getTextArea(); if (ta != null) ta.zoomIn(); });
        actionRegistry.register("Zoom Out",      "View",   "Ctrl+-",       "Decrease editor font size.",                            () -> { TextArea ta = getTextArea(); if (ta != null) ta.zoomOut(); });
        actionRegistry.register("Zoom Reset",    "View",   "Ctrl+0",       "Reset editor font size to default.",                    () -> { TextArea ta = getTextArea(); if (ta != null) ta.zoomReset(); });
        actionRegistry.register("Shapes Sample", "Sample", "F1",           "Load the canvas shapes demo into a tab.",               () -> openSample(SAMPLES[0]));
        actionRegistry.register("Plot Sample",   "Sample", "F2",           "Load the function plot demo into a tab.",                () -> openSample(SAMPLES[1]));
        actionRegistry.register("Logs",          "View",   "Ctrl+G",       "Open the execution log viewer in a tab. Shows run results, errors, and messages.", this::openLogTab);
        actionRegistry.register("Action Menu",   "View",   "Ctrl+P",       "Open the command palette for quick action search.",     () -> pushLegacyMode(new ActionMenuMode(this, actionRegistry)));
        actionRegistry.register("Import Browser","File",   "Ctrl+I",       "Search and insert import statements for modules and their exports.", () -> pushLegacyMode(new ImportMode(this)));
        actionRegistry.register("Module Browser","File",   "Ctrl+M",       "Browse files within the current module. Requires a module.spn in a parent directory.", () -> {
            ModuleContext ctx = getModuleContext();
            if (ctx != null) pushLegacyMode(new ModuleMode(this, ctx));
            else flash("No module loaded \u2014 cannot find module.spn", true);
        });
        actionRegistry.register("Help",          "Help",   "Ctrl+/",       "Open the help search. Search commands, shortcuts, and API reference.", () -> pushLegacyMode(new HelpMode(this, actionRegistry)));
    }

    // ---- Accessors -------------------------------------------------------

    public long getHandle() { return handle; }

    /** Height of the HUD bar in pixels, for layout calculations. */
    public float getHudHeight() {
        return font.getLineHeight(0.25f) * 1.4f;
    }

    public ActionRegistry getActionRegistry() { return actionRegistry; }

    /** Returns the active editor tab's TextArea, or null if no editor tab is active. */
    public TextArea getTextArea() {
        Tab active = tabView.getActiveTab();
        if (active instanceof EditorTab et) return et.getTextArea();
        return null;
    }

    public SdfFontRenderer getFont() { return font; }

    public ModuleContext getModuleContext() {
        Tab active = tabView.getActiveTab();
        if (active instanceof EditorTab et) return et.getModuleContext();
        return null;
    }

    public LogBuffer getLogBuffer() { return logBuffer; }

    public TabView getTabView() { return tabView; }

    /** Flash a message in the HUD and append it to the log. */
    public void flash(String message, boolean isError) {
        frame.getHud().flash(message, isError);
        logBuffer.append(message, isError);
    }

    WindowFrame getFrame() { return frame; }

    String getSavedContent() {
        Tab active = tabView.getActiveTab();
        if (active instanceof EditorTab et) return et.getSavedContent();
        return "";
    }

    /** Get the active editor tab, or null. */
    EditorTab getActiveEditorTab() {
        Tab active = tabView.getActiveTab();
        return active instanceof EditorTab et ? et : null;
    }

    /** Open a new empty editor tab. */
    void openNewTab() {
        EditorTab tab = new EditorTab(this);
        tabView.addTab(tab);
        updateTitle();
    }

    /** Open a new editor tab with initial content. */
    void openNewTab(String content) {
        EditorTab tab = new EditorTab(this);
        tab.loadContent(content);
        tabView.addTab(tab);
        updateTitle();
    }

    /** Open the log tab, or switch to it if already open. */
    void openLogTab() {
        Tab existing = tabView.findTab(t -> t instanceof LogTab);
        if (existing != null) {
            tabView.switchTo(existing);
        } else {
            tabView.addTab(new LogTab(this));
        }
    }

    /** Handle Escape on the tab view: close active tab (with dirty prompt if needed). */
    void handleTabClose() {
        Tab active = tabView.getActiveTab();
        if (active == null) return;

        // Log tab: close without prompt
        if (active instanceof LogTab) {
            tabView.closeActiveTab();
            if (tabView.tabCount() == 0) {
                // No tabs left — open a fresh editor tab
                openNewTab();
            }
            return;
        }

        // Editor tab: check dirty
        if (active instanceof EditorTab et) {
            if (et.isDirty()) {
                pushLegacyMode(new ConfirmExitMode(this));
            } else {
                tabView.closeActiveTab();
                if (tabView.tabCount() == 0) {
                    openNewTab();
                }
                updateTitle();
            }
        }
    }

    /** Reset the active editor tab to a blank state. */
    void clearForNewFile() {
        clearForNewFile("");
    }

    void clearForNewFile(String content) {
        EditorTab et = getActiveEditorTab();
        if (et != null) {
            et.loadContent(content);
            et.setFilePath(null);
            et.setModuleContext(null);
        }
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
        // If there are modes above the base tab view, pop one instead of closing
        if (frame.getModeManager().depth() > 1) {
            frame.getModeManager().pop();
            return;
        }

        // Check if any editor tab has unsaved changes
        boolean anyDirty = tabView.getTabs().stream()
                .anyMatch(t -> t instanceof EditorTab et && et.isDirty());
        if (!anyDirty) {
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
                flash("Sample not found: " + sample.resource(), true);
                return;
            }
            String content = new String(in.readAllBytes(), StandardCharsets.UTF_8);

            EditorTab et = getActiveEditorTab();
            if (et != null && et.getFilePath() == null && et.getTextArea().getText().isBlank()) {
                // Load into current empty tab
                et.loadContent(content);
                glfwSetWindowTitle(handle, sample.label() + " - Symbols+Numbers");
            } else {
                // Open in a new tab
                EditorTab newTab = new EditorTab(this);
                newTab.loadContent(content);
                tabView.addTab(newTab);
                glfwSetWindowTitle(handle, sample.label() + " - Symbols+Numbers");
            }
        } catch (IOException e) {
            flash("Error loading sample: " + e.getMessage(), true);
        }
    }

    // ---- Build & Run ----------------------------------------------------

    void runCurrentFile() {
        EditorTab et = getActiveEditorTab();
        if (et == null) { flash("No editor tab active", true); return; }
        String source = et.getTextArea().getText();
        Path currentFile = et.getFilePath();
        String fileName = currentFile != null ? currentFile.getFileName().toString() : "untitled";
        if (source.isBlank()) {
            flash("[" + fileName + "] Nothing to run", true);
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
            String fullPath = currentFile != null ? currentFile.toAbsolutePath().toString() : "untitled";
            SpnParser parser = new SpnParser(source, fullPath, null, symbolTable, moduleRegistry);
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
                flash("[" + fileName + "] => " + display, false);
            }
        } catch (spn.language.SpnException se) {
            flash(se.formatMessage(), true);
        } catch (spn.lang.SpnParseException pe) {
            flash(pe.formatMessage(), true);
        } catch (Exception e) {
            String msg = e.getMessage();
            if (msg == null) msg = e.getClass().getSimpleName();
            flash("[" + fileName + "] Error: " + msg, true);
        } finally {
            spn.canvas.CanvasState.clear();
        }
    }

    // ---- Module detection ------------------------------------------------

    private void detectModule(EditorTab tab, Path filePath) {
        if (tab.getModuleContext() != null && tab.getModuleContext().contains(filePath)) return;
        ModuleContext ctx = ModuleContext.detect(filePath);
        tab.setModuleContext(ctx);
        if (ctx != null) {
            flash("Module: " + ctx.getNamespace(), false);
        }
    }

    // ---- File operations ------------------------------------------------

    void loadFile(Path path) throws IOException {
        // Check if this file is already open in a tab
        Tab existing = tabView.findTab(t ->
                t instanceof EditorTab et && path.equals(et.getFilePath()));
        if (existing != null) {
            tabView.switchTo(existing);
            updateTitle();
            return;
        }

        String content = Files.readString(path);

        // .spnt files get a dialog: instantiate or edit
        if (spn.gui.template.SpntParser.isTemplate(path.toString())) {
            pushLegacyMode(new spn.gui.template.TemplateOpenMode(this, path, content));
            return;
        }

        loadFileDirectly(path, content);
    }

    /** Load file content into the editor — opens in current tab if empty, new tab otherwise. */
    public void loadFileDirectly(Path path, String content) {
        EditorTab et = getActiveEditorTab();
        if (et != null && et.getFilePath() == null && et.getTextArea().getText().isBlank()) {
            // Reuse current empty tab
            et.loadContent(content);
            et.setFilePath(path);
            detectModule(et, path);
        } else {
            // Open in new tab
            et = new EditorTab(this);
            et.loadContent(content);
            et.setFilePath(path);
            tabView.addTab(et);
            detectModule(et, path);
        }
        updateTitle();
    }

    /** Parse a .spnt template and enter instantiation mode. */
    public void loadTemplateForInstantiation(Path path, String content) {
        EditorTab et = getActiveEditorTab();
        if (et != null) {
            et.setFilePath(path);
        }
        updateTitle();
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
        EditorTab activeTab = getActiveEditorTab();
        Path activePath = activeTab != null ? activeTab.getFilePath() : null;
        String defaultPath = activePath != null ? activePath.toString() : "";
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

    public void saveFile(boolean saveAs) {
        EditorTab et = getActiveEditorTab();
        if (et == null) return;
        Path target = et.getFilePath();
        if (target == null || saveAs) {
            String defaultPath = target != null ? target.toString() : "";
            String path = org.lwjgl.util.tinyfd.TinyFileDialogs.tinyfd_saveFileDialog(
                    "Save File", defaultPath, Main.SPN_FILTER, "SPN / Text files");
            if (path == null) return;
            target = Path.of(path);
        }
        try {
            String content = et.getTextArea().getText();
            Files.writeString(target, content);
            et.setFilePath(target);
            et.setSavedContent(content);
            updateTitle();
        } catch (IOException e) {
            org.lwjgl.util.tinyfd.TinyFileDialogs.tinyfd_messageBox(
                    "Error", "Could not save file:\n" + e.getMessage(), "ok", "error", true);
        }
    }

    private void updateTitle() {
        EditorTab et = getActiveEditorTab();
        String name = "untitled";
        if (et != null && et.getFilePath() != null) {
            name = et.getFilePath().getFileName().toString();
        }
        glfwSetWindowTitle(handle, name + " - Symbols+Numbers");
    }

    // ---- Callbacks — translate GLFW to InputEvent, dispatch to frame -----

    private void setupCallbacks() {
        glfwSetCharCallback(handle, (win, codepoint) ->
                frame.dispatch(new InputEvent.CharInput(codepoint)));

        glfwSetKeyCallback(handle, (win, key, scancode, action, mods) -> {
            // Pressing Ctrl clears any flash message so shortcuts are visible
            if (action == GLFW_PRESS
                    && (key == GLFW_KEY_LEFT_CONTROL || key == GLFW_KEY_RIGHT_CONTROL)) {
                frame.getHud().clearFlash();
            }

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
