package spn.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MainTest {

    @Test void runsSimpleScript(@TempDir Path tmp) throws IOException {
        Path script = tmp.resolve("hello.spn");
        Files.writeString(script, "1 + 2\n");

        Result r = runCli(script.toString());
        assertEquals(0, r.exit);
        assertTrue(r.stdout.contains("3"), "expected result 3, got: " + r.stdout);
    }

    @Test void quietSuppressesResult(@TempDir Path tmp) throws IOException {
        Path script = tmp.resolve("quiet.spn");
        Files.writeString(script, "1 + 2\n");

        Result r = runCli("--quiet", script.toString());
        assertEquals(0, r.exit);
        assertEquals("", r.stdout.trim());
    }

    @Test void usageOnMissingArg() {
        Result r = runCli();
        assertEquals(2, r.exit);
        assertTrue(r.stderr.contains("Usage"));
    }

    @Test void missingFileReturns1(@TempDir Path tmp) {
        Path ghost = tmp.resolve("nope.spn");
        Result r = runCli(ghost.toString());
        assertEquals(1, r.exit);
    }

    @Test void parseErrorReportedWithLocation(@TempDir Path tmp) throws IOException {
        Path script = tmp.resolve("broken.spn");
        Files.writeString(script, "let =\n");

        Result r = runCli(script.toString());
        assertNotEquals(0, r.exit);
        assertTrue(r.stderr.contains("broken.spn"),
                "expected file path in error: " + r.stderr);
    }

    @Test void stringTemplateRunsFromCli(@TempDir Path tmp) throws IOException {
        Path script = tmp.resolve("tmpl.spn");
        Files.writeString(script, """
            let x = 7
            "got ${x}"
            """);

        Result r = runCli(script.toString());
        assertEquals(0, r.exit);
        assertTrue(r.stdout.contains("got 7"), r.stdout);
    }

    record Result(int exit, String stdout, String stderr) {}

    private Result runCli(String... args) {
        PrintStream origOut = System.out;
        PrintStream origErr = System.err;
        ByteArrayOutputStream bout = new ByteArrayOutputStream();
        ByteArrayOutputStream berr = new ByteArrayOutputStream();
        System.setOut(new PrintStream(bout));
        System.setErr(new PrintStream(berr));
        try {
            int exit = new Main().run(args);
            return new Result(exit, bout.toString(), berr.toString());
        } finally {
            System.setOut(origOut);
            System.setErr(origErr);
        }
    }
}
