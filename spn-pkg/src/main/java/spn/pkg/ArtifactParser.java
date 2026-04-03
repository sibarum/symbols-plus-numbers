package spn.pkg;

import spn.lang.SpnParseException;
import spn.lang.SpnTokenizer;
import spn.lang.TokenType;

import java.util.*;

/**
 * Parses an artifact.spn file into its components: identity, dependencies,
 * defaults, and profiles.
 *
 * The artifact file uses SPN's own collection syntax:
 *
 *   artifact [:group "spn", :name "stdlib", :version "1.0.0"]
 *
 *   require [
 *     [:group "spn", :name "collections", :version "2.1.0"]
 *   ]
 *
 *   defaults [
 *     [:group "spn", :name "collections", :version "2.1.0"]
 *   ]
 *
 *   profiles [
 *     [:collection.default :sorted_array]
 *   ]
 */
public final class ArtifactParser {

    private final SpnTokenizer tokens;

    private ArtifactId artifactId;
    private final List<ArtifactId> dependencies = new ArrayList<>();
    private final List<ArtifactId> defaults = new ArrayList<>();
    private final Map<String, String> profiles = new LinkedHashMap<>();

    public ArtifactParser(String source) {
        this.tokens = new SpnTokenizer(source);
    }

    public record ParseResult(
            ArtifactId id,
            List<ArtifactId> dependencies,
            List<ArtifactId> defaults,
            Map<String, String> profiles
    ) {}

    public ParseResult parse() {
        while (tokens.hasMore()) {
            String keyword = tokens.peek().text();
            switch (keyword) {
                case "artifact" -> parseArtifact();
                case "require" -> parseRequire();
                case "defaults" -> parseDefaults();
                case "profiles" -> parseProfiles();
                default -> throw tokens.error("Unknown artifact keyword: " + keyword);
            }
        }

        if (artifactId == null) {
            throw new SpnParseException("artifact.spn must declare an artifact identity");
        }

        return new ParseResult(artifactId, List.copyOf(dependencies),
                List.copyOf(defaults), Map.copyOf(profiles));
    }

    // ── artifact [:group "spn", :name "stdlib", :version "1.0.0"] ──────

    private void parseArtifact() {
        tokens.expect("artifact");
        Map<String, String> fields = parseDictLiteral();
        String group = fields.get("group");
        String name = fields.get("name");
        String version = fields.getOrDefault("version", null);
        if (group == null || name == null) {
            throw new SpnParseException("artifact must have :group and :name");
        }
        artifactId = new ArtifactId(group, name, version);
    }

    // ── require [...] ──────────────────────────────────────────────────

    private void parseRequire() {
        tokens.expect("require");
        tokens.expect("[");
        while (!tokens.check("]")) {
            dependencies.add(parseArtifactRef());
            tokens.match(",");
        }
        tokens.expect("]");
    }

    // ── defaults [...] ─────────────────────────────────────────────────

    private void parseDefaults() {
        tokens.expect("defaults");
        tokens.expect("[");
        while (!tokens.check("]")) {
            defaults.add(parseArtifactRef());
            tokens.match(",");
        }
        tokens.expect("]");
    }

    // ── profiles [...] ─────────────────────────────────────────────────

    private void parseProfiles() {
        tokens.expect("profiles");
        tokens.expect("[");
        while (!tokens.check("]")) {
            tokens.expect("[");
            String key = unquote(tokens.expectType(TokenType.STRING).text());
            String value;
            if (tokens.checkType(TokenType.STRING)) {
                value = unquote(tokens.advance().text());
            } else if (tokens.checkType(TokenType.NUMBER)) {
                value = tokens.advance().text();
            } else {
                throw tokens.error("Expected profile value (string or number)");
            }
            tokens.expect("]");
            tokens.match(",");
            profiles.put(key, value);
        }
        tokens.expect("]");
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private ArtifactId parseArtifactRef() {
        Map<String, String> fields = parseDictLiteral();
        String group = fields.get("group");
        String name = fields.get("name");
        String version = fields.getOrDefault("version", null);
        if (group == null || name == null) {
            throw new SpnParseException("Dependency must have :group and :name");
        }
        return new ArtifactId(group, name, version);
    }

    private Map<String, String> parseDictLiteral() {
        tokens.expect("[");
        Map<String, String> result = new LinkedHashMap<>();
        while (!tokens.check("]")) {
            String key = parseSymbolName();
            String value;
            if (tokens.checkType(TokenType.STRING)) {
                value = unquote(tokens.advance().text());
            } else if (tokens.checkType(TokenType.NUMBER)) {
                value = tokens.advance().text();
            } else if (tokens.checkType(TokenType.SYMBOL)) {
                value = parseSymbolName();
            } else {
                throw tokens.error("Expected value after :" + key);
            }
            result.put(key, value);
            tokens.match(",");
        }
        tokens.expect("]");
        return result;
    }

    private String parseSymbolName() {
        var tok = tokens.expectType(TokenType.SYMBOL);
        return tok.text().substring(1); // strip leading ':'
    }

    private String unquote(String s) {
        if (s.startsWith("\"") && s.endsWith("\"")) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }
}
