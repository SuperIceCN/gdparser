package dev.superice.gdparser.frontend.gdscript;

import dev.superice.gdparser.frontend.ast.AssignmentExpression;
import dev.superice.gdparser.frontend.ast.AstDiagnostic;
import dev.superice.gdparser.frontend.ast.AstDiagnosticSeverity;
import dev.superice.gdparser.frontend.ast.AstMappingResult;
import dev.superice.gdparser.frontend.ast.CallExpression;
import dev.superice.gdparser.frontend.ast.ClassDeclaration;
import dev.superice.gdparser.frontend.ast.CommentStatement;
import dev.superice.gdparser.frontend.ast.ExpressionStatement;
import dev.superice.gdparser.frontend.ast.FunctionDeclaration;
import dev.superice.gdparser.frontend.ast.IdentifierExpression;
import dev.superice.gdparser.frontend.ast.LambdaExpression;
import dev.superice.gdparser.frontend.ast.LiteralExpression;
import dev.superice.gdparser.frontend.ast.MatchStatement;
import dev.superice.gdparser.frontend.ast.Node;
import dev.superice.gdparser.frontend.ast.Parameter;
import dev.superice.gdparser.frontend.ast.PassStatement;
import dev.superice.gdparser.frontend.ast.ReturnStatement;
import dev.superice.gdparser.frontend.ast.VariableDeclaration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Error-recovery corpus: panic-mode recovery (`synchronize()`), expected-token diagnostics,
/// unclosed constructs, EOF dangling points and the assignment-context family. Every case
/// asserts the three recovery properties: termination (class-level `@Timeout`), at least
/// one ERROR diagnostic with an in-bounds range, and correct sibling structure after recovery.
///
/// Godot comparisons recorded on representative cases are derived from tracing
/// `vendor/godot/gdscript_parser.cpp` at commit c016f22009aa773f0b2e6fce72baec168746491f
/// (no Godot editor binary is available in this environment); positions are 1-based
/// line/column, with tabs counting as one column, exactly as Godot reports them.
@Timeout(value = 10, unit = TimeUnit.SECONDS)
class GdScriptParserErrorRecoveryTest {

    private static AstMappingResult parse(String source) {
        return new GdScriptParser().parse(source);
    }

    private static List<AstDiagnostic> errorsOf(AstMappingResult result) {
        return result.diagnostics().stream()
                .filter(d -> d.severity() == AstDiagnosticSeverity.ERROR)
                .toList();
    }

    private static List<String> errorMessages(AstMappingResult result) {
        return errorsOf(result).stream().map(AstDiagnostic::message).toList();
    }

    /// Recovery gate: at least one ERROR diagnostic and every error range inside the source.
    /// Ranges are UTF-8 byte offsets, so the bound is the source's byte length, not its
    /// UTF-16 `String.length()` — the two diverge on non-ASCII sources.
    private static AstMappingResult recoverableParse(String source) {
        var result = parse(source);
        var errors = errorsOf(result);
        assertFalse(errors.isEmpty(), () -> "expected at least one ERROR diagnostic for: " + source);
        var utf8Length = source.getBytes(StandardCharsets.UTF_8).length;
        for (var d : errors) {
            var r = d.range();
            assertTrue(r.startByte() >= 0 && r.startByte() <= r.endByte() && r.endByte() <= utf8Length,
                    () -> "error range out of bounds: " + r + " for: " + source);
        }
        return result;
    }

    private static FunctionDeclaration onlyFunction(AstMappingResult result) {
        var statements = result.ast().statements();
        assertEquals(1, statements.size(), () -> "expected one member in: " + statements);
        return assertInstanceOf(FunctionDeclaration.class, statements.getFirst());
    }

    /// Names of top-level members in order, for sibling-retention assertions on recovery
    /// corpora (non-declaration statements surface as their simple class name).
    private static List<String> topLevelMemberNames(AstMappingResult result) {
        return result.ast().statements().stream()
                .map(s -> switch (s) {
                    case FunctionDeclaration f -> f.name();
                    case VariableDeclaration v -> v.name();
                    case ClassDeclaration c -> c.name();
                    default -> s.getClass().getSimpleName();
                })
                .toList();
    }

    /// All variable declaration names anywhere in the AST, for asserting that a same-level
    /// sibling inside a nested scope survived recovery.
    private static List<String> allVariableNames(AstMappingResult result) {
        var names = new ArrayList<String>();
        collectVariableNames(result.ast(), names);
        return names;
    }

    private static void collectVariableNames(Node node, List<String> names) {
        if (node instanceof VariableDeclaration variable) {
            names.add(variable.name());
        }
        for (var child : node.getChildren()) {
            collectVariableNames(child, names);
        }
    }

    // -----------------------------------------------------------------------------------------
    // synchronize(): a bad statement never swallows the following sibling.
    // -----------------------------------------------------------------------------------------

    @Test
    void garbageMemberThenSiblingRecovers() {
        var result = recoverableParse("var good_before = 1\n% ^ &\nvar good_after = 2\n");
        var statements = result.ast().statements();
        assertEquals(2, statements.size(), () -> "members: " + statements);
        assertEquals("good_before", assertInstanceOf(VariableDeclaration.class, statements.getFirst()).name());
        assertEquals("good_after", assertInstanceOf(VariableDeclaration.class, statements.get(1)).name());
    }

    @Test
    void garbageStatementThenSiblingRecovers() {
        var result = recoverableParse("func f():\n\t)\n\tvar y = 2\n");
        var body = onlyFunction(result).body().statements();
        assertEquals(1, body.size(), () -> "body: " + body);
        assertEquals("y", assertInstanceOf(VariableDeclaration.class, body.getFirst()).name());
        assertTrue(errorMessages(result).stream().anyMatch(m -> m.startsWith("Expected statement, found ")),
                () -> errorMessages(result).toString());
    }

    @Test
    void synchronizeSkipsGarbageTokensBeforeKeyword() {
        // `%` and the stray literal are not sync tokens; synchronization must consume them and
        // stop before `return` (a sync token), which then parses as a proper statement.
        var result = recoverableParse("func f():\n\t% 1\n\treturn 1\n");
        var body = onlyFunction(result).body().statements();
        assertEquals(1, body.size(), () -> "body: " + body);
        var returnStatement = assertInstanceOf(ReturnStatement.class, body.getFirst());
        assertNotNull(returnStatement.value());
    }

    // -----------------------------------------------------------------------------------------
    // Bad expressions: the enclosing declaration survives, the sibling line is unaffected.
    // -----------------------------------------------------------------------------------------

    @Test
    void missingVariableInitializerThenSiblingRecovers() {
        var result = recoverableParse("var x =\nvar y = 2\n");
        assertTrue(errorMessages(result).contains("Expected expression for variable initial value after \"=\"."),
                () -> errorMessages(result).toString());
        var statements = result.ast().statements();
        assertEquals(2, statements.size(), () -> "members: " + statements);
        var x = assertInstanceOf(VariableDeclaration.class, statements.getFirst());
        assertNull(x.value());
        var y = assertInstanceOf(VariableDeclaration.class, statements.get(1));
        assertInstanceOf(LiteralExpression.class, y.value());
    }

    @Test
    void trailingBinaryOperatorThenSiblingRecovers() {
        var result = recoverableParse("var x = 1 +\nvar y = 2\n");
        assertTrue(errorMessages(result).contains("Expected expression after \"+\" operator."),
                () -> errorMessages(result).toString());
        var statements = result.ast().statements();
        assertEquals(2, statements.size(), () -> "members: " + statements);
        var x = assertInstanceOf(VariableDeclaration.class, statements.getFirst());
        // The failed operand collapses the whole expression (no half-built binary node).
        assertNull(x.value());
        assertEquals("y", assertInstanceOf(VariableDeclaration.class, statements.get(1)).name());
    }

    // -----------------------------------------------------------------------------------------
    // Unclosed constructs at EOF: exactly one MISSING-class diagnostic each, node survives.
    // -----------------------------------------------------------------------------------------

    @Test
    void unclosedGroupingAtEof() {
        var result = recoverableParse("var x = (1 + 2");
        assertTrue(errorMessages(result).contains("Expected closing \")\" after grouping expression."),
                () -> errorMessages(result).toString());
        var x = assertInstanceOf(VariableDeclaration.class, result.ast().statements().getFirst());
        assertNotNull(x.value());
    }

    @Test
    void unclosedArrayAtEof() {
        var result = recoverableParse("var x = [1, 2");
        assertTrue(errorMessages(result).contains("Expected closing \"]\" after array elements."),
                () -> errorMessages(result).toString());
        var x = assertInstanceOf(VariableDeclaration.class, result.ast().statements().getFirst());
        assertNotNull(x.value());
    }

    @Test
    void unclosedDictionaryAtEof() {
        var result = recoverableParse("var x = {\"a\": 1");
        assertTrue(errorMessages(result).contains("Expected closing \"}\" after dictionary elements."),
                () -> errorMessages(result).toString());
        var x = assertInstanceOf(VariableDeclaration.class, result.ast().statements().getFirst());
        assertNotNull(x.value());
    }

    /// Godot comparison: for `var x = f(1, 2` Godot reports exactly one error,
    /// `Expected closing ")" after call arguments.`, starting at 1:14 (consume() fails at EOF and
    /// push_error without an origin node lands on `previous`, the `2` token —
    /// gdscript_parser.cpp:616-622, 231-249). Vendor's raw end coordinate is 2:1 rather than
    /// 1:15, because its tokenizer synthesizes a trailing newline at EOF and extends the last
    /// real token's end across it (gdscript_tokenizer.cpp:320-331, 356-361, 665-667); this
    /// project normalizes ranges to physical byte cursors, so only the start is compared here.
    /// The call node survives with both arguments.
    @Test
    void unclosedCallAtEofRecoversArguments() {
        var result = recoverableParse("var x = f(1, 2");
        var errors = errorMessages(result);
        assertEquals(List.of("Expected closing \")\" after call arguments."), errors, errors::toString);
        var error = errorsOf(result).getFirst();
        assertEquals(0, error.range().startPoint().row(), () -> error.range().toString());
        assertEquals(13, error.range().startPoint().column(), () -> error.range().toString());
        var x = assertInstanceOf(VariableDeclaration.class, result.ast().statements().getFirst());
        var call = assertInstanceOf(CallExpression.class, x.value());
        assertEquals(2, call.arguments().size(), () -> "args: " + call.arguments());
    }

    @Test
    void unterminatedStringAtEof() {
        var result = recoverableParse("var x = \"abc");
        assertTrue(errorMessages(result).contains("Unterminated string."),
                () -> errorMessages(result).toString());
        // Tokenizer errors do not interrupt parsing: the declaration itself is still produced.
        assertInstanceOf(VariableDeclaration.class, result.ast().statements().getFirst());
    }

    // -----------------------------------------------------------------------------------------
    // EOF dangling points.
    // -----------------------------------------------------------------------------------------

    @Test
    void danglingDotInInitializerAtEof() {
        var result = recoverableParse("var x = node.");
        assertTrue(errorMessages(result).contains("Expected identifier after \".\" for attribute access."),
                () -> errorMessages(result).toString());
        var x = assertInstanceOf(VariableDeclaration.class, result.ast().statements().getFirst());
        assertNotNull(x.value());
    }

    /// Godot comparison (the plan's reference case `func f():\n\tnode.`): Godot reports exactly
    /// one error, `Expected identifier after "." for attribute access.`, starting at 2:6
    /// (previous = `.`; gdscript_parser.cpp parse_attribute suffix loop). Vendor's raw end
    /// coordinate is 3:1 for the same EOF-newline reason documented on
    /// `unclosedCallAtEofRecoversArguments`; this project reports the physical byte range.
    /// The function and the degraded `node` expression statement survive.
    @Test
    void danglingDotAtEofKeepsFunction() {
        var result = recoverableParse("func f():\n\tnode.");
        var errors = errorMessages(result);
        assertEquals(List.of("Expected identifier after \".\" for attribute access."), errors,
                errors::toString);
        var error = errorsOf(result).getFirst();
        assertEquals(1, error.range().startPoint().row(), () -> error.range().toString());
        assertEquals(5, error.range().startPoint().column(), () -> error.range().toString());
        var function = onlyFunction(result);
        var body = function.body().statements();
        assertEquals(1, body.size(), () -> "body: " + body);
        var statement = assertInstanceOf(ExpressionStatement.class, body.getFirst());
        assertEquals("node", assertInstanceOf(IdentifierExpression.class, statement.expression()).name());
    }

    @Test
    void bareFuncHeaderAtEof() {
        var result = recoverableParse("func f():");
        var function = onlyFunction(result);
        assertEquals("f", function.name());
        assertTrue(function.body().statements().isEmpty(), () -> function.body().toString());
    }

    // -----------------------------------------------------------------------------------------
    // Assignment-context family: `=` is rejected where `canAssign == false` (vendor
    // parse_assignment, gdscript_parser.cpp:3128-3132).
    // -----------------------------------------------------------------------------------------

    /// Godot comparison: for `a = b = 1` Godot reports exactly one error,
    /// `Assignment is not allowed inside an expression.` at 2:8-9 (previous = the second `=`;
    /// the `!p_can_assign` branch discards the `b` operand and returns the parse of `1`, so the
    /// surviving AST is the statement-level assignment `a = 1`).
    @Test
    void chainedAssignmentKeepsOuterAssignment() {
        var result = recoverableParse("func f():\n\ta = b = 1\n");
        var errors = errorMessages(result);
        assertEquals(List.of("Assignment is not allowed inside an expression."), errors,
                errors::toString);
        var error = errorsOf(result).getFirst();
        assertEquals(1, error.range().startPoint().row(), () -> error.range().toString());
        assertEquals(7, error.range().startPoint().column(), () -> error.range().toString());
        var body = onlyFunction(result).body().statements();
        assertEquals(1, body.size(), () -> "body: " + body);
        var statement = assertInstanceOf(ExpressionStatement.class, body.getFirst());
        var assignment = assertInstanceOf(AssignmentExpression.class, statement.expression());
        assertEquals("a", assertInstanceOf(IdentifierExpression.class, assignment.left()).name());
        assertEquals("1", assertInstanceOf(LiteralExpression.class, assignment.right()).sourceText());
    }

    /// Godot comparison: in `g(a = 1, 2)` the call-argument expression parses with
    /// `p_can_assign == false` (gdscript_parser.cpp:3529-3537), so Godot reports exactly one
    /// `Assignment is not allowed inside an expression.` and the recovered argument list is
    /// `[1, 2]` (the discarded assignee `a` never reaches the argument list).
    @Test
    void assignmentInsideCallArgument() {
        var result = recoverableParse("func f():\n\tg(a = 1, 2)\n");
        var errors = errorMessages(result);
        assertEquals(List.of("Assignment is not allowed inside an expression."), errors,
                errors::toString);
        var body = onlyFunction(result).body().statements();
        var statement = assertInstanceOf(ExpressionStatement.class, body.getFirst());
        var call = assertInstanceOf(CallExpression.class, statement.expression());
        assertEquals(2, call.arguments().size(), () -> "args: " + call.arguments());
        assertEquals("1", assertInstanceOf(LiteralExpression.class, call.arguments().getFirst()).sourceText());
        assertEquals("2", assertInstanceOf(LiteralExpression.class, call.arguments().get(1)).sourceText());
    }

    /// Godot comparison: in `var x = y = 1` the initializer parses with `p_can_assign == false`
    /// (gdscript_parser.cpp:1284-1289), so Godot reports exactly one
    /// `Assignment is not allowed inside an expression.` and the surviving initializer is `1`.
    @Test
    void assignmentInsideVariableInitializer() {
        var result = recoverableParse("func f():\n\tvar x = y = 1\n");
        var errors = errorMessages(result);
        assertEquals(List.of("Assignment is not allowed inside an expression."), errors,
                errors::toString);
        var body = onlyFunction(result).body().statements();
        var x = assertInstanceOf(VariableDeclaration.class, body.getFirst());
        assertEquals("1", assertInstanceOf(LiteralExpression.class, x.value()).sourceText());
    }

    // -----------------------------------------------------------------------------------------
    // MISSING-class diagnostics: the expected token is absent; parsing continues at the
    // token that failed the consume().
    // -----------------------------------------------------------------------------------------

    /// Godot comparison: for `class Foo\n\tpass\n` Godot reports exactly one error,
    /// `Expected ":" after class declaration.` at 1:7-10 (previous = `Foo`; consume() failing
    /// does not abort parse_class — gdscript_parser.cpp:964-986). The class body still parses.
    @Test
    void classMissingColonKeepsBody() {
        var result = recoverableParse("class Foo\n\tpass\n");
        var errors = errorMessages(result);
        assertEquals(List.of("Expected \":\" after class declaration."), errors, errors::toString);
        var error = errorsOf(result).getFirst();
        assertEquals(0, error.range().startPoint().row(), () -> error.range().toString());
        assertEquals(6, error.range().startPoint().column(), () -> error.range().toString());
        var classDeclaration = assertInstanceOf(ClassDeclaration.class, result.ast().statements().getFirst());
        assertEquals("Foo", classDeclaration.name());
        assertEquals(1, classDeclaration.body().statements().size(),
                () -> classDeclaration.body().toString());
        assertInstanceOf(PassStatement.class, classDeclaration.body().statements().getFirst());
    }

    @Test
    void classMissingIndentKeepsEmptyBodyAndSibling() {
        var result = recoverableParse("class Foo:\npass\n");
        assertTrue(errorMessages(result).contains("Expected indented block after class declaration."),
                () -> errorMessages(result).toString());
        var statements = result.ast().statements();
        assertEquals(2, statements.size(), () -> "members: " + statements);
        var classDeclaration = assertInstanceOf(ClassDeclaration.class, statements.getFirst());
        assertTrue(classDeclaration.body().statements().isEmpty(), () -> classDeclaration.body().toString());
        // The unindented `pass` is not pulled into the class; it stays a top-level sibling.
        assertInstanceOf(PassStatement.class, statements.get(1));
    }

    @Test
    void functionMissingIndentKeepsSibling() {
        var result = recoverableParse("func f():\npass\n");
        assertTrue(errorMessages(result).contains("Expected indented block after function declaration."),
                () -> errorMessages(result).toString());
        var statements = result.ast().statements();
        assertEquals(2, statements.size(), () -> "members: " + statements);
        var function = assertInstanceOf(FunctionDeclaration.class, statements.getFirst());
        assertTrue(function.body().statements().isEmpty(), () -> function.body().toString());
        assertInstanceOf(PassStatement.class, statements.get(1));
    }

    /// Godot comparison: for `func f():\n\tmatch x:\n\treturn 1\n` Godot reports exactly one
    /// error, `Expected an indented block after "match" statement.` (parse_match returns
    /// immediately when consume(INDENT) fails — crucially without eating the outer suite's
    /// DEDENT). `return 1` survives as the match statement's sibling in the function body.
    @Test
    void matchMissingIndentKeepsOuterDedent() {
        var result = recoverableParse("func f():\n\tmatch x:\n\treturn 1\n");
        var errors = errorMessages(result);
        assertEquals(List.of("Expected an indented block after \"match\" statement."), errors,
                errors::toString);
        var body = onlyFunction(result).body().statements();
        assertEquals(2, body.size(), () -> "body: " + body);
        var match = assertInstanceOf(MatchStatement.class, body.getFirst());
        assertEquals("x", assertInstanceOf(IdentifierExpression.class, match.value()).name());
        assertTrue(match.sections().isEmpty(), () -> match.sections().toString());
        assertInstanceOf(ReturnStatement.class, body.get(1));
    }

    /// Godot comparison: in `func f(a, b:` the `:` is consumed as b's *type-annotation* colon
    /// (vendor parse_parameter → parse_type, gdscript_parser.cpp:1536-1544), so the parameter
    /// `)` is reported missing, the header colon is then absent, and Godot takes the bodyless
    /// (abstract) function path (`has_body == false`, gdscript_parser.cpp:1834-1840); the class
    /// body's panic-mode synchronize() swallows the following `pass`. This test pins that exact
    /// chain: the function and both parameters survive, the body is empty, and `pass` is gone.
    @Test
    void missingClosingParenInFunctionParametersRecovers() {
        var result = recoverableParse("func f(a, b:\n\tpass\n");
        assertTrue(errorMessages(result).contains("Expected closing \")\" after function parameters."),
                () -> errorMessages(result).toString());
        var statements = result.ast().statements();
        assertEquals(1, statements.size(), () -> "members: " + statements);
        var function = assertInstanceOf(FunctionDeclaration.class, statements.getFirst());
        assertEquals("f", function.name());
        assertEquals(List.of("a", "b"),
                function.parameters().stream().map(Parameter::name).toList());
        assertTrue(function.body().statements().isEmpty(), () -> function.body().toString());
    }

    // -----------------------------------------------------------------------------------------
    // Circuit breaker and progress guarantee.
    // -----------------------------------------------------------------------------------------

    /// Godot comparison: a suite's 102nd null statement trips `error_count++ > 100` and Godot
    /// reports `Too many statement errors.` once, then abandons the suite
    /// (gdscript_parser.cpp:1981-1993). The class-level @Timeout additionally proves the
    /// recovery loop keeps consuming input instead of spinning on the bad token.
    @Test
    void tooManyStatementErrorsTerminatesSuite() {
        var source = "func f():\n" + "\t)\n".repeat(150);
        var result = recoverableParse(source);
        assertTrue(errorMessages(result).contains("Too many statement errors."),
                () -> errorMessages(result).toString());
    }

    @Test
    void loneConstructsAtEofTerminate() {
        // Every construct cut right after its keyword must terminate with a diagnostic instead
        // of hanging a recovery loop (the sync set stops *before* these tokens, so each error
        // branch must still make progress).
        for (var source : List.of("static", "var", "func", "match", "const", "signal")) {
            recoverableParse(source);
        }
    }

    // -----------------------------------------------------------------------------------------
    // Lambda recovery.
    // -----------------------------------------------------------------------------------------

    @Test
    void unclosedLambdaParametersAtEof() {
        var result = recoverableParse("var cb = func(");
        assertTrue(errorMessages(result).contains("Expected closing \")\" after function parameters."),
                () -> errorMessages(result).toString());
        var variable = assertInstanceOf(VariableDeclaration.class, result.ast().statements().getFirst());
        assertInstanceOf(LambdaExpression.class, variable.value());
    }

    // -----------------------------------------------------------------------------------------
    // Indentation errors (tokenizer-level: reported as diagnostics, scanning continues).
    // -----------------------------------------------------------------------------------------

    @Test
    void mixedIndentationReportedButParsingContinues() {
        var result = recoverableParse("func f():\n\tpass\n \treturn 1\n");
        assertTrue(errorMessages(result).contains("Mixed use of tabs and spaces for indentation."),
                () -> errorMessages(result).toString());
        var body = onlyFunction(result).body().statements();
        assertEquals(2, body.size(), () -> "body: " + body);
        assertInstanceOf(PassStatement.class, body.getFirst());
        assertInstanceOf(ReturnStatement.class, body.get(1));
    }

    // -----------------------------------------------------------------------------------------
    // Nesting-depth budget (illegal sources must never throw). Godot has no explicit
    // limit; the JVM stack needs one. Every input-driven recursion entry (expressions, suites,
    // match patterns, nested classes) shares one budget, so a compact pathological input fails
    // with a diagnostic instead of a StackOverflowError, and the leftover tokens are consumed
    // by the iterative recovery loops.
    // -----------------------------------------------------------------------------------------

    @Test
    void deeplyNestedGroupingReportsErrorInsteadOfOverflowing() {
        var result = recoverableParse("var x = " + "(".repeat(4096));
        assertTrue(errorMessages(result).contains("Maximum nesting depth exceeded."),
                () -> errorMessages(result).toString());
    }

    @Test
    void deeplyNestedLambdaDefaultsReportErrorInsteadOfOverflowing() {
        var result = recoverableParse("var x = " + "func(a = ".repeat(1024));
        assertTrue(errorMessages(result).contains("Maximum nesting depth exceeded."),
                () -> errorMessages(result).toString());
    }

    @Test
    void deeplyNestedSuitesReportErrorInsteadOfOverflowing() {
        var source = new StringBuilder("func f():\n");
        for (var i = 1; i <= 600; i++) {
            source.repeat("\t", i).append("if true:\n");
        }
        var result = recoverableParse(source.toString());
        assertTrue(errorMessages(result).contains("Maximum nesting depth exceeded."),
                () -> errorMessages(result).toString());
    }

    @Test
    void deeplyNestedMatchPatternsReportErrorInsteadOfOverflowing() {
        var result = recoverableParse("func f():\n\tmatch x:\n\t\t" + "[".repeat(512) + "\n");
        assertTrue(errorMessages(result).contains("Maximum nesting depth exceeded."),
                () -> errorMessages(result).toString());
    }

    @Test
    void deeplyNestedClassesReportErrorInsteadOfOverflowing() {
        var source = new StringBuilder("class A:\n");
        for (var i = 1; i <= 600; i++) {
            source.repeat("\t", i).append("class B:\n");
        }
        var result = recoverableParse(source.toString());
        assertTrue(errorMessages(result).contains("Maximum nesting depth exceeded."),
                () -> errorMessages(result).toString());
    }

    @Test
    void deeplyNestedContainerTypeReportsErrorInsteadOfOverflowing() {
        // Container types reject *nested* containers only after the recursive call returns, so
        // the recursion itself is guarded by the same budget.
        var result = recoverableParse("var x: " + "Array[".repeat(8192) + "int" + "]".repeat(8192)
                + "\nvar after = 1\n");
        assertTrue(errorMessages(result).contains("Maximum nesting depth exceeded."),
                () -> errorMessages(result).toString());
        assertEquals(List.of("after"),
                result.ast().statements().stream()
                        .filter(VariableDeclaration.class::isInstance)
                        .map(s -> ((VariableDeclaration) s).name())
                        .filter(n -> n.equals("after"))
                        .toList());
    }

    @Test
    void lambdaDefaultDepthBoundaryKeepsTopLevelSibling() {
        // Below the budget the chain parses cleanly; at and above it the depth error must not
        // let an unwinding lambda body absorb the following top-level declaration.
        for (var depth : List.of(255, 256, 257)) {
            var source = "var x = " + "func(a = ".repeat(depth) + "1" + "): pass".repeat(depth)
                    + "\nvar after = 2\n";
            var result = parse(source);
            assertEquals(List.of("x", "after"), topLevelMemberNames(result), () -> "depth " + depth);
            if (depth >= 256) {
                assertTrue(errorMessages(result).contains("Maximum nesting depth exceeded."),
                        () -> "depth " + depth + ": " + errorMessages(result));
            } else {
                assertEquals(List.of(), errorMessages(result), () -> "depth " + depth);
            }
        }
    }

    @Test
    void rejectedSuiteLayoutDoesNotLeakIntoOuterScopes() {
        // The deepest lambda's body suite is rejected by the budget; its NEWLINE/INDENT/body/
        // DEDENT layout must be consumed structurally, or the leftover DEDENTs end the
        // top-level class body early and `after` is never parsed.
        for (var depth : List.of(127, 128)) {
            var source = new StringBuilder("func f():\n");
            for (var i = 1; i <= depth; i++) {
                source.repeat("\t", i).append("var cb = func():\n");
            }
            source.repeat("\t", depth + 1).append("pass\nvar after = 3\n");
            var result = parse(source.toString());
            assertEquals(List.of("f", "after"), topLevelMemberNames(result), () -> "depth " + depth);
            if (depth >= 128) {
                assertTrue(errorMessages(result).contains("Maximum nesting depth exceeded."),
                        () -> "depth " + depth + ": " + errorMessages(result));
            } else {
                assertEquals(List.of(), errorMessages(result), () -> "depth " + depth);
            }
        }
    }

    @Test
    void rejectedAccessorSuiteKeepsFollowingMembers() {
        // Same layout-leak class inside a property accessor: the rejected getter suite must not
        // swallow the following top-level members.
        for (var depth : List.of(100, 400)) {
            var source = new StringBuilder("var p:\n\tget:\n");
            for (var i = 2; i <= depth + 1; i++) {
                source.repeat("\t", i).append("if true:\n");
            }
            source.repeat("\t", depth + 2).append("pass\n@export\nvar after = 1\n");
            var result = parse(source.toString());
            // Annotations are separate AnnotationStatement entries in the sequence (they do
            // not merge into the following declaration node).
            assertEquals(List.of("p", "AnnotationStatement", "after"), topLevelMemberNames(result),
                    () -> "depth " + depth);
            if (depth >= 400) {
                assertTrue(errorMessages(result).contains("Maximum nesting depth exceeded."),
                        () -> "depth " + depth + ": " + errorMessages(result));
            } else {
                assertEquals(List.of(), errorMessages(result), () -> "depth " + depth);
            }
        }
    }

    @Test
    void depthTripInsideMultilineCallKeepsSibling() {
        var result = recoverableParse("var x = f(\n\t" + "func(a = ".repeat(300) + "1"
                + "): pass".repeat(300) + "\n)\nvar after = 2\n");
        assertTrue(errorMessages(result).contains("Maximum nesting depth exceeded."),
                () -> errorMessages(result).toString());
        assertEquals(List.of("x", "after"), topLevelMemberNames(result));
    }

    @Test
    void matchPatternDepthTripKeepsNextBranchAndSibling() {
        // The match level recovers a poisoned pattern line without parseStatement's tail hook;
        // the next branch's body and the top-level sibling must survive anyway.
        for (var depth : List.of(253, 254)) {
            var source = "func f():\n\tmatch x:\n\t\t" + "[".repeat(depth) + "1" + "]".repeat(depth)
                    + ": pass\n\t\t_: return 7\nvar after = 2\n";
            var result = parse(source);
            assertEquals(List.of("f", "after"), topLevelMemberNames(result), () -> "depth " + depth);
            var function = (FunctionDeclaration) result.ast().statements().getFirst();
            var match = assertInstanceOf(MatchStatement.class, function.body().statements().getFirst());
            assertEquals(2, match.sections().size(), () -> "depth " + depth + ": " + match.sections());
            var fallbackBody = match.sections().get(1).body().statements();
            assertEquals(1, fallbackBody.size(), () -> "depth " + depth + ": " + fallbackBody);
            assertInstanceOf(ReturnStatement.class, fallbackBody.getFirst());
            if (depth >= 254) {
                assertTrue(errorMessages(result).contains("Maximum nesting depth exceeded."),
                        () -> "depth " + depth + ": " + errorMessages(result));
            } else {
                assertEquals(List.of(), errorMessages(result), () -> "depth " + depth);
            }
        }
    }

    @Test
    void matchAnnotationDepthTripKeepsNextBranch() {
        var result = recoverableParse("func f():\n\tmatch x:\n\t\t@warning_ignore("
                + "(".repeat(300) + ")\n\t\t_: return 7\nvar after = 2\n");
        assertTrue(errorMessages(result).contains("Maximum nesting depth exceeded."),
                () -> errorMessages(result).toString());
        assertEquals(List.of("f", "after"), topLevelMemberNames(result));
        var function = (FunctionDeclaration) result.ast().statements().getFirst();
        var match = assertInstanceOf(MatchStatement.class, function.body().statements().getFirst());
        assertEquals(1, match.sections().size(), () -> match.sections().toString());
        var body = match.sections().getFirst().body().statements();
        assertEquals(1, body.size(), body::toString);
        assertInstanceOf(ReturnStatement.class, body.getFirst());
    }

    @Test
    void matchPatternDepthTripWithMultilineBodyKeepsSibling() {
        // Same trip as matchPatternDepthTripKeepsNextBranchAndSibling but with the branch body
        // on its own line: synchronization may stop at the match's closing DEDENT, which the
        // loop must re-check before parsing another section. The tripped branch's body line
        // belongs to the poisoned region and is consumed with it.
        for (var depth : List.of(253, 254)) {
            var source = "func f():\n\tmatch x:\n\t\t" + "[".repeat(depth) + "1" + "]".repeat(depth)
                    + ":\n\t\t\tpass\nvar after = 2\n";
            var result = parse(source);
            assertEquals(List.of("f", "after"), topLevelMemberNames(result), () -> "depth " + depth);
            var function = (FunctionDeclaration) result.ast().statements().getFirst();
            var match = assertInstanceOf(MatchStatement.class, function.body().statements().getFirst());
            assertEquals(1, match.sections().size(), () -> "depth " + depth + ": " + match.sections());
            var body = match.sections().getFirst().body().statements();
            if (depth >= 254) {
                assertTrue(errorMessages(result).contains("Maximum nesting depth exceeded."),
                        () -> "depth " + depth + ": " + errorMessages(result));
                assertTrue(body.isEmpty(), () -> "depth " + depth + ": " + body);
            } else {
                assertEquals(List.of(), errorMessages(result), () -> "depth " + depth);
                assertEquals(1, body.size(), () -> "depth " + depth + ": " + body);
                assertInstanceOf(PassStatement.class, body.getFirst());
            }
        }
    }

    @Test
    void garbageDedentThenSameLevelSiblingSurvives() {
        // After the balanced garbage DEDENT is consumed, `previous` is a DEDENT: the poisoned
        // line-start gate must still fire for the following same-level line.
        for (var depth : List.of(127, 128)) {
            var source = new StringBuilder("func f():\n");
            for (var i = 1; i <= depth; i++) {
                source.repeat("\t", i).append("var cb = func():\n");
            }
            source.repeat("\t", depth + 1).append("pass\n")
                    .repeat("\t", depth).append("var inner_after = 7\n")
                    .append("var after = 2\n");
            var result = parse(source.toString());
            assertEquals(List.of("f", "after"), topLevelMemberNames(result), () -> "depth " + depth);
            assertTrue(allVariableNames(result).contains("inner_after"),
                    () -> "depth " + depth + ": inner_after lost");
            if (depth >= 128) {
                assertTrue(errorMessages(result).contains("Maximum nesting depth exceeded."),
                        () -> "depth " + depth + ": " + errorMessages(result));
            } else {
                assertEquals(List.of(), errorMessages(result), () -> "depth " + depth);
            }
        }
    }

    @Test
    void depthGateUsesLexerIndentWidthForMixedWhitespace() {
        // Both lines have the same layout width (tab = 4): the trip on the first must not treat
        // the second as deeper-indented garbage just because its byte column is larger.
        var source = "func f():\n\t\t var x = " + "(".repeat(300) + "1" + ")".repeat(300)
                + "\n\t     return 7\nvar after = 2\n";
        var result = parse(source);
        assertTrue(errorMessages(result).contains("Maximum nesting depth exceeded."),
                () -> errorMessages(result).toString());
        assertEquals(List.of("f", "after"), topLevelMemberNames(result));
        var function = (FunctionDeclaration) result.ast().statements().getFirst();
        var body = function.body().statements();
        assertTrue(body.stream().anyMatch(ReturnStatement.class::isInstance),
                () -> "return lost: " + body);
    }

    @Test
    void multilinePoisonedPatternTerminatesWithDiagnostics() {
        // Known limitation: a depth trip on a grouping continuation line
        // breaks sibling scope attribution — the aborted frames pop multiline mode before
        // consuming the grouping remainder, so the continuation's DEDENT becomes an orphan no
        // parsed frame owns. Godot has no depth limit and thus no such path. Only termination
        // and diagnostics are pinned here, NOT the (incorrect) scope attribution.
        recoverableParse("func f():\n\tmatch x:\n\t\t[\n\t\t\t" + "[".repeat(254) + "1"
                + "]".repeat(254) + "\n\t\t]:\n\t\t\tpass\n\t\t_: return 7\n"
                + "\tvar local_after = 7\nvar after = 2\n");
    }

    @Test
    void tokenizerErrorInsidePoisonedRegionDoesNotRescopeSibling() {
        // The stray `]` inside the poisoned region raises a tokenizer error mid-scan, which
        // re-sets panicMode; without clearing it on synchronize()'s exit, the enclosing
        // statement's tail hook synchronizes a second time and eats the outer DEDENT.
        var result = recoverableParse("func f():\n\tmatch x:\n\t\t" + "[".repeat(254) + "1"
                + "]".repeat(254) + ":\n\t\t\tpass ]\nvar after = 2\n");
        assertTrue(errorMessages(result).contains("Maximum nesting depth exceeded."),
                () -> errorMessages(result).toString());
        // `after` must survive as a TOP-LEVEL declaration, not absorbed into f's body.
        assertEquals(List.of("f", "after"), topLevelMemberNames(result));
    }

    @Test
    void poisonedMatchBranchCommentsStayScopedToMatch() {
        // A rejected branch never drains its comments; the match tail cleanup consumes them
        // (trailing ones included) so they cannot leak into the enclosing function body, while
        // the less-indented `# outer` stays pending for the function's sequence.
        var result = recoverableParse("func f():\n\tmatch x:\n\t\t" + "[".repeat(254) + "1"
                + "]".repeat(254) + ":\n\t\t\tpass # poison trailing\n\t\t\t# poison whole\n"
                + "\t# outer\nvar after = 2\n");
        assertEquals(List.of("f", "after"), topLevelMemberNames(result));
        var function = (FunctionDeclaration) result.ast().statements().getFirst();
        var commentTexts = function.body().statements().stream()
                .filter(CommentStatement.class::isInstance)
                .map(s -> ((CommentStatement) s).text())
                .toList();
        assertEquals(List.of("# outer"), commentTexts, commentTexts::toString);
    }

    @Test
    void moderatelyDeepNestingStillParsesCleanly() {
        // The budget only trips on pathological input: ordinary deep expressions stay legal,
        // and the depth counter resets between parses on a reused instance.
        var parser = new GdScriptParser();
        var result = parser.parse("var x = " + "(".repeat(64) + "1" + ")".repeat(64) + "\n");
        assertEquals(List.of(), errorMessages(result), () -> errorMessages(result).toString());
        var second = parser.parse("var x = " + "(".repeat(4096));
        assertTrue(errorMessages(second).contains("Maximum nesting depth exceeded."),
                () -> errorMessages(second).toString());
        var third = parser.parse("var ok = 1\n");
        assertEquals(List.of(), errorMessages(third), () -> errorMessages(third).toString());
    }

    // -----------------------------------------------------------------------------------------
    // Non-ASCII error corpus: ranges stay in UTF-8 bytes even when the source is not ASCII.
    // -----------------------------------------------------------------------------------------

    @Test
    void errorAfterCjkLiteralHasUtf8ByteRange() {
        var result = recoverableParse("var x = \"中\" +");
        assertTrue(errorMessages(result).contains("Expected expression after \"+\" operator."),
                () -> errorMessages(result).toString());
        var error = errorsOf(result).getFirst();
        // `中` is 3 UTF-8 bytes: the `+` sits at byte 14, and Point columns are byte columns,
        // so the diagnostic column is 14 rather than the char column 12.
        assertEquals(14, error.range().startByte(), () -> error.range().toString());
        assertEquals(14, error.range().startPoint().column(), () -> error.range().toString());
    }

    @Test
    void errorAfterEmojiLiteralStaysInBounds() {
        // Surrogate pair: UTF-16 length 14, UTF-8 length 16 — only the byte bound is valid.
        recoverableParse("var x = \"😀\" +");
    }

    // -----------------------------------------------------------------------------------------
    // Deferred semantic checks stay deferred: the analyzer-only diagnostics Godot emits at parse
    // time must NOT appear here (pinned so a future port does not re-add them accidentally).
    // -----------------------------------------------------------------------------------------

    @Test
    void deferredSemanticChecksAreNotReported() {
        // Godot's parse-time target-type checks ("Cannot call on an expression.", assignment
        // target restrictions) are analysis-layer work by design.
        var result = parse("func f():\n\tf(1)(2)\n\t1 + 2 = 3\n");
        var deferred = errorMessages(result).stream()
                .filter(m -> m.contains("Cannot call on an expression")
                        || m.contains("can be used as assignment target"))
                .toList();
        assertEquals(List.of(), deferred, () -> errorMessages(result).toString());
    }
}
