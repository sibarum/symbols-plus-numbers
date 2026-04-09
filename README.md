# Symbols+Numbers (SPN)

A programming language where numeric types carry their own algebraic rules, pattern matching is exhaustive by design, and a built-in OpenGL graphics engine lets you visualize results without leaving the language.

SPN runs on GraalVM with the Truffle framework for JIT compilation to native machine code.

## Quick Look

```
-- Define a numeric type with validation
type Rational(int, int) where (_, denominator) { denominator != 0 }

-- Promotion rule: int can be lifted to Rational
promote int -> Rational = (i) { Rational(i, 1) }

-- Externalized arithmetic as pure functions
pure *(Rational, Rational) -> Rational = ((n1, d1), (n2, d2)) {
  Rational(n1*n2, d1*d2)
}
pure +(Rational, Rational) -> Rational = ((n1, d1), (n2, d2)) {
  Rational(n1*d2 + d1*n2, d1*d2)
}

-- Define a data type with variants
data Shape
  | Circle(radius)
  | Rectangle(width, height)
  | Triangle(a, b, c)

-- Pure functions with exhaustive pattern matching
pure area(Shape) -> float = (shape) {
  match shape
    | Circle(r)          -> 3.14159 * r * r
    | Rectangle(w, h)    -> w * h
    | Triangle(a, b, c)  -> heron(a, b, c)
}
```

## Features

### Custom Numeric Types

Types carry closure-based validation. Arithmetic is externalized into pure functions with automatic promotion dispatch.

```
-- Constrained scalars
type Natural(int) where (n) { n >= 0 && n % 1 == 0 }

-- Distinguished elements
type ExtendedNatural(int) where (n) { n >= 0 && n % 1 == 0 } with Omega

-- Named-field product types
type Complex(real: float, imag: float)
type Vec3(x: float, y: float, z: float)

-- Positional product types
type Rational(int, int) where (_, d) { d != 0 }

-- Externalized operations
pure +(Complex, Complex) -> Complex = ((r1, i1), (r2, i2)) {
  Complex(r1 + r2, i1 + i2)
}

-- Type promotions for dispatch
promote Rational -> Complex = (r) { Complex(r, Rational(0, 1)) }

-- Constrained symbol types
type Direction = Symbol
type Color = Symbol
```

### Pure Functions & Pattern Matching

Pure functions are typed, total, and side-effect-free. The compiler checks exhaustiveness across all match branches.

```
pure opposite(Direction) -> Direction = (dir) {
  match dir
    | :north -> :south
    | :south -> :north
    | :east  -> :west
    | :west  -> :east
}

-- String decomposition via prefix matching and regex capture
pure parseUrl(string) -> string = (url) {
  match url
    | "http://" ++ rest                -> "web: " ++ rest
    | "ftp://" ++ rest                 -> "file: " ++ rest
    | /(\w+):\/\/(.*)/(_,proto,path) -> proto ++ "://" ++ path
    | _                                -> "unknown"
}

-- Array head|tail destructuring
pure sum(Array) -> int = (arr) {
  match arr
    | []      -> 0
    | [h | t] -> h + sum(t)
}

-- Guards
pure classify(int) -> string = (x) {
  match x
    | v if {v < 0}  -> "negative"
    | v if {v > 0}  -> "positive"
    | _              -> "zero"
}
```

Pattern matching supports: struct destructuring, literals, type checks, string prefix/suffix/regex, array head/tail, set membership, dictionary key destructuring, wildcards, and guard expressions.

### Closures & Streaming

Lambdas and yield-based streaming provide pragmatic imperative control flow alongside the pure functional core.

```
-- Iterate over a stream
let sum = 0
while range(1, 10) do (n) {
  sum = sum + n
}
-- sum == 45

-- Producer functions use yield
pure range(int, int) = (start, end) {
  let i = start
  while {i < end} do {
    yield i
    i = i + 1
  }
}
```

### Built-in OpenGL Graphics

An integrated canvas API provides hardware-accelerated 2D drawing with SDF font rendering, all callable directly from SPN code.

```
import Canvas

canvas(800, 600)
clear(0.1, 0.1, 0.12)

fill(0.2, 0.4, 0.8)
rect(50.0, 50.0, 200.0, 150.0)

fill(0.9, 0.2, 0.2)
circle(400.0, 300.0, 80.0)

stroke(1.0, 1.0, 1.0)
strokeWeight(2.0)
line(400.0, 80.0, 300.0, 220.0)

show()
```

A built-in 2D plotting library handles axes, ticks, labels, and auto-scaling:

```
import Canvas
import Math
import spn.canvas.plot

pure sinFn(_) = (x) { sin(x) }

canvas(800, 600)
clear(0.08, 0.08, 0.1)
plotFnAuto(sinFn, 800.0, 600.0, -6.3, 6.3, "sin(x)")
show()
```

### Built-in Mini-IDE

An OpenGL-based editor with syntax highlighting, code suggestions, parameter hints, undo/redo, and multi-window support. No external editor required.

## Standard Library

60 built-in functions across 7 modules:

| Module | Functions |
|--------|-----------|
| **Math** | `sin`, `cos`, `tan`, `asin`, `acos`, `atan`, `ceil`, `floor`, `round`, `abs`, `sign`, `min`, `max`, `clamp`, `pow`, `sqrt`, `heron` |
| **Array** | `map`, `filter`, `fold`, `flatten`, `zip`, `reverse`, `take`, `drop`, `find`, `all`, `any`, `sort`, `enumerate` |
| **Dict** | `hasKey`, `keys`, `values`, `entries`, `merge`, `remove`, `mapValues` |
| **Set** | `difference`, `intersection`, `isSubset`, `fromArray`, `toArray`, `size` |
| **String** | `toUpper`, `toLower`, `trim`, `replace`, `substring`, `split`, `startsWith`, `show`, `join`, `formatNum` |
| **Range** | `rangeStep`, `repeat`, `iterate` |
| **Option** | `mapOption`, `flatMap`, `unwrap`, `unwrapOr` |

Canvas functions (`canvas`, `show`, `clear`, `fill`, `stroke`, `strokeWeight`, `rect`, `circle`, `line`, `text`, `animate`) are provided by the spn-canvas module.

## Architecture

```
spn-parent/
  spn-ast/        Core AST nodes, type system, runtime
  spn-parse/      Hand-written recursive descent parser & lexer
  spn-stdlib/     Standard library with post-compile code generation
  spn-canvas/     OpenGL 2D drawing API + plotting library
  spn-fonts/      SDF font rendering via STB/LWJGL
  spn-stdui/      Platform-neutral UI framework (modes, widgets, input)
  spn-gui/        OpenGL mini-IDE (GLFW/LWJGL), bridges to spn-stdui
  spn-pkg/        Package management & import resolution
  spn-intellij-plugin/  IntelliJ IDE support
```

### Type System

SPN uses nominal typing throughout. The type hierarchy includes:

- **Primitives** -- int (64-bit long), float (64-bit double), string, bool
- **Constrained types** -- scalars with runtime-checked invariants
- **Product types** -- multi-component types with externalized operations
- **Structs** -- named record types
- **Variants (ADTs)** -- tagged unions with exhaustiveness checking
- **Tuples** -- anonymous structurally-typed products
- **Collections** -- Array, Set, Dictionary (unified `[]` syntax)
- **Function types** -- typed callable references

### Design Principles

- **Every body is a lambda.** `(params) { body }` or `{expr}` for no-arg lambdas. `=` binds a lambda to a name. `do` executes it.
- **Signatures carry types, lambdas carry names.** `pure add(int, int) -> int = (a, b) { a + b }`
- **Pure and pragmatic coexist.** Pure functions for logic, closures for control flow and mutation.
- **Types are smart, structs are dumb.** Numeric types carry algebraic rules. Structs are plain containers whose fields may reference smart types.
- **Arithmetic is externalized.** Operations are defined as `pure` functions outside the type, with `promote` rules for automatic dispatch across the type hierarchy.

## Building

Requires Java 25+ and Maven 3.9+.

```bash
mvn process-classes
```

This compiles the source, runs the Truffle DSL annotation processor, then executes post-compile code generation for the standard library registry.

## Future Directions

- Complete decoupling of implementation details (like monads, but simpler and more expressive)
- Highly optimized 3D scenegraph runtime with unified geometry and physics simulation
- Vector math and Cayley transforms for full 3D with zero transcendental functions
- Native image compilation via GraalVM
