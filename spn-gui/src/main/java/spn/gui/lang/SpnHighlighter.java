package spn.gui.lang;

import spn.lang.SpnLexer;
import spn.lang.Token;
import spn.lang.TokenType;
import spn.stdui.highlight.Highlighter;
import spn.stdui.render.ColorSpan;

import java.util.List;

/**
 * SPN language-specific {@link Highlighter} implementation.
 * Wraps {@link SpnLexer} to produce {@link ColorSpan}s from SPN tokens.
 */
public class SpnHighlighter implements Highlighter {

    private final SpnLexer lexer = new SpnLexer();

    @Override
    public ColorSpan[] highlight(String line) {
        List<Token> tokens = lexer.tokenizeLine(line);
        return tokens.stream()
                .filter(t -> t.type() != TokenType.WHITESPACE)
                .map(t -> new ColorSpan(t.startCol(), t.endCol(),
                        t.type().r, t.type().g, t.type().b))
                .toArray(ColorSpan[]::new);
    }
}
