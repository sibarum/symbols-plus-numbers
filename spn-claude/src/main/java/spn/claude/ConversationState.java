package spn.claude;

import com.anthropic.models.messages.MessageParam;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * In-memory chat history for a single conversation. Each turn is one
 * {@link MessageParam}; tool-use turns will append additional blocks in
 * Phase 2 but for now every turn is a single text content block.
 *
 * <p>Not thread-safe — only the main thread mutates this; the worker
 * thread receives a copy via {@link #snapshot()} for each request.
 */
public final class ConversationState {

    public record Turn(Role role, String text) {
        public enum Role { USER, ASSISTANT }
    }

    private final List<Turn> turns = new ArrayList<>();

    public void appendUser(String text) {
        turns.add(new Turn(Turn.Role.USER, text));
    }

    public void appendAssistant(String text) {
        turns.add(new Turn(Turn.Role.ASSISTANT, text));
    }

    public List<Turn> turns() {
        return Collections.unmodifiableList(turns);
    }

    public boolean isEmpty() {
        return turns.isEmpty();
    }

    public void clear() {
        turns.clear();
    }

    /**
     * Snapshots the current history as SDK MessageParams suitable for
     * {@code MessageCreateParams.builder().messages(...)}.
     */
    public List<MessageParam> snapshot() {
        List<MessageParam> out = new ArrayList<>(turns.size());
        for (Turn t : turns) {
            MessageParam.Role role = (t.role == Turn.Role.USER)
                    ? MessageParam.Role.USER
                    : MessageParam.Role.ASSISTANT;
            out.add(MessageParam.builder()
                    .role(role)
                    .content(t.text)
                    .build());
        }
        return out;
    }
}
