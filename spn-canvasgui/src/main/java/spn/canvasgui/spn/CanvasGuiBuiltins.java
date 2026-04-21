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
        builder.function("guiGrid",   buildGridCallTarget(guiCmdType));
        builder.function("guiSpacer", buildSpacerCallTarget(guiCmdType));
        builder.function("guiMask",   buildMaskCallTarget(guiCmdType));
        builder.function("guiSlider", buildSliderCallTarget(guiCmdType));
        builder.function("guiDial",   buildDialCallTarget(guiCmdType));
        builder.function("guiTabs",   buildTabsCallTarget(guiCmdType));
        builder.function("guiScrollable", buildScrollableCallTarget(guiCmdType));

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
        descriptors.put("guiGrid", SpnFunctionDescriptor.pure("guiGrid")
                .param("rows", FieldType.LONG)
                .param("cols", FieldType.LONG)
                .param("children", FieldType.ofArray(FieldType.UNTYPED))
                .returns(guiCmdType).build());
        descriptors.put("guiSpacer", SpnFunctionDescriptor.pure("guiSpacer")
                .returns(guiCmdType).build());
        descriptors.put("guiMask", SpnFunctionDescriptor.pure("guiMask")
                .param("child", guiCmdType)
                .param("w", FieldType.DOUBLE)
                .param("h", FieldType.DOUBLE)
                .returns(guiCmdType).build());
        descriptors.put("guiSlider", SpnFunctionDescriptor.pure("guiSlider")
                .param("min", FieldType.DOUBLE)
                .param("max", FieldType.DOUBLE)
                .param("value", FieldType.DOUBLE)
                .returns(guiCmdType).build());
        descriptors.put("guiDial", SpnFunctionDescriptor.pure("guiDial")
                .param("min", FieldType.DOUBLE)
                .param("max", FieldType.DOUBLE)
                .param("value", FieldType.DOUBLE)
                .returns(guiCmdType).build());
        descriptors.put("guiTabs", SpnFunctionDescriptor.pure("guiTabs")
                .param("activeIdx", FieldType.LONG)
                .param("labels", FieldType.ofArray(FieldType.UNTYPED))
                .param("pages", FieldType.ofArray(FieldType.UNTYPED))
                .returns(guiCmdType).build());
        descriptors.put("guiScrollable", SpnFunctionDescriptor.pure("guiScrollable")
                .param("child", guiCmdType)
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

        // Style methods — chainable on any GuiCmd; wrap the receiver in a Box
        // (or update an existing Box) and return a new GuiCmd.
        addStyleMethod(methods, "margin", buildMarginCallTarget(guiCmdType), guiCmdType);
        addStyleMethod(methods, "padding", buildPaddingCallTarget(guiCmdType), guiCmdType);
        addBorderMethod(methods, buildBorderCallTarget(guiCmdType), guiCmdType);
        addBgMethod(methods, buildBgCallTarget(guiCmdType), guiCmdType);
        addSelectedMethod(methods, buildSelectedCallTarget(guiCmdType), guiCmdType);
        addBoolMethod(methods, "editable", buildEditableCallTarget(guiCmdType), guiCmdType);
        addBoolMethod(methods, "selectable", buildSelectableCallTarget(guiCmdType), guiCmdType);
        addBoolMethod(methods, "multiline", buildMultilineCallTarget(guiCmdType), guiCmdType);
        addBoolMethod(methods, "wordWrap", buildWordWrapCallTarget(guiCmdType), guiCmdType);
        addBoolMethod(methods, "bold", buildBoldCallTarget(guiCmdType), guiCmdType);
        addBoolMethod(methods, "italic", buildItalicCallTarget(guiCmdType), guiCmdType);
        addFontMethod(methods, buildFontMethodCallTarget(guiCmdType), guiCmdType);

        builder.extra("methods", methods);

        // Host lifecycle builtins — per-call-site nodes (no method dispatch needed).
        // These are actions: they mutate the thread-local GuiSpnState.
        Map<String, BuiltinFactory> factories = new LinkedHashMap<>();
        factories.put("guiWindow", args -> SpnGuiWindowNodeGen.create(args[0], args[1], args[2]));
        factories.put("guiRender", args -> SpnGuiRenderNodeGen.create(args[0]));
        factories.put("guiRun",    args -> SpnGuiRunNodeGen.create(args[0], args[1]));
        factories.put("guiLoadFont", args -> SpnGuiLoadFontNodeGen.create(args[0], args[1]));
        factories.forEach(builder::builtinFactory);

        // Flag the lifecycle triad as impure so pure functions can't call them.
        Set<String> impure = new LinkedHashSet<>();
        impure.add("guiWindow");
        impure.add("guiRender");
        impure.add("guiRun");
        impure.add("guiLoadFont");
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

    private static CallTarget buildGridCallTarget(FieldType guiCmdType) {
        var fdb = FrameDescriptor.newBuilder();
        int sRows = fdb.addSlot(FrameSlotKind.Object, "rows", null);
        int sCols = fdb.addSlot(FrameSlotKind.Object, "cols", null);
        int sChildren = fdb.addSlot(FrameSlotKind.Object, "children", null);
        var desc = SpnFunctionDescriptor.pure("guiGrid")
                .param("rows", FieldType.LONG)
                .param("cols", FieldType.LONG)
                .param("children", FieldType.ofArray(FieldType.UNTYPED))
                .returns(guiCmdType).build();
        SpnExpressionNode body = SpnCmdGridNodeGen.create(
                SpnReadLocalVariableNodeGen.create(sRows),
                SpnReadLocalVariableNodeGen.create(sCols),
                SpnReadLocalVariableNodeGen.create(sChildren));
        return new SpnFunctionRootNode(null, fdb.build(), desc,
                new int[]{sRows, sCols, sChildren}, body).getCallTarget();
    }

    private static CallTarget buildSpacerCallTarget(FieldType guiCmdType) {
        var fdb = FrameDescriptor.newBuilder();
        var desc = SpnFunctionDescriptor.pure("guiSpacer")
                .returns(guiCmdType).build();
        SpnExpressionNode body = SpnCmdSpacerNodeGen.create();
        return new SpnFunctionRootNode(null, fdb.build(), desc,
                new int[0], body).getCallTarget();
    }

    // ── Style method CallTarget builders ──────────────────────────────────

    private static void addStyleMethod(Map<String, spn.language.MethodEntry> methods,
                                       String name, CallTarget ct, FieldType guiCmdType) {
        SpnFunctionDescriptor desc = SpnFunctionDescriptor.pure(name)
                .param("this", guiCmdType)
                .param("rem", FieldType.DOUBLE)
                .returns(guiCmdType).build();
        methods.put("GuiCmd." + name, new spn.language.MethodEntry(ct, desc));
    }

    private static void addBorderMethod(Map<String, spn.language.MethodEntry> methods,
                                        CallTarget ct, FieldType guiCmdType) {
        SpnFunctionDescriptor desc = SpnFunctionDescriptor.pure("border")
                .param("this", guiCmdType)
                .param("width", FieldType.DOUBLE)
                .param("r", FieldType.DOUBLE)
                .param("g", FieldType.DOUBLE)
                .param("b", FieldType.DOUBLE)
                .returns(guiCmdType).build();
        methods.put("GuiCmd.border", new spn.language.MethodEntry(ct, desc));
    }

    private static void addBgMethod(Map<String, spn.language.MethodEntry> methods,
                                    CallTarget ct, FieldType guiCmdType) {
        SpnFunctionDescriptor desc = SpnFunctionDescriptor.pure("bg")
                .param("this", guiCmdType)
                .param("r", FieldType.DOUBLE)
                .param("g", FieldType.DOUBLE)
                .param("b", FieldType.DOUBLE)
                .returns(guiCmdType).build();
        methods.put("GuiCmd.bg", new spn.language.MethodEntry(ct, desc));
    }

    private static void addSelectedMethod(Map<String, spn.language.MethodEntry> methods,
                                          CallTarget ct, FieldType guiCmdType) {
        SpnFunctionDescriptor desc = SpnFunctionDescriptor.pure("selected")
                .param("this", guiCmdType)
                .param("value", FieldType.BOOLEAN)
                .returns(guiCmdType).build();
        methods.put("GuiCmd.selected", new spn.language.MethodEntry(ct, desc));
    }

    private static CallTarget buildSelectedCallTarget(FieldType guiCmdType) {
        var fdb = FrameDescriptor.newBuilder();
        int sCmd = fdb.addSlot(FrameSlotKind.Object, "this", null);
        int sVal = fdb.addSlot(FrameSlotKind.Object, "value", null);
        var desc = SpnFunctionDescriptor.pure("selected")
                .param("this", guiCmdType)
                .param("value", FieldType.BOOLEAN)
                .returns(guiCmdType).build();
        SpnExpressionNode body = SpnGuiSelectedNodeGen.create(
                SpnReadLocalVariableNodeGen.create(sCmd),
                SpnReadLocalVariableNodeGen.create(sVal));
        return new SpnFunctionRootNode(null, fdb.build(), desc,
                new int[]{sCmd, sVal}, body).getCallTarget();
    }

    // Generic `bool-method` helper used by editable/selectable. Keeps the per-node
    // wiring tiny while still getting a real CallTarget per method.
    @FunctionalInterface
    private interface BoolMethodBodyFactory {
        SpnExpressionNode make(SpnExpressionNode cmd, SpnExpressionNode val);
    }

    private static void addBoolMethod(Map<String, spn.language.MethodEntry> methods,
                                      String name, CallTarget ct, FieldType guiCmdType) {
        SpnFunctionDescriptor desc = SpnFunctionDescriptor.pure(name)
                .param("this", guiCmdType)
                .param("value", FieldType.BOOLEAN)
                .returns(guiCmdType).build();
        methods.put("GuiCmd." + name, new spn.language.MethodEntry(ct, desc));
    }

    private static CallTarget buildBoolMethodCallTarget(String name, FieldType guiCmdType,
                                                        BoolMethodBodyFactory factory) {
        var fdb = FrameDescriptor.newBuilder();
        int sCmd = fdb.addSlot(FrameSlotKind.Object, "this", null);
        int sVal = fdb.addSlot(FrameSlotKind.Object, "value", null);
        var desc = SpnFunctionDescriptor.pure(name)
                .param("this", guiCmdType)
                .param("value", FieldType.BOOLEAN)
                .returns(guiCmdType).build();
        SpnExpressionNode body = factory.make(
                SpnReadLocalVariableNodeGen.create(sCmd),
                SpnReadLocalVariableNodeGen.create(sVal));
        return new SpnFunctionRootNode(null, fdb.build(), desc,
                new int[]{sCmd, sVal}, body).getCallTarget();
    }

    private static CallTarget buildEditableCallTarget(FieldType guiCmdType) {
        return buildBoolMethodCallTarget("editable", guiCmdType,
                (cmd, val) -> SpnGuiEditableNodeGen.create(cmd, val));
    }

    private static CallTarget buildSelectableCallTarget(FieldType guiCmdType) {
        return buildBoolMethodCallTarget("selectable", guiCmdType,
                (cmd, val) -> SpnGuiSelectableNodeGen.create(cmd, val));
    }

    private static CallTarget buildMultilineCallTarget(FieldType guiCmdType) {
        return buildBoolMethodCallTarget("multiline", guiCmdType,
                (cmd, val) -> SpnGuiMultilineNodeGen.create(cmd, val));
    }

    private static CallTarget buildWordWrapCallTarget(FieldType guiCmdType) {
        return buildBoolMethodCallTarget("wordWrap", guiCmdType,
                (cmd, val) -> SpnGuiWordWrapNodeGen.create(cmd, val));
    }

    private static CallTarget buildBoldCallTarget(FieldType guiCmdType) {
        return buildBoolMethodCallTarget("bold", guiCmdType,
                (cmd, val) -> SpnGuiBoldNodeGen.create(cmd, val));
    }

    private static CallTarget buildItalicCallTarget(FieldType guiCmdType) {
        return buildBoolMethodCallTarget("italic", guiCmdType,
                (cmd, val) -> SpnGuiItalicNodeGen.create(cmd, val));
    }

    private static void addFontMethod(Map<String, spn.language.MethodEntry> methods,
                                      CallTarget ct, FieldType guiCmdType) {
        SpnFunctionDescriptor desc = SpnFunctionDescriptor.pure("font")
                .param("this", guiCmdType)
                .param("symbol", FieldType.SYMBOL)
                .returns(guiCmdType).build();
        methods.put("GuiCmd.font", new spn.language.MethodEntry(ct, desc));
    }

    private static CallTarget buildFontMethodCallTarget(FieldType guiCmdType) {
        var fdb = FrameDescriptor.newBuilder();
        int sCmd = fdb.addSlot(FrameSlotKind.Object, "this", null);
        int sSym = fdb.addSlot(FrameSlotKind.Object, "symbol", null);
        var desc = SpnFunctionDescriptor.pure("font")
                .param("this", guiCmdType)
                .param("symbol", FieldType.SYMBOL)
                .returns(guiCmdType).build();
        SpnExpressionNode body = SpnGuiFontNodeGen.create(
                SpnReadLocalVariableNodeGen.create(sCmd),
                SpnReadLocalVariableNodeGen.create(sSym));
        return new SpnFunctionRootNode(null, fdb.build(), desc,
                new int[]{sCmd, sSym}, body).getCallTarget();
    }

    private static CallTarget buildMarginCallTarget(FieldType guiCmdType) {
        var fdb = FrameDescriptor.newBuilder();
        int sCmd = fdb.addSlot(FrameSlotKind.Object, "this", null);
        int sRem = fdb.addSlot(FrameSlotKind.Object, "rem", null);
        var desc = SpnFunctionDescriptor.pure("margin")
                .param("this", guiCmdType).param("rem", FieldType.DOUBLE)
                .returns(guiCmdType).build();
        SpnExpressionNode body = SpnGuiMarginNodeGen.create(
                SpnReadLocalVariableNodeGen.create(sCmd),
                SpnReadLocalVariableNodeGen.create(sRem));
        return new SpnFunctionRootNode(null, fdb.build(), desc,
                new int[]{sCmd, sRem}, body).getCallTarget();
    }

    private static CallTarget buildPaddingCallTarget(FieldType guiCmdType) {
        var fdb = FrameDescriptor.newBuilder();
        int sCmd = fdb.addSlot(FrameSlotKind.Object, "this", null);
        int sRem = fdb.addSlot(FrameSlotKind.Object, "rem", null);
        var desc = SpnFunctionDescriptor.pure("padding")
                .param("this", guiCmdType).param("rem", FieldType.DOUBLE)
                .returns(guiCmdType).build();
        SpnExpressionNode body = SpnGuiPaddingNodeGen.create(
                SpnReadLocalVariableNodeGen.create(sCmd),
                SpnReadLocalVariableNodeGen.create(sRem));
        return new SpnFunctionRootNode(null, fdb.build(), desc,
                new int[]{sCmd, sRem}, body).getCallTarget();
    }

    private static CallTarget buildBorderCallTarget(FieldType guiCmdType) {
        var fdb = FrameDescriptor.newBuilder();
        int sCmd = fdb.addSlot(FrameSlotKind.Object, "this", null);
        int sW   = fdb.addSlot(FrameSlotKind.Object, "width", null);
        int sR   = fdb.addSlot(FrameSlotKind.Object, "r", null);
        int sG   = fdb.addSlot(FrameSlotKind.Object, "g", null);
        int sB   = fdb.addSlot(FrameSlotKind.Object, "b", null);
        var desc = SpnFunctionDescriptor.pure("border")
                .param("this", guiCmdType)
                .param("width", FieldType.DOUBLE)
                .param("r", FieldType.DOUBLE)
                .param("g", FieldType.DOUBLE)
                .param("b", FieldType.DOUBLE)
                .returns(guiCmdType).build();
        SpnExpressionNode body = SpnGuiBorderNodeGen.create(
                SpnReadLocalVariableNodeGen.create(sCmd),
                SpnReadLocalVariableNodeGen.create(sW),
                SpnReadLocalVariableNodeGen.create(sR),
                SpnReadLocalVariableNodeGen.create(sG),
                SpnReadLocalVariableNodeGen.create(sB));
        return new SpnFunctionRootNode(null, fdb.build(), desc,
                new int[]{sCmd, sW, sR, sG, sB}, body).getCallTarget();
    }

    private static CallTarget buildBgCallTarget(FieldType guiCmdType) {
        var fdb = FrameDescriptor.newBuilder();
        int sCmd = fdb.addSlot(FrameSlotKind.Object, "this", null);
        int sR   = fdb.addSlot(FrameSlotKind.Object, "r", null);
        int sG   = fdb.addSlot(FrameSlotKind.Object, "g", null);
        int sB   = fdb.addSlot(FrameSlotKind.Object, "b", null);
        var desc = SpnFunctionDescriptor.pure("bg")
                .param("this", guiCmdType)
                .param("r", FieldType.DOUBLE)
                .param("g", FieldType.DOUBLE)
                .param("b", FieldType.DOUBLE)
                .returns(guiCmdType).build();
        SpnExpressionNode body = SpnGuiBgNodeGen.create(
                SpnReadLocalVariableNodeGen.create(sCmd),
                SpnReadLocalVariableNodeGen.create(sR),
                SpnReadLocalVariableNodeGen.create(sG),
                SpnReadLocalVariableNodeGen.create(sB));
        return new SpnFunctionRootNode(null, fdb.build(), desc,
                new int[]{sCmd, sR, sG, sB}, body).getCallTarget();
    }

    private static CallTarget buildSliderCallTarget(FieldType guiCmdType) {
        var fdb = FrameDescriptor.newBuilder();
        int sMin = fdb.addSlot(FrameSlotKind.Object, "min", null);
        int sMax = fdb.addSlot(FrameSlotKind.Object, "max", null);
        int sVal = fdb.addSlot(FrameSlotKind.Object, "value", null);
        var desc = SpnFunctionDescriptor.pure("guiSlider")
                .param("min", FieldType.DOUBLE)
                .param("max", FieldType.DOUBLE)
                .param("value", FieldType.DOUBLE)
                .returns(guiCmdType).build();
        SpnExpressionNode body = SpnCmdSliderNodeGen.create(
                SpnReadLocalVariableNodeGen.create(sMin),
                SpnReadLocalVariableNodeGen.create(sMax),
                SpnReadLocalVariableNodeGen.create(sVal));
        return new SpnFunctionRootNode(null, fdb.build(), desc,
                new int[]{sMin, sMax, sVal}, body).getCallTarget();
    }

    private static CallTarget buildDialCallTarget(FieldType guiCmdType) {
        var fdb = FrameDescriptor.newBuilder();
        int sMin = fdb.addSlot(FrameSlotKind.Object, "min", null);
        int sMax = fdb.addSlot(FrameSlotKind.Object, "max", null);
        int sVal = fdb.addSlot(FrameSlotKind.Object, "value", null);
        var desc = SpnFunctionDescriptor.pure("guiDial")
                .param("min", FieldType.DOUBLE)
                .param("max", FieldType.DOUBLE)
                .param("value", FieldType.DOUBLE)
                .returns(guiCmdType).build();
        SpnExpressionNode body = SpnCmdDialNodeGen.create(
                SpnReadLocalVariableNodeGen.create(sMin),
                SpnReadLocalVariableNodeGen.create(sMax),
                SpnReadLocalVariableNodeGen.create(sVal));
        return new SpnFunctionRootNode(null, fdb.build(), desc,
                new int[]{sMin, sMax, sVal}, body).getCallTarget();
    }

    private static CallTarget buildTabsCallTarget(FieldType guiCmdType) {
        var fdb = FrameDescriptor.newBuilder();
        int sIdx = fdb.addSlot(FrameSlotKind.Object, "activeIdx", null);
        int sLabels = fdb.addSlot(FrameSlotKind.Object, "labels", null);
        int sPages = fdb.addSlot(FrameSlotKind.Object, "pages", null);
        var desc = SpnFunctionDescriptor.pure("guiTabs")
                .param("activeIdx", FieldType.LONG)
                .param("labels", FieldType.ofArray(FieldType.UNTYPED))
                .param("pages", FieldType.ofArray(FieldType.UNTYPED))
                .returns(guiCmdType).build();
        SpnExpressionNode body = SpnCmdTabsNodeGen.create(
                SpnReadLocalVariableNodeGen.create(sIdx),
                SpnReadLocalVariableNodeGen.create(sLabels),
                SpnReadLocalVariableNodeGen.create(sPages));
        return new SpnFunctionRootNode(null, fdb.build(), desc,
                new int[]{sIdx, sLabels, sPages}, body).getCallTarget();
    }

    private static CallTarget buildScrollableCallTarget(FieldType guiCmdType) {
        var fdb = FrameDescriptor.newBuilder();
        int sChild = fdb.addSlot(FrameSlotKind.Object, "child", null);
        var desc = SpnFunctionDescriptor.pure("guiScrollable")
                .param("child", guiCmdType)
                .returns(guiCmdType).build();
        SpnExpressionNode body = SpnCmdScrollableNodeGen.create(
                SpnReadLocalVariableNodeGen.create(sChild));
        return new SpnFunctionRootNode(null, fdb.build(), desc,
                new int[]{sChild}, body).getCallTarget();
    }

    private static CallTarget buildMaskCallTarget(FieldType guiCmdType) {
        var fdb = FrameDescriptor.newBuilder();
        int sChild = fdb.addSlot(FrameSlotKind.Object, "child", null);
        int sW = fdb.addSlot(FrameSlotKind.Object, "w", null);
        int sH = fdb.addSlot(FrameSlotKind.Object, "h", null);
        var desc = SpnFunctionDescriptor.pure("guiMask")
                .param("child", guiCmdType)
                .param("w", FieldType.DOUBLE)
                .param("h", FieldType.DOUBLE)
                .returns(guiCmdType).build();
        SpnExpressionNode body = SpnCmdMaskNodeGen.create(
                SpnReadLocalVariableNodeGen.create(sChild),
                SpnReadLocalVariableNodeGen.create(sW),
                SpnReadLocalVariableNodeGen.create(sH));
        return new SpnFunctionRootNode(null, fdb.build(), desc,
                new int[]{sChild, sW, sH}, body).getCallTarget();
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
