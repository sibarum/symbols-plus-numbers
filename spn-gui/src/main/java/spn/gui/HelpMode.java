package spn.gui;

import spn.fonts.SdfFontRenderer;
import spn.gui.help.HelpArticle;
import spn.gui.help.HelpRegistry;
import spn.stdui.action.Action;
import spn.stdui.action.ActionRegistry;

import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.glfw.GLFW.*;

/**
 * Help search mode (Ctrl+/). Searches two indexes:
 * 1) IDE commands from the ActionRegistry — opening shows shortcut + description
 * 2) Reference articles from {@link HelpRegistry} — opening shows long-form
 *    prose with named code examples that open as new editor tabs.
 *
 * Results show a tag (shortcut for actions, [topic] for articles) plus name.
 * Query matches name, category, and description/body text in either source.
 */
class HelpMode implements Mode {

    private static final float FONT_SCALE = 0.35f;
    private static final float SMALL_SCALE = 0.25f;
    private static final float DETAIL_SCALE = 0.30f;
    private static final float CODE_SCALE = 0.28f;
    private static final float PAD = 30f;
    private static final float ROW_HEIGHT_FACTOR = 1.4f;
    /** Upper cap on visible rows; the actual count is clamped by window height. */
    private static final int MAX_VISIBLE_ROWS = 40;
    private static final float SCROLLBAR_WIDTH = 6f;
    private static final float SCROLLBAR_MIN_THUMB = 20f;

    /** Visible row count as computed on the last render frame. Used by the
     *  scroll handler so the cap stays in sync with the actual window size. */
    private int lastVisibleRows = 1;

    // Colors
    private static final float BG_R = 0.10f, BG_G = 0.10f, BG_B = 0.12f;
    private static final float INPUT_BG_R = 0.16f, INPUT_BG_G = 0.16f, INPUT_BG_B = 0.20f;
    private static final float SEL_R = 0.20f, SEL_G = 0.30f, SEL_B = 0.50f;
    private static final float NAME_R = 0.85f, NAME_G = 0.85f, NAME_B = 0.85f;
    private static final float KEY_R = 0.65f, KEY_G = 0.55f, KEY_B = 0.80f;
    private static final float TOPIC_R = 0.40f, TOPIC_G = 0.75f, TOPIC_B = 0.65f;
    private static final float CAT_R = 0.50f, CAT_G = 0.50f, CAT_B = 0.55f;
    private static final float DESC_R = 0.70f, DESC_G = 0.70f, DESC_B = 0.72f;
    private static final float CODE_R = 0.78f, CODE_G = 0.85f, CODE_B = 0.62f;
    private static final float CURSOR_R = 0.90f, CURSOR_G = 0.90f, CURSOR_B = 0.30f;
    private static final float PROMPT_R = 0.55f, PROMPT_G = 0.55f, PROMPT_B = 0.60f;
    private static final float HEADING_R = 0.45f, HEADING_G = 0.60f, HEADING_B = 0.85f;
    private static final float SUBHEAD_R = 0.55f, SUBHEAD_G = 0.50f, SUBHEAD_B = 0.85f;
    private static final float TRACK_R = 0.18f, TRACK_G = 0.18f, TRACK_B = 0.22f;
    private static final float THUMB_R = 0.45f, THUMB_G = 0.45f, THUMB_B = 0.55f;

    private final EditorWindow window;
    private final SdfFontRenderer font;
    private final ActionRegistry registry;
    private final HelpRegistry helpRegistry;

    /** Combined list of every searchable entry (actions + articles). */
    private final List<HelpItem> allItems;

    private final StringBuilder query = new StringBuilder();
    private int cursorPos;
    private int selectedIndex;
    private int scrollOffset;
    private List<HelpItem> filtered;

    // Detail view — null means we're in search view
    private HelpItem detailItem;
    /** When detailItem is an article: scroll offset in body lines. */
    private int articleScroll;
    /** Largest valid {@link #articleScroll} value as of the last article render.
     *  Updated each frame; used to clamp scroll input so the user can't scroll
     *  past the last visible body line. -1 until the first render. */
    private int articleMaxScroll = -1;
    /** When detailItem is an article: index of highlighted example (0-based). */
    private int exampleIndex;

    HelpMode(EditorWindow window, ActionRegistry registry) {
        this(window, registry, new HelpRegistry());
    }

    HelpMode(EditorWindow window, ActionRegistry registry, HelpRegistry helpRegistry) {
        this.window = window;
        this.font = window.getFont();
        this.registry = registry;
        this.helpRegistry = helpRegistry;
        this.allItems = buildIndex();
        this.filtered = allItems;
    }

    private List<HelpItem> buildIndex() {
        List<HelpItem> items = new ArrayList<>();
        for (Action a : registry.all()) items.add(new HelpItem.OfAction(a));
        for (HelpArticle ar : helpRegistry.all()) items.add(new HelpItem.OfArticle(ar));
        return items;
    }

    @Override
    public boolean onKey(int key, int scancode, int action, int mods) {
        if (action != GLFW_PRESS && action != GLFW_REPEAT) return true;

        boolean ctrl = (mods & GLFW_MOD_CONTROL) != 0;

        if (detailItem != null) {
            return onKeyDetail(key, ctrl);
        }

        // Ctrl+V — paste clipboard text into the query at the cursor
        if (ctrl && key == GLFW_KEY_V) {
            String clip = window.getClipboardText();
            if (!clip.isEmpty()) {
                query.insert(cursorPos, clip);
                cursorPos += clip.length();
                refilter();
            }
            return true;
        }

        // Search view
        switch (key) {
            case GLFW_KEY_ESCAPE -> {
                window.popMode();
                return true;
            }
            case GLFW_KEY_ENTER -> {
                if (!filtered.isEmpty() && selectedIndex < filtered.size()) {
                    openDetail(filtered.get(selectedIndex));
                }
                return true;
            }
            case GLFW_KEY_UP -> {
                if (selectedIndex > 0) selectedIndex--;
                ensureVisible();
                return true;
            }
            case GLFW_KEY_DOWN -> {
                if (selectedIndex < filtered.size() - 1) selectedIndex++;
                ensureVisible();
                return true;
            }
            case GLFW_KEY_BACKSPACE -> {
                if (cursorPos > 0) {
                    query.deleteCharAt(cursorPos - 1);
                    cursorPos--;
                    refilter();
                }
                return true;
            }
            case GLFW_KEY_DELETE -> {
                if (cursorPos < query.length()) {
                    query.deleteCharAt(cursorPos);
                    refilter();
                }
                return true;
            }
            case GLFW_KEY_LEFT -> { if (cursorPos > 0) cursorPos--; return true; }
            case GLFW_KEY_RIGHT -> { if (cursorPos < query.length()) cursorPos++; return true; }
            case GLFW_KEY_HOME -> { cursorPos = 0; return true; }
            case GLFW_KEY_END -> { cursorPos = query.length(); return true; }
        }
        return true;
    }

    /** Detail-view key handling. Articles get scrolling and example navigation. */
    private boolean onKeyDetail(int key, boolean ctrl) {
        if (key == GLFW_KEY_ESCAPE || key == GLFW_KEY_BACKSPACE) {
            closeDetail();
            return true;
        }
        if (!(detailItem instanceof HelpItem.OfArticle ofArticle)) {
            return true; // action detail is read-only
        }
        HelpArticle art = ofArticle.article();
        int exCount = art.examples().size();

        // Number keys 1-9 open that example directly.
        if (key >= GLFW_KEY_1 && key <= GLFW_KEY_9) {
            int n = key - GLFW_KEY_1;
            if (n < exCount) {
                openExample(art.examples().get(n));
                return true;
            }
        }

        switch (key) {
            case GLFW_KEY_DOWN -> {
                if (exCount > 0 && exampleIndex < exCount - 1) exampleIndex++;
                else articleScroll = clampArticleScroll(articleScroll + 1);
                return true;
            }
            case GLFW_KEY_UP -> {
                if (articleScroll > 0) articleScroll--;
                else if (exCount > 0 && exampleIndex > 0) exampleIndex--;
                return true;
            }
            case GLFW_KEY_PAGE_DOWN -> { articleScroll = clampArticleScroll(articleScroll + 10); return true; }
            case GLFW_KEY_PAGE_UP -> { articleScroll = Math.max(0, articleScroll - 10); return true; }
            case GLFW_KEY_HOME -> { articleScroll = 0; return true; }
            case GLFW_KEY_END -> { articleScroll = Math.max(0, articleMaxScroll); return true; }
            case GLFW_KEY_ENTER -> {
                if (exCount > 0) openExample(art.examples().get(exampleIndex));
                return true;
            }
        }
        return true;
    }

    /** Cap a proposed scroll offset to the largest position the last render
     *  reported as valid. The first frame has no max yet (-1) so the offset
     *  is allowed through and clamped on the next render. */
    private int clampArticleScroll(int proposed) {
        if (articleMaxScroll < 0) return Math.max(0, proposed);
        return Math.max(0, Math.min(proposed, articleMaxScroll));
    }

    @Override
    public boolean onChar(int codepoint) {
        if (detailItem != null) return true;
        query.insert(cursorPos, Character.toChars(codepoint));
        cursorPos++;
        refilter();
        return true;
    }

    @Override
    public boolean onMouseButton(int button, int action, int mods, double mx, double my) {
        if (detailItem != null) {
            // In article detail view, clicking an example row opens it.
            if (button == GLFW_MOUSE_BUTTON_LEFT && action == GLFW_PRESS
                    && detailItem instanceof HelpItem.OfArticle ofArticle) {
                int idx = exampleRowAtY(my, ofArticle.article());
                if (idx >= 0) {
                    openExample(ofArticle.article().examples().get(idx));
                    return true;
                }
            }
            // Click anywhere else returns to search.
            if (button == GLFW_MOUSE_BUTTON_LEFT && action == GLFW_PRESS) {
                closeDetail();
            }
            return true;
        }
        if (button == GLFW_MOUSE_BUTTON_LEFT && action == GLFW_PRESS) {
            int clickedIndex = rowAtY(my);
            if (clickedIndex >= 0 && clickedIndex < filtered.size()) {
                openDetail(filtered.get(clickedIndex));
            }
        }
        return true;
    }

    @Override
    public boolean onCursorPos(double mx, double my) {
        if (detailItem != null) {
            // Hover over example rows highlights them.
            if (detailItem instanceof HelpItem.OfArticle ofArticle) {
                int idx = exampleRowAtY(my, ofArticle.article());
                if (idx >= 0) exampleIndex = idx;
            }
            return true;
        }
        int hoverIndex = rowAtY(my);
        if (hoverIndex >= 0 && hoverIndex < filtered.size()) {
            selectedIndex = hoverIndex;
        }
        return true;
    }

    @Override
    public boolean onScroll(double xoff, double yoff) {
        int delta = ListScroll.delta(yoff);
        if (detailItem instanceof HelpItem.OfArticle) {
            articleScroll = clampArticleScroll(articleScroll - delta);
            return true;
        }
        scrollOffset = Math.max(0, Math.min(scrollOffset - delta,
                Math.max(0, filtered.size() - lastVisibleRows)));
        return true;
    }

    @Override
    public void render(float width, float height) {
        font.drawRect(0, 0, width, height, BG_R, BG_G, BG_B);

        if (detailItem instanceof HelpItem.OfAction ofAction) {
            renderActionDetail(width, height, ofAction.action());
        } else if (detailItem instanceof HelpItem.OfArticle ofArticle) {
            renderArticleDetail(width, height, ofArticle.article());
        } else {
            renderSearch(width, height);
        }
    }

    private void renderSearch(float width, float height) {
        float rowHeight = font.getLineHeight(FONT_SCALE) * ROW_HEIGHT_FACTOR;
        float smallHeight = font.getLineHeight(SMALL_SCALE) * ROW_HEIGHT_FACTOR;

        float paletteWidth = Math.min(width - PAD * 2, 700f);
        float paletteX = (width - paletteWidth) / 2f;
        float y = PAD + 40f;

        // Title (offset up by half an em so it clears the search input background)
        font.drawText("> Help", paletteX, y - smallHeight * 0.5f, SMALL_SCALE, PROMPT_R, PROMPT_G, PROMPT_B);
        y += smallHeight + 8f;

        // Search input
        float inputH = rowHeight + 8f;
        font.drawRect(paletteX, y - rowHeight, paletteWidth, inputH,
                INPUT_BG_R, INPUT_BG_G, INPUT_BG_B);

        String queryStr = query.toString();
        float textX = paletteX + 12f;
        font.drawText(queryStr, textX, y, FONT_SCALE, NAME_R, NAME_G, NAME_B);

        float cursorX = textX + font.getTextWidth(queryStr.substring(0, cursorPos), FONT_SCALE);
        font.drawRect(cursorX, y - rowHeight + 4f, 2f, rowHeight, CURSOR_R, CURSOR_G, CURSOR_B);

        y += inputH + 8f;

        // Fit the result list to the actual window height, not a hard cap.
        // Reserve space at the bottom for the "N-M of T" indicator so it never
        // overlaps the last visible row.
        float listTop = y;
        float reservedBottom = smallHeight + PAD;
        float listHeight = Math.max(rowHeight, height - listTop - reservedBottom);
        int fitRows = Math.max(1, (int) Math.floor(listHeight / rowHeight));
        int visibleRows = Math.min(MAX_VISIBLE_ROWS, fitRows);
        lastVisibleRows = visibleRows;

        // Keep the scroll cap consistent with the render window. Without this,
        // scrollOffset could stay stuck past the last reachable value when the
        // window shrinks or the filtered list shortens.
        int maxScroll = Math.max(0, filtered.size() - visibleRows);
        if (scrollOffset > maxScroll) scrollOffset = maxScroll;

        // Results
        int visibleCount = Math.min(visibleRows, filtered.size() - scrollOffset);
        boolean hasScrollbar = filtered.size() > visibleRows;
        float rowRight = paletteWidth - (hasScrollbar ? SCROLLBAR_WIDTH + 4f : 0f);

        for (int i = 0; i < visibleCount; i++) {
            int idx = scrollOffset + i;
            HelpItem item = filtered.get(idx);
            float rowY = y + i * rowHeight;

            if (idx == selectedIndex) {
                font.drawRect(paletteX, rowY, rowRight, rowHeight, SEL_R, SEL_G, SEL_B);
            }

            float itemY = rowY + rowHeight - 4f;

            // Tag (left, accent color) — shortcut for actions, [topic] for articles
            String tag = item.tag();
            float[] tagColor = item.isArticle()
                    ? new float[]{TOPIC_R, TOPIC_G, TOPIC_B}
                    : new float[]{KEY_R, KEY_G, KEY_B};
            if (!tag.isEmpty()) {
                font.drawText(tag, paletteX + 12f, itemY, SMALL_SCALE,
                        tagColor[0], tagColor[1], tagColor[2]);
                float tagW = font.getTextWidth(tag, SMALL_SCALE);
                font.drawText(item.name(), paletteX + 12f + tagW + 12f, itemY, FONT_SCALE,
                        NAME_R, NAME_G, NAME_B);
            } else {
                font.drawText(item.name(), paletteX + 12f, itemY, FONT_SCALE, NAME_R, NAME_G, NAME_B);
            }

            // Category tag (right-aligned, dim)
            float catW = font.getTextWidth(item.category(), SMALL_SCALE);
            font.drawText(item.category(), paletteX + rowRight - 12f - catW, itemY,
                    SMALL_SCALE, CAT_R, CAT_G, CAT_B);
        }

        // Scrollbar — thin vertical track + proportional thumb on the right
        // edge of the palette. Drawn only when there's more than one screenful.
        if (hasScrollbar) {
            float trackX = paletteX + paletteWidth - SCROLLBAR_WIDTH;
            float trackTop = y;
            float trackHeight = visibleRows * rowHeight;
            font.drawRect(trackX, trackTop, SCROLLBAR_WIDTH, trackHeight,
                    TRACK_R, TRACK_G, TRACK_B);
            float thumbH = Math.max(SCROLLBAR_MIN_THUMB,
                    trackHeight * ((float) visibleRows / filtered.size()));
            float scrollSpan = trackHeight - thumbH;
            float thumbY = trackTop + (maxScroll > 0
                    ? scrollSpan * ((float) scrollOffset / maxScroll) : 0f);
            font.drawRect(trackX, thumbY, SCROLLBAR_WIDTH, thumbH,
                    THUMB_R, THUMB_G, THUMB_B);

            // Textual position indicator stays as a secondary cue below the list.
            String info = (scrollOffset + 1) + "-" + (scrollOffset + visibleCount)
                    + " of " + filtered.size();
            float infoW = font.getTextWidth(info, SMALL_SCALE);
            font.drawText(info, paletteX + paletteWidth - 12f - infoW,
                    y + visibleCount * rowHeight + smallHeight,
                    SMALL_SCALE, PROMPT_R, PROMPT_G, PROMPT_B);
        }
    }

    private void renderActionDetail(float width, float height, Action a) {
        float paletteWidth = Math.min(width - PAD * 2, 600f);
        float paletteX = (width - paletteWidth) / 2f;
        float lineH = font.getLineHeight(DETAIL_SCALE);
        float y = PAD + 60f;

        // Heading: name
        font.drawText(a.name(), paletteX, y, FONT_SCALE, HEADING_R, HEADING_G, HEADING_B);
        y += font.getLineHeight(FONT_SCALE) * 1.6f;

        // Shortcut
        if (!a.shortcut().isEmpty()) {
            font.drawText("Shortcut", paletteX, y, SMALL_SCALE, PROMPT_R, PROMPT_G, PROMPT_B);
            y += font.getLineHeight(SMALL_SCALE) * 1.2f;
            font.drawText(a.shortcut(), paletteX + 16f, y, DETAIL_SCALE, KEY_R, KEY_G, KEY_B);
            y += lineH * 1.8f;
        }

        // Category
        font.drawText("Category", paletteX, y, SMALL_SCALE, PROMPT_R, PROMPT_G, PROMPT_B);
        y += font.getLineHeight(SMALL_SCALE) * 1.2f;
        font.drawText(a.category(), paletteX + 16f, y, DETAIL_SCALE, CAT_R, CAT_G, CAT_B);
        y += lineH * 1.8f;

        // Description
        String desc = a.description();
        if (!desc.isEmpty()) {
            font.drawText("Description", paletteX, y, SMALL_SCALE, PROMPT_R, PROMPT_G, PROMPT_B);
            y += font.getLineHeight(SMALL_SCALE) * 1.2f;

            for (String line : wordWrap(desc, paletteWidth - 16f, DETAIL_SCALE)) {
                font.drawText(line, paletteX + 16f, y, DETAIL_SCALE, DESC_R, DESC_G, DESC_B);
                y += lineH * 1.3f;
            }
        }

        y += lineH * 2;
        font.drawText("Press Esc or Backspace to return to search", paletteX, y,
                SMALL_SCALE, PROMPT_R, PROMPT_G, PROMPT_B);
    }

    /** Cached layout for example rows, used by mouse hit-testing. */
    private float exampleRowsTop;
    private float exampleRowH;

    private void renderArticleDetail(float width, float height, HelpArticle art) {
        float paletteWidth = Math.min(width - PAD * 2, 760f);
        float paletteX = (width - paletteWidth) / 2f;
        float bodyLineH = font.getLineHeight(DETAIL_SCALE) * 1.3f;
        float subheadLineH = font.getLineHeight(FONT_SCALE) * 1.6f;
        float exampleRowHeight = font.getLineHeight(DETAIL_SCALE) * ROW_HEIGHT_FACTOR;
        float footerH = font.getLineHeight(SMALL_SCALE) * 1.4f;

        // Carve out the bottom region for examples + footer first, so the body
        // never overlaps the examples block on a long article.
        float footerY = height - PAD - 8f;
        float examplesBlockH = art.examples().isEmpty() ? 0f
                : font.getLineHeight(SMALL_SCALE) * 1.4f                  // header
                + art.examples().size() * exampleRowHeight                // rows
                + 8f;                                                     // padding
        float examplesTop = footerY - footerH - examplesBlockH;
        float bodyAreaBottom = examplesTop - bodyLineH;

        float y = PAD + 50f;

        // Title bar — title in the heading color, summary in dim prompt.
        font.drawText(art.title(), paletteX, y, FONT_SCALE, HEADING_R, HEADING_G, HEADING_B);
        float titleW = font.getTextWidth(art.title(), FONT_SCALE);
        if (!art.summary().isEmpty()) {
            font.drawText("  " + art.summary(),
                    paletteX + titleW, y, DETAIL_SCALE, PROMPT_R, PROMPT_G, PROMPT_B);
        }
        y += font.getLineHeight(FONT_SCALE) * 1.8f;

        // Body — word-wrap each non-blank line; blank lines become paragraph
        // gaps. Subheadings (## prefix) render in a distinct color, slightly
        // larger. articleScroll skips that many rendered body lines.
        //
        // We pre-tokenize the body into "render rows" first so we can count
        // the total line height upfront. That gives us the maximum legal
        // scroll position (so the user can't scroll into empty space below
        // the last paragraph).
        record BodyRow(String text, float height, boolean isSubhead) {}
        java.util.List<BodyRow> rows = new java.util.ArrayList<>();
        for (String paragraph : art.body().split("\n")) {
            String trimmed = paragraph.stripTrailing();
            if (trimmed.isEmpty()) {
                rows.add(new BodyRow("", bodyLineH * 0.5f, false));
            } else if (trimmed.startsWith("## ")) {
                rows.add(new BodyRow(trimmed.substring(3), subheadLineH, true));
            } else {
                for (String line : wordWrap(trimmed, paletteWidth, DETAIL_SCALE)) {
                    rows.add(new BodyRow(line, bodyLineH, false));
                }
            }
        }

        // Compute the maximum scroll: the largest articleScroll such that
        // *some* body content is still visible. Walking from the end and
        // accumulating heights gives a tight bound that respects the actual
        // mix of subheadings and prose lines.
        float bodyAvail = bodyAreaBottom - y;
        int maxScroll = rows.size();
        float consumed = 0f;
        for (int i = rows.size() - 1; i >= 0; i--) {
            consumed += rows.get(i).height;
            if (consumed > bodyAvail) break;
            maxScroll = i;
        }
        articleMaxScroll = maxScroll;
        if (articleScroll > maxScroll) articleScroll = maxScroll;

        // Render visible rows starting at articleScroll.
        boolean bodyTruncated = false;
        for (int i = articleScroll; i < rows.size(); i++) {
            BodyRow r = rows.get(i);
            if (y + r.height > bodyAreaBottom) { bodyTruncated = true; break; }
            if (r.isSubhead()) {
                font.drawText(r.text(), paletteX, y, FONT_SCALE,
                        SUBHEAD_R, SUBHEAD_G, SUBHEAD_B);
            } else if (!r.text().isEmpty()) {
                font.drawText(r.text(), paletteX, y, DETAIL_SCALE,
                        DESC_R, DESC_G, DESC_B);
            }
            y += r.height;
        }

        // Tiny truncation indicator when body was clipped — tells the user
        // there's more content below the visible region.
        if (bodyTruncated) {
            font.drawText("⋯ scroll for more (↓ / PgDn)",
                    paletteX, bodyAreaBottom, SMALL_SCALE,
                    PROMPT_R, PROMPT_G, PROMPT_B);
        }

        // Examples list — anchored at the pre-computed examplesTop.
        if (!art.examples().isEmpty()) {
            float ey = examplesTop;
            font.drawText("Examples — Enter to open, 1-9 for direct access:",
                    paletteX, ey, SMALL_SCALE, PROMPT_R, PROMPT_G, PROMPT_B);
            ey += font.getLineHeight(SMALL_SCALE) * 1.4f;

            exampleRowsTop = ey;
            exampleRowH = exampleRowHeight;
            for (int i = 0; i < art.examples().size(); i++) {
                HelpArticle.Example ex = art.examples().get(i);
                if (i == exampleIndex) {
                    font.drawRect(paletteX - 4f, ey, paletteWidth, exampleRowHeight,
                            SEL_R, SEL_G, SEL_B);
                }
                String num = (i < 9 ? (i + 1) + ". " : "   ");
                font.drawText(num + ex.suggestedFileName(),
                        paletteX, ey + exampleRowHeight - 6f, DETAIL_SCALE,
                        CODE_R, CODE_G, CODE_B);
                ey += exampleRowHeight;
            }
        } else {
            exampleRowsTop = -1f;
            exampleRowH = 0f;
        }

        // Footer hint
        String hint = art.examples().isEmpty()
                ? "Esc/Backspace return to search"
                : "↑↓ select example | Enter open | 1-9 direct | Esc return";
        font.drawText(hint, paletteX, footerY, SMALL_SCALE,
                PROMPT_R, PROMPT_G, PROMPT_B);
    }

    /** Hit-test for example rows. Returns -1 if my isn't on a row. */
    private int exampleRowAtY(double my, HelpArticle art) {
        if (exampleRowsTop < 0 || exampleRowH <= 0) return -1;
        if (my < exampleRowsTop) return -1;
        int idx = (int) ((my - exampleRowsTop) / exampleRowH);
        if (idx < 0 || idx >= art.examples().size()) return -1;
        return idx;
    }

    @Override
    public String hudText() {
        if (detailItem instanceof HelpItem.OfArticle) {
            return "↑↓ navigate | Enter / 1-9 open example | Esc Back to Search";
        }
        if (detailItem != null) {
            return "Esc Back to Search";
        }
        return "Type to search | Enter View Details | Esc Close";
    }

    // ── Internal ────────────────────────────────────────────────────

    private void openDetail(HelpItem item) {
        detailItem = item;
        articleScroll = 0;
        articleMaxScroll = -1; // recomputed on first render
        exampleIndex = 0;
    }

    private void closeDetail() {
        detailItem = null;
    }

    /** Open an example as a new untitled tab. The user gets a Save As dialog
     *  on Ctrl+S (handled by EditorWindow.saveFile when filePath is null). */
    private void openExample(HelpArticle.Example ex) {
        window.openNewTab(ex.content());
        window.popMode();
    }

    private void refilter() {
        String q = query.toString().toLowerCase();
        if (q.isEmpty()) {
            filtered = allItems;
        } else {
            List<HelpItem> out = new ArrayList<>();
            for (HelpItem it : allItems) if (it.matches(q)) out.add(it);
            filtered = out;
        }
        selectedIndex = 0;
        scrollOffset = 0;
    }

    private void ensureVisible() {
        if (selectedIndex < scrollOffset) scrollOffset = selectedIndex;
        else if (selectedIndex >= scrollOffset + lastVisibleRows)
            scrollOffset = selectedIndex - lastVisibleRows + 1;
    }

    private int rowAtY(double my) {
        float rowHeight = font.getLineHeight(FONT_SCALE) * ROW_HEIGHT_FACTOR;
        float smallHeight = font.getLineHeight(SMALL_SCALE) * ROW_HEIGHT_FACTOR;
        float listTop = PAD + 40f + smallHeight + 8f + rowHeight + 8f + 8f;
        if (my < listTop) return -1;
        return (int) ((my - listTop) / rowHeight) + scrollOffset;
    }

    /** Simple word-wrap: splits text into lines that fit within maxWidth. */
    private String[] wordWrap(String text, float maxWidth, float scale) {
        var lines = new java.util.ArrayList<String>();
        for (String paragraph : text.split("\n")) {
            String[] words = paragraph.split(" ");
            StringBuilder line = new StringBuilder();
            for (String word : words) {
                String candidate = line.isEmpty() ? word : line + " " + word;
                if (font.getTextWidth(candidate, scale) > maxWidth && !line.isEmpty()) {
                    lines.add(line.toString());
                    line = new StringBuilder(word);
                } else {
                    line = new StringBuilder(candidate);
                }
            }
            if (!line.isEmpty()) lines.add(line.toString());
        }
        return lines.toArray(new String[0]);
    }

    /** Tagged union of searchable entries. Actions show their shortcut as
     *  the leading tag; articles show {@code [topic]}. */
    private sealed interface HelpItem {
        String name();
        String tag();
        String category();
        boolean isArticle();
        boolean matches(String lowerQuery);

        record OfAction(Action action) implements HelpItem {
            public String name()     { return action.name(); }
            public String tag()      { return action.shortcut(); }
            public String category() { return action.category(); }
            public boolean isArticle() { return false; }
            public boolean matches(String q) {
                return action.name().toLowerCase().contains(q)
                    || action.category().toLowerCase().contains(q)
                    || action.shortcut().toLowerCase().contains(q)
                    || action.description().toLowerCase().contains(q);
            }
        }

        record OfArticle(HelpArticle article) implements HelpItem {
            public String name()     { return article.title(); }
            public String tag()      { return "[topic]"; }
            public String category() { return article.category(); }
            public boolean isArticle() { return true; }
            public boolean matches(String q) {
                return article.title().toLowerCase().contains(q)
                    || article.summary().toLowerCase().contains(q)
                    || article.category().toLowerCase().contains(q)
                    || article.body().toLowerCase().contains(q);
            }
        }
    }
}
