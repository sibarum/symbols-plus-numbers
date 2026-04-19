package spn.lang;

public enum TokenType {
    COMMENT       (0.45f, 0.60f, 0.45f),
    KEYWORD       (0.55f, 0.50f, 0.85f),
    TYPE_NAME     (0.35f, 0.78f, 0.75f),
    SYMBOL        (0.85f, 0.60f, 0.35f),
    NUMBER        (0.60f, 0.82f, 0.45f),
    STRING        (0.80f, 0.75f, 0.40f),
    REGEX         (0.80f, 0.50f, 0.65f),
    OPERATOR      (0.80f, 0.80f, 0.80f),
    DELIMITER     (0.65f, 0.65f, 0.65f),
    PATTERN_KW    (0.65f, 0.55f, 0.85f),
    IDENTIFIER    (0.90f, 0.90f, 0.90f),
    // Macro-directive delimiters (<! and !>) — deliberately loud so regions
    // evaluated at macro-expansion time tear visually out of the surrounding
    // SPN code.
    MACRO_DIRECTIVE(0.95f, 0.35f, 0.30f),
    // Qualified dispatch keys (@name, @com.foo.serialize). These mark
    // cross-cutting abstractions — keys that types implement structurally
    // to participate in signatures.
    QUALIFIED_KEY (0.55f, 0.85f, 0.45f),
    WHITESPACE    (0.90f, 0.90f, 0.90f);

    public final float r, g, b;

    TokenType(float r, float g, float b) {
        this.r = r; this.g = g; this.b = b;
    }
}
