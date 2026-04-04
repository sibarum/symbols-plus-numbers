package spn.canvas;

import com.oracle.truffle.api.CallTarget;
import spn.canvas.node.*;
import spn.node.BuiltinFactory;
import spn.node.SpnExpressionNode;

import java.util.Map;

/**
 * Registers all canvas drawing functions into a builtin registry.
 * Called by the host (e.g., EditorWindow) before parsing SPN code.
 */
public final class CanvasBuiltins {

    private CanvasBuiltins() {}

    /**
     * @param registry       the parser's builtin registry
     * @param callTargetResolver resolves a function name to its CallTarget (for animate's callback)
     */
    public static void register(Map<String, BuiltinFactory> registry) {
        registry.put("canvas", args -> SpnCanvasOpenNodeGen.create(args[0], args[1]));
        registry.put("show",   args -> SpnCanvasShowNodeGen.create());
        registry.put("clear",  args -> SpnCanvasClearNodeGen.create(args[0], args[1], args[2]));
        registry.put("fill",   args -> SpnCanvasFillNodeGen.create(args[0], args[1], args[2]));
        registry.put("stroke", args -> SpnCanvasStrokeNodeGen.create(args[0], args[1], args[2]));
        registry.put("strokeWeight", args -> SpnCanvasStrokeWeightNodeGen.create(args[0]));
        registry.put("rect",   args -> SpnCanvasRectNodeGen.create(args[0], args[1], args[2], args[3]));
        registry.put("circle", args -> SpnCanvasCircleNodeGen.create(args[0], args[1], args[2]));
        registry.put("line",   args -> SpnCanvasLineNodeGen.create(args[0], args[1], args[2], args[3]));
        // animate(fps, drawFn) is handled specially — the second arg is a function reference
        // that needs CallTarget resolution. This will be wired when the parser supports it.
    }
}
