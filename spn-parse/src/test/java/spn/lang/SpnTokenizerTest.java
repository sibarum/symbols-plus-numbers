package spn.lang;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SpnTokenizerTest {

    @Test
    void skipsWhitespaceAndComments() {
        var t = new SpnTokenizer("let x = 42 -- a comment");
        assertEquals("let", t.advance().text());
        assertEquals("x", t.advance().text());
        assertEquals("=", t.advance().text());
        assertEquals("42", t.advance().text());
        assertFalse(t.hasMore(), "Comment and whitespace should be stripped");
    }

    @Test
    void tokenizesSymbolsAndStrings() {
        var t = new SpnTokenizer(":red \"hello\"");
        SpnParseToken sym = t.advance();
        assertEquals(TokenType.SYMBOL, sym.type());
        assertEquals(":red", sym.text());

        SpnParseToken str = t.advance();
        assertEquals(TokenType.STRING, str.type());
        assertEquals("\"hello\"", str.text());
    }

    @Test
    void tokenizesOperators() {
        var t = new SpnTokenizer("a + b == c ++ d");
        assertEquals("a", t.advance().text());
        assertEquals("+", t.advance().text());
        assertEquals("b", t.advance().text());
        assertEquals("==", t.advance().text());
        assertEquals("c", t.advance().text());
        assertEquals("++", t.advance().text());
        assertEquals("d", t.advance().text());
    }

    @Test
    void tokenizesMultipleLines() {
        var t = new SpnTokenizer("let x = 1\nlet y = 2");
        assertEquals("let", t.advance().text());
        assertEquals("x", t.advance().text());
        assertEquals("=", t.advance().text());

        SpnParseToken one = t.advance();
        assertEquals("1", one.text());
        assertEquals(1, one.line());

        assertEquals("let", t.advance().text());
        assertEquals("y", t.advance().text());
        assertEquals("=", t.advance().text());

        SpnParseToken two = t.advance();
        assertEquals("2", two.text());
        assertEquals(2, two.line());
    }

    @Test
    void matchAndExpect() {
        var t = new SpnTokenizer("let x = 42");
        assertTrue(t.match("let"));
        assertFalse(t.match("let")); // already consumed
        assertEquals("x", t.expect("x").text());
        t.expect("=");
        assertThrows(SpnParseException.class, () -> t.expect("wrong"));
    }

    @Test
    void peekAhead() {
        var t = new SpnTokenizer("a b c");
        assertEquals("a", t.peek().text());
        assertEquals("b", t.peek(1).text());
        assertEquals("c", t.peek(2).text());
        assertNull(t.peek(3));
    }

    @Test
    void markAndReset() {
        var t = new SpnTokenizer("a b c");
        int mark = t.mark();
        t.advance();
        t.advance();
        assertEquals("c", t.peek().text());
        t.reset(mark);
        assertEquals("a", t.peek().text());
    }

    @Test
    void tokenizesDottedSymbols() {
        var t = new SpnTokenizer(":spn.collections.sorted");
        SpnParseToken sym = t.advance();
        assertEquals(TokenType.SYMBOL, sym.type());
        assertEquals(":spn.collections.sorted", sym.text());
        assertFalse(t.hasMore());
    }

    @Test
    void dottedSymbolDoesNotConsumeTrailingDot() {
        var t = new SpnTokenizer(":foo.");
        SpnParseToken sym = t.advance();
        assertEquals(TokenType.SYMBOL, sym.type());
        assertEquals(":foo", sym.text());
        // The trailing dot is a separate token
        assertTrue(t.hasMore());
    }

    @Test
    void tokenizesCollectionSyntax() {
        var t = new SpnTokenizer("[1, :red, \"hello\"]");
        assertEquals("[", t.advance().text());
        assertEquals("1", t.advance().text());
        assertEquals(",", t.advance().text());
        assertEquals(":red", t.advance().text());
        assertEquals(",", t.advance().text());
        assertEquals("\"hello\"", t.advance().text());
        assertEquals("]", t.advance().text());
    }

    @Test
    void tokenizesLambdaSyntax() {
        var t = new SpnTokenizer("(x, y) { x + y }");
        assertEquals("(", t.advance().text());
        assertEquals("x", t.advance().text());
        assertEquals(",", t.advance().text());
        assertEquals("y", t.advance().text());
        assertEquals(")", t.advance().text());
        assertEquals("{", t.advance().text());
        assertEquals("x", t.advance().text());
        assertEquals("+", t.advance().text());
        assertEquals("y", t.advance().text());
        assertEquals("}", t.advance().text());
    }
}
