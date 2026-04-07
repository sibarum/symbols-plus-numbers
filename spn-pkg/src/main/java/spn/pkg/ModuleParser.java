package spn.pkg;

import spn.lang.SpnParseException;
import spn.lang.SpnTokenizer;
import spn.lang.TokenType;

import java.util.ArrayList;
import java.util.List;

/**
 * Parses a module.spn file. The format uses SPN-native statement syntax:
 *
 * <pre>
 * module com.mysite.mymodule
 * version "1.0.0"
 *
 * require "com.other.lib"
 * require "com.another.dep"
 * </pre>
 */
public final class ModuleParser {

    private final SpnTokenizer tokens;
    private String namespace;
    private String version;
    private final List<String> requires = new ArrayList<>();

    public ModuleParser(String source) {
        this.tokens = new SpnTokenizer(source);
    }

    public record ParseResult(
            ModuleId id,
            List<String> requires
    ) {}

    public ParseResult parse() {
        while (tokens.hasMore()) {
            String keyword = tokens.peek().text();
            switch (keyword) {
                case "module"  -> parseModule();
                case "version" -> parseVersion();
                case "require" -> parseRequire();
                case "--"      -> skipComment();
                default -> {
                    // Skip unknown tokens (comments, blank lines, etc.)
                    if (tokens.peek().type() == TokenType.COMMENT
                            || tokens.peek().type() == TokenType.WHITESPACE) {
                        tokens.advance();
                    } else {
                        throw tokens.error("Unknown module.spn keyword: " + keyword);
                    }
                }
            }
        }

        if (namespace == null) {
            throw new SpnParseException("module.spn must contain a 'module' declaration");
        }

        return new ParseResult(new ModuleId(namespace, version), List.copyOf(requires));
    }

    // ── module com.mysite.mymodule ────────────────────────────────────

    private void parseModule() {
        tokens.expect("module");
        // Read the dotted namespace: ident.ident.ident
        StringBuilder ns = new StringBuilder();
        ns.append(tokens.advance().text()); // first segment
        while (tokens.match(".")) {
            ns.append('.').append(tokens.advance().text());
        }
        this.namespace = ns.toString();
    }

    // ── version "1.0.0" ──────────────────────────────────────────────

    private void parseVersion() {
        tokens.expect("version");
        var tok = tokens.expectType(TokenType.STRING);
        this.version = unquote(tok.text());
    }

    // ── require "com.other.lib" ──────────────────────────────────────

    private void parseRequire() {
        tokens.expect("require");
        var tok = tokens.expectType(TokenType.STRING);
        requires.add(unquote(tok.text()));
    }

    private void skipComment() {
        // Advance past comment tokens
        while (tokens.hasMore() && tokens.peek().type() == TokenType.COMMENT) {
            tokens.advance();
        }
    }

    private String unquote(String s) {
        if (s.startsWith("\"") && s.endsWith("\"")) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }
}
