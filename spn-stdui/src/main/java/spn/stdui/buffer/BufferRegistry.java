package spn.stdui.buffer;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Named collection of {@link TextBuffer}s. The "filesystem" of the TTY metaphor:
 * modes open buffers by name, and multiple modes can share the same buffer.
 */
public class BufferRegistry {

    private final Map<String, TextBuffer> buffers = new LinkedHashMap<>();

    /** Get or create a buffer by name. */
    public TextBuffer open(String name) {
        return buffers.computeIfAbsent(name, k -> new TextBuffer());
    }

    /** Get an existing buffer, or empty. */
    public Optional<TextBuffer> get(String name) {
        return Optional.ofNullable(buffers.get(name));
    }

    /** Register an existing buffer under a name. */
    public void register(String name, TextBuffer buffer) {
        buffers.put(name, buffer);
    }

    /** All buffer names. */
    public Iterable<String> names() {
        return buffers.keySet();
    }

    /** Close (remove) a buffer by name. */
    public void close(String name) {
        buffers.remove(name);
    }
}
