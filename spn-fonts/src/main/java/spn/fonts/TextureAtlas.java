package spn.fonts;

import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE;
import static org.lwjgl.opengl.GL30.GL_R8;

/**
 * A dynamically-growing single-channel texture atlas using shelf-packing.
 *
 * Regions are placed left-to-right in rows. When a region doesn't fit on the
 * current row, it advances to the next. A small solid-white block is reserved
 * at (0,0) for solid-color rectangle rendering.
 */
public final class TextureAtlas {

    private final int size;
    private int texture;

    // Shelf-packing cursor
    private int cursorX;
    private int cursorY;
    private int rowHeight;

    // UV for the solid-white region (for rectangle drawing)
    private final float solidU;
    private final float solidV;

    /**
     * UV coordinates for a placed region in the atlas.
     */
    public record Region(float u0, float v0, float u1, float v1) {}

    /**
     * Creates and initializes the atlas texture.
     *
     * @param size texture width and height in pixels (must be power of 2)
     */
    public TextureAtlas(int size) {
        this.size = size;
        this.solidU = 2f / size;
        this.solidV = 2f / size;
        create();
    }

    /** Returns the GL texture handle. */
    public int getTexture() { return texture; }

    /** Returns the atlas size in pixels. */
    public int getSize() { return size; }

    /** Returns the U coordinate pointing at the solid-white region (for drawRect). */
    public float getSolidU() { return solidU; }

    /** Returns the V coordinate pointing at the solid-white region (for drawRect). */
    public float getSolidV() { return solidV; }

    /**
     * Places a region of pixel data into the atlas using shelf-packing.
     *
     * @param data   single-channel pixel data (GL_RED, GL_UNSIGNED_BYTE)
     * @param width  region width in pixels
     * @param height region height in pixels
     * @return the UV coordinates of the placed region
     * @throws RuntimeException if the atlas is full
     */
    public Region place(ByteBuffer data, int width, int height) {
        // Advance to next row if this region doesn't fit
        if (cursorX + width > size) {
            cursorX = 0;
            cursorY += rowHeight;
            rowHeight = 0;
        }
        if (cursorY + height > size) {
            throw new RuntimeException("Texture atlas overflow — increase atlas size (current: " + size + ")");
        }

        // Upload
        glBindTexture(GL_TEXTURE_2D, texture);
        glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
        glTexSubImage2D(GL_TEXTURE_2D, 0,
                cursorX, cursorY, width, height,
                GL_RED, GL_UNSIGNED_BYTE, data);

        // Compute UVs
        Region region = new Region(
                cursorX / (float) size,
                cursorY / (float) size,
                (cursorX + width) / (float) size,
                (cursorY + height) / (float) size
        );

        // Advance cursor
        cursorX += width;
        rowHeight = Math.max(rowHeight, height);

        return region;
    }

    /** Bind this atlas texture to the active texture unit. */
    public void bind() {
        glBindTexture(GL_TEXTURE_2D, texture);
    }

    /** Unbind the texture. */
    public void unbind() {
        glBindTexture(GL_TEXTURE_2D, 0);
    }

    /** Free the GL texture. */
    public void dispose() {
        if (texture != 0) {
            glDeleteTextures(texture);
            texture = 0;
        }
    }

    // ── Internal ──────────────────────────────────────────────────────────

    private void create() {
        texture = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, texture);
        glPixelStorei(GL_UNPACK_ALIGNMENT, 1);

        // Allocate empty atlas
        glTexImage2D(GL_TEXTURE_2D, 0, GL_R8, size, size,
                0, GL_RED, GL_UNSIGNED_BYTE, (ByteBuffer) null);

        // Reserve a 4x4 solid-white block at (0,0) for rectangle rendering
        ByteBuffer solid = MemoryUtil.memAlloc(4 * 4);
        for (int i = 0; i < 16; i++) solid.put(i, (byte) 255);
        glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, 4, 4,
                GL_RED, GL_UNSIGNED_BYTE, solid);
        MemoryUtil.memFree(solid);

        // Start packing after the reserved block
        cursorX = 4;
        cursorY = 0;
        rowHeight = 4;

        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glBindTexture(GL_TEXTURE_2D, 0);
    }
}
