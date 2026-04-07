package spn.pkg;

import java.nio.file.Path;
import java.util.*;

/**
 * A resolved SPN artifact — a package with its identity, files, dependencies,
 * defaults, profiles, and nested sub-artifacts.
 *
 * The artifact's root directory contains an module.spn file. Every .spn file
 * under this directory (that isn't claimed by a nested artifact) belongs to
 * this artifact. Namespaces are derived from file position relative to the
 * artifact root.
 */
public final class SpnArtifact {

    private final ArtifactId id;
    private final Path root;
    private final List<ArtifactId> dependencies;
    private final List<ArtifactId> defaults;
    private final Map<String, String> profiles;
    private final List<SpnArtifact> nested;
    private final Map<String, Path> sourceFiles; // namespace -> file path

    SpnArtifact(ArtifactId id, Path root, List<ArtifactId> dependencies,
                List<ArtifactId> defaults, Map<String, String> profiles,
                List<SpnArtifact> nested, Map<String, Path> sourceFiles) {
        this.id = id;
        this.root = root;
        this.dependencies = List.copyOf(dependencies);
        this.defaults = List.copyOf(defaults);
        this.profiles = Map.copyOf(profiles);
        this.nested = List.copyOf(nested);
        this.sourceFiles = Map.copyOf(sourceFiles);
    }

    public ArtifactId getId() { return id; }
    public Path getRoot() { return root; }
    public List<ArtifactId> getDependencies() { return dependencies; }
    public List<ArtifactId> getDefaults() { return defaults; }
    public Map<String, String> getProfiles() { return profiles; }
    public List<SpnArtifact> getNested() { return nested; }
    public Map<String, Path> getSourceFiles() { return sourceFiles; }

    /**
     * Returns the namespace for a source file path.
     * Namespace = group.name + relative path with dots, no extension.
     */
    public String namespaceFor(Path file) {
        Path relative = root.relativize(file);
        String relStr = relative.toString()
                .replace('\\', '/')
                .replace('/', '.');
        if (relStr.endsWith(".spn")) {
            relStr = relStr.substring(0, relStr.length() - 4);
        }
        return id.group() + "." + id.name() + "." + relStr;
    }

    /**
     * Finds the source file for a given fully-qualified namespace.
     * Checks this artifact's files, then nested artifacts.
     */
    public Path resolveNamespace(String namespace) {
        Path local = sourceFiles.get(namespace);
        if (local != null) return local;

        for (SpnArtifact child : nested) {
            Path result = child.resolveNamespace(namespace);
            if (result != null) return result;
        }

        return null;
    }

    /**
     * Resolves a version for a dependency by checking defaults.
     * Walks up to ancestors via the parent parameter.
     */
    public String resolveVersion(String group, String name) {
        for (ArtifactId def : defaults) {
            if (def.group().equals(group) && def.name().equals(name)) {
                return def.version();
            }
        }
        return null;
    }

    /**
     * Looks up a profile value by key.
     */
    public String profile(String key) {
        return profiles.get(key);
    }

    @Override
    public String toString() {
        return "SpnArtifact[" + id.coordinate() + " at " + root + ", "
                + sourceFiles.size() + " files, "
                + nested.size() + " nested]";
    }
}
