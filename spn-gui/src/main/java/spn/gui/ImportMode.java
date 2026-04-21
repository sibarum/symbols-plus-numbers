package spn.gui;

import spn.fonts.SdfFontRenderer;
import spn.stdui.buffer.TextBuffer;

import java.util.*;

import static org.lwjgl.glfw.GLFW.*;

/**
 * Import search mode (Ctrl+I). Shows all available modules and their exports.
 * Searching by module name shows "import Module"; searching by export name
 * shows both "import Module" and "import Module (exportName)".
 *
 * Selection inserts the import at the top of the file (after existing imports)
 * without moving the cursor.
 */
class ImportMode implements Mode {

    private static final float FONT_SCALE = 0.35f;
    private static final float SMALL_SCALE = 0.25f;
    private static final float PAD = 30f;
    private static final int MAX_VISIBLE_ROWS = 20;
    private static final float ROW_HEIGHT_FACTOR = 1.4f;

    private static final float BG_R = 0.10f, BG_G = 0.10f, BG_B = 0.12f;
    private static final float INPUT_BG_R = 0.16f, INPUT_BG_G = 0.16f, INPUT_BG_B = 0.20f;
    private static final float SEL_R = 0.20f, SEL_G = 0.30f, SEL_B = 0.50f;
    private static final float NAME_R = 0.85f, NAME_G = 0.85f, NAME_B = 0.85f;
    private static final float MODULE_R = 0.50f, MODULE_G = 0.65f, MODULE_B = 0.80f;
    private static final float CURSOR_R = 0.90f, CURSOR_G = 0.90f, CURSOR_B = 0.30f;
    private static final float PROMPT_R = 0.55f, PROMPT_G = 0.55f, PROMPT_B = 0.60f;

    /** Kind of importable item — drives rendering color and import-statement shape. */
    enum Kind {
        MODULE,      // whole-module import: `import Module`
        FUNCTION,    // stdlib function or .spn-exported function
        TYPE,        // struct or user-defined type
        SIGNATURE,   // `signature Name (...)`
        KEY,         // qualified dispatch key `@ns.name` — imported via `import ns.(name)`
        MACRO        // `macro foo(...)`
    }

    /** An importable item: a whole module or a specific export from a module. */
    record ImportItem(String moduleName, String exportName, Kind kind,
                      String displayText, String importStatement) {}

    private final EditorWindow window;
    private final SdfFontRenderer font;
    private List<ImportItem> allItems;

    private final StringBuilder query = new StringBuilder();
    private int cursorPos;
    private int selectedIndex;
    private int scrollOffset;
    private List<ImportItem> filtered;

    // Cross-reference index: signature name → set of required dispatch keys.
    // Queried during filtering so typing a key-name substring also surfaces
    // signatures that reference that key.
    private Map<String, Set<String>> sigKeyRefs = new HashMap<>();

    ImportMode(EditorWindow window) {
        this.window = window;
        this.font = window.getFont();
        this.allItems = buildImportIndex();
        refilter(); // start with top-level modules only
    }

    @Override
    public boolean onKey(int key, int scancode, int action, int mods) {
        if (action != GLFW_PRESS && action != GLFW_REPEAT) return true;

        boolean ctrl = (mods & GLFW_MOD_CONTROL) != 0;

        // Ctrl+R rebuilds the import index and clears module caches, mirroring
        // the same shortcut in module mode.
        if (ctrl && key == GLFW_KEY_R) {
            allItems = buildImportIndex();
            refilter();
            window.refreshModuleCaches();
            window.flash("Imports refreshed — caches cleared", false);
            return true;
        }

        // Ctrl+V — paste clipboard text into the query at the cursor
        if (ctrl && key == GLFW_KEY_V) {
            String clip = window.getClipboardText();
            if (!clip.isEmpty()) {
                query.insert(cursorPos, clip);
                cursorPos += clip.length();
                refilter();
            }
            return true;
        }

        switch (key) {
            case GLFW_KEY_ESCAPE -> { window.popMode(); return true; }
            case GLFW_KEY_ENTER -> {
                if (!filtered.isEmpty() && selectedIndex < filtered.size()) {
                    insertImport(filtered.get(selectedIndex));
                    window.popMode();
                }
                return true;
            }
            case GLFW_KEY_UP -> {
                if (selectedIndex > 0) selectedIndex--;
                ensureVisible();
                return true;
            }
            case GLFW_KEY_DOWN -> {
                if (selectedIndex < filtered.size() - 1) selectedIndex++;
                ensureVisible();
                return true;
            }
            case GLFW_KEY_BACKSPACE -> {
                if (cursorPos > 0) { query.deleteCharAt(cursorPos - 1); cursorPos--; refilter(); }
                return true;
            }
            case GLFW_KEY_DELETE -> {
                if (cursorPos < query.length()) { query.deleteCharAt(cursorPos); refilter(); }
                return true;
            }
            case GLFW_KEY_LEFT -> { if (cursorPos > 0) cursorPos--; return true; }
            case GLFW_KEY_RIGHT -> { if (cursorPos < query.length()) cursorPos++; return true; }
            case GLFW_KEY_HOME -> { cursorPos = 0; return true; }
            case GLFW_KEY_END -> { cursorPos = query.length(); return true; }
        }
        return true;
    }

    @Override
    public boolean onChar(int codepoint) {
        query.insert(cursorPos, Character.toChars(codepoint));
        cursorPos++;
        refilter();
        return true;
    }

    @Override
    public boolean onMouseButton(int button, int action, int mods, double mx, double my) {
        if (button == GLFW_MOUSE_BUTTON_LEFT && action == GLFW_PRESS) {
            int clicked = rowAtY(my);
            if (clicked >= 0 && clicked < filtered.size()) {
                insertImport(filtered.get(clicked));
                window.popMode();
            }
        }
        return true;
    }

    @Override
    public boolean onCursorPos(double mx, double my) {
        int hover = rowAtY(my);
        if (hover >= 0 && hover < filtered.size()) selectedIndex = hover;
        return true;
    }

    @Override
    public boolean onScroll(double xoff, double yoff) {
        scrollOffset = Math.max(0, Math.min(scrollOffset - ListScroll.delta(yoff),
                Math.max(0, filtered.size() - MAX_VISIBLE_ROWS)));
        return true;
    }

    @Override
    public void render(float width, float height) {
        font.drawRect(0, 0, width, height, BG_R, BG_G, BG_B);

        float rowHeight = font.getLineHeight(FONT_SCALE) * ROW_HEIGHT_FACTOR;
        float smallHeight = font.getLineHeight(SMALL_SCALE) * ROW_HEIGHT_FACTOR;
        float paletteWidth = Math.min(width - PAD * 2, 700f);
        float paletteX = (width - paletteWidth) / 2f;
        float y = PAD + 40f;

        // Title (offset up by half an em so it clears the search input background)
        font.drawText("> Import", paletteX, y - smallHeight * 0.5f, SMALL_SCALE, PROMPT_R, PROMPT_G, PROMPT_B);
        y += smallHeight + 8f;

        // Search input
        float inputH = rowHeight + 8f;
        font.drawRect(paletteX, y - rowHeight, paletteWidth, inputH,
                INPUT_BG_R, INPUT_BG_G, INPUT_BG_B);
        String queryStr = query.toString();
        float textX = paletteX + 12f;
        font.drawText(queryStr, textX, y, FONT_SCALE, NAME_R, NAME_G, NAME_B);
        float cursorX = textX + font.getTextWidth(queryStr.substring(0, cursorPos), FONT_SCALE);
        font.drawRect(cursorX, y - rowHeight + 4f, 2f, rowHeight,
                CURSOR_R, CURSOR_G, CURSOR_B);
        y += inputH + 8f;

        // Results
        listTop = y;
        listRowH = rowHeight;
        int visibleCount = Math.min(MAX_VISIBLE_ROWS, filtered.size() - scrollOffset);
        for (int i = 0; i < visibleCount; i++) {
            int idx = scrollOffset + i;
            ImportItem item = filtered.get(idx);
            float rowY = y + i * rowHeight;

            if (idx == selectedIndex) {
                font.drawRect(paletteX, rowY, paletteWidth, rowHeight, SEL_R, SEL_G, SEL_B);
            }

            float itemY = rowY + rowHeight - 4f;

            // Module name in accent color
            font.drawText(item.moduleName(), paletteX + 12f, itemY, SMALL_SCALE,
                    MODULE_R, MODULE_G, MODULE_B);
            float modW = font.getTextWidth(item.moduleName(), SMALL_SCALE);

            // Display text tinted by the item's kind (keys green, signatures
            // purple, macros yellow, types cyan, functions/modules neutral)
            float[] color = colorForKind(item.kind());
            font.drawText("  " + item.displayText(), paletteX + 12f + modW, itemY, FONT_SCALE,
                    color[0], color[1], color[2]);
        }

        // Scroll indicator
        if (filtered.size() > MAX_VISIBLE_ROWS) {
            String info = (scrollOffset + 1) + "-" + (scrollOffset + visibleCount)
                    + " of " + filtered.size();
            float infoW = font.getTextWidth(info, SMALL_SCALE);
            font.drawText(info, paletteX + paletteWidth - 12f - infoW,
                    y + visibleCount * rowHeight + smallHeight,
                    SMALL_SCALE, PROMPT_R, PROMPT_G, PROMPT_B);
        }
    }

    private float listTop;
    private float listRowH;

    @Override
    public String hudText() {
        return "Search: @ keys | sig: signatures | macro: macros | type: types | fn: functions | Ctrl+R Refresh";
    }

    /** Pick a tint color matching the TokenType used in source-code highlighting. */
    private static float[] colorForKind(Kind kind) {
        return switch (kind) {
            case KEY       -> new float[]{ 0.55f, 0.85f, 0.45f }; // lime-green (QUALIFIED_KEY)
            case SIGNATURE -> new float[]{ 0.55f, 0.50f, 0.85f }; // purple (KEYWORD-like)
            case MACRO     -> new float[]{ 0.95f, 0.80f, 0.30f }; // warm yellow
            case TYPE      -> new float[]{ 0.35f, 0.78f, 0.75f }; // cyan (TYPE_NAME)
            case FUNCTION, MODULE -> new float[]{ NAME_R, NAME_G, NAME_B }; // neutral white
        };
    }

    // ---- Import index ----

    private List<ImportItem> buildImportIndex() {
        List<ImportItem> items = new ArrayList<>();
        sigKeyRefs.clear(); // rebuild the cross-ref index from scratch

        // Stdlib modules — all exports are functions
        var byModule = new LinkedHashMap<String, List<String>>();
        for (var entry : spn.stdlib.gen.SpnStdlibRegistry.allBuiltins()) {
            byModule.computeIfAbsent(entry.module(), k -> new ArrayList<>()).add(entry.name());
        }

        // Canvas module exports
        {
            var canvasRegistry = new spn.language.SpnModuleRegistry();
            spn.canvas.CanvasBuiltins.registerModule(canvasRegistry);
            canvasRegistry.lookup("Canvas").ifPresent(mod -> {
                var exports = byModule.computeIfAbsent("Canvas", k -> new ArrayList<>());
                exports.addAll(mod.allExportedNames());
            });
        }

        // CanvasGui module exports
        {
            var guiRegistry = new spn.language.SpnModuleRegistry();
            spn.canvasgui.spn.CanvasGuiBuiltins.registerModule(guiRegistry);
            guiRegistry.lookup("CanvasGui").ifPresent(mod -> {
                var exports = byModule.computeIfAbsent("CanvasGui", k -> new ArrayList<>());
                exports.addAll(mod.allExportedNames());
            });
        }

        for (var entry : byModule.entrySet()) {
            String mod = entry.getKey();
            items.add(new ImportItem(mod, null, Kind.MODULE,
                    "import " + mod, "import " + mod));
            for (String exp : entry.getValue()) {
                if (isImportableExport(exp)) {
                    items.add(new ImportItem(mod, exp, Kind.FUNCTION,
                            "import " + mod + " (" + exp + ")",
                            "import " + mod + " (" + exp + ")"));
                }
            }
        }

        // .spn script modules on the classpath — have full kind info via extras
        try {
            var symbolTable = new spn.type.SpnSymbolTable();
            var loader = new spn.lang.ClasspathModuleLoader(null, symbolTable);
            var registry = new spn.language.SpnModuleRegistry();
            spn.stdlib.gen.StdlibModuleLoader.registerAll(registry);
            spn.canvas.CanvasBuiltins.registerModule(registry);
            spn.canvasgui.spn.CanvasGuiBuiltins.registerModule(registry);
            registry.addLoader(loader);

            for (String namespace : loader.discoverModules()) {
                if (byModule.containsKey(namespace)) continue;
                try {
                    registry.resolve(namespace).ifPresent(mod ->
                            addModuleItems(items, namespace, mod, ""));
                } catch (Exception e) {
                    items.add(new ImportItem(namespace, null, Kind.MODULE,
                            "import " + namespace, "import " + namespace));
                }
            }
        } catch (Exception e) {
            // Best-effort
        }

        // Sibling .spn files in the current module context
        ModuleContext ctx = window.getModuleContext();
        if (ctx != null) {
            ctx.rescan();
            for (ModuleContext.ModuleFile f : ctx.getFiles()) {
                String rel = f.relativePath();
                if (rel.endsWith(".spn")) rel = rel.substring(0, rel.length() - 4);
                String namespace = rel.replace('/', '.').replace('\\', '.');

                items.add(new ImportItem(namespace, null, Kind.MODULE,
                        "import " + namespace + "  [module]",
                        "import " + namespace));

                try {
                    var modRegistry = new spn.language.SpnModuleRegistry();
                    spn.stdlib.gen.StdlibModuleLoader.registerAll(modRegistry);
                    spn.canvas.CanvasBuiltins.registerModule(modRegistry);
                    spn.canvasgui.spn.CanvasGuiBuiltins.registerModule(modRegistry);
                    var symTable = new spn.type.SpnSymbolTable();
                    modRegistry.addLoader(new spn.lang.ClasspathModuleLoader(null, symTable));
                    modRegistry.addLoader(new spn.lang.FilesystemModuleLoader(
                            ctx.getRoot(), ctx.getNamespace(), null, symTable));
                    modRegistry.resolve(namespace).ifPresent(mod ->
                            addModuleItems(items, namespace, mod, "  [module]"));
                } catch (Exception e) {
                    // Skip exports if module fails to parse
                }
            }
        }

        items.sort(Comparator.comparing(ImportItem::displayText));
        return items;
    }

    /**
     * Emit ImportItems for every importable entity in a module: functions and
     * types from first-class fields; signatures, qualified keys, and macros
     * from extras (populated by the filesystem/classpath module loaders).
     */
    @SuppressWarnings("unchecked")
    private void addModuleItems(List<ImportItem> items, String namespace,
                                spn.language.SpnModule mod, String tag) {
        // Functions and builtin factories → Kind.FUNCTION
        for (String name : mod.getFunctions().keySet()) {
            if (isImportableExport(name)) {
                items.add(new ImportItem(namespace, name, Kind.FUNCTION,
                        "import " + namespace + " (" + name + ")" + tag,
                        "import " + namespace + " (" + name + ")"));
            }
        }
        for (String name : mod.getBuiltinFactories().keySet()) {
            if (isImportableExport(name)) {
                items.add(new ImportItem(namespace, name, Kind.FUNCTION,
                        "import " + namespace + " (" + name + ")" + tag,
                        "import " + namespace + " (" + name + ")"));
            }
        }

        // Types and structs → Kind.TYPE
        for (String name : mod.getTypes().keySet()) {
            if (isImportableExport(name)) {
                items.add(new ImportItem(namespace, name, Kind.TYPE,
                        "import " + namespace + " (" + name + ")" + tag,
                        "import " + namespace + " (" + name + ")"));
            }
        }
        for (String name : mod.getStructs().keySet()) {
            if (isImportableExport(name)) {
                items.add(new ImportItem(namespace, name, Kind.TYPE,
                        "import " + namespace + " (" + name + ")" + tag,
                        "import " + namespace + " (" + name + ")"));
            }
        }

        // Macros, signatures, and qualified keys live in extras
        Map<String, ?> macros = (Map<String, ?>) mod.getExtra("macros");
        if (macros != null) {
            for (String name : macros.keySet()) {
                items.add(new ImportItem(namespace, name, Kind.MACRO,
                        "import " + namespace + " (" + name + ")" + tag + "  [macro]",
                        "import " + namespace + " (" + name + ")"));
            }
        }
        Map<String, ?> signatures = (Map<String, ?>) mod.getExtra("signatures");
        if (signatures != null) {
            for (var e : signatures.entrySet()) {
                String name = e.getKey();
                items.add(new ImportItem(namespace, name, Kind.SIGNATURE,
                        "import " + namespace + " (" + name + ")" + tag + "  [signature]",
                        "import " + namespace + " (" + name + ")"));
                // Record the signature's required keys for cross-ref search.
                if (e.getValue() instanceof Set<?> keySet) {
                    Set<String> stringKeys = new LinkedHashSet<>();
                    for (Object k : keySet) stringKeys.add(k.toString());
                    sigKeyRefs.put(name, stringKeys);
                }
            }
        }
        Map<String, ?> keys = (Map<String, ?>) mod.getExtra("qualifiedKeys");
        if (keys != null) {
            for (String fqn : keys.keySet()) {
                // fqn has the form "@a.b.c.name" — short name is last segment
                String core = fqn.startsWith("@") ? fqn.substring(1) : fqn;
                int lastDot = core.lastIndexOf('.');
                String shortName = lastDot >= 0 ? core.substring(lastDot + 1) : core;
                String pkg = lastDot >= 0 ? core.substring(0, lastDot) : namespace;
                items.add(new ImportItem(namespace, fqn, Kind.KEY,
                        "import " + pkg + ".(" + shortName + ")" + tag + "  [key]",
                        "import " + pkg + ".(" + shortName + ")"));
            }
        }
    }

    /**
     * Check if an export name should appear in the import menu.
     * Filters out operator overloads, internal names, and method/constant keys.
     */
    private static boolean isImportableExport(String name) {
        if (name.isEmpty()) return false;
        // Skip operator overloads (+, -, *, /, etc.)
        char first = name.charAt(0);
        if (!Character.isLetter(first) && first != '_') return false;
        // Skip internal anonymous fields (_0, _1, etc.)
        if (name.startsWith("_")) return false;
        // Skip method/constant keys (TypeName.method — these come with the type)
        if (name.contains(".")) return false;
        return true;
    }

    // ---- Filtering ----

    private void refilter() {
        String q = query.toString().toLowerCase();
        if (q.isEmpty()) {
            // Show only top-level module imports when browsing (no query)
            filtered = allItems.stream()
                    .filter(item -> item.kind() == Kind.MODULE)
                    .toList();
            selectedIndex = 0;
            scrollOffset = 0;
            return;
        }

        // Prefix filter: `kind:text` narrows to one Kind. Leading @ biases to keys.
        Kind requiredKind = null;
        if (q.startsWith("key:"))       { requiredKind = Kind.KEY;       q = q.substring(4); }
        else if (q.startsWith("sig:"))  { requiredKind = Kind.SIGNATURE; q = q.substring(4); }
        else if (q.startsWith("macro:")){ requiredKind = Kind.MACRO;     q = q.substring(6); }
        else if (q.startsWith("type:")) { requiredKind = Kind.TYPE;      q = q.substring(5); }
        else if (q.startsWith("fn:"))   { requiredKind = Kind.FUNCTION;  q = q.substring(3); }
        else if (q.startsWith("mod:"))  { requiredKind = Kind.MODULE;    q = q.substring(4); }
        else if (q.startsWith("@"))     { requiredKind = Kind.KEY;       /* keep @ in the query for matching */ }

        filtered = new ArrayList<>();
        for (ImportItem item : allItems) {
            if (requiredKind != null && item.kind() != requiredKind) continue;
            boolean matchModule = item.moduleName().toLowerCase().contains(q);
            boolean matchExport = item.exportName() != null
                    && item.exportName().toLowerCase().contains(q);

            // Cross-reference: if this is a signature, also match against its
            // referenced keys — so typing a substring of a key name surfaces
            // every signature that includes it.
            boolean matchSigRef = false;
            if (item.kind() == Kind.SIGNATURE && item.exportName() != null) {
                Set<String> refs = sigKeyRefs.get(item.exportName());
                if (refs != null) {
                    for (String key : refs) {
                        if (key.toLowerCase().contains(q)) { matchSigRef = true; break; }
                    }
                }
            }

            if (matchModule || matchExport || matchSigRef) {
                filtered.add(item);
            }
        }
        selectedIndex = 0;
        scrollOffset = 0;
    }

    private void ensureVisible() {
        if (selectedIndex < scrollOffset) scrollOffset = selectedIndex;
        else if (selectedIndex >= scrollOffset + MAX_VISIBLE_ROWS)
            scrollOffset = selectedIndex - MAX_VISIBLE_ROWS + 1;
    }

    // ---- Insert import ----

    private void insertImport(ImportItem item) {
        TextBuffer buffer = window.getTextArea().getBuffer();
        String importLine = item.importStatement();

        // Check if already imported
        for (int r = 0; r < buffer.lineCount(); r++) {
            String line = buffer.getLine(r).trim();
            if (line.equals(importLine)) {
                window.flash("Already imported: " + importLine, false);
                return;
            }
        }

        // Find insertion point: after the last import line, or at line 0
        int insertRow = 0;
        for (int r = 0; r < buffer.lineCount(); r++) {
            String line = buffer.getLine(r).trim();
            if (line.startsWith("import ")) {
                insertRow = r + 1;
            } else if (!line.isEmpty() && !line.startsWith("--") && !line.startsWith("module ")
                    && !line.startsWith("version ") && !line.startsWith("require ")) {
                break; // past the header section
            }
        }

        // Insert the import line
        buffer.insertText(insertRow, 0, importLine + "\n");

        // Adjust cursor position (it shifted down by 1)
        int cursorRow = window.getTextArea().getCursorRow();
        if (cursorRow >= insertRow) {
            window.getTextArea().setCursorPosition(cursorRow + 1,
                    window.getTextArea().getCursorCol());
        }

        window.flash("Added: " + importLine, false);
    }

    private int rowAtY(double my) {
        if (listRowH <= 0) return -1;
        double rel = my - listTop;
        if (rel < 0) return -1;
        return (int) (rel / listRowH) + scrollOffset;
    }
}
