package dev.superice.gdparser.frontend.gdscript;

import dev.superice.gdparser.frontend.ast.AttributeExpression;
import dev.superice.gdparser.frontend.ast.ExtendsStatement;
import dev.superice.gdparser.frontend.ast.IdentifierExpression;
import dev.superice.gdparser.frontend.ast.Node;
import dev.superice.gdparser.frontend.ast.Point;
import dev.superice.gdparser.frontend.ast.Range;
import dev.superice.gdparser.frontend.ast.SourceFile;
import dev.superice.gdparser.frontend.ast.TypeRef;
import dev.superice.gdparser.frontend.ast.VariableDeclaration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

import static dev.superice.gdparser.frontend.gdscript.CompletionContext.Kind.CALL_ARGUMENT;
import static dev.superice.gdparser.frontend.gdscript.CompletionContext.Kind.EXPECTED_KEYWORD;
import static dev.superice.gdparser.frontend.gdscript.CompletionContext.Kind.IDENTIFIER;
import static dev.superice.gdparser.frontend.gdscript.CompletionContext.Kind.MEMBER_ACCESS;
import static dev.superice.gdparser.frontend.gdscript.CompletionContext.Kind.PARAMETER_NAME;
import static dev.superice.gdparser.frontend.gdscript.CompletionContext.Kind.SUITE_START;
import static dev.superice.gdparser.frontend.gdscript.CompletionContext.Kind.TYPE_POSITION;
import static dev.superice.gdparser.frontend.gdscript.CompletionContext.Kind.UNKNOWN;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    private record RecoveryTypeCase(Case completion, List<String> diagnosticCodes,
                                    Class<? extends Node> survivingType, String survivingText) {
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
                plain("func f():|", SUITE_START, ""),
                plain("{\"a\":|}", IDENTIFIER, ""),
                plain("func f(|): pass", PARAMETER_NAME, ""),
                plain("signal s(|)", PARAMETER_NAME, ""),
                plain("func f(x = target(|)): pass", CALL_ARGUMENT, ""),
                plain("super(|)", CALL_ARGUMENT, ""),
                plain("preload(|)", CALL_ARGUMENT, ""),
                plain("func _init(|): pass", PARAMETER_NAME, ""),
                plain("obj.class(1|)", CALL_ARGUMENT, ""),
                plain("obj.class(|)", CALL_ARGUMENT, ""),
                plain("var x = obj.as |", UNKNOWN, ""),
                plain("var x = obj.is |", UNKNOWN, ""),
                plain("x is |", TYPE_POSITION, ""),
                plain("x is not |", TYPE_POSITION, ""),
                plain("x is not In|ner", TYPE_POSITION, "Inner"),
                plain("x is not Outer.In|ner", TYPE_POSITION, "Inner"),
                plain("x not |", EXPECTED_KEYWORD, ""),
                plain("obj.not |", UNKNOWN, ""),
                plain("func f():\n\tvar x: Array[\n\tIn|ner", IDENTIFIER, "Inner"),
                plain("func f(x: Array[\n    In|ner\n]): pass", TYPE_POSITION, "Inner"),
                plain("g(func() -> Array[\n    In|ner)", TYPE_POSITION, "Inner"),
                plain("func f() -> Array[\n    In|ner", TYPE_POSITION, "Inner"),
                plain("$as |", UNKNOWN, ""),
                plain("%is |", UNKNOWN, ""),
                plain("$/as |", UNKNOWN, ""),
                plain("var x :=|", IDENTIFIER, ""),
                plain("var x : =|", IDENTIFIER, ""),
                plain("func f(x :=|): pass", IDENTIFIER, ""),
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
                plain("x is Foo not |", EXPECTED_KEYWORD, ""),
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
                plain("func " + name + "(|): pass", PARAMETER_NAME, ""),
                plain("func " + name + "(x: |): pass", TYPE_POSITION, ""),
                plain("func " + name + "() -> |: pass", TYPE_POSITION, ""),
                plain("func " + name + "\n(|): pass", UNKNOWN, ""),
                plain("var x = g(func " + name + "():\n    a.\n    b|\n)", IDENTIFIER, "b"),
                plain("func\n" + name + "(|", CALL_ARGUMENT, "")
        ));
    }

    @ParameterizedTest
    @MethodSource("healthyTypeStateCases")
    void healthyCompletedTypesDoNotAbsorbFollowingExpressions(Case testCase) {
        var source = withoutCursor(testCase);
        assertTrue(FACADE.parse(source).diagnostics().isEmpty(), source);
        assertExactReplacementAtName(testCase, source, assertCase(testCase, false));
    }

    static Stream<Case> healthyTypeStateCases() {
        return Stream.of(
                member("func f(v, x):\n\tmatch v:\n\t\tx as int when node.pa|:\n\t\t\tpass", "pa", "node"),
                plain("func f(v, x):\n\tmatch v:\n\t\tx as int when bar|:\n\t\t\tpass", IDENTIFIER, "bar"),
                member("func f(x):\n\tvar y = x as Array[int].si|ze()", "size", "x as Array[int]"),
                plain("func f(x):\n\tvar y = x as Array[int][id|x]", IDENTIFIER, "idx"),
                plain("func f(x):\n\tvar y = x as Outer.Inner[id|x]", IDENTIFIER, "idx"),
                member("func f(x):\n\tvar y = x as Array[int][0].na|me", "name", "x as Array[int][0]"),
                plain("extends \"res://base.gd\".In|ner", TYPE_POSITION, "Inner"),
                plain("extends \"res://base.gd\".Outer.In|ner", TYPE_POSITION, "Inner"),
                plain("extends |\"res://base.gd\"", TYPE_POSITION, ""),
                plain("extends \"res://ba|se.gd\".Inner", UNKNOWN, ""),
                plain("func f(x: Dictionary[String, Outer.In|ner]): pass", TYPE_POSITION, "Inner"),
                plain("func f(x: Array[\n    In|ner\n]): pass", TYPE_POSITION, "Inner"),
                plain("var x: Array[\\\nIn|ner]", TYPE_POSITION, "Inner"),
                plain("var 匹配 = x is not 外层.内|部", TYPE_POSITION, "内部")
        ).flatMap(GdCompletionContextTest::withCrlfAndEmoji);
    }

    @ParameterizedTest
    @MethodSource("recoveryTypeStateCases")
    void recoveryTypeSlotsPreserveDiagnosticsAndSurvivingNodes(RecoveryTypeCase testCase) {
        var source = withoutCursor(testCase.completion());
        var result = FACADE.parse(source);
        assertEquals(testCase.diagnosticCodes(), result.diagnostics().stream().map(d -> d.code()).toList(), source);
        var context = assertCase(testCase.completion(), false);
        assertTokenCase(testCase.completion());
        assertExactReplacementAtName(testCase.completion(), source, context);
        var bytes = source.getBytes(StandardCharsets.UTF_8);
        var nodes = new ArrayDeque<Node>();
        nodes.add(result.ast());
        var found = false;
        while (!nodes.isEmpty()) {
            var node = nodes.removeLast();
            var range = node.range();
            if (testCase.survivingType().isInstance(node)
                    && testCase.survivingText().equals(new String(bytes, range.startByte(),
                    range.endByte() - range.startByte(), StandardCharsets.UTF_8))) {
                found = true;
                break;
            }
            nodes.addAll(node.getChildren());
        }
        assertTrue(found, source);
    }

    static Stream<RecoveryTypeCase> recoveryTypeStateCases() {
        var missingType = List.of(DiagnosticCodes.MISSING_TYPE_GENERIC_ARGUMENT,
                DiagnosticCodes.MISSING_CLOSER_TYPE_BRACKET);
        var unexpected = List.of(DiagnosticCodes.SYNTAX_UNEXPECTED_TOKEN);
        return Stream.of(
                new RecoveryTypeCase(plain("extends \"res://base.gd\".|", TYPE_POSITION, ""),
                        unexpected, ExtendsStatement.class, "extends \"res://base.gd\"."),
                new RecoveryTypeCase(
                        plain("func f(x: Dictionary[String, Array[In|ner]]): pass", TYPE_POSITION, "Inner"),
                        unexpected, TypeRef.class, "Dictionary[String, Array[Inner]]"),
                new RecoveryTypeCase(plain("var x = target(value as int\nbar|)", IDENTIFIER, "bar"),
                        List.of(DiagnosticCodes.MISSING_SEPARATOR_COMMA), IdentifierExpression.class, "bar"),
                new RecoveryTypeCase(plain("x is not Foo|\nvar sibling = 1", TYPE_POSITION, "Foo"),
                        unexpected, VariableDeclaration.class, "var sibling = 1"),
                new RecoveryTypeCase(plain("var x: Array[|", TYPE_POSITION, ""),
                        missingType, VariableDeclaration.class, "var x: Array["),
                new RecoveryTypeCase(plain("var 数: Array[\n\t内|部", TYPE_POSITION, "内部"),
                        List.of(DiagnosticCodes.MISSING_TYPE_GENERIC_ARGUMENT, DiagnosticCodes.MISSING_CLOSER_TYPE_BRACKET,
                                DiagnosticCodes.SYNTAX_UNEXPECTED_TOKEN, DiagnosticCodes.SYNTAX_UNEXPECTED_TOKEN),
                        VariableDeclaration.class, "var 数: Array[")
        ).flatMap(testCase -> withCrlfAndEmoji(testCase.completion()).map(completion ->
                new RecoveryTypeCase(completion, testCase.diagnosticCodes(), testCase.survivingType(),
                        testCase.survivingText())));
    }

    @ParameterizedTest
    @MethodSource("matchAndTypeRecoveryCases")
    void matchPatternsAndDamagedTypesDoNotLeakFrames(Case testCase) {
        assertCase(testCase);
        assertTokenCase(testCase);
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
                plain("func f() -> Array[\n    In|ner", TYPE_POSITION, "Inner")
        ).flatMap(testCase -> Stream.of(testCase, new Case("# 😀\r\n" +
                testCase.markedSource().replace("\n", "\r\n"), testCase.kind(),
                testCase.replacement(), testCase.receiver())));
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
                        AttributeExpression.class, "node.\n\tpa", true))) {
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
    void pathFallbackWorksIndependentlyWithEmptyAst() {
        var emptyAst = new SourceFile(List.of(), new Range(0, 0, new Point(0, 0), new Point(0, 0)));
        for (var expression : List.of("$Root/\"Child\"", "$Root/%Child", "%Child",
                "a % (b)", "value%-name", "a % b",
                "a % b / %Child", "a / await %Child", "a % await %Child")) {
            var expectedReceiver = expression.equals("a % (b)") ? "(b)"
                    : expression.equals("value%-name") ? "name"
                    : expression.equals("a % b") ? "b"
                    : (expression.equals("a % b / %Child") || expression.equals("a / await %Child")
                            || expression.equals("a % await %Child")) ? "%Child"
                    : expression;
            var source = "func f():\n    " + expression + ".method(1+) + 2";
            var cursor = source.indexOf(".method") + ".method".length();
            var context = CompletionContextAnalyzer.analyze(source, emptyAst, cursor);
            assertEquals(MEMBER_ACCESS, context.kind(), source);
            assertEquals("method", source.substring(context.replaceableRange().startByte(),
                    context.replaceableRange().endByte()), source);
            var receiverRange = context.receiverRange();
            assertNotNull(receiverRange, source);
            assertEquals(expectedReceiver, source.substring(receiverRange.startByte(),
                    receiverRange.endByte()), source);
        }
    }

    @Test
    void damagedPathCallSurvivesInParsedAst() {
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
                assertEquals(1, chains, source);
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

    @ParameterizedTest
    @MethodSource("recoveredDelimiterCases")
    void recoveredDelimiterBoundariesWorkWithAndWithoutAst(Case testCase) {
        assertCase(testCase);
        assertTokenCase(testCase);
    }

    static Stream<Case> recoveredDelimiterCases() {
        return Stream.of(
                plain("[1)\n\ta.\n\tb|", IDENTIFIER, "b"),
                plain("[a.)\n\tb|", IDENTIFIER, "b"),
                member("(a.\n\tb|)", "b", "a"),
                plain("[1).|", UNKNOWN, ""),
                plain("[a.).|", UNKNOWN, ""),
                plain("[target(1] |", UNKNOWN, ""),
                plain("target(1\nfunc g():\n\t|", UNKNOWN, ""),
                plain("var bad = [1\nvar y = 1\na.\nb|", IDENTIFIER, "b"),
                plain("target(1,|", CALL_ARGUMENT, ""),
                plain("var x = g(func():\n\tvar y = 1\n)|", UNKNOWN, ""),
                plain("var x = g(func():\n\tvar y = 1\n\treturn |)", CALL_ARGUMENT, ""),
                plain("var x = g(func():\n\tvar y = 1\n\treturn |", CALL_ARGUMENT, ""),
                plain("target(1\nvar x = \"unterminated|", UNKNOWN, "")
        );
    }

    @Test
    void onlyExactClosersEnterThePartnerTable() {
        var stream = new CompletionTokenStream("foo([1)");
        assertEquals(4, stream.partner(1));
        assertEquals(1, stream.partner(4));
        assertEquals(-1, stream.partner(2));
        assertEquals(6, stream.effectiveEnd(1));
        assertEquals(6, stream.effectiveEnd(2));
        assertTrue(stream.isEffectiveEndInclusive(1));
        assertTrue(stream.isEffectiveEndInclusive(2));

        stream = new CompletionTokenStream("[target(1] ");
        assertEquals(-1, stream.partner(2));
        assertEquals(9, stream.effectiveEnd(2));
        assertTrue(stream.isEffectiveEndInclusive(2));

        stream = new CompletionTokenStream("[1)");
        assertEquals(-1, stream.partner(0));
        assertEquals(-1, stream.partner(2));
        assertEquals(2, stream.effectiveEnd(0));

        stream = new CompletionTokenStream("target(1\nfunc f(): pass");
        assertEquals(-1, stream.partner(1));
        assertEquals(9, stream.effectiveEnd(1));
        assertTrue(!stream.isEffectiveEndInclusive(1));

        stream = new CompletionTokenStream("target(1,");
        assertEquals(-1, stream.effectiveEnd(1));
        assertEquals(0, stream.oracleScanCount());
    }

    @Test
    void escapingASignatureEndsAllMissingDelimitersBeforeTheSibling() {
        for (var newline : List.of("\n", "\r\n")) {
            var prefix = "var 值 = g(func(x = inner(1," + newline;
            var source = prefix + "var sibling = 1" + newline + "var next = ";
            var stop = prefix.getBytes(StandardCharsets.UTF_8).length;
            var stream = new CompletionTokenStream(source);
            var ended = 0;
            for (var i = 0; i < stream.entries().size(); i++) {
                if (stream.entries().get(i).token().type() == GdTokenType.PARENTHESIS_OPEN) {
                    assertEquals(-1, stream.partner(i), source);
                    assertEquals(stop, stream.effectiveEnd(i), source);
                    assertTrue(!stream.isEffectiveEndInclusive(i), source);
                    ended++;
                }
            }
            assertEquals(3, ended, source);
        }
    }

    @ParameterizedTest
    @MethodSource("logicalDelimiterEscapeCases")
    void logicalDelimiterEscapesRespectClosersSuitesAndCaretBoundaries(Case testCase) {
        assertCase(testCase);
        assertTokenCase(testCase);
    }

    static Stream<Case> logicalDelimiterEscapeCases() {
        return Stream.of(
                member("var a = [func():\n\tpass)\n\tvar y = 1\n].m|em", "mem",
                        "[func():\n\tpass)\n\tvar y = 1\n]"),
                plain("var a = [func(): pass).m|em", IDENTIFIER, "mem"),
                member("foo(func():\n\tpass).m|em", "mem", "foo(func():\n\tpass)"),
                plain("var a = g(func(x,\nvar y = 1\nvar z = |", UNKNOWN, ""),
                plain("var a = g(func(x = inner(1,\nvar y = 1\nvar z = |", UNKNOWN, ""),
                plain("func f():\n\tvar a = g(func(x,\nvar y = 1\nvar z = |", UNKNOWN, ""),
                plain("var x = target(\n\t[func():\n\t\tvar y = 1\n\t],\n\t|", CALL_ARGUMENT, ""),
                plain("var x = target(func():\n\tvar y = 1\n, 2, |", CALL_ARGUMENT, ""),
                member("var x = target(func():\n\tpass\n, node.\n\tpa|", "pa", "node"),
                plain("var x = target(func():\n\tvar y = 1\nfunc g():\n\t|", UNKNOWN, ""),
                plain("var x = target(func():\n\tpass\nif truth: |", UNKNOWN, ""),
                plain("var x = target(func():\n\tpass\nif truth else other, |", CALL_ARGUMENT, ""),
                plain("var x = target(1,\nstatic func g():\n\t|", UNKNOWN, ""),
                plain("var x = target(1,\nstatic var index = |", UNKNOWN, ""),
                plain("var x = target(func():\n\tpass\nstatic bogus, |", CALL_ARGUMENT, ""),
                plain("var x = target(1,\nfunc(): pass, |", CALL_ARGUMENT, ""),
                plain("var x = target(1,\n@tool\nvar y = |", UNKNOWN, ""),
                plain("func f():\n\tif true:\n\t\tvar x = target(1,\n\tfoo() |", UNKNOWN, ""),
                plain("func f():\n\tif true:\n\t\tvar x = g(func(x,\n\tfoo() |", UNKNOWN, ""),
                plain("func f():\n\tif true:\n\t\tvar x = target(1,\n|\tfoo()", UNKNOWN, ""),
                plain("func f():\n\tif true:\n\t\tvar x = target(1,\n\tfoo() |)", CALL_ARGUMENT, ""),
                plain("func f():\n\tif true:\n\t\tvar x = target(1, \\\n\tfoo() |", CALL_ARGUMENT, ""),
                plain("var x = target(1,\n|var y = 1", UNKNOWN, ""),
                plain("var x = target(1,\n\t|var y = 1", UNKNOWN, ""),
                plain("var x = target(1,\n§|var y = 1", UNKNOWN, ""),
                plain("target(1\n|func g():\n\tpass", UNKNOWN, ""),
                plain("target(1,|\nvar y = 1", CALL_ARGUMENT, ""),
                plain("target(1,\n|)", CALL_ARGUMENT, ""),
                plain("[target(1|]", CALL_ARGUMENT, ""),
                plain("func f(x = 1] -> |", TYPE_POSITION, ""),
                plain("func f(x = 1] -> int:|", SUITE_START, ""),
                plain("f(x] -> |", UNKNOWN, "")
        ).flatMap(GdCompletionContextTest::withCrlfAndEmoji);
    }

    @ParameterizedTest
    @MethodSource("nestedLambdaEscapeCases")
    void nestedLambdaRecoveryUnwindsAllFramesBeforeTheSibling(Case testCase) {
        assertCase(testCase);
        assertTokenCase(testCase);
    }

    static Stream<Case> nestedLambdaEscapeCases() {
        return Stream.of(
                plain("var a = outer(func():\n\tvar b = inner(func():\n\t\tpass\nvar z = |", UNKNOWN, ""),
                plain("var a = outer(func():\n\tvar b = inner(func():\n\t\tvar c = leaf(1,\nvar z = |",
                        UNKNOWN, ""),
                plain("func f():\n\tvar a = outer(func():\n\t\tvar b = inner(func():\n\t\t\tvar c = leaf(1,\nfoo() |",
                        UNKNOWN, ""),
                plain("func f():\n\tvar a = outer(func(): var c = leaf(1,\nfoo()\na.\nb|", IDENTIFIER, "b"),
                plain("var a = target(func():\n\tpass]\n\tvar y = 1\nfunc g():\n\t|", UNKNOWN, ""),
                plain("var a = [func():\n\tpass)\n\tvar y = 1\nvar z = 1\na.\nb|", IDENTIFIER, "b"),
                plain("var a = target(func():\n\tpass]\n\tvar y = 1\n, 2, |", CALL_ARGUMENT, ""),
                plain("var a = target(func():\n\tpass]\n|@tool", UNKNOWN, ""),
                plain("func f():\n\tvar a = target(func():\n\t\tpass]\nfoo() |", UNKNOWN, ""),
                member("func f():\n\tvar a = [func():\n\t\tpass)\n].m|em", "mem", "[func():\n\t\tpass)\n]"),
                plain("var a = outer(func():\n\tvar b = inner(func():\n\t\tvar c = leaf(1, |", CALL_ARGUMENT, ""),
                plain("var a = outer(func():\n\tvar b = inner(func():\n\t\tpass\n\t), 2, |", CALL_ARGUMENT, ""),
                member("var a = outer(func():\n\tvar b = inner(func():\n\t\tpass\n\t)\n, node.\n\tpa|", "pa", "node")
        ).flatMap(GdCompletionContextTest::withCrlfAndEmoji);
    }

    @Test
    @Timeout(15)
    void deeplyAlternatingBodiesAndCallsConvergeAtOneBoundary() {
        var layers = 256;
        for (var newline : List.of("\n", "\r\n")) {
            var input = new StringBuilder();
            for (var i = 0; i < layers; i++) {
                input.append("\t".repeat(i)).append("var x = target(func():").append(newline);
            }
            input.append("\t".repeat(layers)).append("var x = target(1,").append(newline);
            var stop = input.length();
            var source = input.append("var sibling = ").toString();
            var stream = new CompletionTokenStream(source);
            assertEquals(1, stream.oracleScanCount());
            var calls = 0;
            for (var i = 0; i < stream.entries().size(); i++) {
                if (stream.entries().get(i).role() == CompletionTokenStream.Role.CALL) {
                    assertEquals(stop, stream.effectiveEnd(i));
                    assertTrue(!stream.isEffectiveEndInclusive(i));
                    calls++;
                }
            }
            assertEquals(layers + 1, calls);
            assertEquals(UNKNOWN, CompletionContextAnalyzer.analyze(source, emptyAst(), source.length()).kind());
        }
    }

    @ParameterizedTest
    @MethodSource("lineAndTypeBoundaryCases")
    void completionKeepsLogicalBoundariesAndExactEmptyTypeSlots(Case testCase) {
        assertCase(testCase);
        assertTokenCase(testCase);
    }

    static Stream<Case> lineAndTypeBoundaryCases() {
        return Stream.of(
                plain("var a = g([node.\nva|r y = 1)", CALL_ARGUMENT, ""),
                member("var a = g([node.|\nvar y = 1)", "", "node"),
                plain("var a = g({\"a\":\n    |   var y = 1)", CALL_ARGUMENT, ""),
                plain("var a = g({\"a\":\n\t|})", IDENTIFIER, ""),
                member("var a = g([node.\n\tpa|])", "pa", "node"),
                plain("func f():\n\tif true:\n\t\tvar x = target(1,\n\t\\\n\tfoo() |", UNKNOWN, ""),
                plain("func f():\n\tif true:\n\t\tvar x = target(1, \\\n\tfoo() |", CALL_ARGUMENT, ""),
                plain("func f():\n\tif true:\n\t\tvar x = target(1,\n\t\\\n# trivia\n\tfoo() |", UNKNOWN, ""),
                plain("func f():\n\tif true:\n\t\tvar x = target(1,\n\t\r\tfoo() |", CALL_ARGUMENT, ""),
                plain("g(func() -> Array[Inner,\n    oth|er)", IDENTIFIER, "other"),
                plain("g(func() -> Dictionary[String,\n    oth|er)", IDENTIFIER, "other"),
                plain("var x: Dictionary[String, int,\n    Oth|er", IDENTIFIER, "Other"),
                plain("g(func() -> Dictionary[String,\n    In|ner])", TYPE_POSITION, "Inner"),
                plain("var x: Dictionary[String,\n\tIn|ner", TYPE_POSITION, "Inner"),
                plain("var x: Array[\n\t|]", TYPE_POSITION, ""),
                plain("var x: Dictionary[\n\tString, |]", TYPE_POSITION, ""),
                plain("g(func() -> Array[\n    |])", TYPE_POSITION, ""),
                plain("var x: Array[\n\t]|", UNKNOWN, ""),
                plain("var x: Array[\n\t|)", UNKNOWN, "")
        ).flatMap(GdCompletionContextTest::withCrlfAndEmoji);
    }

    @ParameterizedTest
    @MethodSource("recoveredSlotOwnershipCases")
    void recoveredSlotsFollowTheirLiveBoundaryAndSyntaxOwner(Case testCase) {
        assertCase(testCase);
        assertTokenCase(testCase);
    }

    static Stream<Case> recoveredSlotOwnershipCases() {
        return Stream.of(
                plain("var a = g([x not\n    |   var y = 1)", CALL_ARGUMENT, ""),
                plain("var a = g([node as\n    |   var y = 1)", CALL_ARGUMENT, ""),
                plain("var a = g([var x :=\n    |   var y = 1)", CALL_ARGUMENT, ""),
                plain("func f():\n\tif true:\n\t\tvar x = g([node\n\t.pa|)", IDENTIFIER, "pa"),
                member("func f():\n\tif true:\n\t\tvar x = g([node\n\t.pa.mo|re)", "more", "pa"),
                plain("func f():\n\tif true:\n\t\tvar x = g([node as\n\tNa|me)", IDENTIFIER, "Name"),
                plain("func f():\n\tif true:\n\t\tvar x = g([node\n\tnot |)", CALL_ARGUMENT, ""),
                member("var a = g([node\n\t.pa|])", "pa", "node"),
                member("var a = g([node\n\t.pa.mo|re])", "more", "node\n\t.pa"),
                plain("var a = g(node as\n\t|)", TYPE_POSITION, ""),
                plain("var a = g(x not\n\t|)", EXPECTED_KEYWORD, ""),
                plain("func f(x: Array[Inner, |): pass", PARAMETER_NAME, ""),
                plain("func f(x: Dictionary[String, |): pass", PARAMETER_NAME, ""),
                plain("func f(x: Array[Inner, y :=|): pass", IDENTIFIER, ""),
                plain("func f(x: Array[Inner, y: |): pass", TYPE_POSITION, ""),
                plain("func f(x: Array[Array[Inner, |): pass", PARAMETER_NAME, ""),
                plain("func f(x: Dictionary[String, |]): pass", TYPE_POSITION, ""),
                plain("func f(x: Dictionary[String, int], |): pass", PARAMETER_NAME, ""),
                plain("func f(x: Array[In|ner]): pass", TYPE_POSITION, "Inner"),
                plain("var y: int not |", UNKNOWN, ""),
                plain("var y: Array[int] not |", UNKNOWN, ""),
                plain("const X: int not |", UNKNOWN, ""),
                plain("extends Node not |", UNKNOWN, ""),
                plain("extends \"res://base.gd\" not |", UNKNOWN, ""),
                plain("func f() -> Array[int] not |", UNKNOWN, ""),
                plain("func f() -> int not |", UNKNOWN, ""),
                plain("func f(x: int not |): pass", UNKNOWN, ""),
                plain("enum E { A } not |", UNKNOWN, ""),
                plain("enum { A } not |", UNKNOWN, ""),
                plain("x as Foo not |", EXPECTED_KEYWORD, ""),
                plain("x as Array[int] not |", EXPECTED_KEYWORD, ""),
                plain("x is Array[int] not |", EXPECTED_KEYWORD, ""),
                plain("x is Foo not |", EXPECTED_KEYWORD, ""),
                plain("{\"a\": 1} not |", EXPECTED_KEYWORD, ""),
                plain("x not |", EXPECTED_KEYWORD, "")
        ).flatMap(GdCompletionContextTest::withCrlfAndEmoji);
    }

    @ParameterizedTest
    @MethodSource("childBoundaryAndDamagedTypeCases")
    void childBoundariesSeparateTokenAdjacencyAndDamagedTypesReleaseCommas(Case testCase) {
        assertAll(() -> assertCase(testCase), () -> assertTokenCase(testCase));
    }

    static Stream<Case> childBoundaryAndDamagedTypeCases() {
        var suite = "func f():\n\tif true:\n\t\tvar x = ";
        var call = suite + "g([";
        return Stream.of(
                plain(suite + "([foo\n\t(|))", UNKNOWN, ""),
                plain(call + "var y:\n\t=|)", CALL_ARGUMENT, ""),
                plain(call + "var y\n\t: |)", CALL_ARGUMENT, ""),
                plain(call + "node.\n\tfunc(|): pass)", PARAMETER_NAME, ""),
                plain(call + "enum E\n\t{\"a\": |})", IDENTIFIER, ""),
                plain(suite + "{[node\n\t: |}", UNKNOWN, ""),
                plain(call + "foo\n\t(|)])", CALL_ARGUMENT, ""),
                plain(call + "var y:\n\t=|])", IDENTIFIER, ""),
                plain(call + "var y\n\t: |])", TYPE_POSITION, ""),
                plain(call + "node.\n\tfunc(|)])", CALL_ARGUMENT, ""),
                plain(call + "enum E\n\t{\"a\": |}])", CALL_ARGUMENT, ""),
                plain(suite + "{[node]\n\t: |}", IDENTIFIER, ""),
                member(call + "node\n\t(value).pa|)", "pa", "(value)"),
                member(call + "node\n\t[0].pa|)", "pa", "[0]"),
                member(call + "node\n\t[0].|)", "", "[0]"),
                member(call + "node\n\t(1).|)", "", "(1)"),
                member(call + "$Root/\n\tChild.pa|)", "pa", "Child"),
                member(call + "$Root\n\t/Child.pa|)", "pa", "Child"),
                member(call + "$Root/\n\t%Child.pa|)", "pa", "%Child"),
                member(call + "node\n\t%Child.pa|)", "pa", "%Child"),
                member(call + "节点\n\t(值).属|性)", "属性", "(值)"),
                member(call + "node\n\t(value).pa|])", "pa", "node\n\t(value)"),
                member(call + "node\n\t[0].pa|])", "pa", "node\n\t[0]"),
                member(call + "$Root/\n\tChild.pa|])", "pa", "$Root/\n\tChild"),
                member(call + "$Root\n\t/%Child.pa|])", "pa", "$Root\n\t/%Child"),
                member(call + "node\n\t%Child.pa|])", "pa", "Child"),
                plain("func f(x: Array[Inner junk, |): pass", PARAMETER_NAME, ""),
                plain("func f(x: Array[Inner +, y :=|): pass", IDENTIFIER, ""),
                plain("func f(x: Array[Inner +, y: |): pass", TYPE_POSITION, ""),
                plain("func f(x: Dictionary[String junk, |): pass", PARAMETER_NAME, ""),
                plain("func f(x: Array[Array[Inner +, |): pass", PARAMETER_NAME, ""),
                plain("func f(x: Array[内部 junk\n\t, |): pass", PARAMETER_NAME, ""),
                plain("func f(x: Dictionary[Array[Inner +, |]]): pass", TYPE_POSITION, ""),
                plain("func f(x: Dictionary[Array[Inner], |]): pass", TYPE_POSITION, ""),
                plain("func f(x: Dictionary[String, |]): pass", TYPE_POSITION, ""),
                plain("func f(x: Dictionary[String, int], |): pass", PARAMETER_NAME, ""),
                plain("g(v as Array[Inner +, |)", CALL_ARGUMENT, "")
        ).flatMap(GdCompletionContextTest::withCrlfAndEmoji);
    }

    @ParameterizedTest
    @MethodSource("expressionRoleAndSeparatorCases")
    void expressionRolesAndSeparatorsKeepTheirOwnCompletionSlots(Case testCase) {
        assertAll(() -> assertCase(testCase), () -> assertTokenCase(testCase));
    }

    static Stream<Case> expressionRoleAndSeparatorCases() {
        var recovered = "var broken = [\nvar ok = 1\n";
        var escaped = "func f():\n\tif true:\n\t\tvar x = g([node\n\t";
        var nested = "func f():\n\tcall(";
        return Stream.of(
                member("var x = value as Array[int] % Child.pa|", "pa", "Child"),
                member(recovered + "var x = value as Array[int] % Child.pa|", "pa", "Child"),
                member(recovered + "var x = value is Array[int] % 节点.属|性", "属性", "节点"),
                member(recovered + "var x = value as Kind % Child.pa|", "pa", "Child"),
                member(recovered + "var x = %Child.pa|", "pa", "%Child"),
                member(recovered + "var x = $Root/%Child.pa|", "pa", "$Root/%Child"),
                member(escaped + "%Child.pa|)", "pa", "%Child"),
                member(escaped + "(value).pa|)", "pa", "(value)"),
                member(escaped + "[0].pa|)", "pa", "[0]"),
                plain("var d = {key = |}", IDENTIFIER, ""),
                plain("var d = {\"键\" = |}", IDENTIFIER, ""),
                plain("var d = {((键)) = |}", IDENTIFIER, ""),
                plain(nested + "{key = |})", IDENTIFIER, ""),
                plain(nested + "{\"键\" = |})", IDENTIFIER, ""),
                plain(nested + "{key = 1,\n\t  次键 = |})", IDENTIFIER, ""),
                plain("var d = {= |}", UNKNOWN, ""),
                plain("var d = {5 = |}", UNKNOWN, ""),
                plain("var d = {obj.key = |}", UNKNOWN, ""),
                plain("var d = {\"a\" + \"键\" = |}", UNKNOWN, ""),
                plain("var d = {key = 1 = |}", UNKNOWN, ""),
                plain("var d = {key = 1, |}", UNKNOWN, ""),
                plain(nested + "{key = 1 = |})", CALL_ARGUMENT, ""),
                plain(nested + "{key = 1, |})", CALL_ARGUMENT, ""),
                plain("var x :|= 1", UNKNOWN, ""),
                plain("var 数 : | = 1", UNKNOWN, ""),
                plain("func f(x : |= 1): pass", UNKNOWN, ""),
                plain("func f(x :\n\t|= 1): pass", UNKNOWN, ""),
                plain("const C :|= 1", UNKNOWN, ""),
                plain("var x: |int", TYPE_POSITION, "int"),
                plain("func f(x: |int): pass", TYPE_POSITION, "int"),
                plain("var x :=|", IDENTIFIER, ""),
                plain("var x : = |", IDENTIFIER, ""),
                plain("const C : = |", IDENTIFIER, ""),
                plain("func f(x :=|): pass", IDENTIFIER, ""),
                plain("func f(x : = |): pass", IDENTIFIER, ""),
                plain("var d = {key: match not |}", EXPECTED_KEYWORD, ""),
                plain("var d = {key = match not |}", EXPECTED_KEYWORD, ""),
                plain(nested + "{\"键\":\n\t   match not |})", EXPECTED_KEYWORD, ""),
                plain(nested + "{\"键\" =\n\t   match not |})", EXPECTED_KEYWORD, ""),
                plain("var d = {key: 1, match not |}", EXPECTED_KEYWORD, ""),
                plain("if ok: match not |", UNKNOWN, ""),
                plain("func f():\n\tmatch not |", UNKNOWN, ""),
                plain("func f():\n\tpass; match not |", UNKNOWN, ""),
                plain("match not |", UNKNOWN, ""),
                plain("f(func(): match not |)", CALL_ARGUMENT, ""),
                plain("if ok: var d = {key: match not |}", EXPECTED_KEYWORD, "")
        ).flatMap(GdCompletionContextTest::withCrlfAndEmoji);
    }

    @ParameterizedTest
    @MethodSource("columnZeroCastReceiverCases")
    void astCastReceiverCanStartAtTheExactRecoveryBoundary(Case testCase) {
        var fallback = member(testCase.markedSource(), testCase.replacement(), "[int]");
        assertAll(() -> assertCase(testCase), () -> assertTokenCase(fallback));
    }

    static Stream<Case> columnZeroCastReceiverCases() {
        return withCrlfAndEmoji(member("func f():\n\tvar x = g([node\nx as Array[int].na|me)",
                "name", "x as Array[int]"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Inner", "Inner junk", "Inner +"})
    void signatureCommaExclusivelyEndsTheTypeWithoutPairingIt(String atom) {
        var source = "# 😀\r\nfunc f(x: Array[Array[" + atom + "\r\n\t, y :=): pass";
        var comma = source.substring(0, source.indexOf(',')).getBytes(StandardCharsets.UTF_8).length;
        var stream = new CompletionTokenStream(source);
        var endedTypes = 0;
        for (var i = 0; i < stream.entries().size(); i++) {
            if (stream.entries().get(i).role() == CompletionTokenStream.Role.TYPE) {
                assertEquals(-1, stream.partner(i));
                assertEquals(comma, stream.effectiveEnd(i));
                assertFalse(stream.isWithinEffectiveEnd(i, comma));
                endedTypes++;
            }
        }
        assertEquals(2, endedTypes);
    }

    @Test
    @Timeout(15)
    void completionScansTheBracketOracleAtMostOncePerStream() {
        var sameLineMismatches = new CompletionTokenStream("[".repeat(20_000) + ")".repeat(20_000));
        assertEquals(0, sameLineMismatches.oracleScanCount());
        for (var source : List.of(
                "[".repeat(20_000) + "\n" + ")".repeat(20_000),
                "[(1]\n".repeat(12_000) + "target(\n1,\nvar 尾 = 1\nnode.",
                "target(\n" + "value,\n".repeat(20_000) + "var tail = 1\n",
                "target(".repeat(8_000) + "\nitem\n" + ")".repeat(8_000))) {
            var stream = new CompletionTokenStream(source);
            assertEquals(1, stream.oracleScanCount());
            var end = source.getBytes(StandardCharsets.UTF_8).length;
            assertContextBounds(CompletionContextAnalyzer.analyze(source, emptyAst(), end), end, "token fallback");
            assertContextBounds(FACADE.parseCompletionContext(source, end), end, "public facade");
        }
    }

    @Test
    @Timeout(15)
    void allCompletionPrefixesKeepUtf8RangesAndReceiverInvariants() {
        for (var sample : List.of(
                "# 😀\r\nvar 错 = [1)\r\n\t节点.\r\n\t属性",
                "# 😀\nvar 数: Array[\n# uncommented type below\n\t内部",
                "g(func() -> Dictionary[String, Array[\n,\n\t节点.\n\t属性\n)",
                "[target(1] \nfunc f(a, ...): pass",
                "func f(a: int, x : = make()) -> Outer: pass",
                "func f(x = 1]: x is Array[int] not in xs",
                "func f \\\r\n(a, ...more): return {\"键😀\": make(1), \"next\": 2}",
                "func named\n(x = target(1)): pass",
                "target(1\nfunc g():\n\tvar y = \"unterminated",
                "var x = g(func():\n\tvar y = 1\n\treturn )")) {
            for (var i = 0; i <= sample.length(); i++) {
                var prefix = sample.substring(0, i);
                var end = prefix.getBytes(StandardCharsets.UTF_8).length;
                assertTrue(new CompletionTokenStream(prefix).oracleScanCount() <= 1, prefix);
                assertContextBounds(CompletionContextAnalyzer.analyze(prefix, emptyAst(), end), end, prefix);
                assertContextBounds(FACADE.parseCompletionContext(prefix, end), end, prefix);
            }
        }
    }

    private static void assertContextBounds(CompletionContext context, int utf8Length, String message) {
        assertNotNull(context.kind(), message);
        assertBounds(context.replaceableRange(), utf8Length, message);
        assertEquals(context.kind() == MEMBER_ACCESS, context.receiverRange() != null, message);
        if (context.receiverRange() != null) {
            assertBounds(context.receiverRange(), utf8Length, message);
            assertTrue(context.receiverRange().endByte() <= context.replaceableRange().startByte(), message);
        }
    }

    private static void assertBounds(Range range, int utf8Length, String message) {
        assertTrue(range.startByte() >= 0, message);
        assertTrue(range.startByte() <= range.endByte(), message);
        assertTrue(range.endByte() <= utf8Length, message);
    }

    @ParameterizedTest
    @MethodSource("trustedTypeContinuationCases")
    void trustedTypeContinuationsAreLocalAndIndependentOfAst(Case testCase) {
        assertCase(testCase);
        assertTokenCase(testCase);
    }

    @Test
    void trustedTypeContinuationDoesNotMakeTheUnclosedTypeStrictlyValid() {
        for (var source : List.of("var x: Array[\n    Inner",
                "func f() -> Array[\n    Inner", "var 数: Array[\r\n\t内部")) {
            var parsed = FACADE.parse(source);
            assertTrue(parsed.diagnostics().stream().anyMatch(diagnostic ->
                    DiagnosticCodes.MISSING_CLOSER_TYPE_BRACKET.equals(diagnostic.code())), source);
            assertThrows(IllegalStateException.class, () -> FACADE.parseStrict(source), source);
            assertEquals(TYPE_POSITION, FACADE.parseCompletionContext(source,
                    source.getBytes(StandardCharsets.UTF_8).length).kind(), source);
        }
    }

    static Stream<Case> trustedTypeContinuationCases() {
        return Stream.of(
                plain("var x: Array[\n\tIn|ner", TYPE_POSITION, "Inner"),
                plain("var x: Array[\n\t|", TYPE_POSITION, ""),
                plain("var x: Array[\n|    Inner", UNKNOWN, ""),
                plain("var x: Array[\n# comment\n\tIn|ner", TYPE_POSITION, "Inner"),
                plain("var x: Array[\n\t\n\tIn|ner", IDENTIFIER, "Inner"),
                plain("var x: Array[Outer.\n\tIn|ner]", TYPE_POSITION, "Inner"),
                plain("var x: Dictionary[\n\tString, Array[In|ner]]", TYPE_POSITION, "Inner"),
                plain("var 数: Array[\n\t内|部", TYPE_POSITION, "内部"),
                plain("func f():\n\tvar x: Array[\n\t\tIn|ner", TYPE_POSITION, "Inner"),
                plain("func f():\n\tvar x: Array[\n\tIn|ner", IDENTIFIER, "Inner"),
                plain("func f() -> Array[\n    In|ner", TYPE_POSITION, "Inner"),
                plain("g(func() -> Array[\n    In|ner)", TYPE_POSITION, "Inner"),
                plain("g(func() -> Array[\n    In|ner])", TYPE_POSITION, "Inner"),
                plain("g(g(func() -> Dictionary[\n    String, In|ner]))", TYPE_POSITION, "Inner"),
                plain("g(func() -> Array[\n    In|ner); pass", IDENTIFIER, "Inner"),
                plain("func f() -> Array[\n    foo(|)", CALL_ARGUMENT, ""),
                plain("func f() -> Array[\n    a| = 1", IDENTIFIER, "a"),
                member("func f() -> Array[\n    a.b|(1)", "b", "a"),
                plain("func f() -> Array[\n    pass|", UNKNOWN, ""),
                plain("func f() -> Array[\n    var y = In|ner", IDENTIFIER, "Inner"),
                plain("func f() -> Array[\n    In|ner Other", IDENTIFIER, "Inner"),
                plain("func f() -> Array[\n    , In|ner", IDENTIFIER, "Inner"),
                plain("func f() -> Array[\n    In|ner; next", IDENTIFIER, "Inner"),
                plain("func f() -> Array[\n    In|ner §", IDENTIFIER, "Inner"),
                plain("func f(x: Array[\n    In|ner\n]): pass", TYPE_POSITION, "Inner"),
                plain("func f() -> Array[\nvar x: Outer.In|ner", TYPE_POSITION, "Inner"),
                plain("var x: Array[\\\nIn|ner", TYPE_POSITION, "Inner")
        ).flatMap(GdCompletionContextTest::withCrlfAndEmoji);
    }

    @ParameterizedTest
    @MethodSource("trustedTypeCursorAndCommaCases")
    void trustedTypesDistinguishOpenCaretSlotsAndCommaOwners(Case testCase) {
        assertCase(testCase);
        assertTokenCase(testCase);
    }

    static Stream<Case> trustedTypeCursorAndCommaCases() {
        return Stream.of(
                plain("var x: Array[\n\tInner |", UNKNOWN, ""),
                plain("var x: Array[\n\tInner]|", UNKNOWN, ""),
                plain("var x: Array[\n\tInner] |", UNKNOWN, ""),
                plain("g(func() -> Array[\n    Inner]|)", UNKNOWN, ""),
                plain("var x: Dictionary[\n\tString |, int]", UNKNOWN, ""),
                plain("var x: Array[\n\tArray |[int]", UNKNOWN, ""),
                plain("var x: Array[\n\t|", TYPE_POSITION, ""),
                plain("var x: Array[\n\tOuter.|", TYPE_POSITION, ""),
                plain("var x: Dictionary[\n\tString, |", TYPE_POSITION, ""),
                plain("var x: Array[\n\tArray[ | int]", TYPE_POSITION, ""),
                plain("g(func() -> Array[\n    Inner, oth|er)", IDENTIFIER, "other"),
                plain("g(func() -> Dictionary[\n    String, oth|er)", IDENTIFIER, "other"),
                plain("g(func() -> Dictionary[\n    String, In|ner])", TYPE_POSITION, "Inner"),
                plain("var x: Dictionary[\n\tString, In|ner", TYPE_POSITION, "Inner"),
                plain("var x: Dictionary[\n\tString, int, oth|er]", IDENTIFIER, "other"),
                plain("var x: Dictionary[String,\n\tInner, oth|er]", IDENTIFIER, "other"),
                plain("var x: Foo[\n\tInner, oth|er]", IDENTIFIER, "other"),
                plain("g(func() -> Array[\n    Dictionary[String, In|ner]])", TYPE_POSITION, "Inner"),
                plain("g(func() -> Array[\n    Dictionary[String, oth|er)", IDENTIFIER, "other"),
                plain("var x: Array[\n\tOuter.ma|tch", TYPE_POSITION, "match"),
                plain("var x: Array[\n\tOuter.IN|F", TYPE_POSITION, "INF"),
                plain("var x: Array[\n\tma|tch", TYPE_POSITION, "match"),
                plain("var x: Array[\n\tOuter.wh|en", TYPE_POSITION, "when"),
                plain("var x: Array[\n\tma|tch x:", IDENTIFIER, "match")
        ).flatMap(GdCompletionContextTest::withCrlfAndEmoji);
    }

    @ParameterizedTest
    @MethodSource("cursorGapCases")
    void cursorGapsRequireAnUnfilledOwnedSlot(Case testCase) {
        assertCase(testCase);
        assertTokenCase(testCase);
    }

    static Stream<Case> cursorGapCases() {
        return Stream.of(
                plain("{\"a\":|}", IDENTIFIER, ""),
                plain("{\"a\": |}", IDENTIFIER, ""),
                plain("{\"a\": 1, \"b\":|}", IDENTIFIER, ""),
                plain("target({\"a\":|})", IDENTIFIER, ""),
                plain("{\"键\":\n\t|}", IDENTIFIER, ""),
                plain("{: |}", UNKNOWN, ""),
                plain("{\"a\": 1|}", UNKNOWN, ""),
                plain("{\"a\": }|", UNKNOWN, ""),
                plain("{\"f\": func():|}", SUITE_START, ""),
                plain("var x :=|", IDENTIFIER, ""),
                plain("var x : =|", IDENTIFIER, ""),
                plain("var x :\t=|", IDENTIFIER, ""),
                plain("const X :=|", IDENTIFIER, ""),
                plain("func f(x :=|): pass", IDENTIFIER, ""),
                plain("func f(x : =|): pass", IDENTIFIER, ""),
                plain("func f(a: int, x :=|): pass", IDENTIFIER, ""),
                plain("func f():\n\tfor x :=|", UNKNOWN, ""),
                plain("var x :=\n|", UNKNOWN, ""),
                plain("func f(|): pass", PARAMETER_NAME, ""),
                plain("func _init(|): pass", PARAMETER_NAME, ""),
                plain("func match(|): pass", PARAMETER_NAME, ""),
                plain("var cb = func(|): pass", PARAMETER_NAME, ""),
                plain("signal s(|)", PARAMETER_NAME, ""),
                plain("func f(a, |): pass", PARAMETER_NAME, ""),
                plain("signal s(a: int, |)", PARAMETER_NAME, ""),
                plain("func f(\n\t|): pass", PARAMETER_NAME, ""),
                plain("func f(a, ...|): pass", PARAMETER_NAME, ""),
                plain("func f \\\n(|): pass", PARAMETER_NAME, ""),
                plain("var cb = func\n(|): pass", UNKNOWN, ""),
                plain("func named\n(|): pass", UNKNOWN, ""),
                plain("func _init\n(|): pass", UNKNOWN, ""),
                plain("var x = g(\nfunc named\n(|): pass)", UNKNOWN, ""),
                plain("func match\n(|): pass", UNKNOWN, ""),
                plain("func named\n(x = target(|)): pass", CALL_ARGUMENT, ""),
                plain("func f():|", SUITE_START, ""),
                plain("func f(): |", SUITE_START, ""),
                plain("func f() -> int:|", SUITE_START, ""),
                plain("func _init():|", SUITE_START, ""),
                plain("var cb = func():|", SUITE_START, ""),
                plain("func f(x = 1]:|", SUITE_START, ""),
                plain("func f():\n\t|", UNKNOWN, ""),
                plain("func f(): pass |", UNKNOWN, ""),
                plain("x not |", EXPECTED_KEYWORD, ""),
                plain("x is Foo not |", EXPECTED_KEYWORD, ""),
                plain("(a + b) not |", EXPECTED_KEYWORD, ""),
                plain("x is Array[int] not |", EXPECTED_KEYWORD, ""),
                plain("x not \\\n|", EXPECTED_KEYWORD, ""),
                plain("target(x not |)", EXPECTED_KEYWORD, ""),
                plain("obj.if not |", EXPECTED_KEYWORD, ""),
                plain("$return not |", EXPECTED_KEYWORD, ""),
                plain("$Root/return not |", EXPECTED_KEYWORD, ""),
                plain("$\"Root\"/return not |", EXPECTED_KEYWORD, ""),
                plain("x is not |", TYPE_POSITION, ""),
                plain("not |", UNKNOWN, ""),
                plain("if not |", UNKNOWN, ""),
                plain("elif not |", UNKNOWN, ""),
                plain("while not |", UNKNOWN, ""),
                plain("return not |", UNKNOWN, ""),
                plain("a and not |", UNKNOWN, ""),
                plain("not not |", UNKNOWN, ""),
                plain("var y = not |", UNKNOWN, ""),
                plain("var y not |", UNKNOWN, ""),
                plain("target(not |)", CALL_ARGUMENT, ""),
                plain("obj.not |", UNKNOWN, ""),
                plain("$not |", UNKNOWN, ""),
                plain("a / return not |", UNKNOWN, ""),
                plain("x not|", UNKNOWN, ""),
                plain("x not bar|", IDENTIFIER, "bar"),
                plain("x not\n|", UNKNOWN, "")
        ).flatMap(GdCompletionContextTest::withCrlfAndEmoji);
    }

    private static Stream<Case> withCrlfAndEmoji(Case testCase) {
        return Stream.of(testCase, new Case("# 😀\r\n" + testCase.markedSource().replace("\n", "\r\n"),
                testCase.kind(), testCase.replacement(), testCase.receiver() == null ? null
                : testCase.receiver().replace("\n", "\r\n")));
    }

    @ParameterizedTest
    @MethodSource("cursorGapOwnerCases")
    void gapSlotsRespectGuardEntryAndSignatureOwnership(Case testCase) {
        assertCase(testCase);
        assertTokenCase(testCase);
    }

    static Stream<Case> cursorGapOwnerCases() {
        return Stream.of(
                plain("func f(x):\n\tmatch x:\n\t\t_ when not |", UNKNOWN, ""),
                plain("func f(x):\n\tmatch x:\n\t\tvar y when not |", UNKNOWN, ""),
                plain("func f(x):\n\tmatch x:\n\t\t1 when not |", UNKNOWN, ""),
                plain("func f(x):\n\tmatch x:\n\t\t_ when when not |", EXPECTED_KEYWORD, ""),
                plain("func f(x):\n\tmatch x:\n\t\tvar y when y not |", EXPECTED_KEYWORD, ""),
                plain("func f(x):\n\tmatch x:\n\t\t1 when obj.when not |", EXPECTED_KEYWORD, ""),
                plain("enum E { A: | }", UNKNOWN, ""),
                plain("enum { A = 1, B: | }", UNKNOWN, ""),
                plain("{\"k\": 1: |}", UNKNOWN, ""),
                plain("{a = 1: |}", UNKNOWN, ""),
                plain("{\"k\": |}", IDENTIFIER, ""),
                plain("{\"a\": 1, \"b\": |}", IDENTIFIER, ""),
                plain("{a = 1, \"b\": |}", IDENTIFIER, ""),
                plain("{(table[0]): |}", IDENTIFIER, ""),
                plain("{\"a\": target({\"b\": |})}", IDENTIFIER, ""),
                plain("{\"a\": target({\"b\": 1}): |}", UNKNOWN, ""),
                plain("signal s(...|)", UNKNOWN, ""),
                plain("signal s(a, ...|)", UNKNOWN, ""),
                plain("signal s(x :=|)", UNKNOWN, ""),
                plain("signal s(x : =|)", UNKNOWN, ""),
                plain("signal s(|)", PARAMETER_NAME, ""),
                plain("signal s(a, |)", PARAMETER_NAME, ""),
                plain("signal s(a: int, |)", PARAMETER_NAME, ""),
                plain("signal s(a: |)", TYPE_POSITION, ""),
                plain("func f(a, ...|): pass", PARAMETER_NAME, ""),
                plain("func f(x : =|): pass", IDENTIFIER, "")
        ).flatMap(GdCompletionContextTest::withCrlfAndEmoji);
    }

    @Test
    void pureJavaCompletionKindsRespectTheRecordContract() {
        assertEquals(Set.of(MEMBER_ACCESS, IDENTIFIER, TYPE_POSITION, CALL_ARGUMENT,
                PARAMETER_NAME, SUITE_START, EXPECTED_KEYWORD, UNKNOWN), Set.of(CompletionContext.Kind.values()));
        var zero = new Range(0, 0, new Point(0, 0), new Point(0, 0));
        for (var kind : CompletionContext.Kind.values()) {
            var context = new CompletionContext(kind, zero, kind == MEMBER_ACCESS ? zero : null);
            assertEquals(zero, context.replaceableRange());
            assertEquals(kind, context.kind());
            assertThrows(IllegalArgumentException.class,
                    () -> new CompletionContext(kind, zero, kind == MEMBER_ACCESS ? null : zero));
        }
    }

    @Test
    @SuppressWarnings("deprecation")
    void exampleLegacyProjectionLeavesNewKindsWithoutCandidates() {
        var legacyKinds = List.of(dev.superice.gdparser.infra.treesitter.CompletionContext.Kind.values());
        assertEquals(Set.of("MEMBER_ACCESS", "IDENTIFIER", "TYPE_POSITION", "CALL_ARGUMENT", "UNKNOWN"),
                Set.copyOf(legacyKinds.stream().map(Enum::name).toList()));
        record Projection(Case completion, String expectedLegacyKind, boolean exampleHasCandidateProvider) {
        }
        for (var testCase : List.of(
                new Projection(member("对象.属|性", "属性", "对象"), "MEMBER_ACCESS", true),
                new Projection(plain("数|值", IDENTIFIER, "数值"), "IDENTIFIER", true),
                new Projection(plain("var x: 数|值", TYPE_POSITION, "数值"), "TYPE_POSITION", true),
                new Projection(plain("foo(|)", CALL_ARGUMENT, ""), "CALL_ARGUMENT", false),
                new Projection(plain("|", UNKNOWN, ""), "UNKNOWN", false),
                new Projection(plain("func f(|): pass", PARAMETER_NAME, ""), "UNKNOWN", false),
                new Projection(plain("func f():|", SUITE_START, ""), "UNKNOWN", false),
                new Projection(plain("x not |", EXPECTED_KEYWORD, ""), "UNKNOWN", false))) {
            var completion = testCase.completion();
            var context = assertCase(completion, true);
            // This local projection illustrates one adapter rule without running downstream code.
            var legacy = legacyKinds.stream().filter(kind -> kind.name().equals(context.kind().name()))
                    .findFirst().orElse(dev.superice.gdparser.infra.treesitter.CompletionContext.Kind.UNKNOWN);
            var projected = new dev.superice.gdparser.infra.treesitter.CompletionContext(
                    legacy, context.replaceableRange(), context.receiverRange());
            var routed = switch (legacy) {
                case MEMBER_ACCESS, IDENTIFIER, TYPE_POSITION -> true;
                case CALL_ARGUMENT, UNKNOWN -> false;
            };
            assertEquals(testCase.expectedLegacyKind(), projected.kind().name(), completion.markedSource());
            assertEquals(testCase.exampleHasCandidateProvider(), routed, completion.markedSource());
        }
    }

    private static Case member(String marked, String replacement, String receiver) {
        return new Case(marked, MEMBER_ACCESS, replacement, receiver);
    }

    private static Case plain(String marked, CompletionContext.Kind kind, String replacement) {
        return new Case(marked, kind, replacement, null);
    }

    private static void assertCase(Case testCase) {
        assertCase(testCase, false);
    }

    private static void assertTokenCase(Case testCase) {
        assertCase(testCase, true);
    }

    private static CompletionContext assertCase(Case testCase, boolean tokenOnly) {
        var marked = testCase.markedSource();
        var cursor = marked.indexOf('|');
        assertTrue(cursor >= 0, marked);
        var source = withoutCursor(testCase);
        var bytes = source.getBytes(StandardCharsets.UTF_8);
        var byteOffset = marked.substring(0, cursor).getBytes(StandardCharsets.UTF_8).length;
        var context = tokenOnly
                ? CompletionContextAnalyzer.analyze(source, emptyAst(), byteOffset)
                : FACADE.parseCompletionContext(source, byteOffset);
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
        return context;
    }

    private static String withoutCursor(Case testCase) {
        var source = testCase.markedSource();
        var cursor = source.indexOf('|');
        return source.substring(0, cursor) + source.substring(cursor + 1);
    }

    private static void assertExactReplacementAtName(Case testCase, String source, CompletionContext context) {
        var replacement = testCase.replacement();
        if (!replacement.isEmpty()) {
            var cursor = testCase.markedSource().indexOf('|');
            var start = source.lastIndexOf(replacement, cursor);
            assertTrue(start >= 0 && cursor <= start + replacement.length(), testCase.markedSource());
            var expectedStart = source.substring(0, start).getBytes(StandardCharsets.UTF_8).length;
            var expectedEnd = expectedStart + replacement.getBytes(StandardCharsets.UTF_8).length;
            var actual = context.replaceableRange();
            assertEquals(expectedStart, actual.startByte(), testCase.markedSource());
            assertEquals(expectedEnd, actual.endByte(), testCase.markedSource());
        }
    }

    private static SourceFile emptyAst() {
        return new SourceFile(List.of(), new Range(0, 0, new Point(0, 0), new Point(0, 0)));
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
