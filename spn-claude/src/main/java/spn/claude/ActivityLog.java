package spn.claude;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Append-only log of Claude interactions.
 *
 * <p>Each line gets persisted to disk (best-effort — log failures must
 * never break a chat) AND held in an in-memory ring buffer that the IDE
 * can render as a tab. Phase 2 will add tool-use entries; for now only
 * USER / ASSISTANT / ERROR lines are recorded.
 *
 * <p>Single-writer assumption: only the worker thread appends. Readers
 * (the log tab) snapshot the in-memory tail under a synchronized block.
 */
public final class ActivityLog {

    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")
                    .withZone(ZoneId.systemDefault());

    private static final int MAX_TAIL = 1000;

    private final Path file;
    private final List<String> tail = new ArrayList<>();

    public ActivityLog(Path file) {
        this.file = file;
    }

    public synchronized void user(String text) {
        append("USER", null, text);
    }

    public synchronized void assistant(String text, long inputTokens, long cacheReadTokens,
                                        long outputTokens, String model) {
        String meta = String.format("input=%d cached=%d output=%d model=%s",
                inputTokens, cacheReadTokens, outputTokens, model);
        append("ASSISTANT", meta, text);
    }

    public synchronized void error(String errorType, String message) {
        append("ERROR", errorType, message);
    }

    public synchronized List<String> snapshotTail() {
        return new ArrayList<>(tail);
    }

    private void append(String level, String meta, String text) {
        String stamp = TS.format(Instant.now());
        StringBuilder sb = new StringBuilder();
        sb.append('[').append(stamp).append("] ").append(level);
        if (meta != null) sb.append(" (").append(meta).append(')');
        sb.append(": ").append(oneLine(text));
        String line = sb.toString();

        tail.add(line);
        while (tail.size() > MAX_TAIL) tail.remove(0);

        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, line + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // Disk-log failures don't break the chat. The in-memory tail still works.
        }
    }

    /** Collapse newlines so each log entry stays on one line. */
    private static String oneLine(String s) {
        if (s == null) return "";
        return s.replace("\r\n", " ").replace('\n', ' ').replace('\r', ' ');
    }
}
