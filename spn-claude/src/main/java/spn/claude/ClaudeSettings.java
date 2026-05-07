package spn.claude;

/**
 * User-editable Claude integration settings, persisted to
 * {@code ~/.spn/claude.settings} as JSON.
 *
 * <p>Mutable to keep load / save / GUI editing simple — instances are not
 * shared across threads (the worker reads via {@link ClaudeService} which
 * holds its own snapshot at construction).
 */
public final class ClaudeSettings {

    public static final String DEFAULT_MODEL = "claude-opus-4-7";
    public static final String DEFAULT_EFFORT = "xhigh";
    public static final String DEFAULT_SYSTEM_PROMPT =
            "You are a coding assistant integrated into the SPN language IDE. "
                    + "SPN is a Truffle-based language with nominal typing, no `if` keyword "
                    + "(use match/cond), `--` line comments, and macros instead of generics. "
                    + "Be concise and direct.";

    private String apiKey = "";
    private String model = DEFAULT_MODEL;
    private String effort = DEFAULT_EFFORT;
    private String systemPrompt = DEFAULT_SYSTEM_PROMPT;
    private boolean allowIdeMutations = false;
    private String logPath = "";

    public String apiKey() { return apiKey; }
    public void setApiKey(String v) { this.apiKey = v == null ? "" : v; }

    public String model() { return model; }
    public void setModel(String v) { this.model = v == null || v.isBlank() ? DEFAULT_MODEL : v; }

    public String effort() { return effort; }
    public void setEffort(String v) { this.effort = v == null || v.isBlank() ? DEFAULT_EFFORT : v; }

    public String systemPrompt() { return systemPrompt; }
    public void setSystemPrompt(String v) { this.systemPrompt = v == null ? "" : v; }

    public boolean allowIdeMutations() { return allowIdeMutations; }
    public void setAllowIdeMutations(boolean v) { this.allowIdeMutations = v; }

    public String logPath() { return logPath; }
    public void setLogPath(String v) { this.logPath = v == null ? "" : v; }

    public boolean hasApiKey() { return !apiKey.isBlank(); }
}
