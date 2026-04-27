package spn.stdlib.math;

/**
 * Mathematical constants available to SPN programs.
 *
 * These are exposed as named values in the stdlib:
 *   pi  = 3.14159265358979...
 *   e   = 2.71828182845904...
 *   tau = 6.28318530717958...  (2 * pi)
 */
public final class SpnMathConstants {

    public static final double PI = Math.PI;
    public static final double E = Math.E;
    public static final double TAU = 2.0 * Math.PI;

    private SpnMathConstants() {}
}
