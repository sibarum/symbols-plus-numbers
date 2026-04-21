package spn.lang;

import spn.language.SpnModuleRegistry;
import spn.source.SourceRange;
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

    /** A parse error with location info (editor convention: 0-based line, 0-based col). */
    public record ParseError(int line, int col, String message) {}

    /**
     * A compile-time dispatch annotation for IDE display.
     *
     * <p>{@code callSite} is the range in the current file where the operator
     * or call appears. {@code targetRange} / {@code targetFile} point at the
     * declaration this call site was resolved to — so go-to-def lands on the
     * exact overload the parser chose, not the first name match. Both ranges
     * are in <b>editor coordinates</b> (0-based line, 0-based col); consumers
     * like the editor can use them directly.
     *
     * <p>{@code targetFile} is null when the dispatch resolved to a builtin
     * (Java-implemented) — the declaration has no source to jump to.
     */
    public record DispatchAnnotation(
            SourceRange callSite,
            String description,
            String targetFile,
            SourceRange targetRange) {}

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
            // Include macro expansion stack in the error message
            String msg = pe.getMessage();
            if (pe.getMacroStack() != null && !pe.getMacroStack().isEmpty()) {
                var sb = new StringBuilder(msg);
                for (String frame : pe.getMacroStack()) {
                    sb.append(" [in macro ").append(frame).append("]");
                }
                msg = sb.toString();
            }
            errors.add(new ParseError(errorLine, errorCol, msg));

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
        // still have valid dispatch info. Field-access sites are merged in so
        // the editor can go-to-def on `state.counter`-style member names.
        lastDispatches = extractDispatches(parser.getResolver(), parser.getTypeGraph(),
                parser.getFieldAccessSites());

        // Retain parser artifacts so the IDE can answer go-to-def queries
        // without re-parsing. A fresh parser is built on every invocation, so
        // holding its TypeGraph + builtin names is the simplest path.
        lastTypeGraph = parser.getTypeGraph();
        lastBuiltinNames = Set.copyOf(parser.getBuiltinRegistry().keySet());

        return new Result(errors, lastDispatches, true, spans.size(), invalidated);
    }

    /** Cached dispatch annotations from the last successful parse. */
    private List<DispatchAnnotation> lastDispatches = List.of();

    /** TypeGraph from the last parse. Null before any parse completes. */
    private TypeGraph lastTypeGraph;

    /** Names of every builtin available after the last parse's imports. */
    private Set<String> lastBuiltinNames = Set.of();

    /** Get the TypeGraph from the most recent parse (null before any parse). */
    public TypeGraph getTypeGraph() {
        return lastTypeGraph;
    }

    /** True if the given name is a stdlib builtin (Java-implemented) in the
     *  current parse context. Only meaningful after at least one parse. */
    public boolean isBuiltin(String name) {
        return lastBuiltinNames.contains(name);
    }

    /** Extract dispatch annotations from a resolver into position-indexed records.
     *  Cross-references each resolved CallTarget against the TypeGraph to
     *  record the exact declaration site so the IDE can jump to the overload
     *  chosen at compile time. Positions are converted to editor coordinates
     *  (0-based line, 0-based col) here so downstream consumers don't have to.
     *  Field-access sites (state.counter) are merged in with their target set
     *  to the FIELD node's declaration position. */
    private List<DispatchAnnotation> extractDispatches(TypeResolver resolver, TypeGraph typeGraph,
                                                        List<SpnParser.FieldAccessSite> fieldSites) {
        List<DispatchAnnotation> result = new ArrayList<>();

        if (resolver != null) {
            for (var entry : resolver.allDispatches().entrySet()) {
                var node = entry.getKey();
                var record = entry.getValue();
                if (!node.hasSourcePosition()) continue;

                // AST nodes use parser convention (1-based line, 0-based col);
                // SourceRange.toEditorCoords() is the single conversion point.
                SourceRange callRange = node.hasSourceSpan()
                        ? new SourceRange(node.getSourceLine(), node.getSourceCol(),
                                          node.getSourceEndLine(), node.getSourceEndCol())
                        : SourceRange.ofToken(node.getSourceLine(), node.getSourceCol(),
                                              node.getSourceCol() + 1);
                SourceRange callSite = callRange.toEditorCoords();

                // Build description: "+(Rational, Rational)" or "+(Rational, Rational) [int→Rational]"
                String desc = record.resolvedTarget();
                if (record.promotionDetail() != null) {
                    desc += " [" + record.promotionDetail() + "]";
                }

                // Cross-reference CallTarget → declaration site. Builtins have
                // no TypeGraph node (no source); they get null target fields.
                String targetFile = null;
                SourceRange targetRange = null;
                TypeGraph.Node decl = typeGraph != null
                        ? typeGraph.byCallTarget(record.callTarget()) : null;
                if (decl != null && decl.file() != null && decl.nameRange().isKnown()) {
                    targetFile = decl.file();
                    targetRange = decl.nameRange().toEditorCoords();
                }
                result.add(new DispatchAnnotation(callSite, desc, targetFile, targetRange));
            }
        }

        // Field-access annotations: target is the FIELD node's declaration.
        if (fieldSites != null) {
            for (SpnParser.FieldAccessSite fs : fieldSites) {
                result.add(new DispatchAnnotation(
                        fs.accessRange().toEditorCoords(),
                        fs.description(),
                        fs.targetFile(),
                        fs.targetRange().toEditorCoords()));
            }
        }

        // Sort by line then col for binary search in the GUI
        result.sort((a, b) -> a.callSite().startLine() != b.callSite().startLine()
                ? Integer.compare(a.callSite().startLine(), b.callSite().startLine())
                : Integer.compare(a.callSite().startCol(), b.callSite().startCol()));
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
