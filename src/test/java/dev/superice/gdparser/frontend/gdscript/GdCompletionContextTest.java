package dev.superice.gdparser.frontend.gdscript;

import dev.superice.gdparser.frontend.ast.AttributeExpression;
import dev.superice.gdparser.frontend.ast.IdentifierExpression;
import dev.superice.gdparser.frontend.ast.Node;
import dev.superice.gdparser.frontend.ast.Point;
import dev.superice.gdparser.frontend.ast.Range;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

import static dev.superice.gdparser.frontend.gdscript.CompletionContext.Kind.CALL_ARGUMENT;
import static dev.superice.gdparser.frontend.gdscript.CompletionContext.Kind.IDENTIFIER;
import static dev.superice.gdparser.frontend.gdscript.CompletionContext.Kind.MEMBER_ACCESS;
import static dev.superice.gdparser.frontend.gdscript.CompletionContext.Kind.TYPE_POSITION;
import static dev.superice.gdparser.frontend.gdscript.CompletionContext.Kind.UNKNOWN;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// The numbered legacy cases retain the old public completion contract. Additional cases
/// exercise recovery independently of whether an expression survives in the tolerant AST.
class GdCompletionContextTest {

    private static final GdScriptParserFacade FACADE = new GdScriptParserFacade();

    private record Case(String markedSource, CompletionContext.Kind kind, String replacement, String receiver) {
    }

    @Test
    void legacy01MemberPrefix() {
        assertCase(member("func f():\n\tnode.pa|", "pa", "node"));
    }

    @Test
    void legacy02CompleteMemberWithCursorInsideName() {
        assertCase(member("func f():\n\tvar x = node.pa|r\n\tpass", "par", "node"));
    }

    @Test
    void legacy03BareDotAtEof() {
        assertCase(member("func f():\n\tnode.|", "", "node"));
    }

    @Test
    void legacy04MissingMemberBeforeOperator() {
        assertCase(member("func f():\n\tvar x = node.| + 1", "", "node"));
    }

    @Test
    void legacy05CursorAfterDotBeforeName() {
        assertCase(member("func f():\n\tnode.|pa", "pa", "node"));
    }

    @Test
    void legacy06WholeChainReceiver() {
        assertCase(member("func f():\n\ta.b.c|", "c", "a.b"));
    }

    @Test
    void legacy07ChainBaseIdentifier() {
        assertCase(plain("func f():\n\tno|de.pa", IDENTIFIER, "node"));
    }

    @Test
    void legacy08CallStepMethodName() {
        assertCase(member("func f():\n\tnode.met|hod(1)", "method", "node"));
    }

    @Test
    void legacy09DotBeforeCallStep() {
        assertCase(member("func f():\n\tnode.|method(1)", "method", "node"));
    }

    @Test
    void legacy10ChainBeforeCallStep() {
        assertCase(member("func f():\n\ta.b.met|hod(1)", "method", "a.b"));
    }

    @Test
    void legacy11StatementPrefix() {
        assertCase(plain("func f():\n\tpri|", IDENTIFIER, "pri"));
    }

    @Test
    void legacy12OpenCallAtEof() {
        assertCase(plain("func f():\n\tnode.f(|", CALL_ARGUMENT, ""));
    }

    @Test
    void legacy13EmptyCall() {
        assertCase(plain("func f():\n\tnode.f(|)", CALL_ARGUMENT, ""));
    }

    @Test
    void legacy14AfterArgumentComma() {
        assertCase(plain("func f():\n\tnode.f(1, |)", CALL_ARGUMENT, ""));
    }

    @Test
    void legacy15VariableType() {
        assertCase(plain("func f():\n\tvar x: Fla|", TYPE_POSITION, "Fla"));
    }

    @Test
    void legacy16ExtendsType() {
        assertCase(plain("extends Fla|", TYPE_POSITION, "Fla"));
    }

    @Test
    void legacy17CastType() {
        assertCase(plain("func f():\n\tx as Fla|", TYPE_POSITION, "Fla"));
    }

    @Test
    void legacy18StringLiteral() {
        assertCase(plain("func f():\n\tvar s = \"hel|lo\"", UNKNOWN, ""));
    }

    @Test
    void legacy19WhitespaceOnly() {
        assertCase(plain("func f():\n\t|", UNKNOWN, ""));
    }

    @Test
    void legacy20EmptySource() {
        assertCase(plain("|", UNKNOWN, ""));
    }

    @Test
    void legacy21ClampedOffsets() {
        var source = "func f():\n\tnode.pa";
        var before = FACADE.parseCompletionContext(source, Long.MIN_VALUE);
        assertEquals(UNKNOWN, before.kind());
        assertEquals(new Range(0, 0, new Point(0, 0), new Point(0, 0)), before.replaceableRange());
        assertEquals(FACADE.parseCompletionContext(source, source.length()),
                FACADE.parseCompletionContext(source, Long.MAX_VALUE));
    }

    @ParameterizedTest
    @MethodSource("recoveryCases")
    void recoveryDrivenContexts(Case testCase) {
        assertCase(testCase);
    }

    static Stream<Case> recoveryCases() {
        return Stream.of(
                member("super.|", "", "super"),
                member("super.ba|", "ba", "super"),
                member("self.b.|(", "", "self.b"),
                member("self.b.(|", "", "self.b"),
                plain("self.b.(1|", CALL_ARGUMENT, ""),
                plain("self.b.(1,|", CALL_ARGUMENT, ""),
                member("self.b.|(1)", "", "self.b"),
                member("node.method|(", "method", "node"),
                member("node.method|(1,", "method", "node"),
                member("(node).method|(", "method", "(node)"),
                member("$Child.method|(", "method", "$Child"),
                member("$Root/Child.method|(", "method", "$Root/Child"),
                member("$/Root/Child.method|(", "method", "$/Root/Child"),
                member("items[0].method|(", "method", "items[0]"),
                member("make().method|(", "method", "make()"),
                member("a.b.| c(", "c", "a.b"),
                member("(node).pa|", "pa", "(node)"),
                plain("var x = g(func(): a.\n\tb|\n)", IDENTIFIER, "b"),
                plain("var x = g(\n\tfunc():\n\t\ta.\n\t\tb|\n)", IDENTIFIER, "b"),
                plain("var x = g(\n\tfunc():\n\ta.\n\tb|\n)", IDENTIFIER, "b"),
                member("var x = (a.\n\tb|)", "b", "a"),
                member("var x = g(func(): pass,\n\tnode.pa|)", "pa", "node"),
                plain("target(na|me, 1+)", IDENTIFIER, "name"),
                member("obj.class|(1+) + 2", "class", "obj")
        );
    }

    @ParameterizedTest
    @MethodSource("typeAndCallCases")
    void typeAndCallTokenFallbacks(Case testCase) {
        assertCase(testCase);
    }

    static Stream<Case> typeAndCallCases() {
        return Stream.of(
                plain("target(|", CALL_ARGUMENT, ""),
                plain("target(1,|", CALL_ARGUMENT, ""),
                // The old classifier returns UNKNOWN for this EOF form.
                plain("target(1|", CALL_ARGUMENT, ""),
                plain("var x: |", TYPE_POSITION, ""),
                plain("const X: |", TYPE_POSITION, ""),
                plain("x as |", TYPE_POSITION, ""),
                plain("extends |", TYPE_POSITION, ""),
                plain("extends Fla|", TYPE_POSITION, "Fla"),
                plain("var x: Outer.In|ner", TYPE_POSITION, "Inner"),
                plain("var x: Array[Outer.In|ner]", TYPE_POSITION, "Inner"),
                plain("x as Outer.In|ner", TYPE_POSITION, "Inner"),
                plain("var x: Outer.|", TYPE_POSITION, ""),
                plain("var x = target(value as Outer.In|ner, 1+)", TYPE_POSITION, "Inner"),
                plain("var x = target(value as In|ner, 1+)", TYPE_POSITION, "Inner"),
                plain("func f() -> |", TYPE_POSITION, ""),
                plain("func f() -> Outer.|", TYPE_POSITION, ""),
                plain("var x = target(func() -> Outer.In|ner: pass, 1+)", TYPE_POSITION, "Inner"),
                plain("func f():|", UNKNOWN, ""),
                plain("{\"a\":|}", UNKNOWN, ""),
                plain("func f(|): pass", UNKNOWN, ""),
                plain("signal s(|)", UNKNOWN, ""),
                plain("func f(x = target(|)): pass", CALL_ARGUMENT, ""),
                plain("super(|)", CALL_ARGUMENT, ""),
                plain("preload(|)", CALL_ARGUMENT, ""),
                plain("func _init(|): pass", UNKNOWN, ""),
                plain("obj.class(1|)", CALL_ARGUMENT, ""),
                plain("obj.class(|)", CALL_ARGUMENT, ""),
                plain("var x = obj.as |", UNKNOWN, ""),
                plain("var x = obj.is |", UNKNOWN, ""),
                plain("x is |", TYPE_POSITION, ""),
                plain("x is not |", TYPE_POSITION, ""),
                plain("x is not In|ner", TYPE_POSITION, "Inner"),
                plain("x is not Outer.In|ner", TYPE_POSITION, "Inner"),
                plain("x not |", UNKNOWN, ""),
                plain("obj.not |", UNKNOWN, ""),
                plain("func f():\n\tvar x: Array[\n\tIn|ner", IDENTIFIER, "Inner"),
                plain("func f(x: Array[\n    In|ner\n]): pass", TYPE_POSITION, "Inner"),
                plain("g(func() -> Array[\n    In|ner)", IDENTIFIER, "Inner"),
                plain("func f() -> Array[\n    In|ner", IDENTIFIER, "Inner"),
                plain("$as |", UNKNOWN, ""),
                plain("%is |", UNKNOWN, ""),
                plain("$/as |", UNKNOWN, ""),
                plain("var x :=|", UNKNOWN, ""),
                plain("var x : =|", UNKNOWN, ""),
                plain("func f(x :=|): pass", UNKNOWN, ""),
                plain("signal s(x: |)", TYPE_POSITION, ""),
                plain("target(na|me)", IDENTIFIER, "name")
        );
    }

    @ParameterizedTest
    @MethodSource("coordinateAndTriviaCases")
    void byteCoordinatesAndTrivia(Case testCase) {
        assertCase(testCase);
    }

    static Stream<Case> coordinateAndTriviaCases() {
        return Stream.of(
                member("func f():\r\n\t节点.属|性", "属性", "节点"),
                member("# 😀\r\nfunc f():\r\n\t节点.属|性", "属性", "节点"),
                plain("# 😀\r\nfunc f():\r\n\t目标(1,|", CALL_ARGUMENT, ""),
                plain("# 😀\r\nvar 值: 外层.内|层", TYPE_POSITION, "内层"),
                plain("# comm|ent", UNKNOWN, ""),
                plain("target(1, # comm|ent\n)", UNKNOWN, ""),
                plain("target(\"hel|lo\")", UNKNOWN, ""),
                plain("target(&\"hel|lo\")", UNKNOWN, ""),
                plain("target(^\"hel|lo\")", UNKNOWN, ""),
                plain("target(\"hel|lo", UNKNOWN, ""),
                plain("target(r\"hel|lo", UNKNOWN, ""),
                plain("target(&\"hel|lo", UNKNOWN, ""),
                plain("target(\"\"\"hel|lo", UNKNOWN, ""),
                member("var x = (node.\\\n\tpa|)", "pa", "node"),
                plain("var x = \"😀|\"", UNKNOWN, "")
        );
    }

    @Test
    void nullSourceAndRecordInvariants() {
        assertThrows(NullPointerException.class, () -> FACADE.parseCompletionContext(null, 0));
        var zero = new Range(0, 0, new Point(0, 0), new Point(0, 0));
        assertThrows(IllegalArgumentException.class, () -> new CompletionContext(MEMBER_ACCESS, zero, null));
        assertThrows(IllegalArgumentException.class, () -> new CompletionContext(IDENTIFIER, zero, zero));
        assertThrows(NullPointerException.class, () -> new CompletionContext(null, zero, null));
        assertThrows(NullPointerException.class, () -> new CompletionContext(UNKNOWN, null, null));
    }

    @ParameterizedTest
    @MethodSource("boundaryRepairCases")
    void completionBoundariesRemainLocal(Case testCase) {
        assertCase(testCase);
    }

    static Stream<Case> boundaryRepairCases() {
        return Stream.of(
                plain("func f():\n\tnode|.pa", IDENTIFIER, "node"),
                plain("func f():\n\tnode|.", IDENTIFIER, "node"),
                member("func f():\n\ta.b|.c", "b", "a"),
                plain("var x: Outer|.Inner", TYPE_POSITION, "Outer"),
                member("func f():\n\tnode.pa|# comment", "pa", "node"),
                member("func f():\n\tnode.|# comment", "", "node"),
                plain("var x: Fla|# comment", TYPE_POSITION, "Fla"),
                plain("|# comment", UNKNOWN, ""),
                plain("#| comment", UNKNOWN, ""),
                plain("target(|\"hello\")", CALL_ARGUMENT, ""),
                plain("target(|\"unterminated", CALL_ARGUMENT, ""),
                plain("target(|r\"hello\")", CALL_ARGUMENT, ""),
                plain("target(|&\"hello\")", CALL_ARGUMENT, ""),
                plain("target(|^\"hello\")", CALL_ARGUMENT, ""),
                plain("target(r|\"hello\")", UNKNOWN, ""),
                plain("target(&|\"hello\")", UNKNOWN, ""),
                plain("target(^|\"hello\")", UNKNOWN, ""),
                plain("func f():\n\tfor x: |in arr:\n\t\tpass", TYPE_POSITION, ""),
                plain("func f():\n\tfor x: Array[int]|in arr:\n\t\tpass", TYPE_POSITION, ""),
                plain("func f():\n\tfor x: i|n arr:\n\t\tpass", UNKNOWN, ""),
                plain("func f():\n\tif x is |and y:\n\t\tpass", TYPE_POSITION, ""),
                plain("x is Foo not |", UNKNOWN, ""),
                plain("x is Foo not bar|", IDENTIFIER, "bar"),
                plain("x is not Foo|", TYPE_POSITION, "Foo")
        ).flatMap(testCase -> Stream.of(testCase, new Case("# 😀\r\n" +
                testCase.markedSource().replace("\n", "\r\n"), testCase.kind(),
                testCase.replacement(), testCase.receiver())));
    }

    @ParameterizedTest
    @MethodSource("functionRepairCases")
    void functionFramesEndAtRecoveryBoundaries(Case testCase) {
        assertCase(testCase);
    }

    static Stream<Case> functionRepairCases() {
        return Stream.of(
                plain("func\nvar x = target(|", CALL_ARGUMENT, ""),
                plain("func broken\nvar x = target(|", CALL_ARGUMENT, ""),
                plain("func\ntarget(|", CALL_ARGUMENT, ""),
                plain("func outer():\n\tfunc\n\ttarget(|", CALL_ARGUMENT, ""),
                plain("func broken\nconst X = target(|", CALL_ARGUMENT, ""),
                plain("func f()\nvar x: Outer.|", TYPE_POSITION, ""),
                plain("func f()\nvar x: Array[|", TYPE_POSITION, ""),
                plain("func f() -> int\nvar x: Array[|", TYPE_POSITION, ""),
                plain("func f() ->\nvar x: Array[|", TYPE_POSITION, ""),
                plain("func f()\nvar x: Array[Outer.In|ner", TYPE_POSITION, "Inner"),
                plain("func f()\nvar x: Array[int]|", TYPE_POSITION, ""),
                member("var x = g(func(),\n\tnode.\n\tpa|)", "pa", "node"),
                plain("var cb = func\n(|): pass", UNKNOWN, ""),
                plain("func named\n(|): pass", UNKNOWN, ""),
                plain("func named\n(x = target(|)): pass", CALL_ARGUMENT, ""),
                plain("signal s(x = target(|))", CALL_ARGUMENT, ""),
                plain("func _init\n(|): pass", UNKNOWN, ""),
                plain("var x = g(\nfunc named\n(|): pass)", UNKNOWN, ""),
                plain("func\nfunc named(): pass\nvar x = target(|", CALL_ARGUMENT, "")
        );
    }

    @ParameterizedTest
    @MethodSource("contextualFunctionNameCases")
    void contextualIdentifiersStillNameSignatures(Case testCase) {
        assertCase(testCase);
    }

    static Stream<Case> contextualFunctionNameCases() {
        return Stream.of("match", "when", "PI", "TAU", "INF", "NAN").flatMap(name -> Stream.of(
                plain("func " + name + "(|): pass", UNKNOWN, ""),
                plain("func " + name + "(x: |): pass", TYPE_POSITION, ""),
                plain("func " + name + "() -> |: pass", TYPE_POSITION, ""),
                plain("func " + name + "\n(|): pass", UNKNOWN, ""),
                plain("var x = g(func " + name + "():\n    a.\n    b|\n)", IDENTIFIER, "b"),
                plain("func\n" + name + "(|", CALL_ARGUMENT, "")
        ));
    }

    @ParameterizedTest
    @MethodSource("typeStateCases")
    void completedTypesDoNotAbsorbFollowingExpressions(Case testCase) {
        assertCase(testCase);
    }

    static Stream<Case> typeStateCases() {
        return Stream.of(
                member("func f(v, x):\n\tmatch v:\n\t\tx as int when node.pa|:\n\t\t\tpass", "pa", "node"),
                plain("func f(v, x):\n\tmatch v:\n\t\tx as int when bar|:\n\t\t\tpass", IDENTIFIER, "bar"),
                member("func f(x):\n\tvar y = x as Array[int].si|ze()", "size", "x as Array[int]"),
                plain("func f(x):\n\tvar y = x as Array[int][id|x]", IDENTIFIER, "idx"),
                plain("func f(x):\n\tvar y = x as Outer.Inner[id|x]", IDENTIFIER, "idx"),
                member("func f(x):\n\tvar y = x as Array[int][0].na|me", "name", "x as Array[int][0]"),
                plain("extends \"res://base.gd\".In|ner", TYPE_POSITION, "Inner"),
                plain("extends \"res://base.gd\".|", TYPE_POSITION, ""),
                plain("extends \"res://base.gd\".Outer.In|ner", TYPE_POSITION, "Inner"),
                plain("extends |\"res://base.gd\"", TYPE_POSITION, ""),
                plain("extends \"res://ba|se.gd\".Inner", UNKNOWN, ""),
                plain("func f(x: Dictionary[String, Array[In|ner]]): pass", TYPE_POSITION, "Inner"),
                plain("var x = target(value as int\nbar|)", IDENTIFIER, "bar"),
                plain("x is not Foo|", TYPE_POSITION, "Foo"),
                plain("var x: Array[|", TYPE_POSITION, "")
        );
    }

    @ParameterizedTest
    @MethodSource("matchAndTypeRecoveryCases")
    void matchPatternsAndDamagedTypesDoNotLeakFrames(Case testCase) {
        assertCase(testCase);
    }

    static Stream<Case> matchAndTypeRecoveryCases() {
        return Stream.of(
                plain("var x = g(func(v):\n\tmatch v:\n\t\t1, 2:\n\t\t\ta.\n\t\t\tb|\n)", IDENTIFIER, "b"),
                plain("var x = g(func(v):\n\tmatch v:\n\t\t1, 2:\n\t\t\tvar y: Array[\n\t\t\tIn|ner\n)", IDENTIFIER, "Inner"),
                plain("var x = g(func(v):\n\tmatch v:\n\t\t1, 2:\n\t\t\tpass\n\t\t3, 4:\n\t\t\ta.\n\t\t\tb|\n)", IDENTIFIER, "b"),
                plain("var x = g(func(v):\n\tmatch v:\n\t\t1, 2:\n\t\t\tmatch v:\n\t\t\t\t3, 4:\n\t\t\t\t\ta.\n\t\t\t\t\tb|\n)", IDENTIFIER, "b"),
                plain("var x = g(func(v):\n\tmatch v:\n\t\t[1, 2], 3:\n\t\t\ta.\n\t\t\tb|\n)", IDENTIFIER, "b"),
                member("var x = g(func(v):\n\tmatch v:\n\t\t1, 2: return a,\n\tnode.\n\tpa|)", "pa", "node"),
                member("var x = g(func(v):\n\tmatch v:\n\t\t1, 2:\n\t\t\treturn a,\n\tnode.\n\tpa|)", "pa", "node"),
                member("var x = g(func() -> Array[\n,\n\tnode.\n\tpa|\n)", "pa", "node"),
                member("var x = g(func() -> Array[, node.\n\tpa|)", "pa", "node"),
                member("var x = g(func() -> Dictionary[String, Array[\n,\n\tnode.\n\tpa|\n)", "pa", "node"),
                plain("func f() -> Array[\nvar x: Outer.In|ner", TYPE_POSITION, "Inner"),
                plain("func f(x: Array[\n\tIn|ner\n]): pass", TYPE_POSITION, "Inner"),
                plain("func f() -> Array[\n    In|ner", IDENTIFIER, "Inner")
        ).flatMap(testCase -> Stream.of(testCase, new Case("# 😀\r\n" +
                testCase.markedSource().replace("\n", "\r\n"), testCase.kind(),
                testCase.replacement(), testCase.receiver())));
    }

    @Test
    void typeStateExpectationsUseHealthyParserInputs() {
        for (var testCase : typeStateCases().limit(7).toList()) {
            var source = testCase.markedSource().replace("|", "");
            assertTrue(FACADE.parse(source).diagnostics().isEmpty(), source);
        }
    }

    @Test
    void recoveryExpectationsMatchSurvivingParserNodes() {
        record Fact(String source, Class<? extends Node> kind, String fragment, boolean present) {
        }
        for (var fact : List.of(
                new Fact("var x = g(func(v):\n\tmatch v:\n\t\t1, 2:\n\t\t\ta.\n\t\t\tb\n)",
                        IdentifierExpression.class, "b", true),
                new Fact("var x = g(func() -> Array[\n,\n\tnode.\n\tpa\n)",
                        AttributeExpression.class, "node.\n\tpa", true),
                new Fact("var x = g(func() -> Array[, node.\n\tpa)",
                        AttributeExpression.class, "node.\n\tpa", false))) {
            var result = FACADE.parse(fact.source());
            assertTrue(!result.diagnostics().isEmpty(), fact.source());
            var nodes = new ArrayDeque<Node>();
            nodes.add(result.ast());
            var found = false;
            while (!nodes.isEmpty()) {
                var node = nodes.removeLast();
                if (fact.kind().isInstance(node)) {
                    var fragment = fact.source().substring(node.range().startByte(), node.range().endByte());
                    found |= fragment.equals(fact.fragment());
                }
                nodes.addAll(node.getChildren());
            }
            assertEquals(fact.present(), found, fact.source());
        }
    }

    @ParameterizedTest
    @MethodSource("logicalLineCases")
    void continuationsPreserveLogicalBodyBoundaries(Case testCase) {
        assertCase(testCase);
    }

    static Stream<Case> logicalLineCases() {
        return Stream.of(
                plain("var x = \\\n    g(func():\n    a.\n    b|\n)", IDENTIFIER, "b"),
                member("var x = \\\n    g(func():\n    a.\\\n    b|\n)", "b", "a"),
                plain("var x = \\\n    g(func():\n    a.\n    # comment\n    b|\n)", IDENTIFIER, "b"),
                plain("var x = \\\n    # comment\n    g(func():\n    a.\n    b|\n)", IDENTIFIER, "b"),
                plain("var x = \\\n    \\\n    g(func():\n    a.\n    b|\n)", IDENTIFIER, "b"),
                plain("var x = \\\n\n    g(func():\n    a.\n    b|\n)", IDENTIFIER, "b"),
                plain("var x = g(func(): return \\\n    a.\n    b|\n)", IDENTIFIER, "b"),
                plain("var x = g(func(): \\\n    a.\n    b|\n)", IDENTIFIER, "b"),
                member("var x = g(func(): return a.\\\n    b|)", "b", "a"),
                member("var x = g(func(): return \\\n    a, node.\n    pa|)", "pa", "node"),
                plain("var x = g(func():\n    var y = h(func():\n        a.\n        b|\n    )\n)", IDENTIFIER, "b"),
                member("var x = g(func():\n    var y = h(func(): pass)\n    pass\n, node.\npa|)", "pa", "node")
        ).flatMap(testCase -> Stream.of(testCase, new Case("# 😀\r\n" +
                testCase.markedSource().replace("\n", "\r\n").replace("    ", "\t"),
                testCase.kind(), testCase.replacement(), testCase.receiver())));
    }

    @ParameterizedTest
    @MethodSource("pathRepairCases")
    void nodePathsAndArithmeticRemainDistinct(Case testCase) {
        assertCase(testCase);
    }

    static Stream<Case> pathRepairCases() {
        return Stream.of(
                List.of("$Root/\"Child\"", "$Root/\"Child\""),
                List.of("$Root/%Child", "$Root/%Child"),
                List.of("%Child", "%Child"),
                List.of("a % (b)", "(b)"),
                List.of("value%-name", "name"),
                List.of("a % b", "b"),
                List.of("a / b", "b"),
                List.of("$/Root/\"Child\"/%Leaf", "$/Root/\"Child\"/%Leaf"),
                List.of("$\"Root\"/Child", "$\"Root\"/Child"),
                List.of("%\"Child\"", "%\"Child\""),
                List.of("return %Child", "%Child"),
                List.of("await %Child", "%Child"),
                List.of("(-name)", "(-name)"),
                List.of("make() % (b)", "(b)"),
                List.of("items[0] % b", "b"),
                List.of("($Root) % b", "b"),
                List.of("a / %Child", "%Child"),
                List.of("a / b / %Child", "%Child"),
                List.of("a + %Child", "%Child"),
                List.of("a and %Child", "%Child"),
                List.of("$Root/return", "$Root/return"),
                List.of("%Child/\"Leaf\"", "%Child/\"Leaf\""),
                List.of("a % b / %Child", "%Child"),
                List.of("a / await %Child", "%Child"),
                List.of("a % await %Child", "%Child"),
                List.of("a / await %Child/\"Leaf\"", "%Child/\"Leaf\""),
                List.of("$Root/await", "$Root/await")
        ).flatMap(parts -> Stream.of("(1)", "(1+) + 2").map(suffix ->
                member("func f():\n    " + parts.getFirst() + ".method|" + suffix,
                        "method", parts.getLast())));
    }

    @Test
    void pathFallbackTestsActuallyDiscardTheTargetChain() {
        for (var expression : List.of("$Root/\"Child\"", "$Root/%Child", "%Child",
                "a % (b)", "value%-name", "a % b",
                "a % b / %Child", "a / await %Child", "a % await %Child")) {
            for (var damaged : List.of(false, true)) {
                var source = "func f():\n    " + expression + ".method" +
                        (damaged ? "(1+) + 2" : "(1)");
                var nodes = new ArrayDeque<Node>();
                nodes.add(FACADE.parse(source).ast());
                var chains = 0;
                while (!nodes.isEmpty()) {
                    var node = nodes.removeLast();
                    if (node instanceof AttributeExpression) {
                        chains++;
                    }
                    nodes.addAll(node.getChildren());
                }
                assertEquals(damaged ? 0 : 1, chains, source);
            }
        }
    }

    @Test
    @Timeout(15)
    void sharedFacadeIsDeterministicAcrossThreads() throws Exception {
        var source = "# 😀\r\nfunc f():\r\n\t节点.属性";
        var offset = source.getBytes(StandardCharsets.UTF_8).length;
        var expected = FACADE.parseCompletionContext(source, offset);
        try (var executor = Executors.newFixedThreadPool(8)) {
            var calls = Stream.<Callable<CompletionContext>>generate(
                    () -> () -> FACADE.parseCompletionContext(source, offset)).limit(100).toList();
            for (var result : executor.invokeAll(calls)) {
                assertEquals(expected, result.get());
            }
        }
    }

    @Test
    @Timeout(15)
    void pathologicalNestingAndEveryTruncationRemainBounded() {
        var source = "var x = " + "(".repeat(600) + "node.pa";
        assertNotNull(FACADE.parseCompletionContext(source, source.length()));
        for (var sample : List.of("target(func(x: Array[Inner]) -> Outer: node.method(1, 2))",
                "var x = g(\n\tfunc():\n\t\ta.\n\t\tb\n)", "var x: Dictionary[String, Array[Inner]]",
                "func\nvar x: Array[Inner]", "func f()\nvar x = target(1)",
                "var x = g(func(): return \\\n a.\n b\n)", "func f():\n $Root/\"Child\".method(1+) + 2")) {
            for (var end = 0; end <= sample.length(); end++) {
                var prefix = sample.substring(0, end);
                var context = FACADE.parseCompletionContext(prefix, end);
                assertNotNull(context.kind(), prefix);
                assertTrue(context.replaceableRange().startByte() >= 0, prefix);
                assertTrue(context.replaceableRange().endByte() <= end, prefix);
            }
        }
    }

    @Test
    @Timeout(15)
    void orphanAndPairedClosersControlCallArgumentDetectionAndStayLinear() {
        for (var source : List.of("var x = )(", "var x = ](")) {
            var context = FACADE.parseCompletionContext(source, source.length());
            assertNotEquals(CALL_ARGUMENT, context.kind(), source);
        }

        assertCase(plain("var x = get_fn()(|", CALL_ARGUMENT, ""));
        assertCase(plain("var x = arr[0](|", CALL_ARGUMENT, ""));

        var storm = "[".repeat(20_000) + ")".repeat(20_000);
        var stormContext = FACADE.parseCompletionContext(storm, storm.length());
        assertNotNull(stormContext.kind());
        assertNotEquals(CALL_ARGUMENT, stormContext.kind());
    }

    @Test
    void malformedClosedStringStillAllowsCompletionWhileUnterminatedStringSuppressesIt() {
        assertCase(plain("var x = f(\"\\u123\"|", CALL_ARGUMENT, ""));
        assertCase(plain("var x = f(\"\\u123|", UNKNOWN, ""));
    }

    @Test
    void orphanClosersAreRejectedAsPrimaryReceiversWhilePairedClosersRemainValid() {
        assertCase(plain("var x = ).|", UNKNOWN, ""));
        assertCase(member("var x = ][0].|", "", "[0]"));
        assertCase(member("var x = (a).|", "", "(a)"));
        assertCase(member("var x = arr[0].|", "", "arr[0]"));
    }

    private static Case member(String marked, String replacement, String receiver) {
        return new Case(marked, MEMBER_ACCESS, replacement, receiver);
    }

    private static Case plain(String marked, CompletionContext.Kind kind, String replacement) {
        return new Case(marked, kind, replacement, null);
    }

    private static void assertCase(Case testCase) {
        var marked = testCase.markedSource();
        var cursor = marked.indexOf('|');
        assertTrue(cursor >= 0, marked);
        var source = marked.substring(0, cursor) + marked.substring(cursor + 1);
        var bytes = source.getBytes(StandardCharsets.UTF_8);
        var byteOffset = marked.substring(0, cursor).getBytes(StandardCharsets.UTF_8).length;
        var context = FACADE.parseCompletionContext(source, byteOffset);
        assertEquals(testCase.kind(), context.kind(), marked);
        assertRange(testCase.replacement(), context.replaceableRange(), bytes, marked);
        if (testCase.replacement().isEmpty()) {
            var expected = byteOffset;
            if (testCase.kind() == MEMBER_ACCESS) {
                expected = marked.substring(0, cursor).lastIndexOf('.') + 1;
                expected = marked.substring(0, expected).getBytes(StandardCharsets.UTF_8).length;
            }
            assertEquals(expected, context.replaceableRange().startByte(), marked);
        }
        if (testCase.receiver() == null) {
            assertNull(context.receiverRange(), marked);
        } else {
            assertNotNull(context.receiverRange(), marked);
            assertRange(testCase.receiver(), context.receiverRange(), bytes, marked);
        }
    }

    private static void assertRange(String expected, Range range, byte[] bytes, String message) {
        assertTrue(range.startByte() >= 0 && range.endByte() <= bytes.length, message);
        assertEquals(expected, new String(bytes, range.startByte(),
                range.endByte() - range.startByte(), StandardCharsets.UTF_8), message);
        assertEquals(pointAt(bytes, range.startByte()), range.startPoint(), message);
        assertEquals(pointAt(bytes, range.endByte()), range.endPoint(), message);
    }

    private static Point pointAt(byte[] bytes, int end) {
        var row = 0;
        var column = 0;
        for (var i = 0; i < end; i++) {
            if (bytes[i] == '\n') {
                row++;
                column = 0;
            } else {
                column++;
            }
        }
        return new Point(row, column);
    }
}
