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

    /** An importable item: either a whole module or a specific export from a module. */
    record ImportItem(String moduleName, String exportName, String displayText, String importStatement) {}

    private final EditorWindow window;
    private final SdfFontRenderer font;
    private final List<ImportItem> allItems;

    private final StringBuilder query = new StringBuilder();
    private int cursorPos;
    private int selectedIndex;
    private int scrollOffset;
    private List<ImportItem> filtered;

    ImportMode(EditorWindow window) {
        this.window = window;
        this.font = window.getFont();
        this.allItems = buildImportIndex();
        refilter(); // start with top-level modules only
    }

    @Override
    public boolean onKey(int key, int scancode, int action, int mods) {
        if (action != GLFW_PRESS && action != GLFW_REPEAT) return true;
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
        scrollOffset = Math.max(0, Math.min(scrollOffset - (int) yoff * 3,
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

            // Display text (the import statement)
            font.drawText("  " + item.displayText(), paletteX + 12f + modW, itemY, FONT_SCALE,
                    NAME_R, NAME_G, NAME_B);
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
        return "Type to search | Enter Import | Esc Cancel";
    }

    // ---- Import index ----

    private List<ImportItem> buildImportIndex() {
        List<ImportItem> items = new ArrayList<>();

        // Build stdlib modules with their exports
        var byModule = new LinkedHashMap<String, List<String>>();
        for (var entry : spn.stdlib.gen.SpnStdlibRegistry.allBuiltins()) {
            byModule.computeIfAbsent(entry.module(), k -> new ArrayList<>()).add(entry.name());
        }

        // Add Canvas builtins by building a temporary module
        {
            var canvasRegistry = new spn.language.SpnModuleRegistry();
            spn.canvas.CanvasBuiltins.registerModule(canvasRegistry);
            canvasRegistry.lookup("Canvas").ifPresent(mod -> {
                var exports = byModule.computeIfAbsent("Canvas", k -> new ArrayList<>());
                exports.addAll(mod.allExportedNames());
            });
        }

        for (var entry : byModule.entrySet()) {
            String mod = entry.getKey();
            List<String> exports = entry.getValue();

            // Whole module import
            items.add(new ImportItem(mod, null,
                    "import " + mod,
                    "import " + mod));

            // Individual export imports
            for (String exp : exports) {
                items.add(new ImportItem(mod, exp,
                        "import " + mod + " (" + exp + ")",
                        "import " + mod + " (" + exp + ")"));
            }
        }

        // Discover .spn script modules on the classpath
        try {
            var symbolTable = new spn.type.SpnSymbolTable();
            var loader = new spn.lang.ClasspathModuleLoader(null, symbolTable);
            var registry = new spn.language.SpnModuleRegistry();
            spn.stdlib.gen.StdlibModuleLoader.registerAll(registry);
            spn.canvas.CanvasBuiltins.registerModule(registry);
            registry.addLoader(loader);

            for (String namespace : loader.discoverModules()) {
                // Skip native modules already shown above
                if (byModule.containsKey(namespace)) continue;
                try {
                    registry.resolve(namespace).ifPresent(mod -> {
                        var exports = mod.allExportedNames();
                        // Whole module import
                        items.add(new ImportItem(namespace, null,
                                "import " + namespace,
                                "import " + namespace));
                        // Individual export imports
                        for (String exp : exports) {
                            items.add(new ImportItem(namespace, exp,
                                    "import " + namespace + " (" + exp + ")",
                                    "import " + namespace + " (" + exp + ")"));
                        }
                    });
                } catch (Exception e) {
                    // Skip modules that fail to parse — still show the module-level import
                    items.add(new ImportItem(namespace, null,
                            "import " + namespace,
                            "import " + namespace));
                }
            }
        } catch (Exception e) {
            // Best-effort: continue with what we have
        }

        // Add module context files as importable namespaces
        ModuleContext ctx = window.getModuleContext();
        if (ctx != null) {
            for (ModuleContext.ModuleFile f : ctx.getFiles()) {
                // Derive a short name from the relative path
                String rel = f.relativePath();
                if (rel.endsWith(".spn")) rel = rel.substring(0, rel.length() - 4);
                String namespace = rel.replace('/', '.');
                String fullNs = ctx.getNamespace() + "." + namespace;
                items.add(new ImportItem(fullNs, null,
                        "import " + fullNs,
                        "import " + fullNs));
            }
        }

        items.sort(Comparator.comparing(ImportItem::displayText));
        return items;
    }

    // ---- Filtering ----

    private void refilter() {
        String q = query.toString().toLowerCase();
        if (q.isEmpty()) {
            // Show only top-level module imports when browsing (no query)
            filtered = allItems.stream()
                    .filter(item -> item.exportName() == null)
                    .toList();
            selectedIndex = 0;
            scrollOffset = 0;
            return;
        }

        filtered = new ArrayList<>();
        for (ImportItem item : allItems) {
            boolean matchModule = item.moduleName().toLowerCase().contains(q);
            boolean matchExport = item.exportName() != null
                    && item.exportName().toLowerCase().contains(q);
            if (matchModule || matchExport) {
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
