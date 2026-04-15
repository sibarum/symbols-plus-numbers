package spn.traction;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import spn.lang.ClasspathModuleLoader;
import spn.lang.FilesystemModuleLoader;
import spn.lang.SpnParser;
import spn.language.SpnModuleRegistry;
import spn.node.SpnRootNode;
import spn.type.SpnSymbolTable;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class WaveformTest {

    private SpnSymbolTable symbolTable;
    private SpnModuleRegistry registry;

    @BeforeEach
    void setUp() {
        symbolTable = new SpnSymbolTable();
        registry = new SpnModuleRegistry();
        spn.stdlib.gen.StdlibModuleLoader.registerAll(registry);
        registry.addLoader(new ClasspathModuleLoader(null, symbolTable));
        Path root = Path.of(System.getProperty("user.dir"));
        if (!Files.isRegularFile(root.resolve("module.spn")))
            root = root.resolve("spn-traction");
        registry.addLoader(new FilesystemModuleLoader(
                root, "sibarum.spn.traction", null, symbolTable));
    }

    private Object run(String source) {
        SpnParser parser = new SpnParser(source, null, null, symbolTable, registry);
        SpnRootNode root = parser.parse();
        return root.getCallTarget().call();
    }

    @Nested
    class Generators {
        @Test void dcLength() {
            assertEquals(4L, run("""
                import signal.waveform
                import Array (length)
                length(dc(4, Rational(1,1)))
                """));
        }

        @Test void dcSample() {
            assertEquals(true, run("""
                import signal.waveform
                let sig = dc(5, Rational(3,4))
                sig[0] == Rational(3,4) && sig[4] == Rational(3,4)
                """));
        }

        @Test void rampLength() {
            assertEquals(8L, run("""
                import signal.waveform
                import Array (length)
                length(ramp(8))
                """));
        }

        @Test void rampValues() {
            // ramp(4) = [0/4, 1/4, 2/4, 3/4]
            assertEquals(true, run("""
                import signal.waveform
                let r = ramp(4)
                r[0] == Rational(0,4) && r[3] == Rational(3,4)
                """));
        }

        @Test void squareHighSamples() {
            // square(8, 4, Rational(3,1)): first two samples are positive amp
            assertEquals(true, run("""
                import signal.waveform
                let sig = square(8, 4, Rational(3,1))
                sig[0] == Rational(3,1) && sig[1] == Rational(3,1)
                """));
        }

        @Test void squarePeriodic() {
            // period repeats: sample 0 == sample 4
            assertEquals(true, run("""
                import signal.waveform
                let sig = square(12, 4, Rational(5,3))
                sig[0] == sig[4] && sig[0] == sig[8]
                """));
        }

        @Test void stepLength() {
            assertEquals(6L, run("""
                import signal.waveform
                import Array (length)
                length(step(6, 3, Rational(2,1)))
                """));
        }
    }
}
