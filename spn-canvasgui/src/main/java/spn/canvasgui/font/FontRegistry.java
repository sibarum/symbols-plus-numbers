package spn.canvasgui.font;

import spn.fonts.SdfFontRenderer;
import spn.type.SpnSymbol;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Name → {@link FontFamily} registry for canvasgui. Each family holds up
 * to four variants (regular / bold / italic / bold-italic); widgets pick a
 * variant by combining a family name with {@code bold} / {@code italic}
 * flags on the Text being rendered.
 *
 * <p>Keyed by the symbol's <b>name</b> (String), not by {@link SpnSymbol}
 * identity — the canvasgui runtime and the SPN parser use different
 * {@code SpnSymbolTable}s, so the same logical {@code :mono} resolves to
 * different Java objects on each side. Name comparison sidesteps that.
 *
 * <p>Per-{@link spn.canvasgui.loop.GuiWindow}; owns the renderers it loads
 * and disposes them on shutdown.
 */
public final class FontRegistry {

    private final Map<String, FontFamily> byName = new LinkedHashMap<>();
    private String defaultName;

    public FontRegistry() {}

    /** Legacy ctor — kept for callers that already passed a symbol table; unused. */
    public FontRegistry(Object ignored) { this(); }

    public void register(String familyName, boolean bold, boolean italic, SdfFontRenderer r) {
        FontFamily family = byName.computeIfAbsent(familyName, k -> new FontFamily());
        family.setVariant(bold, italic, r);
        if (defaultName == null) defaultName = familyName;
    }

    public void registerRegular(String familyName, SdfFontRenderer r) {
        register(familyName, false, false, r);
    }

    public void setDefault(String familyName) {
        if (byName.containsKey(familyName)) defaultName = familyName;
    }

    public SdfFontRenderer get(SpnSymbol familyName, boolean bold, boolean italic) {
        return get(familyName != null ? familyName.name() : null, bold, italic);
    }

    public SdfFontRenderer get(String familyName, boolean bold, boolean italic) {
        FontFamily family = familyName != null ? byName.get(familyName) : null;
        if (family == null) family = defaultFamily();
        return family != null ? family.get(bold, italic) : null;
    }

    public SdfFontRenderer get(SpnSymbol familyName) {
        return get(familyName, false, false);
    }

    public SdfFontRenderer get(String familyName) {
        return get(familyName, false, false);
    }

    public SdfFontRenderer getDefault() {
        FontFamily f = defaultFamily();
        return f != null ? f.get(false, false) : null;
    }

    public boolean contains(String familyName) {
        return familyName != null && byName.containsKey(familyName);
    }

    public boolean contains(SpnSymbol sym) {
        return sym != null && byName.containsKey(sym.name());
    }

    private FontFamily defaultFamily() {
        return defaultName != null ? byName.get(defaultName) : null;
    }

    public void removeWithoutDispose(String familyName) {
        byName.remove(familyName);
    }

    public void dispose() {
        for (FontFamily f : byName.values()) {
            try { f.dispose(); } catch (Throwable t) { /* best effort */ }
        }
        byName.clear();
    }
}
