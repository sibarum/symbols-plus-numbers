package spn.language;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.source.Source;
import spn.type.FieldType;

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

    static {
        FieldType.installCallableTest(v -> v instanceof CallTarget);
    }

    @Override
    protected SpnContext createContext(Env env) {
        return new SpnContext(this, env);
    }

    /**
     * Parses SPN source code into an executable CallTarget via the Polyglot API.
     *
     * <p>Not yet wired — the editor (spn-gui) and tests drive parsing directly
     * through {@code SpnParser}, bypassing this entry point. Wiring this
     * requires resolving the spn-ast → spn-parse dependency direction (parser
     * depends on AST types, not vice versa). A callback or service-loader
     * pattern would work.
     */
    @Override
    protected CallTarget parse(ParsingRequest request) {
        throw new UnsupportedOperationException(
                "Polyglot parse not yet wired — use SpnParser directly");
    }
}
