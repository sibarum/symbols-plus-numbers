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
  - [Qualified Dispatch Keys](#qualified-dispatch-keys)
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
  - [Compile-Time Type Resolution](#compile-time-type-resolution)
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

-- Unary inverses as methods (operator mutex: unary OR binary, not both)
pure Rational.neg() -> Rational = () { Rational(-this.0, this.1) }
pure Rational.inv() -> Rational = () { Rational(this.1, this.0) }

-- Derive ordering from a single comparator
import Ordering
pure compare(Rational, Rational) -> int = (a, b) { ... }
deriveOrderingFromInt<Rational, compare>

-- Anonymous union types
pure area(Circle | Rectangle) -> float = (shape) {
  match shape
    | Circle(r)         -> 3.14159 * r * r
    | Rectangle(w, h)   -> w * h
}

-- Signatures + qualified keys: name a structural contract, constrain
-- macro params with `requires`. No conformance declaration on the type.
signature Additive (@+, @-, @neg)

macro deriveDouble<T requires Additive> = {
  pure T.doubled() -> T = () { this + this }
}
deriveDouble<Rational>        -- compile error if Rational lacks any Additive key
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

Compile-time impure functions. A macro body can define types, functions, and methods — but declarations are LOCAL to the macro scope unless explicitly `emit`-ted. Operator overloads and promotions register globally (they modify semantics, not scope).

Macros use angle brackets at declaration and invocation sites: `macro Name<P> = { ... }` is the only declaration form; `Name<Arg>` is the only invocation form. Parentheses are reserved for runtime calls.

```
-- Simple macro: register a function for some type
macro deriveDouble<T> = {
  pure double(T) -> T = (x) { x + x }
}
deriveDouble<int>
double(5)  -- 10

-- Derive ordering from a single comparator (stdlib)
import Ordering
deriveOrderingFromInt<Rational, compare>
-- now <, >, <=, >= work on Rational

-- Typed collection wrapper (stdlib)
import Collections
type RationalArray = Array<Rational>
let arr = RationalArray().push(5)      -- 5 promoted to Rational automatically
arr[0] == Rational(5, 1)                -- subscript returns the typed element
arr.items                               -- compile error: private field
```

**Macro features:**
- **`emit`** keyword transmits a declaration from the macro's scope to the caller. One emit per macro — `type X = macro<Arg>` binds the single emitted type under a user-chosen name.
- **Memoized invocations** — `Array<Rational>` used twice in the same file refers to the same emitted type. Type aliases bound to the same macro+args are interchangeable.
- **Conditional blocks** — `<! if COND !> { ... } <! else !> { ... }` selects between branches at expansion time based on macro-param values. Works for both conditional bodies and conditional declarations (see below).
- **Scoped isolation** — once a macro uses `emit`, all other named helpers it declares are discarded on completion; only emitted names plus operator overloads and promotions survive. A macro that doesn't `emit` anything (e.g. `deriveOrderingFromInt`, which only registers operator overloads) keeps everything it declares — the scoping kicks in specifically when `emit` narrows what escapes.
- **Private constructor fields** — `let this.field = expr` creates encapsulated state, accessible only from methods on the same type.
- **Multiple dispatch** — macro-generated functions participate in type-dispatched overloading.
- **Macro-aware error messages** — parse errors inside an expanded body are tagged with the macro invocation site (`[in macro name(file:line)]`), so diagnostics point back to the caller rather than the internal expansion.
- **Signature constraints** — `macro foo<T requires Ring> = { ... }` checks at the call site that T has impls for every dispatch key the signature lists; missing keys are named in the error. Multiple signatures compose with `&`: `<T requires Additive & Stringable>` requires both (see "Qualified Dispatch Keys" below).

**User-definable subscript:**

Any type can define `[]` as a method, and `x[i]` at a call site dispatches to it. Stdlib collection macros use this to preserve element types through subscript:

```
macro Array<T> = {
  type TypedArray
  ...
  pure TypedArray[](int) -> T = (i) { this.items[i] }
  ...
  emit TypedArray
}

type IntArray = Array<int>
let xs = IntArray().push(1).push(2)
xs[0]         -- int, not _ — typechecks for arithmetic, match narrowing, etc.
```

The setter form `pure TypeName[](int, T) -> ...` is reserved for stateful types (not yet implemented — immutable types use a named `.with(i, x)` method instead).

**Macro conditional blocks:**

The `<! ... !>` delimiters mark a macro-expansion-time directive. The only form in v1 is a binary conditional — always paired with a mandatory `else` — selecting between two blocks:

```
macro withFlavor<flavor> = {
  type Wrapper(int)

  -- Conditional body: same method, different implementations
  pure Wrapper.go() -> int = () {
    <! if flavor == :fast !> { this.0 * 2 }
    <! else !> { this.0 }
  }

  -- Conditional declaration: empty else = "include only if"
  <! if flavor == :tagged !> {
    pure Wrapper.tag() -> string = () { "tagged" }
  } <! else !> { }

  emit Wrapper
}

type Fast = withFlavor<:fast>         -- .go() doubles; no .tag()
type Tagged = withFlavor<:tagged>     -- .go() passes through; has .tag()
```

The condition is evaluated against macro-param-substituted literals. Supported: int/string/symbol/bool literals, `==`/`!=`/`<`/`>`/`<=`/`>=`, `&&`/`||`/`!`, parentheses. Any non-literal identifier reaching evaluation produces a parse error attributed to the macro call site. Conditional blocks nest; inner directives are resolved on the chosen outer branch.

The delimiters are deliberately loud (`<!` / `!>` render in a warning color) so that macro-time logic tears itself visually out of the surrounding SPN code — at a glance, you can tell what's evaluated now versus what runs later.

### Qualified Dispatch Keys

Global, fully-qualified dispatch slots that unify operators, methods, and interface members into one primitive. Every call site — `a + b`, `a.foo()`, `foo(a, b)`, and `macro<T requires Sig>(a)` — is the same mechanism: find an entry in the dispatch table matching the arg types, with promotion allowed as a relaxation.

```
module com.myapp

-- Register the canonical shape of a key. The namespace prefix
-- (com.myapp) must match this file's module declaration.
register pure @com.myapp.serialize() -> string

-- Any type, anywhere, can implement the key.
type Box(int)
pure Box.@com.myapp.serialize() -> string = () { "box" }

-- Call with the `.@fqn(args)` form.
Box(7).@com.myapp.serialize()
```

**Namespace ownership.** A file may only `register` keys whose prefix it owns — either exactly its module namespace or a sub-prefix. Attempts to claim a broader or unrelated namespace fail at parse time. Files without a `module` declaration can't register qualified keys at all.

**Operators are pre-registered keys.** `@+`, `@-`, `@*`, `@*_dot` etc. reference the built-in operator overloads with no need for explicit registration.

**Import shortening.** Writing `obj.@com.myapp.serialize()` everywhere is noisy. Bringing the key into scope lets you use the short name:

```
import com.myapp.(serialize, describe)

obj.serialize()         -- resolves to obj.@com.myapp.serialize()
obj.describe()          -- resolves to obj.@com.myapp.describe()
```

Regular methods take precedence over aliases — if `Box.foo` exists as a method and `@com.myapp.foo` is imported, `box.foo()` calls the method. The alias is a fallback, not a shadow.

**Signatures** — a named set of required dispatch keys. Flat, composable, anonymous-structural:

```
signature Additive (@+, @-, @neg)
signature Multiplicative (@*, @inv)
signature Ring (Additive, Multiplicative, @zero, @one)
```

Sub-signature references expand at parse time so the registry holds the transitive closure. A type satisfies a signature iff it has impls for every key — *structurally*, with no conformance declaration, no `impl` block, no orphan rule. Add a missing operator on a type and it retroactively satisfies every signature that required it.

**`requires` constraints on macro parameters.** The payoff: you can state polymorphism constraints at the call site without reaching for generics:

```
macro deriveArithmetic<T requires Ring> = {
  pure T.doubled() -> T = () { this + this }
  pure T.squared() -> T = () { this * this }
}

deriveArithmetic<Rational>   -- Rational has all Ring keys → ok
deriveArithmetic<Box>        -- parse error: "Box doesn't satisfy Ring: missing @-, @inv, ..."
```

The error names the specific missing keys at the macro invocation site — dispatch failures don't have to happen deep inside macro expansion.

**Composing signatures at the use site.** Multiple signatures can be required together with `&`:

```
signature Additive (@+, @-)
signature Stringable (@stringify)

macro describePair<T requires Additive & Stringable> = {
  pure T.pair() -> T = () { this + this }
  pure T.show() -> string = () { this.@stringify() }
}
```

Only the *unsatisfied* signatures are named in the error — if T satisfies `Additive` but not `Stringable`, the error cites only `Stringable` and its missing keys. This avoids having to pre-declare every useful intersection (`signature AddStringable (Additive, Stringable)`) as a global name.

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
- **Ctrl+T** — type info: shows resolved operator dispatches for the current line (e.g. `+(Rational, Rational)`)
- **Ctrl+F / Ctrl+H** — find and replace with live highlighting
- **Ctrl+P** — command palette (action menu)
- **Ctrl+/** — help browser
- **Ctrl+I** — import browser with module discovery, kind filters (`@`, `sig:`, `macro:`, `type:`, `fn:`, `mod:`), color-coded results, and cross-reference search (typing a key name surfaces every signature that includes it). Ctrl+R rebuilds the index.
- **Ctrl+M** — module browser with file search (Ctrl+R to refresh caches)
- **Ctrl+V** — paste into any text-input mode (import / module / help / palette / template fields)
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

120+ built-in functions across 7 modules, plus SPN-source stdlib modules:

| Module | Functions |
|--------|-----------|
| **Math** | `sin`, `cos`, `tan`, `asin`, `acos`, `atan`, `ceil`, `floor`, `round`, `abs`, `sign`, `min`, `max`, `clamp`, `pow`, `sqrt`, `heron`, `gcd`, `toFloat`, `bitWidth`, `shr`, `shl` |
| **Array** | `map`, `filter`, `fold`, `flatten`, `zip`, `reverse`, `take`, `drop`, `find`, `all`, `any`, `sort`, `enumerate`, `concat`, `append`, `length`, `first`, `last`, `unique`, `chunk`, `groupBy`, `indexOf`, `contains` |
| **Dict** | `put`, `dictGet`, `hasKey`, `keys`, `values`, `entries`, `merge`, `remove`, `mapValues`, `dictSize`, `emptyDict` |
| **Set** | `setAdd`, `setRemove`, `difference`, `intersection`, `isSubset`, `fromArray`, `toArray`, `size` |
| **String** | `toUpper`, `toLower`, `trim`, `replace`, `substring`, `split`, `startsWith`, `show`, `join`, `formatNum` |
| **Range** | `rangeStep`, `repeat`, `iterate` |
| **Option** | `mapOption`, `flatMap`, `unwrap`, `unwrapOr` |
| **Ordering** | `deriveOrderingFromInt<T, cmp>`, `deriveOrderingFromOrdering<T, cmp>` — stdlib macros |
| **Collections** | Stdlib macros for typed collection wrappers with private encapsulated storage: `Array<T>` (`.push`, `[i]`, `.length`, `.reverse`), `Set<T>` (`.add`, `.remove`, `.size`, `.toArray`), `Dict<K, V>` (`.put`, `[k]`, `.has`, `.remove`, `.size`, `.keys`, `.values`) |
| **Numerics** | `Rational<bits>` and `TComplex<R>` — composable stdlib macros. `Rational<31>` emits a hard-budget rational type with bitshift normalization; `TComplex<R>` wraps any rational-like `R` as `(scale, tan θ)` for exact angle arithmetic. Nest as `TComplex<Rational<31>>`. |

Canvas functions (`canvas`, `show`, `clear`, `fill`, `stroke`, `strokeWeight`, `rect`, `circle`, `line`, `text`, `animate`) are provided by the spn-canvas module.

## Architecture

```
spn-parent/
  spn-ast/        Core AST nodes, type system, trace recorder, runtime
  spn-parse/      Parser (Pratt precedence), TypeResolver, PatternParser, TypeParser, TypeGraph
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

### Compile-Time Type Resolution

The `TypeResolver` (separate from the parser) tracks expression types and resolves every dispatch decision before execution. Every operator, method call, and promotion is resolved at compile time with certainty — no runtime dispatch. This enables:

- **Operator dispatch** at compile time (not runtime) with promotion chains
- **Method resolution** on inferred receiver types
- **Categorical pattern checking** (tuple patterns can't match structs, etc.)
- **Missing overload detection** ("No overload for +(Foo, Bar)" at compile time)
- **Exhaustiveness checking** on union/variant subjects
- **Match type unification** (branch types merge into unions)
- **Implicit argument promotion** via user-defined `promote` rules at function call sites
- **Implicit Long-to-Double widening** at function boundaries
- **IDE dispatch annotations** (Ctrl+T shows resolved overloads per line)

The `TypeGraph` records every declaration (types, functions, operators, promotions) with source positions, enabling go-to-definition, find-usages, and incremental re-inference on edit.

### Design Principles

- **Every body is a lambda.** `(params) { body }` or `{expr}` for no-arg lambdas. `=` binds a lambda to a name. `do` executes it.
- **Signatures carry types, lambdas carry names.** `pure add(int, int) -> int = (a, b) { a + b }`
- **Pure and pragmatic coexist.** Pure functions for logic, closures for control flow and mutation.
- **Types are smart, structs are dumb.** Numeric types carry algebraic rules. Structs are plain containers whose fields may reference smart types.
- **Arithmetic is externalized.** Operations are defined as `pure` functions outside the type, with `promote` rules for automatic dispatch across the type hierarchy.
- **Nominal typing.** Structs, tuples, and algebraic types are categorically distinct. No cross-type structural matching in pattern matching.
- **Newlines are statement boundaries.** No semicolons. Operators on a new line start a new expression (same-line rule for function calls, binary minus, and parenthesized expressions).
- **Definition order matters.** Each line can use everything above it. Unary operators before binary operators that use them. Types before functions that reference them. The file reads as a dependency chain.
- **No `if` keyword.** Conditionals are subject-less guard matches: `match | cond -> a | _ -> b`. One canonical form for all branching.
- **Macros are compile-time impure functions.** Declare with `macro Name<P> = { ... }` and invoke with `Name<Arg>`. `emit` controls what single declaration escapes to the caller's scope. Replaces generics: `type RationalArray = Array<Rational>`. Macro parameters can carry `requires` constraints — `macro foo<T requires Ring>` or composed as `<T requires Additive & Stringable>` — to specify polymorphism without a separate type-variable system. Invocations memoize: `Array<Rational>` twice in the same file refers to the same emitted type.
- **Dispatch is one mechanism.** Operators (`a + b`), method calls (`a.foo()`), free-function calls (`foo(a, b)`), and signature-constrained generics (`macro<T requires Sig>`) all resolve via the same global table — find an entry matching the arg types, with promotion as a relaxation. Qualified keys (`@com.foo.bar`) are the universal name for a dispatch slot; operators are pre-registered keys; `signature` declarations name sets of required keys. No parallel typeclass / trait / interface system.
- **Encapsulation via constructor fields.** `let this.field = expr` in a constructor creates private state, accessible only from methods on the same type. No runtime reflection.

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
| Type Info | Ctrl+T |
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

- **TypeGraph-driven IDE features** -- go-to-definition, find-usages, and dependency visualization powered by the unified declaration graph
- **Incremental type inference** -- exploit definition-order for sub-millisecond re-inference on edits (invalidate from edit line onward, cache above)
- **Macro evolution** -- compile-time evaluation beyond literal comparisons (currently the conditional-block evaluator handles literal/symbol/bool/int comparisons + `&&`/`||`/`!`); environment- or platform-specific code generation; non-source artifact generation (manifests, lookup tables)
- **Subscript setter for stateful types** -- `pure MyType[](int, T) = (i, obj) { ... }` on stateful types so `arr[i] = x` inside a `do` block mutates the heap-allocated instance
- **`=?` three-way comparator** -- single `compareTo` dispatch replacing individual `<`, `>`, `<=`, `>=` overloads, returning `:lt | :eq | :gt`
- **Cross-file trace links** -- clickable caller links in the Tracer invocation panel
- **Type narrowing in match branches** -- union types narrow to the matched variant inside each arm
- **Native dispatcher overflow recovery** -- per-(operation, type-pair) fallback implementations registered alongside the canonical impl; macro-generatable
- **Constrained-symbol coercion** -- `:red` should auto-coerce into `type Color = Symbol` at function call sites (today the coercion has to be explicit, blocking constrained-key TypedDict)
- **Generic functions with `requires`** -- bring the macro-side `T requires Sig` constraint to plain `pure` functions so polymorphism doesn't require a macro
- Highly optimized 3D scenegraph runtime with unified geometry and physics simulation
- Vector math and Cayley transforms for full 3D with zero transcendental functions
- Native image compilation via GraalVM
