package spn.lang;

import java.util.ArrayList;
import java.util.List;

/**
 * Caches per-declaration parse results for incremental re-parsing.
 *
 * <p>On each parse cycle:
 * <ol>
 *   <li>Scan the new source into spans (DeclarationScanner)</li>
 *   <li>Compare against cached spans — any span whose source text changed is invalidated</li>
 *   <li>Only invalidated spans need re-parsing</li>
 * </ol>
 *
 * <p>A span is considered unchanged if its kind + source text are identical.
 * Position shifts (from insertions/deletions above) don't invalidate a span
 * as long as its content is the same.
 */
public final class DeclarationCache {

    /** Cached result for one declaration span. */
    public record CachedSpan(
            DeclarationScanner.Span span,
            boolean valid,     // true = parse result is usable, false = needs re-parse
            String error       // parse error message, or null if clean
    ) {}

    private List<CachedSpan> entries = new ArrayList<>();

    /**
     * Update the cache with new spans from the current source.
     * Returns the list of cached spans with validity flags.
     * Unchanged spans retain valid=true; changed/new spans get valid=false.
     */
    public List<CachedSpan> update(List<DeclarationScanner.Span> newSpans) {
        List<CachedSpan> newEntries = new ArrayList<>();

        for (int i = 0; i < newSpans.size(); i++) {
            DeclarationScanner.Span newSpan = newSpans.get(i);

            // Try to find a matching cached entry by content
            CachedSpan matched = findMatch(newSpan);
            if (matched != null && matched.valid()) {
                // Content unchanged — reuse cache
                newEntries.add(new CachedSpan(newSpan, true, matched.error()));
            } else {
                // New or changed — needs re-parse
                newEntries.add(new CachedSpan(newSpan, false, null));
            }
        }

        this.entries = newEntries;
        return newEntries;
    }

    /** Mark a span as successfully parsed (no error). */
    public void markValid(int index) {
        if (index >= 0 && index < entries.size()) {
            CachedSpan old = entries.get(index);
            entries.set(index, new CachedSpan(old.span(), true, null));
        }
    }

    /** Mark a span as having a parse error. */
    public void markError(int index, String error) {
        if (index >= 0 && index < entries.size()) {
            CachedSpan old = entries.get(index);
            entries.set(index, new CachedSpan(old.span(), true, error));
        }
    }

    /** Get current entries. */
    public List<CachedSpan> entries() {
        return entries;
    }

    /** Number of cached spans. */
    public int size() { return entries.size(); }

    /** Check if any spans need re-parsing. */
    public boolean hasInvalidEntries() {
        return entries.stream().anyMatch(e -> !e.valid());
    }

    /** Clear all cached entries. */
    public void invalidateAll() {
        entries.clear();
    }

    /**
     * Find a cached entry whose source content matches the given span.
     * Uses content equality (kind + source text), not position.
     */
    private CachedSpan findMatch(DeclarationScanner.Span span) {
        for (CachedSpan entry : entries) {
            if (entry.span().contentEquals(span)) {
                return entry;
            }
        }
        return null;
    }
}
