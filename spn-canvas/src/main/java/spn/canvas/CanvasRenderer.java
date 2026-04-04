package spn.canvas;

import java.util.List;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;

/**
 * Replays a list of {@link DrawCommand}s into OpenGL draw calls.
 * Uses a simple flat-color shader with an orthographic projection.
 */
public final class CanvasRenderer {

    private static final int CIRCLE_SEGMENTS = 32;
    // 5 floats per vertex: x, y, r, g, b
    private static final int FLOATS_PER_VERTEX = 5;
    // Conservative max vertices per batch (rects=6, circles=SEGMENTS*3, lines=6)
    private static final int MAX_VERTICES = 16384;

    private int program;
    private int vao;
    private int vbo;
    private int uProjectionLoc;

    private final float[] vertexBuf = new float[MAX_VERTICES * FLOATS_PER_VERTEX];
    private int vertexCount;

    // Current state while replaying
    private float fillR = 1f, fillG = 1f, fillB = 1f;
    private float strokeR = 1f, strokeG = 1f, strokeB = 1f;
    private float strokeWeight = 1f;

    // ── Shader sources ───────────────────────────────────────────────────

    private static final String VERTEX_SHADER = """
            #version 330
            layout(location=0) in vec2 aPos;
            layout(location=1) in vec3 aColor;
            uniform mat4 uProjection;
            out vec3 vColor;
            void main() {
                gl_Position = uProjection * vec4(aPos, 0.0, 1.0);
                vColor = aColor;
            }
            """;

    private static final String FRAGMENT_SHADER = """
            #version 330
            in vec3 vColor;
            out vec4 fragColor;
            void main() {
                fragColor = vec4(vColor, 1.0);
            }
            """;

    // ── Init / Dispose ───────────────────────────────────────────────────

    public void init() {
        createShader();
        createBuffers();
    }

    public void dispose() {
        glDeleteBuffers(vbo);
        glDeleteVertexArrays(vao);
        glDeleteProgram(program);
    }

    // ── Replay ───────────────────────────────────────────────────────────

    /**
     * Replay a list of draw commands. Caller must have an active GL context
     * and have already called glClear if desired.
     */
    public void replay(List<DrawCommand> commands, int canvasWidth, int canvasHeight) {
        glUseProgram(program);

        // Orthographic projection: (0,0) top-left, (w,h) bottom-right
        float l = 0, r = canvasWidth, t = 0, b = canvasHeight;
        float[] proj = {
            2f/(r-l),   0,          0, 0,
            0,          2f/(t-b),   0, 0,
            0,          0,         -1, 0,
            -(r+l)/(r-l), -(t+b)/(t-b), 0, 1
        };
        glUniformMatrix4fv(uProjectionLoc, false, proj);

        glBindVertexArray(vao);
        vertexCount = 0;

        // Reset state
        fillR = 1f; fillG = 1f; fillB = 1f;
        strokeR = 1f; strokeG = 1f; strokeB = 1f;
        strokeWeight = 1f;

        for (DrawCommand cmd : commands) {
            switch (cmd) {
                case DrawCommand.Clear c -> {
                    flush();
                    glClearColor(c.r(), c.g(), c.b(), 1f);
                    glClear(GL_COLOR_BUFFER_BIT);
                }
                case DrawCommand.SetFill f -> {
                    fillR = f.r(); fillG = f.g(); fillB = f.b();
                }
                case DrawCommand.SetStroke s -> {
                    strokeR = s.r(); strokeG = s.g(); strokeB = s.b();
                }
                case DrawCommand.SetStrokeWeight w -> {
                    strokeWeight = w.w();
                }
                case DrawCommand.FillRect rect -> {
                    emitRect(rect.x(), rect.y(), rect.w(), rect.h(), fillR, fillG, fillB);
                }
                case DrawCommand.FillCircle circle -> {
                    emitCircle(circle.cx(), circle.cy(), circle.r(), fillR, fillG, fillB);
                }
                case DrawCommand.StrokeLine line -> {
                    emitLine(line.x1(), line.y1(), line.x2(), line.y2(),
                             strokeR, strokeG, strokeB, strokeWeight);
                }
            }
        }

        flush();
        glBindVertexArray(0);
        glUseProgram(0);
    }

    // ── Geometry emission ────────────────────────────────────────────────

    private void emitRect(float x, float y, float w, float h,
                          float r, float g, float b) {
        ensureCapacity(6);
        // Two triangles
        vertex(x, y, r, g, b);
        vertex(x + w, y, r, g, b);
        vertex(x + w, y + h, r, g, b);

        vertex(x, y, r, g, b);
        vertex(x + w, y + h, r, g, b);
        vertex(x, y + h, r, g, b);
    }

    private void emitCircle(float cx, float cy, float radius,
                            float r, float g, float b) {
        ensureCapacity(CIRCLE_SEGMENTS * 3);
        double step = 2.0 * Math.PI / CIRCLE_SEGMENTS;
        for (int i = 0; i < CIRCLE_SEGMENTS; i++) {
            double a0 = i * step;
            double a1 = (i + 1) * step;
            vertex(cx, cy, r, g, b);
            vertex(cx + (float)(Math.cos(a0) * radius), cy + (float)(Math.sin(a0) * radius), r, g, b);
            vertex(cx + (float)(Math.cos(a1) * radius), cy + (float)(Math.sin(a1) * radius), r, g, b);
        }
    }

    private void emitLine(float x1, float y1, float x2, float y2,
                          float r, float g, float b, float weight) {
        ensureCapacity(6);
        float dx = x2 - x1;
        float dy = y2 - y1;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len < 1e-6f) return;
        // Perpendicular, half-width
        float hw = weight * 0.5f;
        float nx = (-dy / len) * hw;
        float ny = (dx / len) * hw;

        vertex(x1 + nx, y1 + ny, r, g, b);
        vertex(x1 - nx, y1 - ny, r, g, b);
        vertex(x2 - nx, y2 - ny, r, g, b);

        vertex(x1 + nx, y1 + ny, r, g, b);
        vertex(x2 - nx, y2 - ny, r, g, b);
        vertex(x2 + nx, y2 + ny, r, g, b);
    }

    private void vertex(float x, float y, float r, float g, float b) {
        int base = vertexCount * FLOATS_PER_VERTEX;
        vertexBuf[base]     = x;
        vertexBuf[base + 1] = y;
        vertexBuf[base + 2] = r;
        vertexBuf[base + 3] = g;
        vertexBuf[base + 4] = b;
        vertexCount++;
    }

    private void ensureCapacity(int additional) {
        if (vertexCount + additional > MAX_VERTICES) {
            flush();
        }
    }

    private void flush() {
        if (vertexCount == 0) return;
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferSubData(GL_ARRAY_BUFFER, 0,
                java.util.Arrays.copyOf(vertexBuf, vertexCount * FLOATS_PER_VERTEX));
        glDrawArrays(GL_TRIANGLES, 0, vertexCount);
        vertexCount = 0;
    }

    // ── GL setup ─────────────────────────────────────────────────────────

    private void createShader() {
        int vert = compileShader(GL_VERTEX_SHADER, VERTEX_SHADER);
        int frag = compileShader(GL_FRAGMENT_SHADER, FRAGMENT_SHADER);

        program = glCreateProgram();
        glAttachShader(program, vert);
        glAttachShader(program, frag);
        glLinkProgram(program);

        if (glGetProgrami(program, GL_LINK_STATUS) == GL_FALSE) {
            String log = glGetProgramInfoLog(program);
            throw new RuntimeException("Canvas shader link failed:\n" + log);
        }

        glDeleteShader(vert);
        glDeleteShader(frag);

        uProjectionLoc = glGetUniformLocation(program, "uProjection");
    }

    private static int compileShader(int type, String source) {
        int shader = glCreateShader(type);
        glShaderSource(shader, source);
        glCompileShader(shader);
        if (glGetShaderi(shader, GL_COMPILE_STATUS) == GL_FALSE) {
            String log = glGetShaderInfoLog(shader);
            String kind = type == GL_VERTEX_SHADER ? "vertex" : "fragment";
            throw new RuntimeException("Canvas " + kind + " shader compile failed:\n" + log);
        }
        return shader;
    }

    private void createBuffers() {
        vao = glGenVertexArrays();
        glBindVertexArray(vao);

        vbo = glGenBuffers();
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferData(GL_ARRAY_BUFFER, (long) MAX_VERTICES * FLOATS_PER_VERTEX * Float.BYTES,
                     GL_DYNAMIC_DRAW);

        int stride = FLOATS_PER_VERTEX * Float.BYTES;
        // layout(location=0) vec2 aPos
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(0, 2, GL_FLOAT, false, stride, 0);
        // layout(location=1) vec3 aColor
        glEnableVertexAttribArray(1);
        glVertexAttribPointer(1, 3, GL_FLOAT, false, stride, 2L * Float.BYTES);

        glBindBuffer(GL_ARRAY_BUFFER, 0);
        glBindVertexArray(0);
    }
}
