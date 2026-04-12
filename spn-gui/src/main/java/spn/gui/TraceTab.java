package spn.gui;

import spn.fonts.SdfFontRenderer;
import spn.trace.TraceEvent;

import java.util.*;

import static org.lwjgl.glfw.GLFW.*;

/**
 * Interactive trace viewer tab. Displays the execution call tree
 * with expand/collapse, input/output inspection, and pure function
 * replay capability.
 */
public class TraceTab implements Tab {

    private static final float FONT_SCALE = 0.28f;
    private static final float SMALL_SCALE = 0.22f;
    private static final float PAD = 10f;
    private static final float INDENT = 20f;

    // Colors
    private static final float BG_R = 0.10f, BG_G = 0.10f, BG_B = 0.12f;
    private static final float CALL_R = 0.50f, CALL_G = 0.70f, CALL_B = 0.90f;
    private static final float PURE_R = 0.40f, PURE_G = 0.80f, PURE_B = 0.50f;
    private static final float RET_R = 0.70f, RET_G = 0.70f, RET_B = 0.72f;
    private static final float ERR_R = 0.90f, ERR_G = 0.30f, ERR_B = 0.30f;
    private static final float DIM_R = 0.45f, DIM_G = 0.45f, DIM_B = 0.48f;
    private static final float SEL_R = 0.18f, SEL_G = 0.25f, SEL_B = 0.38f;
    private static final float TIME_R = 0.55f, TIME_G = 0.50f, TIME_B = 0.35f;

    private final EditorWindow window;
    private final SdfFontRenderer font;
    private final String fileName;
    private final List<TraceEvent> events;

    // Call tree nodes built from CALL/RETURN pairs
    private final List<CallNode> rootCalls = new ArrayList<>();
    private final List<CallNode> flatVisible = new ArrayList<>(); // flattened visible rows

    private int selectedIndex;
    private int scrollOffset;
    private float contentTop; // y coordinate where rows start (set during render)

    /** A node in the call tree. */
    private static class CallNode {
        final TraceEvent call;      // CALL event
        TraceEvent result;          // RETURN or ERROR event
        final List<CallNode> children = new ArrayList<>();
        final int depth;
        boolean expanded;

        CallNode(TraceEvent call, int depth) {
            this.call = call;
            this.depth = depth;
            this.expanded = depth < 2; // auto-expand first 2 levels
        }
    }

    TraceTab(EditorWindow window, List<TraceEvent> events, String fileName) {
        this.window = window;
        this.font = window.getFont();
        this.events = events;
        this.fileName = fileName;
        buildCallTree();
        rebuildFlatList();
    }

    /** Build a tree from the flat event list using parentSequence. */
    private void buildCallTree() {
        Map<Long, CallNode> bySequence = new HashMap<>();
        for (TraceEvent e : events) {
            if (e.kind() == TraceEvent.Kind.CALL) {
                CallNode node = new CallNode(e, 0);
                bySequence.put(e.sequence(), node);
                if (e.parentSequence() < 0) {
                    rootCalls.add(node);
                } else {
                    CallNode parent = bySequence.get(e.parentSequence());
                    if (parent != null) {
                        node = new CallNode(e, parent.depth + 1);
                        node.expanded = node.depth < 2;
                        bySequence.put(e.sequence(), node);
                        parent.children.add(node);
                    } else {
                        rootCalls.add(node);
                    }
                }
            } else if (e.kind() == TraceEvent.Kind.RETURN || e.kind() == TraceEvent.Kind.ERROR) {
                CallNode node = bySequence.get(e.parentSequence());
                if (node != null) node.result = e;
            }
        }
    }

    /** Flatten the tree into a visible row list based on expanded state. */
    private void rebuildFlatList() {
        flatVisible.clear();
        for (CallNode root : rootCalls) {
            flattenNode(root);
        }
    }

    private void flattenNode(CallNode node) {
        flatVisible.add(node);
        if (node.expanded) {
            for (CallNode child : node.children) {
                flattenNode(child);
            }
        }
    }

    // ── Tab interface ──────────────────────────────────────────────────

    @Override
    public String label() { return "Trace: " + fileName; }

    @Override
    public boolean isDirty() { return false; }

    @Override
    public void render(float x, float y, float width, float height) {
        font.drawRect(x, y, width, height, BG_R, BG_G, BG_B);

        float lineH = font.getLineHeight(FONT_SCALE) * 1.3f;
        float hudH = window.getHudHeight();
        int visibleRows = (int) ((height - hudH - PAD * 2) / lineH);
        float textY = y + PAD;

        // Header
        String header = "Execution Trace: " + events.size() + " events, "
                + rootCalls.size() + " top-level calls";
        font.drawText(header, x + PAD, textY + font.getLineHeight(SMALL_SCALE),
                SMALL_SCALE, DIM_R, DIM_G, DIM_B);
        textY += font.getLineHeight(SMALL_SCALE) * 1.8f;
        contentTop = textY; // save for mouse hit-testing

        // Rows
        int rowCount = Math.min(visibleRows, flatVisible.size() - scrollOffset);
        for (int i = 0; i < rowCount; i++) {
            int idx = scrollOffset + i;
            CallNode node = flatVisible.get(idx);
            float rowY = textY + i * lineH;

            // Selection highlight
            if (idx == selectedIndex) {
                font.drawRect(x, rowY, width, lineH, SEL_R, SEL_G, SEL_B);
            }

            float rowTextY = rowY + lineH - 4f;
            float indent = PAD + node.depth * INDENT;

            // Expand/collapse indicator
            if (!node.children.isEmpty()) {
                String arrow = node.expanded ? "v " : "> ";
                font.drawText(arrow, x + indent, rowTextY, FONT_SCALE, DIM_R, DIM_G, DIM_B);
                indent += font.getTextWidth("> ", FONT_SCALE);
            } else {
                indent += font.getTextWidth("  ", FONT_SCALE);
            }

            // Pure badge
            if (node.call.pure()) {
                font.drawText("pure ", x + indent, rowTextY, SMALL_SCALE,
                        PURE_R, PURE_G, PURE_B);
                indent += font.getTextWidth("pure ", SMALL_SCALE);
            }

            // Function name
            font.drawText(node.call.location(), x + indent, rowTextY, FONT_SCALE,
                    CALL_R, CALL_G, CALL_B);
            indent += font.getTextWidth(node.call.location(), FONT_SCALE);

            // Inputs
            String inputs = node.call.inputsSummary();
            font.drawText(inputs, x + indent, rowTextY, SMALL_SCALE, DIM_R, DIM_G, DIM_B);
            indent += font.getTextWidth(inputs, SMALL_SCALE) + 8f;

            // Result
            if (node.result != null) {
                if (node.result.kind() == TraceEvent.Kind.RETURN) {
                    String out = "=> " + node.result.outputSummary();
                    font.drawText(out, x + indent, rowTextY, SMALL_SCALE,
                            RET_R, RET_G, RET_B);
                    indent += font.getTextWidth(out, SMALL_SCALE) + 8f;
                } else {
                    String err = "!! " + node.result.error();
                    font.drawText(err, x + indent, rowTextY, SMALL_SCALE,
                            ERR_R, ERR_G, ERR_B);
                    indent += font.getTextWidth(err, SMALL_SCALE) + 8f;
                }
                // Duration
                if (node.result.durationNanos() > 0) {
                    String dur = formatDuration(node.result.durationNanos());
                    float durW = font.getTextWidth(dur, SMALL_SCALE);
                    font.drawText(dur, x + width - PAD - durW, rowTextY, SMALL_SCALE,
                            TIME_R, TIME_G, TIME_B);
                }
            }
        }
    }

    @Override
    public boolean onKey(int key, int scancode, int action, int mods) {
        if (action != GLFW_PRESS && action != GLFW_REPEAT) return true;

        switch (key) {
            case GLFW_KEY_UP -> {
                if (selectedIndex > 0) selectedIndex--;
                ensureVisible();
            }
            case GLFW_KEY_DOWN -> {
                if (selectedIndex < flatVisible.size() - 1) selectedIndex++;
                ensureVisible();
            }
            case GLFW_KEY_RIGHT -> {
                // Expand
                if (selectedIndex < flatVisible.size()) {
                    CallNode node = flatVisible.get(selectedIndex);
                    if (!node.children.isEmpty() && !node.expanded) {
                        node.expanded = true;
                        rebuildFlatList();
                    }
                }
            }
            case GLFW_KEY_LEFT -> {
                // Collapse
                if (selectedIndex < flatVisible.size()) {
                    CallNode node = flatVisible.get(selectedIndex);
                    if (node.expanded) {
                        node.expanded = false;
                        rebuildFlatList();
                    }
                }
            }
            case GLFW_KEY_ENTER, GLFW_KEY_SPACE -> {
                // Toggle expand/collapse
                if (selectedIndex < flatVisible.size()) {
                    CallNode node = flatVisible.get(selectedIndex);
                    if (!node.children.isEmpty()) {
                        node.expanded = !node.expanded;
                        rebuildFlatList();
                    }
                }
            }
        }
        return true;
    }

    @Override
    public boolean onChar(int codepoint) { return true; }

    @Override
    public boolean onMouseButton(int button, int action, int mods, double mx, double my) {
        if (button == GLFW_MOUSE_BUTTON_LEFT && action == GLFW_PRESS) {
            int row = rowAtY(my);
            if (row >= 0 && row < flatVisible.size()) {
                selectedIndex = row;
                // Double-click toggles expand
                CallNode node = flatVisible.get(row);
                if (!node.children.isEmpty()) {
                    node.expanded = !node.expanded;
                    rebuildFlatList();
                }
            }
        }
        return true;
    }

    @Override
    public boolean onCursorPos(double mx, double my) { return true; }

    @Override
    public boolean onScroll(double xoff, double yoff) {
        scrollOffset = Math.max(0, scrollOffset - (int) yoff * 3);
        if (scrollOffset > Math.max(0, flatVisible.size() - 10))
            scrollOffset = Math.max(0, flatVisible.size() - 10);
        return true;
    }

    @Override
    public String hudText() {
        int calls = (int) events.stream()
                .filter(e -> e.kind() == TraceEvent.Kind.CALL).count();
        int errors = (int) events.stream()
                .filter(e -> e.kind() == TraceEvent.Kind.ERROR).count();
        int pures = (int) events.stream()
                .filter(e -> e.kind() == TraceEvent.Kind.CALL && e.pure()).count();
        return calls + " calls | " + pures + " pure | " + errors + " errors"
                + " | Arrow keys Navigate | Enter Expand/Collapse | Esc Close";
    }

    // ── Helpers ──────────────────────────────────────────────────────

    private void ensureVisible() {
        float lineH = font.getLineHeight(FONT_SCALE) * 1.3f;
        int visibleRows = 20; // approximate
        if (selectedIndex < scrollOffset) scrollOffset = selectedIndex;
        else if (selectedIndex >= scrollOffset + visibleRows)
            scrollOffset = selectedIndex - visibleRows + 1;
    }

    private int rowAtY(double my) {
        float lineH = font.getLineHeight(FONT_SCALE) * 1.3f;
        if (my < contentTop) return -1;
        return (int) ((my - contentTop) / lineH) + scrollOffset;
    }

    private static String formatDuration(long nanos) {
        if (nanos < 1_000) return nanos + "ns";
        if (nanos < 1_000_000) return String.format("%.1fus", nanos / 1_000.0);
        if (nanos < 1_000_000_000) return String.format("%.1fms", nanos / 1_000_000.0);
        return String.format("%.2fs", nanos / 1_000_000_000.0);
    }
}
