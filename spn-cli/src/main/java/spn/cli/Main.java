package spn.cli;

import spn.lang.ClasspathModuleLoader;
import spn.lang.FilesystemModuleLoader;
import spn.lang.SpnParseException;
import spn.lang.SpnParser;
import spn.language.SpnException;
import spn.language.SpnModuleRegistry;
import spn.node.SpnRootNode;
import spn.pkg.ModuleParser;
import spn.type.SpnSymbolTable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Command-line runner for SPN scripts.
 *
 * <pre>
 *   spn script.spn                 # run a single file
 *   spn script.spn --quiet         # suppress the result line
 * </pre>
 *
 * <p>If a {@code module.spn} exists in any parent directory, its namespace is
 * used to wire up a {@link FilesystemModuleLoader}, so local module imports
 * resolve. The stdlib is always loaded.
 */
public final class Main {

    public static void main(String[] args) {
        int exit = new Main().run(args);
        System.exit(exit);
    }

    int run(String[] args) {
        if (args.length == 0 || isHelp(args[0])) {
            printUsage();
            return args.length == 0 ? 2 : 0;
        }

        boolean quiet = false;
        Path script = null;
        for (String arg : args) {
            if (arg.equals("-q") || arg.equals("--quiet")) {
                quiet = true;
            } else if (arg.startsWith("-")) {
                System.err.println("Unknown option: " + arg);
                printUsage();
                return 2;
            } else if (script == null) {
                script = Path.of(arg);
            } else {
                System.err.println("Too many script arguments");
                printUsage();
                return 2;
            }
        }
        if (script == null) {
            printUsage();
            return 2;
        }

        Path absScript = script.toAbsolutePath().normalize();
        if (!Files.isRegularFile(absScript)) {
            System.err.println("File not found: " + absScript);
            return 1;
        }

        String source;
        try {
            source = Files.readString(absScript);
        } catch (IOException e) {
            System.err.println("Cannot read " + absScript + ": " + e.getMessage());
            return 1;
        }

        SpnSymbolTable symbolTable = new SpnSymbolTable();
        SpnModuleRegistry registry = new SpnModuleRegistry();
        spn.stdlib.gen.StdlibModuleLoader.registerAll(registry);
        registry.addLoader(new ClasspathModuleLoader(null, symbolTable));

        ModuleRoot moduleRoot = findModuleRoot(absScript);
        if (moduleRoot != null) {
            registry.addLoader(new FilesystemModuleLoader(
                    moduleRoot.root, moduleRoot.namespace, null, symbolTable));
        }

        try {
            SpnParser parser = new SpnParser(source, absScript.toString(), null,
                    symbolTable, registry);
            SpnRootNode root;
            try {
                root = parser.parse();
            } catch (SpnParseException pe) {
                for (SpnParseException e : parser.getErrors()) {
                    System.err.println(e.formatMessage());
                }
                if (parser.getErrors().isEmpty()) {
                    System.err.println(pe.formatMessage());
                }
                return 1;
            }
            Object result = root.getCallTarget().call();
            if (!quiet) {
                System.out.println(result == null ? "(no result)" : result);
            }
            return 0;
        } catch (SpnException se) {
            System.err.println(se.formatMessage());
            return 1;
        } catch (RuntimeException e) {
            String msg = e.getMessage();
            System.err.println(msg != null ? msg : e.getClass().getSimpleName());
            return 1;
        }
    }

    private static boolean isHelp(String s) {
        return s.equals("-h") || s.equals("--help");
    }

    private static void printUsage() {
        System.err.println("Usage: spn [options] <script.spn>");
        System.err.println();
        System.err.println("Options:");
        System.err.println("  -q, --quiet    Suppress result output");
        System.err.println("  -h, --help     Show this help");
    }

    /** Walk up from the script looking for module.spn (max 20 levels). */
    static ModuleRoot findModuleRoot(Path script) {
        Path dir = script.getParent();
        for (int i = 0; i < 20 && dir != null; i++) {
            Path moduleFile = dir.resolve("module.spn");
            if (Files.isRegularFile(moduleFile)) {
                try {
                    String src = Files.readString(moduleFile);
                    ModuleParser.ParseResult parsed = new ModuleParser(src).parse();
                    return new ModuleRoot(dir, parsed.id().namespace());
                } catch (Exception e) {
                    return null;
                }
            }
            dir = dir.getParent();
        }
        return null;
    }

    record ModuleRoot(Path root, String namespace) {}
}
