package spn.intellij;

import com.intellij.psi.tree.IElementType;
import spn.lang.TokenType;

import java.util.EnumMap;
import java.util.Map;

/**
 * Maps SPN TokenType enum values to IntelliJ IElementType instances.
 */
public final class SpnTokenTypes {

    private static final Map<TokenType, IElementType> MAP = new EnumMap<>(TokenType.class);

    static {
        for (TokenType tt : TokenType.values()) {
            MAP.put(tt, new IElementType(tt.name(), SpnLanguage.INSTANCE));
        }
    }

    public static IElementType get(TokenType type) {
        return MAP.get(type);
    }

    // Convenient constants for the highlighter
    public static final IElementType COMMENT    = get(TokenType.COMMENT);
    public static final IElementType KEYWORD    = get(TokenType.KEYWORD);
    public static final IElementType TYPE_NAME  = get(TokenType.TYPE_NAME);
    public static final IElementType SYMBOL     = get(TokenType.SYMBOL);
    public static final IElementType NUMBER     = get(TokenType.NUMBER);
    public static final IElementType STRING     = get(TokenType.STRING);
    public static final IElementType REGEX      = get(TokenType.REGEX);
    public static final IElementType OPERATOR   = get(TokenType.OPERATOR);
    public static final IElementType DELIMITER  = get(TokenType.DELIMITER);
    public static final IElementType PATTERN_KW = get(TokenType.PATTERN_KW);
    public static final IElementType IDENTIFIER = get(TokenType.IDENTIFIER);
    public static final IElementType WHITESPACE = get(TokenType.WHITESPACE);

    private SpnTokenTypes() {}
}
