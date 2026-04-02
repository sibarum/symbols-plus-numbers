package spn.language;

import com.oracle.truffle.api.TruffleLanguage;

/**
 * The runtime context for an SPN language instance.
 *
 * Each polyglot Context that uses SPN gets its own SpnContext. This is where you
 * store per-context state: global variables, the function registry, output streams, etc.
 * For now it's minimal -- expand it as the language grows.
 */
public final class SpnContext {

    private final SpnLanguage language;
    private final TruffleLanguage.Env env;

    public SpnContext(SpnLanguage language, TruffleLanguage.Env env) {
        this.language = language;
        this.env = env;
    }

    public SpnLanguage getLanguage() {
        return language;
    }

    public TruffleLanguage.Env getEnv() {
        return env;
    }
}
