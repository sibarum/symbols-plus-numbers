package spn.canvas;

import spn.canvas.node.*;
import spn.language.SpnModule;
import spn.language.SpnModuleRegistry;
import spn.node.BuiltinFactory;

import java.util.Map;

/**
 * Registers all canvas drawing functions into a builtin registry.
 * Called by the host (e.g., EditorWindow) before parsing SPN code.
 */
public final class CanvasBuiltins {

    private CanvasBuiltins() {}

    /**
     * Legacy registration into a flat builtin map.
     * @param registry the parser's builtin registry
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
        registry.put("animate", args -> SpnCanvasAnimateNodeGen.create(args[0], args[1]));
    }

    /**
     * Registers the Canvas module into the module system.
     */
    public static void registerModule(SpnModuleRegistry registry) {
        SpnModule.Builder builder = SpnModule.builder("Canvas");
        builder.builtinFactory("canvas", args -> SpnCanvasOpenNodeGen.create(args[0], args[1]));
        builder.builtinFactory("show",   args -> SpnCanvasShowNodeGen.create());
        builder.builtinFactory("clear",  args -> SpnCanvasClearNodeGen.create(args[0], args[1], args[2]));
        builder.builtinFactory("fill",   args -> SpnCanvasFillNodeGen.create(args[0], args[1], args[2]));
        builder.builtinFactory("stroke", args -> SpnCanvasStrokeNodeGen.create(args[0], args[1], args[2]));
        builder.builtinFactory("strokeWeight", args -> SpnCanvasStrokeWeightNodeGen.create(args[0]));
        builder.builtinFactory("rect",   args -> SpnCanvasRectNodeGen.create(args[0], args[1], args[2], args[3]));
        builder.builtinFactory("circle", args -> SpnCanvasCircleNodeGen.create(args[0], args[1], args[2]));
        builder.builtinFactory("line",   args -> SpnCanvasLineNodeGen.create(args[0], args[1], args[2], args[3]));
        builder.builtinFactory("animate", args -> SpnCanvasAnimateNodeGen.create(args[0], args[1]));
        registry.register("Canvas", builder.build());
    }
}
