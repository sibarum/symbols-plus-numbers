package spn.gui;

import spn.fonts.SdfFontRenderer;
import spn.lang.DeclarationScanner;
import spn.trace.TraceEvent;

import java.util.*;

import static org.lwjgl.glfw.GLFW.*;

/**
 * Read-only source view with execution trace overlay.
 *
 * Shows source code with traced declaration blocks highlighted.
 * Default view: summary table of all traced blocks.
 * Click a block (in code or table) to pin it and see its invocation details.
 */
public class TraceSourceTab extends ScrollableTab {

    // Block tint colors (cycling)
    private static final float[][] BLOCK_COLORS = {
            {0.3f, 0.5f, 0.9f}, {0.3f, 0.8f, 0.5f}, {0.6f, 0.4f, 0.8f},
            {0.8f, 0.6f, 0.3f}, {0.5f, 0.8f, 0.8f},
    };

    private final String fileName;
    private final String source;
    private final List<TraceEvent> events;
    private final List<DeclarationScanner.Span> spans;

    // Traced spans with data (sorted by call count descending)
    private final List<Integer> tracedSpanIndices = new ArrayList<>();

    // Map: span index → CALL/RETURN events
    private final Map<Integer, List<TraceEvent>> spanCalls = new LinkedHashMap<>();
    private final Map<Integer, List<TraceEvent>> spanReturns = new LinkedHashMap<>();
    // Map: CALL sequence → list of ASSIGN events within that call
    private final Map<Long, List<TraceEvent>> callAssigns = new LinkedHashMap<>();

    private int hoveredSpan = -1;
    private int pinnedSpan = -1;
    private final ListScroll tableScroll = new ListScroll();
    private int summarySelected = 0;

    // Panel layout — set during render, used for click hit-testing and scroll routing
    private float panelTop;
    private float panelRowStart;
    private float panelRowHeight;
    private int summaryHovered = -1; // row under mouse in summary table
    private double lastMouseY;       // for hover-based scroll routing

    TraceSourceTab(EditorWindow window, String source, List<TraceEvent> events, String fileName) {
        super(window);
        this.fileName = fileName;
        this.source = source;
        this.events = events;

        textArea.setText(source);
        spans = DeclarationScanner.scan(source);
        mapEventsToSpans();

        // Install pre-text renderer for block highlights
        textArea.setPreTextRenderer(this::renderBlockOverlays);
    }

    private void mapEventsToSpans() {
        Map<String, Integer> nameToSpan = new HashMap<>();
        for (int i = 0; i < spans.size(); i++) {
            String name = extractDeclName(spans.get(i));
            if (name != null) nameToSpan.put(name, i);
        }
        for (TraceEvent e : events) {
            if (e.kind() == TraceEvent.Kind.ASSIGN) {
                // Group ASSIGN events by their parent CALL sequence
                callAssigns.computeIfAbsent(e.parentSequence(), k -> new ArrayList<>()).add(e);
                continue;
            }
            Integer idx = nameToSpan.get(e.location());
            if (idx == null) continue;
            if (e.kind() == TraceEvent.Kind.CALL)
                spanCalls.computeIfAbsent(idx, k -> new ArrayList<>()).add(e);
            else if (e.kind() == TraceEvent.Kind.RETURN)
                spanReturns.computeIfAbsent(idx, k -> new ArrayList<>()).add(e);
        }
        // Build sorted list of traced spans (by call count, descending)
        tracedSpanIndices.addAll(spanCalls.keySet());
        tracedSpanIndices.sort((a, b) -> spanCalls.get(b).size() - spanCalls.get(a).size());
    }

    private String extractDeclName(DeclarationScanner.Span span) {
        String src = span.source().trim();
        if (src.startsWith("pure ") || src.startsWith("action ")) {
            String after = src.substring(src.indexOf(' ') + 1);
            int paren = after.indexOf('(');
            if (paren > 0) {
                String name = after.substring(0, paren).trim();
                // Factory declarations (name starts with uppercase, no dot) use arity-qualified names
                if (!name.isEmpty() && Character.isUpperCase(name.charAt(0)) && !name.contains(".")) {
                    int arity = countParams(after.substring(paren));
                    return name + "/" + arity;
                }
                return name;
            }
        }
        if (src.startsWith("type ")) {
            String after = src.substring(5).trim();
            int paren = after.indexOf('(');
            if (paren > 0) return after.substring(0, paren).trim();
        }
        if (src.startsWith("const ")) {
            String after = src.substring(6).trim();
            int eq = after.indexOf('=');
            if (eq > 0) return after.substring(0, eq).trim();
        }
        return null;
    }

    /** Count comma-separated params in a parenthesized list like "(int, int) -> ...". */
    private int countParams(String fromParen) {
        int depth = 0;
        int count = 0;
        boolean seenContent = false;
        for (int i = 0; i < fromParen.length(); i++) {
            char c = fromParen.charAt(i);
            if (c == '(') { depth++; continue; }
            if (c == ')') { depth--; if (depth == 0) break; continue; }
            if (depth == 1) {
                if (c == ',') { count++; }
                else if (!Character.isWhitespace(c)) { seenContent = true; }
            }
        }
        return seenContent ? count + 1 : 0;
    }

    // ── Tab interface ──────────────────────────────────────────────────

    @Override
    public String label() { return "Source: " + fileName; }

    @Override
    public boolean isDirty() { return false; }

    @Override
    public void render(float x, float y, float width, float height) {
        float hudH = window.getHudHeight();

        // Compute panel height first so the TextArea shrinks to fit above it
        float panelH;
        if (pinnedSpan >= 0) {
            panelH = height * 0.30f;
        } else if (!tracedSpanIndices.isEmpty()) {
            panelH = Math.min(height * 0.30f, tracedSpanIndices.size() * 25f + 40f);
        } else {
            panelH = 0;
        }

        float sourceH = height - panelH;
        panelTop = y + sourceH;

        // Render source code in the top region (no overlap with panel)
        layoutAndRender(x, y, width, sourceH);
        textArea.setExtraScrollPadding(0);

        // Bottom panel: either invocation details (pinned) or summary table
        if (pinnedSpan >= 0) {
            renderInvocationPanel(x, y, width, height, panelH);
        } else {
            renderSummaryPanel(x, y, width, height, panelH);
        }
    }

    /** Pre-text renderer callback — draws block highlights inside TextArea's render pipeline. */
    private void renderBlockOverlays(SdfFontRenderer font, float textX, float textY,
                                      float cellWidth, float cellHeight, float highlightY,
                                      int scrollRow, int visibleRows,
                                      float boundsX, float totalWidth) {
        for (int i = 0; i < spans.size(); i++) {
            DeclarationScanner.Span span = spans.get(i);
            List<TraceEvent> calls = spanCalls.get(i);
            int callCount = calls != null ? calls.size() : 0;
            if (callCount == 0 && i != hoveredSpan && i != pinnedSpan) continue;

            int startRow = Math.max(span.startLine() - scrollRow, 0);
            int endRow = Math.min(span.endLine() - scrollRow, visibleRows);
            if (startRow >= visibleRows || endRow <= 0) continue;

            float[] color = BLOCK_COLORS[i % BLOCK_COLORS.length];
            float alpha;
            if (i == pinnedSpan) alpha = 0.18f;
            else if (i == hoveredSpan) alpha = 0.12f;
            else alpha = 0.05f + Math.min(callCount * 0.005f, 0.05f);

            float ry = textY + startRow * cellHeight + highlightY;
            float rh = (endRow - startRow) * cellHeight;
            font.drawRect(boundsX, ry, totalWidth, rh,
                    color[0] * alpha, color[1] * alpha, color[2] * alpha);

            // Call count badge
            if (callCount > 0) {
                String badge = callCount + "x";
                float badgeW = font.getTextWidth(badge, 0.20f);
                font.drawText(badge, boundsX + totalWidth - badgeW - 8f,
                        ry + cellHeight * 0.8f, 0.20f, color[0] * 0.7f, color[1] * 0.7f, color[2] * 0.7f);
            }
        }
    }

    /** Summary panel: table of all traced blocks (default view). */
    private void renderSummaryPanel(float x, float y, float width, float height, float panelH) {
        if (tracedSpanIndices.isEmpty()) return;
        SdfFontRenderer font = window.getFont();

        float panelY = panelTop;
        float lineH = font.getLineHeight(0.26f) * 1.3f;

        font.drawRect(x, panelY, width, panelH, 0.08f, 0.08f, 0.10f);
        font.drawRect(x, panelY, width, 1f, 0.3f, 0.3f, 0.4f);

        float ty = panelY + 6f;
        panelRowHeight = lineH;

        font.drawText("Traced Blocks (" + tracedSpanIndices.size() + ") — click to inspect",
                x + 10f, ty + font.getLineHeight(0.24f), 0.24f, 0.6f, 0.6f, 0.7f);
        ty += font.getLineHeight(0.24f) * 1.6f;
        panelRowStart = ty;

        int maxRows = (int) ((panelY + panelH - ty) / lineH);
        for (int i = 0; i < tracedSpanIndices.size() && i < maxRows; i++) {
            int spanIdx = tracedSpanIndices.get(i);
            DeclarationScanner.Span span = spans.get(spanIdx);
            String name = extractDeclName(span);
            if (name == null) name = span.kind();
            int callCount = spanCalls.get(spanIdx).size();
            float[] color = BLOCK_COLORS[spanIdx % BLOCK_COLORS.length];

            float ry = ty + i * lineH;
            float rowTextY = ry + lineH - 3f;

            // Unified highlight — keyboard or mouse, one at a time
            if (i == summarySelected) {
                font.drawRect(x, ry, width, lineH, 0.18f, 0.22f, 0.32f);
            }

            // Color dot
            font.drawRect(x + 10f, ry + lineH * 0.3f, 8f, 8f, color[0], color[1], color[2]);

            // Name and call count
            font.drawText(name, x + 26f, rowTextY, 0.26f, 0.80f, 0.80f, 0.85f);
            String countStr = callCount + " calls";
            float countW = font.getTextWidth(countStr, 0.22f);
            font.drawText(countStr, x + width - countW - 10f, rowTextY, 0.22f,
                    0.55f, 0.55f, 0.60f);
        }
    }

    /** Invocation panel: detailed table for the pinned block. */
    private void renderInvocationPanel(float x, float y, float width, float height, float panelH) {
        SdfFontRenderer font = window.getFont();
        List<TraceEvent> calls = spanCalls.get(pinnedSpan);
        List<TraceEvent> returns = spanReturns.get(pinnedSpan);
        if (calls == null || calls.isEmpty()) return;

        float panelY = panelTop;
        float lineH = font.getLineHeight(0.24f) * 1.3f;

        font.drawRect(x, panelY, width, panelH, 0.08f, 0.08f, 0.10f);
        font.drawRect(x, panelY, width, 1f, 0.3f, 0.3f, 0.4f);

        float ty = panelY + 6f;
        String title = extractDeclName(spans.get(pinnedSpan));
        if (title == null) title = spans.get(pinnedSpan).kind();
        font.drawText(title + " — " + calls.size() + " invocations",
                x + 10f, ty + font.getLineHeight(0.26f), 0.26f, 0.7f, 0.7f, 0.8f);
        ty += font.getLineHeight(0.26f) * 1.6f;

        // Column headers
        float colInputs = x + 40f;
        float colLocals = x + width * 0.35f;
        float colOutput = x + width * 0.60f;
        float colTime = x + width - 80f;
        font.drawText("#", x + 10f, ty + font.getLineHeight(0.22f), 0.22f, 0.5f, 0.5f, 0.55f);
        font.drawText("Inputs", colInputs, ty + font.getLineHeight(0.22f), 0.22f, 0.5f, 0.5f, 0.55f);
        font.drawText("Locals", colLocals, ty + font.getLineHeight(0.22f), 0.22f, 0.5f, 0.5f, 0.55f);
        font.drawText("Output", colOutput, ty + font.getLineHeight(0.22f), 0.22f, 0.5f, 0.5f, 0.55f);
        font.drawText("Time", colTime, ty + font.getLineHeight(0.22f), 0.22f, 0.5f, 0.5f, 0.55f);
        ty += lineH;

        int maxRows = (int) ((panelY + panelH - ty) / lineH);
        tableScroll.setMax(Math.max(0, calls.size() - maxRows));
        int startRow = tableScroll.get();
        for (int i = startRow; i < calls.size() && i - startRow < maxRows; i++) {
            TraceEvent call = calls.get(i);
            TraceEvent ret = i < (returns != null ? returns.size() : 0) ? returns.get(i) : null;
            float ry = ty + (i - startRow) * lineH;
            float rowTextY = ry + lineH - 3f;

            if ((i - startRow) % 2 == 1)
                font.drawRect(x, ry, width, lineH, 0.12f, 0.12f, 0.14f);

            font.drawText(String.valueOf(i + 1), x + 10f, rowTextY, 0.22f, 0.4f, 0.4f, 0.45f);
            font.drawText(call.inputsSummary(), colInputs, rowTextY, 0.24f, 0.75f, 0.75f, 0.78f);

            // Local variables assigned during this call
            List<TraceEvent> assigns = callAssigns.get(call.sequence());
            if (assigns != null && !assigns.isEmpty()) {
                StringBuilder locals = new StringBuilder();
                for (TraceEvent a : assigns) {
                    if (!locals.isEmpty()) locals.append(", ");
                    locals.append(a.location()).append("=").append(
                            a.output() != null ? TraceEvent.summarizeValue(a.output()) : "null");
                }
                font.drawText(locals.toString(), colLocals, rowTextY, 0.22f, 0.65f, 0.60f, 0.80f);
            }

            if (ret != null) {
                if (ret.kind() == TraceEvent.Kind.RETURN) {
                    font.drawText(ret.outputSummary(), colOutput, rowTextY, 0.24f, 0.6f, 0.8f, 0.6f);
                } else {
                    font.drawText(ret.error(), colOutput, rowTextY, 0.24f, 0.9f, 0.3f, 0.3f);
                }
                String dur = formatDuration(ret.durationNanos());
                float durW = font.getTextWidth(dur, 0.22f);
                font.drawText(dur, x + width - durW - 10f, rowTextY, 0.22f, 0.5f, 0.45f, 0.35f);
            }
        }
    }

    // ── Input ──────────────────────────────────────────────────────────

    private boolean lastInputWasKeyboard; // true = arrow keys drive highlight, false = mouse

    @Override
    public boolean onKey(int key, int scancode, int action, int mods) {
        if (action != GLFW_PRESS && action != GLFW_REPEAT) return false;

        // Escape: unpin or close tab
        if (key == GLFW_KEY_ESCAPE) {
            if (pinnedSpan >= 0) {
                pinnedSpan = -1;
                tableScroll.reset();
                return true;
            }
            return false; // let TabViewMode close the tab
        }

        if (pinnedSpan >= 0) {
            // Scroll invocation table
            if (key == GLFW_KEY_DOWN) { tableScroll.set(tableScroll.get() + 1); return true; }
            if (key == GLFW_KEY_UP) { tableScroll.set(tableScroll.get() - 1); return true; }
            if (key == GLFW_KEY_PAGE_DOWN) { tableScroll.set(tableScroll.get() + 10); return true; }
            if (key == GLFW_KEY_PAGE_UP) { tableScroll.set(tableScroll.get() - 10); return true; }
        } else {
            // Navigate summary table with arrow keys
            if (key == GLFW_KEY_DOWN && summarySelected < tracedSpanIndices.size() - 1) {
                summarySelected++;
                lastInputWasKeyboard = true;
                return true;
            }
            if (key == GLFW_KEY_UP && summarySelected > 0) {
                summarySelected--;
                lastInputWasKeyboard = true;
                return true;
            }
            if (key == GLFW_KEY_ENTER && summarySelected < tracedSpanIndices.size()) {
                pinSpanAndScroll(tracedSpanIndices.get(summarySelected));
                return true;
            }
        }

        // Copy
        if ((mods & GLFW_MOD_CONTROL) != 0 && key == GLFW_KEY_C) {
            textArea.onKey(key, mods);
            return true;
        }
        return true;
    }

    @Override
    public boolean onChar(int codepoint) { return true; }

    @Override
    public boolean onMouseButton(int button, int action, int mods, double mx, double my) {
        if (button == GLFW_MOUSE_BUTTON_LEFT && action == GLFW_PRESS) {
            // Panel area clicks
            if (my > panelTop && panelRowHeight > 0) {
                if (pinnedSpan < 0 && my > panelRowStart) {
                    int row = (int) ((my - panelRowStart) / panelRowHeight);
                    if (row >= 0 && row < tracedSpanIndices.size()) {
                        pinSpanAndScroll(tracedSpanIndices.get(row));
                    }
                }
                return true;
            }

            // Source code area clicks
            int row = sourceRowAtY(my);
            if (row >= 0) {
                int span = findSpanForRow(row);
                if (span >= 0 && spanCalls.containsKey(span)) {
                    pinSpanAndScroll(span);
                } else {
                    pinnedSpan = -1;
                    tableScroll.reset();
                }
            }
            return true; // always consume clicks — don't pass to text area
        }
        return true; // consume all mouse events
    }

    @Override
    public boolean onCursorPos(double mx, double my) {
        lastMouseY = my;
        // Track hover on summary panel rows
        if (my > panelTop && pinnedSpan < 0 && panelRowHeight > 0 && my > panelRowStart) {
            int row = (int) ((my - panelRowStart) / panelRowHeight);
            if (row >= 0 && row < tracedSpanIndices.size()) {
                summaryHovered = row;
                summarySelected = row; // unify hover and selection
                lastInputWasKeyboard = false;
            } else {
                summaryHovered = -1;
            }
        } else {
            summaryHovered = -1;
        }
        // Track hover on source code blocks
        int row = sourceRowAtY(my);
        hoveredSpan = row >= 0 ? findSpanForRow(row) : -1;
        return true;
    }

    @Override
    public boolean onScroll(double xoff, double yoff) {
        // Scroll whichever area the cursor is hovering over
        if (lastMouseY > panelTop) {
            tableScroll.onScroll(yoff);
        } else {
            textArea.onScroll(xoff, yoff);
        }
        return true;
    }

    @Override
    public String hudText() {
        if (pinnedSpan >= 0) {
            String name = extractDeclName(spans.get(pinnedSpan));
            List<TraceEvent> calls = spanCalls.get(pinnedSpan);
            return (name != null ? name : "block") + ": "
                    + (calls != null ? calls.size() : 0)
                    + " invocations | Up/Down Scroll | Esc Unpin";
        }
        return tracedSpanIndices.size() + " traced blocks | Click or Enter to inspect | Shift+F5 Re-trace";
    }

    // ── Helpers ─────────────────────────────────────────────────────

    private void pinSpanAndScroll(int spanIdx) {
        pinnedSpan = spanIdx;
        tableScroll.reset();
        // Scroll source to show the pinned block
        DeclarationScanner.Span span = spans.get(spanIdx);
        textArea.setScrollRow(Math.max(0, span.startLine() - 2));
    }

    private int findSpanForRow(int row) {
        for (int i = 0; i < spans.size(); i++) {
            DeclarationScanner.Span s = spans.get(i);
            if (row >= s.startLine() && row < s.endLine()) return i;
        }
        return -1;
    }

    private int sourceRowAtY(double my) {
        float cellHeight = window.getFont().getLineHeight(textArea.getFontScale()) * 1.2f;
        float textY = textArea.getBoundsY() + 10f; // PAD
        if (my < textY) return -1;
        return (int) ((my - textY) / cellHeight) + textArea.getScrollRow();
    }

    private static String formatDuration(long nanos) {
        if (nanos < 1_000) return nanos + "ns";
        if (nanos < 1_000_000) return String.format("%.1fus", nanos / 1_000.0);
        if (nanos < 1_000_000_000) return String.format("%.1fms", nanos / 1_000_000.0);
        return String.format("%.2fs", nanos / 1_000_000_000.0);
    }
}
