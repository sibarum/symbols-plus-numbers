package spn.pkg;

import java.nio.file.Path;
import java.util.*;

/**
 * Resolves import statements against the artifact tree.
 *
 * Resolution order (per spec):
 *   1. Current artifact's own files (local namespace)
 *   2. Embedded artifacts (subdirectories with module.spn)
 *   3. Declared dependencies (require section) — matched by namespace prefix
 *   4. Ancestor artifact defaults for version resolution
 */
public final class ImportResolver {

    private final SpnArtifact root;

    public ImportResolver(SpnArtifact root) {
        this.root = root;
    }

    /**
     * Result of resolving an import. Either a local file path (for files within
     * the artifact tree) or an external dependency reference.
     */
    public sealed interface ResolvedImport {

        /** A local source file within the artifact tree. */
        record LocalFile(SpnArtifact artifact, String namespace, Path path) implements ResolvedImport {}

        /** An external dependency not present in the local tree. */
        record ExternalDependency(ArtifactId dependency, String namespace) implements ResolvedImport {}
    }

    /**
     * Resolves a fully-qualified namespace starting from the given context artifact.
     *
     * @param namespace the fully-qualified namespace to resolve (e.g. "spn.collections.sorted")
     * @param context   the artifact where the import statement appears
     * @return the resolved import, or empty if unresolvable
     */
    public Optional<ResolvedImport> resolve(String namespace, SpnArtifact context) {
        // 1. Check the context artifact's own files
        Path local = context.getSourceFiles().get(namespace);
        if (local != null) {
            return Optional.of(new ResolvedImport.LocalFile(context, namespace, local));
        }

        // 2. Check embedded (nested) artifacts recursively
        Optional<ResolvedImport> nested = resolveInNested(namespace, context);
        if (nested.isPresent()) return nested;

        // 3. Check declared dependencies by namespace prefix
        Optional<ResolvedImport> dep = resolveInDependencies(namespace, context);
        if (dep.isPresent()) return dep;

        // 4. Walk up to root and check its full tree (ancestor search)
        if (context != root) {
            return resolveFromRoot(namespace, context);
        }

        return Optional.empty();
    }

    /**
     * Convenience: resolves from the root artifact context.
     */
    public Optional<ResolvedImport> resolve(String namespace) {
        return resolve(namespace, root);
    }

    /**
     * Searches nested artifacts for a namespace match.
     */
    private Optional<ResolvedImport> resolveInNested(String namespace, SpnArtifact artifact) {
        for (SpnArtifact child : artifact.getNested()) {
            // Check child's own files
            Path file = child.getSourceFiles().get(namespace);
            if (file != null) {
                return Optional.of(new ResolvedImport.LocalFile(child, namespace, file));
            }
            // Recurse into child's nested
            Optional<ResolvedImport> deeper = resolveInNested(namespace, child);
            if (deeper.isPresent()) return deeper;
        }
        return Optional.empty();
    }

    /**
     * Checks if the namespace matches any declared dependency by prefix.
     * A dependency with group "spn" and name "collections" owns the prefix "spn.collections.".
     */
    private Optional<ResolvedImport> resolveInDependencies(String namespace, SpnArtifact artifact) {
        for (ArtifactId dep : artifact.getDependencies()) {
            String prefix = dep.group() + "." + dep.name() + ".";
            if (namespace.startsWith(prefix) || namespace.equals(dep.group() + "." + dep.name())) {
                ArtifactId resolved = dep;
                // If the dependency has no version, resolve from defaults
                if (resolved.version() == null) {
                    String version = artifact.resolveVersion(dep.group(), dep.name());
                    if (version != null) {
                        resolved = resolved.withVersion(version);
                    }
                }
                return Optional.of(new ResolvedImport.ExternalDependency(resolved, namespace));
            }
        }
        return Optional.empty();
    }

    /**
     * Falls back to searching the entire tree from the root.
     * This handles the case where an import references a sibling artifact
     * that the current context doesn't directly declare.
     */
    private Optional<ResolvedImport> resolveFromRoot(String namespace, SpnArtifact excludeContext) {
        // Search root's own files
        Path local = root.getSourceFiles().get(namespace);
        if (local != null) {
            return Optional.of(new ResolvedImport.LocalFile(root, namespace, local));
        }

        // Search all nested from root
        Optional<ResolvedImport> nested = resolveInNested(namespace, root);
        if (nested.isPresent()) return nested;

        // Check root's dependencies
        return resolveInDependencies(namespace, root);
    }
}
