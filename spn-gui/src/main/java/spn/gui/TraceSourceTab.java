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

    // ── Panel state machine ──────────────────────────────────────────────
    // SUMMARY → (Enter/click) → INVOCATIONS
    // In INVOCATIONS, clicking a row opens its detail in the SOURCE AREA
    // (the panel stays on the invocation list for easy navigation).
    // Esc from detail → back to source view. Esc from invocations → summary.
    private enum PanelState { SUMMARY, INVOCATIONS }
    private PanelState panelState = PanelState.SUMMARY;

    // Shared selection model for both summary and invocation lists
    private final ListScroll tableScroll = new ListScroll();
    private int listSelected = 0;
    private int listHovered = -1;

    // Detail view: read-only TextArea showing full invocation info
    private final TextArea detailArea;
    private int detailCallIndex = -1;     // which invocation is being detailed

    // Panel layout — set during render, used for click hit-testing and scroll routing
    private float panelTop;
    private float panelRowStart;
    private float panelRowHeight;
    private double lastMouseY;

    /** File path used to filter events to those originating in this file, or null to accept all. */
    private final String sourceFilePath;

    TraceSourceTab(EditorWindow window, String source, List<TraceEvent> events, String fileName) {
        this(window, source, events, fileName, null);
    }

    /**
     * @param sourceFilePath absolute path of the file shown in this tab. When non-null,
     *                       only events whose sourceFile matches are mapped to spans.
     *                       When null, all events are considered (legacy single-file mode).
     */
    TraceSourceTab(EditorWindow window, String source, List<TraceEvent> events,
                   String fileName, String sourceFilePath) {
        super(window);
        this.fileName = fileName;
        this.source = source;
        this.events = events;
        this.sourceFilePath = sourceFilePath;

        textArea.setText(source);
        spans = DeclarationScanner.scan(source);
        mapEventsToSpans();

        // Install pre-text renderer for block highlights
        textArea.setPreTextRenderer(this::renderBlockOverlays);

        // Detail area: read-only text for invocation inspection
        detailArea = new TextArea(window.getFont());
        detailArea.setClipboard(new TextArea.ClipboardHandler() {
            @Override public void set(String text) {
                org.lwjgl.glfw.GLFW.glfwSetClipboardString(window.getHandle(), text);
            }
            @Override public String get() {
                return org.lwjgl.glfw.GLFW.glfwGetClipboardString(window.getHandle());
            }
        });
    }

    /** True if at least one event in this tab's event list is attributable to this file. */
    public boolean hasTraces() {
        return !tracedSpanIndices.isEmpty();
    }

    private void mapEventsToSpans() {
        Map<String, Integer> nameToSpan = new HashMap<>();
        for (int i = 0; i < spans.size(); i++) {
            String name = extractDeclName(spans.get(i));
            if (name != null) nameToSpan.put(name, i);
        }
        // First pass: gather the set of CALL sequence IDs that belong to THIS file
        // (by matching sourceFile). ASSIGN events are attributed via parentSequence,
        // so they stay with the file where the enclosing call was defined.
        java.util.Set<Long> callsInThisFile = new java.util.HashSet<>();
        for (TraceEvent e : events) {
            if (e.kind() != TraceEvent.Kind.CALL) continue;
            if (!matchesThisFile(e)) continue;
            callsInThisFile.add(e.sequence());
        }
        for (TraceEvent e : events) {
            if (e.kind() == TraceEvent.Kind.ASSIGN) {
                // Only attach ASSIGN to a parent CALL that belongs to this file
                if (!callsInThisFile.contains(e.parentSequence())) continue;
                callAssigns.computeIfAbsent(e.parentSequence(), k -> new ArrayList<>()).add(e);
                continue;
            }
            // CALL / RETURN / ERROR: must be attributable to this file
            if (!matchesThisFile(e)) continue;
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
        // Cache max call count for heat-map normalization
        for (List<TraceEvent> calls : spanCalls.values()) {
            if (calls.size() > maxCallCount) maxCallCount = calls.size();
        }
    }

    /** True if the event's source file matches this tab's file (or no filter set). */
    private boolean matchesThisFile(TraceEvent e) {
        if (sourceFilePath == null) return true;         // legacy / single-file mode
        if (e.sourceFile() == null) return false;        // event has no file attribution
        return e.sourceFile().equals(sourceFilePath);
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
        for (int i = 0; i < spans.size(); i++) {
            DeclarationScanner.Span span = spans.get(i);
            List<TraceEvent> calls = spanCalls.get(i);
            int callCount = calls != null ? calls.size() : 0;
            if (callCount == 0 && i != hoveredSpan) continue;

            int startRow = Math.max(span.startLine() - scrollRow, 0);
            int endRow = Math.min(span.endLine() - scrollRow, visibleRows);
            if (startRow >= visibleRows || endRow <= 0) continue;

            float[] color = heatColor(callCount, maxCallCount);

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
            float[] color = heatColor(callCount, maxCallCount);

            float ry = ty + i * lineH;
            float rowTextY = ry + lineH - 3f;

            // Unified highlight — keyboard or mouse, one at a time
            if (i == listSelected) {
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

    /** Invocation panel: simplified table with row selection. */
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

            // Selection highlight
            if (i == listSelected) {
                font.drawRect(x, ry, width, lineH, 0.18f, 0.22f, 0.32f);
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

    /** Build a detail string for the selected invocation. */
    private String buildInvocationDetail(int callIndex) {
        List<TraceEvent> calls = spanCalls.get(pinnedSpan);
        List<TraceEvent> returns = spanReturns.get(pinnedSpan);
        if (calls == null || callIndex >= calls.size()) return "";
        TraceEvent call = calls.get(callIndex);
        TraceEvent ret = callIndex < (returns != null ? returns.size() : 0) ? returns.get(callIndex) : null;

        String declName = extractDeclName(spans.get(pinnedSpan));
        if (declName == null) declName = spans.get(pinnedSpan).kind();

        var sb = new StringBuilder();
        sb.append("═══ Invocation #").append(callIndex + 1).append(" of ").append(declName).append(" ═══\n\n");

        // Inputs (full, not truncated)
        sb.append("── Inputs ──\n");
        if (call.inputs() != null) {
            for (int i = 0; i < call.inputs().length; i++) {
                Object val = call.inputs()[i];
                sb.append("  arg").append(i).append(" = ").append(val != null ? val.toString() : "null").append("\n");
            }
        } else {
            sb.append("  (none)\n");
        }

        // Output
        sb.append("\n── Output ──\n");
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
        List<TraceEvent> assigns = callAssigns.get(call.sequence());
        if (assigns != null && !assigns.isEmpty()) {
            sb.append("\n── Local Variables ──\n");
            for (TraceEvent a : assigns) {
                sb.append("  ").append(a.location()).append(" = ")
                  .append(a.output() != null ? a.output().toString() : "null").append("\n");
            }
        }

        // Call stack: walk the parent chain to show where this call happened.
        sb.append("\n── Call Stack ──\n");
        // Frame 0: this call itself
        sb.append("  → ").append(call.location()).append(call.inputsSummary()).append("\n");
        if (call.sourceFile() != null)
            sb.append("      defined in ").append(shortenPath(call.sourceFile())).append("\n");
        // Caller chain
        long parentSeq = call.parentSequence();
        int depth = 0;
        while (parentSeq >= 0 && depth < 20) {
            TraceEvent parent = findCallEvent(parentSeq);
            if (parent == null) {
                sb.append("    called from [event #").append(parentSeq).append(" — not found]\n");
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

        sb.append("\n── Metadata ──\n");
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

    /** Item count for the current list state (summary or invocations). */
    private int listSize() {
        return switch (panelState) {
            case SUMMARY -> tracedSpanIndices.size();
            case INVOCATIONS -> {
                List<TraceEvent> calls = spanCalls.get(pinnedSpan);
                yield calls != null ? calls.size() : 0;
            }
            default -> 0;
        };
    }

    @Override
    public boolean onKey(int key, int scancode, int action, int mods) {
        if (action != GLFW_PRESS && action != GLFW_REPEAT) return false;
        boolean ctrl = (mods & GLFW_MOD_CONTROL) != 0;

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
            return false; // let TabViewMode close
        }

        // Ctrl+S: save detail (when viewing one)
        if (ctrl && key == GLFW_KEY_S && detailCallIndex >= 0) {
            saveDetail();
            return true;
        }

        // Ctrl+C: copy from whichever area is active
        if (ctrl && key == GLFW_KEY_C) {
            if (detailCallIndex >= 0) {
                detailArea.onKey(key, mods);
            } else {
                textArea.onKey(key, mods);
            }
            return true;
        }

        // List navigation (shared by SUMMARY and INVOCATIONS)
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
            if (panelState == PanelState.SUMMARY && listSelected < tracedSpanIndices.size()) {
                pinSpanAndScroll(tracedSpanIndices.get(listSelected));
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
        if (button == GLFW_MOUSE_BUTTON_LEFT && action == GLFW_PRESS) {
            // Click in source/detail area — forward to the active TextArea for selection
            if (my < panelTop) {
                if (detailCallIndex >= 0) {
                    detailArea.onMouseButton(button, action, mods, mx, my);
                }
                return true;
            }

            // Panel area clicks (summary or invocation list)
            if (panelRowHeight > 0 && my > panelRowStart) {
                int row = (int) ((my - panelRowStart) / panelRowHeight) + tableScroll.get();
                if (row >= 0 && row < listSize()) {
                    listSelected = row;
                    if (panelState == PanelState.SUMMARY) {
                        pinSpanAndScroll(tracedSpanIndices.get(row));
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
        // Forward to detail area for text selection drag
        if (detailCallIndex >= 0 && my < panelTop) {
            detailArea.onCursorPos(mx, my);
        }
        // Track hover on panel rows (summary or invocations)
        if (my > panelTop && panelRowHeight > 0 && my > panelRowStart) {
            int row = (int) ((my - panelRowStart) / panelRowHeight) + tableScroll.get();
            if (row >= 0 && row < listSize()) {
                listHovered = row;
                listSelected = row;
                // Live-update detail as mouse moves over invocation rows
                if (detailCallIndex >= 0 && panelState == PanelState.INVOCATIONS) {
                    openInvocationDetail(row);
                }
            } else {
                listHovered = -1;
            }
        } else {
            listHovered = -1;
        }
        // Track hover on source code blocks (only when source is showing)
        if (detailCallIndex < 0) {
            int row = sourceRowAtY(my);
            hoveredSpan = row >= 0 ? findSpanForRow(row) : -1;
        }
        return true;
    }

    @Override
    public boolean onScroll(double xoff, double yoff) {
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
            case SUMMARY -> tracedSpanIndices.size() + " traced blocks | Enter Inspect | Shift+F5 Re-trace";
            case INVOCATIONS -> {
                String name = extractDeclName(spans.get(pinnedSpan));
                List<TraceEvent> calls = spanCalls.get(pinnedSpan);
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
        String declName = extractDeclName(spans.get(pinnedSpan));
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

    /** Max call count across all traced spans (cached at map time). */
    private int maxCallCount = 1;

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
