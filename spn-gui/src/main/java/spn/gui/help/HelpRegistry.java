package spn.gui.help;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Loads built-in help articles from {@code spn-gui/src/main/resources/help/*.help}.
 *
 * <h2>{@code .help} file format</h2>
 * <pre>
 * # Title
 * @summary One-line summary shown in search results
 * @category Optional category override (default = "Topic")
 *
 * Body paragraphs separated by blank lines. Lines starting with ## are
 * rendered as subheadings.
 *
 * --- example: hello.spn
 * import Canvas
 * canvas(400, 300)
 * show()
 * ---
 *
 * --- example: shapes.spn
 * ...
 * ---
 * </pre>
 *
 * <p>Articles are listed explicitly so the loader works the same in IDE,
 * tests, and a packaged JAR (no classpath enumeration).
 */
public final class HelpRegistry {

    /** Resource basenames under {@code /help/} (without {@code .help}). */
    private static final List<String> ARTICLE_IDS = List.of(
            "canvas",
            "canvasgui",
            "arrays",
            "dictionaries",
            "math",
            "strings",
            "sets",
            "filesystem",
            "json"
    );

    private final List<HelpArticle> articles;

    public HelpRegistry() {
        List<HelpArticle> loaded = new ArrayList<>();
        for (String id : ARTICLE_IDS) {
            HelpArticle a = loadArticle(id);
            if (a != null) loaded.add(a);
        }
        this.articles = Collections.unmodifiableList(loaded);
    }

    public List<HelpArticle> all() { return articles; }

    private static HelpArticle loadArticle(String id) {
        String resource = "/help/" + id + ".help";
        try (InputStream in = HelpRegistry.class.getResourceAsStream(resource)) {
            if (in == null) {
                System.err.println("[HelpRegistry] missing resource: " + resource);
                return null;
            }
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return parse(id, text);
        } catch (IOException e) {
            System.err.println("[HelpRegistry] failed to read " + resource + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * Parse a {@code .help} file. The format is line-based:
     * <ul>
     *   <li>Lines beginning {@code # } start the title (first such line wins).</li>
     *   <li>{@code @summary <text>} sets the one-liner.</li>
     *   <li>{@code @category <text>} overrides the auto-derived category.</li>
     *   <li>{@code --- example: <name>} opens an example block, terminated by a
     *       line of just {@code ---}. Content is captured verbatim.</li>
     *   <li>Everything else (between header and the first example) is body.</li>
     * </ul>
     */
    static HelpArticle parse(String id, String text) {
        String[] lines = text.replace("\r\n", "\n").split("\n", -1);

        String title = id;
        String summary = "";
        String category = "Topic";
        StringBuilder body = new StringBuilder();
        List<HelpArticle.Example> examples = new ArrayList<>();

        boolean inExample = false;
        String exampleName = null;
        StringBuilder exampleBody = new StringBuilder();

        for (String raw : lines) {
            if (inExample) {
                if (raw.trim().equals("---")) {
                    examples.add(new HelpArticle.Example(
                            exampleName,
                            stripTrailingBlankLine(exampleBody.toString())));
                    exampleBody.setLength(0);
                    exampleName = null;
                    inExample = false;
                } else {
                    exampleBody.append(raw).append('\n');
                }
                continue;
            }

            String trimmed = raw.trim();
            if (trimmed.startsWith("--- example:")) {
                exampleName = trimmed.substring("--- example:".length()).trim();
                if (exampleName.isEmpty()) exampleName = "example.spn";
                inExample = true;
                continue;
            }
            if (trimmed.startsWith("# ") && body.isEmpty() && summary.isEmpty()) {
                title = trimmed.substring(2).trim();
                continue;
            }
            if (trimmed.startsWith("@summary ")) {
                summary = trimmed.substring("@summary ".length()).trim();
                continue;
            }
            if (trimmed.startsWith("@category ")) {
                category = trimmed.substring("@category ".length()).trim();
                continue;
            }
            // Body content. Preserve blank lines for paragraph breaks.
            body.append(raw).append('\n');
        }

        // Trim leading/trailing blank lines from body but preserve internal structure.
        String bodyStr = body.toString().strip();
        return new HelpArticle(id, title, category, summary, bodyStr, List.copyOf(examples));
    }

    private static String stripTrailingBlankLine(String s) {
        // Each example body picks up one trailing newline from the loop;
        // strip it so saved content is the user's verbatim source.
        if (s.endsWith("\n")) return s.substring(0, s.length() - 1);
        return s;
    }
}
