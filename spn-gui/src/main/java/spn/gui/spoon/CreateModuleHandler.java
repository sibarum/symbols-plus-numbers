package spn.gui.spoon;

import spn.gui.EditorWindow;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Handler for the "Create Module" .spoon command.
 *
 * <p>Creates an SPN language module: a {@code .spn} source file and an
 * {@code artifact.spn} manifest (if one doesn't already exist in the folder).
 *
 * <p>The module namespace is derived automatically from the file's position
 * relative to {@code artifact.spn}, following the convention:
 * {@code group.name} (e.g., {@code spn.mylib}).
 */
public final class CreateModuleHandler {

    private CreateModuleHandler() {}

    public static void handle(Map<String, String> fields, EditorWindow window) throws Exception {
        String group = fields.getOrDefault("group", "").trim();
        String name = fields.getOrDefault("name", "").trim();
        String version = fields.getOrDefault("version", "").trim();
        String folder = fields.getOrDefault("folder", "").trim();

        if (name.isEmpty()) throw new IllegalArgumentException("name is required");
        if (group.isEmpty()) throw new IllegalArgumentException("group is required");

        // Default folder to the module name
        if (folder.isEmpty()) folder = name;

        Path moduleDir = Path.of(folder);
        Files.createDirectories(moduleDir);

        // Create artifact.spn if it doesn't exist
        Path artifactFile = moduleDir.resolve("artifact.spn");
        if (!Files.exists(artifactFile)) {
            Files.writeString(artifactFile, generateArtifact(group, name, version));
        }

        // Create the root .spn module file: <name>.spn
        Path moduleFile = moduleDir.resolve(name + ".spn");
        if (Files.exists(moduleFile)) {
            throw new IOException(moduleFile + " already exists");
        }
        String namespace = group + "." + name;
        Files.writeString(moduleFile, generateModule(namespace));
    }

    private static String generateArtifact(String group, String name, String version) {
        StringBuilder sb = new StringBuilder();
        sb.append("artifact [:group \"").append(group)
          .append("\", :name \"").append(name).append("\"");
        if (!version.isEmpty()) {
            sb.append(", :version \"").append(version).append("\"");
        }
        sb.append("]\n");
        return sb.toString();
    }

    private static String generateModule(String namespace) {
        return "-- " + namespace + "\n\n";
    }
}
