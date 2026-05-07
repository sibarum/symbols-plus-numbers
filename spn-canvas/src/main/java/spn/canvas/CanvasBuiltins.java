package spn.canvas;

import spn.canvas.node.*;
import spn.language.SpnModule;
import spn.language.SpnModuleRegistry;
import spn.node.BuiltinFactory;
import spn.stdlib.string.SpnShowNodeGen;
import spn.type.FieldType;
import spn.type.SpnFunctionDescriptor;

import java.util.LinkedHashMap;
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
        Map<String, BuiltinFactory> factories = new LinkedHashMap<>();
        addDrawing(factories);
        addUtilities(factories);
        factories.forEach(builder::builtinFactory);
        builder.extra("descriptors", buildDescriptors());
        registry.register("Canvas", builder.build());
    }

    /** Function descriptors with named, typed parameters. The IDE reads
     *  these for autocomplete (param-name autofill) and HUD signature
     *  hints. Marked impure since every Canvas call mutates window state. */
    private static Map<String, SpnFunctionDescriptor> buildDescriptors() {
        Map<String, SpnFunctionDescriptor> d = new LinkedHashMap<>();
        d.put("canvas", SpnFunctionDescriptor.impure("canvas")
                .param("width", FieldType.LONG)
                .param("height", FieldType.LONG)
                .returns(FieldType.LONG).build());
        d.put("show", SpnFunctionDescriptor.impure("show")
                .returns(FieldType.LONG).build());
        d.put("clear", SpnFunctionDescriptor.impure("clear")
                .param("r", FieldType.DOUBLE)
                .param("g", FieldType.DOUBLE)
                .param("b", FieldType.DOUBLE)
                .returns(FieldType.LONG).build());
        d.put("fill", SpnFunctionDescriptor.impure("fill")
                .param("r", FieldType.DOUBLE)
                .param("g", FieldType.DOUBLE)
                .param("b", FieldType.DOUBLE)
                .returns(FieldType.LONG).build());
        d.put("stroke", SpnFunctionDescriptor.impure("stroke")
                .param("r", FieldType.DOUBLE)
                .param("g", FieldType.DOUBLE)
                .param("b", FieldType.DOUBLE)
                .returns(FieldType.LONG).build());
        d.put("strokeWeight", SpnFunctionDescriptor.impure("strokeWeight")
                .param("weight", FieldType.DOUBLE)
                .returns(FieldType.LONG).build());
        d.put("rect", SpnFunctionDescriptor.impure("rect")
                .param("x", FieldType.DOUBLE)
                .param("y", FieldType.DOUBLE)
                .param("w", FieldType.DOUBLE)
                .param("h", FieldType.DOUBLE)
                .returns(FieldType.LONG).build());
        d.put("circle", SpnFunctionDescriptor.impure("circle")
                .param("cx", FieldType.DOUBLE)
                .param("cy", FieldType.DOUBLE)
                .param("r", FieldType.DOUBLE)
                .returns(FieldType.LONG).build());
        d.put("line", SpnFunctionDescriptor.impure("line")
                .param("x1", FieldType.DOUBLE)
                .param("y1", FieldType.DOUBLE)
                .param("x2", FieldType.DOUBLE)
                .param("y2", FieldType.DOUBLE)
                .returns(FieldType.LONG).build());
        d.put("text", SpnFunctionDescriptor.impure("text")
                .param("x", FieldType.DOUBLE)
                .param("y", FieldType.DOUBLE)
                .param("content", FieldType.STRING)
                .param("scale", FieldType.DOUBLE)
                .returns(FieldType.LONG).build());
        // animate(fps, drawFn) — drawFn is a function value (CallTarget at
        // runtime); we model it as untyped at the descriptor level.
        d.put("animate", SpnFunctionDescriptor.impure("animate")
                .param("fps", FieldType.DOUBLE)
                .param("drawFn")
                .returns(FieldType.LONG).build());
        // image(width, height) -> Image (untyped at descriptor level; runtime
        // type is SpnImage and per-op nodes enforce it).
        d.put("image", SpnFunctionDescriptor.impure("image")
                .param("width", FieldType.LONG)
                .param("height", FieldType.LONG)
                .returns(FieldType.UNTYPED).build());
        d.put("setPixel", SpnFunctionDescriptor.impure("setPixel")
                .param("image")
                .param("x", FieldType.LONG)
                .param("y", FieldType.LONG)
                .param("r", FieldType.DOUBLE)
                .param("g", FieldType.DOUBLE)
                .param("b", FieldType.DOUBLE)
                .returns(FieldType.LONG).build());
        // mapPixels(image, fn) — fn is (x, y) -> (r, g, b). Accepts both
        // pure lambdas and do() closures (SpnBoundClosure).
        d.put("mapPixels", SpnFunctionDescriptor.impure("mapPixels")
                .param("image")
                .param("fn")
                .returns(FieldType.LONG).build());
        d.put("drawImage", SpnFunctionDescriptor.impure("drawImage")
                .param("image")
                .param("x", FieldType.DOUBLE)
                .param("y", FieldType.DOUBLE)
                .returns(FieldType.LONG).build());
        d.put("savePng", SpnFunctionDescriptor.impure("savePng")
                .param("image")
                .param("path", FieldType.STRING)
                .returns(FieldType.LONG).build());
        d.put("str", SpnFunctionDescriptor.pure("str")
                .param("value")
                .returns(FieldType.STRING).build());
        return d;
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
        r.put("image",       args -> SpnCanvasImageCreateNodeGen.create(args[0], args[1]));
        r.put("setPixel",    args -> SpnCanvasSetPixelNodeGen.create(args[0], args[1], args[2], args[3], args[4], args[5]));
        r.put("mapPixels",   args -> SpnCanvasMapPixelsNodeGen.create(args[0], args[1]));
        r.put("drawImage",   args -> SpnCanvasDrawImageNodeGen.create(args[0], args[1], args[2]));
        r.put("savePng",     args -> SpnCanvasSavePngNodeGen.create(args[0], args[1]));
    }

    private static void addUtilities(Map<String, BuiltinFactory> r) {
        // String conversion alias
        r.put("str",       args -> SpnShowNodeGen.create(args[0]));
    }
}
