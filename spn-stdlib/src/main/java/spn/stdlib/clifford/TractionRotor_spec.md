# TractionRotor — Implementation Specification

## Purpose

A Java class implementing a Clifford-algebra rotor from Traction Theory. The
rotor is parameterized by two angles (theta_w, theta_u) and projects to a point
on the unit 2-sphere via a sandwich-style action on a base vector.

## Algebraic background (just enough for the implementer)

The rotor lives in a 4-dimensional algebra. There are two equivalent bases:

**Traction expression basis:** {1, 0^(1/4), 0^(-1/4), 0^(1/2)}
**Tower basis:** {1, t, w, tw}, where w = 0^(1/4) and t = w + w^(-1)

The two bases are related by:
  - t  = 0^(1/4) + 0^(-1/4)
  - w  = 0^(1/4)
  - tw = 0^(1/2) + 1

So a tower element a + b*t + c*w + d*tw expands in the Traction basis as:
  - coefficient on 1         = a + d
  - coefficient on 0^(1/4)   = b + c
  - coefficient on 0^(-1/4)  = b
  - coefficient on 0^(1/2)   = d

The implementer does not need to do symbolic algebra. The two bases are only
used for *display* and *interpretation*; all numerics are done in the tower
basis.

## Construction formula

Given two angles theta_w and theta_u (in radians), the rotor decomposes as a
commuting product of two Clifford rotors:

  R_w(theta_w) = cos(theta_w/2) + sin(theta_w/2) * w
  R_u(theta_u) = cos(theta_u/2) + sin(theta_u/2) * t
  R            = R_w * R_u

Multiplying out and collecting in the tower basis {1, t, w, tw} gives the
four components directly:

  alpha = cos(theta_w / 2)
  beta  = sin(theta_w / 2)
  gamma = cos(theta_u / 2)
  delta = sin(theta_u / 2)

  a = alpha * gamma     (coefficient on 1)
  b = alpha * delta     (coefficient on t)
  c = beta  * gamma     (coefficient on w)
  d = beta  * delta     (coefficient on tw)

This is the canonical constructor.

## Geometric action — projection to the unit 2-sphere

The rotor acts on the base vector +x = (1, 0, 0). Empirically (verified against
three reference inputs from the user's diagnostic tool), the resulting point on
the unit 2-sphere is produced by two successive plane rotations:

  Step 1: rotate +x in the xy-plane by theta_w
            (x, y, z) = (cos(theta_w), sin(theta_w), 0)

  Step 2: rotate the result in the yz-plane by phi = (theta_u - 2*pi)
            y' = cos(phi)*y - sin(phi)*z
            z' = sin(phi)*y + cos(phi)*z

The final sphere point is (x, y', z'). The phi = theta_u - 2*pi shift is a
sign/orientation convention from the source diagnostic; phi is mathematically
equivalent to theta_u mod 2*pi but the explicit shift matches the reference
data exactly.

## Reference data for testing

These three (theta_w, theta_u) inputs and their expected outputs come from the
user's diagnostic tool and should pass to within 1e-3 tolerance:

| Example | theta_w | theta_u  | a       | b       | c       | d       | x      | y      | z       |
|---------|---------|----------|---------|---------|---------|---------|--------|--------|---------|
| 1       | pi/4    | 3*pi/2   | -0.6533 | +0.6533 | -0.2706 | +0.2706 | +0.7071| +0.0000| -0.7071 |
| 2       | pi/2    | 7*pi/4   | -0.6533 | +0.2706 | -0.6533 | +0.2706 | +0.0000| +0.7071| -0.7071 |
| 3       | pi/2    | 0        | +0.7071 | 0.0000  | +0.7071 | 0.0000  | +0.0000| +1.0000| +0.0000 |

Their Traction expressions:

| Example | Expression                                                                |
|---------|---------------------------------------------------------------------------|
| 1       | -0.3827 + 0.3827*0^(1/4) + 0.6533*0^(-1/4) + 0.2706*0^(1/2)               |
| 2       | -0.3827 - 0.3827*0^(1/4) + 0.2706*0^(-1/4) + 0.2706*0^(1/2)               |
| 3       |  0.7071 + 0.7071*0^(1/4)                                                  |

## API

```java
public final class TractionRotor {

    // --- Construction ---

    /** Build a rotor from the two Traction angles (radians). */
    public static TractionRotor fromAngles(double thetaW, double thetaU);

    /** Build a rotor directly from raw tower components. Sphere projection
     *  is not available on rotors built this way. */
    public static TractionRotor fromTower(double a, double b, double c, double d);

    // --- Tower component accessors ---

    public double a();   // coefficient on 1
    public double b();   // coefficient on t
    public double c();   // coefficient on w
    public double d();   // coefficient on tw

    // --- Traction-basis coefficients ---

    public double scalarCoeff();      // a + d   (coefficient on 1)
    public double quarterCoeff();     // b + c   (coefficient on 0^(1/4))
    public double negQuarterCoeff();  // b       (coefficient on 0^(-1/4))
    public double halfCoeff();        // d       (coefficient on 0^(1/2))

    // --- Geometric action ---

    /** Sphere projection. Throws IllegalStateException if the rotor was
     *  constructed via fromTower(). */
    public double[] toSpherePoint();   // returns {x, y, z}

    // --- Display ---

    /** Render in the Traction basis, e.g.
     *  "-0.3827 +0.3827·0^(1/4) +0.6533·0^(-1/4) +0.2706·0^(1/2)".
     *  Suppresses terms with |coefficient| < 1e-10. */
    public String toTractionExpression();

    /** Debug-friendly: shows tower components and the Traction expression. */
    @Override public String toString();
}
```

## Implementation notes

1. The class should be immutable. Store a, b, c, d as final doubles. Store
   thetaW and thetaU as nullable Double objects so toSpherePoint() can detect
   when it cannot be computed (rotor built via fromTower()).

2. toTractionExpression() formatting: use "%.4f" for magnitudes, prepend "-"
   for negative coefficients and "+" for positive non-leading coefficients,
   skip terms whose magnitude is below 1e-10, and use "·" (middle dot) between
   coefficient and basis element. Return "0" if all terms are suppressed.

3. Include a main() method that runs the three reference examples above,
   prints the tower components, the Traction expression, and the sphere point
   for each, and reports OK / MISMATCH against the expected values (tolerance
   1e-3).

4. No external dependencies. Pure java.lang + java.util only. Target Java 17+
   (records are optional; a normal final class with private final fields is
   fine).

## Possible extensions (not required for the initial implementation)

- Rotor composition: R3 = R1.compose(R2) by multiplying tower elements using
  the algebra w*w = t*w - 1.
- Rotor inverse: the conjugate has the same scalar part with sign-flipped
  generator parts, suitably adjusted by the t-trace relation.
- Logarithm: recover (theta_w, theta_u) from raw tower components by
  half-angle extraction.
- Acting on arbitrary 3D base vectors rather than only +x. The current
  projection bakes in the +x base; a general implementation would expose the
  base vector as a parameter.
