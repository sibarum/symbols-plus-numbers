package spn.gui.help;

import java.util.List;

/**
 * A reference article shown in Help Mode (Ctrl+/) alongside IDE actions.
 * Loaded from a {@code .help} resource file by {@link HelpRegistry}.
 *
 * @param id        stable identifier (resource basename, e.g. "canvas")
 * @param title     display name shown in the search row (e.g. "Canvas")
 * @param category  grouping tag shown right-aligned in the search row
 * @param summary   one-line description shown next to the title
 * @param body      long-form prose; {@code ##} starts a subheading line
 * @param examples  ordered list of openable code samples
 */
public record HelpArticle(String id,
                          String title,
                          String category,
                          String summary,
                          String body,
                          List<Example> examples) {

    /**
     * A named code sample. {@code suggestedFileName} is the proposed file name
     * for Save As when the example is opened in a new tab.
     */
    public record Example(String suggestedFileName, String content) {}
}
