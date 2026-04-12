package spn.language;

import com.oracle.truffle.api.CallTarget;
import spn.node.BuiltinFactory;
import spn.type.SpnStructDescriptor;
import spn.type.SpnTypeDescriptor;
import spn.type.SpnVariantSet;

import java.util.*;

/**
 * A resolved SPN module — the set of exported names from a single namespace.
 *
 * Modules can be:
 *   - Native (stdlib, canvas) — registered at startup from Java code
 *   - SPN-defined — loaded from .spn files when imported
 *
 * A module carries all exportable symbols: functions (as CallTargets or
 * BuiltinFactories), types, structs, and data variants.
 *
 * <pre>
 *   SpnModule math = SpnModule.builder("Math")
 *       .function("abs", absCallTarget)
 *       .function("sqrt", sqrtCallTarget)
 *       .builtinFactory("map", args -> SpnArrayMapNodeGen.create(...))
 *       .build();
 * </pre>
 */
public final class SpnModule {

    private final String namespace;
    private final boolean impure;
    private final Map<String, CallTarget> functions;
    private final Map<String, BuiltinFactory> builtinFactories;
    private final Map<String, SpnTypeDescriptor> types;
    private final Map<String, SpnStructDescriptor> structs;
    private final Map<String, SpnVariantSet> variants;
    // Extensible metadata — carries parser-specific registries (methods, factories, operators, etc.)
    private final Map<String, Object> extras;

    private SpnModule(String namespace, boolean impure,
                      Map<String, CallTarget> functions,
                      Map<String, BuiltinFactory> builtinFactories,
                      Map<String, SpnTypeDescriptor> types,
                      Map<String, SpnStructDescriptor> structs,
                      Map<String, SpnVariantSet> variants,
                      Map<String, Object> extras) {
        this.namespace = namespace;
        this.impure = impure;
        this.functions = Map.copyOf(functions);
        this.builtinFactories = Map.copyOf(builtinFactories);
        this.types = Map.copyOf(types);
        this.structs = Map.copyOf(structs);
        this.variants = Map.copyOf(variants);
        this.extras = extras != null ? Map.copyOf(extras) : Map.of();
    }

    public String getNamespace() { return namespace; }
    public boolean isImpure() { return impure; }
    public Map<String, Object> getExtras() { return extras; }

    /** Get a typed extra registry by key. Returns null if not present. */
    @SuppressWarnings("unchecked")
    public <T> T getExtra(String key) { return (T) extras.get(key); }

    // ── Lookups ────────────────────────────────────────────────────────────

    public CallTarget getFunction(String name) { return functions.get(name); }
    public BuiltinFactory getBuiltinFactory(String name) { return builtinFactories.get(name); }
    public SpnTypeDescriptor getType(String name) { return types.get(name); }
    public SpnStructDescriptor getStruct(String name) { return structs.get(name); }
    public SpnVariantSet getVariant(String name) { return variants.get(name); }

    public Map<String, CallTarget> getFunctions() { return functions; }
    public Map<String, BuiltinFactory> getBuiltinFactories() { return builtinFactories; }
    public Map<String, SpnTypeDescriptor> getTypes() { return types; }
    public Map<String, SpnStructDescriptor> getStructs() { return structs; }
    public Map<String, SpnVariantSet> getVariants() { return variants; }

    /** Returns all exported names across all categories. */
    public Set<String> allExportedNames() {
        var names = new LinkedHashSet<String>();
        names.addAll(functions.keySet());
        names.addAll(builtinFactories.keySet());
        names.addAll(types.keySet());
        names.addAll(structs.keySet());
        names.addAll(variants.keySet());
        return names;
    }

    @Override
    public String toString() {
        return "SpnModule[" + namespace + ", exports=" + allExportedNames().size() + "]";
    }

    // ── Builder ────────────────────────────────────────────────────────────

    public static Builder builder(String namespace) {
        return new Builder(namespace);
    }

    public static final class Builder {
        private final String namespace;
        private boolean impure;
        private final Map<String, CallTarget> functions = new LinkedHashMap<>();
        private final Map<String, BuiltinFactory> builtinFactories = new LinkedHashMap<>();
        private final Map<String, SpnTypeDescriptor> types = new LinkedHashMap<>();
        private final Map<String, SpnStructDescriptor> structs = new LinkedHashMap<>();
        private final Map<String, SpnVariantSet> variants = new LinkedHashMap<>();

        private Builder(String namespace) {
            this.namespace = namespace;
        }

        /** Mark this module as containing impure (action) functions. */
        public Builder impure() {
            this.impure = true;
            return this;
        }

        public Builder function(String name, CallTarget target) {
            functions.put(name, target);
            return this;
        }

        public Builder builtinFactory(String name, BuiltinFactory factory) {
            builtinFactories.put(name, factory);
            return this;
        }

        public Builder type(String name, SpnTypeDescriptor descriptor) {
            types.put(name, descriptor);
            return this;
        }

        public Builder struct(String name, SpnStructDescriptor descriptor) {
            structs.put(name, descriptor);
            return this;
        }

        public Builder variant(String name, SpnVariantSet variantSet) {
            variants.put(name, variantSet);
            return this;
        }

        public Builder functions(Map<String, CallTarget> all) {
            functions.putAll(all);
            return this;
        }

        public Builder builtinFactories(Map<String, BuiltinFactory> all) {
            builtinFactories.putAll(all);
            return this;
        }

        public Builder types(Map<String, SpnTypeDescriptor> all) {
            types.putAll(all);
            return this;
        }

        public Builder structs(Map<String, SpnStructDescriptor> all) {
            structs.putAll(all);
            return this;
        }

        public Builder variants(Map<String, SpnVariantSet> all) {
            variants.putAll(all);
            return this;
        }

        private Map<String, Object> extras;

        /** Add an extensible registry (methods, factories, operators, etc.). */
        public Builder extra(String key, Object value) {
            if (extras == null) extras = new LinkedHashMap<>();
            extras.put(key, value);
            return this;
        }

        public SpnModule build() {
            return new SpnModule(namespace, impure, functions, builtinFactories,
                    types, structs, variants, extras);
        }
    }
}
