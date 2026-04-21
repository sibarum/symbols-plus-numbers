package spn.gui;

import spn.fonts.SdfFontRenderer;

import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.glfw.GLFW.*;

/**
 * Reusable tab view component. Renders a tab bar at the top and delegates
 * content rendering and input to the active tab.
 *
 * <p>The tab bar shows labels for all open tabs with a visual indicator
 * for the active tab and dirty (unsaved) state. Clicking a tab switches
 * to it; Ctrl+Tab/Ctrl+Shift+Tab cycles through tabs.
 */
public class TabView {

    private static final float TAB_BAR_SCALE = 0.22f;
    private static final float TAB_PAD_X = 14f;
    private static final float TAB_PAD_Y = 4f;

    // Tab bar colors
    private static final float BAR_R = 0.10f, BAR_G = 0.10f, BAR_B = 0.12f;
    private static final float ACTIVE_R = 0.18f, ACTIVE_G = 0.20f, ACTIVE_B = 0.28f;
    private static final float INACTIVE_R = 0.12f, INACTIVE_G = 0.12f, INACTIVE_B = 0.14f;
    private static final float LABEL_R = 0.85f, LABEL_G = 0.85f, LABEL_B = 0.85f;
    private static final float DIM_R = 0.50f, DIM_G = 0.50f, DIM_B = 0.55f;
    private static final float DIRTY_R = 0.90f, DIRTY_G = 0.70f, DIRTY_B = 0.30f;

    private final SdfFontRenderer font;
    private final List<Tab> tabs = new ArrayList<>();
    private int activeIndex = -1;

    /** Optional listener fired whenever the active tab changes (and on close
     *  when activeIndex shifts to a different tab). Set by the owning window
     *  to refresh window chrome (e.g. the titlebar). */
    private java.util.function.Consumer<Tab> activationListener;

    public TabView(SdfFontRenderer font) {
        this.font = font;
    }

    public void setActivationListener(java.util.function.Consumer<Tab> listener) {
        this.activationListener = listener;
    }

    private void fireActivation() {
        if (activationListener != null && activeIndex >= 0 && activeIndex < tabs.size()) {
            activationListener.accept(tabs.get(activeIndex));
        }
    }

    // ── Tab management ─────────────────────────────────────────────────

    /** Add a tab and make it active. */
    public void addTab(Tab tab) {
        tabs.add(tab);
        activeIndex = tabs.size() - 1;
        tab.onActivated();
        fireActivation();
    }

    /** Switch to the tab at the given index. */
    public void setActiveIndex(int index) {
        if (index >= 0 && index < tabs.size() && index != activeIndex) {
            activeIndex = index;
            tabs.get(activeIndex).onActivated();
            fireActivation();
        }
    }

    /** Switch to a specific tab instance. Returns true if found. */
    public boolean switchTo(Tab tab) {
        int idx = tabs.indexOf(tab);
        if (idx >= 0) {
            setActiveIndex(idx);
            return true;
        }
        return false;
    }

    /** Close the tab at the given index. Returns true if closed. */
    public boolean closeTab(int index) {
        if (index < 0 || index >= tabs.size()) return false;
        Tab tab = tabs.get(index);
        if (!tab.onClose()) return false;

        tabs.remove(index);
        if (tabs.isEmpty()) {
            activeIndex = -1;
        } else if (activeIndex >= tabs.size()) {
            activeIndex = tabs.size() - 1;
            tabs.get(activeIndex).onActivated();
            fireActivation();
        } else if (index <= activeIndex && activeIndex > 0) {
            activeIndex--;
            tabs.get(activeIndex).onActivated();
            fireActivation();
        } else if (index == activeIndex) {
            // Removed the active tab from the middle; same index now points
            // at the next sibling — treat that as an activation.
            tabs.get(activeIndex).onActivated();
            fireActivation();
        }
        return true;
    }

    /** Close the active tab. */
    public boolean closeActiveTab() {
        return closeTab(activeIndex);
    }

    /** Cycle to next tab. */
    public void nextTab() {
        if (tabs.size() <= 1) return;
        setActiveIndex((activeIndex + 1) % tabs.size());
    }

    /** Cycle to previous tab. */
    public void prevTab() {
        if (tabs.size() <= 1) return;
        setActiveIndex((activeIndex - 1 + tabs.size()) % tabs.size());
    }

    public Tab getActiveTab() {
        return activeIndex >= 0 ? tabs.get(activeIndex) : null;
    }

    public int getActiveIndex() { return activeIndex; }

    public int tabCount() { return tabs.size(); }

    public List<Tab> getTabs() { return tabs; }

    /** Find a tab by predicate. */
    public Tab findTab(java.util.function.Predicate<Tab> predicate) {
        return tabs.stream().filter(predicate).findFirst().orElse(null);
    }

    // ── Tab bar height ─────────────────────────────────────────────────

    /** Returns the height of the tab bar in pixels. */
    public float getTabBarHeight() {
        if (tabs.size() <= 1) return 0; // No bar for single tab
        return font.getLineHeight(TAB_BAR_SCALE) + TAB_PAD_Y * 2;
    }

    // ── Rendering ──────────────────────────────────────────────────────

    /** Render the tab bar and active tab content. */
    public void render(float width, float height) {
        float barH = getTabBarHeight();

        // Draw tab bar (only if multiple tabs)
        if (tabs.size() > 1) {
            renderTabBar(width, barH);
        }

        // Render active tab content below the bar
        Tab active = getActiveTab();
        if (active != null) {
            active.render(0, barH, width, height - barH);
        }
    }

    private void renderTabBar(float width, float barH) {
        // Background
        font.drawRect(0, 0, width, barH, BAR_R, BAR_G, BAR_B);

        float x = 0;
        float lineH = font.getLineHeight(TAB_BAR_SCALE);
        float textY = TAB_PAD_Y + lineH * 0.8f;

        for (int i = 0; i < tabs.size(); i++) {
            Tab tab = tabs.get(i);
            String label = tab.label();
            float labelW = font.getTextWidth(label, TAB_BAR_SCALE);
            float tabW = labelW + TAB_PAD_X * 2;

            // Tab background
            boolean isActive = (i == activeIndex);
            font.drawRect(x, 0, tabW, barH,
                    isActive ? ACTIVE_R : INACTIVE_R,
                    isActive ? ACTIVE_G : INACTIVE_G,
                    isActive ? ACTIVE_B : INACTIVE_B);

            // Label
            float lr, lg, lb;
            if (tab.isDirty()) {
                lr = DIRTY_R; lg = DIRTY_G; lb = DIRTY_B;
            } else if (isActive) {
                lr = LABEL_R; lg = LABEL_G; lb = LABEL_B;
            } else {
                lr = DIM_R; lg = DIM_G; lb = DIM_B;
            }
            font.drawText(label, x + TAB_PAD_X, textY, TAB_BAR_SCALE, lr, lg, lb);

            x += tabW + 1; // 1px gap between tabs
        }
    }

    // ── Input routing ──────────────────────────────────────────────────

    /** Handle key input. Returns true if consumed. */
    public boolean onKey(int key, int scancode, int action, int mods) {
        if (action == GLFW_PRESS) {
            boolean ctrl = (mods & GLFW_MOD_CONTROL) != 0;

            // Ctrl+Tab / Ctrl+Shift+Tab: cycle tabs
            if (ctrl && key == GLFW_KEY_TAB) {
                if ((mods & GLFW_MOD_SHIFT) != 0) prevTab();
                else nextTab();
                return true;
            }
        }

        Tab active = getActiveTab();
        return active != null && active.onKey(key, scancode, action, mods);
    }

    public boolean onChar(int codepoint) {
        Tab active = getActiveTab();
        return active != null && active.onChar(codepoint);
    }

    public boolean onMouseButton(int button, int action, int mods, double mx, double my) {
        // Check if click is in the tab bar
        float barH = getTabBarHeight();
        if (barH > 0 && my < barH && action == GLFW_PRESS && button == GLFW_MOUSE_BUTTON_LEFT) {
            handleTabBarClick(mx);
            return true;
        }

        Tab active = getActiveTab();
        return active != null && active.onMouseButton(button, action, mods, mx, my);
    }

    public boolean onCursorPos(double mx, double my) {
        Tab active = getActiveTab();
        return active != null && active.onCursorPos(mx, my);
    }

    public boolean onScroll(double xoff, double yoff) {
        Tab active = getActiveTab();
        return active != null && active.onScroll(xoff, yoff);
    }

    public void onCursorEnter(boolean entered) {
        Tab active = getActiveTab();
        if (active != null) active.onCursorEnter(entered);
    }

    public String hudText() {
        Tab active = getActiveTab();
        return active != null ? active.hudText() : "Ctrl+N New | Ctrl+O Open";
    }

    private void handleTabBarClick(double mx) {
        float x = 0;
        for (int i = 0; i < tabs.size(); i++) {
            Tab tab = tabs.get(i);
            float labelW = font.getTextWidth(tab.label(), TAB_BAR_SCALE);
            float tabW = labelW + TAB_PAD_X * 2;
            if (mx >= x && mx < x + tabW) {
                setActiveIndex(i);
                return;
            }
            x += tabW + 1;
        }
    }
}
