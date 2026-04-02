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
     * Parses SPN source code into an executable CallTarget.
     * This is where a parser would build the AST from source text.
     * For now, this is a placeholder -- you'll wire in a parser once syntax is designed.
     */
    @Override
    protected CallTarget parse(ParsingRequest request) {
        Source source = request.getSource();
        // TODO: parse source.getCharacters() into an AST, wrap in SpnRootNode,
        //       then return SpnRootNode.getCallTarget()
        throw new UnsupportedOperationException("Parser not yet implemented");
    }
}
