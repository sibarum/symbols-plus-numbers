package spn.canvas;

import spn.canvas.node.*;
import spn.language.SpnModule;
import spn.language.SpnModuleRegistry;
import spn.node.BuiltinFactory;
import spn.node.array.SpnArrayLengthNodeGen;
import spn.stdlib.string.SpnShowNodeGen;

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
        addDrawing(registry);
        addUtilities(registry);
    }

    /**
     * Registers the Canvas module into the module system.
     */
    public static void registerModule(SpnModuleRegistry registry) {
        SpnModule.Builder builder = SpnModule.builder("Canvas").impure();
        Map<String, BuiltinFactory> factories = new java.util.LinkedHashMap<>();
        addDrawing(factories);
        addUtilities(factories);
        factories.forEach(builder::builtinFactory);
        registry.register("Canvas", builder.build());
    }

    private static void addDrawing(Map<String, BuiltinFactory> r) {
        r.put("canvas",      args -> SpnCanvasOpenNodeGen.create(args[0], args[1]));
        r.put("show",        args -> SpnCanvasShowNodeGen.create());
        r.put("clear",       args -> SpnCanvasClearNodeGen.create(args[0], args[1], args[2]));
        r.put("fill",        args -> SpnCanvasFillNodeGen.create(args[0], args[1], args[2]));
        r.put("stroke",      args -> SpnCanvasStrokeNodeGen.create(args[0], args[1], args[2]));
        r.put("strokeWeight", args -> SpnCanvasStrokeWeightNodeGen.create(args[0]));
        r.put("rect",        args -> SpnCanvasRectNodeGen.create(args[0], args[1], args[2], args[3]));
        r.put("circle",      args -> SpnCanvasCircleNodeGen.create(args[0], args[1], args[2]));
        r.put("line",        args -> SpnCanvasLineNodeGen.create(args[0], args[1], args[2], args[3]));
        r.put("text",        args -> SpnCanvasTextNodeGen.create(args[0], args[1], args[2], args[3]));
        r.put("animate",     args -> SpnCanvasAnimateNodeGen.create(args[0], args[1]));
    }

    private static void addUtilities(Map<String, BuiltinFactory> r) {
        // Array (not yet in stdlib generator)
        r.put("length",    args -> SpnArrayLengthNodeGen.create(args[0]));
        // String conversion alias
        r.put("str",       args -> SpnShowNodeGen.create(args[0]));
    }
}
