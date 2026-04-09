package spn.language;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.source.Source;

/**
 * Entry point for the SPN language in the Truffle framework.
 *
 * The @Registration annotation makes this language discoverable by GraalVM's polyglot
 * engine. When someone evaluates SPN source via the Polyglot API, Truffle instantiates
 * this class and calls {@link #parse} to produce a CallTarget (a compiled AST).
 */
@TruffleLanguage.Registration(
        id = SpnLanguage.ID,
        name = "SPN",
        defaultMimeType = SpnLanguage.MIME_TYPE,
        characterMimeTypes = SpnLanguage.MIME_TYPE
)
public final class SpnLanguage extends TruffleLanguage<SpnContext> {

    public static final String ID = "spn";
    public static final String MIME_TYPE = "application/x-spn";

    @Override
    protected SpnContext createContext(Env env) {
        return new SpnContext(this, env);
    }

    /**
     * Parses SPN source code into an executable CallTarget via the Polyglot API.
     *
     * Note: The editor (spn-gui) drives parsing directly through {@code SpnParser},
     * bypassing this entry point. This method exists for GraalVM polyglot embedding
     * and is not yet wired up.
     */
    @Override
    protected CallTarget parse(ParsingRequest request) {
        throw new UnsupportedOperationException(
                "Polyglot parse not yet wired — use SpnParser directly");
    }
}
