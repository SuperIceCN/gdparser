package dev.superice.gdparser.frontend.gdscript;

import dev.superice.gdparser.frontend.ast.AstDiagnostic;
import dev.superice.gdparser.frontend.ast.AstDiagnosticSeverity;
import dev.superice.gdparser.frontend.ast.AstMappingResult;
import dev.superice.gdparser.frontend.ast.BinaryExpression;
import dev.superice.gdparser.frontend.ast.CommentStatement;
import dev.superice.gdparser.frontend.ast.ConditionalExpression;
import dev.superice.gdparser.frontend.ast.Expression;
import dev.superice.gdparser.frontend.ast.ExpressionStatement;
import dev.superice.gdparser.frontend.ast.FunctionDeclaration;
import dev.superice.gdparser.frontend.ast.IdentifierExpression;
import dev.superice.gdparser.frontend.ast.LambdaExpression;
import dev.superice.gdparser.frontend.ast.LiteralExpression;
import dev.superice.gdparser.frontend.ast.Node;
import dev.superice.gdparser.frontend.ast.PassStatement;
import dev.superice.gdparser.frontend.ast.Point;
import dev.superice.gdparser.frontend.ast.Range;
import dev.superice.gdparser.frontend.ast.RegionDirectiveStatement;
import dev.superice.gdparser.frontend.ast.SourceFile;
import dev.superice.gdparser.frontend.ast.UnaryExpression;
import dev.superice.gdparser.frontend.ast.VariableDeclaration;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

/// Exercises whole files and combinations of GDScript syntax without parse errors.
class GdScriptParserCorpusTest {

    private static final Path FIXTURE_DIR = Path.of("src", "test", "resources", "gdscript");

    @TestFactory
    List<DynamicTest> fixturesParseWithLfAndCrlf() throws IOException {
        List<Path> scripts;
        try (var files = Files.list(FIXTURE_DIR)) {
            scripts = files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".gd"))
                    .sorted()
                    .toList();
        }
        assertEquals(13, scripts.size(), "fixture corpus size changed");

        var tests = new ArrayList<DynamicTest>();
        for (var script : scripts) {
            var lf = Files.readString(script, StandardCharsets.UTF_8).replace("\r\n", "\n");
            tests.add(dynamicTest(script.getFileName() + " (LF)", () -> parseFixture(lf)));
            tests.add(dynamicTest(script.getFileName() + " (CRLF)", () -> {
                var crlf = lf.replace("\n", "\r\n");
                assertCrlfAst(parseFixture(lf), parseFixture(crlf), crlfOffsets(lf, crlf), "source");
            }));
        }
        return List.copyOf(tests);
    }

    private static SourceFile parseFixture(String source) {
        var result = parse(source);
        assertNoErrors(source, result);
        assertFalse(result.ast().statements().isEmpty(), "fixture produced an empty AST");
        return result.ast();
    }

    private record CrlfOffsets(byte[] lf, byte[] crlf, int[] priorLineFeeds) {
        int offset(int lfOffset) {
            return lfOffset + priorLineFeeds[lfOffset];
        }

        boolean commentHasInsertedCr(int lfEnd, String field) {
            if (lfEnd == lf.length) {
                return false;
            }
            assertEquals((byte) '\n', lf[lfEnd], field + ": LF comment must end immediately before LF");
            var crlfOffset = offset(lfEnd);
            assertEquals((byte) '\r', crlf[crlfOffset], field + ": inserted CR at the mapped comment end");
            assertEquals((byte) '\n', crlf[crlfOffset + 1], field + ": LF immediately after the inserted CR");
            return true;
        }
    }

    private static CrlfOffsets crlfOffsets(String lf, String crlf) {
        var lfBytes = lf.getBytes(StandardCharsets.UTF_8);
        var priorLineFeeds = new int[lfBytes.length + 1];
        for (var i = 0; i < lfBytes.length; i++) {
            priorLineFeeds[i + 1] = priorLineFeeds[i] + (lfBytes[i] == '\n' ? 1 : 0);
        }
        return new CrlfOffsets(lfBytes, crlf.getBytes(StandardCharsets.UTF_8), priorLineFeeds);
    }

    private static void assertCrlfAst(Object lf, Object crlf, CrlfOffsets offsets, String field)
            throws ReflectiveOperationException {
        switch (lf) {
            case List<?> expected -> {
                var actual = assertInstanceOf(List.class, crlf, field);
                assertEquals(expected.size(), actual.size(), field + ".size");
                for (var i = 0; i < expected.size(); i++) {
                    assertCrlfAst(expected.get(i), actual.get(i), offsets, field + "[" + i + "]");
                }
            }
            case Node expected -> {
                var actual = assertInstanceOf(expected.getClass(), crlf, field);
                var commentEndsAtCr = endsAtComment(expected)
                        && offsets.commentHasInsertedCr(expected.range().endByte(), field);
                assertCrlfRange(expected.range(), actual.range(), offsets, commentEndsAtCr, field + ".range");
                if (expected instanceof CommentStatement comment && actual instanceof CommentStatement actualComment) {
                    assertEquals(comment.text() + (commentEndsAtCr ? "\r" : ""), actualComment.text(), field + ".text");
                } else {
                    for (var component : expected.getClass().getRecordComponents()) {
                        if (!component.getName().equals("range")) {
                            var accessor = component.getAccessor();
                            assertCrlfAst(accessor.invoke(expected), accessor.invoke(actual), offsets,
                                    field + "." + component.getName());
                        }
                    }
                }
            }
            case String expected -> {
                var actual = assertInstanceOf(String.class, crlf, field);
                // Normalize physical line endings in raw fields, not in serialized or escaped text.
                assertTrue(expected.equals(actual) || expected.equals(actual.replace("\r\n", "\n")),
                        () -> field + ": expected <" + expected + "> but was <" + actual + ">");
            }
            case null, default -> assertEquals(lf, crlf, field);
        }
    }

    private static void assertCrlfRange(Range lf, Range crlf, CrlfOffsets offsets, boolean includesCr, String field) {
        var extraEndByte = includesCr ? 1 : 0;
        assertEquals(lf.startPoint(), crlf.startPoint(), field + ".startPoint");
        assertEquals(new Point(lf.endPoint().row(), lf.endPoint().column() + extraEndByte),
                crlf.endPoint(), field + ".endPoint");
        assertEquals(offsets.offset(lf.startByte()), crlf.startByte(), field + ".startByte");
        assertEquals(offsets.offset(lf.endByte()) + extraEndByte, crlf.endByte(), field + ".endByte");
    }

    private static boolean endsAtComment(Node node) {
        if (node instanceof CommentStatement || node instanceof RegionDirectiveStatement) {
            return true;
        }
        // A container inherits the physical comment end only along its last-child endpoint chain.
        var children = node.getChildren();
        return !children.isEmpty()
                && children.getLast().range().endByte() == node.range().endByte()
                && endsAtComment(children.getLast());
    }

    @Test
    void programPrefixParsesWithoutErrors() {
        assertParsesWithoutErrors("class_name Foo\n");
        assertParsesWithoutErrors("extends Node\n");
        assertParsesWithoutErrors("class_name Foo extends Node\n");
        assertParsesWithoutErrors("extends Node\nclass_name Foo\n");
        assertParsesWithoutErrors("extends \"res://base.gd\"\n");
        assertParsesWithoutErrors("extends Foo.Bar\n");
        assertParsesWithoutErrors("");
        assertTrue(parse("\n").ast().statements().isEmpty());
    }

    @Test
    void variableAndConstantParsesWithoutErrors() {
        assertParsesWithoutErrors("var x\n");
        assertParsesWithoutErrors("var x = 1\n");
        assertParsesWithoutErrors("var x: int\n");
        assertParsesWithoutErrors("var x: int = 1\n");
        assertParsesWithoutErrors("var x := 1\n");
        assertParsesWithoutErrors("var x : = 1\n");
        assertParsesWithoutErrors("static var x\n");
        assertParsesWithoutErrors("const A = 1\n");
        assertParsesWithoutErrors("const A: int = 1\n");
        assertParsesWithoutErrors("const A := 1\n");
    }

    @Test
    void signalParsesWithoutErrors() {
        assertParsesWithoutErrors("signal sig\n");
        assertParsesWithoutErrors("signal sig(a, b: int)\n");
        assertParsesWithoutErrors("signal sig(\n\ta,\n\tb: int,\n)\n");
    }

    @Test
    void enumParsesWithoutErrors() {
        assertParsesWithoutErrors("enum {A, B, C}\n");
        assertParsesWithoutErrors("enum {A = 1, B = 2}\n");
        assertParsesWithoutErrors("enum Name {A, B}\n");
        assertParsesWithoutErrors("enum {A,}\n");
        assertParsesWithoutErrors("enum {\n\tA,\n\tB = 2,\n}\n");
    }

    @Test
    void functionParsesWithoutErrors() {
        assertParsesWithoutErrors("func f(): pass\n");
        assertParsesWithoutErrors("func f(a, b: int, c = 1, d: String = \"x\"): pass\n");
        assertParsesWithoutErrors("func f(a, ...args): pass\n");
        assertParsesWithoutErrors("func f() -> void: pass\n");
        assertParsesWithoutErrors("func f() -> int: pass\n");
        assertParsesWithoutErrors("static func f(): pass\n");
        assertParsesWithoutErrors("func f()\n");
        assertParsesWithoutErrors("func f(): pass; pass\n");
        assertParsesWithoutErrors("func f():\n\tvar x = 1\n\tpass\n");
        assertParsesWithoutErrors("func f(\n\ta,\n\tb: int,\n) -> void: pass\n");
    }

    @Test
    void constructorParsesWithoutErrors() {
        assertParsesWithoutErrors("func _init(): pass\n");
        assertParsesWithoutErrors("func _init() -> void: pass\n");
        assertParsesWithoutErrors("func _init(a, b := 1): pass\n");
    }

    @Test
    void nestedClassParsesWithoutErrors() {
        assertParsesWithoutErrors("class Inner:\n\tpass\n");
        assertParsesWithoutErrors("class Inner extends Base:\n\tpass\n");
        assertParsesWithoutErrors("class Inner: pass\n");
    }

    @Test
    void annotationParsesWithoutErrors() {
        assertParsesWithoutErrors("@export var x = 1\n");
        assertParsesWithoutErrors("@export\nvar x = 1\n");
        assertParsesWithoutErrors("@tool\nclass_name Foo\n");
        assertParsesWithoutErrors("@rpc(\"any_peer\", \"call_local\")\nfunc f(): pass\n");
        assertParsesWithoutErrors("@rpc(\n\t\"any_peer\",\n)\nfunc g(): pass\n");
        assertParsesWithoutErrors("@export_enum(\n\t\"a\",\n)\nvar x = 1\n");
    }

    @Test
    void typeParsesWithoutErrors() {
        assertParsesWithoutErrors("var x: Array[int]\n");
        assertParsesWithoutErrors("var d: Dictionary[String, int]\n");
        assertParsesWithoutErrors("var p: Foo.Bar\n");
        assertParsesWithoutErrors("func f(a: Array[int], b: Dictionary[String, int]) -> Array[int]: pass\n");
    }

    @Test
    void propertyConsumptionParsesWithoutErrors() {
        assertParsesWithoutErrors("var x: int: get = a, set = b\n");
        assertParsesWithoutErrors("var x: int: set = b, get = a\n");
    }

    @Test
    void declarationExpressionParsesWithoutErrors() {
        assertParsesWithoutErrors("var x = -1\n");
        assertParsesWithoutErrors("var x = +1\n");
        assertParsesWithoutErrors("const A = 1 + 2 * 3\n");
        assertParsesWithoutErrors("const A = 2 ** 3 ** 2\n");
        assertParsesWithoutErrors("const A = f(1, 2)\n");
        assertParsesWithoutErrors("const A = a.b.c\n");
        assertParsesWithoutErrors("const A = a[0]\n");
        assertParsesWithoutErrors("const A = PI\n");
        assertParsesWithoutErrors("const A = a.b(1).c[2]\n");
        assertParsesWithoutErrors("const A = f(1).x\n");
        assertParsesWithoutErrors("const A = a[0][1]\n");
        assertParsesWithoutErrors("const A = a.b.c.d.e.f.g.h\n");
        assertParsesWithoutErrors("const A = a.b(\n\t1\n).c\n");
    }

    @Test
    void invalidDirectCallsReportOneDiagnostic() {
        for (var source : List.of("const A = f(1)(2)\n", "const A = a.b(1)(2)\n")) {
            var actual = parse(source);
            assertEquals(List.of(DiagnosticCodes.CALL_INVALID_CALLEE),
                    actual.diagnostics().stream().map(AstDiagnostic::code).toList());
        }
    }

    @Test
    void damagedAssignmentAndCallKeepOnlyPrimaryErrorsWithoutRelaxingHealthyParsesWithoutErrors() {
        var source = "func f():\n\t(a ?) = 2\n\tx as = 2\n\t(a ?)(2)\n";
        var result = parse(source);
        assertEquals(List.of(DiagnosticCodes.SYNTAX_UNEXPECTED_TOKEN, DiagnosticCodes.MISSING_TYPE_CAST,
                        DiagnosticCodes.SYNTAX_UNEXPECTED_TOKEN),
                result.diagnostics().stream().map(AstDiagnostic::code).toList());
        assertParsesWithoutErrors("func f():\n\t(x) = 2\n\tf()[0] = 2\n\t(f)(1)\n");
    }

    @Test
    void containerLiteralParsesWithoutErrors() {
        assertParsesWithoutErrors("var a = [1, 2, 3]\n");
        assertParsesWithoutErrors("var a = []\n");
        assertParsesWithoutErrors("var a = [1, 2,]\n");
        assertParsesWithoutErrors("var a = [[1], [2]]\n");
        assertParsesWithoutErrors("var a = [\n\t1,\n\t2,\n]\n");
        assertParsesWithoutErrors("var d = {\"a\": 1, b: 2}\n");
        assertParsesWithoutErrors("var d = {}\n");
        assertParsesWithoutErrors("var d = {\"a\": 1,}\n");
        assertParsesWithoutErrors("var d = {x = 1}\n");
        assertParsesWithoutErrors("var d = {\"name\" = 1}\n");
        assertParsesWithoutErrors("var d = {x = 1, y = 2}\n");
        assertParsesWithoutErrors("var d = {\n\t\"a\": 1,\n\tb: 2,\n}\n");
    }

    @Test
    void dedicatedExpressionFormParsesWithoutErrors() {
        assertParsesWithoutErrors("var n = $Child\n");
        assertParsesWithoutErrors("var n = $\"a/b\"\n");
        assertParsesWithoutErrors("var n = $/root\n");
        assertParsesWithoutErrors("var n = $A/B\n");
        assertParsesWithoutErrors("var n = %Unique\n");
        assertParsesWithoutErrors("const P = preload(\"res://a.gd\")\n");
        assertParsesWithoutErrors("func f():\n\tawait foo()\n");
        assertParsesWithoutErrors("func f():\n\tawait get_tree().create_timer(1).timeout\n");
        assertParsesWithoutErrors("func f():\n\tsuper()\n");
        assertParsesWithoutErrors("func f():\n\tsuper.foo(1)\n");
        assertParsesWithoutErrors("var a = x as int\n");
        assertParsesWithoutErrors("var a = x as Array[int]\n");
        assertParsesWithoutErrors("var a = x is Node\n");
        assertParsesWithoutErrors("var a = x is not Node\n");
        assertParsesWithoutErrors("var a = 1 if c else 2\n");
        assertParsesWithoutErrors("var a = x not in y\n");
        assertParsesWithoutErrors("var a = not b\n");
        assertParsesWithoutErrors("var a = !b\n");
        assertParsesWithoutErrors("var a = ~x\n");
    }

    @Test
    void groupingParsesWithoutErrors() {
        assertParsesWithoutErrors("const A = (1 + 2) * 3\n");
        assertParsesWithoutErrors("var a = ((1))\n");
        assertParsesWithoutErrors("var a = -(x)\n");
        assertParsesWithoutErrors("var a = (x) + (y)\n");
        assertParsesWithoutErrors("func f():\n\tawait (x)\n");
        assertParsesWithoutErrors("func f():\n\t(f)(1)\n");
        assertParsesWithoutErrors("var a = (x) if c else (y)\n");
        assertParsesWithoutErrors("var d = {(a): 1}\n");
        assertParsesWithoutErrors("var a = [(1), (2)]\n");
    }

    @Test
    void assignmentParsesWithoutErrors() {
        assertParsesWithoutErrors("func f():\n\tx = 1\n");
        assertParsesWithoutErrors("func f():\n\tx += 1\n");
        assertParsesWithoutErrors("func f():\n\tx -= 1\n");
        assertParsesWithoutErrors("func f():\n\tx *= 2\n");
        assertParsesWithoutErrors("func f():\n\tx **= 2\n");
        assertParsesWithoutErrors("func f():\n\tx /= 2\n");
        assertParsesWithoutErrors("func f():\n\tx %= 2\n");
        assertParsesWithoutErrors("func f():\n\tx <<= 1\n");
        assertParsesWithoutErrors("func f():\n\tx >>= 1\n");
        assertParsesWithoutErrors("func f():\n\tx &= 1\n");
        assertParsesWithoutErrors("func f():\n\tx |= 1\n");
        assertParsesWithoutErrors("func f():\n\tx ^= 1\n");
        assertParsesWithoutErrors("func f():\n\ta.b = 1\n");
        assertParsesWithoutErrors("func f():\n\ta[0] = 1\n");
        assertParsesWithoutErrors("func f():\n\ta = b + c\n");
        assertParsesWithoutErrors("func f():\n\ta = b if c else d\n");
    }

    @Test
    void operatorPrecedenceMatrixFollowsGodotGrouping() {
        assertExpressionShape("x + y * z", "(+ x (* y z))");
        assertExpressionShape("x * y + z", "(+ (* x y) z)");
        assertExpressionShape("x ** y ** z", "(** (** x y) z)");
        assertExpressionShape("x ** y * z", "(* (** x y) z)");
        assertExpressionShape("x << y + z", "(<< x (+ y z))");
        assertExpressionShape("x + y << z", "(<< (+ x y) z)");
        assertExpressionShape("x & y == z", "(== (& x y) z)");
        assertExpressionShape("x | y ^ z", "(| x (^ y z))");
        assertExpressionShape("x < y == z", "(== (< x y) z)");
        assertExpressionShape("x == y and z", "(and (== x y) z)");
        assertExpressionShape("x and y or z", "(or (and x y) z)");
        assertExpressionShape("x in y and z", "(and (in x y) z)");
        assertExpressionShape("-x * y", "(* (- x) y)");
        assertExpressionShape("not x and y", "(and (not x) y)");
        assertExpressionShape("1 if c else 2 + 3", "(if c (literal 1) (+ (literal 2) (literal 3)))");
        assertExpressionShape("x + 1 if c else y - 2", "(if c (+ x (literal 1)) (- y (literal 2)))");
        assertExpressionShape("x if c else y if d else z", "(if c x (if d y z))");
    }

    private static void assertExpressionShape(String expression, String expectedShape) {
        var source = "var a = " + expression + "\n";
        var result = parse(source);
        assertNoErrors(source, result);
        var statements = result.ast().statements();
        assertEquals(1, statements.size(), source);
        var declaration = assertInstanceOf(VariableDeclaration.class, statements.getFirst(), source);
        assertEquals(expectedShape, expressionShape(declaration.value()), source);
    }

    private static String expressionShape(Expression expression) {
        return switch (expression) {
            case BinaryExpression binary -> "(" + binary.operator() + " " + expressionShape(binary.left()) + " "
                    + expressionShape(binary.right()) + ")";
            case UnaryExpression unary -> "(" + unary.operator() + " " + expressionShape(unary.operand()) + ")";
            case ConditionalExpression conditional ->
                    "(if " + expressionShape(conditional.condition()) + " " + expressionShape(conditional.left()) + " "
                            + expressionShape(conditional.right()) + ")";
            case IdentifierExpression identifier -> identifier.name();
            case LiteralExpression literal -> "(literal " + literal.sourceText() + ")";
            case null, default -> throw new AssertionError("Unexpected expression: " + expression);
        };
    }

    @Test
    void controlFlowParsesWithoutErrors() {
        assertParsesWithoutErrors("func f(x):\n\tif x > 0:\n\t\treturn 1\n\telif x < 0:\n\t\treturn -1\n\telse:\n\t\treturn 0\n");
        assertParsesWithoutErrors("func f():\n\tif x: return 1\n\tpass\n");
        assertParsesWithoutErrors("func f():\n\tif x:\n\t\tpass\n\telif y:\n\t\tpass\n\telif z:\n\t\tpass\n");
        assertParsesWithoutErrors("func f():\n\twhile x < 3:\n\t\tbreak\n\t\tcontinue\n");
        assertParsesWithoutErrors("func f():\n\tfor i in range(3):\n\t\tpass\n");
        assertParsesWithoutErrors("func f():\n\tfor i: int in arr:\n\t\tpass\n");
        assertParsesWithoutErrors("func f():\n\treturn\nfunc g():\n\treturn 1\n");
        assertParsesWithoutErrors("func f():\n\tassert(x > 0, \"bad\")\n");
        assertParsesWithoutErrors("func f():\n\tassert(x)\n\tbreakpoint\n");
        assertParsesWithoutErrors("func f():\n\twhile true:\n\t\tif x:\n\t\t\tbreak\n\t\telif y:\n\t\t\tcontinue\n");
    }

    @Test
    void suiteShapeParsesWithoutErrors() {
        assertParsesWithoutErrors("func f():\n\tif x: pass; pass\n");
        assertParsesWithoutErrors("func f():\n\tfor i in a:\n\t\tfor j in b:\n\t\t\tif j: break\n");
        assertParsesWithoutErrors("func f():\n\tif a:\n\t\tpass\n\telse:\n\t\tif b:\n\t\t\tpass\n");
        assertParsesWithoutErrors("func f():\n\twhile x:\n\t\tpass\n\twhile y: pass\n\tpass\n");
    }

    @Test
    void matchParsesWithoutErrors() {
        assertParsesWithoutErrors("func f(v):\n\tmatch v:\n\t\t1:\n\t\t\tpass\n\t\tvar a:\n\t\t\tpass\n\t\t_:\n\t\t\tpass\n");
        assertParsesWithoutErrors("func f(v):\n\tmatch v:\n\t\t1, 2:\n\t\t\tpass\n\t\t3 when ok:\n\t\t\tbreakpoint\n");
        assertParsesWithoutErrors("func f(v):\n\tmatch v:\n\t\t[1, 2]:\n\t\t\tpass\n\t\t[_, var a, ..]:\n\t\t\tpass\n");
        assertParsesWithoutErrors("func f(v):\n\tmatch v:\n\t\t[..]:\n\t\t\tpass\n");
        assertParsesWithoutErrors("func f(v):\n\tmatch v:\n\t\t{\"k\": 1, \"j\": var b, ..}:\n\t\t\tpass\n");
        assertParsesWithoutErrors("func f(v):\n\tmatch v:\n\t\t{\"name\"}:\n\t\t\tpass\n");
        assertParsesWithoutErrors("func f(v):\n\tmatch v:\n\t\t[{\"k\"}]:\n\t\t\tpass\n");
        assertParsesWithoutErrors("func f(v):\n\tmatch v:\n\t\t{\"a\": {\"b\"}}:\n\t\t\tpass\n");
        assertParsesWithoutErrors("func f(v):\n\tmatch v:\n\t\t[{\"k\": _}, [..]]:\n\t\t\tpass\n");
        assertParsesWithoutErrors("func f(v):\n\tmatch v:\n\t\t1 + 2:\n\t\t\tpass\n\t\tCONST:\n\t\t\tpass\n\t\t'y':\n\t\t\tpass\n");
        assertParsesWithoutErrors("func f(v):\n\tmatch v:\n\t\t1: pass\n\t\t_: pass\n");
        assertParsesWithoutErrors("func f(v):\n\tmatch v:\n\t\t@warning_ignore(\"unused\")\n\t\t1:\n\t\t\tpass\n");
    }

    @Test
    void lambdaParsesWithoutErrors() {
        assertParsesWithoutErrors("var f = func(a): return a\n");
        assertParsesWithoutErrors("var f = func(a):\n\treturn a\n");
        assertParsesWithoutErrors("var f = func named(x) -> int:\n\treturn x\n");
        assertParsesWithoutErrors("var g = func(a: int, b = 1): return a + b\n");
        assertParsesWithoutErrors("var f = func(a):\n\tvar t = a\n\treturn t\n");
        assertParsesWithoutErrors("var x = g(func(a): return a, 2)\n");
        assertParsesWithoutErrors("var x = g(func():\n\treturn 1\n, 2)\n");
        assertParsesWithoutErrors("var f = func(): return\n");
    }

    @Test
    void bareSuiteLambdaRecoversAsAnExpressionStatement() {
        for (var source : List.of(
                "func f():\n\tfunc(): pass\n\tpass\n",
                "func f():\n\tfunc named(): pass\n",
                "func f():\n\tfunc() -> int: return 1\n"
        )) {
            var result = parse(source);
            var function = assertInstanceOf(FunctionDeclaration.class, result.ast().statements().getFirst());
            var statement = assertInstanceOf(ExpressionStatement.class, function.body().statements().getFirst());
            assertInstanceOf(LambdaExpression.class, statement.expression());
            assertTrue(result.diagnostics().stream().anyMatch(d -> "parse.lambda.bare_statement".equals(d.code())));
        }
    }

    @Test
    void groupedBareLambdaRetainsItsShapeAndReportsAnError() {
        var source = "func f():\n\t(func(): pass)\n\tpass\n";
        var result = parse(source);
        var fn = assertInstanceOf(FunctionDeclaration.class, result.ast().statements().getFirst());
        var statement = assertInstanceOf(ExpressionStatement.class, fn.body().statements().getFirst());
        var lambda = assertInstanceOf(LambdaExpression.class, statement.expression());
        assertInstanceOf(PassStatement.class, fn.body().statements().getLast());
        assertEquals(1, result.diagnostics().size());
        var diagnostic = result.diagnostics().getFirst();
        assertEquals(DiagnosticCodes.LAMBDA_BARE_STATEMENT, diagnostic.code());
        assertEquals(lambda.range(), diagnostic.range());
        assertSame(statement, diagnostic.recovery().owner());
        assertEquals("expression", diagnostic.recovery().facet());
    }

    @Test
    void commentAttributionParsesWithoutErrors() {
        assertParsesWithoutErrors("#region 初始化\nvar x = 1 # 尾注释\n# 整行注释\nfunc f():\n\tpass # 尾\n#endregion\n");
        assertParsesWithoutErrors("func f():\n\tpass\n# hello\nfunc g():\n\tpass\n");
        assertParsesWithoutErrors("func f():\n\t# first\n\tpass\n");
        assertParsesWithoutErrors("var x = 1\n# eof comment\n");
        assertParsesWithoutErrors("@export\n# note\nvar x = 1\n");
        assertParsesWithoutErrors("# note\n@export\nvar x = 1\n");
        assertParsesWithoutErrors("class Inner:\n\tpass\n\t# inner\nvar x = 1\n");
        assertParsesWithoutErrors("func f():\n\tpass\n# mid\n\tpass\n");
        assertParsesWithoutErrors("func f():\n\tif x: pass # c\n\tpass\n");
        assertParsesWithoutErrors("func f(): # c\n\tpass\n");
        assertParsesWithoutErrors("func f(): # c  \n\tpass\n");
        assertParsesWithoutErrors("func f(): # c  \r\n\tpass\r\n");
        assertParsesWithoutErrors("func f():   \r\n\t# body comment\r\n\tpass\r\n");
        assertParsesWithoutErrors("class Inner:  \r\n\tpass\r\n");
        assertParsesWithoutErrors("func f(v):\n\tmatch v:\n\t\t# c\n\t\t1:\n\t\t\tpass\n");
        assertParsesWithoutErrors("#regionfoo\nvar x = 1\n#endregion done\n# region\n#region\nvar y = 1\n");
        assertParsesWithoutErrors("func f():\n\twhile x: # w\n\t\tbreak # b\n");
        assertParsesWithoutErrors("func f():\n\tfor i in a: # f\n\t\tcontinue\n");
    }

    @Test
    void lambdaInteractionParsesWithoutErrors() {
        assertParsesWithoutErrors("func test():\n\tif f(func(): return 1, 2):\n\t\treturn 3\n");
        assertParsesWithoutErrors("var x = g(func():\n\treturn 1\n\t, 2)\n");
        assertParsesWithoutErrors("func f(cb = func():\n\treturn 1\n):\n\tpass\n");
        assertParsesWithoutErrors("func f(v):\n\tmatch v:\n\t\t_: pass\n# outer\nvar after = 1\n");
        assertParsesWithoutErrors("func f():\n\treturn func(): return 1\n\t+ 2\n");
        assertParsesWithoutErrors("func f(v):\n\tmatch v:\n\t\t_: pass\n\t\t# branch\n\treturn 3\n");
        assertParsesWithoutErrors("var x = g(func():\n\t# outerBody\n\t@custom(func(): return 2)\n\tvar y = 1\n\treturn y\n)\n");
        assertParsesWithoutErrors("# before\n@first\n@custom(func(): return 2)\nvar x = 1\n");
        assertParsesWithoutErrors("@first\n@custom(func(cb = func(): return 1): return cb)\nvar x = 1\n");
    }

    private static AstMappingResult parse(String source) {
        return new GdScriptParser().parse(source);
    }

    private static void assertParsesWithoutErrors(String source) {
        assertNoErrors(source, parse(source));
    }

    private static void assertNoErrors(String source, AstMappingResult result) {
        var errors = result.diagnostics().stream()
                .filter(diagnostic -> diagnostic.severity() == AstDiagnosticSeverity.ERROR)
                .toList();
        assertEquals(List.of(), errors, () -> "Unexpected errors in source:\n" + source);
    }
}
