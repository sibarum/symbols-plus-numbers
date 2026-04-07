package spn.pkg;

/**
 * The identity of an SPN module: a dotted namespace with optional version.
 *
 * <p>The namespace is a single combined field (e.g., "com.mysite.mymodule")
 * that serves as both the group identity and the namespace prefix for all
 * source files under the module.
 *
 * @param namespace the module namespace (e.g., "com.mysite.mymodule")
 * @param version   semantic version string, or null if unversioned
 */
public record ModuleId(String namespace, String version) {

    /** Coordinate string, e.g. "com.mysite.mymodule:1.0.0" */
    public String coordinate() {
        if (version == null) return namespace;
        return namespace + ":" + version;
    }

    /** Matches ignoring version. */
    public boolean matchesIgnoringVersion(ModuleId other) {
        return namespace.equals(other.namespace);
    }

    /** Returns a copy with the given version applied. */
    public ModuleId withVersion(String version) {
        return new ModuleId(namespace, version);
    }

    @Override
    public String toString() {
        return coordinate();
    }
}
