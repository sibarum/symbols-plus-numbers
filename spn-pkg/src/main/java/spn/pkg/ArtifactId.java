package spn.pkg;

/**
 * The identity of an SPN artifact: group:name:version.
 *
 * Version may be null for embedded artifacts that inherit from
 * an ancestor's defaults.
 */
public record ArtifactId(String group, String name, String version) {

    /** Coordinate string, e.g. "spn:stdlib:1.0.0" */
    public String coordinate() {
        if (version == null) return group + ":" + name;
        return group + ":" + name + ":" + version;
    }

    /** Matches ignoring version (for default resolution). */
    public boolean matchesIgnoringVersion(ArtifactId other) {
        return group.equals(other.group) && name.equals(other.name);
    }

    /** Returns a copy with the given version applied. */
    public ArtifactId withVersion(String version) {
        return new ArtifactId(group, name, version);
    }

    @Override
    public String toString() {
        return coordinate();
    }
}
