package dev.superice.gdparser.frontend.gdscript;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.DynamicTest.dynamicTest;

/// Dual-path parity for the declaration layer: program prefix, class-body declarations,
/// suites, annotations, types and the declaration-scoped expression subset. All snippets
/// belong to the "both paths support, structure identical" parity class; registered
/// divergences (docstring consumption, in-body `extends`, precedence corner combos) are
/// intentionally excluded here and covered by dedicated Godot-structure tests.
class GdParserParityTest extends GdParserParityTestSupport {

    /// Phase 4 acceptance gate: all 13 fixtures must pass full-file dual-path parity, exercising
    /// statements, control flow, lambdas, match patterns, properties, comments and regions
    /// together (§5.7 (i); the whitelist stays limited to the registered entries).
    @TestFactory
    List<DynamicTest> fixtureFullFileParity() throws IOException {
        var tests = new ArrayList<DynamicTest>();
        for (var fixture : fixtureScripts()) {
            tests.add(dynamicTest("fixture: " + fixture.getFileName(), () -> {
                var source = Files.readString(fixture, StandardCharsets.UTF_8);
                assertCleanParity(source);
            }));
        }
        return List.copyOf(tests);
    }

    @Test
    void programPrefixParity() {
        assertCleanParity("class_name Foo\n");
        assertCleanParity("extends Node\n");
        assertCleanParity("class_name Foo extends Node\n");
        assertCleanParity("extends Node\nclass_name Foo\n");
        assertCleanParity("extends \"res://base.gd\"\n");
        assertCleanParity("extends Foo.Bar\n");
        assertCleanParity("");
        assertParity("\n");
    }

    @Test
    void variableAndConstantParity() {
        assertCleanParity("var x\n");
        assertCleanParity("var x = 1\n");
        assertCleanParity("var x: int\n");
        assertCleanParity("var x: int = 1\n");
        assertCleanParity("var x := 1\n");
        assertCleanParity("var x : = 1\n");
        assertCleanParity("static var x\n");
        assertCleanParity("const A = 1\n");
        assertCleanParity("const A: int = 1\n");
        assertCleanParity("const A := 1\n");
    }

    @Test
    void signalParity() {
        assertCleanParity("signal sig\n");
        assertCleanParity("signal sig(a, b: int)\n");
        assertCleanParity("signal sig(\n\ta,\n\tb: int,\n)\n");
    }

    @Test
    void enumParity() {
        assertCleanParity("enum {A, B, C}\n");
        assertCleanParity("enum {A = 1, B = 2}\n");
        assertCleanParity("enum Name {A, B}\n");
        assertCleanParity("enum {A,}\n");
        assertCleanParity("enum {\n\tA,\n\tB = 2,\n}\n");
    }

    @Test
    void functionParity() {
        assertCleanParity("func f(): pass\n");
        assertCleanParity("func f(a, b: int, c = 1, d: String = \"x\"): pass\n");
        assertCleanParity("func f(a, ...args): pass\n");
        assertCleanParity("func f() -> void: pass\n");
        assertCleanParity("func f() -> int: pass\n");
        assertCleanParity("static func f(): pass\n");
        assertCleanParity("func f()\n");
        assertCleanParity("func f(): pass; pass\n");
        assertCleanParity("func f():\n\tvar x = 1\n\tpass\n");
        assertCleanParity("func f(\n\ta,\n\tb: int,\n) -> void: pass\n");
    }

    @Test
    void constructorParity() {
        assertCleanParity("func _init(): pass\n");
        assertCleanParity("func _init() -> void: pass\n");
        assertCleanParity("func _init(a, b := 1): pass\n");
    }

    @Test
    void nestedClassParity() {
        assertCleanParity("class Inner:\n\tpass\n");
        assertCleanParity("class Inner extends Base:\n\tpass\n");
        assertCleanParity("class Inner: pass\n");
    }

    @Test
    void annotationParity() {
        assertCleanParity("@export var x = 1\n");
        assertCleanParity("@export\nvar x = 1\n");
        assertCleanParity("@tool\nclass_name Foo\n");
        assertCleanParity("@rpc(\"any_peer\", \"call_local\")\nfunc f(): pass\n");
        assertCleanParity("@rpc(\n\t\"any_peer\",\n)\nvar x = 1\n");
    }

    @Test
    void typeParity() {
        assertCleanParity("var x: Array[int]\n");
        assertCleanParity("var d: Dictionary[String, int]\n");
        assertCleanParity("var p: Foo.Bar\n");
        assertCleanParity("func f(a: Array[int], b: Dictionary[String, int]) -> Array[int]: pass\n");
    }

    @Test
    void propertyConsumptionParity() {
        assertCleanParity("var x: int: get = a, set = b\n");
        assertCleanParity("var x: int: set = b, get = a\n");
    }

    @Test
    void declarationExpressionParity() {
        assertCleanParity("var x = -1\n");
        assertCleanParity("var x = +1\n");
        assertCleanParity("const A = 1 + 2 * 3\n");
        assertCleanParity("const A = 2 ** 3 ** 2\n");
        assertCleanParity("const A = f(1, 2)\n");
        assertCleanParity("const A = a.b.c\n");
        assertCleanParity("const A = a[0]\n");
        assertCleanParity("const A = PI\n");
        assertCleanParity("const A = a.b(1).c[2]\n");
        assertCleanParity("const A = f(1).x\n");
        assertCleanParity("const A = f(1)(2)\n");
        assertCleanParity("const A = a[0][1]\n");
        assertCleanParity("const A = a.b(1)(2)\n");
        assertCleanParity("const A = a.b.c.d.e.f.g.h\n");
        assertCleanParity("const A = a.b(\n\t1\n).c\n");
    }

    @Test
    void containerLiteralParity() {
        assertCleanParity("var a = [1, 2, 3]\n");
        assertCleanParity("var a = []\n");
        assertCleanParity("var a = [1, 2,]\n");
        assertCleanParity("var a = [[1], [2]]\n");
        assertCleanParity("var a = [\n\t1,\n\t2,\n]\n");
        assertCleanParity("var d = {\"a\": 1, b: 2}\n");
        assertCleanParity("var d = {}\n");
        assertCleanParity("var d = {\"a\": 1,}\n");
        // Lua-style keys normalize to synthesized StringName literals on both paths.
        assertCleanParity("var d = {x = 1}\n");
        assertCleanParity("var d = {\"name\" = 1}\n");
        assertCleanParity("var d = {x = 1, y = 2}\n");
        assertCleanParity("var d = {\n\t\"a\": 1,\n\tb: 2,\n}\n");
    }

    @Test
    void dedicatedExpressionFormParity() {
        assertCleanParity("var n = $Child\n");
        assertCleanParity("var n = $\"a/b\"\n");
        assertCleanParity("var n = $/root\n");
        assertCleanParity("var n = $A/B\n");
        assertCleanParity("var n = %Unique\n");
        assertCleanParity("const P = preload(\"res://a.gd\")\n");
        assertCleanParity("func f():\n\tawait foo()\n");
        assertCleanParity("func f():\n\tawait get_tree().create_timer(1).timeout\n");
        assertCleanParity("func f():\n\tsuper()\n");
        assertCleanParity("func f():\n\tsuper.foo(1)\n");
        assertCleanParity("var a = x as int\n");
        assertCleanParity("var a = x as Array[int]\n");
        assertCleanParity("var a = x is Node\n");
        assertCleanParity("var a = x is not Node\n");
        assertCleanParity("var a = 1 if c else 2\n");
        assertCleanParity("var a = x not in y\n");
        assertCleanParity("var a = not b\n");
        assertCleanParity("var a = !b\n");
        assertCleanParity("var a = ~x\n");
    }

    /// Grouping participates in enclosing-node ranges: the inner node keeps its own range while
    /// enclosing nodes span the consumed parentheses (the range contract pinned here byte-exact).
    @Test
    void groupingRangeParity() {
        assertCleanParity("const A = (1 + 2) * 3\n");
        assertCleanParity("var a = ((1))\n");
        assertCleanParity("var a = -(x)\n");
        assertCleanParity("var a = (x) + (y)\n");
        assertCleanParity("func f():\n\tawait (x)\n");
        assertCleanParity("func f():\n\t(f)(1)\n");
        assertCleanParity("var a = (x) if c else (y)\n");
        assertCleanParity("var d = {(a): 1}\n");
        assertCleanParity("var a = [(1), (2)]\n");
    }

    @Test
    void assignmentParity() {
        assertCleanParity("func f():\n\tx = 1\n");
        assertCleanParity("func f():\n\tx += 1\n");
        assertCleanParity("func f():\n\tx -= 1\n");
        assertCleanParity("func f():\n\tx *= 2\n");
        assertCleanParity("func f():\n\tx **= 2\n");
        assertCleanParity("func f():\n\tx /= 2\n");
        assertCleanParity("func f():\n\tx %= 2\n");
        assertCleanParity("func f():\n\tx <<= 1\n");
        assertCleanParity("func f():\n\tx >>= 1\n");
        assertCleanParity("func f():\n\tx &= 1\n");
        assertCleanParity("func f():\n\tx |= 1\n");
        assertCleanParity("func f():\n\tx ^= 1\n");
        assertCleanParity("func f():\n\ta.b = 1\n");
        assertCleanParity("func f():\n\ta[0] = 1\n");
        assertCleanParity("func f():\n\ta = b + c\n");
        assertCleanParity("func f():\n\ta = b if c else d\n");
    }

    /// Representative two-operator cross-family combos from the precedence matrix. §5.8
    /// divergence combos (`*` followed by `**`, unary sign before `**`, additive left of `as`)
    /// are excluded here and pinned as Godot structures in `GdScriptParserTest`.
    @Test
    void operatorPrecedenceMatrixParity() {
        assertCleanParity("var a = x + y * z\n");
        assertCleanParity("var a = x * y + z\n");
        assertCleanParity("var a = x ** y ** z\n");
        assertCleanParity("var a = x ** y * z\n");
        assertCleanParity("var a = x << y + z\n");
        assertCleanParity("var a = x + y << z\n");
        assertCleanParity("var a = x & y == z\n");
        assertCleanParity("var a = x | y ^ z\n");
        assertCleanParity("var a = x < y == z\n");
        assertCleanParity("var a = x == y and z\n");
        assertCleanParity("var a = x and y or z\n");
        assertCleanParity("var a = x in y and z\n");
        assertCleanParity("var a = -x * y\n");
        assertCleanParity("var a = not x and y\n");
        assertCleanParity("var a = 1 if c else 2 + 3\n");
        assertCleanParity("var a = x + 1 if c else y - 2\n");
        assertCleanParity("var a = x if c else y if d else z\n");
    }

    /// Control-flow statements share the same AST contract on both paths (§5.2 folding); every
    /// sample must serialize byte-identically, ranges included.
    @Test
    void controlFlowParity() {
        assertCleanParity("func f(x):\n\tif x > 0:\n\t\treturn 1\n\telif x < 0:\n\t\treturn -1\n\telse:\n\t\treturn 0\n");
        assertCleanParity("func f():\n\tif x: return 1\n\tpass\n");
        assertCleanParity("func f():\n\tif x:\n\t\tpass\n\telif y:\n\t\tpass\n\telif z:\n\t\tpass\n");
        assertCleanParity("func f():\n\twhile x < 3:\n\t\tbreak\n\t\tcontinue\n");
        assertCleanParity("func f():\n\tfor i in range(3):\n\t\tpass\n");
        assertCleanParity("func f():\n\tfor i: int in arr:\n\t\tpass\n");
        assertCleanParity("func f():\n\treturn\nfunc g():\n\treturn 1\n");
        assertCleanParity("func f():\n\tassert(x > 0, \"bad\")\n");
        assertCleanParity("func f():\n\tassert(x)\n\tbreakpoint\n");
        assertCleanParity("func f():\n\twhile true:\n\t\tif x:\n\t\t\tbreak\n\t\telif y:\n\t\t\tcontinue\n");
    }

    /// Nested suites, semicolon-joined single-line suites and suite boundaries around loops.
    @Test
    void suiteShapeParity() {
        assertCleanParity("func f():\n\tif x: pass; pass\n");
        assertCleanParity("func f():\n\tfor i in a:\n\t\tfor j in b:\n\t\t\tif j: break\n");
        assertCleanParity("func f():\n\tif a:\n\t\tpass\n\telse:\n\t\tif b:\n\t\t\tpass\n");
        assertCleanParity("func f():\n\twhile x:\n\t\tpass\n\twhile y: pass\n\tpass\n");
    }

    /// Match sections and every pattern form. Branch-level `pass` is excluded (§5.7 (ii): the
    /// reference path rejects it); key-only dictionary patterns and `@warning_ignore` are kept:
    /// both paths drop the same information, so the ASTs still match byte-identically.
    @Test
    void matchParity() {
        assertCleanParity("func f(v):\n\tmatch v:\n\t\t1:\n\t\t\tpass\n\t\tvar a:\n\t\t\tpass\n\t\t_:\n\t\t\tpass\n");
        assertCleanParity("func f(v):\n\tmatch v:\n\t\t1, 2:\n\t\t\tpass\n\t\t3 when ok:\n\t\t\tbreakpoint\n");
        assertCleanParity("func f(v):\n\tmatch v:\n\t\t[1, 2]:\n\t\t\tpass\n\t\t[_, var a, ..]:\n\t\t\tpass\n");
        assertCleanParity("func f(v):\n\tmatch v:\n\t\t[..]:\n\t\t\tpass\n");
        assertCleanParity("func f(v):\n\tmatch v:\n\t\t{\"k\": 1, \"j\": var b, ..}:\n\t\t\tpass\n");
        assertCleanParity("func f(v):\n\tmatch v:\n\t\t{\"name\"}:\n\t\t\tpass\n");
        assertCleanParity("func f(v):\n\tmatch v:\n\t\t[{\"k\": _}, [..]]:\n\t\t\tpass\n");
        assertCleanParity("func f(v):\n\tmatch v:\n\t\t1 + 2:\n\t\t\tpass\n\t\tCONST:\n\t\t\tpass\n\t\t'y':\n\t\t\tpass\n");
        assertCleanParity("func f(v):\n\tmatch v:\n\t\t1: pass\n\t\t_: pass\n");
        assertCleanParity("func f(v):\n\tmatch v:\n\t\t@warning_ignore(\"unused\")\n\t\t1:\n\t\t\tpass\n");
    }

    /// Lambdas: single-line and indented bodies, names, signatures, statement-level use, and
    /// lambdas nested in call parentheses (which exercise the expression-indented-block
    /// snapshot/restore path on both sides).
    @Test
    void lambdaParity() {
        assertCleanParity("var f = func(a): return a\n");
        assertCleanParity("var f = func(a):\n\treturn a\n");
        assertCleanParity("var f = func named(x) -> int:\n\treturn x\n");
        assertCleanParity("var g = func(a: int, b = 1): return a + b\n");
        assertCleanParity("var f = func(a):\n\tvar t = a\n\treturn t\n");
        assertCleanParity("var x = g(func(a): return a, 2)\n");
        assertCleanParity("var x = g(func():\n\treturn 1\n, 2)\n");
        assertCleanParity("func f():\n\tfunc(): pass\n\tpass\n");
        assertCleanParity("func f():\n\tfunc named(): pass\n");
        assertCleanParity("func f():\n\tfunc() -> int: return 1\n");
        assertCleanParity("var f = func(): return\n");
    }

    /// Comment/region attribution per §5.4: trailing, whole-line, dedent boundaries, annotation
    /// and static interleavings, single-line suites. Expression-internal comments are excluded
    /// (registered parity difference); header-line and match-level comments are kept (both paths
    /// drop them identically).
    @Test
    void commentAttributionParity() {
        assertCleanParity("#region 初始化\nvar x = 1 # 尾注释\n# 整行注释\nfunc f():\n\tpass # 尾\n#endregion\n");
        assertCleanParity("func f():\n\tpass\n# hello\nfunc g():\n\tpass\n");
        assertCleanParity("func f():\n\t# first\n\tpass\n");
        assertCleanParity("var x = 1\n# eof comment\n");
        assertCleanParity("@export\n# note\nvar x = 1\n");
        assertCleanParity("# note\n@export\nvar x = 1\n");
        assertCleanParity("class Inner:\n\tpass\n\t# inner\nvar x = 1\n");
        assertCleanParity("func f():\n\tpass\n# mid\n\tpass\n");
        assertCleanParity("func f():\n\tif x: pass # c\n\tpass\n");
        assertCleanParity("func f(): # c\n\tpass\n");
        assertCleanParity("func f(v):\n\tmatch v:\n\t\t# c\n\t\t1:\n\t\t\tpass\n");
        assertCleanParity("#regionfoo\nvar x = 1\n#endregion done\n# region\n#region\nvar y = 1\n");
        assertCleanParity("func f():\n\twhile x: # w\n\t\tbreak # b\n");
        assertCleanParity("func f():\n\tfor i in a: # f\n\t\tcontinue\n");
    }

    /// Cross-mechanism combinations that exercise the lambda state machine against calls,
    /// conditions, defaults and match tails (review-driven).
    @Test
    void lambdaInteractionParity() {
        assertCleanParity("func test():\n\tif f(func(): return 1, 2):\n\t\treturn 3\n");
        assertCleanParity("var x = g(func():\n\treturn 1\n\t, 2)\n");
        assertCleanParity("func f(cb = func():\n\treturn 1\n):\n\tpass\n");
        assertCleanParity("func f(v):\n\tmatch v:\n\t\t_: pass\n# outer\nvar after = 1\n");
        assertCleanParity("func f():\n\treturn func(): return 1\n\t+ 2\n");
        assertCleanParity("func f(v):\n\tmatch v:\n\t\t_: pass\n\t\t# branch\n\treturn 3\n");
        assertCleanParity("var x = g(func():\n\t# outerBody\n\t@custom(func(): return 2)\n\tvar y = 1\n\treturn y\n)\n");
        assertCleanParity("# before\n@first\n@custom(func(): return 2)\nvar x = 1\n");
        assertCleanParity("@first\n@custom(func(cb = func(): return 1): return cb)\nvar x = 1\n");
    }
}
