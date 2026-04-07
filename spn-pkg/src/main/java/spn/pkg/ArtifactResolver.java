package spn.pkg;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

/**
 * Resolves a directory tree into an SpnArtifact by:
 *
 *   1. Finding module.spn in the given root
 *   2. Parsing it for identity, deps, defaults, profiles
 *   3. Scanning for .spn source files (computing namespaces)
 *   4. Recursing into subdirectories that have their own module.spn
 *   5. Applying version defaults from ancestor artifacts
 */
public final class ArtifactResolver {

    private static final String MODULE_FILE = "module.spn";

    /**
     * Resolves the module rooted at the given directory.
     *
     * @param root directory containing module.spn
     * @return the fully resolved module tree
     */
    public SpnArtifact resolve(Path root) throws IOException {
        return resolve(root, List.of(), Map.of());
    }

    /**
     * Resolves with inherited defaults and profiles from ancestor modules.
     */
    private SpnArtifact resolve(Path root, List<ArtifactId> inheritedDefaults,
                                Map<String, String> inheritedProfiles) throws IOException {
        Path moduleFile = root.resolve(MODULE_FILE);
        if (!Files.exists(moduleFile)) {
            throw new IOException("No " + MODULE_FILE + " found in " + root);
        }

        ModuleParser.ParseResult parsed = parseModuleFile(moduleFile);

        // Split namespace into ArtifactId (group = all but last segment, name = last)
        ArtifactId id = toArtifactId(parsed.id());

        // Apply version inheritance
        if (id.version() == null) {
            String resolved = resolveVersionFromDefaults(id.group(), id.name(), inheritedDefaults);
            if (resolved != null) {
                id = id.withVersion(resolved);
            } else {
                throw new IOException("Unversioned module: " + parsed.id().namespace()
                        + " — no version declared and none found in ancestor defaults");
            }
        }

        // Convert requires to ArtifactId for dependency resolution
        List<ArtifactId> dependencies = parsed.requires().stream()
                .map(ArtifactResolver::requireToArtifactId)
                .toList();

        // Merge inherited profiles (ancestor wins)
        Map<String, String> mergedProfiles = new LinkedHashMap<>(inheritedProfiles);

        // Discover nested modules and source files
        List<SpnArtifact> nested = new ArrayList<>();
        Map<String, Path> sourceFiles = new LinkedHashMap<>();
        Set<Path> nestedRoots = new HashSet<>();

        findNestedArtifactRoots(root, nestedRoots);

        for (Path nestedRoot : nestedRoots) {
            nested.add(resolve(nestedRoot, inheritedDefaults, mergedProfiles));
        }

        detectVersionConflicts(nested);
        collectSourceFiles(root, id, sourceFiles, nestedRoots);

        return new SpnArtifact(id, root, dependencies,
                List.of(), mergedProfiles, nested, sourceFiles);
    }

    private ModuleParser.ParseResult parseModuleFile(Path moduleFile) throws IOException {
        String source = Files.readString(moduleFile);
        return new ModuleParser(source).parse();
    }

    private static ArtifactId toArtifactId(ModuleId moduleId) {
        String ns = moduleId.namespace();
        int lastDot = ns.lastIndexOf('.');
        String group = lastDot > 0 ? ns.substring(0, lastDot) : "";
        String name = lastDot > 0 ? ns.substring(lastDot + 1) : ns;
        return new ArtifactId(group, name, moduleId.version());
    }

    private static ArtifactId requireToArtifactId(String namespace) {
        int lastDot = namespace.lastIndexOf('.');
        if (lastDot > 0) {
            return new ArtifactId(namespace.substring(0, lastDot),
                    namespace.substring(lastDot + 1), null);
        }
        return new ArtifactId("", namespace, null);
    }

    /**
     * Finds immediate subdirectories that contain module.spn.
     * Does NOT recurse into those — they handle their own children.
     */
    private void findNestedArtifactRoots(Path root, Set<Path> result) throws IOException {
        try (var stream = Files.walk(root)) {
            stream.filter(p -> p.getFileName().toString().equals(MODULE_FILE))
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
                if (name.endsWith(".spn") && !name.equals(MODULE_FILE)) {
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
     * Detects version conflicts among sibling artifacts.
     * If two artifacts at the same depth declare different versions of the same
     * dependency, that's an error — must be resolved in root defaults.
     */
    private void detectVersionConflicts(List<SpnArtifact> siblings) throws IOException {
        // Collect all dependency declarations from siblings, keyed by group:name
        Map<String, ArtifactId> seen = new HashMap<>();
        for (SpnArtifact sibling : siblings) {
            for (ArtifactId dep : sibling.getDependencies()) {
                String key = dep.group() + ":" + dep.name();
                ArtifactId existing = seen.get(key);
                if (existing != null && dep.version() != null && existing.version() != null
                        && !dep.version().equals(existing.version())) {
                    throw new IOException("Version conflict for " + key
                            + ": " + existing.version() + " vs " + dep.version()
                            + " — resolve in root defaults");
                }
                if (existing == null || (dep.version() != null && existing.version() == null)) {
                    seen.put(key, dep);
                }
            }
        }
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
     * Merges profiles: ancestor (inherited) profiles win over local,
     * because the root project controls implementation configuration.
     */
    private Map<String, String> mergeProfiles(Map<String, String> local,
                                               Map<String, String> inherited) {
        Map<String, String> merged = new LinkedHashMap<>(local);
        merged.putAll(inherited); // ancestor wins
        return merged;
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
