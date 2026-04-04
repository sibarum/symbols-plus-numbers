package spn.stdlib.gen;

import com.oracle.truffle.api.CallTarget;
import spn.node.builtin.SpnBuiltin;
import spn.node.builtin.SpnParamHint;

import java.io.IOException;
import java.io.PrintWriter;
import java.lang.reflect.Constructor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Scans compiled classes for @SpnBuiltin annotations and generates SpnStdlibRegistry.java.
 *
 * This runs as a post-compile step (process-classes phase) via exec-maven-plugin.
 * It reads the compiled .class files from the classpath, introspects @NodeChild
 * declarations and constructor signatures, and emits a registry source file that
 * wires each builtin into a callable SpnFunctionDescriptor + CallTarget.
 *
 * The generated registry is then compiled in a second pass.
 *
 * Usage:
 *   java spn.stdlib.gen.StdlibRegistryGenerator <classes-dir> <output-dir>
 */
public final class StdlibRegistryGenerator {

    /** Discovered info about one builtin function. */
    record BuiltinInfo(
            String name,
            String module,
            boolean pure,
            String returns,
            String nodeClassName,       // e.g., "spn.stdlib.math.SpnAbsNode"
            String genClassName,        // e.g., "spn.stdlib.math.SpnAbsNodeGen"
            List<String> nodeChildren,  // from @SpnBuiltin params, in order
            List<ParamHintInfo> hints,
            boolean hasCallTargetCtor,  // constructor takes CallTarget
            String ctorParamName,       // name of the CallTarget param in @SpnParamHint
            boolean isAbstract          // true = DSL node (use Gen class), false = concrete (use directly)
    ) {}

    record ParamHintInfo(String name, String type, boolean function) {}

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: StdlibRegistryGenerator <classes-dir> <output-dir>");
            System.exit(1);
        }

        Path classesDir = Path.of(args[0]);
        Path outputDir = Path.of(args[1]);

        List<BuiltinInfo> builtins = scanForBuiltins(classesDir);

        if (builtins.isEmpty()) {
            System.out.println("[StdlibRegistryGenerator] No @SpnBuiltin classes found.");
            return;
        }

        builtins.sort(Comparator.comparing(BuiltinInfo::module).thenComparing(BuiltinInfo::name));

        Path outputFile = outputDir.resolve("spn/stdlib/gen/SpnStdlibRegistry.java");
        Files.createDirectories(outputFile.getParent());

        try (var out = new PrintWriter(Files.newBufferedWriter(outputFile))) {
            generate(builtins, out);
        }

        System.out.println("[StdlibRegistryGenerator] Generated SpnStdlibRegistry with "
                + builtins.size() + " builtins.");
    }

    /** Scans the classes directory for .class files annotated with @SpnBuiltin. */
    private static List<BuiltinInfo> scanForBuiltins(Path classesDir) throws IOException {
        var builtins = new ArrayList<BuiltinInfo>();

        try (var walk = Files.walk(classesDir)) {
            var classFiles = walk
                    .filter(p -> p.toString().endsWith(".class"))
                    .filter(p -> !p.getFileName().toString().contains("Gen"))
                    .filter(p -> !p.getFileName().toString().contains("$"))
                    .toList();

            for (Path classFile : classFiles) {
                String relativePath = classesDir.relativize(classFile).toString();
                String className = relativePath
                        .replace('/', '.').replace('\\', '.')
                        .replaceAll("\\.class$", "");

                try {
                    Class<?> clazz = Class.forName(className);
                    SpnBuiltin annotation = clazz.getAnnotation(SpnBuiltin.class);
                    if (annotation == null) continue;

                    // Read params from @SpnBuiltin (since @NodeChild has CLASS retention)
                    List<String> nodeChildren = new ArrayList<>(List.of(annotation.params()));

                    // Read @SpnParamHint annotations
                    List<ParamHintInfo> hints = new ArrayList<>();
                    for (var hint : clazz.getAnnotationsByType(SpnParamHint.class)) {
                        hints.add(new ParamHintInfo(hint.name(), hint.type(), hint.function()));
                    }

                    // Check if constructor takes a CallTarget parameter
                    boolean hasCallTargetCtor = false;
                    String ctorParamName = null;
                    for (Constructor<?> ctor : clazz.getDeclaredConstructors()) {
                        for (Class<?> paramType : ctor.getParameterTypes()) {
                            if (paramType == CallTarget.class) {
                                hasCallTargetCtor = true;
                                break;
                            }
                        }
                        if (hasCallTargetCtor) break;
                    }

                    // Find the function param hint name
                    if (hasCallTargetCtor) {
                        for (var hint : hints) {
                            if (hint.function()) {
                                ctorParamName = hint.name();
                                break;
                            }
                        }
                        if (ctorParamName == null) ctorParamName = "function";
                    }

                    // Abstract nodes get a Truffle-generated *Gen factory class;
                    // concrete (final) nodes are used directly
                    boolean isAbstract = java.lang.reflect.Modifier.isAbstract(clazz.getModifiers());
                    String genClassName = isAbstract ? className + "Gen" : className;

                    builtins.add(new BuiltinInfo(
                            annotation.name(), annotation.module(), annotation.pure(),
                            annotation.returns(), className, genClassName,
                            nodeChildren, hints, hasCallTargetCtor, ctorParamName,
                            isAbstract));

                } catch (ClassNotFoundException | NoClassDefFoundError e) {
                    // Skip classes that can't be loaded
                }
            }
        }
        return builtins;
    }

    /** Generates the SpnStdlibRegistry.java source. */
    private static void generate(List<BuiltinInfo> builtins, PrintWriter out) {
        out.println("package spn.stdlib.gen;");
        out.println();
        out.println("import com.oracle.truffle.api.CallTarget;");
        out.println("import com.oracle.truffle.api.frame.FrameDescriptor;");
        out.println("import com.oracle.truffle.api.frame.FrameSlotKind;");
        out.println("import spn.node.SpnExpressionNode;");
        out.println("import spn.node.func.SpnFunctionRootNode;");
        out.println("import spn.node.local.SpnReadLocalVariableNodeGen;");
        out.println("import spn.type.FieldType;");
        out.println("import spn.type.SpnFunctionDescriptor;");
        out.println();
        out.println("import java.util.*;");
        out.println();

        // Collect imports: Gen classes for abstract nodes, direct classes for concrete nodes
        Set<String> imports = new TreeSet<>();
        for (BuiltinInfo b : builtins) {
            if (b.isAbstract()) {
                imports.add(b.genClassName());  // e.g., SpnAbsNodeGen
            } else {
                imports.add(b.nodeClassName());  // e.g., SpnRangeStepNode (no Gen)
            }
        }
        for (String imp : imports) {
            out.println("import " + imp + ";");
        }
        out.println();

        out.println("/**");
        out.println(" * Auto-generated registry of SPN standard library builtins.");
        out.println(" *");
        out.println(" * Generated by StdlibRegistryGenerator from @SpnBuiltin annotations.");
        out.println(" * DO NOT EDIT — changes will be overwritten.");
        out.println(" */");
        out.println("public final class SpnStdlibRegistry {");
        out.println();
        out.println("    public record BuiltinEntry(");
        out.println("        String name,");
        out.println("        String module,");
        out.println("        SpnFunctionDescriptor descriptor,");
        out.println("        CallTarget callTarget");
        out.println("    ) {}");
        out.println();

        // Group by module
        Map<String, List<BuiltinInfo>> byModule = builtins.stream()
                .collect(Collectors.groupingBy(BuiltinInfo::module, LinkedHashMap::new, Collectors.toList()));

        // Generate a factory method for each builtin
        for (BuiltinInfo b : builtins) {
            generateFactoryMethod(b, out);
        }

        // Generate allBuiltins() method
        out.println("    /** Returns all builtin entries. */");
        out.println("    public static List<BuiltinEntry> allBuiltins() {");
        out.println("        var list = new ArrayList<BuiltinEntry>();");
        for (BuiltinInfo b : builtins) {
            if (!b.hasCallTargetCtor()) {
                out.println("        list.add(create_" + b.name() + "());");
            }
        }
        out.println("        return Collections.unmodifiableList(list);");
        out.println("    }");
        out.println();

        // Generate byName() lookup
        out.println("    /** Returns all non-higher-order builtins indexed by name. */");
        out.println("    public static Map<String, BuiltinEntry> byName() {");
        out.println("        var map = new LinkedHashMap<String, BuiltinEntry>();");
        out.println("        for (var entry : allBuiltins()) {");
        out.println("            map.put(entry.name(), entry);");
        out.println("        }");
        out.println("        return Collections.unmodifiableMap(map);");
        out.println("    }");
        out.println();

        // Generate byModule() lookup
        out.println("    /** Returns all non-higher-order builtins grouped by module. */");
        out.println("    public static Map<String, List<BuiltinEntry>> byModule() {");
        out.println("        var map = new LinkedHashMap<String, List<BuiltinEntry>>();");
        out.println("        for (var entry : allBuiltins()) {");
        out.println("            map.computeIfAbsent(entry.module(), k -> new ArrayList<>()).add(entry);");
        out.println("        }");
        out.println("        return Collections.unmodifiableMap(map);");
        out.println("    }");
        out.println();

        out.println("    private SpnStdlibRegistry() {}");
        out.println("}");
    }

    /** Generates a factory method for one builtin. */
    private static void generateFactoryMethod(BuiltinInfo b, PrintWriter out) {
        String methodName = "create_" + b.name();
        boolean isHigherOrder = b.hasCallTargetCtor();

        if (isHigherOrder) {
            out.println("    /** Creates a CallTarget for '" + b.name()
                    + "' (higher-order, requires a function argument). */");
            out.println("    public static BuiltinEntry " + methodName + "(CallTarget "
                    + b.ctorParamName() + ") {");
        } else {
            out.println("    /** Creates a CallTarget for '" + b.name() + "'. */");
            out.println("    public static BuiltinEntry " + methodName + "() {");
        }

        List<String> children = b.nodeChildren();
        String genSimpleName = b.genClassName().substring(b.genClassName().lastIndexOf('.') + 1);

        if (children.isEmpty()) {
            // Producer-style node (like range producers) — no @NodeChild, reads args from frame directly
            out.println("        var fdBuilder = FrameDescriptor.newBuilder();");
            out.println("        var desc = SpnFunctionDescriptor."
                    + (b.pure() ? "pure" : "impure") + "(\"" + b.name() + "\")");
            out.println("            .build();");
            out.println("        var body = new " + b.nodeClassName() + "("
                    + (isHigherOrder ? b.ctorParamName() : "") + ");");
            out.println("        var root = new SpnFunctionRootNode(null, fdBuilder.build(), desc,");
            out.println("            new int[]{}, body);");
        } else {
            // Standard DSL node with @NodeChild parameters
            out.println("        var fdBuilder = FrameDescriptor.newBuilder();");

            // Create frame slots for each @NodeChild
            for (int i = 0; i < children.size(); i++) {
                String child = children.get(i);
                out.println("        int slot_" + child + " = fdBuilder.addSlot(FrameSlotKind.Object, \""
                        + child + "\", null);");
            }

            // Build the SpnFunctionDescriptor
            out.println("        var desc = SpnFunctionDescriptor."
                    + (b.pure() ? "pure" : "impure") + "(\"" + b.name() + "\")");
            for (String child : children) {
                String fieldType = inferFieldType(b, child);
                if (fieldType != null) {
                    out.println("            .param(\"" + child + "\", " + fieldType + ")");
                } else {
                    out.println("            .param(\"" + child + "\")");
                }
            }
            // Add the function param to the descriptor if higher-order
            if (isHigherOrder) {
                out.println("            .param(\"" + b.ctorParamName() + "\")");
            }
            String returnFieldType = resolveReturnFieldType(b.returns());
            if (returnFieldType != null) {
                out.println("            .returns(" + returnFieldType + ")");
            }
            out.println("            .build();");

            // Build ReadLocalVariable nodes for each child
            out.println("        var body = " + genSimpleName + ".create(");
            if (isHigherOrder) {
                out.print("            " + b.ctorParamName());
                if (!children.isEmpty()) out.println(",");
                else out.println(");");
            }
            for (int i = 0; i < children.size(); i++) {
                String child = children.get(i);
                out.print("            SpnReadLocalVariableNodeGen.create(slot_" + child + ")");
                out.println(i < children.size() - 1 ? "," : ");");
            }

            // Build the slot array
            out.print("        var slots = new int[]{");
            out.print(children.stream().map(c -> "slot_" + c).collect(Collectors.joining(", ")));
            out.println("};");

            out.println("        var root = new SpnFunctionRootNode(null, fdBuilder.build(), desc,");
            out.println("            slots, body);");
        }

        out.println("        return new BuiltinEntry(\"" + b.name() + "\", \""
                + b.module() + "\", desc, root.getCallTarget());");
        out.println("    }");
        out.println();
    }

    /** Infers a FieldType expression for a @NodeChild based on @SpnParamHint or naming conventions. */
    private static String inferFieldType(BuiltinInfo b, String childName) {
        // Check @SpnParamHint overrides first
        for (var hint : b.hints()) {
            if (hint.name().equals(childName) && !hint.type().isEmpty()) {
                return resolveReturnFieldType(hint.type());
            }
        }
        return null; // untyped
    }

    /** Maps an SPN type name string to a FieldType expression. */
    private static String resolveReturnFieldType(String typeName) {
        if (typeName == null || typeName.isEmpty()) return null;
        return switch (typeName) {
            case "Long" -> "FieldType.LONG";
            case "Double" -> "FieldType.DOUBLE";
            case "Boolean" -> "FieldType.BOOLEAN";
            case "String" -> "FieldType.STRING";
            case "Symbol" -> "FieldType.SYMBOL";
            case "Array" -> "FieldType.ofArray(FieldType.UNTYPED)";
            case "Set" -> "FieldType.ofSet(FieldType.UNTYPED)";
            case "Dict" -> "FieldType.ofDictionary(FieldType.UNTYPED)";
            case "Option" -> "FieldType.UNTYPED"; // Option is a struct, needs special handling
            default -> null;
        };
    }
}
