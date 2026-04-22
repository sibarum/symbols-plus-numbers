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
    public float buttonSelectedR = 0.38f, buttonSelectedG = 0.50f, buttonSelectedB = 0.70f;

    public float focusRingR = 0.40f, focusRingG = 0.75f, focusRingB = 1.00f;
    public float focusRingWidthRem = 0.125f;

    /** Relative font size for body text (passed as scale to SdfFontRenderer). */
    public float fontScale = 0.35f;

    // ── Editable Text ──────────────────────────────────────────────────
    public float textCursorR = 0.92f, textCursorG = 0.92f, textCursorB = 0.94f;
    public float textSelectionR = 0.38f, textSelectionG = 0.48f, textSelectionB = 0.70f;
    public float textCursorWidthRem = 0.08f;
    public double textBlinkRateSec = 1.0;       // full on/off cycle
    public float textEditPadXRem = 0.25f;      // inner left/right padding for editable text
    public double textMultiClickTimeSec = 0.4; // press-to-press window for double/triple-click
    public float textMultiClickSlopPx = 4f;    // max local-coord drift between multi-click presses
    public float textLineHeightMult = 1.0f;    // default inter-line spacing multiplier

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

    // ── Dial ────────────────────────────────────────────────────────────
    public float dialTrackR = 0.22f, dialTrackG = 0.24f, dialTrackB = 0.30f;
    public float dialIndicatorR = 0.70f, dialIndicatorG = 0.78f, dialIndicatorB = 0.95f;
    public float dialDefaultSizeRem = 4f;
    public float dialIndicatorWidthRem = 0.2f;
    // 270° sweep, starting at 135° (7 o'clock) going clockwise to 45° (5 o'clock).
    public double dialSweepStartRad = 3.0 * Math.PI / 4.0;
    public double dialSweepRangeRad = 3.0 * Math.PI / 2.0;

    // ── Scrollable ──────────────────────────────────────────────────────
    public float scrollbarWidthRem = 0.4f;
    public float scrollbarThumbR = 0.30f, scrollbarThumbG = 0.33f, scrollbarThumbB = 0.40f;
    public float scrollbarTrackR = 0.12f, scrollbarTrackG = 0.14f, scrollbarTrackB = 0.18f;
    public float scrollWheelStepPx = 30f;

    // ── Tabs ────────────────────────────────────────────────────────────
    public float tabHeaderHeightRem = 1.6f;
    public float tabHeaderPadXRem = 0.9f;
    public float tabActiveR = 0.30f, tabActiveG = 0.34f, tabActiveB = 0.42f;
    public float tabInactiveR = 0.18f, tabInactiveG = 0.20f, tabInactiveB = 0.26f;
    public float tabHoverR = 0.24f, tabHoverG = 0.27f, tabHoverB = 0.34f;
}
