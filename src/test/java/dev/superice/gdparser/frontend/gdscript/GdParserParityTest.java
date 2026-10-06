package dev.superice.gdparser.frontend.gdscript;

import org.junit.jupiter.api.Test;

/// Dual-path parity for the declaration layer: program prefix, class-body declarations,
/// suites, annotations, types and the declaration-scoped expression subset. All snippets
/// belong to the "both paths support, structure identical" parity class; registered
/// divergences (docstring consumption, in-body `extends`, precedence corner combos) are
/// intentionally excluded here and covered by dedicated Godot-structure tests.
class GdParserParityTest extends GdParserParityTestSupport {

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
}
