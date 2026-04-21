package spn.gui.diagnostic;

import spn.lang.ClasspathModuleLoader;
import spn.lang.FilesystemModuleLoader;
import spn.lang.IncrementalParser;
import spn.language.SpnModuleRegistry;
import spn.type.SpnSymbolTable;

import java.nio.file.Path;

import java.util.ArrayList;
import java.util.List;

/**
 * Debounced diagnostic engine using incremental parsing.
 *
 * <p>Uses {@link IncrementalParser} to avoid re-parsing unchanged declarations.
 * When the source is edited, only the affected declaration spans are re-parsed.
 * If nothing changed, the cached result is returned immediately.
 *
 * <p>All methods are called from the main (render/event) thread.
 */
public class DiagnosticEngine {

    private static final double DEBOUNCE_SECONDS = 0.6;

    private final DiagnosticOverlay overlay;
    private final IncrementalParser incrementalParser;

    private double lastEditTime = -1;
    private boolean dirty;

    /** Dispatch annotations from the last successful parse. */
    private java.util.List<spn.lang.IncrementalParser.DispatchAnnotation> dispatches = java.util.List.of();

    /** Type-reference annotations from the last successful parse. */
    private java.util.List<spn.lang.IncrementalParser.TypeRefAnnotation> typeRefs = java.util.List.of();

    private final SpnSymbolTable symbolTable;
    private final SpnModuleRegistry registry;

    public DiagnosticEngine(DiagnosticOverlay overlay) {
        this.overlay = overlay;

        // Set up a module registry matching the runtime environment
        this.symbolTable = new SpnSymbolTable();
        this.registry = new SpnModuleRegistry();
        spn.stdlib.gen.StdlibModuleLoader.registerAll(registry);
        spn.canvas.CanvasBuiltins.registerModule(registry);
        spn.canvasgui.spn.CanvasGuiBuiltins.registerModule(registry);
        registry.addLoader(new ClasspathModuleLoader(null, symbolTable));

        this.incrementalParser = new IncrementalParser(symbolTable, registry);
    }

    /** Configure filesystem module loading for local module imports. */
    public void setModuleRoot(Path root, String namespace) {
        registry.addLoader(new FilesystemModuleLoader(root, namespace, null, symbolTable));
    }

    /** Called whenever the source is modified. Resets the debounce timer. */
    public void notifyEdit(double now) {
        lastEditTime = now;
        dirty = true;
    }

    /** Called on line modification — marks that line's diagnostics as stale. */
    public void notifyLineModified(int row) {
        overlay.markLineStale(row);
    }

    /**
     * Called every frame (from render). Checks if debounce has elapsed
     * and triggers a re-parse if needed.
     */
    public void update(double now, String source, String fileName) {
        if (!dirty) return;
        if (now - lastEditTime < DEBOUNCE_SECONDS) return;

        dirty = false;

        IncrementalParser.Result result = incrementalParser.parse(source, fileName);

        // Cache dispatch annotations for IDE display
        if (result.dispatches() != null && !result.dispatches().isEmpty()) {
            dispatches = result.dispatches();
        }

        // Cache type-reference annotations for IDE display (go-to-def on types)
        if (result.typeRefs() != null && !result.typeRefs().isEmpty()) {
            typeRefs = result.typeRefs();
        }

        // Convert parse errors to diagnostic marks
        List<DiagnosticMark> newMarks = new ArrayList<>();
        for (IncrementalParser.ParseError err : result.errors()) {
            newMarks.add(new DiagnosticMark(new Diagnostic(
                    err.line(), err.col(), -1, err.message(), Diagnostic.Severity.ERROR)));
        }
        overlay.setMarks(newMarks);
    }

    /**
     * Evict a specific module from the registry cache. Called when a file is
     * saved so that other tabs importing it pick up the fresh version.
     */
    public void invalidateModule(String namespace) {
        registry.invalidate(namespace);
    }

    /**
     * Nuclear option: clear all caches (incremental parser spans, module registry
     * dynamic modules) and force a full re-parse on the next update cycle.
     */
    public void invalidateAll() {
        incrementalParser.invalidateAll();
        // Snapshot which modules are native (pre-registered at startup)
        var nativeNamespaces = new java.util.HashSet<String>();
        for (var entry : registry.allModules().entrySet()) {
            // Native modules don't come from filesystem loaders — they were
            // registered before any loader was added. We keep stdlib and canvas.
            nativeNamespaces.add(entry.getKey());
        }
        // Actually we want to clear ONLY dynamically-loaded modules.
        // But we don't have a clean way to tell which are native vs dynamic.
        // Simplest: just clear ALL and re-register the known natives.
        // For now: mark dirty so the next update triggers a full re-parse.
        dirty = true;
        lastEditTime = 0; // bypass debounce — re-parse immediately
    }

    /**
     * Collect all dispatch annotations on the given line, formatted as a
     * HUD-ready string. Returns null if no dispatches on this line.
     * Example: "-(Rational, Rational)  /(Rational, Rational)  +(Rational, Rational)"
     */
    public String dispatchesOnLine(int line) {
        var sb = new StringBuilder();
        for (var d : dispatches) {
            var cs = d.callSite();
            if (cs.startLine() < line && cs.endLine() < line) continue;
            if (cs.startLine() > line) break;
            if (!sb.isEmpty()) sb.append("  ");
            sb.append(d.description().replace('|', '/'));
        }
        return sb.isEmpty() ? null : sb.toString();
    }

    /**
     * Find the dispatch annotation whose span contains the cursor position.
     * Returns the resolved target string or null.
     */
    public String dispatchAtCursor(int line, int col) {
        var d = dispatchAnnotationAt(line, col);
        return d != null ? d.description() : null;
    }

    /** The full dispatch annotation at a position (or null). Carries the
     *  resolved call site's target range, so go-to-def can jump to the exact
     *  overload chosen at compile time. (line, col) are in editor coordinates. */
    public spn.lang.IncrementalParser.DispatchAnnotation dispatchAnnotationAt(int line, int col) {
        for (var d : dispatches) {
            var cs = d.callSite();
            if (cs.startLine() > line) break;
            boolean afterStart = cs.startLine() < line
                    || (cs.startLine() == line && cs.startCol() <= col);
            boolean beforeEnd = cs.endLine() > line
                    || (cs.endLine() == line && cs.endCol() >= col);
            if (afterStart && beforeEnd) return d;
        }
        return null;
    }

    /** The type-reference annotation whose use-site range contains the given
     *  position (or null). Used by go-to-def to navigate from a type name in a
     *  signature or type ascription to its declaration or import statement.
     *  (line, col) are in editor coordinates. */
    public spn.lang.IncrementalParser.TypeRefAnnotation typeReferenceAt(int line, int col) {
        for (var t : typeRefs) {
            var us = t.useSite();
            if (us.startLine() > line) break;
            boolean afterStart = us.startLine() < line
                    || (us.startLine() == line && us.startCol() <= col);
            boolean beforeEnd = us.endLine() > line
                    || (us.endLine() == line && us.endCol() >= col);
            if (afterStart && beforeEnd) return t;
        }
        return null;
    }

    /** Get the incremental parser for inspection (e.g., cached spans for diffing). */
    public IncrementalParser getIncrementalParser() {
        return incrementalParser;
    }

    /** The TypeGraph from the most recent parse, or null before any parse. */
    public spn.lang.TypeGraph getTypeGraph() {
        return incrementalParser.getTypeGraph();
    }

    /** True if the given name is a stdlib builtin imported into the last parse. */
    public boolean isBuiltin(String name) {
        return incrementalParser.isBuiltin(name);
    }
}
