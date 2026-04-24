package spn.gui;

import spn.fonts.SdfFontRenderer;
import spn.lang.DeclarationScanner;
import spn.trace.TraceEvent;

import java.util.*;

import static org.lwjgl.glfw.GLFW.*;

/**
 * Singleton trace tab with multi-file support.
 *
 * If multiple files have traces, opens in FILE_SELECT mode where the user
 * picks a source file to inspect. Then shows source code with traced blocks
 * highlighted. Single-file traces skip straight to the block summary.
 *
 * State machine: FILE_SELECT → SUMMARY → INVOCATIONS → (detail in source area)
 * Esc chain: detail → INVOCATIONS → SUMMARY → FILE_SELECT (multi-file) → close tab
 */
public class TraceSourceTab extends ScrollableTab {

    // ── Per-file trace data ─────────────────────────────────────────────

    /** Holds all trace-mapped data for a single source file. */
    static class TraceFile {
        final String source;
        final String filePath;
        final String label;
        final List<DeclarationScanner.Span> spans;
        final List<Integer> tracedSpanIndices = new ArrayList<>();
        final Map<Integer, List<TraceEvent>> spanCalls = new LinkedHashMap<>();
        final Map<Integer, List<TraceEvent>> spanReturns = new LinkedHashMap<>();
        final Map<Long, List<TraceEvent>> callAssigns = new LinkedHashMap<>();
        int maxCallCount = 1;

        TraceFile(String source, String filePath, String label) {
            this.source = source;
            this.filePath = filePath;
            this.label = label;
            this.spans = DeclarationScanner.scan(source);
        }

        int totalCalls() {
            int total = 0;
            for (List<TraceEvent> calls : spanCalls.values()) total += calls.size();
            return total;
        }
    }

    /** Input entry for constructing a multi-file trace tab. */
    record FileEntry(String source, String filePath, String label) {}

    // ── Instance state ──────────────────────────────────────────────────

    private final List<TraceEvent> events;
    private final List<TraceFile> files;
    private int activeFileIndex = -1;   // -1 when in FILE_SELECT (no file loaded yet)

    private int hoveredSpan = -1;
    private int pinnedSpan = -1;

    // ── Panel state machine ──────────────────────────────────────────────
    private enum PanelState { FILE_SELECT, SUMMARY, INVOCATIONS }
    private PanelState panelState;

    // Shared selection/scroll for whichever list is active
    private final ListScroll tableScroll = new ListScroll();
    private int listSelected = 0;
    private int listHovered = -1;

    // Detail view: read-only TextArea showing full invocation info
    private final TextArea detailArea;
    private int detailCallIndex = -1;

    // Panel layout — set during render, used for click hit-testing and scroll routing
    private float panelTop;
    private float panelRowStart;
    private float panelRowHeight;
    private double lastMouseY;

    // ── Constructor ──────────────────────────────────────────────────────

    /**
     * Create a trace tab for one or more source files.
     *
     * @param window  the editor window
     * @param events  all captured trace events (shared across files)
     * @param entries file entries to display (source, path, label)
     */
    TraceSourceTab(EditorWindow window, List<TraceEvent> events, List<FileEntry> entries) {
        super(window);
        this.events = events;

        files = new ArrayList<>(entries.size());
        for (FileEntry entry : entries) {
            TraceFile tf = new TraceFile(entry.source(), entry.filePath(), entry.label());
            mapEventsToSpans(tf);
            files.add(tf);
        }

        textArea.setPreTextRenderer(this::renderBlockOverlays);

        detailArea = new TextArea(window.getFont());
        detailArea.setClipboard(new TextArea.ClipboardHandler() {
            @Override public void set(String text) {
                org.lwjgl.glfw.GLFW.glfwSetClipboardString(window.getHandle(), text);
            }
            @Override public String get() {
                return org.lwjgl.glfw.GLFW.glfwGetClipboardString(window.getHandle());
            }
        });

        // Single file: skip FILE_SELECT, go straight to SUMMARY
        if (files.size() == 1) {
            switchToFile(0);
        } else {
            panelState = PanelState.FILE_SELECT;
        }
    }

    // ── File switching ──────────────────────────────────────────────────

    private TraceFile active() { return files.get(activeFileIndex); }

    private void switchToFile(int index) {
        activeFileIndex = index;
        textArea.setText(files.get(index).source);
        textArea.setScrollRow(0);
        panelState = PanelState.SUMMARY;
        pinnedSpan = -1;
        hoveredSpan = -1;
        detailCallIndex = -1;
        listSelected = 0;
        listHovered = -1;
        tableScroll.reset();
    }

    private void returnToFileSelect() {
        int prevFile = activeFileIndex;
        activeFileIndex = -1;
        panelState = PanelState.FILE_SELECT;
        pinnedSpan = -1;
        hoveredSpan = -1;
        detailCallIndex = -1;
        listSelected = prevFile;   // pre-select the file we were just viewing
        listHovered = -1;
        tableScroll.reset();
    }

    // ── Event mapping (per-file) ────────────────────────────────────────

    private void mapEventsToSpans(TraceFile tf) {
        Map<String, Integer> nameToSpan = new HashMap<>();
        for (int i = 0; i < tf.spans.size(); i++) {
            String name = extractDeclName(tf.spans.get(i));
            if (name != null) nameToSpan.put(name, i);
        }
        Set<Long> callsInThisFile = new HashSet<>();
        for (TraceEvent e : events) {
            if (e.kind() != TraceEvent.Kind.CALL) continue;
            if (!matchesFile(e, tf.filePath)) continue;
            callsInThisFile.add(e.sequence());
        }
        for (TraceEvent e : events) {
            if (e.kind() == TraceEvent.Kind.ASSIGN) {
                if (!callsInThisFile.contains(e.parentSequence())) continue;
                tf.callAssigns.computeIfAbsent(e.parentSequence(), k -> new ArrayList<>()).add(e);
                continue;
            }
            if (!matchesFile(e, tf.filePath)) continue;
            Integer idx = nameToSpan.get(e.location());
            if (idx == null) continue;
            if (e.kind() == TraceEvent.Kind.CALL)
                tf.spanCalls.computeIfAbsent(idx, k -> new ArrayList<>()).add(e);
            else if (e.kind() == TraceEvent.Kind.RETURN)
                tf.spanReturns.computeIfAbsent(idx, k -> new ArrayList<>()).add(e);
        }
        tf.tracedSpanIndices.addAll(tf.spanCalls.keySet());
        tf.tracedSpanIndices.sort((a, b) -> tf.spanCalls.get(b).size() - tf.spanCalls.get(a).size());
        for (List<TraceEvent> calls : tf.spanCalls.values()) {
            if (calls.size() > tf.maxCallCount) tf.maxCallCount = calls.size();
        }
    }

    private static boolean matchesFile(TraceEvent e, String filePath) {
        if (filePath == null) return true;
        if (e.sourceFile() == null) return false;
        return e.sourceFile().equals(filePath);
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
    public String label() {
        if (activeFileIndex >= 0) return "Trace: " + active().label;
        return "Trace";
    }

    @Override
    public boolean isDirty() { return false; }

    @Override
    public void render(float x, float y, float width, float height) {
        if (panelState == PanelState.FILE_SELECT) {
            renderFileSelectView(x, y, width, height);
            return;
        }

        float hudH = window.getHudHeight();
        TraceFile tf = active();

        // Compute panel height first so the TextArea shrinks to fit above it
        float panelH;
        if (pinnedSpan >= 0) {
            panelH = height * 0.30f;
        } else if (!tf.tracedSpanIndices.isEmpty()) {
            panelH = Math.min(height * 0.30f, tf.tracedSpanIndices.size() * 25f + 40f);
        } else {
            panelH = 0;
        }

        float sourceH = height - panelH;
        panelTop = y + sourceH;

        // Source area: either source code with overlays, or detail TextArea
        if (detailCallIndex >= 0) {
            // Detail view replaces source area — panel stays on invocation list
            detailArea.setBounds(x, y, width - ScrollableTab.SCROLLBAR_SIZE, sourceH - hudH);
            detailArea.render();
        } else {
            layoutAndRender(x, y, width, sourceH);
            textArea.setExtraScrollPadding(0);
        }

        // Bottom panel: summary or invocations (always visible)
        switch (panelState) {
            case SUMMARY -> renderSummaryPanel(x, y, width, height, panelH);
            case INVOCATIONS -> renderInvocationPanel(x, y, width, height, panelH);
        }
    }

    // ── FILE_SELECT view (full-area file list) ──────────────────────────

    private void renderFileSelectView(float x, float y, float width, float height) {
        SdfFontRenderer font = window.getFont();
        float lineH = font.getLineHeight(0.28f) * 1.5f;

        // Background
        font.drawRect(x, y, width, height, 0.08f, 0.08f, 0.10f);

        float ty = y + 20f;

        // Title
        int totalEvents = events.size();
        String title = "Trace Results \u2014 " + files.size() + " files, " + totalEvents + " events";
        font.drawText(title, x + 20f, ty + font.getLineHeight(0.30f), 0.30f, 0.7f, 0.7f, 0.8f);
        ty += font.getLineHeight(0.30f) * 2.0f;

        // Column headers
        float colCalls = x + width * 0.55f;
        float colBlocks = x + width * 0.75f;
        font.drawText("File", x + 20f, ty + font.getLineHeight(0.22f), 0.22f, 0.5f, 0.5f, 0.55f);
        font.drawText("Calls", colCalls, ty + font.getLineHeight(0.22f), 0.22f, 0.5f, 0.5f, 0.55f);
        font.drawText("Blocks", colBlocks, ty + font.getLineHeight(0.22f), 0.22f, 0.5f, 0.5f, 0.55f);
        ty += lineH;
        font.drawRect(x + 10f, ty - 2f, width - 20f, 1f, 0.25f, 0.25f, 0.30f);

        panelRowStart = ty;
        panelRowHeight = lineH;
        panelTop = y;   // entire area is the "panel" in FILE_SELECT

        int maxRows = (int) ((y + height - ty) / lineH);
        tableScroll.setMax(Math.max(0, files.size() - maxRows));
        int startRow = tableScroll.get();

        for (int i = startRow; i < files.size() && i - startRow < maxRows; i++) {
            TraceFile tf = files.get(i);
            float ry = ty + (i - startRow) * lineH;
            float rowTextY = ry + lineH - 6f;

            if (i == listSelected) {
                font.drawRect(x, ry, width, lineH, 0.18f, 0.22f, 0.32f);
            } else if (i == listHovered) {
                font.drawRect(x, ry, width, lineH, 0.12f, 0.13f, 0.18f);
            } else if ((i - startRow) % 2 == 1) {
                font.drawRect(x, ry, width, lineH, 0.10f, 0.10f, 0.12f);
            }

            font.drawText(tf.label, x + 20f, rowTextY, 0.28f, 0.80f, 0.80f, 0.85f);

            int calls = tf.totalCalls();
            font.drawText(String.valueOf(calls), colCalls, rowTextY, 0.26f, 0.60f, 0.75f, 0.60f);
            font.drawText(String.valueOf(tf.tracedSpanIndices.size()), colBlocks, rowTextY,
                    0.26f, 0.60f, 0.60f, 0.75f);
        }
    }

    // ── Block overlay rendering ─────────────────────────────────────────

    /** Pre-text renderer callback — draws block highlights inside TextArea's render pipeline.
     *
     * Color encodes call frequency (heat map):
     *   blue (low) → green (medium) → red (high)
     * Brightness encodes interaction state:
     *   base (has data) → brighter on hover → brightest when pinned
     * Blocks with no recorded calls are not highlighted at all.
     */
    private void renderBlockOverlays(SdfFontRenderer font, float textX, float textY,
                                      float cellWidth, float cellHeight, float highlightY,
                                      int scrollRow, int visibleRows,
                                      float boundsX, float totalWidth) {
        if (activeFileIndex < 0) return;   // no file loaded (FILE_SELECT)
        TraceFile tf = active();

        for (int i = 0; i < tf.spans.size(); i++) {
            DeclarationScanner.Span span = tf.spans.get(i);
            List<TraceEvent> calls = tf.spanCalls.get(i);
            int callCount = calls != null ? calls.size() : 0;
            if (callCount == 0) continue;

            int startRow = Math.max(span.startLine() - scrollRow, 0);
            int endRow = Math.min(span.endLine() - scrollRow, visibleRows);
            if (startRow >= visibleRows || endRow <= 0) continue;

            float[] color = heatColor(callCount, tf.maxCallCount);

            // Brightness: base → hover (darker) → pinned (darkest)
            float alpha;
            if (i == pinnedSpan) alpha = 0.22f;
            else if (i == hoveredSpan) alpha = 0.15f;
            else if (callCount > 0) alpha = 0.08f;
            else continue;

            float ry = textY + startRow * cellHeight + highlightY;
            float rh = (endRow - startRow) * cellHeight;
            font.drawRect(boundsX, ry, totalWidth, rh,
                    color[0] * alpha, color[1] * alpha, color[2] * alpha);

            // Call count badge
            if (callCount > 0) {
                String badge = callCount + "x";
                float badgeW = font.getTextWidth(badge, 0.20f);
                font.drawText(badge, boundsX + totalWidth - badgeW - 8f,
                        ry + cellHeight * 0.8f, 0.20f,
                        color[0] * 0.7f, color[1] * 0.7f, color[2] * 0.7f);
            }
        }
    }

    // ── Summary panel ───────────────────────────────────────────────────

    /** Summary panel: table of all traced blocks (default view). */
    private void renderSummaryPanel(float x, float y, float width, float height, float panelH) {
        TraceFile tf = active();
        if (tf.tracedSpanIndices.isEmpty()) return;
        SdfFontRenderer font = window.getFont();

        float panelY = panelTop;
        float lineH = font.getLineHeight(0.26f) * 1.3f;

        font.drawRect(x, panelY, width, panelH, 0.08f, 0.08f, 0.10f);
        font.drawRect(x, panelY, width, 1f, 0.3f, 0.3f, 0.4f);

        float ty = panelY + 6f;
        panelRowHeight = lineH;

        font.drawText("Traced Blocks (" + tf.tracedSpanIndices.size() + ") \u2014 click to inspect",
                x + 10f, ty + font.getLineHeight(0.24f), 0.24f, 0.6f, 0.6f, 0.7f);
        ty += font.getLineHeight(0.24f) * 1.6f;
        panelRowStart = ty;

        int maxRows = (int) ((panelY + panelH - ty) / lineH);
        for (int i = 0; i < tf.tracedSpanIndices.size() && i < maxRows; i++) {
            int spanIdx = tf.tracedSpanIndices.get(i);
            DeclarationScanner.Span span = tf.spans.get(spanIdx);
            String name = extractDeclName(span);
            if (name == null) name = span.kind();
            int callCount = tf.spanCalls.get(spanIdx).size();
            float[] color = heatColor(callCount, tf.maxCallCount);

            float ry = ty + i * lineH;
            float rowTextY = ry + lineH - 3f;

            if (i == listSelected) {
                font.drawRect(x, ry, width, lineH, 0.18f, 0.22f, 0.32f);
            } else if (i == listHovered) {
                font.drawRect(x, ry, width, lineH, 0.12f, 0.13f, 0.18f);
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

    // ── Invocation panel ────────────────────────────────────────────────

    /** Invocation panel: simplified table with row selection. */
    private void renderInvocationPanel(float x, float y, float width, float height, float panelH) {
        TraceFile tf = active();
        SdfFontRenderer font = window.getFont();
        List<TraceEvent> calls = tf.spanCalls.get(pinnedSpan);
        List<TraceEvent> returns = tf.spanReturns.get(pinnedSpan);
        if (calls == null || calls.isEmpty()) return;

        float panelY = panelTop;
        float lineH = font.getLineHeight(0.24f) * 1.3f;

        font.drawRect(x, panelY, width, panelH, 0.08f, 0.08f, 0.10f);
        font.drawRect(x, panelY, width, 1f, 0.3f, 0.3f, 0.4f);

        float ty = panelY + 6f;
        String title = extractDeclName(tf.spans.get(pinnedSpan));
        if (title == null) title = tf.spans.get(pinnedSpan).kind();
        font.drawText(title + " \u2014 " + calls.size() + " invocations",
                x + 10f, ty + font.getLineHeight(0.26f), 0.26f, 0.7f, 0.7f, 0.8f);
        ty += font.getLineHeight(0.26f) * 1.6f;

        // Column headers: #, Inputs, Output, Duration
        float colInputs = x + 40f;
        float colOutput = x + width * 0.55f;
        float colTime = x + width - 80f;
        font.drawText("#", x + 10f, ty + font.getLineHeight(0.22f), 0.22f, 0.5f, 0.5f, 0.55f);
        font.drawText("Inputs", colInputs, ty + font.getLineHeight(0.22f), 0.22f, 0.5f, 0.5f, 0.55f);
        font.drawText("Output", colOutput, ty + font.getLineHeight(0.22f), 0.22f, 0.5f, 0.5f, 0.55f);
        font.drawText("Time", colTime, ty + font.getLineHeight(0.22f), 0.22f, 0.5f, 0.5f, 0.55f);
        ty += lineH;
        panelRowStart = ty;
        panelRowHeight = lineH;

        int maxRows = (int) ((panelY + panelH - ty) / lineH);
        tableScroll.setMax(Math.max(0, calls.size() - maxRows));
        int startRow = tableScroll.get();
        for (int i = startRow; i < calls.size() && i - startRow < maxRows; i++) {
            TraceEvent call = calls.get(i);
            TraceEvent ret = i < (returns != null ? returns.size() : 0) ? returns.get(i) : null;
            float ry = ty + (i - startRow) * lineH;
            float rowTextY = ry + lineH - 3f;

            if (i == listSelected) {
                font.drawRect(x, ry, width, lineH, 0.18f, 0.22f, 0.32f);
            } else if (i == listHovered) {
                font.drawRect(x, ry, width, lineH, 0.12f, 0.13f, 0.18f);
            } else if ((i - startRow) % 2 == 1) {
                font.drawRect(x, ry, width, lineH, 0.12f, 0.12f, 0.14f);
            }

            font.drawText(String.valueOf(i + 1), x + 10f, rowTextY, 0.22f, 0.4f, 0.4f, 0.45f);
            font.drawText(call.inputsSummary(), colInputs, rowTextY, 0.24f, 0.75f, 0.75f, 0.78f);

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

    // ── Detail builder ──────────────────────────────────────────────────

    /** Build a detail string for the selected invocation. */
    private String buildInvocationDetail(int callIndex) {
        TraceFile tf = active();
        List<TraceEvent> calls = tf.spanCalls.get(pinnedSpan);
        List<TraceEvent> returns = tf.spanReturns.get(pinnedSpan);
        if (calls == null || callIndex >= calls.size()) return "";
        TraceEvent call = calls.get(callIndex);
        TraceEvent ret = callIndex < (returns != null ? returns.size() : 0) ? returns.get(callIndex) : null;

        String declName = extractDeclName(tf.spans.get(pinnedSpan));
        if (declName == null) declName = tf.spans.get(pinnedSpan).kind();

        var sb = new StringBuilder();
        sb.append("\u2550\u2550\u2550 Invocation #").append(callIndex + 1).append(" of ").append(declName).append(" \u2550\u2550\u2550\n\n");

        // Inputs (full, not truncated)
        sb.append("\u2500\u2500 Inputs \u2500\u2500\n");
        if (call.inputs() != null) {
            for (int i = 0; i < call.inputs().length; i++) {
                Object val = call.inputs()[i];
                sb.append("  arg").append(i).append(" = ").append(val != null ? val.toString() : "null").append("\n");
            }
        } else {
            sb.append("  (none)\n");
        }

        // Output
        sb.append("\n\u2500\u2500 Output \u2500\u2500\n");
        if (ret != null) {
            if (ret.kind() == TraceEvent.Kind.RETURN) {
                sb.append("  ").append(ret.output() != null ? ret.output().toString() : "null").append("\n");
            } else {
                sb.append("  ERROR: ").append(ret.error()).append("\n");
            }
            sb.append("  Duration: ").append(formatDuration(ret.durationNanos())).append("\n");
        } else {
            sb.append("  (no return recorded)\n");
        }

        // Local variable assignments
        List<TraceEvent> assigns = tf.callAssigns.get(call.sequence());
        if (assigns != null && !assigns.isEmpty()) {
            sb.append("\n\u2500\u2500 Local Variables \u2500\u2500\n");
            for (TraceEvent a : assigns) {
                sb.append("  ").append(a.location()).append(" = ")
                  .append(a.output() != null ? a.output().toString() : "null").append("\n");
            }
        }

        // Call stack: walk the parent chain to show where this call happened.
        sb.append("\n\u2500\u2500 Call Stack \u2500\u2500\n");
        sb.append("  \u2192 ").append(call.location()).append(call.inputsSummary()).append("\n");
        if (call.sourceFile() != null)
            sb.append("      defined in ").append(shortenPath(call.sourceFile())).append("\n");
        long parentSeq = call.parentSequence();
        int depth = 0;
        while (parentSeq >= 0 && depth < 20) {
            TraceEvent parent = findCallEvent(parentSeq);
            if (parent == null) {
                sb.append("    called from [event #").append(parentSeq).append(" \u2014 not found]\n");
                break;
            }
            sb.append("    called from ").append(parent.location()).append(parent.inputsSummary()).append("\n");
            if (parent.sourceFile() != null) {
                sb.append("      defined in ").append(shortenPath(parent.sourceFile())).append("\n");
            }
            parentSeq = parent.parentSequence();
            depth++;
        }
        if (call.parentSequence() < 0) sb.append("    (called from top-level script)\n");

        sb.append("\n\u2500\u2500 Metadata \u2500\u2500\n");
        sb.append("  Defined in: ").append(call.sourceFile() != null ? shortenPath(call.sourceFile()) : "(unknown)").append("\n");
        sb.append("  Pure: ").append(call.pure()).append("\n");
        sb.append("  Event sequence: ").append(call.sequence()).append("\n");

        return sb.toString();
    }

    /** Shorten a file path to just the last 2-3 segments for readability. */
    private static String shortenPath(String path) {
        if (path == null) return "(unknown)";
        String normalized = path.replace('\\', '/');
        int lastSlash = normalized.lastIndexOf('/');
        if (lastSlash <= 0) return normalized;
        int secondLast = normalized.lastIndexOf('/', lastSlash - 1);
        return secondLast >= 0 ? normalized.substring(secondLast + 1) : normalized.substring(lastSlash + 1);
    }

    // Index: sequence → CALL event (built lazily for O(1) call-stack lookups)
    private Map<Long, TraceEvent> callEventIndex;

    /** Find a CALL event by sequence number (for call-stack reconstruction). */
    private TraceEvent findCallEvent(long sequence) {
        if (callEventIndex == null) {
            callEventIndex = new HashMap<>();
            for (TraceEvent e : events) {
                if (e.kind() == TraceEvent.Kind.CALL) callEventIndex.put(e.sequence(), e);
            }
        }
        return callEventIndex.get(sequence);
    }

    // ── Input ──────────────────────────────────────────────────────────

    /** Item count for the current list state. */
    private int listSize() {
        return switch (panelState) {
            case FILE_SELECT -> files.size();
            case SUMMARY -> active().tracedSpanIndices.size();
            case INVOCATIONS -> {
                List<TraceEvent> calls = active().spanCalls.get(pinnedSpan);
                yield calls != null ? calls.size() : 0;
            }
        };
    }

    @Override
    public boolean onKey(int key, int scancode, int action, int mods) {
        if (action != GLFW_PRESS && action != GLFW_REPEAT) return false;
        boolean ctrl = (mods & GLFW_MOD_CONTROL) != 0;

        // Global shortcuts available everywhere in trace mode
        if (ctrl && action == GLFW_PRESS) {
            if (key == GLFW_KEY_O) { window.openFile(); return true; }
            if (key == GLFW_KEY_G) { window.openLogTab(); return true; }
            if (key == GLFW_KEY_M) {
                ModuleContext ctx = window.findAnyModuleContext();
                if (ctx != null) window.pushLegacyMode(new ModuleMode(window, ctx));
                else window.flash("No module loaded — cannot find module.spn", true);
                return true;
            }
        }

        // Escape: back one level
        if (key == GLFW_KEY_ESCAPE) {
            if (detailCallIndex >= 0) {
                // Close detail → back to source view (panel stays on invocations)
                detailCallIndex = -1;
                return true;
            }
            if (panelState == PanelState.INVOCATIONS) {
                panelState = PanelState.SUMMARY;
                pinnedSpan = -1;
                detailCallIndex = -1;
                listSelected = 0;
                tableScroll.reset();
                return true;
            }
            if (panelState == PanelState.SUMMARY && files.size() > 1) {
                returnToFileSelect();
                return true;
            }
            return false; // FILE_SELECT or single-file SUMMARY: let TabViewMode close
        }

        // Ctrl+S: save detail (when viewing one)
        if (ctrl && key == GLFW_KEY_S && detailCallIndex >= 0) {
            saveDetail();
            return true;
        }

        // Ctrl+C / Ctrl+A: copy or select-all in whichever area is active
        if (ctrl && (key == GLFW_KEY_C || key == GLFW_KEY_A)) {
            if (detailCallIndex >= 0) {
                detailArea.onKey(key, mods);
            } else if (panelState != PanelState.FILE_SELECT) {
                textArea.onKey(key, mods);
            }
            return true;
        }

        // List navigation (shared by FILE_SELECT, SUMMARY, and INVOCATIONS)
        int prevSelected = listSelected;
        if (key == GLFW_KEY_DOWN && listSelected < listSize() - 1) {
            listSelected++;
        } else if (key == GLFW_KEY_UP && listSelected > 0) {
            listSelected--;
        } else if (key == GLFW_KEY_PAGE_DOWN) {
            listSelected = Math.min(listSelected + 10, Math.max(0, listSize() - 1));
        } else if (key == GLFW_KEY_PAGE_UP) {
            listSelected = Math.max(listSelected - 10, 0);
        }
        if (listSelected != prevSelected) {
            // If detail is open, update it to follow the selection
            if (detailCallIndex >= 0 && panelState == PanelState.INVOCATIONS) {
                openInvocationDetail(listSelected);
            }
            return true;
        }

        // Enter: activate selected item
        if (key == GLFW_KEY_ENTER) {
            if (panelState == PanelState.FILE_SELECT && listSelected < files.size()) {
                switchToFile(listSelected);
                return true;
            }
            if (panelState == PanelState.SUMMARY && listSelected < active().tracedSpanIndices.size()) {
                pinSpanAndScroll(active().tracedSpanIndices.get(listSelected));
                panelState = PanelState.INVOCATIONS;
                listSelected = 0;
                tableScroll.reset();
                return true;
            }
            if (panelState == PanelState.INVOCATIONS && listSelected < listSize()) {
                openInvocationDetail(listSelected);
                return true;
            }
        }

        return true;
    }

    @Override
    public boolean onChar(int codepoint) { return true; }

    @Override
    public boolean onMouseButton(int button, int action, int mods, double mx, double my) {
        // Always forward RELEASE to the detail text area so its drag state clears,
        // regardless of where the mouse was released.
        if (button == GLFW_MOUSE_BUTTON_LEFT && action == GLFW_RELEASE
                && detailCallIndex >= 0) {
            detailArea.onMouseButton(button, action, mods, mx, my);
            return true;
        }
        if (button == GLFW_MOUSE_BUTTON_LEFT && action == GLFW_PRESS) {
            // FILE_SELECT: only handle file list clicks
            if (panelState == PanelState.FILE_SELECT) {
                if (panelRowHeight > 0 && my > panelRowStart) {
                    int row = (int) ((my - panelRowStart) / panelRowHeight) + tableScroll.get();
                    if (row >= 0 && row < files.size()) {
                        listSelected = row;
                        switchToFile(row);
                    }
                }
                return true;
            }

            // Click in source/detail area
            if (my < panelTop) {
                if (detailCallIndex >= 0) {
                    detailArea.onMouseButton(button, action, mods, mx, my);
                } else if (hoveredSpan >= 0 && active().spanCalls.containsKey(hoveredSpan)) {
                    // Click on a traced block in source → pin it and show invocations
                    pinSpanAndScroll(hoveredSpan);
                    panelState = PanelState.INVOCATIONS;
                    listSelected = 0;
                    tableScroll.reset();
                }
                return true;
            }

            // Panel area clicks (summary or invocation list)
            if (panelRowHeight > 0 && my > panelRowStart) {
                int row = (int) ((my - panelRowStart) / panelRowHeight) + tableScroll.get();
                if (row >= 0 && row < listSize()) {
                    listSelected = row;
                    if (panelState == PanelState.SUMMARY) {
                        pinSpanAndScroll(active().tracedSpanIndices.get(row));
                        panelState = PanelState.INVOCATIONS;
                        listSelected = 0;
                        tableScroll.reset();
                    } else if (panelState == PanelState.INVOCATIONS) {
                        openInvocationDetail(row);
                    }
                }
            }
            return true;
        }
        return true;
    }

    @Override
    public boolean onCursorPos(double mx, double my) {
        lastMouseY = my;

        // FILE_SELECT: only track hover on file list
        if (panelState == PanelState.FILE_SELECT) {
            if (panelRowHeight > 0 && my > panelRowStart) {
                int row = (int) ((my - panelRowStart) / panelRowHeight) + tableScroll.get();
                listHovered = (row >= 0 && row < files.size()) ? row : -1;
            } else {
                listHovered = -1;
            }
            return true;
        }

        // Forward to detail area for text selection drag
        if (detailCallIndex >= 0 && my < panelTop) {
            detailArea.onCursorPos(mx, my);
        }
        // Track hover on panel rows (visual only — does not change selection)
        if (my > panelTop && panelRowHeight > 0 && my > panelRowStart) {
            int row = (int) ((my - panelRowStart) / panelRowHeight) + tableScroll.get();
            listHovered = (row >= 0 && row < listSize()) ? row : -1;
        } else {
            listHovered = -1;
        }
        // Track hover on source code blocks (only when source is showing)
        if (detailCallIndex < 0 && panelState != PanelState.FILE_SELECT) {
            int row = sourceRowAtY(my);
            hoveredSpan = row >= 0 ? findSpanForRow(row) : -1;
        }
        return true;
    }

    @Override
    public boolean onScroll(double xoff, double yoff) {
        if (panelState == PanelState.FILE_SELECT) {
            tableScroll.onScroll(yoff);
            return true;
        }
        if (lastMouseY > panelTop) {
            tableScroll.onScroll(yoff);
        } else if (detailCallIndex >= 0) {
            detailArea.onScroll(xoff, yoff);
        } else {
            textArea.onScroll(xoff, yoff);
        }
        return true;
    }

    @Override
    public String hudText() {
        if (detailCallIndex >= 0) {
            return "Invocation #" + (detailCallIndex + 1) + " | Ctrl+C Copy | Ctrl+S Save | Esc Source";
        }
        return switch (panelState) {
            case FILE_SELECT -> files.size() + " files | Enter Select | Esc Close";
            case SUMMARY -> {
                String hint = files.size() > 1
                        ? " | Enter Inspect | Esc Files | Shift+F5 Re-trace"
                        : " | Enter Inspect | Shift+F5 Re-trace";
                yield active().tracedSpanIndices.size() + " traced blocks" + hint;
            }
            case INVOCATIONS -> {
                TraceFile tf = active();
                String name = extractDeclName(tf.spans.get(pinnedSpan));
                List<TraceEvent> calls = tf.spanCalls.get(pinnedSpan);
                yield (name != null ? name : "block") + ": "
                        + (calls != null ? calls.size() : 0)
                        + " invocations | Enter Detail | Esc Back";
            }
        };
    }

    // ── Detail helpers ──────────────────────────────────────────────────

    private void openInvocationDetail(int callIndex) {
        detailCallIndex = callIndex;
        String detail = buildInvocationDetail(callIndex);
        detailArea.setText(detail);
    }

    private void saveDetail() {
        TraceFile tf = active();
        String declName = extractDeclName(tf.spans.get(pinnedSpan));
        if (declName == null) declName = "trace";
        declName = declName.replace("/", "_").replace(".", "_");
        long ts = System.currentTimeMillis();
        String filename = declName + "_" + (detailCallIndex + 1) + "-invocationTrace_" + ts + ".txt";

        String path = org.lwjgl.util.tinyfd.TinyFileDialogs.tinyfd_saveFileDialog(
                "Save Invocation Detail", filename, null, null);
        if (path != null) {
            try {
                java.nio.file.Files.writeString(java.nio.file.Path.of(path), detailArea.getText());
                window.flash("Saved: " + path, false);
            } catch (java.io.IOException e) {
                window.flash("Save failed: " + e.getMessage(), true);
            }
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────

    /** Heat-map color: blue (low calls) → green (medium) → red (high). */
    private static float[] heatColor(int callCount, int maxCalls) {
        float t = maxCalls > 0 ? (float) callCount / maxCalls : 0f;
        float r, g, b;
        if (t < 0.5f) {
            float s = t * 2f;
            r = 0.2f * (1f - s);
            g = 0.3f + 0.5f * s;
            b = 0.8f * (1f - s) + 0.2f * s;
        } else {
            float s = (t - 0.5f) * 2f;
            r = 0.3f + 0.6f * s;
            g = 0.8f * (1f - s) + 0.2f * s;
            b = 0.2f * (1f - s);
        }
        return new float[]{r, g, b};
    }

    private void pinSpanAndScroll(int spanIdx) {
        pinnedSpan = spanIdx;
        tableScroll.reset();
        // Scroll source to show the pinned block
        DeclarationScanner.Span span = active().spans.get(spanIdx);
        textArea.setScrollRow(Math.max(0, span.startLine() - 2));
    }

    private int findSpanForRow(int row) {
        TraceFile tf = active();
        for (int i = 0; i < tf.spans.size(); i++) {
            DeclarationScanner.Span s = tf.spans.get(i);
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
