package spn.pkg;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ArtifactParserTest {

    @Test
    void parsesMinimalArtifact() {
        var result = new ArtifactParser("""
            artifact [:group "spn", :name "stdlib", :version "1.0.0"]
            """).parse();

        assertEquals("spn", result.id().group());
        assertEquals("stdlib", result.id().name());
        assertEquals("1.0.0", result.id().version());
    }

    @Test
    void parsesArtifactWithoutVersion() {
        var result = new ArtifactParser("""
            artifact [:group "spn", :name "collections"]
            """).parse();

        assertEquals("spn", result.id().group());
        assertEquals("collections", result.id().name());
        assertNull(result.id().version());
    }

    @Test
    void parsesDependencies() {
        var result = new ArtifactParser("""
            artifact [:group "spn", :name "myapp", :version "1.0.0"]
            require [
              [:group "spn", :name "collections", :version "2.1.0"],
              [:group "spn", :name "io", :version "1.0.0"]
            ]
            """).parse();

        assertEquals(2, result.dependencies().size());
        assertEquals("spn:collections:2.1.0", result.dependencies().get(0).coordinate());
        assertEquals("spn:io:1.0.0", result.dependencies().get(1).coordinate());
    }

    @Test
    void parsesDefaults() {
        var result = new ArtifactParser("""
            artifact [:group "spn", :name "root", :version "1.0.0"]
            defaults [
              [:group "spn", :name "collections", :version "2.1.0"],
              [:group "spn", :name "testing", :version "0.5.0"]
            ]
            """).parse();

        assertEquals(2, result.defaults().size());
        assertEquals("2.1.0", result.defaults().get(0).version());
        assertEquals("0.5.0", result.defaults().get(1).version());
    }

    @Test
    void parsesProfiles() {
        var result = new ArtifactParser("""
            artifact [:group "spn", :name "myapp", :version "1.0.0"]
            profiles [
              [:collection.default :sorted_array],
              [:string.encoding :utf8]
            ]
            """).parse();

        assertEquals("sorted_array", result.profiles().get("collection.default"));
        assertEquals("utf8", result.profiles().get("string.encoding"));
    }

    @Test
    void parsesFullArtifact() {
        var result = new ArtifactParser("""
            artifact [:group "spn", :name "stdlib", :version "1.0.0"]
            require [
              [:group "spn", :name "io", :version "1.0.0"]
            ]
            defaults [
              [:group "spn", :name "io", :version "1.0.0"],
              [:group "spn", :name "collections", :version "2.1.0"]
            ]
            profiles [
              [:collection.default :sorted_array]
            ]
            """).parse();

        assertEquals("spn:stdlib:1.0.0", result.id().coordinate());
        assertEquals(1, result.dependencies().size());
        assertEquals(2, result.defaults().size());
        assertEquals(1, result.profiles().size());
    }

    @Test
    void rejectsMissingArtifact() {
        assertThrows(Exception.class, () ->
            new ArtifactParser("require []").parse());
    }

    @Test
    void rejectsMissingGroup() {
        assertThrows(Exception.class, () ->
            new ArtifactParser("artifact [:name \"test\"]").parse());
    }
}
