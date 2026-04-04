package spn.language;

import java.util.*;

/**
 * Registry of all loaded modules, keyed by namespace.
 *
 * Native modules (stdlib, canvas) are pre-registered at startup.
 * SPN modules are loaded on demand when imported and cached here.
 *
 * The registry also tracks modules currently being loaded to detect
 * circular imports.
 */
public final class SpnModuleRegistry {

    private final Map<String, SpnModule> modules = new LinkedHashMap<>();
    private final List<ModuleLoader> loaders = new ArrayList<>();
    private final Set<String> loading = new LinkedHashSet<>();

    /** Register a module by its namespace. */
    public void register(String namespace, SpnModule module) {
        modules.put(namespace, module);
    }

    /** Add a module loader to the chain (tried in order on cache miss). */
    public void addLoader(ModuleLoader loader) {
        loaders.add(loader);
    }

    /** Look up a module by exact namespace. */
    public Optional<SpnModule> lookup(String namespace) {
        return Optional.ofNullable(modules.get(namespace));
    }

    /**
     * Look up a native module by its short name (e.g., "Math", "Canvas").
     * Native modules are registered with their short name as namespace.
     */
    public Optional<SpnModule> lookupNative(String shortName) {
        return Optional.ofNullable(modules.get(shortName));
    }

    /**
     * Resolves a module: checks registered modules first, then tries loaders.
     * Loaded modules are cached in the registry for subsequent lookups.
     */
    public Optional<SpnModule> resolve(String namespace) {
        // Check already-loaded modules
        SpnModule cached = modules.get(namespace);
        if (cached != null) return Optional.of(cached);

        // Try loaders
        for (ModuleLoader loader : loaders) {
            Optional<SpnModule> loaded = loader.load(namespace, this);
            if (loaded.isPresent()) {
                modules.put(namespace, loaded.get());
                return loaded;
            }
        }
        return Optional.empty();
    }

    /** Returns all registered modules. */
    public Map<String, SpnModule> allModules() {
        return Collections.unmodifiableMap(modules);
    }

    // ── Cycle detection for module loading ──────────────────────────────────

    /**
     * Marks a namespace as currently being loaded. Returns false if it's
     * already in the loading set (circular dependency).
     */
    public boolean beginLoading(String namespace) {
        return loading.add(namespace);
    }

    /** Marks a namespace as finished loading. */
    public void finishLoading(String namespace) {
        loading.remove(namespace);
    }

    /** Returns the current loading chain (for error messages). */
    public Set<String> getLoadingChain() {
        return Collections.unmodifiableSet(loading);
    }
}
