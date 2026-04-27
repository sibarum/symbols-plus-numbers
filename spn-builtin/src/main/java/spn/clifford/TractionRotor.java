package spn.clifford;

import java.util.Locale;
import java.util.Objects;

/**
 * Elliptic rotor in the even subalgebra of Cl(3,0) — equivalent to a unit
 * quaternion / classical 3D rotor. Tower components (a, b, c, d) coordinatize
 * the four-dimensional even subalgebra; on the unit 3-sphere
 * {@code a² + b² + c² + d² = 1} they parameterize SO(3) (double cover).
 *
 * <p>This is the elliptic case of a planned family of Traction-Theory rotors
 * (parabolic, hyperbolic, projective). Composition ({@link #multiply}),
 * inversion ({@link #inverse}, {@link #reverse}), great-circle interpolation
 * ({@link #slerp}), and the sandwich action on 3-vectors ({@link #apply})
 * are all closed in this algebra.
 *
 * <p>The 4-vector arithmetic methods (negate / add / subtract / scale / dot /
 * norm) operate on tower components linearly. They generally produce
 * non-unit rotors and are intended for differentiation and parameter-update
 * code paths.
 */
@Deprecated(since = "Kept only for historical documentation purposes", forRemoval = true)
public final class TractionRotor {

    private static final double DISPLAY_EPS = 1e-10;
    private static final double UNIT_EPS = 1e-9;

    private final double a;
    private final double b;
    private final double c;
    private final double d;

    private TractionRotor(double a, double b, double c, double d) {
        this.a = a;
        this.b = b;
        this.c = c;
        this.d = d;
    }

    /** Convenience factory: builds the rotor for R_x(thetaU) ∘ R_z(thetaW)
     *  acting on 3-vectors (so {@code fromAngles(W, U).apply({1,0,0})} gives
     *  the standard sphere point). The result is just a generic unit rotor;
     *  the angles are NOT recoverable after composition. */
    public static TractionRotor fromAngles(double thetaW, double thetaU) {
        double alpha = Math.cos(thetaW / 2.0);
        double beta  = Math.sin(thetaW / 2.0);
        double gamma = Math.cos(thetaU / 2.0);
        double delta = Math.sin(thetaU / 2.0);
        return new TractionRotor(
                alpha * gamma,
                alpha * delta,
                beta  * gamma,
                beta  * delta);
    }

    /** Raw tower components. No unit-norm check; suitable for differentiation
     *  or arithmetic paths. Use {@link #normalize()} before applying or
     *  slerping if the result must act as a rotation. */
    public static TractionRotor fromTower(double a, double b, double c, double d) {
        return new TractionRotor(a, b, c, d);
    }

    public static TractionRotor identity() {
        return new TractionRotor(1.0, 0.0, 0.0, 0.0);
    }

    public double a() { return a; }
    public double b() { return b; }
    public double c() { return c; }
    public double d() { return d; }

    public double scalarCoeff()     { return a + d; }
    public double quarterCoeff()    { return b + c; }
    public double negQuarterCoeff() { return b; }
    public double halfCoeff()       { return d; }

    // ── 4-vector arithmetic on tower components ───────────────────────
    // Linear ops on the underlying 4-component vector. Results are generally
    // not unit rotors.

    public TractionRotor negate()                  { return new TractionRotor(-a, -b, -c, -d); }
    public TractionRotor scale(double k)           { return new TractionRotor(k*a, k*b, k*c, k*d); }
    public TractionRotor add(TractionRotor o)      { return new TractionRotor(a+o.a, b+o.b, c+o.c, d+o.d); }
    public TractionRotor subtract(TractionRotor o) { return new TractionRotor(a-o.a, b-o.b, c-o.c, d-o.d); }

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

    // ── Rotor algebra ─────────────────────────────────────────────────

    /** Clifford / quaternion product. Closed in the 4D even subalgebra:
     *  composing two rotors yields another rotor with no escape. The 3D
     *  rotation induced by {@code r.multiply(s)} is the rotation of {@code s}
     *  followed by the rotation of {@code r}. */
    public TractionRotor multiply(TractionRotor o) {
        // Derived from quaternion product under the mapping
        // (a,b,c,d) ↔ (s,x,y,z) = (a, b, -d, c).
        double na = a*o.a - b*o.b - c*o.c - d*o.d;
        double nb = a*o.b + b*o.a + c*o.d - d*o.c;
        double nc = a*o.c - b*o.d + c*o.a + d*o.b;
        double nd = a*o.d + b*o.c - c*o.b + d*o.a;
        return new TractionRotor(na, nb, nc, nd);
    }

    /** Reverse (Clifford {@code R~}): flip the sign of the bivector grades.
     *  For a unit rotor this equals {@link #inverse()}; for a non-unit rotor
     *  it does NOT. */
    public TractionRotor reverse() {
        return new TractionRotor(a, -b, -c, -d);
    }

    /** Group inverse: {@code R · R.inverse() == identity} for any non-zero
     *  rotor. Equals {@link #reverse()} divided by {@link #normSquared()}. */
    public TractionRotor inverse() {
        double n2 = normSquared();
        if (n2 < 1e-30) {
            throw new ArithmeticException("Cannot invert a zero-norm rotor.");
        }
        double k = 1.0 / n2;
        return new TractionRotor(a*k, -b*k, -c*k, -d*k);
    }

    /** Spherical linear interpolation on the unit 3-sphere. Both endpoints
     *  are normalized defensively; the shorter arc is taken automatically.
     *  {@code t=0} returns this, {@code t=1} returns other. */
    public TractionRotor slerp(TractionRotor other, double t) {
        TractionRotor p = this.normalize();
        TractionRotor q = other.normalize();
        double cosOmega = p.dot(q);
        if (cosOmega < 0.0) {
            q = q.negate();
            cosOmega = -cosOmega;
        }
        // For nearly-parallel rotors, fall back to lerp+normalize to avoid
        // dividing by sin(Ω) near zero.
        if (cosOmega > 1.0 - 1e-9) {
            double na = p.a + t * (q.a - p.a);
            double nb = p.b + t * (q.b - p.b);
            double nc = p.c + t * (q.c - p.c);
            double nd = p.d + t * (q.d - p.d);
            return new TractionRotor(na, nb, nc, nd).normalize();
        }
        double omega = Math.acos(cosOmega);
        double sinOmega = Math.sin(omega);
        double w0 = Math.sin((1.0 - t) * omega) / sinOmega;
        double w1 = Math.sin(t * omega) / sinOmega;
        return new TractionRotor(
                w0 * p.a + w1 * q.a,
                w0 * p.b + w1 * q.b,
                w0 * p.c + w1 * q.c,
                w0 * p.d + w1 * q.d);
    }

    // ── 3D action ─────────────────────────────────────────────────────

    /** Apply the rotor to a 3-vector via the sandwich product
     *  {@code R · v · R~}. Requires a unit rotor; non-unit rotors should be
     *  normalized first. */
    public double[] apply(double[] v) {
        if (v.length != 3) {
            throw new IllegalArgumentException("apply expects a 3-vector, got length " + v.length);
        }
        double n2 = normSquared();
        if (Math.abs(n2 - 1.0) > UNIT_EPS) {
            throw new IllegalStateException(
                    "apply() requires a unit rotor; |R|² = " + n2 + ". Call normalize() first.");
        }
        // Direct expansion of (a + b·e₂₃ + c·e₁₂ + d·...) · v · reverse, with
        // bivector basis chosen so that fromAngles(W, U).apply({1,0,0}) yields
        // (cos W, sin W cos U, sin W sin U). Equivalent to the standard
        // quaternion sandwich under (a,b,c,d) ↔ (s,x,y,z) = (a, b, -d, c):
        //   v' = (s² - x² - y² - z²) v + 2(qv · v) qv + 2 s (qv × v)
        // with qv = (b, -d, c).
        double vx = v[0], vy = v[1], vz = v[2];
        double scale = 2.0 * a * a - 1.0;
        double dot = b * vx - d * vy + c * vz;
        // qv × v = (-d·vz - c·vy, c·vx - b·vz, b·vy + d·vx)
        double crossX = -d * vz - c * vy;
        double crossY =  c * vx - b * vz;
        double crossZ =  b * vy + d * vx;
        return new double[] {
                scale * vx + 2.0 * dot * b   + 2.0 * a * crossX,
                scale * vy + 2.0 * dot * (-d) + 2.0 * a * crossY,
                scale * vz + 2.0 * dot * c   + 2.0 * a * crossZ,
        };
    }

    /** Convenience: {@code apply({1, 0, 0})}. */
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
