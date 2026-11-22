package dev.superice.gdparser.frontend.ast;

import dev.superice.gdparser.frontend.gdscript.GdScriptParser;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// A parser-produced dictionary pattern and a dictionary literal with the same keys.
public final class DictionaryPatternFixture {

    public static final String SOURCE = """
            func match_keys(subject):
                match subject:
                    {"name", CONST_KEY, 1 + 2}: pass
            var literal = {"name": 4, CONST_KEY: 5, 1 + 2: 6}
            """;

    private DictionaryPatternFixture() {
    }

    public static SourceFile parse() {
        var result = new GdScriptParser().parse(SOURCE);
        assertTrue(result.diagnostics().isEmpty(), () -> result.diagnostics().toString());
        return result.ast();
    }

    public static List<DictEntry> patternEntries(SourceFile ast) {
        var function = assertInstanceOf(FunctionDeclaration.class, ast.statements().getFirst());
        var match = assertInstanceOf(MatchStatement.class, function.body().statements().getFirst());
        return assertInstanceOf(DictionaryExpression.class, match.sections().getFirst().patterns().getFirst()).entries();
    }

    public static List<DictEntry> literalEntries(SourceFile ast) {
        var variable = assertInstanceOf(VariableDeclaration.class, ast.statements().getLast());
        return assertInstanceOf(DictionaryExpression.class, variable.value()).entries();
    }

    public static void assertKeys(List<DictEntry> entries) {
        assertEquals(3, entries.size());
        assertEquals("\"name\"", assertInstanceOf(LiteralExpression.class, entries.getFirst().key()).sourceText());
        assertEquals("CONST_KEY", assertInstanceOf(IdentifierExpression.class, entries.get(1).key()).name());
        var sum = assertInstanceOf(BinaryExpression.class, entries.getLast().key());
        assertEquals("+", sum.operator());
        assertEquals("1", assertInstanceOf(LiteralExpression.class, sum.left()).sourceText());
        assertEquals("2", assertInstanceOf(LiteralExpression.class, sum.right()).sourceText());
    }
}
