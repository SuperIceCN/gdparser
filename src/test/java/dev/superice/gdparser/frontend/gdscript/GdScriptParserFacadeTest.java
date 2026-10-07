package dev.superice.gdparser.frontend.gdscript;

import dev.superice.gdparser.frontend.ast.AstDiagnostic;
import dev.superice.gdparser.frontend.ast.AstDiagnosticSeverity;
import dev.superice.gdparser.frontend.ast.AstMappingResult;
import dev.superice.gdparser.frontend.ast.FunctionDeclaration;
import dev.superice.gdparser.frontend.serialize.AstSexprSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Facade-level contract tests: entry semantics (tolerant/strict/tokenize raw stream) and the
/// shared-instance concurrency guarantee. Parser behavior itself is anchored in
/// `GdScriptParserTest`/`GdScriptParserErrorRecoveryTest`; here we only pin what the facade
/// adds on top.
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class GdScriptParserFacadeTest {

    private static final AstSexprSerializer SERIALIZER = new AstSexprSerializer();

    private final GdScriptParserFacade facade = new GdScriptParserFacade();

    @Test
    void parseReturnsAstAndNoErrorsForValidSource() {
        var result = facade.parse("func f():\n\tpass\n");
        assertEquals(List.of(), errorMessages(result));
        assertInstanceOf(FunctionDeclaration.class, result.ast().statements().getFirst());
    }

    @Test
    void parseToleratesIllegalSourceAndReturnsPartialAst() {
        // Tolerant entry: diagnostics instead of exceptions, surviving siblings preserved.
        var result = facade.parse("var a = \nvar b = 2\n");
        assertTrue(errorMessages(result).contains("Expected expression for variable initial value after \"=\"."));
        assertEquals(2, result.ast().statements().size());
    }

    @Test
    void parseStrictReturnsAstForValidSource() {
        var ast = facade.parseStrict("var a = 1\n");
        assertEquals(1, ast.statements().size());
    }

    @Test
    void parseStrictThrowsOnFirstErrorDiagnostic() {
        // Two distinct ERRORs: the thrown message must come from the FIRST one (initializer),
        // not the later `static` misuse — pins ordering, not just presence.
        var exception = assertThrows(IllegalStateException.class,
                () -> facade.parseStrict("var a = \nstatic const B = 1\n"));
        // Mirrors CstToAstMapper.mapStrict: first ERROR diagnostic, "<phase> failed at <nodeType>: <message>".
        assertEquals("Parsing failed at ERROR: Expected expression for variable initial value after \"=\".",
                exception.getMessage());
    }

    @Test
    void parseStrictSkipsWarningsAndThrowsFirstError() {
        // WARNING (key-only pattern) precedes the ERROR (missing initializer): strict mode must
        // skip the warning and throw the first ERROR, not the first diagnostic.
        var source = "func f(v):\n\tmatch v:\n\t\t{\"name\"}:\n\t\t\tpass\n\tvar x = \n";
        var diagnostics = facade.parse(source).diagnostics();
        assertEquals(AstDiagnosticSeverity.WARNING, diagnostics.getFirst().severity());
        assertEquals(AstDiagnosticSeverity.ERROR, diagnostics.get(1).severity());
        var exception = assertThrows(IllegalStateException.class, () -> facade.parseStrict(source));
        assertEquals("Parsing failed at ERROR: Expected expression for variable initial value after \"=\".",
                exception.getMessage());
    }

    @Test
    void parseStrictDoesNotThrowOnWarningsOnly() {
        // Key-only dictionary pattern: exactly one WARNING, zero ERRORs (registered AST gap).
        var ast = facade.parseStrict("func f(v):\n\tmatch v:\n\t\t{\"name\"}:\n\t\t\tpass\n");
        assertEquals(1, ast.statements().size());
    }

    @Test
    void tokenizeReturnsRawStreamIncludingErrorsUpToEof() {
        var tokens = facade.tokenize("a\u2003b\n");
        assertEquals(GdTokenType.EOF, tokens.getLast().type());
        var errors = tokens.stream().filter(GdToken::isError).toList();
        assertEquals(1, errors.size());
        assertEquals("Invalid white space character U+2003.", errors.getFirst().errorMessage());
    }

    @Test
    void tokenizeRawStreamExposesLayoutTokensSuppressedDuringParse() {
        // Raw stream has no parser pushing multiline mode, so the grouping's NEWLINE/INDENT
        // survive tokenize; the parser-driven stream inside parse() suppresses them, which is
        // why the same source parses cleanly.
        var source = "var x = (\n\t1 +\n\t2\n)\n";
        var types = facade.tokenize(source).stream().map(GdToken::type).toList();
        var open = types.indexOf(GdTokenType.PARENTHESIS_OPEN);
        var close = types.lastIndexOf(GdTokenType.PARENTHESIS_CLOSE);
        var inside = types.subList(open, close);
        assertTrue(inside.contains(GdTokenType.NEWLINE), "raw stream must keep NEWLINE inside groupings");
        assertTrue(inside.contains(GdTokenType.INDENT), "raw stream must keep INDENT inside groupings");
        assertEquals(List.of(), errorMessages(facade.parse(source)));
    }

    @Test
    void tokenizeRawStreamMayReportIndentErrorsForParserLegalSource() {
        // The closing line dedents to a width recorded only inside the grouping: raw scanning
        // reports the misaligned unindent, while parse() never observes it (multiline mode
        // discards the layout). Pins "raw stream ERROR count is not a validity criterion".
        var source = "var x = [\n\t\t1\n\t]\n";
        var errors = facade.tokenize(source).stream().filter(GdToken::isError).toList();
        assertEquals(1, errors.size());
        assertEquals("Unindent doesn't match the previous indentation level.", errors.getFirst().errorMessage());
        assertEquals(List.of(), errorMessages(facade.parse(source)));
    }

    @Test
    void sequentialParsesOnSharedFacadeAreIndependent() {
        // Each call builds a fresh parser: an error run must not bleed state into the next.
        assertEquals(1, errorMessages(facade.parse("var a = \n")).size());
        assertEquals(List.of(), errorMessages(facade.parse("var b = 2\n")));
    }

    @Test
    void concurrentCallsOnSharedFacadeProduceIdenticalResults() throws Exception {
        var validSource = "class_name Foo\nextends Node\n\nfunc f(x):\n\tvar y = x * 2\n\treturn y\n";
        var invalidSource = "func f(:\n\tvar x = = 1\nvar after = 2\n";
        var expectedValid = snapshotOf(facade.parse(validSource));
        var expectedInvalid = snapshotOf(facade.parse(invalidSource));
        var expectedTokens = facade.tokenize(validSource);
        var expectedInvalidTokens = facade.tokenize(invalidSource);
        var expectedStrictSexpr = SERIALIZER.serialize(facade.parseStrict(validSource));
        var expectedStrictMessage = assertThrows(IllegalStateException.class,
                () -> facade.parseStrict(invalidSource)).getMessage();

        var threadCount = 8;
        var iterations = 25;
        var pool = Executors.newFixedThreadPool(threadCount);
        try {
            var futures = new ArrayList<Future<?>>();
            for (var thread = 0; thread < threadCount; thread++) {
                futures.add(pool.submit(() -> {
                    for (var iteration = 0; iteration < iterations; iteration++) {
                        assertEquals(expectedValid, snapshotOf(facade.parse(validSource)));
                        assertEquals(expectedInvalid, snapshotOf(facade.parse(invalidSource)));
                        assertEquals(expectedTokens, facade.tokenize(validSource));
                        assertEquals(expectedInvalidTokens, facade.tokenize(invalidSource));
                        assertEquals(expectedStrictSexpr, SERIALIZER.serialize(facade.parseStrict(validSource)));
                        assertEquals(expectedStrictMessage, assertThrows(IllegalStateException.class,
                                () -> facade.parseStrict(invalidSource)).getMessage());
                    }
                }));
            }
            for (var future : futures) {
                future.get();
            }
        } catch (ExecutionException exception) {
            throw new AssertionError("Concurrent facade use failed", exception.getCause());
        } finally {
            pool.shutdownNow();
        }
    }

    /// Serializable parse snapshot; record value equality compares the serialized AST text and
    /// the full diagnostic list component-wise (severity/message/nodeType/range, in order).
    private record Snapshot(String astSexpr, List<AstDiagnostic> diagnostics) {
    }

    private static Snapshot snapshotOf(AstMappingResult result) {
        return new Snapshot(SERIALIZER.serialize(result.ast()), result.diagnostics());
    }

    private static List<String> errorMessages(AstMappingResult result) {
        return result.diagnostics().stream()
                .filter(d -> d.severity() == AstDiagnosticSeverity.ERROR)
                .map(AstDiagnostic::message)
                .toList();
    }
}
