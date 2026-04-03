package spn.reflect;

import org.junit.jupiter.api.Test;
import spn.type.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static spn.type.ComponentExpression.*;

/**
 * Generates the SPN syntax specification file using the reflector.
 * Run this test to produce spn-syntax-spec.spn in the project root.
 */
class SyntaxSpecGenerator {

    static final SpnSymbolTable T = new SpnSymbolTable();

    @Test
    void generateSyntaxSpec() throws IOException {
        var sb = new StringBuilder();

        sb.append("""
                -- ═══════════════════════════════════════════════════════════════════════
                -- SPN (Symbols Plus Numbers) — Syntax Specification Draft
                -- ═══════════════════════════════════════════════════════════════════════
                -- This file represents the proposed syntax for SPN, generated from the
                -- AST framework. Edit this file to refine the syntax, then we'll build
                -- a parser that matches it.
                --
                -- Primary influences: Haskell/OCaml > Java > JavaScript > Ruby
                -- ═══════════════════════════════════════════════════════════════════════


                -- ─── NUMERIC / ALGEBRAIC TYPES ──────────────────────────────────────────
                -- Numeric types are the "smart" types: they carry constraints, rules,
                -- and operation definitions. They are tuples of primitives.

                """);

        // Natural numbers
        var natural = SpnTypeDescriptor.builder("Natural")
                .constraint(new Constraint.GreaterThanOrEqual(0))
                .constraint(new Constraint.ModuloEquals(1, 0))
                .build();
        sb.append(SpnSourceReflector.reflectType(natural)).append("\n\n");

        // Extended natural with Omega
        var omega = new SpnDistinguishedElement("Omega");
        var extNat = SpnTypeDescriptor.builder("ExtendedNatural")
                .constraint(new Constraint.GreaterThanOrEqual(0))
                .constraint(new Constraint.ModuloEquals(1, 0))
                .element(omega)
                .rule(new AlgebraicRule(Operation.DIV, new OperandPattern.Any(), new OperandPattern.ExactLong(0), omega))
                .rule(new AlgebraicRule(Operation.ADD, new OperandPattern.IsElement(omega), new OperandPattern.Any(), omega))
                .rule(new AlgebraicRule(Operation.ADD, new OperandPattern.Any(), new OperandPattern.IsElement(omega), omega))
                .build();
        sb.append(SpnSourceReflector.reflectType(extNat)).append("\n\n");

        // Complex numbers
        var complex = SpnTypeDescriptor.builder("Complex")
                .component("real", FieldType.DOUBLE)
                .component("imag", FieldType.DOUBLE)
                .productRule(Operation.ADD,
                        add(left(0), right(0)),
                        add(left(1), right(1)))
                .productRule(Operation.MUL,
                        sub(mul(left(0), right(0)), mul(left(1), right(1))),
                        add(mul(left(0), right(1)), mul(left(1), right(0))))
                .build();
        sb.append(SpnSourceReflector.reflectType(complex)).append("\n\n");

        // Vec3 with cross product
        var vec3 = SpnTypeDescriptor.builder("Vec3")
                .component("x", FieldType.DOUBLE)
                .component("y", FieldType.DOUBLE)
                .component("z", FieldType.DOUBLE)
                .productRule(Operation.ADD,
                        add(left(0), right(0)),
                        add(left(1), right(1)),
                        add(left(2), right(2)))
                .productRule(Operation.MUL,
                        sub(mul(left(1), right(2)), mul(left(2), right(1))),
                        sub(mul(left(2), right(0)), mul(left(0), right(2))),
                        sub(mul(left(0), right(1)), mul(left(1), right(0))))
                .build();
        sb.append(SpnSourceReflector.reflectType(vec3)).append("\n\n");

        // ColoredNumber - mixed numeric + symbol
        var red = T.intern("red");
        var green = T.intern("green");
        var blue = T.intern("blue");
        var colored = SpnTypeDescriptor.builder("ColoredNumber")
                .component("n", FieldType.DOUBLE)
                .component("color", FieldType.SYMBOL, Constraint.SymbolOneOf.of(red, green, blue))
                .productRule(Operation.ADD,
                        add(left(0), right(0)),
                        left(1))
                .build();
        sb.append(SpnSourceReflector.reflectType(colored)).append("\n\n");

        sb.append("""

                -- ─── SYMBOL TYPES ───────────────────────────────────────────────────────
                -- Symbols are interned atoms. Constrained symbol types act like enums.

                """);

        var colorSet = new SpnSymbolSet("Color", red, green, blue);
        sb.append(SpnSourceReflector.reflectSymbolSet(colorSet)).append("\n\n");

        var north = T.intern("north");
        var south = T.intern("south");
        var east = T.intern("east");
        var west = T.intern("west");
        var dirSet = new SpnSymbolSet("Direction", north, south, east, west);
        sb.append(SpnSourceReflector.reflectSymbolSet(dirSet)).append("\n\n");

        sb.append("""

                -- ─── CONSTRAINED STRING TYPES ───────────────────────────────────────────

                """);

        var identifier = SpnTypeDescriptor.builder("Identifier")
                .constraint(new Constraint.MinLength(1))
                .constraint(new Constraint.MaxLength(64))
                .constraint(new Constraint.MatchesPattern("[a-zA-Z_][a-zA-Z0-9_]*"))
                .build();
        sb.append(SpnSourceReflector.reflectType(identifier)).append("\n\n");

        var hexColor = SpnTypeDescriptor.builder("HexColor")
                .constraint(new Constraint.MinLength(6))
                .constraint(new Constraint.MaxLength(6))
                .constraint(new Constraint.CharSetConstraint(Constraint.CharClass.HEX))
                .build();
        sb.append(SpnSourceReflector.reflectType(hexColor)).append("\n\n");

        sb.append("""

                -- ─── STRUCTS (DATA TYPES) ───────────────────────────────────────────────
                -- Structs are "dumb" containers. They get their smartness from typed fields
                -- that reference numeric/algebraic types.

                """);

        // Shape ADT
        var circle = new SpnStructDescriptor("Circle", "radius");
        var rectangle = new SpnStructDescriptor("Rectangle", "width", "height");
        var triangle = new SpnStructDescriptor("Triangle", "a", "b", "c");
        var shape = new SpnVariantSet("Shape", circle, rectangle, triangle);
        sb.append(SpnSourceReflector.reflectVariantSet(shape)).append("\n\n");

        // Typed struct
        var point = SpnStructDescriptor.builder("Point")
                .field("x", FieldType.DOUBLE)
                .field("y", FieldType.DOUBLE)
                .build();
        sb.append(SpnSourceReflector.reflectStruct(point)).append("\n\n");

        // Generic struct
        var pair = SpnStructDescriptor.builder("Pair")
                .typeParam("T").typeParam("U")
                .field("first", FieldType.generic("T"))
                .field("second", FieldType.generic("U"))
                .build();
        sb.append(SpnSourceReflector.reflectStruct(pair)).append("\n\n");

        // Struct with algebraic type fields
        var pixel = SpnStructDescriptor.builder("Pixel")
                .field("color", FieldType.ofProduct(colored))
                .field("x", FieldType.LONG)
                .field("y", FieldType.LONG)
                .build();
        sb.append(SpnSourceReflector.reflectStruct(pixel)).append("\n\n");

        sb.append("""

                -- ─── TUPLES ─────────────────────────────────────────────────────────────
                -- Anonymous, structurally typed. Variable specificity per position.

                """);

        sb.append("-- Fully typed\n");
        sb.append(SpnSourceReflector.reflectTuple(new SpnTupleDescriptor(FieldType.LONG, FieldType.STRING))).append("\n\n");
        sb.append("-- Mixed specificity\n");
        sb.append(SpnSourceReflector.reflectTuple(new SpnTupleDescriptor(FieldType.LONG, FieldType.UNTYPED, FieldType.DOUBLE))).append("\n\n");

        sb.append("""

                -- ─── PURE FUNCTIONS ─────────────────────────────────────────────────────
                -- Pure functions have isolated scope, typed parameters (tuple-like),
                -- a return type, and must be total (all match cases covered).

                """);

        var areaFunc = SpnFunctionDescriptor.pure("area")
                .param("shape")
                .returns(FieldType.DOUBLE)
                .build();
        sb.append(SpnSourceReflector.reflectFunction(areaFunc)).append(" =\n");
        sb.append("""
                  match shape
                    | Circle(r)          -> 3.14159 * r * r
                    | Rectangle(w, h)    -> w * h
                    | Triangle(a, b, c)  -> heron(a, b, c)

                """);

        var addFunc = SpnFunctionDescriptor.pure("add")
                .param("a", FieldType.LONG)
                .param("b", FieldType.LONG)
                .returns(FieldType.LONG)
                .build();
        sb.append(SpnSourceReflector.reflectFunction(addFunc)).append(" = a + b\n\n");

        var oppositeFunc = SpnFunctionDescriptor.pure("opposite")
                .param("dir", FieldType.ofConstrainedType(
                        SpnTypeDescriptor.builder("Direction")
                                .constraint(new Constraint.IsSymbol()).build()))
                .returns(FieldType.ofConstrainedType(
                        SpnTypeDescriptor.builder("Direction")
                                .constraint(new Constraint.IsSymbol()).build()))
                .build();
        sb.append(SpnSourceReflector.reflectFunction(oppositeFunc)).append(" =\n");
        sb.append("""
                  match dir
                    | :north -> :south
                    | :south -> :north
                    | :east  -> :west
                    | :west  -> :east

                """);

        sb.append("""

                -- ─── PATTERN MATCHING ───────────────────────────────────────────────────
                -- Patterns can match on: struct type, literal, type, string prefix/suffix/regex,
                -- array structure, set membership, dictionary keys, or wildcard.

                -- String patterns
                pure parseUrl(url: String) -> String =
                  match url
                    | "http://" ++ rest                -> "web: " ++ rest
                    | "ftp://" ++ rest                 -> "file: " ++ rest
                    | /(\\w+):\\/\\/(.*)/(_,proto,path) -> proto ++ "://" ++ path
                    | _                                -> "unknown"

                -- Array patterns
                pure sum(arr: Array<Long>) -> Long =
                  match arr
                    | []      -> 0
                    | [h | t] -> h + sum(t)

                pure head3(arr) =
                  match arr
                    | [a, b, c] -> (a, b, c)
                    | _         -> (:error, 0, 0)

                -- Set patterns (membership only, no positional)
                pure describeColors(colors: Set<Symbol>) -> String =
                  match colors
                    | {}                       -> "none"
                    | {contains :red, :blue}   -> "has red and blue"
                    | {contains :red}          -> "has red"
                    | _                        -> "other"

                -- Dictionary patterns (key destructuring, binds values)
                pure greet(person: Dict) -> String =
                  match person
                    | {:name n, :age a} -> "Hello " ++ n ++ ", age " ++ show(a)
                    | {:name n}         -> "Hello " ++ n
                    | {:}               -> "Hello stranger"
                    | _                 -> "not a dict"

                -- Guards
                pure classify(n: Long) -> String =
                  match n
                    | x | x < 0  -> "negative"
                    | x | x > 0  -> "positive"
                    | _           -> "zero"

                """);

        sb.append("""

                -- ─── LAMBDA SCOPE & STREAMING ───────────────────────────────────────────
                -- Lambda blocks execute in the caller's frame (read/write parent vars).
                -- They cannot be passed by reference. Used with yield for streaming.

                -- Accumulation via streaming
                let sum = 0
                stream range(1, 10) { |n|
                  sum = sum + n
                }
                -- sum == 45

                -- Map-like transformation
                let results = []
                stream range(0, 5) { |i|
                  results = append(results, i * i)
                }
                -- results == [0, 1, 4, 9, 16]

                -- Producer function (uses yield)
                pure range(start: Long, end: Long) =
                  let i = start
                  while i < end do
                    yield i
                    i = i + 1
                  end

                """);

        sb.append("""

                -- ─── COLLECTIONS ────────────────────────────────────────────────────────

                -- Arrays (immutable, ordered, indexed)
                let nums: Array<Long> = [1, 2, 3, 4, 5]
                let first = nums[0]
                let len = length(nums)

                -- Sets (immutable, unordered, no duplicates)
                let tags: Set<Symbol> = {:red, :green, :blue}
                let hasRed = contains(tags, :red)
                let combined = union(tags, {:yellow})

                -- Dictionaries (immutable, symbol-keyed)
                let config: Dict<String> = {:host "localhost", :port "8080"}
                let host = config[:host]
                let updated = set(config, :port, "9090")  -- returns new dict

                """);

        sb.append("""

                -- ─── STRUCT CONSTRUCTION ────────────────────────────────────────────────

                let c = Circle(5.0)
                let r = Rectangle(3.0, 4.0)
                let p = Point(1.0, 2.0)
                let z = Complex(3.0, 4.0)
                let v = Vec3(1.0, 0.0, 0.0)

                -- Generic instantiation
                let pair = Pair<Long, String>(42, "hello")

                -- Nested
                let px = Pixel(ColoredNumber(1.0, :red), 10, 20)

                """);

        // Write the file
        var path = Path.of(System.getProperty("user.dir"), "spn-syntax-spec.spn");
        Files.writeString(path, sb.toString());
        System.out.println("Generated: " + path.toAbsolutePath());
    }
}
