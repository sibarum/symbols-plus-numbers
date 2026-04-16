package spn.lang;

import spn.language.SpnModuleRegistry;
import spn.type.SpnSymbolTable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Incremental parsing wrapper. Uses DeclarationScanner + DeclarationCache to
 * avoid re-parsing unchanged declarations.
 *
 * <p>Strategy:
 * <ol>
 *   <li>Scan source into declaration spans</li>
 *   <li>Compare against cache — identify changed spans</li>
 *   <li>Parse the full source but collect diagnostics only from changed regions</li>
 *   <li>Update the cache with results</li>
 * </ol>
 *
 * <p>Note: SPN declarations can depend on each other (imports, type references),
 * so we still parse the full source for correctness. The optimization is that
 * we can skip diagnostic collection for unchanged spans, and we can quickly
 * determine whether a re-parse is needed at all (if no spans changed, skip entirely).
 */
public final class IncrementalParser {

    private final DeclarationCache cache = new DeclarationCache();
    private final SpnSymbolTable symbolTable;
    private final SpnModuleRegistry registry;

    public IncrementalParser(SpnSymbolTable symbolTable, SpnModuleRegistry registry) {
        this.symbolTable = symbolTable;
        this.registry = registry;
    }

    /** A parse error with location info. */
    public record ParseError(int line, int col, String message) {}

    /** A compile-time dispatch annotation for IDE display. */
    public record DispatchAnnotation(int line, int col, int endLine, int endCol, String description) {}

    /** Result of an incremental parse. */
    public record Result(
            List<ParseError> errors,
            List<DispatchAnnotation> dispatches, // resolved overloads for IDE display
            boolean fullReparse,   // true if a full reparse was needed
            int totalSpans,        // total declaration spans
            int invalidatedSpans   // spans that needed re-parsing
    ) {}

    /**
     * Parse the source incrementally. Returns diagnostics for any errors found.
     *
     * @param source   the full source text
     * @param fileName source file name for error messages
     * @return parse result with diagnostics and cache statistics
     */
    public Result parse(String source, String fileName) {
        if (source.isBlank()) {
            cache.invalidateAll();
            return new Result(List.of(), List.of(), false, 0, 0);
        }

        // Scan into declaration spans
        List<DeclarationScanner.Span> spans = DeclarationScanner.scan(source);

        // Update cache — compare against previous spans
        List<DeclarationCache.CachedSpan> cachedSpans = cache.update(spans);

        // Count invalidated spans
        int invalidated = 0;
        for (DeclarationCache.CachedSpan cs : cachedSpans) {
            if (!cs.valid()) invalidated++;
        }

        // If nothing changed, return cached errors with last dispatch data
        if (invalidated == 0) {
            return new Result(collectCachedErrors(cachedSpans, spans),
                    lastDispatches, false, spans.size(), 0);
        }

        // Something changed — do a full parse with error recovery.
        // The parser now collects ALL errors instead of throwing on the first one.
        List<ParseError> errors = new ArrayList<>();

        SpnParser parser = new SpnParser(source, fileName, null, symbolTable, registry);

        // Invalidate TypeGraph from the first changed span onward
        int firstChangedLine = 0;
        for (int i = 0; i < cachedSpans.size(); i++) {
            if (!cachedSpans.get(i).valid()) {
                firstChangedLine = i < spans.size() ? spans.get(i).startLine() : 0;
                break;
            }
        }
        parser.getTypeGraph().invalidateFrom(fileName, firstChangedLine);

        try {
            parser.parse();
        } catch (SpnParseException ignored) {
            // parse() throws the first error for execution callers, but we
            // read ALL collected errors below via getErrors().
        } catch (Exception e) {
            // Truly catastrophic — record and move on
            int lastLine = Math.max(0, (int) source.lines().count() - 1);
            String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            errors.add(new ParseError(lastLine, 0, msg));
        }

        // Collect all errors from recovery-mode parsing
        Set<Integer> errorSpanIndices = new HashSet<>();
        for (SpnParseException pe : parser.getErrors()) {
            int errorLine = pe.getLine() > 0 ? pe.getLine() - 1 : Math.max(0, spans.size() - 1);
            int errorCol = pe.getCol() > 0 ? pe.getCol() - 1 : 0;
            errors.add(new ParseError(errorLine, errorCol, pe.getMessage()));

            int errorSpanIndex = findSpanForLine(spans, errorLine);
            if (errorSpanIndex >= 0) {
                cache.markError(errorSpanIndex, pe.getMessage());
                errorSpanIndices.add(errorSpanIndex);
            }
        }

        // Mark non-error spans as valid
        for (int i = 0; i < cachedSpans.size(); i++) {
            if (!errorSpanIndices.contains(i)) cache.markValid(i);
        }

        // Extract dispatch annotations — declarations parsed before any error
        // still have valid dispatch info.
        lastDispatches = extractDispatches(parser.getResolver());

        return new Result(errors, lastDispatches, true, spans.size(), invalidated);
    }

    /** Cached dispatch annotations from the last successful parse. */
    private List<DispatchAnnotation> lastDispatches = List.of();

    /** Extract dispatch annotations from a resolver into position-indexed records. */
    private List<DispatchAnnotation> extractDispatches(TypeResolver resolver) {
        if (resolver == null) return List.of();
        var dispatches = resolver.allDispatches();
        if (dispatches.isEmpty()) return List.of();
        List<DispatchAnnotation> result = new ArrayList<>();
        for (var entry : dispatches.entrySet()) {
            var node = entry.getKey();
            var record = entry.getValue();
            if (node.hasSourcePosition()) {
                // Convert from 1-based parser lines to 0-based editor lines
                int line = node.getSourceLine() - 1;
                int col = node.getSourceCol();
                int endLine = node.hasSourceSpan() ? node.getSourceEndLine() - 1 : line;
                int endCol = node.hasSourceSpan() ? node.getSourceEndCol() : col + 1;
                result.add(new DispatchAnnotation(
                        line, col, endLine, endCol,
                        record.resolvedTarget()));
            }
        }
        // Sort by line then col for binary search in the GUI
        result.sort((a, b) -> a.line() != b.line()
                ? Integer.compare(a.line(), b.line())
                : Integer.compare(a.col(), b.col()));
        return List.copyOf(result);
    }

    /** Get the current declaration spans (for diff/inspection). */
    public List<DeclarationCache.CachedSpan> getCachedSpans() {
        return cache.entries();
    }

    /** Force full invalidation (e.g., on file reload). */
    public void invalidateAll() {
        cache.invalidateAll();
    }

    /** Collect errors from cached spans (when no re-parse was needed). */
    private List<ParseError> collectCachedErrors(List<DeclarationCache.CachedSpan> cachedSpans,
                                                  List<DeclarationScanner.Span> spans) {
        List<ParseError> errors = new ArrayList<>();
        for (int i = 0; i < cachedSpans.size(); i++) {
            DeclarationCache.CachedSpan cs = cachedSpans.get(i);
            if (cs.error() != null) {
                DeclarationScanner.Span span = spans.get(i);
                errors.add(new ParseError(span.startLine(), 0, cs.error()));
            }
        }
        return errors;
    }

    /** Find which span index contains the given 0-based line number. */
    private int findSpanForLine(List<DeclarationScanner.Span> spans, int line) {
        for (int i = 0; i < spans.size(); i++) {
            DeclarationScanner.Span s = spans.get(i);
            if (line >= s.startLine() && line < s.endLine()) return i;
        }
        return spans.isEmpty() ? -1 : spans.size() - 1;
    }
}
