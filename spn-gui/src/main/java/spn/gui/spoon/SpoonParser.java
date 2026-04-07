package spn.gui.spoon;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Parses .spoon text (SPN Object Notation) into a key-value map.
 *
 * <p>Format rules:
 * <ul>
 *   <li>Lines starting with {@code #} are comments — skipped</li>
 *   <li>Blank lines are visual separators — skipped</li>
 *   <li>Lines matching {@code key: value} produce a field entry</li>
 *   <li>The key is everything before the first {@code ": "}, trimmed</li>
 *   <li>The value is everything after the first {@code ": "}, trimmed</li>
 * </ul>
 */
public final class SpoonParser {

    private SpoonParser() {}

    /**
     * Parse .spoon text into an ordered map of field name → value.
     *
     * @param text the full .spoon file content
     * @return ordered map preserving field declaration order
     * @throws SpoonParseException if a non-blank, non-comment line has no colon
     */
    public static Map<String, String> parse(String text) {
        Map<String, String> fields = new LinkedHashMap<>();
        String[] lines = text.split("\n", -1);

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();

            // Skip comments and blank lines
            if (line.isEmpty() || line.startsWith("#")) continue;

            int colon = line.indexOf(": ");
            if (colon < 0) {
                // Allow bare "key:" with no value (colon at end)
                if (line.endsWith(":")) {
                    String key = line.substring(0, line.length() - 1).trim();
                    if (!key.isEmpty()) {
                        fields.put(key, "");
                        continue;
                    }
                }
                throw new SpoonParseException(
                        "Line " + (i + 1) + ": expected 'key: value', got: " + line);
            }

            String key = line.substring(0, colon).trim();
            String value = line.substring(colon + 2).trim();
            fields.put(key, value);
        }

        return fields;
    }

    /** Thrown when .spoon text has a malformed line. */
    public static class SpoonParseException extends RuntimeException {
        public SpoonParseException(String message) {
            super(message);
        }
    }
}
