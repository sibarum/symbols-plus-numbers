package spn.gui.lang;

import spn.lang.SpnLexer;
import spn.lang.Token;

import java.util.ArrayList;
import java.util.List;

/**
 * Per-line token cache with lazy re-lex on access.
 * Call the invalidation methods when the underlying TextBuffer mutates.
 */
public class HighlightCache {

    private final SpnLexer lexer = new SpnLexer();
    private final List<List<Token>> cache = new ArrayList<>();

    /** Get (or lazily compute) the token list for the given line. */
    public List<Token> getTokens(int row, String lineContent) {
        ensureSize(row + 1);
        List<Token> tokens = cache.get(row);
        if (tokens == null) {
            tokens = lexer.tokenizeLine(lineContent);
            cache.set(row, tokens);
        }
        return tokens;
    }

    public void invalidateLine(int row) {
        if (row >= 0 && row < cache.size()) cache.set(row, null);
    }

    public void insertLine(int row) {
        ensureSize(row);
        cache.add(row, null);
    }

    public void removeLine(int row) {
        if (row >= 0 && row < cache.size()) cache.remove(row);
    }

    public void invalidateAll() {
        cache.clear();
    }

    private void ensureSize(int minSize) {
        while (cache.size() < minSize) cache.add(null);
    }
}
