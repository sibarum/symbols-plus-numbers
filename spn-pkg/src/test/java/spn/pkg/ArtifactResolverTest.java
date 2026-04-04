package spn.pkg;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ArtifactResolverTest {

    @TempDir
    Path tempDir;

    private void writeFile(Path dir, String name, String content) throws IOException {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(name), content);
    }

    @Test
    void resolvesFlatArtifact() throws IOException {
        writeFile(tempDir, "artifact.spn", """
            artifact [:group "spn", :name "myapp", :version "1.0.0"]
            """);
        writeFile(tempDir, "main.spn", "let x = 42");
        writeFile(tempDir.resolve("util"), "strings.spn", "-- utils");

        SpnArtifact artifact = new ArtifactResolver().resolve(tempDir);

        assertEquals("spn:myapp:1.0.0", artifact.getId().coordinate());
        assertEquals(2, artifact.getSourceFiles().size());
        assertTrue(artifact.getSourceFiles().containsKey("spn.myapp.main"));
        assertTrue(artifact.getSourceFiles().containsKey("spn.myapp.util.strings"));
        assertTrue(artifact.getNested().isEmpty());
    }

    @Test
    void resolvesNestedArtifacts() throws IOException {
        // Root artifact
        writeFile(tempDir, "artifact.spn", """
            artifact [:group "spn", :name "root", :version "1.0.0"]
            defaults [
              [:group "spn", :name "collections", :version "2.1.0"]
            ]
            """);
        writeFile(tempDir, "main.spn", "-- root main");

        // Nested artifact
        Path libDir = tempDir.resolve("lib").resolve("collections");
        writeFile(libDir, "artifact.spn", """
            artifact [:group "spn", :name "collections"]
            """);
        writeFile(libDir, "sorted.spn", "-- sorted impl");
        writeFile(libDir, "hashing.spn", "-- hashing impl");

        SpnArtifact root = new ArtifactResolver().resolve(tempDir);

        assertEquals("spn:root:1.0.0", root.getId().coordinate());
        assertEquals(1, root.getSourceFiles().size());
        assertTrue(root.getSourceFiles().containsKey("spn.root.main"));

        assertEquals(1, root.getNested().size());
        SpnArtifact nested = root.getNested().getFirst();
        assertEquals("spn", nested.getId().group());
        assertEquals("collections", nested.getId().name());
        // Version inherited from root defaults
        assertEquals("2.1.0", nested.getId().version());
        assertEquals(2, nested.getSourceFiles().size());
        assertTrue(nested.getSourceFiles().containsKey("spn.collections.sorted"));
        assertTrue(nested.getSourceFiles().containsKey("spn.collections.hashing"));
    }

    @Test
    void nestedFilesNotClaimedByParent() throws IOException {
        writeFile(tempDir, "artifact.spn", """
            artifact [:group "spn", :name "root", :version "1.0.0"]
            """);
        writeFile(tempDir, "app.spn", "-- app");

        Path libDir = tempDir.resolve("lib");
        writeFile(libDir, "artifact.spn", """
            artifact [:group "spn", :name "lib", :version "1.0.0"]
            """);
        writeFile(libDir, "impl.spn", "-- lib impl");

        SpnArtifact root = new ArtifactResolver().resolve(tempDir);

        // Root should only have app.spn, not lib/impl.spn
        assertEquals(1, root.getSourceFiles().size());
        assertTrue(root.getSourceFiles().containsKey("spn.root.app"));
        assertFalse(root.getSourceFiles().containsKey("spn.root.lib.impl"));

        // Nested artifact should have impl.spn
        assertEquals(1, root.getNested().size());
        assertTrue(root.getNested().getFirst().getSourceFiles()
                .containsKey("spn.lib.impl"));
    }

    @Test
    void namespaceResolution() throws IOException {
        writeFile(tempDir, "artifact.spn", """
            artifact [:group "spn", :name "myapp", :version "1.0.0"]
            """);
        writeFile(tempDir, "main.spn", "-- main");
        writeFile(tempDir.resolve("util"), "math.spn", "-- math");

        Path libDir = tempDir.resolve("lib");
        writeFile(libDir, "artifact.spn", """
            artifact [:group "spn", :name "stdlib", :version "1.0.0"]
            """);
        writeFile(libDir, "core.spn", "-- core");

        SpnArtifact root = new ArtifactResolver().resolve(tempDir);

        // Resolve local namespace
        assertNotNull(root.resolveNamespace("spn.myapp.main"));
        assertNotNull(root.resolveNamespace("spn.myapp.util.math"));

        // Resolve nested namespace
        assertNotNull(root.resolveNamespace("spn.stdlib.core"));

        // Unknown namespace
        assertNull(root.resolveNamespace("spn.unknown.whatever"));
    }

    @Test
    void profilesFlowThrough() throws IOException {
        writeFile(tempDir, "artifact.spn", """
            artifact [:group "spn", :name "myapp", :version "1.0.0"]
            profiles [
              [:collection.default :sorted_array],
              [:numeric.overflow :saturate]
            ]
            """);

        SpnArtifact root = new ArtifactResolver().resolve(tempDir);
        assertEquals("sorted_array", root.profile("collection.default"));
        assertEquals("saturate", root.profile("numeric.overflow"));
        assertNull(root.profile("nonexistent"));
    }

    @Test
    void computeNamespaceWorks() {
        Path root = Path.of("/project");
        ArtifactId id = new ArtifactId("spn", "stdlib", "1.0.0");

        assertEquals("spn.stdlib.main",
                ArtifactResolver.computeNamespace(root, root.resolve("main.spn"), id));
        assertEquals("spn.stdlib.util.math",
                ArtifactResolver.computeNamespace(root, root.resolve("util/math.spn"), id));
        assertEquals("spn.stdlib.deep.nested.file",
                ArtifactResolver.computeNamespace(root, root.resolve("deep/nested/file.spn"), id));
    }

    @Test
    void deeplyNestedArtifacts() throws IOException {
        // Root
        writeFile(tempDir, "artifact.spn", """
            artifact [:group "org", :name "app", :version "1.0.0"]
            defaults [
              [:group "org", :name "lib", :version "2.0.0"],
              [:group "org", :name "deep", :version "3.0.0"]
            ]
            """);

        // Level 1 nested
        Path lib = tempDir.resolve("vendor/lib");
        writeFile(lib, "artifact.spn", """
            artifact [:group "org", :name "lib"]
            """);
        writeFile(lib, "api.spn", "-- api");

        // Level 2 nested (inside lib)
        Path deep = lib.resolve("internal/deep");
        writeFile(deep, "artifact.spn", """
            artifact [:group "org", :name "deep"]
            """);
        writeFile(deep, "impl.spn", "-- deep impl");

        SpnArtifact root = new ArtifactResolver().resolve(tempDir);

        assertEquals("org:app:1.0.0", root.getId().coordinate());
        assertEquals(1, root.getNested().size());

        SpnArtifact libArtifact = root.getNested().getFirst();
        assertEquals("org:lib:2.0.0", libArtifact.getId().coordinate());
        assertEquals(1, libArtifact.getSourceFiles().size());

        assertEquals(1, libArtifact.getNested().size());
        SpnArtifact deepArtifact = libArtifact.getNested().getFirst();
        assertEquals("org:deep:3.0.0", deepArtifact.getId().coordinate());
        assertTrue(deepArtifact.getSourceFiles().containsKey("org.deep.impl"));
    }

    @Test
    void errorOnUnversionedArtifact() throws IOException {
        writeFile(tempDir, "artifact.spn", """
            artifact [:group "spn", :name "root", :version "1.0.0"]
            """);

        // Nested artifact with no version and no defaults to inherit from
        Path libDir = tempDir.resolve("lib");
        writeFile(libDir, "artifact.spn", """
            artifact [:group "spn", :name "orphan"]
            """);

        var resolver = new ArtifactResolver();
        var ex = assertThrows(IOException.class, () -> resolver.resolve(tempDir));
        assertTrue(ex.getMessage().contains("Unversioned artifact"));
        assertTrue(ex.getMessage().contains("spn:orphan"));
    }

    @Test
    void profilesInheritedByNestedArtifacts() throws IOException {
        writeFile(tempDir, "artifact.spn", """
            artifact [:group "spn", :name "root", :version "1.0.0"]
            defaults [
              [:group "spn", :name "lib", :version "1.0.0"]
            ]
            profiles [
              [:collection.default :sorted_array],
              [:numeric.overflow :saturate]
            ]
            """);

        Path libDir = tempDir.resolve("lib");
        writeFile(libDir, "artifact.spn", """
            artifact [:group "spn", :name "lib"]
            profiles [
              [:lib.specific :value],
              [:numeric.overflow :wrap]
            ]
            """);
        writeFile(libDir, "core.spn", "-- core");

        SpnArtifact root = new ArtifactResolver().resolve(tempDir);
        SpnArtifact nested = root.getNested().getFirst();

        // Root profiles flow down
        assertEquals("sorted_array", nested.profile("collection.default"));
        // Ancestor profile wins over local
        assertEquals("saturate", nested.profile("numeric.overflow"));
        // Local-only profile preserved
        assertEquals("value", nested.profile("lib.specific"));
    }

    @Test
    void versionConflictAmongSiblings() throws IOException {
        writeFile(tempDir, "artifact.spn", """
            artifact [:group "org", :name "root", :version "1.0.0"]
            defaults [
              [:group "org", :name "a", :version "1.0.0"],
              [:group "org", :name "b", :version "1.0.0"]
            ]
            """);

        // Sibling A depends on utils 1.0
        Path aDir = tempDir.resolve("a");
        writeFile(aDir, "artifact.spn", """
            artifact [:group "org", :name "a"]
            require [
              [:group "org", :name "utils", :version "1.0.0"]
            ]
            """);

        // Sibling B depends on utils 2.0 — conflict!
        Path bDir = tempDir.resolve("b");
        writeFile(bDir, "artifact.spn", """
            artifact [:group "org", :name "b"]
            require [
              [:group "org", :name "utils", :version "2.0.0"]
            ]
            """);

        var resolver = new ArtifactResolver();
        var ex = assertThrows(IOException.class, () -> resolver.resolve(tempDir));
        assertTrue(ex.getMessage().contains("Version conflict"));
        assertTrue(ex.getMessage().contains("org:utils"));
    }

    @Test
    void noConflictWhenVersionsMatch() throws IOException {
        writeFile(tempDir, "artifact.spn", """
            artifact [:group "org", :name "root", :version "1.0.0"]
            defaults [
              [:group "org", :name "a", :version "1.0.0"],
              [:group "org", :name "b", :version "1.0.0"]
            ]
            """);

        Path aDir = tempDir.resolve("a");
        writeFile(aDir, "artifact.spn", """
            artifact [:group "org", :name "a"]
            require [
              [:group "org", :name "utils", :version "1.0.0"]
            ]
            """);

        Path bDir = tempDir.resolve("b");
        writeFile(bDir, "artifact.spn", """
            artifact [:group "org", :name "b"]
            require [
              [:group "org", :name "utils", :version "1.0.0"]
            ]
            """);

        // Should not throw — both agree on version 1.0.0
        SpnArtifact root = new ArtifactResolver().resolve(tempDir);
        assertEquals(2, root.getNested().size());
    }
}
