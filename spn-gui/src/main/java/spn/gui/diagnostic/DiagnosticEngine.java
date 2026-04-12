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

    private final SpnSymbolTable symbolTable;
    private final SpnModuleRegistry registry;

    public DiagnosticEngine(DiagnosticOverlay overlay) {
        this.overlay = overlay;

        // Set up a module registry matching the runtime environment
        this.symbolTable = new SpnSymbolTable();
        this.registry = new SpnModuleRegistry();
        spn.stdlib.gen.StdlibModuleLoader.registerAll(registry);
        spn.canvas.CanvasBuiltins.registerModule(registry);
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

        // Convert parse errors to diagnostic marks
        List<DiagnosticMark> newMarks = new ArrayList<>();
        for (IncrementalParser.ParseError err : result.errors()) {
            newMarks.add(new DiagnosticMark(new Diagnostic(
                    err.line(), err.col(), -1, err.message(), Diagnostic.Severity.ERROR)));
        }
        overlay.setMarks(newMarks);
    }

    /** Get the incremental parser for inspection (e.g., cached spans for diffing). */
    public IncrementalParser getIncrementalParser() {
        return incrementalParser;
    }
}
