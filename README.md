```
    __________________________________________________________
   /___         _        _      _ _   _          _            \
  /   *\=======|*\======|*\====/*\ \=|*\========|*\============\
 /| [--/ _ _ __| |__ ___| |____| |_ \| |_ _ _ __| |__ ___ ____ _\
/ \   \|| \ ' '\ '_*\ _*\ | _[_  *_] \ ||| | '`*\ '_ \ _*\ *__/_\\
\/--] *|| | | |*||_) )_) )|__*\| | \ * |||*| | | ||_)*)__/ |\__*\/
 \____/_,*|_|_|_|.__/___/_|___/\_/_|\__|_._/_|_|_|.__/__\|_|/___/
  \====_/ |====================================================/
   \__(__/____________________________________________________/
```

# Symbols+Numbers (SPN)

A programming language where numeric types carry their own algebraic rules, pattern matching is exhaustive by design, and a built-in OpenGL graphics engine lets you visualize results without leaving the language.

## Table of Contents

- [Quick Look](#quick-look)
- [Features](#features)
  - [Custom Numeric Types](#custom-numeric-types)
  - [Union Types](#union-types)
  - [Macros](#macros)
  - [Unary Operator Dispatch](#unary-operator-dispatch)
  - [Pure Functions & Pattern Matching](#pure-functions--pattern-matching)
  - [Tuple Types & Destructuring](#tuple-types--destructuring)
  - [Closures & Streaming](#closures--streaming)
  - [Built-in OpenGL Graphics](#built-in-opengl-graphics)
  - [Built-in Mini-IDE](#built-in-mini-ide)
  - [Trace Mode (Debugger)](#trace-mode-debugger)
- [Standard Library](#standard-library)
- [Architecture](#architecture)
  - [Type System](#type-system)
  - [Parse-Time Type Inference](#parse-time-type-inference)
  - [Design Principles](#design-principles)
- [Getting Started](#getting-started)
  - [Prerequisites](#prerequisites)
  - [Building](#building)
  - [Running the IDE](#running-the-ide)
  - [Running tests](#running-tests)
- [Future Directions](#future-directions)

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

-- Unary inverse operators
pure -(Rational) -> Rational = ((n, d)) { Rational(-n, d) }
pure /(Rational) -> Rational = ((n, d)) { Rational(d, n) }

-- Derive ordering from a single comparator
import Ordering
pure compare(Rational, Rational) -> int = (a, b) { ... }
deriveOrderingFromInt(Rational, compare)

-- Anonymous union types
pure area(Circle | Rectangle) -> float = (shape) {
  match shape
    | Circle(r)         -> 3.14159 * r * r
    | Rectangle(w, h)   -> w * h
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

### Union Types

Types can be combined with `|` to form anonymous unions. Named unions via `data` declarations are also supported. The compiler enforces exhaustive pattern matching over all variants.

```
-- Anonymous union (inline)
pure describe(Circle | Rectangle | Triangle) -> string = (shape) { ... }

-- Named union
data Shape = Circle | Rectangle | Triangle
pure describe(Shape) -> string = (shape) { ... }

-- Union subtyping: Circle is assignable to Circle | Rectangle
let c = Circle(5.0)
area(c)  -- works: Circle is a member of Circle | Rectangle

-- Exhaustiveness checking at parse time
match shape
  | Circle(r) -> ...       -- error: missing Rectangle branch
```

### Macros

Compile-time code generation via textual template macros. Macros are functions that emit code at their call site.

```
-- Define a macro
macro deriveDouble(T) = {
  pure double(T) -> T = (x) { x + x }
}

-- Invoke it — emits the function for a specific type
deriveDouble(int)
double(5)  -- 10

-- Macros cross module boundaries via imports
import Ordering
deriveOrderingFromInt(Rational, compare)
-- now <, >, <=, >= work on Rational
```

Macros support multiple parameters, emit multiple declarations, and can be defined in stdlib SPN files that are importable without touching Java code.

### Unary Operator Dispatch

Unary operators dispatch through the same overload system as binary operators, **but an operator may be defined as unary OR binary on a given type — never both.** The alternative form lives as a method (`.neg()` for additive inverse, `.inv()` for multiplicative inverse), and `-x` falls back to `x.neg()` at parse time when no unary `-(T)` overload is registered.

```
-- Option A: type has no subtraction, so unary - defines additive inverse.
pure -(SmallBox) -> SmallBox = ((n)) { SmallBox(-n) }
-mySmallBox  -- calls the unary overload directly

-- Option B: type has binary subtraction, so unary negation is a method.
pure -(Rational, Rational) -> Rational = (q1, q2) { q1 + q2.neg() }
pure Rational.neg() -> Rational = () { Rational(-this.0, this.1) }
-myRational  -- parser falls back to myRational.neg()

-- Attempting both forms on the same type is a compile error:
--   Cannot define both unary and binary '-' for Rational — pick one;
--   use a .neg()/.inv() method for the other form.
```

### Pure Functions & Pattern Matching

Pure functions are typed, total, and side-effect-free. The compiler checks exhaustiveness across all match branches and validates pattern categories against the subject type at parse time.

```
pure opposite(Direction) -> Direction = (dir) {
  match dir
    | :north -> :south
    | :south -> :north
    | :east  -> :west
    | :west  -> :east
}

-- Nested pattern matching with struct destructuring
match (r1, r2)
  | (Rational(0, _), Rational(0, _)) -> true
  | _ -> r1.0 == r2.0 && r1.1 == r2.1

-- Block expressions with let bindings
match pair
  | _ -> {
      let x = compute(pair)
      let y = transform(x)
      Result(x, y)
    }
```

Pattern matching supports: struct destructuring (nominal), nested patterns, tuple positional matching, literals, type checks, string prefix/suffix/regex, array head/tail, set membership, dictionary key destructuring, wildcards, guard expressions, and boolean literal patterns.

**Parse-time categorical checking** rejects patterns that can't match the subject's type:
```
let r = Rational(3, 4)
match r
  | (n, d) -> ...           -- ERROR: tuple pattern can't match Rational
  | Rational(n, d) -> ...   -- OK: nominal destructuring
```

**Named field patterns** let you destructure by field name instead of position. Useful when field order would otherwise be implicit:
```
type TComplex(scale: Rational, tangent: Rational)
match z
  | TComplex(scale = s) -> s.isZero()          -- bind only `scale`; tangent is wildcard
  | TComplex(tangent = t, scale = s) -> ...    -- any order, any subset

-- Rejected at parse time:
--   TComplex(foo = x)        → unknown field 'foo' on TComplex
--   TComplex(scale = a, scale = b)  → duplicate field 'scale'
```
Positional and named forms cannot be mixed in the same pattern.

**Subject-less guard match** replaces the conventional `if/else` chain. There is no `if` keyword in SPN — conditional expressions are written as guard arms of a match:
```
let category = match
  | x < 0      -> "negative"
  | x < 10     -> "small"
  | x < 100    -> "medium"
  | _          -> "huge"
```
Each condition is evaluated in order; the first `true` guard wins. The final `| _ -> default` arm is required for totality (a parse error otherwise).

### Tuple Types & Destructuring

Tuples are first-class with type annotations, return types, and positional destructuring.

```
-- Tuple return type
pure swap(int, int) -> (int, int) = (a, b) { (b, a) }

-- Let-destructuring (works on tuples, arrays, structs, products)
let (x, y) = someFunction()
let (a, _, c) = myTuple       -- skip elements with _

-- Type inference through destructuring
let (re, im) = tc.toCartesian()   -- re and im get Rational type
re.neg()                            -- method dispatch works
```

### Closures & Streaming

Lambdas and yield-based streaming provide pragmatic imperative control flow alongside the pure functional core.

```
-- Iterate over a stream
let sum = 0
while range(1, 10) do (n) {
  sum = sum + n
}

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
import spn.canvas.draw
import spn.canvas.plot

canvas(800, 600)
render(concat(
    [CmdClear(0.08, 0.08, 0.1)],
    plotFnAuto(sinFn, 800.0, 600.0, -6.3, 6.3, "sin(x)")
))
show()
```

The canvas supports static rendering, animation via `animate(fps, drawFn)`, and a command-based drawing model (`CmdRect`, `CmdCircle`, `CmdLine`, `CmdText`, etc.) where all scene computation is pure.

### Built-in Mini-IDE

An OpenGL-based editor with:

- **Syntax highlighting** with incremental lexing
- **Real-time diagnostics** — parse errors appear as you type (0.6s debounce)
- **Ctrl+F / Ctrl+H** — find and replace with live highlighting
- **Ctrl+P** — command palette (action menu)
- **Ctrl+/** — help browser
- **Ctrl+I** — import browser with module discovery
- **Ctrl+M** — module browser with file search (Ctrl+R to refresh caches)
- **Shift+F5** — Trace Mode (execution profiler with per-file source overlay)
- **F5** — run current file
- **Multi-tab editing** with dirty detection and Ctrl+Tab cycling
- **Undo/redo** with branch navigation

### Trace Mode (Debugger)

Shift+F5 records all function calls, returns, errors, and variable assignments during execution. Results open as source-overlay tabs — one per module file — with:

- Heat-map block highlights (blue → green → red by call frequency, darker on hover/pin)
- Summary table of all traced declarations sorted by call count
- Invocation list with row selection (arrow keys, mouse, Enter to inspect)
- Invocation detail view: full inputs/outputs, local variables, call stack with file attribution, metadata — opens in the source area while the invocation list stays navigable
- Call stack reconstruction showing the full caller chain across files
- Per-file event attribution: operator overloads across files are correctly separated
- Ctrl+S to export invocation detail as a text file
- Scroll-aware split layout (hover to scroll source or panel independently)

## Standard Library

113+ built-in functions across 7 modules, plus SPN-source stdlib modules:

| Module | Functions |
|--------|-----------|
| **Math** | `sin`, `cos`, `tan`, `asin`, `acos`, `atan`, `ceil`, `floor`, `round`, `abs`, `sign`, `min`, `max`, `clamp`, `pow`, `sqrt`, `heron`, `gcd`, `toFloat` |
| **Array** | `map`, `filter`, `fold`, `flatten`, `zip`, `reverse`, `take`, `drop`, `find`, `all`, `any`, `sort`, `enumerate`, `concat`, `append`, `length`, `first`, `last`, `unique`, `chunk`, `groupBy`, `indexOf`, `contains` |
| **Dict** | `hasKey`, `keys`, `values`, `entries`, `merge`, `remove`, `mapValues` |
| **Set** | `difference`, `intersection`, `isSubset`, `fromArray`, `toArray`, `size` |
| **String** | `toUpper`, `toLower`, `trim`, `replace`, `substring`, `split`, `startsWith`, `show`, `join`, `formatNum` |
| **Range** | `rangeStep`, `repeat`, `iterate` |
| **Option** | `mapOption`, `flatMap`, `unwrap`, `unwrapOr` |
| **Ordering** | `deriveOrderingFromInt(T, cmp)`, `deriveOrderingFromOrdering(T, cmp)` — stdlib macros |

Canvas functions (`canvas`, `show`, `clear`, `fill`, `stroke`, `strokeWeight`, `rect`, `circle`, `line`, `text`, `animate`) are provided by the spn-canvas module.

## Architecture

```
spn-parent/
  spn-ast/        Core AST nodes, type system, trace recorder, runtime
  spn-parse/      Hand-written recursive descent parser, lexer, macro expander
  spn-stdlib/     Standard library (Java builtins + SPN-source modules in src/main/spn/)
  spn-canvas/     OpenGL 2D drawing API + plotting library (spn.canvas.draw, spn.canvas.plot)
  spn-fonts/      SDF font rendering via STB/LWJGL (per-context VAO, shared atlas)
  spn-stdui/      Platform-neutral UI framework (modes, widgets, input, HUD)
  spn-gui/        OpenGL mini-IDE (GLFW/LWJGL), diagnostic engine, tracer UI
  spn-pkg/        Package management & import resolution
  spn-traction/   Example SPN project (Rational, TComplex, TRot3 numerics)
  spn-intellij-plugin/  IntelliJ IDE support
```

### Type System

SPN uses nominal typing throughout. The type hierarchy includes:

- **Primitives** -- int (64-bit long), float (64-bit double), string, bool, Symbol
- **Constrained types** -- scalars with runtime-checked invariants
- **Product types** -- multi-component types with externalized operations
- **Structs** -- named record types with typed or positional fields
- **Variants (ADTs)** -- tagged unions with exhaustiveness checking
- **Anonymous unions** -- inline `Circle | Rectangle` type expressions with subtype assignability
- **Tuples** -- positionally-typed anonymous products with `(Type, Type)` syntax
- **Collections** -- Array, Set, Dictionary (unified `[]` syntax)
- **Function types** -- typed callable references

### Parse-Time Type Inference

The parser tracks expression types through the full precedence chain. This enables:

- **Operator dispatch** at parse time (not runtime) with promotion chains
- **Method resolution** on inferred receiver types
- **Categorical pattern checking** (tuple patterns can't match structs, etc.)
- **Missing overload detection** ("No overload for +(Foo, Bar)" at parse time)
- **Exhaustiveness checking** on union/variant subjects
- **Block/if/match type unification** (branch types merge into unions)
- **Implicit argument promotion** via user-defined `promote` rules at function call sites
- **Implicit Long-to-Double widening** at function boundaries

### Design Principles

- **Every body is a lambda.** `(params) { body }` or `{expr}` for no-arg lambdas. `=` binds a lambda to a name. `do` executes it.
- **Signatures carry types, lambdas carry names.** `pure add(int, int) -> int = (a, b) { a + b }`
- **Pure and pragmatic coexist.** Pure functions for logic, closures for control flow and mutation.
- **Types are smart, structs are dumb.** Numeric types carry algebraic rules. Structs are plain containers whose fields may reference smart types.
- **Arithmetic is externalized.** Operations are defined as `pure` functions outside the type, with `promote` rules for automatic dispatch across the type hierarchy.
- **Nominal typing.** Structs, tuples, and algebraic types are categorically distinct. No cross-type structural matching in pattern matching.
- **Newlines are statement boundaries.** No semicolons. Operators on a new line start a new expression (same-line rule for function calls, binary minus, and parenthesized expressions).
- **Definition order matters.** Each line can use everything above it. Unary operators before binary operators that use them. Types before functions that reference them. The file reads as a dependency chain.
- **Macros are code templates.** Compile-time textual substitution with function-invocation semantics. Output is normal SPN code, debuggable and inspectable.

## Getting Started

### Prerequisites

- **GraalVM JDK 25+** — download from [graalvm.org](https://www.graalvm.org/downloads/). Set `JAVA_HOME` to the GraalVM installation directory.
- **Apache Maven 3.9+** — download from [maven.apache.org](https://maven.apache.org/download.cgi). Ensure `mvn` is on your `PATH`.

### Building

```bash
mvn clean install
```

This compiles all modules, runs the Truffle DSL annotation processor, generates the standard library registry, and runs the test suite.

### Running the IDE

```bash
mvn -pl spn-gui exec:java
```

This launches the SPN editor. The IDE opens with an empty editor tab. From there:

| Action | Shortcut |
|--------|----------|
| Run | F5 |
| Run with Trace | Shift+F5 |
| New tab | Ctrl+N |
| Open file | Ctrl+O |
| Save | Ctrl+S |
| Find / Replace | Ctrl+F / Ctrl+H |
| Command palette | Ctrl+P |
| Help | Ctrl+/ |
| Import browser | Ctrl+I |
| Module browser | Ctrl+M |

To run with a file directly, open it with Ctrl+O or pass it as a working directory argument. For module-aware features (imports, trace across files), save your file inside a directory with a `module.spn` file.

### Running tests

```bash
mvn test
```

## Future Directions

- **Macro evolution** -- `emit { }` blocks with logic, `Type` as a first-class type, compile-time evaluation, macro composition via splicing
- **AND-types (interfaces)** -- globally namespaced interfaces that can be dynamically attached to any type at compile time, enabling omnidirectional dependency injection
- **`=?` three-way comparator** -- single `compareTo` dispatch replacing individual `<`, `>`, `<=`, `>=` overloads, returning `:lt | :eq | :gt`
- **Cross-file trace links** -- clickable caller links in the Tracer invocation panel
- **Type narrowing in match branches** -- union types narrow to the matched variant inside each arm
- Highly optimized 3D scenegraph runtime with unified geometry and physics simulation
- Vector math and Cayley transforms for full 3D with zero transcendental functions
- Native image compilation via GraalVM
