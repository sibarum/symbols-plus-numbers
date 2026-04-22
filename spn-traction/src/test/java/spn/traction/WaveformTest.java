package spn.traction;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WaveformTest extends TractionTestBase {

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
