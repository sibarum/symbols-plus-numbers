package spn.pkg;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

/**
 * Resolves a directory tree into an SpnArtifact by:
 *
 *   1. Finding artifact.spn in the given root
 *   2. Parsing it for identity, deps, defaults, profiles
 *   3. Scanning for .spn source files (computing namespaces)
 *   4. Recursing into subdirectories that have their own artifact.spn
 *   5. Applying version defaults from ancestor artifacts
 */
public final class ArtifactResolver {

    private static final String ARTIFACT_FILE = "artifact.spn";

    /**
     * Resolves the artifact rooted at the given directory.
     * This is the entry point for the root project.
     *
     * @param root directory containing artifact.spn
     * @return the fully resolved artifact tree
     */
    public SpnArtifact resolve(Path root) throws IOException {
        return resolve(root, List.of());
    }

    /**
     * Resolves with inherited defaults from ancestor artifacts.
     */
    private SpnArtifact resolve(Path root, List<ArtifactId> inheritedDefaults) throws IOException {
        Path artifactFile = root.resolve(ARTIFACT_FILE);
        if (!Files.exists(artifactFile)) {
            throw new IOException("No " + ARTIFACT_FILE + " found in " + root);
        }

        String source = Files.readString(artifactFile);
        ArtifactParser.ParseResult parsed = new ArtifactParser(source).parse();

        // Apply version inheritance
        ArtifactId id = parsed.id();
        if (id.version() == null) {
            String resolved = resolveVersionFromDefaults(id.group(), id.name(), inheritedDefaults);
            if (resolved != null) {
                id = id.withVersion(resolved);
            }
        }

        // Merge defaults: this artifact's defaults + inherited (this wins)
        List<ArtifactId> mergedDefaults = mergeDefaults(parsed.defaults(), inheritedDefaults);

        // Discover nested artifacts and source files
        List<SpnArtifact> nested = new ArrayList<>();
        Map<String, Path> sourceFiles = new LinkedHashMap<>();
        Set<Path> nestedRoots = new HashSet<>();

        // First pass: find all nested artifact roots
        findNestedArtifactRoots(root, nestedRoots);

        // Resolve nested artifacts
        for (Path nestedRoot : nestedRoots) {
            nested.add(resolve(nestedRoot, mergedDefaults));
        }

        // Second pass: collect source files not claimed by nested artifacts
        collectSourceFiles(root, id, sourceFiles, nestedRoots);

        return new SpnArtifact(id, root, parsed.dependencies(),
                parsed.defaults(), parsed.profiles(), nested, sourceFiles);
    }

    /**
     * Finds immediate subdirectories that contain artifact.spn.
     * Does NOT recurse into those — they handle their own children.
     */
    private void findNestedArtifactRoots(Path root, Set<Path> result) throws IOException {
        try (var stream = Files.walk(root)) {
            stream.filter(p -> p.getFileName().toString().equals(ARTIFACT_FILE))
                  .filter(p -> !p.getParent().equals(root)) // skip self
                  .forEach(p -> {
                      Path nestedRoot = p.getParent();
                      // Only add if no ancestor (other than root) is already in the set
                      boolean isNested = result.stream()
                              .noneMatch(existing -> nestedRoot.startsWith(existing));
                      if (isNested) {
                          // Remove any existing entries that are children of this one
                          result.removeIf(existing -> existing.startsWith(nestedRoot));
                          result.add(nestedRoot);
                      }
                  });
        }
    }

    /**
     * Collects .spn files under root that aren't claimed by nested artifacts.
     */
    private void collectSourceFiles(Path root, ArtifactId id,
                                     Map<String, Path> sourceFiles,
                                     Set<Path> nestedRoots) throws IOException {
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (!dir.equals(root) && nestedRoots.contains(dir)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                String name = file.getFileName().toString();
                if (name.endsWith(".spn") && !name.equals(ARTIFACT_FILE)) {
                    String namespace = computeNamespace(root, file, id);
                    sourceFiles.put(namespace, file);
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /**
     * Computes the fully qualified namespace for a source file.
     * namespace = group.name.relative.path (dots, no extension)
     */
    static String computeNamespace(Path artifactRoot, Path file, ArtifactId id) {
        Path relative = artifactRoot.relativize(file);
        String relStr = relative.toString()
                .replace('\\', '/')
                .replace('/', '.');
        if (relStr.endsWith(".spn")) {
            relStr = relStr.substring(0, relStr.length() - 4);
        }
        return id.group() + "." + id.name() + "." + relStr;
    }

    /**
     * Looks up a version from a defaults list.
     */
    private String resolveVersionFromDefaults(String group, String name,
                                               List<ArtifactId> defaults) {
        for (ArtifactId def : defaults) {
            if (def.group().equals(group) && def.name().equals(name)) {
                return def.version();
            }
        }
        return null;
    }

    /**
     * Merges two defaults lists. The local list wins on conflicts.
     */
    private List<ArtifactId> mergeDefaults(List<ArtifactId> local,
                                            List<ArtifactId> inherited) {
        Map<String, ArtifactId> merged = new LinkedHashMap<>();
        for (ArtifactId id : inherited) {
            merged.put(id.group() + ":" + id.name(), id);
        }
        for (ArtifactId id : local) {
            merged.put(id.group() + ":" + id.name(), id); // local wins
        }
        return List.copyOf(merged.values());
    }
}
