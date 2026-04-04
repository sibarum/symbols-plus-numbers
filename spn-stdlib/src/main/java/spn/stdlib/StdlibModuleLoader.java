package spn.stdlib;

import com.oracle.truffle.api.CallTarget;
import spn.language.SpnModule;
import spn.language.SpnModuleRegistry;
import spn.node.BuiltinFactory;
import spn.node.SpnExpressionNode;
import spn.node.func.SpnFunctionRefNode;
import spn.stdlib.gen.SpnStdlibRegistry;
import spn.stdlib.gen.SpnStdlibRegistry.BuiltinEntry;

import java.util.*;
import java.util.function.BiFunction;

/**
 * Builds SpnModule instances from the auto-generated SpnStdlibRegistry
 * and registers them in a SpnModuleRegistry.
 *
 * Non-higher-order builtins (abs, sqrt, etc.) register as pre-compiled
 * CallTargets for maximum performance (DirectCallNode inlining).
 *
 * Higher-order builtins (map, filter, fold, etc.) register as BuiltinFactory
 * instances because they need the function argument's CallTarget at node
 * construction time.
 */
public final class StdlibModuleLoader {

    private StdlibModuleLoader() {}

    /**
     * Registers all stdlib modules ("Math", "Array", "String", etc.)
     * into the given registry.
     */
    public static void registerAll(SpnModuleRegistry registry) {
        // Collect all non-higher-order builtins by module
        Map<String, List<BuiltinEntry>> byModule = SpnStdlibRegistry.byModule();

        // Also register higher-order builtins as BuiltinFactory entries
        Map<String, Map<String, BuiltinFactory>> higherOrder = buildHigherOrderFactories();

        for (var entry : byModule.entrySet()) {
            String moduleName = entry.getKey();
            SpnModule.Builder builder = SpnModule.builder(moduleName);

            // Add pre-compiled CallTarget builtins
            for (BuiltinEntry builtin : entry.getValue()) {
                builder.function(builtin.name(), builtin.callTarget());
            }

            // Add higher-order BuiltinFactory builtins
            Map<String, BuiltinFactory> factories = higherOrder.get(moduleName);
            if (factories != null) {
                for (var factoryEntry : factories.entrySet()) {
                    builder.builtinFactory(factoryEntry.getKey(), factoryEntry.getValue());
                }
            }

            registry.register(moduleName, builder.build());
        }
    }

    /**
     * Builds BuiltinFactory entries for higher-order functions.
     *
     * These functions take a function argument (CallTarget) that must be
     * extracted from the parsed argument expressions at parse time. The
     * convention is: the function/predicate/comparator is the LAST argument.
     *
     * <pre>
     *   map(array, fn)       → args[0]=array, args[1]=fn
     *   filter(array, pred)  → args[0]=array, args[1]=pred
     *   fold(array, init, fn)→ args[0]=array, args[1]=init, args[2]=fn
     * </pre>
     */
    private static Map<String, Map<String, BuiltinFactory>> buildHigherOrderFactories() {
        var result = new LinkedHashMap<String, Map<String, BuiltinFactory>>();

        // Array module higher-order functions
        var array = new LinkedHashMap<String, BuiltinFactory>();
        array.put("map",    hoFactory(1, SpnStdlibRegistry::create_map));
        array.put("filter", hoFactory(1, SpnStdlibRegistry::create_filter));
        array.put("fold",   hoFactory(2, SpnStdlibRegistry::create_fold));
        array.put("sort",   hoFactory(1, SpnStdlibRegistry::create_sort));
        array.put("any",    hoFactory(1, SpnStdlibRegistry::create_any));
        array.put("all",    hoFactory(1, SpnStdlibRegistry::create_all));
        array.put("find",   hoFactory(1, SpnStdlibRegistry::create_find));
        result.put("Array", array);

        // Dict module
        var dict = new LinkedHashMap<String, BuiltinFactory>();
        dict.put("mapValues", hoFactory(1, SpnStdlibRegistry::create_mapValues));
        result.put("Dict", dict);

        // Option module
        var option = new LinkedHashMap<String, BuiltinFactory>();
        option.put("mapOption", hoFactory(1, SpnStdlibRegistry::create_mapOption));
        option.put("flatMap",   hoFactory(1, SpnStdlibRegistry::create_flatMap));
        result.put("Option", option);

        // Range module
        var range = new LinkedHashMap<String, BuiltinFactory>();
        range.put("iterate", hoFactory(0, SpnStdlibRegistry::create_iterate));
        result.put("Range", range);

        return result;
    }

    /**
     * Creates a BuiltinFactory for a higher-order function.
     *
     * @param valueArgCount number of non-function arguments (the function arg is last)
     * @param registryFactory the SpnStdlibRegistry factory method that takes a CallTarget
     */
    private static BuiltinFactory hoFactory(int valueArgCount,
                                             java.util.function.Function<CallTarget, BuiltinEntry> registryFactory) {
        return args -> {
            // The function argument is the last one
            SpnExpressionNode fnArg = args[valueArgCount];
            CallTarget callTarget;
            if (fnArg instanceof SpnFunctionRefNode ref) {
                // Extract the CallTarget at parse time
                callTarget = (CallTarget) ref.executeGeneric(null);
            } else {
                throw new IllegalArgumentException(
                        "Higher-order function expects a function reference as argument "
                                + (valueArgCount + 1));
            }

            // Create the builtin entry with the resolved CallTarget
            BuiltinEntry entry = registryFactory.apply(callTarget);

            // Return the body node from the root (unwrap the FunctionRootNode wrapper)
            // Actually, we need to invoke via the CallTarget, so return an InvokeNode
            // that passes the value args through
            SpnExpressionNode[] valueArgs = new SpnExpressionNode[valueArgCount];
            System.arraycopy(args, 0, valueArgs, 0, valueArgCount);
            return new spn.node.func.SpnInvokeNode(entry.callTarget(), valueArgs);
        };
    }
}
