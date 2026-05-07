package spn.gui;

import spn.claude.ActivityLog;
import spn.claude.ClaudeService;
import spn.claude.ClaudeSettings;
import spn.claude.ConversationState;
import spn.claude.MainThreadQueue;
import spn.fonts.SdfFontRenderer;

import static org.lwjgl.glfw.GLFW.*;

/**
 * A chat tab for talking to Claude. The history sits above an input area
 * separated by a status strip. Each tab owns its own {@link ConversationState};
 * closing the tab discards the conversation.
 *
 * <p>The history reuses the inherited {@link ScrollableTab#textArea}; we treat
 * it as read-only by routing every typing key into the separate input
 * {@link TextArea} below. The history's text is rebuilt from
 * {@code conversation} after each turn.
 */
public final class ClaudeChatTab extends ScrollableTab {

    private static final float INPUT_MIN_H = 80f;
    private static final float INPUT_MAX_RATIO = 0.35f;
    private static final float STATUS_H = 22f;
    private static final float PAD = 4f;

    private final MainThreadQueue mainQueue;
    private final ClaudeSettings settings;
    private final ActivityLog log;
    private final ConversationState conversation = new ConversationState();
    private final TextArea inputArea;
    private final int instanceNumber;

    private boolean inFlight;
    private String statusMessage = "";
    private boolean closed;

    public ClaudeChatTab(EditorWindow window,
                         MainThreadQueue mainQueue,
                         ClaudeSettings settings,
                         ActivityLog log,
                         int instanceNumber) {
        super(window);
        this.mainQueue = mainQueue;
        this.settings = settings;
        this.log = log;
        this.instanceNumber = instanceNumber;

        // History: plain-text rendering — chat output is prose, not SPN code,
        // so syntax coloring and line numbers would mislead. The history is
        // read-only, so suppress its cursor entirely.
        textArea.setPlainTextMode(true);
        textArea.setCursorEnabled(false);
        textArea.setText("");

        inputArea = new TextArea(window.getFont());
        inputArea.setPlainTextMode(true);
        inputArea.setClipboard(new TextArea.ClipboardHandler() {
            @Override public void set(String text) { glfwSetClipboardString(window.getHandle(), text); }
            @Override public String get()          { return glfwGetClipboardString(window.getHandle()); }
        });
    }

    // ── Tab interface ──────────────────────────────────────────────────

    @Override
    public String label() {
        return instanceNumber <= 1 ? "Claude" : "Claude (" + instanceNumber + ")";
    }

    @Override
    public boolean isDirty() { return false; }

    @Override
    public void render(float x, float y, float width, float height) {
        if (!settings.hasApiKey()) {
            renderNoKeyPlaceholder(x, y, width, height);
            return;
        }

        float inputH = Math.max(INPUT_MIN_H, height * INPUT_MAX_RATIO);
        if (inputH > height * 0.5f) inputH = height * 0.5f;
        float historyH = height - inputH - STATUS_H;

        // History (top): re-use ScrollableTab's textArea + scrollbars
        renderWithScrollbars(textArea, vScroll, hScroll, x, y, width, historyH, 0);

        // Status strip
        renderStatus(x, y + historyH, width, STATUS_H);

        // Input (bottom)
        SdfFontRenderer font = window.getFont();
        float inputY = y + historyH + STATUS_H;
        font.drawRect(x, inputY, width, inputH, 0.10f, 0.10f, 0.13f);
        font.drawRect(x, inputY, width, 1f, 0.30f, 0.30f, 0.40f);
        inputArea.setBounds(x + PAD, inputY + PAD, width - 2 * PAD, inputH - 2 * PAD);
        inputArea.render();
    }

    @Override
    public boolean onKey(int key, int scancode, int action, int mods) {
        if (action != GLFW_PRESS && action != GLFW_REPEAT) return false;

        boolean ctrl = (mods & GLFW_MOD_CONTROL) != 0;
        boolean shift = (mods & GLFW_MOD_SHIFT) != 0;

        // Enter sends; Shift+Enter inserts a newline into the input.
        if (key == GLFW_KEY_ENTER && !shift && !ctrl && action == GLFW_PRESS) {
            sendCurrentInput();
            return true;
        }
        if (ctrl && key == GLFW_KEY_L && action == GLFW_PRESS) {
            clearConversation();
            return true;
        }
        // Esc not consumed — TabViewMode closes the tab.
        if (key == GLFW_KEY_ESCAPE) return false;

        // Pass through editor-window shortcuts so the user isn't trapped when
        // the chat tab is the only one open. Without these, Ctrl+N / Ctrl+O /
        // Ctrl+M etc. would be silently swallowed by inputArea.
        if (ctrl && action == GLFW_PRESS) {
            switch (key) {
                case GLFW_KEY_N -> { window.openNewTab(); return true; }
                case GLFW_KEY_O -> { window.openFile(); return true; }
                case GLFW_KEY_G -> { window.openLogTab(); return true; }
                case GLFW_KEY_M -> {
                    ModuleContext ctx = window.findAnyModuleContext();
                    if (ctx != null) window.pushLegacyMode(new ModuleMode(window, ctx));
                    else window.flash("No module loaded — cannot find module.spn", true);
                    return true;
                }
                case GLFW_KEY_P -> {
                    window.pushLegacyMode(new ActionMenuMode(window, window.getActionRegistry()));
                    return true;
                }
                case GLFW_KEY_SLASH -> {
                    if (!shift) {
                        window.pushLegacyMode(new HelpMode(window, window.getActionRegistry()));
                        return true;
                    }
                }
                default -> { /* fall through to inputArea */ }
            }
        }

        // Everything else (typing, navigation, copy/paste, undo, etc.) flows
        // to the input area. The history is read-only and ignores keystrokes.
        inputArea.onKey(key, mods);
        return true;
    }

    @Override
    public boolean onChar(int codepoint) {
        inputArea.onCharInput(codepoint);
        return true;
    }

    @Override
    public boolean onMouseButton(int button, int action, int mods, double mx, double my) {
        if (insideInput(mx, my)) {
            inputArea.onMouseButton(button, action, mods, mx, my);
            return true;
        }
        return super.onMouseButton(button, action, mods, mx, my);
    }

    @Override
    public boolean onCursorPos(double mx, double my) {
        if (insideInput(mx, my)) {
            inputArea.onCursorPos(mx, my);
            return true;
        }
        return super.onCursorPos(mx, my);
    }

    @Override
    public boolean onClose() {
        closed = true;
        return true;
    }

    @Override
    public String hudText() {
        if (!settings.hasApiKey()) {
            return "Claude - no API key | Ctrl+P -> Claude Settings";
        }
        if (inFlight) return "Claude | Thinking...";
        return "Claude | Enter Send | Shift+Enter Newline | Ctrl+L Clear | Esc Close Tab";
    }

    // ── Send / receive ─────────────────────────────────────────────────

    private void sendCurrentInput() {
        if (inFlight) return;
        if (!settings.hasApiKey()) return;
        ClaudeService service = window.getClaudeService();
        if (service == null) return;
        String text = inputArea.getText();
        if (text == null || text.isBlank()) return;

        conversation.appendUser(text);
        log.user(text);
        inputArea.setText("");
        inFlight = true;
        statusMessage = "Thinking...";
        rebuildHistory();
        scrollHistoryToBottom();

        var snapshot = conversation.snapshot();
        service.send(snapshot).whenComplete((result, ex) -> mainQueue.post(() -> {
            if (closed) return;
            inFlight = false;
            if (ex != null) {
                statusMessage = "Error: " + ex.getMessage();
                log.error("client_exception", ex.getMessage());
                return;
            }
            if (result instanceof ClaudeService.Result.Success s) {
                conversation.appendAssistant(s.text());
                statusMessage = "";
                rebuildHistory();
                scrollHistoryToBottom();
            } else if (result instanceof ClaudeService.Result.Error e) {
                statusMessage = "[" + e.errorType() + "] " + e.message();
            }
        }));
    }

    private void clearConversation() {
        conversation.clear();
        statusMessage = "";
        textArea.setText("");
    }

    private void rebuildHistory() {
        StringBuilder sb = new StringBuilder();
        for (var t : conversation.turns()) {
            String prefix = t.role() == ConversationState.Turn.Role.USER ? "> You" : "< Claude";
            sb.append(prefix).append('\n');
            sb.append(t.text());
            sb.append("\n\n");
        }
        textArea.setText(sb.toString());
    }

    private void scrollHistoryToBottom() {
        int total = textArea.getContentRows();
        int visible = textArea.getVisibleRows();
        if (visible <= 0) visible = 20;
        textArea.setScrollRow(Math.max(0, total - visible));
    }

    // ── Layout helpers ─────────────────────────────────────────────────

    private boolean insideInput(double mx, double my) {
        return mx >= inputArea.getBoundsX()
                && mx < inputArea.getBoundsX() + inputArea.getBoundsW()
                && my >= inputArea.getBoundsY()
                && my < inputArea.getBoundsY() + inputArea.getBoundsH();
    }

    private void renderStatus(float x, float y, float width, float height) {
        SdfFontRenderer font = window.getFont();
        font.drawRect(x, y, width, height, 0.08f, 0.08f, 0.10f);
        if (statusMessage == null || statusMessage.isEmpty()) return;
        float scale = 0.22f;
        float ly = y + height - 4f;
        boolean error = statusMessage.startsWith("[") || statusMessage.startsWith("Error:");
        float r = error ? 0.95f : 0.70f;
        float g = error ? 0.40f : 0.70f;
        float b = error ? 0.40f : 0.50f;
        font.drawText(statusMessage, x + 8f, ly, scale, r, g, b);
    }

    private void renderNoKeyPlaceholder(float x, float y, float width, float height) {
        SdfFontRenderer font = window.getFont();
        font.drawRect(x, y, width, height, 0.10f, 0.10f, 0.12f);
        String msg = "No Claude API key configured.";
        String hint = "Open the Action Menu (Ctrl+P) and run \"Claude Settings\".";
        float scale = 0.32f;
        float lineH = font.getLineHeight(scale) * 1.4f;
        float msgW = font.getTextWidth(msg, scale);
        float hintW = font.getTextWidth(hint, scale * 0.75f);
        float cx = x + width / 2f;
        float cy = y + height / 2f;
        font.drawText(msg, cx - msgW / 2f, cy - lineH / 2f, scale,
                0.85f, 0.85f, 0.90f);
        font.drawText(hint, cx - hintW / 2f, cy + lineH, scale * 0.75f,
                0.55f, 0.55f, 0.65f);
    }
}
