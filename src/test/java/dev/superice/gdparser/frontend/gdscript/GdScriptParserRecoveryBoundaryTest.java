package dev.superice.gdparser.frontend.gdscript;

import dev.superice.gdparser.frontend.ast.AstDiagnostic;
import dev.superice.gdparser.frontend.ast.BinaryExpression;
import dev.superice.gdparser.frontend.ast.CallExpression;
import dev.superice.gdparser.frontend.ast.ClassDeclaration;
import dev.superice.gdparser.frontend.ast.DictionaryExpression;
import dev.superice.gdparser.frontend.ast.ErrorExpression;
import dev.superice.gdparser.frontend.ast.FunctionDeclaration;
import dev.superice.gdparser.frontend.ast.IdentifierExpression;
import dev.superice.gdparser.frontend.ast.LambdaExpression;
import dev.superice.gdparser.frontend.ast.LiteralExpression;
import dev.superice.gdparser.frontend.ast.MatchStatement;
import dev.superice.gdparser.frontend.ast.PassStatement;
import dev.superice.gdparser.frontend.ast.Point;
import dev.superice.gdparser.frontend.ast.Range;
import dev.superice.gdparser.frontend.ast.ReturnStatement;
import dev.superice.gdparser.frontend.ast.SignalStatement;
import dev.superice.gdparser.frontend.ast.VariableDeclaration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GdScriptParserRecoveryBoundaryTest {
    private final GdScriptParserFacade facade = new GdScriptParserFacade();

    @Test
    void closerAfterReturnOnSamePhysicalLineBelongsToReturnWithoutExtendingIt() {
        var source = "func f():\r\n\treturn \"😀\" )\r\n\tpass\r\n";
        var result = facade.parse(source);
        var function = assertInstanceOf(FunctionDeclaration.class, result.ast().statements().getFirst());
        var statements = function.body().statements();
        var returnStatement = assertInstanceOf(ReturnStatement.class, statements.getFirst());
        assertEquals(2, statements.size());
        assertInstanceOf(PassStatement.class, statements.getLast());
        assertByteSpan(returnStatement.range(), 12, 25);
        assertEquals(1, result.diagnostics().size());
        var diagnostic = result.diagnostics().getFirst();
        assertEquals(DiagnosticCodes.UNEXPECTED_CLOSER, diagnostic.code());
        assertEquals(new Range(26, 27, new Point(1, 15), new Point(1, 16)), diagnostic.range());
        assertSame(returnStatement, diagnostic.recovery().owner());
        assertEquals("statement.trailing", diagnostic.recovery().facet());
    }

    @Test
    void nestedClassCloserBetweenMembersBelongsToInnermostClass() {
        var source = "class Outer:\n\tclass Inner:\n\t\tpass\n\t\t)\n\t\tpass\n\tpass\n";
        var result = facade.parse(source);
        var outer = assertInstanceOf(ClassDeclaration.class, result.ast().statements().getFirst());
        var inner = assertInstanceOf(ClassDeclaration.class, outer.body().statements().getFirst());
        assertEquals(2, inner.body().statements().size());
        assertByteSpan(inner.body().statements().getFirst().range(), 29, 33);
        assertInstanceOf(PassStatement.class, inner.body().statements().getLast());
        assertInstanceOf(PassStatement.class, outer.body().statements().getLast());
        assertEquals(1, result.diagnostics().size());
        var diagnostic = result.diagnostics().getFirst();
        assertEquals(DiagnosticCodes.UNEXPECTED_CLOSER, diagnostic.code());
        assertByteSpan(diagnostic.range(), 36, 37);
        assertSame(inner, diagnostic.recovery().owner());
        assertEquals("class.body", diagnostic.recovery().facet());
    }

    @ParameterizedTest
    @ValueSource(strings = {"func(): pass", "func():\n\t\tpass"})
    void closerOnLineAfterLambdaDoesNotBelongToItsVariable(String lambda) {
        var source = "func f():\n\tvar l = " + lambda + "\n\t)\n\tpass\n";
        var result = facade.parse(source);
        assertEquals(1, result.ast().statements().size());
        var function = assertInstanceOf(FunctionDeclaration.class, result.ast().statements().getFirst());
        assertEquals(2, function.body().statements().size());
        assertInstanceOf(PassStatement.class, function.body().statements().getLast());
        var variable = assertInstanceOf(VariableDeclaration.class, function.body().statements().getFirst());
        assertEquals(source.indexOf("pass") + 4, variable.range().endByte());
        assertEquals(1, result.diagnostics().size());
        var diagnostic = result.diagnostics().getFirst();
        assertEquals(DiagnosticCodes.UNEXPECTED_CLOSER, diagnostic.code());
        assertSame(function, diagnostic.recovery().owner());
        assertEquals("suite.body", diagnostic.recovery().facet());
    }

    @Test
    void dedentedCloserAfterMultilineLambdaHasNoOwner() {
        var result = facade.parse("var f = func():\n\tpass\n)\nvar after = 1\n");
        assertEquals(2, result.ast().statements().size());
        assertEquals(1, result.diagnostics().size());
        var diagnostic = result.diagnostics().getFirst();
        assertEquals(DiagnosticCodes.UNEXPECTED_CLOSER, diagnostic.code());
        assertNull(diagnostic.recovery());
    }

    @Test
    void semicolonSeparatesOrphanCloserFromPreviousStatement() {
        for (var source : List.of(
                "func f():\n\tpass; )\n\tpass\n",
                "class C:\n\tpass; )\n\tpass\n",
                "var a = 1; )\nvar after = 1\n",
                "func f():\n\tpass ); pass\n")) {
            var result = facade.parse(source);
            var first = result.ast().statements().getFirst();
            assertEquals(1, result.diagnostics().size(), source);
            var diagnostic = result.diagnostics().getFirst();
            assertEquals(DiagnosticCodes.UNEXPECTED_CLOSER, diagnostic.code());
            int closer = source.lastIndexOf(')');
            assertByteSpan(diagnostic.range(), closer, closer + 1);
            if (first instanceof FunctionDeclaration function) {
                var statements = function.body().statements();
                assertEquals(2, statements.size());
                var pass = assertInstanceOf(PassStatement.class, statements.getFirst());
                assertEquals(source.indexOf("pass") + 4, pass.range().endByte());
                assertInstanceOf(PassStatement.class, statements.getLast());
                var afterSemicolon = source.contains("; )");
                assertSame(afterSemicolon ? function : pass, diagnostic.recovery().owner());
                assertEquals(afterSemicolon ? "suite.body" : "statement.trailing", diagnostic.recovery().facet());
            } else if (first instanceof ClassDeclaration declaration) {
                assertEquals(2, declaration.body().statements().size());
                assertInstanceOf(PassStatement.class, declaration.body().statements().getLast());
                assertSame(declaration, diagnostic.recovery().owner());
                assertEquals("class.body", diagnostic.recovery().facet());
            } else {
                assertNull(diagnostic.recovery());
                assertEquals("after", assertInstanceOf(VariableDeclaration.class,
                        result.ast().statements().getLast()).name());
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"\n\tvar leaked = 1", " return (1"})
    void rejectedBlockInInlinePropertyStaysIsolatedAndRetainsIndependentDiagnostics(String body) {
        var source = "var p: int: get = g, set(v):" + body + "\nvar after = 2\n";
        var result = facade.parse(source);
        assertEquals(2, result.ast().statements().size());
        var property = assertInstanceOf(VariableDeclaration.class, result.ast().statements().getFirst());
        assertEquals("p", property.name());
        assertEquals("after", assertInstanceOf(VariableDeclaration.class, result.ast().statements().getLast()).name());
        var codes = result.diagnostics().stream().map(AstDiagnostic::code).toList();
        assertEquals(body.startsWith("\n") ? List.of(DiagnosticCodes.PROPERTY_INVALID_ACCESSOR)
                : List.of(DiagnosticCodes.PROPERTY_INVALID_ACCESSOR, DiagnosticCodes.MISSING_CLOSER_GROUPING), codes);
        assertByteSpan(result.diagnostics().getFirst().range(), source.indexOf("set"), source.indexOf("set") + 3);
        for (var diagnostic : result.diagnostics()) {
            assertSame(property, diagnostic.recovery().owner());
            assertEquals("variable.property", diagnostic.recovery().facet());
        }
    }

    @Test
    void rejectedInlineAccessorYieldsOnlyOuterPropertyCommas() {
        for (var propertyHead : List.of("var p:\n\tget = g,\n\t", "var p: int: get = g, ")) {
            for (var body : List.of("pass", "return f(1, 2)", "return [1, 2]", "return {1: 2, 3: 4}")) {
                var header = propertyHead + "set(v): " + body + ", set = s";
                var source = header + "\nvar after = 2\n";
                var result = facade.parse(source);
                assertEquals(2, result.ast().statements().size(), source);
                var property = assertInstanceOf(VariableDeclaration.class, result.ast().statements().getFirst());
                assertEquals(header.length(), property.range().endByte(), source);
                assertEquals("after", assertInstanceOf(
                        VariableDeclaration.class, result.ast().statements().getLast()).name());
                assertEquals(List.of(DiagnosticCodes.PROPERTY_INVALID_ACCESSOR),
                        result.diagnostics().stream().map(AstDiagnostic::code).toList(), source);
                var diagnostic = result.diagnostics().getFirst();
                assertSame(property, diagnostic.recovery().owner());
                assertEquals("variable.property", diagnostic.recovery().facet());

                // A following duplicate proves the recovered setter was parsed, not just drained.
                var duplicate = facade.parse(header + ", set = other\nvar after = 2\n");
                assertEquals(List.of(DiagnosticCodes.PROPERTY_INVALID_ACCESSOR, DiagnosticCodes.PROPERTY_DUPLICATE_SETTER),
                        duplicate.diagnostics().stream().map(AstDiagnostic::code).toList(), source);
            }
        }
    }

    @Test
    void lambdaInsideRejectedAccessorDoesNotEndTheNextFunctionInitializer() {
        for (var operator : List.of("+", "*")) {
            var source = """
                    var p:
                    	get = g,
                    	set(v): var l = func(): pass, set = s
                    func after():
                    	var a = 1 %s 2
                    """.formatted(operator);
            var result = facade.parse(source);
            GdScriptParserRangeInvariantTest.assertDiagnosticInvariants(source, result, () -> source);
            assertEquals(2, result.ast().statements().size());
            var property = assertInstanceOf(VariableDeclaration.class, result.ast().statements().getFirst());
            var function = assertInstanceOf(FunctionDeclaration.class, result.ast().statements().getLast());
            assertEquals("after", function.name());
            assertEquals(1, function.body().statements().size());
            var variable = assertInstanceOf(VariableDeclaration.class, function.body().statements().getFirst());
            assertEquals("a", variable.name());
            var binary = assertInstanceOf(BinaryExpression.class, variable.value());
            assertEquals(operator, binary.operator());
            assertEquals("1", assertInstanceOf(LiteralExpression.class, binary.left()).sourceText());
            assertEquals("2", assertInstanceOf(LiteralExpression.class, binary.right()).sourceText());
            assertEquals(List.of(DiagnosticCodes.PROPERTY_INVALID_ACCESSOR),
                    result.diagnostics().stream().map(AstDiagnostic::code).toList());
            var recovery = result.diagnostics().getFirst().recovery();
            assertSame(property, recovery.owner());
            assertEquals("variable.property", recovery.facet());
        }
    }

    @Test
    void matchBodyYieldsItsAncestorsClosingParenthesis() {
        var source = "var t = g(func(): match 0:\n\t0: pass\n\t)\nvar after = 1\n";
        var result = facade.parse(source);
        assertEquals(2, result.ast().statements().size());
        var variable = assertInstanceOf(VariableDeclaration.class, result.ast().statements().getFirst());
        var call = assertInstanceOf(CallExpression.class, variable.value());
        assertEquals(source.lastIndexOf(')') + 1, call.range().endByte());
        var lambda = assertInstanceOf(LambdaExpression.class, call.arguments().getFirst());
        var match = assertInstanceOf(MatchStatement.class, lambda.body().statements().getFirst());
        assertEquals(1, match.sections().size());
        assertInstanceOf(PassStatement.class, match.sections().getFirst().body().statements().getFirst());
        assertEquals("after", assertInstanceOf(VariableDeclaration.class, result.ast().statements().getLast()).name());
        assertTrue(result.diagnostics().isEmpty(), () -> result.diagnostics().toString());
    }

    @Test
    void signalDiscardedDefaultStillHasAReachableOwner() {
        var source = "signal f(x, x = func(): return (1";
        var result = facade.parse(source);
        var signal = assertInstanceOf(SignalStatement.class, result.ast().statements().getFirst());
        assertEquals(1, signal.parameters().size());
        assertEquals("x", signal.parameters().getFirst().name());
        assertNull(signal.parameters().getFirst().defaultValue());
        var diagnostic = result.diagnostics().stream()
                .filter(d -> DiagnosticCodes.MISSING_CLOSER_GROUPING.equals(d.code())).findFirst().orElseThrow();
        assertByteSpan(diagnostic.range(), source.length(), source.length());
        assertSame(signal, diagnostic.recovery().owner());
        assertEquals("signal.parameter", diagnostic.recovery().facet());
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "-> Array[int | type_bracket",
            ": assert(1 | assert",
            ": return [1 | array",
    })
    void discardedLambdaClosersFollowItsOuterSignature(String lambdaTail, String closer) {
        var source = "func f(x, x = func() " + lambdaTail;
        var result = facade.parse(source);
        var function = assertInstanceOf(FunctionDeclaration.class, result.ast().statements().getFirst());
        assertEquals(1, function.parameters().size());
        var diagnostic = result.diagnostics().stream()
                .filter(d -> ("parse.missing_closer." + closer).equals(d.code())).findFirst().orElseThrow();
        assertByteSpan(diagnostic.range(), source.length(), source.length());
        assertSame(function, diagnostic.recovery().owner());
        assertEquals("function.signature", diagnostic.recovery().facet());
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "get = g,\\n\\tget:\\n\\t\\tpass | get | duplicate_getter",
            "get:\\n\\t\\tpass\\n\\tget = g | get | duplicate_getter",
            "set = s,\\n\\tset:\\n\\t\\tpass | set | duplicate_setter",
            "set(v):\\n\\t\\tpass\\n\\tset = s | set | duplicate_setter",
            "get = g,\\n\\tset:\\n\\t\\tpass | set | invalid_accessor",
            "get:\\n\\t\\tpass\\n\\tset = s | set | invalid_accessor",
    })
    void mixedStyleAccessorsHaveOnePrimaryDiagnostic(String accessors, String keyword, String expected) {
        var source = ("# 😀\nvar p:\n\t" + accessors + "\nvar after = 1\n")
                .replace("\\n", "\n").replace("\\t", "\t").replace("\n", "\r\n");
        var result = facade.parse(source);
        var statements = result.ast().statements();
        assertEquals(3, statements.size());
        var property = assertInstanceOf(VariableDeclaration.class, statements.get(1));
        assertEquals("p", property.name());
        assertEquals("after", assertInstanceOf(VariableDeclaration.class, statements.getLast()).name());
        assertEquals(1, result.diagnostics().size());
        var diagnostic = result.diagnostics().getFirst();
        assertEquals("parse.property." + expected, diagnostic.code());
        assertSame(property, diagnostic.recovery().owner());
        assertEquals("variable.property", diagnostic.recovery().facet());
        var start = source.lastIndexOf(keyword);
        assertByteSpan(diagnostic.range(), byteOffset(source, start), byteOffset(source, start + keyword.length()));
    }

    @Test
    void healthyAndIndependentlyMalformedPropertyAccessorsRemainDistinct() {
        var valid = "var p:\n\tget = g, set = s\nvar q:\n\tget:\n\t\tpass\n\tset(v):\n\t\tpass\n";
        assertTrue(facade.parse(valid).diagnostics().isEmpty());
        var malformed = facade.parse("var p:\n\tset:\n\t\tpass\nvar after = 1\n");
        assertTrue(malformed.diagnostics().stream()
                .anyMatch(diagnostic -> "parse.declaration.missing_name".equals(diagnostic.code())));
        assertEquals("after",
                assertInstanceOf(VariableDeclaration.class, malformed.ast().statements().getLast()).name());
    }

    @Test
    void discardedAccessorStillReportsIndependentSuiteDamageAndDoesNotTaintNextAccessor() {
        var source = "var p:\n\tget = g,\n\tget:\n\t\tvar local = [1 2]\n\tset = s\nvar after = 1\n";
        var result = facade.parse(source);
        var property = assertInstanceOf(VariableDeclaration.class, result.ast().statements().getFirst());
        assertEquals(List.of(DiagnosticCodes.PROPERTY_DUPLICATE_GETTER, DiagnosticCodes.MISSING_SEPARATOR_COMMA),
                result.diagnostics().stream().map(AstDiagnostic::code).toList());
        for (var diagnostic : result.diagnostics()) {
            assertSame(property, diagnostic.recovery().owner());
            assertEquals("variable.property", diagnostic.recovery().facet());
        }
        assertEquals("after", assertInstanceOf(VariableDeclaration.class, result.ast().statements().getLast()).name());
    }

    @ParameterizedTest
    @ValueSource(strings = {"var d = {(\n\ta) 2}\n", "var d = {(\n\ta) 2", "# 😀\r\nvar d = {(\r\n\ta) 2}\r\n"})
    void dictionaryValueCanFollowPhysicalEndOfGroupedKey(String source) {
        var result = facade.parse(source);
        var declaration = assertInstanceOf(VariableDeclaration.class, result.ast().statements().getLast());
        var dictionary = assertInstanceOf(DictionaryExpression.class, declaration.value());
        assertEquals(1, dictionary.entries().size());
        var entry = dictionary.entries().getFirst();
        assertEquals("a", assertInstanceOf(IdentifierExpression.class, entry.key()).name());
        assertEquals("2", assertInstanceOf(LiteralExpression.class, entry.value()).sourceText());
        var keyStart = byteOffset(source, source.indexOf("\ta") + 1);
        var groupStart = byteOffset(source, source.indexOf('('));
        var closer = byteOffset(source, source.indexOf(')'));
        var valueEnd = byteOffset(source, source.indexOf('2') + 1);
        assertByteSpan(entry.key().range(), keyStart, keyStart + 1);
        assertByteSpan(entry.range(), groupStart, valueEnd);
        var codes = result.diagnostics().stream().map(AstDiagnostic::code).toList();
        assertEquals(source.endsWith("2")
                ? List.of(DiagnosticCodes.MISSING_SEPARATOR_DICTIONARY, DiagnosticCodes.MISSING_CLOSER_DICTIONARY)
                : List.of(DiagnosticCodes.MISSING_SEPARATOR_DICTIONARY), codes);
        var diagnostic = result.diagnostics().getFirst();
        assertByteSpan(diagnostic.range(), closer, closer + 1);
        assertSame(declaration, diagnostic.recovery().owner());
        assertEquals("variable.initializer", diagnostic.recovery().facet());
    }

    @Test
    void dictionaryValueAndNextKeyRemainDistinct() {
        var sameLine = facade.parse("var d = {1 2}\n");
        var literal = dictionary(sameLine.ast().statements().getFirst());
        assertEquals(1, literal.entries().size());
        assertEquals("2", assertInstanceOf(LiteralExpression.class, literal.entries().getFirst().value()).sourceText());
        assertEquals(List.of(DiagnosticCodes.MISSING_SEPARATOR_DICTIONARY),
                sameLine.diagnostics().stream().map(AstDiagnostic::code).toList());

        var nextLine = facade.parse("var d = {\"a\"\n\t\"b\": 1}\n");
        literal = dictionary(nextLine.ast().statements().getFirst());
        assertEquals(2, literal.entries().size());
        assertInstanceOf(ErrorExpression.class, literal.entries().getFirst().value());
        assertEquals("\"b\"", assertInstanceOf(LiteralExpression.class, literal.entries().getLast().key()).sourceText());
        assertEquals(List.of(DiagnosticCodes.MISSING_SEPARATOR_DICTIONARY, DiagnosticCodes.MISSING_SEPARATOR_COMMA),
                nextLine.diagnostics().stream().map(AstDiagnostic::code).toList());

        var healthy = facade.parse("var d = {(\n\ta): 2}\n");
        assertTrue(healthy.diagnostics().isEmpty());
        var entry = dictionary(healthy.ast().statements().getFirst()).entries().getFirst();
        assertByteSpan(entry.key().range(), 12, 13);
        assertByteSpan(entry.range(), 9, 17);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "func f(a:\n\t-> int: pass\nvar after = 1\n",
            "func f(a:\n\t-> int:\n\t\tpass\nvar after = 1\n",
            "func f(a -> int:\n\tpass\nvar after = 1\n",
            "# 😀\r\nfunc f(a:\r\n\t-> int: pass\r\nvar after = 1\r\n",
    })
    void damagedParameterListHandsReturnArrowBackToItsSignature(String source) {
        var result = facade.parse(source);
        var statements = result.ast().statements();
        var function = assertInstanceOf(FunctionDeclaration.class, statements.get(statements.size() - 2));
        assertEquals("a", function.parameters().getFirst().name());
        assertNotNull(function.returnType());
        assertEquals("int", function.returnType().sourceText());
        var typeStart = byteOffset(source, source.indexOf("int:"));
        var colonEnd = typeStart + 4;
        var passStart = byteOffset(source, source.indexOf("pass"));
        assertByteSpan(function.returnType().range(), typeStart, typeStart + 3);
        assertEquals(source.contains("int: pass") ? passStart : colonEnd, function.body().range().startByte());
        assertByteSpan(assertInstanceOf(PassStatement.class, function.body().statements().getFirst()).range(),
                passStart, passStart + 4);
        assertEquals("after", assertInstanceOf(VariableDeclaration.class, statements.getLast()).name());
        var diagnostic = result.diagnostics().stream()
                .filter(d -> DiagnosticCodes.MISSING_CLOSER_PARAMETER_LIST.equals(d.code())).findFirst().orElseThrow();
        var parameterEnd = byteOffset(source, source.indexOf("(a") + (source.contains("(a:") ? 3 : 2));
        assertByteSpan(diagnostic.range(), parameterEnd, parameterEnd);
        assertSame(function, diagnostic.recovery().owner());
        assertEquals("function.signature", diagnostic.recovery().facet());
        assertThrows(IllegalStateException.class, () -> facade.parseStrict(source));
    }

    @Test
    void returnArrowRecoveryDoesNotCrossCompleteSuiteHead() {
        var complete = facade.parse("func f(a):\n\t-> int: pass\nvar after = 1\n");
        var function = assertInstanceOf(FunctionDeclaration.class, complete.ast().statements().getFirst());
        assertNull(function.returnType());
        var diagnostic = complete.diagnostics().getFirst();
        assertEquals(DiagnosticCodes.SYNTAX_UNEXPECTED_TOKEN, diagnostic.code());
        assertSame(function, diagnostic.recovery().owner());
        assertEquals("suite.body", diagnostic.recovery().facet());
        assertEquals("after",
                assertInstanceOf(VariableDeclaration.class, complete.ast().statements().getLast()).name());

        var pass = facade.parse("func f(a, b:\n\tpass\n");
        function = assertInstanceOf(FunctionDeclaration.class, pass.ast().statements().getFirst());
        assertEquals(2, function.parameters().size());
        assertNull(function.returnType());
        assertInstanceOf(PassStatement.class, function.body().statements().getFirst());

        var legal = facade.parse("func f(\n\ta:\n\t\tArray[int]\n) -> int:\n\tpass\nfunc empty() -> int\n");
        assertTrue(legal.diagnostics().isEmpty());
        function = assertInstanceOf(FunctionDeclaration.class, legal.ast().statements().getFirst());
        assertEquals("Array[int]", function.parameters().getFirst().type().sourceText());
        assertEquals("int", function.returnType().sourceText());
        assertTrue(assertInstanceOf(FunctionDeclaration.class, legal.ast().statements().getLast())
                .body().statements().isEmpty());
        assertInstanceOf(LambdaExpression.class, assertInstanceOf(VariableDeclaration.class,
                facade.parseStrict("var l = func(a) -> int: return a\n").statements().getFirst()).value());
    }

    @Test
    void crossLineArrowRecoversWithinLambdaWithoutSwallowingItsCallCloser() {
        var source = "var l = g(func(a:\n\t-> int: pass, 2)\nvar after = 1\n";
        var result = facade.parse(source);
        var variable = assertInstanceOf(VariableDeclaration.class, result.ast().statements().getFirst());
        var call = assertInstanceOf(CallExpression.class, variable.value());
        assertEquals(2, call.arguments().size());
        var lambda = assertInstanceOf(LambdaExpression.class, call.arguments().getFirst());
        assertEquals("2", assertInstanceOf(LiteralExpression.class, call.arguments().getLast()).sourceText());
        assertEquals("a", lambda.parameters().getFirst().name());
        assertEquals("int", lambda.returnType().sourceText());
        assertInstanceOf(PassStatement.class, lambda.body().statements().getFirst());
        assertEquals("after", assertInstanceOf(VariableDeclaration.class, result.ast().statements().getLast()).name());
        assertEquals(List.of(DiagnosticCodes.MISSING_TYPE_LAMBDA_PARAMETER, DiagnosticCodes.MISSING_CLOSER_PARAMETER_LIST),
                result.diagnostics().stream().map(AstDiagnostic::code).toList());
        assertSame(lambda, result.diagnostics().getLast().recovery().owner());
        assertEquals("function.signature", result.diagnostics().getLast().recovery().facet());
    }

    private static DictionaryExpression dictionary(Object statement) {
        return assertInstanceOf(DictionaryExpression.class, assertInstanceOf(VariableDeclaration.class, statement).value());
    }

    private static int byteOffset(String source, int utf16Index) {
        return source.substring(0, utf16Index).getBytes(StandardCharsets.UTF_8).length;
    }

    private static void assertByteSpan(Range range, int start, int end) {
        assertEquals(start, range.startByte());
        assertEquals(end, range.endByte());
    }
}
