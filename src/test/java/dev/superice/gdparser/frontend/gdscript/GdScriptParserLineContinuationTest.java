package dev.superice.gdparser.frontend.gdscript;

import dev.superice.gdparser.frontend.ast.ArrayExpression;
import dev.superice.gdparser.frontend.ast.AssignmentExpression;
import dev.superice.gdparser.frontend.ast.BinaryExpression;
import dev.superice.gdparser.frontend.ast.CallExpression;
import dev.superice.gdparser.frontend.ast.CastExpression;
import dev.superice.gdparser.frontend.ast.ExpressionStatement;
import dev.superice.gdparser.frontend.ast.FunctionDeclaration;
import dev.superice.gdparser.frontend.ast.IdentifierExpression;
import dev.superice.gdparser.frontend.ast.IfStatement;
import dev.superice.gdparser.frontend.ast.LiteralExpression;
import dev.superice.gdparser.frontend.ast.SourceFile;
import dev.superice.gdparser.frontend.ast.TypeTestExpression;
import dev.superice.gdparser.frontend.ast.VariableDeclaration;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GdScriptParserLineContinuationTest {

    @Test
    void binaryOperatorsCanContinueOnAnotherLine() {
        var ast = parse("""
                var sum = 1 + \\
                    2
                """);

        var declaration = assertInstanceOf(VariableDeclaration.class, ast.statements().getFirst());
        var binary = assertInstanceOf(BinaryExpression.class, declaration.value());
        assertEquals("+", binary.operator());
        assertEquals("1", assertInstanceOf(LiteralExpression.class, binary.left()).sourceText());
        assertEquals("2", assertInstanceOf(LiteralExpression.class, binary.right()).sourceText());
    }

    @Test
    void comparisonOperatorsCanContinueOnAnotherLine() {
        var ast = parse("""
                func check(a, b):
                    if a > \\
                            b:
                        return a
                    return b
                """);

        var function = assertInstanceOf(FunctionDeclaration.class, ast.statements().getFirst());
        var ifStatement = assertInstanceOf(IfStatement.class, function.body().statements().getFirst());
        var condition = assertInstanceOf(BinaryExpression.class, ifStatement.condition());
        assertEquals(">", condition.operator());
        assertEquals("a", assertInstanceOf(IdentifierExpression.class, condition.left()).name());
        assertEquals("b", assertInstanceOf(IdentifierExpression.class, condition.right()).name());
    }

    @Test
    void assignmentOperatorsCanContinueOnAnotherLine() {
        var ast = parse("""
                func assign(target):
                    var value = \\
                        target
                    value = \\
                        42
                    value += \\
                        1
                """);

        var function = assertInstanceOf(FunctionDeclaration.class, ast.statements().getFirst());
        var body = function.body().statements();
        assertEquals(3, body.size());

        var inferred = assertInstanceOf(VariableDeclaration.class, body.getFirst());
        assertEquals("target", assertInstanceOf(IdentifierExpression.class, inferred.value()).name());

        var plain = assertInstanceOf(AssignmentExpression.class,
                assertInstanceOf(ExpressionStatement.class, body.get(1)).expression());
        assertEquals("=", plain.operator());
        assertEquals("42", assertInstanceOf(LiteralExpression.class, plain.right()).sourceText());

        var augmented = assertInstanceOf(AssignmentExpression.class,
                assertInstanceOf(ExpressionStatement.class, body.getLast()).expression());
        assertEquals("+=", augmented.operator());
        assertEquals("1", assertInstanceOf(LiteralExpression.class, augmented.right()).sourceText());
    }

    @Test
    void typeOperatorsCanContinueOnAnotherLine() {
        var ast = parse("""
                func convert(value):
                    var tested = value is \\
                        int
                    var negated = value is not \\
                        String
                    var casted = value as \\
                        float
                """);

        var function = assertInstanceOf(FunctionDeclaration.class, ast.statements().getFirst());
        var body = function.body().statements();
        assertEquals(3, body.size());

        var tested = assertInstanceOf(TypeTestExpression.class,
                assertInstanceOf(VariableDeclaration.class, body.get(0)).value());
        assertFalse(tested.negated());
        assertEquals("int", tested.targetType().sourceText());

        var negated = assertInstanceOf(TypeTestExpression.class,
                assertInstanceOf(VariableDeclaration.class, body.get(1)).value());
        assertTrue(negated.negated());
        assertEquals("String", negated.targetType().sourceText());

        var casted = assertInstanceOf(CastExpression.class,
                assertInstanceOf(VariableDeclaration.class, body.get(2)).value());
        assertEquals("float", casted.targetType().sourceText());
    }

    @Test
    void callArgumentsCanContinueOnAnotherLine() {
        var ast = parse("""
                func call():
                    foo(1, \\
                        2, 3)
                """);

        var function = assertInstanceOf(FunctionDeclaration.class, ast.statements().getFirst());
        var statement = assertInstanceOf(ExpressionStatement.class, function.body().statements().getFirst());
        var call = assertInstanceOf(CallExpression.class, statement.expression());
        assertEquals(3, call.arguments().size());
        assertEquals("1", assertInstanceOf(LiteralExpression.class, call.arguments().getFirst()).sourceText());
        assertEquals("2", assertInstanceOf(LiteralExpression.class, call.arguments().get(1)).sourceText());
        assertEquals("3", assertInstanceOf(LiteralExpression.class, call.arguments().getLast()).sourceText());
    }

    @Test
    void arrayElementsCanContinueOnAnotherLine() {
        var ast = parse("""
                var values = [1, \\
                    2, \\
                    3]
                """);

        var declaration = assertInstanceOf(VariableDeclaration.class, ast.statements().getFirst());
        var array = assertInstanceOf(ArrayExpression.class, declaration.value());
        assertEquals(3, array.elements().size());
        assertEquals("1", assertInstanceOf(LiteralExpression.class, array.elements().getFirst()).sourceText());
        assertEquals("2", assertInstanceOf(LiteralExpression.class, array.elements().get(1)).sourceText());
        assertEquals("3", assertInstanceOf(LiteralExpression.class, array.elements().getLast()).sourceText());
    }

    private static SourceFile parse(String source) {
        var result = new GdScriptParser().parse(source);
        assertEquals(List.of(), result.diagnostics());
        return result.ast();
    }
}
