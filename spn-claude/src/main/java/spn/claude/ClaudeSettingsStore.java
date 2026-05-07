package spn.claude;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Loads and saves {@link ClaudeSettings} to {@code ~/.spn/claude.settings}.
 *
 * <p>Writes go through a temp file + atomic rename so a crashed save can't
 * leave a half-written settings file. Missing file → fresh defaults.
 *
 * <p>Jackson is already on the classpath via the anthropic-java SDK.
 */
public final class ClaudeSettingsStore {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private final Path settingsPath;
    private final Path defaultLogPath;

    public ClaudeSettingsStore() {
        Path home = Path.of(System.getProperty("user.home"));
        Path spnDir = home.resolve(".spn");
        this.settingsPath = spnDir.resolve("claude.settings");
        this.defaultLogPath = spnDir.resolve("claude.log");
    }

    /** Visible for use by ActivityLog when settings.logPath is unset. */
    public Path defaultLogPath() { return defaultLogPath; }

    public Path settingsPath() { return settingsPath; }

    /** Loads settings, returning fresh defaults if the file doesn't exist or is unreadable. */
    public ClaudeSettings load() {
        if (!Files.exists(settingsPath)) return new ClaudeSettings();
        try {
            return MAPPER.readValue(settingsPath.toFile(), ClaudeSettings.class);
        } catch (IOException e) {
            // Corrupt file — return defaults rather than crashing the IDE.
            // The user can re-enter their key via the Settings mode.
            return new ClaudeSettings();
        }
    }

    /** Atomically writes settings. Creates {@code ~/.spn/} if missing. */
    public void save(ClaudeSettings settings) throws IOException {
        Files.createDirectories(settingsPath.getParent());
        Path tmp = settingsPath.resolveSibling(settingsPath.getFileName() + ".tmp");
        MAPPER.writeValue(tmp.toFile(), settings);
        try {
            Files.move(tmp, settingsPath,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicFailed) {
            Files.move(tmp, settingsPath, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
