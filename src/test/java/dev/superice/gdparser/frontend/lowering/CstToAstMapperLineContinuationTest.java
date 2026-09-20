package dev.superice.gdparser.frontend.lowering;

import dev.superice.gdparser.frontend.ast.ArrayExpression;
import dev.superice.gdparser.frontend.ast.AssignmentExpression;
import dev.superice.gdparser.frontend.ast.AstDiagnosticSeverity;
import dev.superice.gdparser.frontend.ast.AstMappingResult;
import dev.superice.gdparser.frontend.ast.BinaryExpression;
import dev.superice.gdparser.frontend.ast.CallExpression;
import dev.superice.gdparser.frontend.ast.CastExpression;
import dev.superice.gdparser.frontend.ast.ExpressionStatement;
import dev.superice.gdparser.frontend.ast.FunctionDeclaration;
import dev.superice.gdparser.frontend.ast.IdentifierExpression;
import dev.superice.gdparser.frontend.ast.IfStatement;
import dev.superice.gdparser.frontend.ast.LiteralExpression;
import dev.superice.gdparser.frontend.ast.TypeTestExpression;
import dev.superice.gdparser.frontend.ast.VariableDeclaration;
import dev.superice.gdparser.infra.treesitter.GdParserFacade;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/// Regression tests for lowering sources that use `\` line continuations.
/// The grammar emits `line_continuation` as a named extra node, so the mapper must
/// ignore it when enumerating children and when recovering operator source text.
class CstToAstMapperLineContinuationTest {

    private static GdParserFacade parserFacade;
    private static CstToAstMapper mapper;

    @BeforeAll
    static void setUp() {
        parserFacade = GdParserFacade.withDefaultLanguage();
        mapper = new CstToAstMapper();
    }

    @Test
    void binaryOperatorsShouldIgnoreLineContinuations() {
        var result = map("""
                var sum = 1 + \\
                    2
                """);

        assertNoErrors(result);
        var declaration = assertInstanceOf(VariableDeclaration.class, result.ast().statements().getFirst());
        var binary = assertInstanceOf(BinaryExpression.class, declaration.value());
        assertEquals("+", binary.operator());
        assertEquals("1", assertInstanceOf(LiteralExpression.class, binary.left()).sourceText());
        assertEquals("2", assertInstanceOf(LiteralExpression.class, binary.right()).sourceText());
    }

    @Test
    void comparisonOperatorsShouldIgnoreLineContinuations() {
        var result = map("""
                func check(a, b):
                    if a > \\
                            b:
                        return a
                    return b
                """);

        assertNoErrors(result);
        var function = assertInstanceOf(FunctionDeclaration.class, result.ast().statements().getFirst());
        var ifStatement = assertInstanceOf(IfStatement.class, function.body().statements().getFirst());
        var condition = assertInstanceOf(BinaryExpression.class, ifStatement.condition());
        assertEquals(">", condition.operator());
        assertEquals("a", assertInstanceOf(IdentifierExpression.class, condition.left()).name());
        assertEquals("b", assertInstanceOf(IdentifierExpression.class, condition.right()).name());
    }

    @Test
    void assignmentOperatorsShouldIgnoreLineContinuations() {
        var result = map("""
                func assign(target):
                    var value = \\
                        target
                    value = \\
                        42
                    value += \\
                        1
                """);

        assertNoErrors(result);
        var function = assertInstanceOf(FunctionDeclaration.class, result.ast().statements().getFirst());
        var body = function.body().statements();

        var inferred = assertInstanceOf(VariableDeclaration.class, body.get(0));
        assertEquals("target", assertInstanceOf(IdentifierExpression.class, inferred.value()).name());

        var plain = assertInstanceOf(AssignmentExpression.class,
                assertInstanceOf(ExpressionStatement.class, body.get(1)).expression());
        assertEquals("=", plain.operator());
        assertEquals("42", assertInstanceOf(LiteralExpression.class, plain.right()).sourceText());

        var augmented = assertInstanceOf(AssignmentExpression.class,
                assertInstanceOf(ExpressionStatement.class, body.get(2)).expression());
        assertEquals("+=", augmented.operator());
        assertEquals("1", assertInstanceOf(LiteralExpression.class, augmented.right()).sourceText());
    }

    @Test
    void isAndAsOperatorsShouldIgnoreLineContinuations() {
        var result = map("""
                func convert(value):
                    var tested = value is \\
                        int
                    var negated = value is not \\
                        String
                    var casted = value as \\
                        float
                """);

        assertNoErrors(result);
        var function = assertInstanceOf(FunctionDeclaration.class, result.ast().statements().getFirst());
        var body = function.body().statements();

        var tested = assertInstanceOf(TypeTestExpression.class,
                assertInstanceOf(VariableDeclaration.class, body.get(0)).value());
        assertFalse(tested.negated());
        assertEquals("int", tested.targetType().sourceText());

        var negated = assertInstanceOf(TypeTestExpression.class,
                assertInstanceOf(VariableDeclaration.class, body.get(1)).value());
        assertEquals(true, negated.negated());
        assertEquals("String", negated.targetType().sourceText());

        var casted = assertInstanceOf(CastExpression.class,
                assertInstanceOf(VariableDeclaration.class, body.get(2)).value());
        assertEquals("float", casted.targetType().sourceText());
    }

    @Test
    void callArgumentsShouldIgnoreLineContinuations() {
        var result = map("""
                func call():
                    foo(1, \\
                        2, 3)
                """);

        assertNoErrors(result);
        var function = assertInstanceOf(FunctionDeclaration.class, result.ast().statements().getFirst());
        var statement = assertInstanceOf(ExpressionStatement.class, function.body().statements().getFirst());
        var call = assertInstanceOf(CallExpression.class, statement.expression());
        assertEquals(3, call.arguments().size());
        assertEquals("1", assertInstanceOf(LiteralExpression.class, call.arguments().get(0)).sourceText());
        assertEquals("2", assertInstanceOf(LiteralExpression.class, call.arguments().get(1)).sourceText());
        assertEquals("3", assertInstanceOf(LiteralExpression.class, call.arguments().get(2)).sourceText());
    }

    @Test
    void arrayElementsShouldIgnoreLineContinuations() {
        var result = map("""
                var values = [1, \\
                    2, \\
                    3]
                """);

        assertNoErrors(result);
        var declaration = assertInstanceOf(VariableDeclaration.class, result.ast().statements().getFirst());
        var array = assertInstanceOf(ArrayExpression.class, declaration.value());
        assertEquals(3, array.elements().size());
        assertEquals("1", assertInstanceOf(LiteralExpression.class, array.elements().get(0)).sourceText());
        assertEquals("2", assertInstanceOf(LiteralExpression.class, array.elements().get(1)).sourceText());
        assertEquals("3", assertInstanceOf(LiteralExpression.class, array.elements().get(2)).sourceText());
    }

    private static void assertNoErrors(AstMappingResult result) {
        assertFalse(result.diagnostics().stream()
                        .anyMatch(diagnostic -> diagnostic.severity() == AstDiagnosticSeverity.ERROR),
                () -> "Unexpected errors: " + result.diagnostics());
        assertEquals(0, result.diagnostics().stream()
                        .filter(diagnostic -> diagnostic.severity() == AstDiagnosticSeverity.WARNING)
                        .count(),
                () -> "Unexpected warnings: " + result.diagnostics());
    }

    private static AstMappingResult map(String source) {
        var root = parserFacade.parseCstRoot(source);
        return mapper.map(source, root);
    }
}
