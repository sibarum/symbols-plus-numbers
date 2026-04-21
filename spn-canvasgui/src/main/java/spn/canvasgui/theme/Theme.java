package spn.canvasgui.theme;

/**
 * Shared visual theme: colors and font scale defaults.
 * All colors are RGB in [0,1].
 */
public final class Theme {

    public float bgR = 0.12f, bgG = 0.12f, bgB = 0.14f;
    public float textR = 0.92f, textG = 0.92f, textB = 0.94f;

    public float buttonR = 0.22f, buttonG = 0.24f, buttonB = 0.30f;
    public float buttonHoverR = 0.30f, buttonHoverG = 0.34f, buttonHoverB = 0.42f;
    public float buttonPressR = 0.18f, buttonPressG = 0.20f, buttonPressB = 0.26f;

    public float focusRingR = 0.40f, focusRingG = 0.75f, focusRingB = 1.00f;
    public float focusRingWidthRem = 0.125f;

    /** Relative font size for body text (passed as scale to SdfFontRenderer). */
    public float fontScale = 0.35f;

    /** Button horizontal padding, rem. */
    public float buttonPadXRem = 0.75f;
    public float buttonPadYRem = 0.4f;

    // ── Slider ──────────────────────────────────────────────────────────
    public float sliderTrackR = 0.18f, sliderTrackG = 0.20f, sliderTrackB = 0.26f;
    public float sliderThumbR = 0.55f, sliderThumbG = 0.62f, sliderThumbB = 0.78f;
    public float sliderThumbHoverR = 0.70f, sliderThumbHoverG = 0.78f, sliderThumbHoverB = 0.95f;
    public float sliderTrackHeightRem = 0.35f;
    public float sliderThumbWidthRem = 0.7f;
    public float sliderDefaultWidthRem = 12f;
    public float sliderDefaultHeightRem = 1.5f;
}
