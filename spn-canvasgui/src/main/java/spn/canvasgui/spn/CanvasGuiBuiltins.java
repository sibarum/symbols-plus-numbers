package spn.canvasgui.spn;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.FrameSlotKind;
import spn.canvasgui.node.*;
import spn.language.SpnModule;
import spn.language.SpnModuleRegistry;
import spn.node.BuiltinFactory;
import spn.node.SpnExpressionNode;
import spn.node.func.SpnFunctionRootNode;
import spn.node.local.SpnReadLocalVariableNodeGen;
import spn.type.FieldType;
import spn.type.SpnFunctionDescriptor;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Registers the {@code CanvasGui} SPN module:
 *
 * <ul>
 *   <li>nominal type {@code GuiCmd} (opaque — backed by Java {@link GuiCmd})</li>
 *   <li>factory functions {@code guiButton / guiText / guiHBox / guiVBox} returning {@code GuiCmd}</li>
 *   <li>method {@code GuiCmd.on(:event, handler)} — enables {@code cmd.on(...)} syntax</li>
 *   <li>host builtins {@code guiWindow / guiRender / guiRun}</li>
 * </ul>
 */
public final class CanvasGuiBuiltins {

    private CanvasGuiBuiltins() {}

    public static void registerModule(SpnModuleRegistry registry) {
        // Module is mostly-pure: constructors (guiButton/Text/HBox/VBox) and the
        // .on() method have no side effects. Only the lifecycle triad
        // (guiWindow, guiRender, guiRun) mutates GuiSpnState; flagged below
        // via the "impureFunctions" extras set.
        SpnModule.Builder builder = SpnModule.builder("CanvasGui");

        // GuiCmd as a FieldType: opaque-Java-class backed. Matches any
        // instance of the Java GuiCmd sealed interface at return-time; its
        // describe() "GuiCmd" keys the method registry so `.on` dispatches.
        FieldType guiCmdType = new FieldType.OfClass(GuiCmd.class, "GuiCmd");

        // Factory functions: each returns a GuiCmd.
        builder.function("guiButton", buildButtonCallTarget(guiCmdType));
        builder.function("guiText",   buildTextCallTarget(guiCmdType));
        builder.function("guiHBox",   buildHBoxCallTarget(guiCmdType));
        builder.function("guiVBox",   buildVBoxCallTarget(guiCmdType));

        // Return-type descriptors — the parser uses these to track that
        // `guiButton(...)` evaluates to a GuiCmd, which enables `.on(...)`
        // method dispatch at the call site.
        Map<String, SpnFunctionDescriptor> descriptors = new LinkedHashMap<>();
        descriptors.put("guiButton", SpnFunctionDescriptor.pure("guiButton")
                .param("label", FieldType.STRING).returns(guiCmdType).build());
        descriptors.put("guiText", SpnFunctionDescriptor.pure("guiText")
                .param("content", FieldType.STRING).returns(guiCmdType).build());
        descriptors.put("guiHBox", SpnFunctionDescriptor.pure("guiHBox")
                .param("children", FieldType.ofArray(FieldType.UNTYPED))
                .returns(guiCmdType).build());
        descriptors.put("guiVBox", SpnFunctionDescriptor.pure("guiVBox")
                .param("children", FieldType.ofArray(FieldType.UNTYPED))
                .returns(guiCmdType).build());
        builder.extra("descriptors", descriptors);

        // Method on GuiCmd — enables `cmd.on(:click, fn)` dispatch.
        CallTarget onCt = buildOnCallTarget(guiCmdType);
        SpnFunctionDescriptor onDesc = SpnFunctionDescriptor.pure("on")
                .param("this", guiCmdType)
                .param("event", FieldType.SYMBOL)
                .param("handler")
                .returns(guiCmdType)
                .build();
        Map<String, spn.language.MethodEntry> methods = new LinkedHashMap<>();
        methods.put("GuiCmd.on", new spn.language.MethodEntry(onCt, onDesc));
        builder.extra("methods", methods);

        // Host lifecycle builtins — per-call-site nodes (no method dispatch needed).
        // These are actions: they mutate the thread-local GuiSpnState.
        Map<String, BuiltinFactory> factories = new LinkedHashMap<>();
        factories.put("guiWindow", args -> SpnGuiWindowNodeGen.create(args[0], args[1], args[2]));
        factories.put("guiRender", args -> SpnGuiRenderNodeGen.create(args[0]));
        factories.put("guiRun",    args -> SpnGuiRunNodeGen.create(args[0], args[1]));
        factories.forEach(builder::builtinFactory);

        // Flag the lifecycle triad as impure so pure functions can't call them.
        Set<String> impure = new LinkedHashSet<>();
        impure.add("guiWindow");
        impure.add("guiRender");
        impure.add("guiRun");
        builder.extra("impureFunctions", impure);

        registry.register("CanvasGui", builder.build());
    }

    // ── CallTarget builders ────────────────────────────────────────────────

    private static CallTarget buildButtonCallTarget(FieldType guiCmdType) {
        var fdb = FrameDescriptor.newBuilder();
        int slotLabel = fdb.addSlot(FrameSlotKind.Object, "label", null);
        var desc = SpnFunctionDescriptor.pure("guiButton")
                .param("label", FieldType.STRING)
                .returns(guiCmdType).build();
        SpnExpressionNode body = SpnCmdButtonNodeGen.create(
                SpnReadLocalVariableNodeGen.create(slotLabel));
        return new SpnFunctionRootNode(null, fdb.build(), desc,
                new int[]{slotLabel}, body).getCallTarget();
    }

    private static CallTarget buildTextCallTarget(FieldType guiCmdType) {
        var fdb = FrameDescriptor.newBuilder();
        int slot = fdb.addSlot(FrameSlotKind.Object, "content", null);
        var desc = SpnFunctionDescriptor.pure("guiText")
                .param("content", FieldType.STRING)
                .returns(guiCmdType).build();
        SpnExpressionNode body = SpnCmdTextNodeGen.create(
                SpnReadLocalVariableNodeGen.create(slot));
        return new SpnFunctionRootNode(null, fdb.build(), desc,
                new int[]{slot}, body).getCallTarget();
    }

    private static CallTarget buildHBoxCallTarget(FieldType guiCmdType) {
        var fdb = FrameDescriptor.newBuilder();
        int slot = fdb.addSlot(FrameSlotKind.Object, "children", null);
        var desc = SpnFunctionDescriptor.pure("guiHBox")
                .param("children", FieldType.ofArray(FieldType.UNTYPED))
                .returns(guiCmdType).build();
        SpnExpressionNode body = SpnCmdHBoxNodeGen.create(
                SpnReadLocalVariableNodeGen.create(slot));
        return new SpnFunctionRootNode(null, fdb.build(), desc,
                new int[]{slot}, body).getCallTarget();
    }

    private static CallTarget buildVBoxCallTarget(FieldType guiCmdType) {
        var fdb = FrameDescriptor.newBuilder();
        int slot = fdb.addSlot(FrameSlotKind.Object, "children", null);
        var desc = SpnFunctionDescriptor.pure("guiVBox")
                .param("children", FieldType.ofArray(FieldType.UNTYPED))
                .returns(guiCmdType).build();
        SpnExpressionNode body = SpnCmdVBoxNodeGen.create(
                SpnReadLocalVariableNodeGen.create(slot));
        return new SpnFunctionRootNode(null, fdb.build(), desc,
                new int[]{slot}, body).getCallTarget();
    }

    private static CallTarget buildOnCallTarget(FieldType guiCmdType) {
        var fdb = FrameDescriptor.newBuilder();
        int slotCmd = fdb.addSlot(FrameSlotKind.Object, "this", null);
        int slotEv  = fdb.addSlot(FrameSlotKind.Object, "event", null);
        int slotFn  = fdb.addSlot(FrameSlotKind.Object, "handler", null);
        var desc = SpnFunctionDescriptor.pure("on")
                .param("this", guiCmdType)
                .param("event", FieldType.SYMBOL)
                .param("handler")
                .returns(guiCmdType).build();
        SpnExpressionNode body = SpnGuiOnNodeGen.create(
                SpnReadLocalVariableNodeGen.create(slotCmd),
                SpnReadLocalVariableNodeGen.create(slotEv),
                SpnReadLocalVariableNodeGen.create(slotFn));
        return new SpnFunctionRootNode(null, fdb.build(), desc,
                new int[]{slotCmd, slotEv, slotFn}, body).getCallTarget();
    }
}
