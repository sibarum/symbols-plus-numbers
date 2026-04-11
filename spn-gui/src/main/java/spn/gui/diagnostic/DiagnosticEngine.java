package spn.gui.diagnostic;

import spn.lang.ClasspathModuleLoader;
import spn.lang.SpnParseException;
import spn.lang.SpnParser;
import spn.language.SpnModuleRegistry;
import spn.type.SpnSymbolTable;

import java.util.ArrayList;
import java.util.List;

/**
 * Debounced background diagnostic engine. Re-parses the source after a
 * quiet period (no edits) and reconciles the results with the overlay.
 *
 * <p>All methods are called from the main (render/event) thread — no
 * multi-threading is needed since parse is fast enough for interactive use.
 */
public class DiagnosticEngine {

    private static final double DEBOUNCE_SECONDS = 0.6;

    private final DiagnosticOverlay overlay;
    private final SpnSymbolTable symbolTable = new SpnSymbolTable();
    private final SpnModuleRegistry registry;

    private double lastEditTime = -1;
    private boolean dirty;
    private String lastParsedSource = "";

    public DiagnosticEngine(DiagnosticOverlay overlay) {
        this.overlay = overlay;

        // Set up a module registry matching the runtime environment
        // so imports resolve during diagnostic parsing
        this.registry = new SpnModuleRegistry();
        spn.stdlib.gen.StdlibModuleLoader.registerAll(registry);
        spn.canvas.CanvasBuiltins.registerModule(registry);
        registry.addLoader(new ClasspathModuleLoader(null, symbolTable));
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
     *
     * @param now       current time (glfwGetTime)
     * @param source    current editor source text
     * @param fileName  source file name for error messages
     */
    public void update(double now, String source, String fileName) {
        if (!dirty) return;
        if (now - lastEditTime < DEBOUNCE_SECONDS) return;

        dirty = false;
        lastParsedSource = source;

        List<Diagnostic> diagnostics = parse(source, fileName);
        reconcile(diagnostics);
    }

    /** Parse the source and collect diagnostics. */
    private List<Diagnostic> parse(String source, String fileName) {
        List<Diagnostic> result = new ArrayList<>();
        if (source.isBlank()) return result;

        int lastRow = Math.max(0, (int) source.lines().count() - 1);
        try {
            SpnParser parser = new SpnParser(source, fileName, null, symbolTable, registry);
            parser.parse();
        } catch (SpnParseException pe) {
            int row = pe.getLine() > 0 ? pe.getLine() - 1 : lastRow;  // convert 1-based to 0-based
            int col = pe.getCol() > 0 ? pe.getCol() - 1 : 0;
            result.add(new Diagnostic(row, col, -1, pe.getMessage(), Diagnostic.Severity.ERROR));
        } catch (Exception e) {
            // Generic error — put on last line as best guess
            String msg = e.getMessage();
            if (msg == null) msg = e.getClass().getSimpleName();
            result.add(new Diagnostic(lastRow, 0, -1, msg, Diagnostic.Severity.ERROR));
        }
        return result;
    }

    /** Reconcile new diagnostics with existing marks. */
    private void reconcile(List<Diagnostic> newDiags) {
        List<DiagnosticMark> newMarks = new ArrayList<>();
        for (Diagnostic d : newDiags) {
            newMarks.add(new DiagnosticMark(d));
        }
        overlay.setMarks(newMarks);
    }
}
