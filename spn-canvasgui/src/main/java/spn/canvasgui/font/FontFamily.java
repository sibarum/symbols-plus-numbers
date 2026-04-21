package spn.canvasgui.font;

import spn.fonts.SdfFontRenderer;

/**
 * A font family — up to four {@link SdfFontRenderer} variants keyed by
 * (bold × italic). Missing slots degrade gracefully: if (bold+italic) isn't
 * loaded, {@link #get} returns bold, then italic, then regular as fallbacks.
 *
 * <p>The registry tracks per-family variants; widgets pick a variant by
 * combining a family symbol with the current {@code bold} / {@code italic}
 * flags on the Text being rendered.
 */
public final class FontFamily {

    private SdfFontRenderer regular;
    private SdfFontRenderer bold;
    private SdfFontRenderer italic;
    private SdfFontRenderer boldItalic;

    public void setVariant(boolean bold, boolean italic, SdfFontRenderer r) {
        if (bold && italic) this.boldItalic = r;
        else if (bold)      this.bold = r;
        else if (italic)    this.italic = r;
        else                this.regular = r;
    }

    /** Best-matching renderer for the requested variant, with graceful fallback. */
    public SdfFontRenderer get(boolean bold, boolean italic) {
        if (bold && italic) {
            if (boldItalic != null) return boldItalic;
            if (this.bold != null)  return this.bold;
            if (this.italic != null) return this.italic;
            return regular;
        }
        if (bold) {
            if (this.bold != null) return this.bold;
            return regular;
        }
        if (italic) {
            if (this.italic != null) return this.italic;
            return regular;
        }
        return regular;
    }

    public SdfFontRenderer regular() { return regular; }

    /** Invoke dispose on every loaded variant. */
    public void dispose() {
        disposeQuiet(regular);
        disposeQuiet(bold);
        disposeQuiet(italic);
        disposeQuiet(boldItalic);
        regular = bold = italic = boldItalic = null;
    }

    private static void disposeQuiet(SdfFontRenderer r) {
        if (r != null) {
            try { r.dispose(); } catch (Throwable t) { /* best effort */ }
        }
    }
}
