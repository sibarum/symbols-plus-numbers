package spn.pkg;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ImportResolverTest {

    @TempDir
    Path tempDir;

    private void writeFile(Path dir, String name, String content) throws IOException {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(name), content);
    }

    @Test
    void resolvesLocalFile() throws IOException {
        writeFile(tempDir, "module.spn", """
            module spn.myapp
            version "1.0.0"
            """);
        writeFile(tempDir, "main.spn", "-- main");
        writeFile(tempDir.resolve("util"), "math.spn", "-- math");

        SpnArtifact root = new ArtifactResolver().resolve(tempDir);
        ImportResolver resolver = new ImportResolver(root);

        var result = resolver.resolve("spn.myapp.main");
        assertTrue(result.isPresent());
        assertInstanceOf(ImportResolver.ResolvedImport.LocalFile.class, result.get());
        var local = (ImportResolver.ResolvedImport.LocalFile) result.get();
        assertEquals("spn.myapp.main", local.namespace());
        assertEquals(root, local.artifact());
    }

    @Test
    void resolvesNestedModuleFile() throws IOException {
        writeFile(tempDir, "module.spn", """
            module spn.myapp
            version "1.0.0"
            """);
        writeFile(tempDir, "main.spn", "-- main");

        Path libDir = tempDir.resolve("lib");
        writeFile(libDir, "module.spn", """
            module spn.lib
            version "1.0.0"
            """);
        writeFile(libDir, "core.spn", "-- core");

        SpnArtifact root = new ArtifactResolver().resolve(tempDir);
        ImportResolver resolver = new ImportResolver(root);

        var result = resolver.resolve("spn.lib.core");
        assertTrue(result.isPresent());
        assertInstanceOf(ImportResolver.ResolvedImport.LocalFile.class, result.get());
        var local = (ImportResolver.ResolvedImport.LocalFile) result.get();
        assertEquals("spn.lib.core", local.namespace());
        assertEquals("spn:lib:1.0.0", local.artifact().getId().coordinate());
    }

    @Test
    void resolvesDeclaredDependency() throws IOException {
        writeFile(tempDir, "module.spn", """
            module spn.myapp
            version "1.0.0"
            require "spn.collections"
            """);

        SpnArtifact root = new ArtifactResolver().resolve(tempDir);
        ImportResolver resolver = new ImportResolver(root);

        var result = resolver.resolve("spn.collections.sorted");
        assertTrue(result.isPresent());
        assertInstanceOf(ImportResolver.ResolvedImport.ExternalDependency.class, result.get());
        var ext = (ImportResolver.ResolvedImport.ExternalDependency) result.get();
        assertEquals("spn.collections.sorted", ext.namespace());
    }

    @Test
    void returnsEmptyForUnknownNamespace() throws IOException {
        writeFile(tempDir, "module.spn", """
            module spn.myapp
            version "1.0.0"
            """);

        SpnArtifact root = new ArtifactResolver().resolve(tempDir);
        ImportResolver resolver = new ImportResolver(root);

        var result = resolver.resolve("spn.unknown.whatever");
        assertTrue(result.isEmpty());
    }

    @Test
    void prefersLocalOverNested() throws IOException {
        writeFile(tempDir, "module.spn", """
            module spn.myapp
            version "1.0.0"
            """);
        writeFile(tempDir, "main.spn", "-- main");

        Path libDir = tempDir.resolve("lib");
        writeFile(libDir, "module.spn", """
            module spn.lib
            version "1.0.0"
            """);
        writeFile(libDir, "core.spn", "-- core");

        SpnArtifact root = new ArtifactResolver().resolve(tempDir);
        ImportResolver resolver = new ImportResolver(root);

        var result = resolver.resolve("spn.myapp.main");
        assertTrue(result.isPresent());
        var local = (ImportResolver.ResolvedImport.LocalFile) result.get();
        assertEquals(root, local.artifact());
    }
}
