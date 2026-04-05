package spn.canvas;

/**
 * Immutable draw commands buffered during SPN execution, replayed into OpenGL afterwards.
 */
public sealed interface DrawCommand {
    record Clear(float r, float g, float b) implements DrawCommand {}
    record SetFill(float r, float g, float b) implements DrawCommand {}
    record SetStroke(float r, float g, float b) implements DrawCommand {}
    record SetStrokeWeight(float w) implements DrawCommand {}
    record FillRect(float x, float y, float w, float h) implements DrawCommand {}
    record FillCircle(float cx, float cy, float r) implements DrawCommand {}
    record StrokeLine(float x1, float y1, float x2, float y2) implements DrawCommand {}
    record Text(float x, float y, String text, float scale) implements DrawCommand {}
}
