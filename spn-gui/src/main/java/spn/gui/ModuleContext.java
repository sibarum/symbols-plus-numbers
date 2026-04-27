package spn.gui;

import spn.pkg.ModuleParser;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Cached module information for the currently open file's module.
 * Detected by scanning up the directory tree for {@code module.spn}.
 */
public class ModuleContext {

    private static final String MODULE_FILE = "module.spn";

    private final Path root;
    private final String namespace;
    private final String version;
    private volatile List<ModuleFile> files;

    public record ModuleFile(Path absolutePath, String relativePath) {}

    /** A single matched line within a file. {@code matchStart}/{@code matchEnd} are 0-based char offsets within {@code lineText}. */
    public record ContentMatch(ModuleFile file, int lineNumber, String lineText,
                               int matchStart, int matchEnd) {}

    private ModuleContext(Path root, String namespace, String version, List<ModuleFile> files) {
        this.root = root;
        this.namespace = namespace;
        this.version = version;
        this.files = List.copyOf(files);
    }

    public Path getRoot() { return root; }
    public String getNamespace() { return namespace; }
    public String getVersion() { return version; }
    public List<ModuleFile> getFiles() { return files; }

    /** Rescan the module directory for new/deleted files. */
    public void rescan() {
        try {
            Set<Path> nestedRoots = new HashSet<>();
            findNestedModuleRoots(root, nestedRoots);

            List<ModuleFile> updated = new ArrayList<>();
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (!dir.equals(root) && nestedRoots.contains(dir))
                        return FileVisitResult.SKIP_SUBTREE;
                    return FileVisitResult.CONTINUE;
                }
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    String name = file.getFileName().toString();
                    if ((name.endsWith(".spn") || name.endsWith(".spnt"))
                            && !name.equals(MODULE_FILE)) {
                        String rel = root.relativize(file).toString().replace('\\', '/');
                        updated.add(new ModuleFile(file, rel));
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
            updated.sort(Comparator.comparing(ModuleFile::relativePath));
            this.files = List.copyOf(updated);
        } catch (IOException ignored) {
            // Keep existing file list on error
        }
    }

    /**
     * Detect a module by scanning up from the given file's directory.
     * Returns null if no module.spn is found within 20 levels.
     */
    public static ModuleContext detect(Path filePath) {
        Path dir = filePath.toAbsolutePath().getParent();
        if (dir == null) return null;

        // Walk up looking for module.spn
        for (int i = 0; i < 20 && dir != null; i++) {
            Path moduleFile = dir.resolve(MODULE_FILE);
            if (Files.isRegularFile(moduleFile)) {
                try {
                    return load(dir, moduleFile);
                } catch (Exception e) {
                    // Caller (EditorWindow.detectModule) will show "No module loaded"
                    // if we return null — no need to log separately
                    return null;
                }
            }
            dir = dir.getParent();
        }
        return null;
    }

    /**
     * Check if a file path is within this module's root.
     */
    public boolean contains(Path filePath) {
        return filePath.toAbsolutePath().startsWith(root);
    }

    /**
     * Filter files by substring match on relative path.
     */
    public List<ModuleFile> filterByName(String query) {
        if (query == null || query.isEmpty()) return files;
        String lower = query.toLowerCase();
        List<ModuleFile> result = new ArrayList<>();
        for (ModuleFile f : files) {
            if (f.relativePath().toLowerCase().contains(lower)) {
                result.add(f);
            }
        }
        return result;
    }

    /**
     * Search file contents line-by-line. {@code query} is interpreted as a literal
     * with {@code *} / {@code ?} wildcards when {@code isRegex} is false, or as a
     * full regex when true. Both modes are case-insensitive. The first match per
     * line is reported; multiple matched lines per file are returned in order.
     *
     * @throws PatternSyntaxException if {@code isRegex} is true and the query is not
     *         a valid regex. Callers (the GUI) catch and surface this to the user.
     */
    public List<ContentMatch> searchContents(String query, boolean isRegex) {
        if (query == null || query.isEmpty()) return List.of();
        String regex = isRegex ? query : wildcardToRegex(query);
        Pattern pattern = Pattern.compile(regex, Pattern.CASE_INSENSITIVE);

        List<ContentMatch> result = new ArrayList<>();
        for (ModuleFile f : files) {
            String content;
            try {
                content = Files.readString(f.absolutePath());
            } catch (IOException ignored) {
                continue;
            }
            int lineNum = 1;
            int start = 0;
            int len = content.length();
            while (start <= len) {
                int end = content.indexOf('\n', start);
                if (end < 0) end = len;
                String line = content.substring(start, end);
                Matcher m = pattern.matcher(line);
                if (m.find()) {
                    result.add(new ContentMatch(f, lineNum, line, m.start(), m.end()));
                }
                if (end == len) break;
                start = end + 1;
                lineNum++;
            }
        }
        return result;
    }

    /** Convert a glob-style query ({@code *}, {@code ?}) to a regex; other regex metachars are escaped. */
    static String wildcardToRegex(String query) {
        StringBuilder sb = new StringBuilder(query.length() * 2);
        for (int i = 0; i < query.length(); i++) {
            char c = query.charAt(i);
            switch (c) {
                case '*' -> sb.append(".*");
                case '?' -> sb.append(".");
                case '.', '\\', '+', '(', ')', '[', ']', '{', '}', '^', '$', '|' ->
                        sb.append('\\').append(c);
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    // ---- Loading ----

    private static ModuleContext load(Path root, Path moduleFile) throws IOException {
        String source = Files.readString(moduleFile);
        ModuleParser.ParseResult parsed = new ModuleParser(source).parse();

        String namespace = parsed.id().namespace();
        String version = parsed.id().version() != null ? parsed.id().version() : "";

        // Scan for .spn and .spnt files, excluding nested module.spn subtrees
        Set<Path> nestedRoots = new HashSet<>();
        findNestedModuleRoots(root, nestedRoots);

        List<ModuleFile> files = new ArrayList<>();
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
                if ((name.endsWith(".spn") || name.endsWith(".spnt"))
                        && !name.equals(MODULE_FILE)) {
                    String rel = root.relativize(file).toString().replace('\\', '/');
                    files.add(new ModuleFile(file, rel));
                }
                return FileVisitResult.CONTINUE;
            }
        });

        files.sort(Comparator.comparing(ModuleFile::relativePath));
        return new ModuleContext(root, namespace, version, files);
    }

    private static void findNestedModuleRoots(Path root, Set<Path> result) throws IOException {
        try (var stream = Files.walk(root)) {
            stream.filter(p -> p.getFileName().toString().equals(MODULE_FILE))
                  .filter(p -> !p.getParent().equals(root))
                  .forEach(p -> result.add(p.getParent()));
        }
    }
}
