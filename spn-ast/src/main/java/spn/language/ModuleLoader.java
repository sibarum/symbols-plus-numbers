package spn.language;

import java.util.Optional;

/**
 * Loads an SPN module by namespace on demand.
 *
 * Module loaders are tried in order when a module is not already registered.
 * Implementations may load from the classpath, the filesystem, or other sources.
 */
@FunctionalInterface
public interface ModuleLoader {

    /**
     * Attempts to load a module for the given namespace.
     *
     * @param namespace the fully-qualified module namespace (e.g., "spn.canvas.plot")
     * @param registry  the module registry (for resolving transitive imports)
     * @return the loaded module, or empty if this loader can't handle the namespace
     */
    Optional<SpnModule> load(String namespace, SpnModuleRegistry registry);
}
