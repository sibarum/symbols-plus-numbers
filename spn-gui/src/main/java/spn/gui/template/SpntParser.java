package spn.gui.template;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses .spnt template files for {@code %{type:label}} placeholders.
 *
 * <p>Supported types:
 * <ul>
 *   <li>{@code %{name:Label}} — name field, linked by label</li>
 *   <li>{@code %{string:Label}} — string field (quoted on output)</li>
 *   <li>{@code %{number:Label}} — number field (validated)</li>
 * </ul>
 */
public final class SpntParser {

    private static final Pattern PLACEHOLDER =
            Pattern.compile("%\\{(name|string|number):([^}]+)}");

    /**
     * A placeholder occurrence in the template text.
     *
     * @param type   "name", "string", or "number"
     * @param label  the display label (also the link key for name fields)
     * @param start  start offset in the template text (inclusive)
     * @param end    end offset in the template text (exclusive)
     */
    public record Placeholder(String type, String label, int start, int end) {}

    private SpntParser() {}

    /** Parse all placeholders from template text, in order of appearance. */
    public static List<Placeholder> parse(String text) {
        List<Placeholder> result = new ArrayList<>();
        Matcher m = PLACEHOLDER.matcher(text);
        while (m.find()) {
            result.add(new Placeholder(m.group(1), m.group(2), m.start(), m.end()));
        }
        return result;
    }

    /**
     * Replace all {@code %{type:label}} placeholders with just the label text.
     * Returns the display text for template instantiation.
     */
    public static String toEditableText(String templateText, List<Placeholder> placeholders) {
        if (placeholders.isEmpty()) return templateText;
        StringBuilder sb = new StringBuilder();
        int cursor = 0;
        for (Placeholder p : placeholders) {
            sb.append(templateText, cursor, p.start());
            sb.append(p.label());
            cursor = p.end();
        }
        sb.append(templateText, cursor, templateText.length());
        return sb.toString();
    }

    /** Check if a file path has the .spnt extension. */
    public static boolean isTemplate(String path) {
        return path != null && path.endsWith(".spnt");
    }
}
