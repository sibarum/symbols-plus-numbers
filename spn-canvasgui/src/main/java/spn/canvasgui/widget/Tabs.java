package spn.canvasgui.widget;

import spn.canvas.DrawCommand;
import spn.canvasgui.cmd.GuiCommand;
import spn.canvasgui.component.Bounds;
import spn.canvasgui.component.Component;
import spn.canvasgui.component.Constraints;
import spn.canvasgui.component.Size;
import spn.canvasgui.input.GuiEvent;
import spn.canvasgui.theme.Theme;
import spn.canvasgui.unit.GuiContext;

import java.util.Collections;
import java.util.List;
import java.util.function.IntConsumer;

/**
 * Tabbed container. Stateless — the active index is supplied each frame;
 * the caller's {@code on:select} handler updates state and re-renders.
 *
 * <p>Layout: row of tab headers at the top, active page filling the rest.
 * Inactive pages are not built by the reconciler — only the active child
 * is present at any time. State that should survive tab switches belongs
 * on the enclosing {@code stateful} instance, not inside page widgets.
 */
public class Tabs extends Component {

    private final Theme theme;
    private List<String> labels = List.of();
    private int activeIndex = 0;
    private Component activePage;
    private IntConsumer onSelect = i -> {};

    private float headerHeightPx;
    private float[] headerWidthsPx;

    public Tabs(Theme theme) {
        this.theme = theme;
    }

    public Tabs setLabels(List<String> labels) {
        this.labels = labels != null ? labels : Collections.emptyList();
        invalidate();
        return this;
    }

    public Tabs setActiveIndex(int i) {
        this.activeIndex = i;
        invalidate();
        return this;
    }

    public int activeIndex() { return activeIndex; }

    public Tabs setActivePage(Component page) {
        if (this.activePage != null && this.activePage != page) {
            // dropping reference; GC takes care of it
        }
        this.activePage = page;
        if (page != null) page.setParent(this);
        invalidate();
        return this;
    }

    public Component activePage() { return activePage; }

    public Tabs setOnSelect(IntConsumer cb) { this.onSelect = cb; return this; }

    @Override
    public Size measure(Constraints c, GuiContext ctx) {
        headerHeightPx = ctx.rem(theme.tabHeaderHeightRem);
        float padX = ctx.rem(theme.tabHeaderPadXRem);
        headerWidthsPx = new float[labels.size()];
        float totalHeaderW = 0;
        for (int i = 0; i < labels.size(); i++) {
            float tw = ctx.font().getTextWidth(labels.get(i), theme.fontScale);
            headerWidthsPx[i] = tw + 2 * padX;
            totalHeaderW += headerWidthsPx[i];
        }
        Size pageSize = Size.ZERO;
        if (activePage != null) {
            Constraints inner = new Constraints(
                    c.minW(), c.maxW(),
                    Math.max(0, c.minH() - headerHeightPx),
                    Math.max(0, c.maxH() - headerHeightPx));
            pageSize = activePage.measure(inner, ctx);
        }
        float w = Math.max(totalHeaderW, pageSize.w());
        float h = headerHeightPx + pageSize.h();
        return new Size(c.clampW(w), c.clampH(h));
    }

    @Override
    public void arrange(Bounds b, GuiContext ctx) {
        super.arrange(b, ctx);
        if (activePage != null) {
            float headerH = ctx.rem(theme.tabHeaderHeightRem);
            activePage.arrange(new Bounds(b.x(), b.y() + headerH,
                    b.w(), Math.max(0, b.h() - headerH)), ctx);
        }
    }

    @Override
    public void paint(List<GuiCommand> out, GuiContext ctx) {
        float headerH = ctx.rem(theme.tabHeaderHeightRem);
        float x = 0;
        for (int i = 0; i < labels.size(); i++) {
            float w = headerWidthsPx != null && i < headerWidthsPx.length ? headerWidthsPx[i] : 0;
            boolean active = i == activeIndex;
            float r, g, b;
            if (active) { r = theme.tabActiveR; g = theme.tabActiveG; b = theme.tabActiveB; }
            else        { r = theme.tabInactiveR; g = theme.tabInactiveG; b = theme.tabInactiveB; }
            out.add(new GuiCommand.Draw(new DrawCommand.SetFill(r, g, b)));
            out.add(new GuiCommand.Draw(new DrawCommand.FillRect(x, 0, w, headerH)));

            float textW = ctx.font().getTextWidth(labels.get(i), theme.fontScale);
            float textH = ctx.font().getLineHeight(theme.fontScale);
            float tx = x + (w - textW) * 0.5f;
            float baseline = (headerH - textH) * 0.5f + textH * 0.8f;
            out.add(new GuiCommand.TextRun(tx, baseline, labels.get(i),
                    theme.fontScale, theme.textR, theme.textG, theme.textB));
            x += w;
        }
        if (activePage != null) {
            Bounds cb = activePage.bounds();
            out.add(new GuiCommand.PushOffset(cb.x() - bounds().x(), cb.y() - bounds().y()));
            activePage.paint(out, ctx);
            out.add(new GuiCommand.PopOffset());
        }
        clearDirty();
    }

    @Override
    public boolean onEvent(GuiEvent e) {
        if (e instanceof GuiEvent.Pointer p && p.phase() == GuiEvent.PointerPhase.CLICK
                && p.localY() >= 0 && p.localY() < headerHeightPx) {
            float x = 0;
            for (int i = 0; i < labels.size(); i++) {
                float w = headerWidthsPx != null && i < headerWidthsPx.length ? headerWidthsPx[i] : 0;
                if (p.localX() >= x && p.localX() < x + w) {
                    onSelect.accept(i);
                    return true;
                }
                x += w;
            }
        }
        return false;
    }

    @Override
    public Component hitTest(float px, float py) {
        if (!bounds().contains(px, py)) return null;
        if (py - bounds().y() < headerHeightPx) return this; // header row
        if (activePage != null) {
            Component hit = activePage.hitTest(px, py);
            if (hit != null) return hit;
        }
        return this;
    }

    @Override
    public void collectTabOrder(List<Component> out) {
        if (activePage != null) activePage.collectTabOrder(out);
    }
}
