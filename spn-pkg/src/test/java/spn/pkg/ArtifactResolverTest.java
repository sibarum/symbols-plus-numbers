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
    void resolvesFlatModule() throws IOException {
        writeFile(tempDir, "module.spn", """
            module spn.myapp
            version "1.0.0"
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
    void resolvesNestedModules() throws IOException {
        writeFile(tempDir, "module.spn", """
            module spn.root
            version "1.0.0"
            """);
        writeFile(tempDir, "main.spn", "-- root main");

        Path libDir = tempDir.resolve("lib").resolve("collections");
        writeFile(libDir, "module.spn", """
            module spn.collections
            version "2.1.0"
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
        assertEquals("2.1.0", nested.getId().version());
        assertEquals(2, nested.getSourceFiles().size());
        assertTrue(nested.getSourceFiles().containsKey("spn.collections.sorted"));
        assertTrue(nested.getSourceFiles().containsKey("spn.collections.hashing"));
    }

    @Test
    void nestedFilesNotClaimedByParent() throws IOException {
        writeFile(tempDir, "module.spn", """
            module spn.root
            version "1.0.0"
            """);
        writeFile(tempDir, "app.spn", "-- app");

        Path libDir = tempDir.resolve("lib");
        writeFile(libDir, "module.spn", """
            module spn.lib
            version "1.0.0"
            """);
        writeFile(libDir, "impl.spn", "-- lib impl");

        SpnArtifact root = new ArtifactResolver().resolve(tempDir);

        assertEquals(1, root.getSourceFiles().size());
        assertTrue(root.getSourceFiles().containsKey("spn.root.app"));
        assertFalse(root.getSourceFiles().containsKey("spn.root.lib.impl"));

        assertEquals(1, root.getNested().size());
        assertTrue(root.getNested().getFirst().getSourceFiles()
                .containsKey("spn.lib.impl"));
    }

    @Test
    void namespaceResolution() throws IOException {
        writeFile(tempDir, "module.spn", """
            module spn.myapp
            version "1.0.0"
            """);
        writeFile(tempDir, "main.spn", "-- main");
        writeFile(tempDir.resolve("util"), "math.spn", "-- math");

        Path libDir = tempDir.resolve("lib");
        writeFile(libDir, "module.spn", """
            module spn.stdlib
            version "1.0.0"
            """);
        writeFile(libDir, "core.spn", "-- core");

        SpnArtifact root = new ArtifactResolver().resolve(tempDir);

        assertNotNull(root.resolveNamespace("spn.myapp.main"));
        assertNotNull(root.resolveNamespace("spn.myapp.util.math"));
        assertNotNull(root.resolveNamespace("spn.stdlib.core"));
        assertNull(root.resolveNamespace("spn.unknown.whatever"));
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
    void deeplyNestedModules() throws IOException {
        writeFile(tempDir, "module.spn", """
            module org.app
            version "1.0.0"
            """);

        Path lib = tempDir.resolve("vendor/lib");
        writeFile(lib, "module.spn", """
            module org.lib
            version "2.0.0"
            """);
        writeFile(lib, "api.spn", "-- api");

        Path deep = lib.resolve("internal/deep");
        writeFile(deep, "module.spn", """
            module org.deep
            version "3.0.0"
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
    void errorOnUnversionedModule() throws IOException {
        writeFile(tempDir, "module.spn", """
            module spn.root
            version "1.0.0"
            """);

        Path libDir = tempDir.resolve("lib");
        writeFile(libDir, "module.spn", """
            module spn.orphan
            """);

        var resolver = new ArtifactResolver();
        var ex = assertThrows(IOException.class, () -> resolver.resolve(tempDir));
        assertTrue(ex.getMessage().contains("Unversioned module"));
    }

    @Test
    void requireStatements() throws IOException {
        writeFile(tempDir, "module.spn", """
            module com.mysite.myapp
            version "1.0.0"
            require "com.other.lib"
            require "com.another.dep"
            """);

        SpnArtifact root = new ArtifactResolver().resolve(tempDir);
        assertEquals(2, root.getDependencies().size());
        assertEquals("com.other", root.getDependencies().get(0).group());
        assertEquals("lib", root.getDependencies().get(0).name());
        assertEquals("com.another", root.getDependencies().get(1).group());
        assertEquals("dep", root.getDependencies().get(1).name());
    }
}
