package spn.intellij;

import com.intellij.lexer.Lexer;
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors;
import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase;
import com.intellij.psi.tree.IElementType;

import static com.intellij.openapi.editor.colors.TextAttributesKey.createTextAttributesKey;

public final class SpnSyntaxHighlighter extends SyntaxHighlighterBase {

    public static final TextAttributesKey COMMENT =
            createTextAttributesKey("SPN_COMMENT", DefaultLanguageHighlighterColors.LINE_COMMENT);
    public static final TextAttributesKey KEYWORD =
            createTextAttributesKey("SPN_KEYWORD", DefaultLanguageHighlighterColors.KEYWORD);
    public static final TextAttributesKey TYPE_NAME =
            createTextAttributesKey("SPN_TYPE_NAME", DefaultLanguageHighlighterColors.CLASS_NAME);
    public static final TextAttributesKey SYMBOL =
            createTextAttributesKey("SPN_SYMBOL", DefaultLanguageHighlighterColors.METADATA);
    public static final TextAttributesKey NUMBER =
            createTextAttributesKey("SPN_NUMBER", DefaultLanguageHighlighterColors.NUMBER);
    public static final TextAttributesKey STRING =
            createTextAttributesKey("SPN_STRING", DefaultLanguageHighlighterColors.STRING);
    public static final TextAttributesKey REGEX =
            createTextAttributesKey("SPN_REGEX", DefaultLanguageHighlighterColors.STRING);
    public static final TextAttributesKey OPERATOR =
            createTextAttributesKey("SPN_OPERATOR", DefaultLanguageHighlighterColors.OPERATION_SIGN);
    public static final TextAttributesKey DELIMITER =
            createTextAttributesKey("SPN_DELIMITER", DefaultLanguageHighlighterColors.BRACKETS);
    public static final TextAttributesKey PATTERN_KW =
            createTextAttributesKey("SPN_PATTERN_KW", DefaultLanguageHighlighterColors.KEYWORD);
    public static final TextAttributesKey IDENTIFIER =
            createTextAttributesKey("SPN_IDENTIFIER", DefaultLanguageHighlighterColors.IDENTIFIER);

    private static final TextAttributesKey[] COMMENT_KEYS    = {COMMENT};
    private static final TextAttributesKey[] KEYWORD_KEYS    = {KEYWORD};
    private static final TextAttributesKey[] TYPE_NAME_KEYS  = {TYPE_NAME};
    private static final TextAttributesKey[] SYMBOL_KEYS     = {SYMBOL};
    private static final TextAttributesKey[] NUMBER_KEYS     = {NUMBER};
    private static final TextAttributesKey[] STRING_KEYS     = {STRING};
    private static final TextAttributesKey[] REGEX_KEYS      = {REGEX};
    private static final TextAttributesKey[] OPERATOR_KEYS   = {OPERATOR};
    private static final TextAttributesKey[] DELIMITER_KEYS  = {DELIMITER};
    private static final TextAttributesKey[] PATTERN_KW_KEYS = {PATTERN_KW};
    private static final TextAttributesKey[] IDENTIFIER_KEYS = {IDENTIFIER};
    private static final TextAttributesKey[] EMPTY           = {};

    @Override
    public Lexer getHighlightingLexer() {
        return new SpnLexerAdapter();
    }

    @Override
    public TextAttributesKey[] getTokenHighlights(IElementType tokenType) {
        if (tokenType == SpnTokenTypes.COMMENT)    return COMMENT_KEYS;
        if (tokenType == SpnTokenTypes.KEYWORD)    return KEYWORD_KEYS;
        if (tokenType == SpnTokenTypes.TYPE_NAME)  return TYPE_NAME_KEYS;
        if (tokenType == SpnTokenTypes.SYMBOL)     return SYMBOL_KEYS;
        if (tokenType == SpnTokenTypes.NUMBER)     return NUMBER_KEYS;
        if (tokenType == SpnTokenTypes.STRING)     return STRING_KEYS;
        if (tokenType == SpnTokenTypes.REGEX)      return REGEX_KEYS;
        if (tokenType == SpnTokenTypes.OPERATOR)   return OPERATOR_KEYS;
        if (tokenType == SpnTokenTypes.DELIMITER)  return DELIMITER_KEYS;
        if (tokenType == SpnTokenTypes.PATTERN_KW) return PATTERN_KW_KEYS;
        if (tokenType == SpnTokenTypes.IDENTIFIER) return IDENTIFIER_KEYS;
        return EMPTY;
    }
}
