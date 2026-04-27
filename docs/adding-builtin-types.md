# Adding a New Built-in Type to SPN

This is the recipe for surfacing a Java class as a first-class SPN nominal
type — like `Array`, `Dict`, `Set`. The type is known to the type system,
methods dispatch via `receiver.method(args)` syntax, and instances flow
through the runtime as bare Java objects (no `SpnStructValue` wrapping).

When **not** to use this pattern:
  - If the type can be expressed as an SPN-side struct or macro, prefer that
    (see `spn-stdlib/src/main/spn/Numerics.spn` for `TractionQuaternion` /
    `Rational` macro examples). Built-in types are warranted when:
    (a) you want native-image speed, (b) the implementation is the source
    of truth and shouldn't be re-derived in SPN, or (c) the type is
    fundamental enough that struct overhead is wrong.
  - True primitives (`int`, `float`, `bool`) also have lexer keywords. That's
    a heavier change and not what this doc covers.

The example throughout is `TractionRotor` (`spn.clifford.TractionRotor`).
Replace with your new type's name.

## Touch-points

| # | File                                                          | What                                                                                |
|---|---------------------------------------------------------------|-------------------------------------------------------------------------------------|
| 1 | `spn-ast/src/main/java/spn/<pkg>/<TypeName>.java`             | The value class itself                                                              |
| 2 | `spn-ast/src/main/java/spn/type/FieldType.java`               | Add an `OfClass` constant                                                           |
| 3 | `spn-parse/src/main/java/spn/lang/TypeParser.java`            | Add a case in `parseSingleFieldType()`'s switch                                     |
| 4 | `spn-stdlib/src/main/java/spn/stdlib/gen/StdlibRegistryGenerator.java` | Add a case in `resolveReturnFieldType()`                                    |
| 5 | `spn-stdlib/src/main/java/spn/stdlib/<module>/Spn*Node.java`  | One Truffle node per factory and method, annotated with `@SpnBuiltin`               |

After (5) compiles, the build's post-compile generator wires everything: the
generated `SpnStdlibRegistry`, `StdlibModuleLoader`, and `spn-stdlib.spn`
interface all pick up your nodes automatically.

## Step 1 — Value class lives in spn-ast

The value class must be visible to `FieldType` (which lives in
`spn-ast/src/main/java/spn/type/`). Putting the class in `spn-stdlib` would
create a circular module dependency, so it goes in `spn-ast`.

```java
package spn.clifford;

public final class TractionRotor {
    // immutable, equals/hashCode, all the math methods
}
```

Constraints:
  - Should be immutable (instances are shared values; SPN code can't mutate).
  - Should have proper `equals`/`hashCode` so SPN's `==` and structural
    matching behave sensibly.
  - Avoid Truffle dependencies in the value class itself — keep it pure
    Java. (The `@SpnBuiltin` nodes in step 5 are the Truffle layer.)

## Step 2 — Register the FieldType

Add an `OfClass` constant in `FieldType.java` next to `LONG`/`DOUBLE`/etc.:

```java
import spn.clifford.TractionRotor;

public sealed interface FieldType {
    OfClass LONG = new OfClass(Long.class, "Long");
    OfClass DOUBLE = new OfClass(Double.class, "Double");
    // ...
    OfClass TRACTION_ROTOR = new OfClass(TractionRotor.class, "TractionRotor");
    // ...
}
```

The display name string (`"TractionRotor"`) is what shows up in error
messages and is the key the method-dispatch system uses to look up methods
(see step 5: `receiver = "TractionRotor"` must match this string).

## Step 3 — Recognize the type name in `TypeParser`

In `TypeParser.parseSingleFieldType()`'s switch on the type name, add:

```java
case "Symbol" -> FieldType.SYMBOL;
case "TractionRotor" -> FieldType.TRACTION_ROTOR;
case "Array", "UntypedArray", "Collection" -> FieldType.ofArray(FieldType.UNTYPED);
```

This is what makes SPN code like `pure foo(TractionRotor) -> Double` and
`let r: TractionRotor = ...` parse correctly.

## Step 4 — Teach the registry generator the return-type mapping

The `@SpnBuiltin(returns = "...")` annotation is a string; the generator
needs to know how to turn that string into a `FieldType` reference for the
generated descriptor. Add a case in
`StdlibRegistryGenerator.resolveReturnFieldType()`:

```java
return switch (typeName) {
    case "Long" -> "FieldType.LONG";
    case "Double" -> "FieldType.DOUBLE";
    // ...
    case "TractionRotor" -> "FieldType.TRACTION_ROTOR";
    case "Array" -> "FieldType.ofArray(FieldType.UNTYPED)";
    // ...
};
```

**Forgetting this step is the most common bug.** Without it, the generator
emits a descriptor with no return type, and the parser fails to infer that
`let r = makeRotor(...)` makes `r` a `TractionRotor` — so dot-method
dispatch later fails with `Unknown method 'apply'`. Symptom: the *factory*
call works but `.method()` on its result doesn't.

## Step 5 — Write `@SpnBuiltin` nodes

Each factory and method is a Truffle node in `spn-stdlib`, annotated with
`@SpnBuiltin`. Pattern after `spn.stdlib.array.SpnLengthNode` for methods
and `spn.stdlib.math.SpnHeronNode` for free functions.

### Factory (free function)

```java
@SpnBuiltin(name = "tractionRotor", module = "Clifford",
        params = {"thetaW", "thetaU"}, returns = "TractionRotor")
@NodeChild("thetaW")
@NodeChild("thetaU")
public abstract class SpnTractionRotorFromAnglesNode extends SpnExpressionNode {

    @Specialization
    protected TractionRotor fromAngles(double thetaW, double thetaU) {
        return TractionRotor.fromAngles(thetaW, thetaU);
    }

    @Fallback
    protected Object typeError(Object thetaW, Object thetaU) {
        throw new SpnException("tractionRotor expects two numbers", this);
    }
}
```

### Method (receiver-bound)

```java
@SpnBuiltin(name = "rotorApply", module = "Clifford",
        params = {"rotor", "v"}, returns = "Array",
        receiver = "TractionRotor", method = "apply")
@NodeChild("rotor")
@NodeChild("v")
public abstract class SpnTractionRotorApplyNode extends SpnExpressionNode {

    @Specialization
    protected SpnArrayValue apply(TractionRotor rotor, SpnArrayValue v) {
        // ... math ...
        return new SpnArrayValue(FieldType.DOUBLE, /* … */);
    }

    @Fallback
    protected Object typeError(Object rotor, Object v) {
        throw new SpnException("apply expects (TractionRotor, Array)", this);
    }
}
```

Notes:
  - `receiver = "TractionRotor"` must match the `displayName` from step 2.
  - `method = "apply"` is the dotted-method name; if omitted, defaults to
    `name`. The pattern of distinct flat name + method name (e.g. flat
    `rotorApply`, method `apply`) avoids collisions in the global flat-
    function namespace and reads naturally as `r.apply(...)`.
  - Receiver MUST be the first `@NodeChild`.
  - `params` array names must match `@NodeChild` declarations (the DSL has
    CLASS retention so the runtime can't read them otherwise).
  - SPN's `Array` corresponds to `SpnArrayValue` at runtime. Element access
    is `Object[]` — extract numbers with `((Number) v.get(i)).doubleValue()`.

## Step 6 — Test from SPN

Tests live in `spn-stdlib/src/test/java/spn/stdlib/`. Pattern after
`CollectionMethodsTest`:

```java
private Object run(String source) {
    SpnParser parser = new SpnParser(source, null, null, symbolTable, registry);
    return parser.parse().getCallTarget().call();
}

@Test void rotorApply() {
    Object result = run("""
        import Clifford
        let r = tractionRotor(1.5707, 0.0)
        r.toSpherePoint()
        """);
    // assertions
}
```

`import <Module>` is required to bring stdlib functions and methods into
scope — the module name is whatever you put in `@SpnBuiltin(module = "...")`.

Run with:
```
mvn -pl spn-stdlib test -Dtest=YourTestClass -Dsurefire.failIfNoSpecifiedTests=false
```

## Build / regeneration notes

The generator runs in Maven's `process-classes` phase, **not** `compile`.
If you only run `mvn compile`, the generated registry / loader / `.spn`
interface won't update. Use one of:

  - `mvn -pl spn-stdlib process-classes` — regenerates without running tests
  - `mvn -pl spn-stdlib test -Dtest=...` — regenerates as a side effect of
    `compile` (which the test phase depends on, transitively pulling in
    `process-classes`)
  - `mvn -pl spn-ast install -DskipTests` — needed if the value class in
    spn-ast was edited and downstream modules need the fresh artifact

When in doubt: `mvn install -DskipTests` from the repo root rebuilds everything.

## Verifying it worked

After regeneration, check that all four pieces show up:

```bash
# 1. The function appears in the generated SPN interface:
grep -A2 YOUR_MODULE spn-stdlib/target/generated-sources/stdlib/spn-stdlib.spn

# 2. The descriptor has the right return type:
grep -A8 'describe_yourFactory' spn-stdlib/target/generated-sources/stdlib/spn/stdlib/gen/SpnStdlibRegistry.java
# Expect to see: .returns(FieldType.YOUR_TYPE)

# 3. Methods are registered with the right receiver name:
grep 'YourType\.' spn-stdlib/target/generated-sources/stdlib/spn/stdlib/gen/StdlibModuleLoader.java
```

If `.returns(...)` is missing from the descriptor, you forgot step 4.
If methods don't appear in the loader, the `receiver` annotation field is
likely wrong or missing.

## Common pitfalls

  - **Skipping step 4.** Factory works, methods on its result don't. See
    "Forgetting this step is the most common bug" above.
  - **Receiver name mismatch.** The string in `receiver = "..."`,
    `FieldType.<CONST>.displayName`, and the `TypeParser` case must all
    match exactly.
  - **Forgetting `import <Module>` in tests.** SPN doesn't auto-import
    stdlib modules. Without the import, `Unknown function: yourFactory`.
  - **Putting the value class in spn-stdlib.** Creates a circular
    dependency with spn-ast (which `FieldType` lives in). Always put
    new value classes in spn-ast.
  - **Not running `process-classes`.** The build will compile but the
    generated registry won't pick up your new nodes. Always at least
    run `mvn -pl spn-stdlib process-classes` after adding `@SpnBuiltin`
    nodes.
