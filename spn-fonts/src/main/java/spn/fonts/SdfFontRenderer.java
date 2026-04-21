package spn.fonts;

import spn.stdui.render.ColorSpan;
import spn.stdui.render.Renderer;

import org.lwjgl.stb.STBTTFontinfo;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL13.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;
import static org.lwjgl.stb.STBTruetype.*;

/**
 * SDF (Signed Distance Field) font renderer with a dynamic glyph atlas.
 *
 * Glyphs are rendered on demand as they are first encountered. The atlas
 * grows incrementally using shelf-packing — no fixed character set is
 * pre-loaded at init time.
 */
public class SdfFontRenderer implements Renderer {

    private static final int ATLAS_SIZE = 1024;
    private static final int SDF_PADDING = 8;
    private static final int ON_EDGE_VALUE = 128;
    private static final float PIXEL_DIST_SCALE = (float) ON_EDGE_VALUE / SDF_PADDING;
    private static final int MAX_CHARS_PER_DRAW = 512;

    // 6 vertices per char (2 triangles), 7 floats per vertex (x, y, u, v, r, g, b)
    private static final int FLOATS_PER_VERTEX = 7;
    private static final int FLOATS_PER_CHAR = 6 * FLOATS_PER_VERTEX;

    private static class Glyph {
        float u0, v0, u1, v1;       // texture coordinates in atlas
        int width, height;           // SDF bitmap dimensions
        int offsetX, offsetY;        // offset from glyph origin to top-left of bitmap
        int advance;                 // unscaled horizontal advance
    }

    private final Map<Integer, Glyph> glyphs = new HashMap<>();

    private TextureAtlas atlas;
    private int program;
    private int vbo;
    private int uProjectionLoc, uSmoothingLoc;

    // VAOs are per-context (not shared across GL contexts), so we track one per window
    private final Map<Long, Integer> vaoByContext = new HashMap<>();

    private float pixelScale;        // stbtt scale factor for the chosen font size
    private int ascent, descent;     // unscaled vertical metrics

    private ByteBuffer fontData;     // must stay alive while stbtt is using it
    private STBTTFontinfo fontInfo;  // kept alive for on-demand glyph rendering
    private final float[] vertexBuf = new float[MAX_CHARS_PER_DRAW * FLOATS_PER_CHAR];

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /**
     * Load a TTF font and create an empty dynamic atlas + GL resources.
     * No glyphs are rasterized at this point — they are loaded on demand.
     *
     * @param fontPath path to a .ttf file
     * @param fontSize pixel height to rasterize the SDF at (48+ recommended)
     */
    public void init(String fontPath, float fontSize) {
        initFromBuffer(loadFileToDirectBuffer(fontPath), fontSize, fontPath);
    }

    /**
     * Like {@link #init(String, float)} but from an already-loaded byte array
     * (classpath resource, network, memory, etc.). The bytes are copied into
     * a native direct buffer internally.
     */
    public void init(byte[] fontBytes, float fontSize, String displayName) {
        ByteBuffer buf = MemoryUtil.memAlloc(fontBytes.length);
        buf.put(fontBytes).flip();
        initFromBuffer(buf, fontSize, displayName);
    }

    private void initFromBuffer(ByteBuffer buffer, float fontSize, String displayName) {
        fontInfo = STBTTFontinfo.create();
        fontData = buffer;

        if (!stbtt_InitFont(fontInfo, fontData)) {
            throw new RuntimeException("Failed to initialise font: " + displayName);
        }

        pixelScale = stbtt_ScaleForPixelHeight(fontInfo, fontSize);

        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer pAscent  = stack.mallocInt(1);
            IntBuffer pDescent = stack.mallocInt(1);
            IntBuffer pLineGap = stack.mallocInt(1);
            stbtt_GetFontVMetrics(fontInfo, pAscent, pDescent, pLineGap);
            ascent  = pAscent.get(0);
            descent = pDescent.get(0);
        }

        atlas = new TextureAtlas(ATLAS_SIZE);
        createShader();
        createBuffers();
    }

    /**
     * Ensure a glyph for the given codepoint is present in the atlas.
     * If it is already loaded this is a no-op (fast HashMap lookup).
     * Must be called with an active GL context.
     */
    public void ensureGlyph(int codepoint) {
        if (glyphs.containsKey(codepoint)) return;

        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer pAdv = stack.mallocInt(1);
            IntBuffer pLsb = stack.mallocInt(1);
            IntBuffer pW   = stack.mallocInt(1);
            IntBuffer pH   = stack.mallocInt(1);
            IntBuffer pXo  = stack.mallocInt(1);
            IntBuffer pYo  = stack.mallocInt(1);

            stbtt_GetCodepointHMetrics(fontInfo, codepoint, pAdv, pLsb);

            ByteBuffer sdf = stbtt_GetCodepointSDF(
                    fontInfo, pixelScale, codepoint,
                    SDF_PADDING, (byte) ON_EDGE_VALUE, PIXEL_DIST_SCALE,
                    pW, pH, pXo, pYo);

            Glyph g = new Glyph();
            g.advance = pAdv.get(0);

            if (sdf != null) {
                int w = pW.get(0), h = pH.get(0);
                g.width   = w;
                g.height  = h;
                g.offsetX = pXo.get(0);
                g.offsetY = pYo.get(0);

                TextureAtlas.Region region = atlas.place(sdf, w, h);
                g.u0 = region.u0();
                g.v0 = region.v0();
                g.u1 = region.u1();
                g.v1 = region.v1();

                stbtt_FreeSDF(sdf);
            }

            glyphs.put(codepoint, g);
        }
    }

    /** Measure the width of a string in pixels at the given scale. */
    public float getTextWidth(String text, float scale) {
        float x = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            ensureGlyph(cp);
            Glyph g = glyphs.get(cp);
            if (g == null) continue;
            x += g.advance * pixelScale * scale;
        }
        return x;
    }

    /** Returns the line height (ascent - descent) at the given scale. */
    public float getLineHeight(float scale) {
        return (ascent - descent) * pixelScale * scale;
    }

    /**
     * Set up GL state for 2D text rendering. Call once per frame before drawText.
     */
    public void beginText(int screenWidth, int screenHeight) {
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        glDisable(GL_DEPTH_TEST);

        glUseProgram(program);

        // Orthographic projection: (0,0) = top-left, (w,h) = bottom-right
        float[] ortho = {
                2f / screenWidth,  0,                    0, 0,
                0,                -2f / screenHeight,    0, 0,
                0,                 0,                   -1, 0,
               -1,                 1,                    0, 1
        };
        glUniformMatrix4fv(uProjectionLoc, false, ortho);

        glActiveTexture(GL_TEXTURE0);
        atlas.bind();
    }

    /**
     * Render a string in a single color. Must be called between beginText/endText.
     * Unknown glyphs are loaded into the atlas on the fly.
     */
    public void drawText(String text, float x, float y, float scale, float r, float g, float b) {
        // Ensure every codepoint is in the atlas before batching geometry
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            ensureGlyph(cp);
        }

        float smoothing = Math.min(0.25f / scale, 0.15f);
        glUniform1f(uSmoothingLoc, smoothing);

        float s = pixelScale * scale;
        float cursorX = x;
        int charIndex = 0;
        int totalChars = text.length();

        while (charIndex < totalChars) {
            int batchSize = 0;
            int bufPos = 0;

            while (charIndex < totalChars && batchSize < MAX_CHARS_PER_DRAW) {
                int cp = text.codePointAt(charIndex);
                charIndex += Character.charCount(cp);
                Glyph gl = glyphs.get(cp);
                if (gl == null || gl.width == 0) {
                    cursorX += gl != null ? gl.advance * s : 0;
                    continue;
                }

                bufPos = emitGlyph(bufPos, gl, cursorX, y, scale, r, g, b);
                cursorX += gl.advance * s;
                batchSize++;
            }

            flushBatch(batchSize, bufPos);
        }
    }

    /**
     * Render a line of text with per-span coloring in a single batched draw call.
     * Each span's color is baked into the vertex data, avoiding per-span draw calls.
     *
     * @param line      full line text
     * @param spans     color spans for the line
     * @param x         pixel x of column 0
     * @param y         pixel y (baseline)
     * @param scale     font scale
     * @param startCol  first visible column (scroll offset)
     * @param endCol    one past last visible column
     */
    public void drawColoredLine(String line, ColorSpan[] spans, float x, float y,
                                float scale, int startCol, int endCol) {
        if (startCol >= endCol || startCol >= line.length()) return;
        endCol = Math.min(endCol, line.length());

        // Ensure all visible glyphs are in the atlas
        for (int i = startCol; i < endCol; ) {
            int cp = line.codePointAt(i);
            i += Character.charCount(cp);
            ensureGlyph(cp);
        }

        float smoothing = Math.min(0.25f / scale, 0.15f);
        glUniform1f(uSmoothingLoc, smoothing);

        float s = pixelScale * scale;
        float charAdvance = getCharAdvance(s);

        int batchSize = 0;
        int bufPos = 0;

        for (ColorSpan span : spans) {
            int tStart = Math.max(span.startCol(), startCol);
            int tEnd = Math.min(span.endCol(), endCol);
            if (tStart >= tEnd) continue;

            float r = span.r(), g = span.g(), b = span.b();
            float cursorX = x + (tStart - startCol) * charAdvance;

            for (int ci = tStart; ci < tEnd; ) {
                int cp = line.codePointAt(ci);
                ci += Character.charCount(cp);
                Glyph gl = glyphs.get(cp);
                if (gl == null || gl.width == 0) {
                    cursorX += gl != null ? gl.advance * s : 0;
                    continue;
                }

                // Flush if batch is full
                if (batchSize >= MAX_CHARS_PER_DRAW) {
                    flushBatch(batchSize, bufPos);
                    batchSize = 0;
                    bufPos = 0;
                }

                bufPos = emitGlyph(bufPos, gl, cursorX, y, scale, r, g, b);
                cursorX += gl.advance * s;
                batchSize++;
            }
        }

        flushBatch(batchSize, bufPos);
    }

    /** Write 6 vertices (2 triangles) for a glyph into vertexBuf. Returns updated bufPos. */
    private int emitGlyph(int bufPos, Glyph gl, float cursorX, float y,
                          float scale, float r, float g, float b) {
        float x0 = cursorX + gl.offsetX * scale;
        float y0 = y + gl.offsetY * scale;
        float x1 = x0 + gl.width * scale;
        float y1 = y0 + gl.height * scale;

        // Triangle 1
        bufPos = putVertex(bufPos, x0, y0, gl.u0, gl.v0, r, g, b);
        bufPos = putVertex(bufPos, x1, y0, gl.u1, gl.v0, r, g, b);
        bufPos = putVertex(bufPos, x1, y1, gl.u1, gl.v1, r, g, b);
        // Triangle 2
        bufPos = putVertex(bufPos, x0, y0, gl.u0, gl.v0, r, g, b);
        bufPos = putVertex(bufPos, x1, y1, gl.u1, gl.v1, r, g, b);
        bufPos = putVertex(bufPos, x0, y1, gl.u0, gl.v1, r, g, b);
        return bufPos;
    }

    private int putVertex(int bufPos, float x, float y, float u, float v,
                          float r, float g, float b) {
        vertexBuf[bufPos++] = x;
        vertexBuf[bufPos++] = y;
        vertexBuf[bufPos++] = u;
        vertexBuf[bufPos++] = v;
        vertexBuf[bufPos++] = r;
        vertexBuf[bufPos++] = g;
        vertexBuf[bufPos++] = b;
        return bufPos;
    }

    private void flushBatch(int batchSize, int bufPos) {
        if (batchSize == 0) return;

        // Defensively re-establish all GL state before drawing.
        // When the font renderer is shared across GL contexts (editor + canvas),
        // the shader/texture/VAO state may not be what we expect — another
        // renderer (CanvasRenderer) may have changed it between our beginText
        // and this flush. Re-binding everything is cheap and eliminates an
        // intermittent rendering failure where text silently doesn't appear.
        glUseProgram(program);
        glActiveTexture(GL_TEXTURE0);
        atlas.bind();
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);

        glBindVertexArray(ensureVao());
        glBindBuffer(GL_ARRAY_BUFFER, vbo);

        FloatBuffer fb = MemoryUtil.memAllocFloat(bufPos);
        fb.put(vertexBuf, 0, bufPos).flip();
        glBufferSubData(GL_ARRAY_BUFFER, 0, fb);
        MemoryUtil.memFree(fb);

        glDrawArrays(GL_TRIANGLES, 0, batchSize * 6);
        glBindVertexArray(0);
    }

    /** Returns the pixel advance for one character at the given computed scale. */
    private float getCharAdvance(float s) {
        // Use 'M' as the reference glyph (monospace — any would do)
        ensureGlyph('M');
        Glyph m = glyphs.get((int) 'M');
        return m != null ? m.advance * s : 0;
    }

    /**
     * Draw a solid filled rectangle. Must be called between beginText/endText.
     * Uses a solid-white texel in the atlas to produce an opaque quad.
     */
    public void drawRect(float x, float y, float w, float h, float r, float g, float b) {
        glUniform1f(uSmoothingLoc, 0.1f);

        float su = atlas.getSolidU(), sv = atlas.getSolidV();
        float x1 = x + w;
        float y1 = y + h;

        int bufPos = 0;
        bufPos = putVertex(bufPos, x,  y,  su, sv, r, g, b);
        bufPos = putVertex(bufPos, x1, y,  su, sv, r, g, b);
        bufPos = putVertex(bufPos, x1, y1, su, sv, r, g, b);
        bufPos = putVertex(bufPos, x,  y,  su, sv, r, g, b);
        bufPos = putVertex(bufPos, x1, y1, su, sv, r, g, b);
        bufPos = putVertex(bufPos, x,  y1, su, sv, r, g, b);

        flushBatch(1, bufPos);
    }

    // ---- Renderer interface aliases ----

    @Override public void beginFrame(int screenWidth, int screenHeight) { beginText(screenWidth, screenHeight); }
    @Override public void endFrame() { endText(); }

    /** Restore GL state after text rendering. */
    public void endText() {
        glUseProgram(0);
        atlas.unbind();
        glDisable(GL_BLEND);
        glEnable(GL_DEPTH_TEST);
    }

    /** Free all GL resources and native memory. */
    public void dispose() {
        atlas.dispose();
        glDeleteBuffers(vbo);
        for (int vao : vaoByContext.values()) glDeleteVertexArrays(vao);
        vaoByContext.clear();
        glDeleteProgram(program);
        if (fontData != null) {
            MemoryUtil.memFree(fontData);
            fontData = null;
        }
        fontInfo = null;
    }

    // -----------------------------------------------------------------------
    // Shader
    // -----------------------------------------------------------------------

    private static final String VERTEX_SHADER = """
            #version 330 core
            layout(location = 0) in vec2 aPos;
            layout(location = 1) in vec2 aUV;
            layout(location = 2) in vec3 aColor;

            uniform mat4 uProjection;

            out vec2 vUV;
            out vec3 vColor;

            void main() {
                gl_Position = uProjection * vec4(aPos, 0.0, 1.0);
                vUV = aUV;
                vColor = aColor;
            }
            """;

    private static final String FRAGMENT_SHADER = """
            #version 330 core
            in vec2 vUV;
            in vec3 vColor;

            uniform sampler2D uFontAtlas;
            uniform float uSmoothing;

            out vec4 fragColor;

            void main() {
                float dist  = texture(uFontAtlas, vUV).r;
                float alpha = smoothstep(0.5 - uSmoothing, 0.5 + uSmoothing, dist);
                if (alpha < 0.01) discard;
                fragColor = vec4(vColor, alpha);
            }
            """;

    private void createShader() {
        int vert = compileShader(GL_VERTEX_SHADER,   VERTEX_SHADER);
        int frag = compileShader(GL_FRAGMENT_SHADER, FRAGMENT_SHADER);

        program = glCreateProgram();
        glAttachShader(program, vert);
        glAttachShader(program, frag);
        glLinkProgram(program);

        if (glGetProgrami(program, GL_LINK_STATUS) == GL_FALSE) {
            String log = glGetProgramInfoLog(program);
            throw new RuntimeException("Shader link failed:\n" + log);
        }

        glDeleteShader(vert);
        glDeleteShader(frag);

        uProjectionLoc = glGetUniformLocation(program, "uProjection");
        uSmoothingLoc  = glGetUniformLocation(program, "uSmoothing");

        glUseProgram(program);
        glUniform1i(glGetUniformLocation(program, "uFontAtlas"), 0);
        glUseProgram(0);
    }

    private static int compileShader(int type, String source) {
        int shader = glCreateShader(type);
        glShaderSource(shader, source);
        glCompileShader(shader);
        if (glGetShaderi(shader, GL_COMPILE_STATUS) == GL_FALSE) {
            String log = glGetShaderInfoLog(shader);
            String kind = type == GL_VERTEX_SHADER ? "vertex" : "fragment";
            throw new RuntimeException(kind + " shader compile failed:\n" + log);
        }
        return shader;
    }

    // -----------------------------------------------------------------------
    // Buffers
    // -----------------------------------------------------------------------

    private void createBuffers() {
        vbo = glGenBuffers();
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferData(GL_ARRAY_BUFFER, (long) MAX_CHARS_PER_DRAW * FLOATS_PER_CHAR * Float.BYTES, GL_DYNAMIC_DRAW);
        glBindBuffer(GL_ARRAY_BUFFER, 0);

        ensureVao();
    }

    private int ensureVao() {
        long ctx = org.lwjgl.glfw.GLFW.glfwGetCurrentContext();
        Integer vao = vaoByContext.get(ctx);
        if (vao != null) return vao;

        int newVao = glGenVertexArrays();
        glBindVertexArray(newVao);

        int stride = FLOATS_PER_VERTEX * Float.BYTES;

        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(0, 2, GL_FLOAT, false, stride, 0);
        glEnableVertexAttribArray(1);
        glVertexAttribPointer(1, 2, GL_FLOAT, false, stride, 2L * Float.BYTES);
        glEnableVertexAttribArray(2);
        glVertexAttribPointer(2, 3, GL_FLOAT, false, stride, 4L * Float.BYTES);

        glBindVertexArray(0);
        glBindBuffer(GL_ARRAY_BUFFER, 0);

        vaoByContext.put(ctx, newVao);
        return newVao;
    }

    // -----------------------------------------------------------------------
    // File loading
    // -----------------------------------------------------------------------

    private static ByteBuffer loadFileToDirectBuffer(String path) {
        try {
            byte[] bytes = Files.readAllBytes(Path.of(path));
            ByteBuffer buf = MemoryUtil.memAlloc(bytes.length);
            buf.put(bytes).flip();
            return buf;
        } catch (IOException e) {
            throw new RuntimeException("Failed to load font file: " + path, e);
        }
    }
}
