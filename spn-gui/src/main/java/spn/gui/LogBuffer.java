package spn.gui;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * In-memory log buffer with a maximum line count.
 * Every HUD flash message is appended here with a timestamp.
 */
public class LogBuffer {

    private static final int MAX_LINES = 500;
    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("HH:mm:ss");

    private final List<String> lines = new ArrayList<>();

    /** Append a log entry with timestamp. */
    public void append(String message, boolean isError) {
        String prefix = LocalTime.now().format(TIME_FMT);
        String tag = isError ? " [ERROR] " : " [INFO]  ";
        lines.add(prefix + tag + message);
        // Trim to max size
        while (lines.size() > MAX_LINES) {
            lines.removeFirst();
        }
    }

    /** Get all log lines as a single string. */
    public String getText() {
        return String.join("\n", lines);
    }

    /** Number of log lines. */
    public int lineCount() {
        return lines.size();
    }

    public boolean isEmpty() {
        return lines.isEmpty();
    }
}
