package spn.clifford;

import java.util.Locale;
import java.util.Objects;

/**
 * Clifford-algebra rotor from Traction Theory, parameterized by two angles
 * (thetaW, thetaU).
 *
 * <p>Scope: this class represents only rotors of the canonical form
 * {@code R = R_w(thetaW) · R_u(thetaU)}. Ring multiplication, algebraic
 * inversion, and conjugation are NOT supported because their results leave
 * the 4-component {1, t, w, tw} subspace. The 4-vector arithmetic methods
 * (negate / add / scale / dot / norm) operate on tower components as a plain
 * 4-vector and produce results that are generally not canonical rotors —
 * they lose their angle parameters.
 */
public final class TractionRotor {

    private static final double DISPLAY_EPS = 1e-10;

    private final double a;
    private final double b;
    private final double c;
    private final double d;
    private final Double thetaW;
    private final Double thetaU;

    private TractionRotor(double a, double b, double c, double d, Double thetaW, Double thetaU) {
        this.a = a;
        this.b = b;
        this.c = c;
        this.d = d;
        this.thetaW = thetaW;
        this.thetaU = thetaU;
    }

    public static TractionRotor fromAngles(double thetaW, double thetaU) {
        double alpha = Math.cos(thetaW / 2.0);
        double beta  = Math.sin(thetaW / 2.0);
        double gamma = Math.cos(thetaU / 2.0);
        double delta = Math.sin(thetaU / 2.0);
        return new TractionRotor(
                alpha * gamma,
                alpha * delta,
                beta  * gamma,
                beta  * delta,
                thetaW,
                thetaU);
    }

    /** Raw tower components. The result is generally not a canonical rotor;
     *  sphere projection and angle-space ops are unavailable. */
    public static TractionRotor fromTower(double a, double b, double c, double d) {
        return new TractionRotor(a, b, c, d, null, null);
    }

    public static TractionRotor identity() {
        return fromAngles(0.0, 0.0);
    }

    public double a() { return a; }
    public double b() { return b; }
    public double c() { return c; }
    public double d() { return d; }

    public boolean hasAngles() { return thetaW != null && thetaU != null; }
    public double thetaW() { requireAngles("thetaW()"); return thetaW; }
    public double thetaU() { requireAngles("thetaU()"); return thetaU; }

    public double scalarCoeff()     { return a + d; }
    public double quarterCoeff()    { return b + c; }
    public double negQuarterCoeff() { return b; }
    public double halfCoeff()       { return d; }

    // ── 4-vector arithmetic on tower components ───────────────────────
    // Linear ops on the 4-component vector. Results are generally NOT of the
    // canonical R_w·R_u form and lose their angle parameters.

    public TractionRotor negate()                  { return fromTower(-a, -b, -c, -d); }
    public TractionRotor scale(double k)           { return fromTower(k*a, k*b, k*c, k*d); }
    public TractionRotor add(TractionRotor o)      { return fromTower(a+o.a, b+o.b, c+o.c, d+o.d); }
    public TractionRotor subtract(TractionRotor o) { return fromTower(a-o.a, b-o.b, c-o.c, d-o.d); }

    public double dot(TractionRotor o) { return a*o.a + b*o.b + c*o.c + d*o.d; }
    public double normSquared()        { return dot(this); }
    public double norm()               { return Math.sqrt(normSquared()); }

    public TractionRotor normalize() {
        double n = norm();
        if (n < 1e-15) {
            throw new ArithmeticException("Cannot normalize a zero-norm rotor.");
        }
        return scale(1.0 / n);
    }

    public boolean approxEquals(TractionRotor o, double eps) {
        return Math.abs(a - o.a) <= eps
            && Math.abs(b - o.b) <= eps
            && Math.abs(c - o.c) <= eps
            && Math.abs(d - o.d) <= eps;
    }

    // ── Parameter-space interpolation ─────────────────────────────────

    /** Linear interp on the angle parameters, shortest arc on each.
     *  Not a great-circle geodesic on the sphere — a smooth path through
     *  the (thetaW, thetaU) parameterization. t=0 -> this, t=1 -> other. */
    public TractionRotor interpAngles(TractionRotor other, double t) {
        requireAngles("interpAngles()");
        other.requireAngles("interpAngles() argument");
        double dW = shortestArc(other.thetaW - this.thetaW);
        double dU = shortestArc(other.thetaU - this.thetaU);
        return fromAngles(this.thetaW + t * dW, this.thetaU + t * dU);
    }

    // ── 3D action ─────────────────────────────────────────────────────

    /** Apply the SO(3) rotation R_z(thetaW) ∘ R_x(thetaU) to a 3-vector.
     *  This is the parameterization-induced rotation chosen so that
     *  apply(+x) reproduces toSpherePoint(). It is NOT a Clifford sandwich
     *  product on the algebra (which would need the full polynomial-in-s
     *  representation). Two TractionRotors composed as 3D rotations do not
     *  generally produce another canonical TractionRotor. */
    public double[] apply(double[] v) {
        if (v.length != 3) {
            throw new IllegalArgumentException("apply expects a 3-vector, got length " + v.length);
        }
        requireAngles("apply()");
        double cw = Math.cos(thetaW);
        double sw = Math.sin(thetaW);
        double cu = Math.cos(thetaU);
        double su = Math.sin(thetaU);
        double x1 = cw * v[0] - sw * v[1];
        double y1 = sw * v[0] + cw * v[1];
        double z1 = v[2];
        return new double[] {
                x1,
                cu * y1 - su * z1,
                su * y1 + cu * z1,
        };
    }

    /** Standard spherical coords with +x as the pole; equals apply({1,0,0}):
     *    x = cos(thetaW)
     *    y = sin(thetaW) * cos(thetaU)
     *    z = sin(thetaW) * sin(thetaU) */
    public double[] toSpherePoint() {
        return apply(new double[] { 1.0, 0.0, 0.0 });
    }

    // ── Display ───────────────────────────────────────────────────────

    public String toTractionExpression() {
        StringBuilder sb = new StringBuilder();
        appendTerm(sb, scalarCoeff(),     null);
        appendTerm(sb, quarterCoeff(),    "0^(1/4)");
        appendTerm(sb, negQuarterCoeff(), "0^(-1/4)");
        appendTerm(sb, halfCoeff(),       "0^(1/2)");
        return sb.length() == 0 ? "0" : sb.toString();
    }

    private static void appendTerm(StringBuilder sb, double coeff, String basis) {
        if (Math.abs(coeff) < DISPLAY_EPS) return;
        boolean leading = sb.length() == 0;
        double mag = Math.abs(coeff);
        String sign = coeff < 0 ? "-" : (leading ? "" : "+");
        if (!leading) sb.append(' ');
        sb.append(sign);
        sb.append(String.format(Locale.ROOT, "%.4f", mag));
        if (basis != null) sb.append('·').append(basis);
    }

    @Override
    public String toString() {
        String tower = String.format(Locale.ROOT,
                "tower[a=%.4f, b=%.4f, c=%.4f, d=%.4f]", a, b, c, d);
        return "TractionRotor{" + tower + ", traction=" + toTractionExpression() + "}";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof TractionRotor r)) return false;
        return Double.compare(a, r.a) == 0
            && Double.compare(b, r.b) == 0
            && Double.compare(c, r.c) == 0
            && Double.compare(d, r.d) == 0;
    }

    @Override
    public int hashCode() {
        return Objects.hash(a, b, c, d);
    }

    private void requireAngles(String name) {
        if (!hasAngles()) {
            throw new IllegalStateException(
                    name + " requires a rotor built via fromAngles().");
        }
    }

    private static double shortestArc(double delta) {
        double twoPi = 2.0 * Math.PI;
        double m = ((delta + Math.PI) % twoPi + twoPi) % twoPi - Math.PI;
        return m;
    }

    // ── Reference-data verification ───────────────────────────────────

    public static void main(String[] args) {
        double[][] inputs = {
                { Math.PI / 4.0, 3.0 * Math.PI / 2.0 },
                { Math.PI / 2.0, 7.0 * Math.PI / 4.0 },
                { Math.PI / 2.0, 0.0 },
                { 0.0,           Math.PI / 2.0 },
                { 0.0,           Math.PI },
                { Math.PI,       0.0 },
        };
        double[][] expectedTower = {
                { -0.6533, +0.6533, -0.2706, +0.2706 },
                { -0.6533, +0.2706, -0.6533, +0.2706 },
                { +0.7071,  0.0000, +0.7071,  0.0000 },
                { +0.7071, +0.7071,  0.0000,  0.0000 },
                {  0.0000, +1.0000,  0.0000,  0.0000 },
                {  0.0000,  0.0000, +1.0000,  0.0000 },
        };
        double[][] expectedSphere = {
                { +0.7071, +0.0000, -0.7071 },
                { +0.0000, +0.7071, -0.7071 },
                { +0.0000, +1.0000, +0.0000 },
                { +1.0000, +0.0000, +0.0000 },
                { +1.0000, +0.0000, +0.0000 },
                { -1.0000, +0.0000, +0.0000 },
        };
        double tol = 1e-3;
        boolean allOk = true;

        for (int i = 0; i < inputs.length; i++) {
            TractionRotor r = TractionRotor.fromAngles(inputs[i][0], inputs[i][1]);
            double[] s = r.toSpherePoint();
            double[] et = expectedTower[i];
            double[] es = expectedSphere[i];

            boolean towerOk =
                    near(r.a(), et[0], tol) && near(r.b(), et[1], tol)
                    && near(r.c(), et[2], tol) && near(r.d(), et[3], tol);
            boolean sphereOk =
                    near(s[0], es[0], tol) && near(s[1], es[1], tol) && near(s[2], es[2], tol);

            System.out.printf(Locale.ROOT,
                    "Example %d: thetaW=%.4f, thetaU=%.4f%n", i + 1, inputs[i][0], inputs[i][1]);
            System.out.printf(Locale.ROOT,
                    "  tower    = [%.4f, %.4f, %.4f, %.4f]   %s%n",
                    r.a(), r.b(), r.c(), r.d(), towerOk ? "OK" : "MISMATCH");
            System.out.println("  traction = " + r.toTractionExpression());
            System.out.printf(Locale.ROOT,
                    "  sphere   = (%.4f, %.4f, %.4f)         %s%n",
                    s[0], s[1], s[2], sphereOk ? "OK" : "MISMATCH");

            allOk &= towerOk && sphereOk;
        }
        System.out.println(allOk ? "ALL OK" : "FAILURES PRESENT");
    }

    private static boolean near(double actual, double expected, double tol) {
        return Math.abs(actual - expected) <= tol;
    }
}
