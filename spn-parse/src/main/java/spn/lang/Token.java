package spn.lang;

/** A highlighted span within a single line: half-open range [startCol, endCol). */
public record Token(int startCol, int endCol, TokenType type) {}
