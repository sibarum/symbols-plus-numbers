package spn.claude;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.models.messages.CacheControlEphemeral;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.ThinkingConfigAdaptive;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Thin wrapper over the Anthropic Java SDK that runs requests on a single
 * background worker thread and returns results as {@link CompletableFuture}s.
 *
 * <p>Each request rebuilds {@link MessageCreateParams} from the snapshot
 * settings provided at construction, plus the caller's conversation history.
 * The system prompt carries an ephemeral cache-control breakpoint so a long
 * prompt is paid for once across a chat session.
 *
 * <p>Phase 1: no tools, no streaming. Phase 2 will add a tool-use loop here.
 */
public final class ClaudeService {

    private static final long MAX_TOKENS = 16_000L;

    private final ClaudeSettings settings;
    private final ActivityLog log;
    private final AnthropicClient client;
    private final ExecutorService executor;

    public ClaudeService(ClaudeSettings settings, ActivityLog log) {
        this.settings = settings;
        this.log = log;
        this.client = AnthropicOkHttpClient.builder()
                .apiKey(settings.apiKey())
                .build();
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "claude-worker");
            t.setDaemon(true);
            return t;
        });
    }

    /** Submits a request. The future completes on the worker thread. */
    public CompletableFuture<Result> send(List<MessageParam> history) {
        return CompletableFuture.supplyAsync(() -> doSend(history), executor);
    }

    private Result doSend(List<MessageParam> history) {
        try {
            MessageCreateParams.Builder b = MessageCreateParams.builder()
                    .model(settings.model())
                    .maxTokens(MAX_TOKENS)
                    .thinking(ThinkingConfigAdaptive.builder().build())
                    .outputConfig(OutputConfig.builder()
                            .effort(parseEffort(settings.effort()))
                            .build())
                    .messages(history);

            String sys = settings.systemPrompt();
            if (sys != null && !sys.isBlank()) {
                b.systemOfTextBlockParams(List.of(
                        TextBlockParam.builder()
                                .text(sys)
                                .cacheControl(CacheControlEphemeral.builder().build())
                                .build()));
            }

            Message resp = client.messages().create(b.build());
            String text = extractText(resp);

            long inTok = resp.usage().inputTokens();
            long cacheTok = resp.usage().cacheReadInputTokens().orElse(0L);
            long outTok = resp.usage().outputTokens();
            log.assistant(text, inTok, cacheTok, outTok, settings.model());

            return new Result.Success(text);
        } catch (AnthropicServiceException e) {
            String type = e.errorType().map(Object::toString).orElse("unknown");
            String msg = e.getMessage();
            log.error(type, msg);
            return new Result.Error(type, msg);
        } catch (Exception e) {
            String msg = e.getClass().getSimpleName() + ": " + e.getMessage();
            log.error("client_error", msg);
            return new Result.Error("client_error", msg);
        }
    }

    private static String extractText(Message resp) {
        StringBuilder sb = new StringBuilder();
        for (var block : resp.content()) {
            block.text().ifPresent(t -> sb.append(t.text()));
        }
        return sb.toString();
    }

    /**
     * Map a settings string to one of the SDK's {@link OutputConfig.Effort}
     * static fields. Unknown values fall back to HIGH. {@code XHIGH} is the
     * recommended setting for coding/agentic work on Opus 4.7.
     */
    private static OutputConfig.Effort parseEffort(String s) {
        if (s == null) return OutputConfig.Effort.HIGH;
        return switch (s.trim().toLowerCase()) {
            case "low"    -> OutputConfig.Effort.LOW;
            case "medium" -> OutputConfig.Effort.MEDIUM;
            case "high"   -> OutputConfig.Effort.HIGH;
            case "xhigh"  -> OutputConfig.Effort.XHIGH;
            case "max"    -> OutputConfig.Effort.MAX;
            default       -> OutputConfig.Effort.HIGH;
        };
    }

    public void shutdown() {
        executor.shutdownNow();
    }

    public sealed interface Result {
        record Success(String text) implements Result {}
        record Error(String errorType, String message) implements Result {}
    }
}
