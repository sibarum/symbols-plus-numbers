package spn.canvasgui.font;

import spn.fonts.SdfFontRenderer;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Helpers for turning a path/resource into an {@link SdfFontRenderer} and
 * adding it to a {@link FontRegistry}.
 *
 * <p>Two sources: the canvasgui / spn-fonts classpath (for bundled families)
 * and an absolute filesystem path (for {@code guiLoadFont(...)} after
 * module-relative resolution).
 */
public final class FontLoader {

    private FontLoader() {}

    /**
     * Try to load a bundled variant from the classpath. On failure, logs to
     * stderr and returns {@code false} — the caller can register a fallback.
     */
    public static boolean tryLoadBundledVariant(FontRegistry registry,
                                                String familyName,
                                                boolean bold, boolean italic,
                                                String classpathResource,
                                                float fontSize) {
        try (InputStream in = FontLoader.class.getClassLoader()
                .getResourceAsStream(classpathResource)) {
            if (in == null) {
                System.err.println("[canvasgui] no bundled font at " + classpathResource
                        + " — :" + familyName
                        + (bold ? " bold" : "") + (italic ? " italic" : "")
                        + " not registered");
                return false;
            }
            byte[] bytes = in.readAllBytes();
            SdfFontRenderer r = new SdfFontRenderer();
            r.init(bytes, fontSize, classpathResource);
            registry.register(familyName, bold, italic, r);
            return true;
        } catch (IOException | RuntimeException e) {
            System.err.println("[canvasgui] failed to load " + classpathResource
                    + ": " + e.getMessage());
            return false;
        }
    }

    /** Loads a whole family's regular+bold+italic variants from a path pattern. */
    public static boolean tryLoadBundledFamily(FontRegistry registry, String familyName,
                                               String regularPath, String boldPath,
                                               String italicPath, float fontSize) {
        boolean ok = tryLoadBundledVariant(registry, familyName, false, false, regularPath, fontSize);
        tryLoadBundledVariant(registry, familyName, true,  false, boldPath,   fontSize);
        tryLoadBundledVariant(registry, familyName, false, true,  italicPath, fontSize);
        return ok;
    }

    /**
     * Load a TTF from an already-resolved filesystem path. Registered as the
     * regular variant of the given family (no bold/italic wiring from a single
     * file — if you want variants, call this multiple times with distinct
     * family names or extend the API).
     */
    public static void loadFromPath(FontRegistry registry, String familyName,
                                    String path, float fontSize) {
        try {
            byte[] bytes = Files.readAllBytes(Path.of(path));
            SdfFontRenderer r = new SdfFontRenderer();
            r.init(bytes, fontSize, path);
            registry.registerRegular(familyName, r);
        } catch (IOException | RuntimeException e) {
            System.err.println("[canvasgui] failed to load font '" + familyName
                    + "' from " + path + ": " + e.getMessage());
        }
    }
}
