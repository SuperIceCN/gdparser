package dev.superice.gdparser.frontend.gdscript;

import dev.superice.gdparser.frontend.ast.AnnotationStatement;
import dev.superice.gdparser.frontend.ast.ArrayExpression;
import dev.superice.gdparser.frontend.ast.AssertStatement;
import dev.superice.gdparser.frontend.ast.AssignmentExpression;
import dev.superice.gdparser.frontend.ast.AstDiagnostic;
import dev.superice.gdparser.frontend.ast.AstDiagnosticSeverity;
import dev.superice.gdparser.frontend.ast.AstMappingResult;
import dev.superice.gdparser.frontend.ast.AttributeCallStep;
import dev.superice.gdparser.frontend.ast.AttributeExpression;
import dev.superice.gdparser.frontend.ast.AttributePropertyStep;
import dev.superice.gdparser.frontend.ast.AttributeSubscriptStep;
import dev.superice.gdparser.frontend.ast.BinaryExpression;
import dev.superice.gdparser.frontend.ast.CallExpression;
import dev.superice.gdparser.frontend.ast.ClassDeclaration;
import dev.superice.gdparser.frontend.ast.CommentStatement;
import dev.superice.gdparser.frontend.ast.DeclarationKind;
import dev.superice.gdparser.frontend.ast.DictEntry;
import dev.superice.gdparser.frontend.ast.DictionaryExpression;
import dev.superice.gdparser.frontend.ast.EnumDeclaration;
import dev.superice.gdparser.frontend.ast.ErrorExpression;
import dev.superice.gdparser.frontend.ast.ExpressionStatement;
import dev.superice.gdparser.frontend.ast.ForStatement;
import dev.superice.gdparser.frontend.ast.FunctionDeclaration;
import dev.superice.gdparser.frontend.ast.IdentifierExpression;
import dev.superice.gdparser.frontend.ast.IfStatement;
import dev.superice.gdparser.frontend.ast.LambdaExpression;
import dev.superice.gdparser.frontend.ast.LiteralExpression;
import dev.superice.gdparser.frontend.ast.MatchSection;
import dev.superice.gdparser.frontend.ast.MatchStatement;
import dev.superice.gdparser.frontend.ast.MissingAttributeStep;
import dev.superice.gdparser.frontend.ast.Node;
import dev.superice.gdparser.frontend.ast.Parameter;
import dev.superice.gdparser.frontend.ast.PassStatement;
import dev.superice.gdparser.frontend.ast.PatternBindingExpression;
import dev.superice.gdparser.frontend.ast.Point;
import dev.superice.gdparser.frontend.ast.Range;
import dev.superice.gdparser.frontend.ast.RecoveryLink;
import dev.superice.gdparser.frontend.ast.ReturnStatement;
import dev.superice.gdparser.frontend.ast.SignalStatement;
import dev.superice.gdparser.frontend.ast.SubscriptExpression;
import dev.superice.gdparser.frontend.ast.TypeRef;
import dev.superice.gdparser.frontend.ast.VariableDeclaration;
import dev.superice.gdparser.frontend.ast.WhileStatement;
import dev.superice.gdparser.frontend.cst.CstIssueKind;
import dev.superice.gdparser.frontend.serialize.AstSexprDeserializer;
import dev.superice.gdparser.frontend.serialize.AstSexprSerializer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
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
import static org.junit.jupiter.api.Assertions.assertSame;
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

    private static <T> @NotNull T requireNotNull(@Nullable T value) {
        assertNotNull(value);
        return value;
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
        var closerDiag = findErrorByCode(result, DiagnosticCodes.UNEXPECTED_CLOSER);
        assertEquals("Unexpected closing \")\".", closerDiag.message());
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
        // The opened initializer slot keeps a zero-width MISSING marker at the `=` end.
        var marker = assertInstanceOf(ErrorExpression.class, x.value());
        assertEquals(CstIssueKind.MISSING, marker.kind());
        assertEquals("variable.initializer", marker.nodeType());
        assertEquals(7, marker.range().startByte());
        assertEquals(7, marker.range().endByte());
        assertEquals("parse.missing_expression.variable_initializer", errorsOf(result).getFirst().code());
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
        // The damaged binary survives with a MISSING marker in its right slot.
        var binary = assertInstanceOf(BinaryExpression.class, x.value());
        assertEquals("+", binary.operator());
        assertEquals("1", assertInstanceOf(LiteralExpression.class, binary.left()).sourceText());
        var right = assertInstanceOf(ErrorExpression.class, binary.right());
        assertEquals(CstIssueKind.MISSING, right.kind());
        assertEquals("binary.right", right.nodeType());
        assertEquals("y", assertInstanceOf(VariableDeclaration.class, statements.get(1)).name());
    }

    // -----------------------------------------------------------------------------------------
    // Unclosed constructs at EOF: exactly one MISSING-class diagnostic each, node survives.
    // -----------------------------------------------------------------------------------------

    @Test
    void unclosedGroupingAtEof() {
        var result = recoverableParse("var x = (1 + 2");
        var errors = errorsOf(result);
        assertEquals(List.of("Expected closing \")\" after grouping expression."), errorMessages(result),
                () -> errorMessages(result).toString());
        // Missing-closer diagnostics are zero-width at the failure point and carry a stable code
        // plus a RecoveryLink to the owning construct (here the variable's initializer facet).
        var error = errors.getFirst();
        assertEquals("parse.missing_closer.grouping", error.code());
        assertEquals(14, error.range().startByte());
        assertEquals(14, error.range().endByte());
        var x = assertInstanceOf(VariableDeclaration.class, result.ast().statements().getFirst());
        assertNotNull(error.recovery());
        assertSame(x, error.recovery().owner());
        assertEquals("variable.initializer", error.recovery().facet());
        assertInstanceOf(BinaryExpression.class, x.value());
    }

    @Test
    void unclosedArrayAtEof() {
        var result = recoverableParse("var x = [1, 2");
        assertTrue(errorMessages(result).contains("Expected closing \"]\" after array elements."),
                () -> errorMessages(result).toString());
        var error = errorsOf(result).getFirst();
        assertEquals("parse.missing_closer.array", error.code());
        var x = assertInstanceOf(VariableDeclaration.class, result.ast().statements().getFirst());
        assertNotNull(error.recovery());
        assertSame(x, error.recovery().owner());
        assertEquals("variable.initializer", error.recovery().facet());
        assertInstanceOf(ArrayExpression.class, x.value());
    }

    @Test
    void unclosedDictionaryAtEof() {
        var result = recoverableParse("var x = {\"a\": 1");
        assertTrue(errorMessages(result).contains("Expected closing \"}\" after dictionary elements."),
                () -> errorMessages(result).toString());
        var error = errorsOf(result).getFirst();
        assertEquals("parse.missing_closer.dictionary", error.code());
        var x = assertInstanceOf(VariableDeclaration.class, result.ast().statements().getFirst());
        assertNotNull(error.recovery());
        assertSame(x, error.recovery().owner());
        assertInstanceOf(DictionaryExpression.class, x.value());
    }

    /// Godot comparison: for `var x = f(1, 2` Godot reports exactly one error,
    /// `Expected closing ")" after call arguments.` (gdscript_parser.cpp:616-622). The parser's
    /// missing-closer diagnostics are zero-width at the failure point (`extentEnd()`, the end of
    /// the `2` token) rather than spanning Godot's `previous` token — the coordinate policy
    /// change is registered in the plan's §6.2. The call node survives with both arguments and
    /// the diagnostic links to the owning variable's initializer facet.
    @Test
    void unclosedCallAtEofRecoversArguments() {
        var result = recoverableParse("var x = f(1, 2");
        var errors = errorMessages(result);
        assertEquals(List.of("Expected closing \")\" after call arguments."), errors, errors::toString);
        var error = errorsOf(result).getFirst();
        assertEquals("parse.missing_closer.call", error.code());
        assertEquals(0, error.range().startPoint().row(), () -> error.range().toString());
        assertEquals(14, error.range().startPoint().column(), () -> error.range().toString());
        assertEquals(14, error.range().endPoint().column(), () -> error.range().toString());
        var x = assertInstanceOf(VariableDeclaration.class, result.ast().statements().getFirst());
        assertNotNull(error.recovery());
        assertSame(x, error.recovery().owner());
        assertEquals("variable.initializer", error.recovery().facet());
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
    /// The function survives and the chain keeps the receiver with a zero-width
    /// `MissingAttributeStep` at the dot's end (the receiver stays visible for completion).
    @Test
    void danglingDotAtEofKeepsFunction() {
        var result = recoverableParse("func f():\n\tnode.");
        var errors = errorMessages(result);
        assertEquals(List.of("Expected identifier after \".\" for attribute access."), errors,
                errors::toString);
        var error = errorsOf(result).getFirst();
        assertEquals("parse.attribute.missing_name", error.code());
        assertEquals(1, error.range().startPoint().row(), () -> error.range().toString());
        assertEquals(5, error.range().startPoint().column(), () -> error.range().toString());
        var function = onlyFunction(result);
        var body = function.body().statements();
        assertEquals(1, body.size(), () -> "body: " + body);
        var statement = assertInstanceOf(ExpressionStatement.class, body.getFirst());
        var chain = assertInstanceOf(AttributeExpression.class, statement.expression());
        assertEquals("node", assertInstanceOf(IdentifierExpression.class, chain.base()).name());
        assertEquals(1, chain.steps().size());
        var missing = assertInstanceOf(MissingAttributeStep.class, chain.steps().getFirst());
        // Zero-width at the dot's end (the `.` token ends at byte 16).
        assertEquals(16, missing.range().startByte());
        assertEquals(16, missing.range().endByte());
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
    /// An unclosed parameter list followed by a header-end `:` and a strictly deeper suite line
    /// yields the `:` back as the function body introducer instead of eating it as a missing
    /// parameter type slot, emitting only MISSING_CLOSER_PARAMETER_LIST and retaining the body.
    @Test
    void missingClosingParenInFunctionParametersRecovers() {
        var result = recoverableParse("func f(a, b:\n\tpass\n");
        var errors = errorsOf(result);
        assertEquals(1, errors.size(), () -> errors.toString());
        assertEquals(DiagnosticCodes.MISSING_CLOSER_PARAMETER_LIST, errors.getFirst().code());
        assertEquals("Expected closing \")\" after function parameters.", errors.getFirst().message());
        var statements = result.ast().statements();
        assertEquals(1, statements.size(), () -> "members: " + statements);
        var function = assertInstanceOf(FunctionDeclaration.class, statements.getFirst());
        assertEquals("f", function.name());
        assertEquals(List.of("a", "b"),
                function.parameters().stream().map(Parameter::name).toList());
        assertNull(function.parameters().get(1).type());
        assertEquals(1, function.body().statements().size(), () -> function.body().toString());
        assertInstanceOf(PassStatement.class, function.body().statements().getFirst());
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
        // Bounded depth-trip unwinding settles matching closers before releasing the branch colon,
        // so both the real `]:` body and the outer top-level sibling survive.
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
                var depthDiag = findErrorByCode(result, DiagnosticCodes.NESTING_DEPTH_EXCEEDED);
                assertEquals("Maximum nesting depth exceeded.", depthDiag.message());
            } else {
                assertEquals(List.of(), errorMessages(result), () -> "depth " + depth);
            }
            assertEquals(1, body.size(), () -> "depth " + depth + ": " + body);
            assertInstanceOf(PassStatement.class, body.getFirst());
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
        // Bounded unwinding keeps the outer `[` multiline owner active across the depth trip so
        // `\t\t]:` closes cleanly without diagnostic flooding, `_: return 7` stays a branch of the
        // same `match`, `local_after` stays in `f`, and `after` stays top-level.
        var result = parse("func f():\n\tmatch x:\n\t\t[\n\t\t\t" + "[".repeat(254) + "1"
                + "]".repeat(254) + "\n\t\t]:\n\t\t\tpass\n\t\t_: return 7\n"
                + "\tvar local_after = 7\nvar after = 2\n");
        var depthDiag = findErrorByCode(result, DiagnosticCodes.NESTING_DEPTH_EXCEEDED);
        assertEquals("Maximum nesting depth exceeded.", depthDiag.message());
        assertEquals(1, errorsOf(result).size(), () -> "unexpected diagnostic flood: " + errorsOf(result));
        assertEquals(List.of("f", "after"), topLevelMemberNames(result));

        var function = (FunctionDeclaration) result.ast().statements().getFirst();
        var fnStatements = function.body().statements();
        assertEquals(2, fnStatements.size(), () -> fnStatements.toString());
        var match = assertInstanceOf(MatchStatement.class, fnStatements.getFirst());
        assertEquals(2, match.sections().size(), () -> match.sections().toString());
        assertInstanceOf(PassStatement.class, match.sections().getFirst().body().statements().getFirst());
        var fallbackReturn = assertInstanceOf(ReturnStatement.class,
                match.sections().get(1).body().statements().getFirst());
        assertEquals("7", assertInstanceOf(LiteralExpression.class, fallbackReturn.value()).sourceText());

        var localAfter = assertInstanceOf(VariableDeclaration.class, fnStatements.get(1));
        assertEquals("local_after", localAfter.name());
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

    // -----------------------------------------------------------------------------------------
    // Sealed-outcome recovery (T1/T2): return-value and variable-initializer slots.
    // -----------------------------------------------------------------------------------------

    @Test
    void returnDamagedValueKeepsMarker() {
        // `return 1+`: the value is the damaged binary with a MISSING right slot; exactly one
        // diagnostic (the inner `+` root cause) — the slot host adds no generic message.
        var result = recoverableParse("func f():\n\treturn 1+\n");
        assertEquals(List.of("Expected expression after \"+\" operator."), errorMessages(result),
                () -> errorMessages(result).toString());
        var body = onlyFunction(result).body().statements();
        var returnStatement = assertInstanceOf(ReturnStatement.class, body.getFirst());
        var binary = assertInstanceOf(BinaryExpression.class, returnStatement.value());
        assertEquals("1", assertInstanceOf(LiteralExpression.class, binary.left()).sourceText());
        var right = assertInstanceOf(ErrorExpression.class, binary.right());
        assertEquals(CstIssueKind.MISSING, right.kind());
        assertEquals("binary.right", right.nodeType());
    }

    @Test
    void returnSlotControls() {
        // Bare return and healthy value: no marker, no diagnostics.
        var bare = parse("func f():\n\treturn\n");
        assertEquals(List.of(), errorMessages(bare), () -> errorMessages(bare).toString());
        assertNull(assertInstanceOf(ReturnStatement.class,
                onlyFunction(bare).body().statements().getFirst()).value());

        // Lambda-internal zero-consumption: `return` before the call's comma stays bare and
        // produces no diagnostic.
        var inCall = parse("var x = call(func(): return, 1)\n");
        assertEquals(List.of(), errorMessages(inCall), () -> errorMessages(inCall).toString());
        var call = assertInstanceOf(CallExpression.class,
                assertInstanceOf(VariableDeclaration.class, inCall.ast().statements().getFirst()).value());
        var lambda = assertInstanceOf(LambdaExpression.class, call.arguments().getFirst());
        assertNull(assertInstanceOf(ReturnStatement.class,
                lambda.body().statements().getFirst()).value());

        // `return =3`: `=` opens no expression; the value stays absent and the only diagnostic
        // comes from the statement tail (the garbage token is not double-reported by the slot).
        var result = recoverableParse("func f():\n\treturn =3\n");
        assertEquals(List.of("Expected end of statement after return statement, found \"=\" instead."),
                errorMessages(result), () -> errorMessages(result).toString());
        assertNull(assertInstanceOf(ReturnStatement.class,
                onlyFunction(result).body().statements().getFirst()).value());
    }

    @Test
    void returnFailedValueKeepsErrorFragment() {
        // A get-node failure consumes tokens and reports inside; the slot fills an ERROR
        // fragment over the consumed span without a second generic message.
        var result = recoverableParse("func f():\n\treturn $/!!\n");
        assertTrue(errorMessages(result).contains("Unexpected \"!\" in node path."),
                () -> errorMessages(result).toString());
        var returnStatement = assertInstanceOf(ReturnStatement.class,
                onlyFunction(result).body().statements().getFirst());
        var fragment = assertInstanceOf(ErrorExpression.class, returnStatement.value());
        assertEquals(CstIssueKind.ERROR, fragment.kind());
        assertEquals("$/", fragment.sourceText());
    }

    @Test
    void variableInitializerSlotShapes() {
        // Damaged value: the partial node survives inside the declaration.
        var damaged = recoverableParse("var hp = 1+\nvar mp = 2\n");
        var hp = assertInstanceOf(VariableDeclaration.class, damaged.ast().statements().getFirst());
        var binary = assertInstanceOf(BinaryExpression.class, hp.value());
        assertInstanceOf(ErrorExpression.class, binary.right());
        assertEquals("mp", assertInstanceOf(VariableDeclaration.class,
                damaged.ast().statements().get(1)).name());

        // Zero-consumption value: zero-width MISSING marker at the `=` end.
        var missing = recoverableParse("var hp =\nvar mp = 2\n");
        var marker = assertInstanceOf(ErrorExpression.class,
                assertInstanceOf(VariableDeclaration.class, missing.ast().statements().getFirst()).value());
        assertEquals(CstIssueKind.MISSING, marker.kind());
        assertEquals("variable.initializer", marker.nodeType());

        // Failed value (get-node consumed tokens, reported inside): ERROR fragment over the
        // consumed span, no generic slot message.
        var failed = recoverableParse("var hp = $/!!\nvar mp = 2\n");
        assertTrue(errorMessages(failed).contains("Unexpected \"!\" in node path."),
                () -> errorMessages(failed).toString());
        var fragment = assertInstanceOf(ErrorExpression.class,
                assertInstanceOf(VariableDeclaration.class, failed.ast().statements().getFirst()).value());
        assertEquals(CstIssueKind.ERROR, fragment.kind());
        assertEquals("$/", fragment.sourceText());
        assertEquals("mp", assertInstanceOf(VariableDeclaration.class,
                failed.ast().statements().get(1)).name());
    }

    // -----------------------------------------------------------------------------------------
    // T3/T4: parameter defaults and annotation arguments.
    // -----------------------------------------------------------------------------------------

    @Test
    void parameterDefaultSlotShapes() {
        // Name and type survive with a damaged default value.
        var damaged = recoverableParse("func f(x: int = 1+): pass\n");
        var function = onlyFunction(damaged);
        var parameter = function.parameters().getFirst();
        assertEquals("x", parameter.name());
        assertEquals("int", requireNotNull(parameter.type()).sourceText());
        assertInstanceOf(BinaryExpression.class, parameter.defaultValue());

        // `=` written but nothing follows: zero-width MISSING marker (distinguishable from a
        // never-written `=`).
        var missing = recoverableParse("func f(x=): pass\n");
        var marker = assertInstanceOf(ErrorExpression.class,
                onlyFunction(missing).parameters().getFirst().defaultValue());
        assertEquals(CstIssueKind.MISSING, marker.kind());
        assertEquals("parameter.default", marker.nodeType());

        // Controls: inferred-type default and no default produce no diagnostics.
        var inferred = parse("func f(x:=1): pass\n");
        assertEquals(List.of(), errorMessages(inferred), () -> errorMessages(inferred).toString());
        var plain = parse("func f(x): pass\n");
        assertEquals(List.of(), errorMessages(plain), () -> errorMessages(plain).toString());
        assertNull(onlyFunction(plain).parameters().getFirst().defaultValue());
    }

    @Test
    void annotationArgumentSlotShapes() {
        // A damaged argument keeps its position between healthy siblings.
        var damaged = recoverableParse("@export_range(0, 1+, 2)\nvar x = 1\n");
        var annotation = assertInstanceOf(AnnotationStatement.class,
                damaged.ast().statements().getFirst());
        assertEquals(3, annotation.arguments().size());
        assertEquals("0", assertInstanceOf(LiteralExpression.class, annotation.arguments().getFirst()).sourceText());
        assertInstanceOf(BinaryExpression.class, annotation.arguments().get(1));
        assertEquals("2", assertInstanceOf(LiteralExpression.class, annotation.arguments().get(2)).sourceText());

        // A zero-consumption argument slot keeps a MISSING marker mid-list.
        var missing = recoverableParse("@export_enum(\"A\", ,\"B\")\nvar x = 1\n");
        var enumAnnotation = assertInstanceOf(AnnotationStatement.class,
                missing.ast().statements().getFirst());
        assertEquals(3, enumAnnotation.arguments().size());
        var marker = assertInstanceOf(ErrorExpression.class, enumAnnotation.arguments().get(1));
        assertEquals(CstIssueKind.MISSING, marker.kind());
        assertEquals("annotation.argument", marker.nodeType());

        // A legal annotation produces no diagnostics.
        var legal = parse("@export_range(0, 1, 2)\nvar x = 1\n");
        assertEquals(List.of(), errorMessages(legal), () -> errorMessages(legal).toString());
    }

    // -----------------------------------------------------------------------------------------
    // T5: container slots.
    // -----------------------------------------------------------------------------------------

    @Test
    void containerSlotShapes() {
        // Mid-list missing element keeps a marker; positions are preserved.
        var array = recoverableParse("var a = [1, , 3]\n");
        var elements = assertInstanceOf(ArrayExpression.class,
                assertInstanceOf(VariableDeclaration.class, array.ast().statements().getFirst()).value())
                .elements();
        assertEquals(3, elements.size());
        var elementMarker = assertInstanceOf(ErrorExpression.class, elements.get(1));
        assertEquals("array.element", elementMarker.nodeType());

        // Missing value: marker anchors at the `:` end and the entry envelope stays consistent.
        var missingValue = recoverableParse("var d = {\"a\":}\n");
        var entry = assertInstanceOf(DictionaryExpression.class,
                assertInstanceOf(VariableDeclaration.class, missingValue.ast().statements().getFirst()).value())
                .entries().getFirst();
        var valueMarker = assertInstanceOf(ErrorExpression.class, entry.value());
        assertEquals(CstIssueKind.MISSING, valueMarker.kind());
        assertEquals("dictionary.value", valueMarker.nodeType());
        assertEquals(13, valueMarker.range().startByte());
        assertEquals(13, valueMarker.range().endByte());
        assertEquals(entry.range().startByte(), entry.key().range().startByte());
        assertEquals(entry.range().endByte(), entry.value().range().endByte());

        // Missing key: the entry start moves forward to the marker anchor.
        var missingKey = recoverableParse("var d = {:1}\n");
        var keyEntry = assertInstanceOf(DictionaryExpression.class,
                assertInstanceOf(VariableDeclaration.class, missingKey.ast().statements().getFirst()).value())
                .entries().getFirst();
        var keyMarker = assertInstanceOf(ErrorExpression.class, keyEntry.key());
        assertEquals("dictionary.key", keyMarker.nodeType());
        assertEquals(9, keyEntry.range().startByte());

        // Damaged key: the partial node is kept (no Lua-normalization complaint on top of the
        // root-cause diagnostic), and the entry starts at the key's consumed start.
        var damagedKey = recoverableParse("var d = {1+:2}\n");
        assertEquals(List.of("Expected expression after \"+\" operator."), errorMessages(damagedKey),
                () -> errorMessages(damagedKey).toString());
        var damagedEntry = assertInstanceOf(DictionaryExpression.class,
                assertInstanceOf(VariableDeclaration.class, damagedKey.ast().statements().getFirst()).value())
                .entries().getFirst();
        assertInstanceOf(BinaryExpression.class, damagedEntry.key());
        assertEquals(9, damagedEntry.range().startByte());

        // Both sides missing: the entry is dropped (no marker-only pair is materialized).
        var bothMissing = recoverableParse("var d = {:}\n");
        var emptyDict = assertInstanceOf(DictionaryExpression.class,
                assertInstanceOf(VariableDeclaration.class, bothMissing.ast().statements().getFirst()).value());
        assertEquals(0, emptyDict.entries().size());
        assertEquals(2, errorMessages(bothMissing).size());

        // Legal forms: no markers, no diagnostics.
        for (var legal : List.of("var f = f()\n", "var f = f(1,)\n", "var a = [1, 2,]\n")) {
            assertEquals(List.of(), errorMessages(parse(legal)), () -> legal);
        }
    }

    @Test
    void groupedDictionaryKeyKeepsGroupingInEntryRange() {
        // Control: a legal grouping key's entry range covers the parentheses.
        var result = parse("var d = {(a): 1}\n");
        assertEquals(List.of(), errorMessages(result), () -> errorMessages(result).toString());
        var entry = assertInstanceOf(DictionaryExpression.class,
                assertInstanceOf(VariableDeclaration.class, result.ast().statements().getFirst()).value())
                .entries().getFirst();
        assertEquals(9, entry.range().startByte());
        assertEquals(15, entry.range().endByte());
    }

    // -----------------------------------------------------------------------------------------
    // T6: missing-closer diagnostics carry a code and a RecoveryLink to the owning construct;
    // geometrically identical diagnostics bind by the owner stack across lambda boundaries.
    // -----------------------------------------------------------------------------------------

    @Test
    void missingCloserBindsToOuterVariableAcrossLambda() {
        // The grouping opens OUTSIDE the lambda; its missing `)` belongs to the outer
        // variable's initializer facet, not to the lambda's return.
        var result = recoverableParse("var cb = (func(): return 1\n");
        var error = errorsOf(result).stream()
                .filter(d -> "parse.missing_closer.grouping".equals(d.code()))
                .findFirst().orElseThrow();
        var variable = assertInstanceOf(VariableDeclaration.class, result.ast().statements().getFirst());
        assertNotNull(error.recovery());
        assertSame(variable, error.recovery().owner());
        assertEquals("variable.initializer", error.recovery().facet());
    }

    @Test
    void missingCloserBindsToInnerReturnInsideLambda() {
        // The grouping opens INSIDE the lambda's return value; the same diagnostic kind binds
        // to the inner return statement instead.
        var result = recoverableParse("var cb = func(): return (1\n");
        var error = errorsOf(result).stream()
                .filter(d -> "parse.missing_closer.grouping".equals(d.code()))
                .findFirst().orElseThrow();
        var variable = assertInstanceOf(VariableDeclaration.class, result.ast().statements().getFirst());
        var lambda = assertInstanceOf(LambdaExpression.class, variable.value());
        var returnStatement = assertInstanceOf(ReturnStatement.class,
                lambda.body().statements().getFirst());
        assertNotNull(error.recovery());
        assertSame(returnStatement, error.recovery().owner());
        assertEquals("return.value", error.recovery().facet());
    }

    @Test
    void unclosedCallAtEofKeepsCallAndBindsLink() {
        // `target(1` at EOF (no final newline): the call survives with its argument and the
        // missing-`)` diagnostic binds to the variable initializer facet.
        var result = recoverableParse("var x = target(1");
        var error = errorsOf(result).stream()
                .filter(d -> "parse.missing_closer.call".equals(d.code()))
                .findFirst().orElseThrow();
        var variable = assertInstanceOf(VariableDeclaration.class, result.ast().statements().getFirst());
        var call = assertInstanceOf(CallExpression.class, variable.value());
        assertEquals(1, call.arguments().size());
        assertNotNull(error.recovery());
        assertSame(variable, error.recovery().owner());
    }

    /// Every bound recovery link must point at an owner reachable from the final AST
    /// (identity semantics): the end-of-parse sweep unlinks owners of discarded constructs.
    @Test
    void recoveryLinkOfDiscardedOwnerIsSwept() {
        // The grouping's missing-`)` diagnostic is first bound to the inner return statement;
        // the enclosing duplicate parameter `x` is then dropped from the signature, making the
        // return (inside the lambda default) unreachable — the sweep must clear the link while
        // keeping code, range and message.
        var source = "func f(x, x = func(): return (1";
        var result = recoverableParse(source);
        var function = assertInstanceOf(FunctionDeclaration.class, result.ast().statements().getFirst());
        assertEquals(1, function.parameters().size());
        var closer = errorsOf(result).stream()
                .filter(d -> "parse.missing_closer.grouping".equals(d.code()))
                .findFirst().orElseThrow();
        assertNull(closer.recovery());
        assertEquals("Expected closing \")\" after grouping expression.", closer.message());
        assertEquals(source.length(), closer.range().startByte());
        assertEquals(source.length(), closer.range().endByte());
    }

    @Test
    void recoveryLinkOwnersAreReachableFromFinalAst() {
        // Sweep invariant over a corpus that binds links on surviving owners (variable, return,
        // parameter, assert, expression statement): every bound owner is identity-reachable.
        var corpus = List.of(
                "var hp = (1",
                "func f():\n\treturn (1\n",
                "func f(...args = (1: pass\n",
                "func f():\n\tassert(1+, \"m\"\n",
                "func f():\n\tfoo(1\n"
        );
        for (var source : corpus) {
            var result = recoverableParse(source);
            for (var diagnostic : errorsOf(result)) {
                if (diagnostic.recovery() != null) {
                    var owner = diagnostic.recovery().owner();
                    assertTrue(isReachable(result.ast(), owner),
                            () -> "owner not reachable from AST: " + diagnostic + " in: " + source);
                }
            }
        }
    }

    private static boolean isReachable(Node root, Node target) {
        if (root == target) {
            return true;
        }
        for (var child : root.getChildren()) {
            if (isReachable(child, target)) {
                return true;
            }
        }
        return false;
    }

    // -----------------------------------------------------------------------------------------
    // T18: C1 (if/while/for) and C2 (assert/const/match) statements survive with markers.
    // -----------------------------------------------------------------------------------------

    @Test
    void controlFlowHeadersSurviveWithMarkers() {
        var ifResult = recoverableParse("func f():\n\tif 1+: pass\n");
        var ifStatement = assertInstanceOf(IfStatement.class,
                onlyFunction(ifResult).body().statements().getFirst());
        assertInstanceOf(BinaryExpression.class, ifStatement.condition());
        assertEquals(1, ifStatement.body().statements().size());

        var whileResult = recoverableParse("func f():\n\twhile : pass\n");
        var whileStatement = assertInstanceOf(WhileStatement.class,
                onlyFunction(whileResult).body().statements().getFirst());
        var whileMarker = assertInstanceOf(ErrorExpression.class, whileStatement.condition());
        assertEquals("while.condition", whileMarker.nodeType());
        assertEquals(1, whileStatement.body().statements().size());

        var forResult = recoverableParse("func f():\n\tfor x in : pass\n");
        var forStatement = assertInstanceOf(ForStatement.class,
                onlyFunction(forResult).body().statements().getFirst());
        var iterableMarker = assertInstanceOf(ErrorExpression.class, forStatement.iterable());
        assertEquals("for.iterable", iterableMarker.nodeType());
        assertEquals("x", forStatement.iterator());
        assertEquals(1, forStatement.body().statements().size());
    }

    @Test
    void c2StatementsSurviveAndConsumeTail() {
        // assert keeps both slots and consumes the `)` + statement end like the success path.
        var assertResult = recoverableParse("func f():\n\tassert(1+, \"msg\")\n");
        assertEquals(List.of("Expected expression after \"+\" operator."), errorMessages(assertResult),
                () -> errorMessages(assertResult).toString());
        var assertStatement = assertInstanceOf(AssertStatement.class,
                onlyFunction(assertResult).body().statements().getFirst());
        assertInstanceOf(BinaryExpression.class, assertStatement.condition());
        assertEquals("\"msg\"", assertInstanceOf(LiteralExpression.class,
                requireNotNull(assertStatement.message())).sourceText());

        var assertMessage = recoverableParse("func f():\n\tassert(true, 1+)\n");
        var messageStatement = assertInstanceOf(AssertStatement.class,
                onlyFunction(assertMessage).body().statements().getFirst());
        assertInstanceOf(BinaryExpression.class, messageStatement.message());

        // const survives and consumes its statement end.
        var constResult = recoverableParse("const A = 1+\nvar b = 2\n");
        assertEquals(List.of("Expected expression after \"+\" operator."), errorMessages(constResult),
                () -> errorMessages(constResult).toString());
        var constant = assertInstanceOf(VariableDeclaration.class, constResult.ast().statements().getFirst());
        assertEquals("A", constant.name());
        assertInstanceOf(BinaryExpression.class, constant.value());
        assertEquals("b", assertInstanceOf(VariableDeclaration.class,
                constResult.ast().statements().get(1)).name());

        // match survives with a damaged value and parses its sections.
        var matchResult = recoverableParse("func f():\n\tmatch 1+:\n\t\t1: pass\n");
        assertEquals(List.of("Expected expression after \"+\" operator."), errorMessages(matchResult),
                () -> errorMessages(matchResult).toString());
        var match = assertInstanceOf(MatchStatement.class,
                onlyFunction(matchResult).body().statements().getFirst());
        assertInstanceOf(BinaryExpression.class, match.value());
        assertEquals(1, match.sections().size());
    }

    @Test
    void survivingAssertAbsorbsSyncKeywordIntoMessageSlot() {
        // `func` is a sync keyword, so `assert(1+, func(): pass)` parses `func(): pass` as its
        // message slot and cleanly settles `)` with only the inner `+` error.
        var result = recoverableParse("func f():\n\tassert(1+, func(): pass)\n");
        assertEquals(List.of("Expected expression after \"+\" operator."), errorMessages(result),
                () -> errorMessages(result).toString());
        var statement = assertInstanceOf(AssertStatement.class,
                onlyFunction(result).body().statements().getFirst());
        assertInstanceOf(BinaryExpression.class, statement.condition());
        assertInstanceOf(LambdaExpression.class, statement.message());
    }

    @Test
    void circuitBreakerCountsNullStatementsWithoutHealthyReset() {
        // Healthy statements interleaved between failures must not reset the cumulative count:
        // 105 genuinely failing `var` statements (parser-level nulls) interleaved with healthy
        // `pass` lines still trip the breaker at the 102nd failure.
        var source = "func f():\n" + "\tvar\n\tpass\n".repeat(105);
        var result = recoverableParse(source);
        var breaker = findErrorByCode(result, DiagnosticCodes.STATEMENT_TOO_MANY_ERRORS);
        assertEquals("Too many statement errors.", breaker.message());
    }

    // -----------------------------------------------------------------------------------------
    // T19: 3A slot markers — match guards, enum values, elif conditions, dictionary pattern keys.
    // -----------------------------------------------------------------------------------------

    @Test
    void matchGuardFailureKeepsMarker() {
        var result = recoverableParse("func f():\n\tmatch x:\n\t\t1 when : pass\n");
        var match = assertInstanceOf(MatchStatement.class,
                onlyFunction(result).body().statements().getFirst());
        var section = match.sections().getFirst();
        var guard = assertInstanceOf(ErrorExpression.class, section.guard());
        assertEquals(CstIssueKind.MISSING, guard.kind());
        assertEquals("match.guard", guard.nodeType());
        assertEquals(1, section.body().statements().size());
    }

    @Test
    void enumValueFailureKeepsMarkerAndExtendsMemberRange() {
        var result = recoverableParse("enum E { A =, B }\n");
        var enumDeclaration = assertInstanceOf(EnumDeclaration.class, result.ast().statements().getFirst());
        assertEquals(2, enumDeclaration.members().size());
        var a = enumDeclaration.members().getFirst();
        var marker = assertInstanceOf(ErrorExpression.class, a.value());
        assertEquals(CstIssueKind.MISSING, marker.kind());
        assertEquals("enum.value", marker.nodeType());
        // The member range covers the marker (envelope), and the healthy sibling is untouched.
        assertEquals(marker.range().endByte(), a.range().endByte());
        assertNull(enumDeclaration.members().get(1).value());
    }

    @Test
    void damagedElifClauseKeepsBody() {
        var result = recoverableParse("func f():\n\tif a: pass\n\telif : pass\n");
        var ifStatement = assertInstanceOf(IfStatement.class,
                onlyFunction(result).body().statements().getFirst());
        assertEquals(1, ifStatement.elifClauses().size());
        var clause = ifStatement.elifClauses().getFirst();
        var condition = assertInstanceOf(ErrorExpression.class, clause.condition());
        assertEquals("elif.condition", condition.nodeType());
        assertEquals(1, clause.body().statements().size());
    }

    @Test
    void dictionaryPatternDamagedKeyKeepsEntryWithCarrier() {
        // A damaged key with a surviving `: sub-pattern` carrier stays in its entry.
        var result = recoverableParse("func f():\n\tmatch x:\n\t\t{1+: 1}: pass\n");
        assertEquals(List.of("Expected expression after \"+\" operator."), errorMessages(result),
                () -> errorMessages(result).toString());
        var match = assertInstanceOf(MatchStatement.class,
                onlyFunction(result).body().statements().getFirst());
        var dictionary = assertInstanceOf(DictionaryExpression.class,
                match.sections().getFirst().patterns().getFirst());
        assertEquals(1, dictionary.entries().size());
        assertInstanceOf(BinaryExpression.class, dictionary.entries().getFirst().key());
    }

    @Test
    void dictionaryPatternHomelessKeyFallsBackToErrorFragment() {
        // A key-only damaged entry stays as DictEntry(DamagedKey, null) inside the
        // DictionaryExpression rather than collapsing the whole pattern to an ERROR fragment.
        var result = recoverableParse("func f():\n\tmatch x:\n\t\t{1+}: pass\n");
        assertEquals(List.of("Expected expression after \"+\" operator."), errorMessages(result),
                () -> errorMessages(result).toString());
        var match = assertInstanceOf(MatchStatement.class,
                onlyFunction(result).body().statements().getFirst());
        var dictionary = assertInstanceOf(DictionaryExpression.class,
                match.sections().getFirst().patterns().getFirst());
        assertEquals(1, dictionary.entries().size());
        assertInstanceOf(BinaryExpression.class, dictionary.entries().getFirst().key());
        assertNull(dictionary.entries().getFirst().value());
        assertEquals(1, match.sections().getFirst().body().statements().size());
    }

    @Test
    void dictionaryPatternMixedEntriesFallBackTogether() {
        // Key-only damaged entries and healthy `key: value` entries coexist in the same
        // DictionaryExpression pattern.
        var result = recoverableParse("func f():\n\tmatch x:\n\t\t{1+, 2: 3}: pass\n");
        assertEquals(List.of("Expected expression after \"+\" operator."), errorMessages(result),
                () -> errorMessages(result).toString());
        var match = assertInstanceOf(MatchStatement.class,
                onlyFunction(result).body().statements().getFirst());
        var dictionary = assertInstanceOf(DictionaryExpression.class,
                match.sections().getFirst().patterns().getFirst());
        assertEquals(2, dictionary.entries().size());
        assertInstanceOf(BinaryExpression.class, dictionary.entries().getFirst().key());
        assertNull(dictionary.entries().getFirst().value());
        assertEquals("2", assertInstanceOf(LiteralExpression.class, dictionary.entries().get(1).key()).sourceText());
        assertEquals("3", assertInstanceOf(LiteralExpression.class, dictionary.entries().get(1).value()).sourceText());
    }

    @Test
    void dictionaryPatternNoCommaTailStopsLoopAndReportsCloser() {
        // After `{1+`, `var x: 3` is not a legal key starter: bounded same-level sync skips to
        // the real `}`, preserves the damaged entry, consumes `}`, and retains `: pass` without
        // piling a secondary error on top of the damaged key.
        var result = recoverableParse("func f():\n\tmatch x:\n\t\t{1+ var x: 3}: pass\n");
        assertEquals(List.of("Expected expression after \"+\" operator."),
                errorMessages(result), () -> errorMessages(result).toString());
        var match = assertInstanceOf(MatchStatement.class,
                onlyFunction(result).body().statements().getFirst());
        var section = match.sections().getFirst();
        var dictionary = assertInstanceOf(DictionaryExpression.class, section.patterns().getFirst());
        assertEquals(1, dictionary.entries().size());
        assertInstanceOf(BinaryExpression.class, dictionary.entries().getFirst().key());
        assertNull(dictionary.entries().getFirst().value());
        assertEquals(1, section.body().statements().size());
        assertInstanceOf(PassStatement.class, section.body().statements().getFirst());

        // When the key itself is healthy (`1`), the unexpected non-comma tail `var x: 3` emits
        // SYNTAX_UNEXPECTED_TOKEN once while still keeping the entry and `: pass`.
        var healthyKeyResult = recoverableParse("func f():\n\tmatch x:\n\t\t{1 var x: 3}: pass\n");
        assertEquals(List.of("Unexpected \"var\" in dictionary pattern."),
                errorMessages(healthyKeyResult), () -> errorMessages(healthyKeyResult).toString());
        var healthyMatch = assertInstanceOf(MatchStatement.class,
                onlyFunction(healthyKeyResult).body().statements().getFirst());
        assertEquals(1, healthyMatch.sections().getFirst().body().statements().size());
    }

    @Test
    void matchPatternMarkerInFollowUpBranchStaysInsideSection() {
        // A missing pattern in a non-first branch anchors at `extentEnd()` — the preceding
        // branch's newline end, before the section's content start; the section range extends to
        // cover its retained marker (range envelope).
        var result = recoverableParse("func f():\n\tmatch x:\n\t\t1: pass\n\t\t: pass\n");
        var match = assertInstanceOf(MatchStatement.class,
                onlyFunction(result).body().statements().getFirst());
        assertEquals(2, match.sections().size());
        var section = match.sections().get(1);
        var marker = assertInstanceOf(ErrorExpression.class, section.patterns().getFirst());
        assertEquals(CstIssueKind.MISSING, marker.kind());
        assertEquals("match.pattern", marker.nodeType());
        assertTrue(section.range().startByte() <= marker.range().startByte(),
                () -> "section " + section.range() + " must cover marker " + marker.range());
    }

    // -----------------------------------------------------------------------------------------
    // T20: protocol-directed cases — stop-fold, absorbing hosts, statement-dispatch gating.
    // -----------------------------------------------------------------------------------------

    @Test
    void damagedCallArgumentStopsFoldAndKeepsSibling() {
        // `foo(1+)` survives at the statement boundary (NEWLINE); the next line is unaffected.
        var result = recoverableParse("func f():\n\tfoo(1+)\nvar y = 1\n");
        var function = assertInstanceOf(FunctionDeclaration.class, result.ast().statements().getFirst());
        var statement = assertInstanceOf(ExpressionStatement.class, function.body().statements().getFirst());
        var call = assertInstanceOf(CallExpression.class, statement.expression());
        assertInstanceOf(BinaryExpression.class, call.arguments().getFirst());
        assertEquals("y", assertInstanceOf(VariableDeclaration.class,
                result.ast().statements().get(1)).name());
    }

    @Test
    void damagedSubscriptOrGroupingDoesNotFoldFollowingOperators() {
        // Settled subscript and grouping boundaries remain damaged but continue folding trailing
        // infix `+ 2`, retaining the full BinaryExpression statement.
        var subscript = recoverableParse("func f():\n\ta[1+]+2\n");
        assertEquals(List.of("Expected expression after \"+\" operator."), errorMessages(subscript),
                () -> errorMessages(subscript).toString());
        var subBody = onlyFunction(subscript).body().statements();
        assertEquals(1, subBody.size());
        var subBin = assertInstanceOf(BinaryExpression.class,
                assertInstanceOf(ExpressionStatement.class, subBody.getFirst()).expression());
        assertEquals("+", subBin.operator());
        assertInstanceOf(SubscriptExpression.class, subBin.left());
        assertEquals("2", assertInstanceOf(LiteralExpression.class, subBin.right()).sourceText());

        var grouped = recoverableParse("func f():\n\t(1+)+2\n");
        assertEquals(List.of("Expected expression after \"+\" operator."), errorMessages(grouped),
                () -> errorMessages(grouped).toString());
        var grpBody = onlyFunction(grouped).body().statements();
        assertEquals(1, grpBody.size());
        var grpBin = assertInstanceOf(BinaryExpression.class,
                assertInstanceOf(ExpressionStatement.class, grpBody.getFirst()).expression());
        assertEquals("+", grpBin.operator());
        assertInstanceOf(BinaryExpression.class, grpBin.left());
        assertEquals("2", assertInstanceOf(LiteralExpression.class, grpBin.right()).sourceText());
    }

    @Test
    void nestedFailuresKeepInnerMarkersWithoutBubbleUp() {
        // `foo(1+, [2+])`: the array absorbs its element damage; both inner `+` failures report
        // exactly once and the call survives at the boundary.
        var result = recoverableParse("func f():\n\tfoo(1+, [2+])\n");
        assertEquals(2, errorMessages(result).size(), () -> errorMessages(result).toString());
        var statement = assertInstanceOf(ExpressionStatement.class,
                onlyFunction(result).body().statements().getFirst());
        var call = assertInstanceOf(CallExpression.class, statement.expression());
        assertEquals(2, call.arguments().size());
        assertInstanceOf(BinaryExpression.class, call.arguments().getFirst());
        var array = assertInstanceOf(ArrayExpression.class, call.arguments().get(1));
        assertInstanceOf(BinaryExpression.class, array.elements().getFirst());

        // `foo([1+])`: the absorbing array host does not bubble damage into the call's fold.
        var absorbed = recoverableParse("func f():\n\tfoo([1+])\n");
        assertEquals(List.of("Expected expression after \"+\" operator."), errorMessages(absorbed),
                () -> errorMessages(absorbed).toString());
        var absorbedCall = assertInstanceOf(CallExpression.class,
                assertInstanceOf(ExpressionStatement.class,
                        onlyFunction(absorbed).body().statements().getFirst()).expression());
        assertInstanceOf(ArrayExpression.class, absorbedCall.arguments().getFirst());
    }

    @Test
    void damagedArgumentsStopTheSuffixChain() {
        // After damaged call arguments or subscript indices, the settled suffix chain continues
        // folding subsequent `(2)` / `[2]` suffixes and preserves both argument/index slots.
        var callResult = recoverableParse("func f():\n\tfoo(1+)(2)\n");
        assertEquals(List.of("Expected expression after \"+\" operator."), errorMessages(callResult),
                () -> errorMessages(callResult).toString());
        var callBody = onlyFunction(callResult).body().statements();
        assertEquals(1, callBody.size());
        var outerCall = assertInstanceOf(CallExpression.class,
                assertInstanceOf(ExpressionStatement.class, callBody.getFirst()).expression());
        assertEquals(List.of("2"), outerCall.arguments().stream()
                .map(arg -> assertInstanceOf(LiteralExpression.class, arg).sourceText()).toList());
        var innerCall = assertInstanceOf(CallExpression.class, outerCall.callee());
        assertInstanceOf(BinaryExpression.class, innerCall.arguments().getFirst());

        var subResult = recoverableParse("func f():\n\ta[1+][2]\n");
        assertEquals(List.of("Expected expression after \"+\" operator."), errorMessages(subResult),
                () -> errorMessages(subResult).toString());
        var subBody = onlyFunction(subResult).body().statements();
        assertEquals(1, subBody.size());
        var outerSub = assertInstanceOf(SubscriptExpression.class,
                assertInstanceOf(ExpressionStatement.class, subBody.getFirst()).expression());
        assertEquals("2", assertInstanceOf(LiteralExpression.class, outerSub.arguments().getFirst()).sourceText());
        var innerSub = assertInstanceOf(SubscriptExpression.class, outerSub.base());
        assertInstanceOf(BinaryExpression.class, innerSub.arguments().getFirst());
    }

    @Test
    void lambdaBoundariesIsolateDamage() {
        // `foo(1+, func(): pass)`: per-frame pendingSync keeps the damage inside the first
        // argument's frame without polluting `foo(...)`, so `)` settles cleanly with zero phantom
        // missing-closer diagnostics.
        var result = recoverableParse("func f():\n\tfoo(1+, func(): pass)\n");
        assertEquals(List.of("Expected expression after \"+\" operator."), errorMessages(result),
                () -> errorMessages(result).toString());
        var statement = assertInstanceOf(ExpressionStatement.class,
                onlyFunction(result).body().statements().getFirst());
        var call = assertInstanceOf(CallExpression.class, statement.expression());
        assertEquals(2, call.arguments().size());
        assertInstanceOf(BinaryExpression.class, call.arguments().getFirst());
        assertInstanceOf(LambdaExpression.class, call.arguments().get(1));

        // `var x = func(a = 1+): pass + 2`: damage inside the lambda signature stays isolated to
        // its parameter list, so the trailing `+ 2` after the lambda's single-line body is still
        // diagnosed in the enclosing class body rather than masked by global panic state.
        var lambdaDefault = recoverableParse("var x = func(a = 1+): pass + 2\n");
        assertEquals(List.of("Expected expression after \"+\" operator.", "Unexpected \"+\" in class body."),
                errorMessages(lambdaDefault), () -> errorMessages(lambdaDefault).toString());
        var variable = assertInstanceOf(VariableDeclaration.class,
                lambdaDefault.ast().statements().getFirst());
        assertInstanceOf(LambdaExpression.class, variable.value());
    }

    @Test
    void superCallWithDamagedArgumentStopsFold() {
        // `super(1+)+2`: the damaged argument list still settles `)` so `+ 2` folds onto the
        // CallExpression(SuperExpression, ...) and the statement survives.
        var result = recoverableParse("func f():\n\tsuper(1+)+2\n");
        assertEquals(List.of("Expected expression after \"+\" operator."), errorMessages(result),
                () -> errorMessages(result).toString());
        var body = onlyFunction(result).body().statements();
        assertEquals(1, body.size());
        var binary = assertInstanceOf(BinaryExpression.class,
                assertInstanceOf(ExpressionStatement.class, body.getFirst()).expression());
        assertEquals("+", binary.operator());
        var call = assertInstanceOf(CallExpression.class, binary.left());
        assertEquals("super", assertInstanceOf(IdentifierExpression.class, call.callee()).name());
        assertInstanceOf(BinaryExpression.class, call.arguments().getFirst());
        assertEquals("2", assertInstanceOf(LiteralExpression.class, binary.right()).sourceText());
    }

    @Test
    void groupingPureMissingDoesNotConsumeCloser() {
        // `var x = ()`: empty grouping settles its own `)` cleanly with only the content error,
        // `foo((), 1)` preserves the second argument, `(1+)` settles its own `)`, and `var x = (\n`
        // keeps a single primary diagnostic without emitting a duplicate missing-closer error.
        var result = recoverableParse("func f():\n\tvar x = ()\n");
        assertEquals(List.of("Expected grouping expression."), errorMessages(result),
                () -> errorMessages(result).toString());
        var body = onlyFunction(result).body().statements();
        var marker = assertInstanceOf(ErrorExpression.class,
                assertInstanceOf(VariableDeclaration.class, body.getFirst()).value());
        assertEquals("grouping", marker.nodeType());

        var callResult = recoverableParse("func f():\n\tfoo((), 1)\n");
        assertEquals(List.of("Expected grouping expression."), errorMessages(callResult),
                () -> errorMessages(callResult).toString());
        var call = assertInstanceOf(CallExpression.class,
                assertInstanceOf(ExpressionStatement.class, onlyFunction(callResult).body().statements().getFirst())
                        .expression());
        assertEquals(2, call.arguments().size());
        assertInstanceOf(ErrorExpression.class, call.arguments().getFirst());
        assertEquals("1", assertInstanceOf(LiteralExpression.class, call.arguments().get(1)).sourceText());

        var damagedGrouping = recoverableParse("var x = (1+)\n");
        assertEquals(List.of("Expected expression after \"+\" operator."), errorMessages(damagedGrouping),
                () -> errorMessages(damagedGrouping).toString());
        assertInstanceOf(BinaryExpression.class,
                assertInstanceOf(VariableDeclaration.class, damagedGrouping.ast().statements().getFirst()).value());

        var open = recoverableParse("var x = (\n");
        assertEquals(List.of("Expected grouping expression."), errorMessages(open),
                () -> errorMessages(open).toString());
        assertInstanceOf(ErrorExpression.class,
                assertInstanceOf(VariableDeclaration.class, open.ast().statements().getFirst()).value());
    }

    @Test
    void nestedAssignmentDegradationKeepsSingleDiagnostic() {
        // `var x = a = 1`: the inner `=` is rejected; the right operand (`1`) survives as the
        // initializer (vendor's degraded rescue — no AssignmentExpression is built).
        var result = recoverableParse("var x = a = 1\n");
        assertEquals(List.of("Assignment is not allowed inside an expression."), errorMessages(result),
                () -> errorMessages(result).toString());
        var variable = assertInstanceOf(VariableDeclaration.class, result.ast().statements().getFirst());
        assertEquals("1", assertInstanceOf(LiteralExpression.class, variable.value()).sourceText());

        // `return a =`: the rejected assignment's missing right side must not double-report —
        // exactly one diagnostic, and the return keeps an ERROR fragment over `a =`.
        var returnResult = recoverableParse("func f():\n\treturn a =\n");
        assertEquals(List.of("Assignment is not allowed inside an expression."),
                errorMessages(returnResult), () -> errorMessages(returnResult).toString());
        var returnStatement = assertInstanceOf(ReturnStatement.class,
                onlyFunction(returnResult).body().statements().getFirst());
        var fragment = assertInstanceOf(ErrorExpression.class, returnStatement.value());
        assertEquals(CstIssueKind.ERROR, fragment.kind());
        assertEquals("a =", fragment.sourceText());
    }

    // -----------------------------------------------------------------------------------------
    // T21: serde round-trip over the new recovery shapes.
    // -----------------------------------------------------------------------------------------

    @Test
    void damagedShapesSurviveSerdeRoundTrip() {
        var serializer = new AstSexprSerializer();
        var deserializer = new AstSexprDeserializer();
        var corpus = List.of(
                // Damaged call arguments inside a surviving call.
                "var x = target(1+\n",
                // Chain with a missing-member step.
                "var x = self.b.(1)\n",
                // Damaged ternary branches.
                "var x = 1 if + else 2\n",
                // Dictionary-pattern ERROR fallback fragment.
                "func f():\n\tmatch x:\n\t\t{1+}: pass\n"
        );
        for (var source : corpus) {
            var ast = parse(source).ast();
            var restored = deserializer.deserialize(serializer.serialize(ast));
            assertEquals(ast, restored, () -> "serde round-trip mismatch for: " + source);
        }
    }

    @Test
    void missingSlotDiagnosticsBindToTheirOwningNodesAndFacets() {
        var varResult = recoverableParse("var x =\n");
        var varDiag = findErrorByCode(varResult, DiagnosticCodes.SLOT_VARIABLE_INITIALIZER);
        var varNode = assertInstanceOf(VariableDeclaration.class, varResult.ast().statements().getFirst());
        assertNotNull(varDiag.recovery());
        assertSame(varNode, varDiag.recovery().owner());
        assertEquals("variable.initializer", varDiag.recovery().facet());

        var constResult = recoverableParse("const X =\n");
        var constDiag = findErrorByCode(constResult, DiagnosticCodes.SLOT_CONSTANT_VALUE);
        var constNode = assertInstanceOf(VariableDeclaration.class, constResult.ast().statements().getFirst());
        assertNotNull(constDiag.recovery());
        assertSame(constNode, constDiag.recovery().owner());
        assertEquals("constant.value", constDiag.recovery().facet());

        var paramResult = recoverableParse("func f(a =):\n\tpass\n");
        var paramDiag = findErrorByCode(paramResult, DiagnosticCodes.SLOT_PARAMETER_DEFAULT);
        var paramNode = onlyFunction(paramResult).parameters().getFirst();
        assertNotNull(paramDiag.recovery());
        assertSame(paramNode, paramDiag.recovery().owner());
        assertEquals("parameter.default", paramDiag.recovery().facet());

        var enumResult = recoverableParse("enum E { A =\n");
        var enumDiag = findErrorByCode(enumResult, DiagnosticCodes.SLOT_ENUM_VALUE);
        var enumNode = assertInstanceOf(EnumDeclaration.class, enumResult.ast().statements().getFirst());
        assertNotNull(enumDiag.recovery());
        assertSame(enumNode.members().getFirst(), enumDiag.recovery().owner());
        assertEquals("enum.value", enumDiag.recovery().facet());

        var ifResult = recoverableParse("func f():\n\tif:\n\t\tpass\n");
        var ifDiag = findErrorByCode(ifResult, DiagnosticCodes.SLOT_IF_CONDITION);
        var ifNode = assertInstanceOf(IfStatement.class, onlyFunction(ifResult).body().statements().getFirst());
        assertNotNull(ifDiag.recovery());
        assertSame(ifNode, ifDiag.recovery().owner());
        assertEquals("if.condition", ifDiag.recovery().facet());

        var elifResult = recoverableParse("func f():\n\tif true:\n\t\tpass\n\telif:\n\t\tpass\n");
        var elifDiag = findErrorByCode(elifResult, DiagnosticCodes.SLOT_ELIF_CONDITION);
        var outerIf = assertInstanceOf(IfStatement.class, onlyFunction(elifResult).body().statements().getFirst());
        assertNotNull(elifDiag.recovery());
        assertSame(outerIf.elifClauses().getFirst(), elifDiag.recovery().owner());
        assertEquals("elif.condition", elifDiag.recovery().facet());

        var whileResult = recoverableParse("func f():\n\twhile:\n\t\tpass\n");
        var whileDiag = findErrorByCode(whileResult, DiagnosticCodes.SLOT_WHILE_CONDITION);
        var whileNode = assertInstanceOf(WhileStatement.class, onlyFunction(whileResult).body().statements().getFirst());
        assertNotNull(whileDiag.recovery());
        assertSame(whileNode, whileDiag.recovery().owner());
        assertEquals("while.condition", whileDiag.recovery().facet());

        var forResult = recoverableParse("func f():\n\tfor i in:\n\t\tpass\n");
        var forDiag = findErrorByCode(forResult, DiagnosticCodes.SLOT_FOR_ITERABLE);
        var forNode = assertInstanceOf(ForStatement.class, onlyFunction(forResult).body().statements().getFirst());
        assertNotNull(forDiag.recovery());
        assertSame(forNode, forDiag.recovery().owner());
        assertEquals("for.iterable", forDiag.recovery().facet());

        var assertCondResult = recoverableParse("func f():\n\tassert(\n");
        var assertCondDiag = findErrorByCode(assertCondResult, DiagnosticCodes.SLOT_ASSERT_CONDITION);
        var assertCondNode = assertInstanceOf(AssertStatement.class, onlyFunction(assertCondResult).body().statements().getFirst());
        assertNotNull(assertCondDiag.recovery());
        assertSame(assertCondNode, assertCondDiag.recovery().owner());
        assertEquals("assert.condition", assertCondDiag.recovery().facet());

        var assertMsgResult = recoverableParse("func f():\n\tassert(true,\n");
        var assertMsgDiag = findErrorByCode(assertMsgResult, DiagnosticCodes.SLOT_ASSERT_MESSAGE);
        var assertMsgNode = assertInstanceOf(AssertStatement.class, onlyFunction(assertMsgResult).body().statements().getFirst());
        assertNotNull(assertMsgDiag.recovery());
        assertSame(assertMsgNode, assertMsgDiag.recovery().owner());
        assertEquals("assert.message", assertMsgDiag.recovery().facet());

        var matchValResult = recoverableParse("func f():\n\tmatch:\n\t\t1:\n\t\t\tpass\n");
        var matchValDiag = findErrorByCode(matchValResult, DiagnosticCodes.SLOT_MATCH_VALUE);
        var matchNode = assertInstanceOf(MatchStatement.class, onlyFunction(matchValResult).body().statements().getFirst());
        assertNotNull(matchValDiag.recovery());
        assertSame(matchNode, matchValDiag.recovery().owner());
        assertEquals("match.value", matchValDiag.recovery().facet());

        var guardResult = recoverableParse("func f():\n\tmatch x:\n\t\t1 when:\n\t\t\tpass\n");
        var guardDiag = findErrorByCode(guardResult, DiagnosticCodes.SLOT_MATCH_GUARD);
        var guardMatch = assertInstanceOf(MatchStatement.class, onlyFunction(guardResult).body().statements().getFirst());
        assertNotNull(guardDiag.recovery());
        assertSame(guardMatch.sections().getFirst(), guardDiag.recovery().owner());
        assertEquals("match.guard", guardDiag.recovery().facet());

        var groupingResult = recoverableParse("var g = ()\n");
        var groupingDiag = findErrorByCode(groupingResult, DiagnosticCodes.SLOT_GROUPING);
        var groupingNode = assertInstanceOf(VariableDeclaration.class, groupingResult.ast().statements().getFirst());
        assertNotNull(groupingDiag.recovery());
        assertSame(groupingNode, groupingDiag.recovery().owner());
        assertEquals("variable.initializer", groupingDiag.recovery().facet());
    }

    @Test
    void unclosedContainersStopAtOuterDeclarationsAndStatementBoundaries() {
        var arrayEscape = recoverableParse("""
                var a = [1,
                # comment with ] } )
                var s = "string with ] and if x:"
                var r = r"raw \\"]"
                enum E { A, B }
                func next_fn():
                \tpass
                """);
        var declStatements = arrayEscape.ast().statements().stream()
                .filter(s -> !(s instanceof CommentStatement))
                .toList();
        assertEquals(5, declStatements.size(), () -> declStatements.toString());
        assertEquals("a", assertInstanceOf(VariableDeclaration.class, declStatements.getFirst()).name());
        assertEquals("s", assertInstanceOf(VariableDeclaration.class, declStatements.get(1)).name());
        assertEquals("r", assertInstanceOf(VariableDeclaration.class, declStatements.get(2)).name());
        assertEquals("E", assertInstanceOf(EnumDeclaration.class, declStatements.get(3)).name());
        assertEquals("next_fn", assertInstanceOf(FunctionDeclaration.class, declStatements.get(4)).name());
        findErrorByCode(arrayEscape, DiagnosticCodes.MISSING_CLOSER_ARRAY);

        var dictAndAnnotEscape = recoverableParse("""
                var d = {
                \t"k": 1,
                @export
                var exported = 2
                """);
        assertEquals(List.of("d", "AnnotationStatement", "exported"), topLevelMemberNames(dictAndAnnotEscape));
        assertInstanceOf(AnnotationStatement.class, dictAndAnnotEscape.ast().statements().get(1));
        var exportedVar = assertInstanceOf(VariableDeclaration.class, dictAndAnnotEscape.ast().statements().get(2));
        assertEquals("exported", exportedVar.name());
        findErrorByCode(dictAndAnnotEscape, DiagnosticCodes.MISSING_CLOSER_DICTIONARY);

        // Statement-head `if cond:` must stop an unclosed grouping, whereas a ternary inside a
        // valid multiline expression must NOT stop early.
        var ifHeadEscape = recoverableParse("""
                func f(cond):
                \tvar x = (1 +
                \tif cond:
                \t\treturn 1
                var after = 2
                """);
        assertEquals(List.of("f", "after"), topLevelMemberNames(ifHeadEscape));
        var ifFn = assertInstanceOf(FunctionDeclaration.class, ifHeadEscape.ast().statements().getFirst());
        var fnBody = ifFn.body().statements();
        assertEquals(2, fnBody.size(), () -> fnBody.toString());
        assertInstanceOf(VariableDeclaration.class, fnBody.getFirst());
        assertInstanceOf(IfStatement.class, fnBody.get(1));
        findErrorByCode(ifHeadEscape, DiagnosticCodes.MISSING_CLOSER_GROUPING);

        var validTernaryAndLambda = parse("""
                var x = (1 if
                \ttrue else 2)
                var y = foo(
                \tfunc():
                \t\treturn 1,
                \t2
                )
                """);
        assertEquals(List.of(), errorMessages(validTernaryAndLambda),
                () -> errorMessages(validTernaryAndLambda).toString());
        assertEquals(List.of("x", "y"), topLevelMemberNames(validTernaryAndLambda));
    }

    @Test
    void nestedUnclosedPrintAndCloserReplacementFollowOwnershipRules() {
        // In `print(1\n\tprint(2)`, the single `)` belongs strictly to inner `print(2)` while outer
        // `print(1` reports missing `)` and yields to `var after = 2`.
        var nestedPrint = recoverableParse("""
                func f():
                \tprint(1
                \tprint(2)
                var after = 2
                """);
        assertEquals(List.of("f", "after"), topLevelMemberNames(nestedPrint));
        var outerMissing = findErrorByCode(nestedPrint, DiagnosticCodes.MISSING_CLOSER_CALL);
        assertEquals("Expected closing \")\" after call arguments.", outerMissing.message());
        var printFn = assertInstanceOf(FunctionDeclaration.class, nestedPrint.ast().statements().getFirst());
        assertEquals(2, printFn.body().statements().size());

        // Ancestor closer takes precedence over a single-layer mismatch: `foo([1)` closes `foo(...)`
        // and reports missing `]` on `[1`.
        var ancestorOwned = recoverableParse("var x = foo([1)\n");
        findErrorByCode(ancestorOwned, DiagnosticCodes.MISSING_CLOSER_ARRAY);
        assertTrue(errorsOf(ancestorOwned).stream()
                .noneMatch(d -> DiagnosticCodes.MISMATCHED_CLOSER_ARRAY.equals(d.code())));
        var call = assertInstanceOf(CallExpression.class,
                assertInstanceOf(VariableDeclaration.class, ancestorOwned.ast().statements().getFirst()).value());
        assertEquals(1, call.arguments().size());
        assertInstanceOf(ArrayExpression.class, call.arguments().getFirst());

        // Unowned closer triggers single-layer replacement: `[1)` reports `MISMATCHED_CLOSER_ARRAY`.
        var singleReplacement = recoverableParse("var x = [1)\n");
        var mismatchDiag = findErrorByCode(singleReplacement, DiagnosticCodes.MISMATCHED_CLOSER_ARRAY);
        assertEquals("Expected \"]\" to close array elements, found \")\" instead.", mismatchDiag.message());
    }

    @Test
    void bidirectionalLayoutReplayHandlesIndentDedentCommentsCrlfBackslashAndLambdas() {
        // Header escape where parameter list omits `)` before `:\n\tpass` recovers body & sibling.
        var headerEscape = recoverableParse("""
                func f(a, b = 1:
                \tpass
                var after = 2
                """);
        assertEquals(List.of("f", "after"), topLevelMemberNames(headerEscape));
        var fDecl = assertInstanceOf(FunctionDeclaration.class, headerEscape.ast().statements().getFirst());
        assertEquals(2, fDecl.parameters().size());
        assertEquals(1, fDecl.body().statements().size());
        assertInstanceOf(PassStatement.class, fDecl.body().statements().getFirst());
        findErrorByCode(headerEscape, DiagnosticCodes.MISSING_CLOSER_PARAMETER_LIST);

        // Unclosed grouping directly before an indented `pass` line triggers `NEWLINE + INDENT`
        // layout replay on escape so `pass` still enters a clean block/statement sequence.
        var replayIndent = recoverableParse("""
                func f():
                \tvar x = (1 +
                \t\tpass
                var after = 2
                """);
        assertEquals(List.of("f", "after"), topLevelMemberNames(replayIndent));
        findErrorByCode(replayIndent, DiagnosticCodes.MISSING_CLOSER_GROUPING);

        // CRLF + comment-only lines inside escaped grouping replay `NEWLINE + DEDENT` cleanly.
        var crlfAndComments = recoverableParse("func f():\r\n\tvar x = (1 +\r\n\t# inside\r\n\t2\r\n# outer\r\nvar after = 2\r\n");
        assertEquals(List.of("f", "CommentStatement", "after"), topLevelMemberNames(crlfAndComments));
        findErrorByCode(crlfAndComments, DiagnosticCodes.MISSING_CLOSER_GROUPING);

        // Explicit backslash continuation across lines stays part of the expression rather than
        // triggering a premature line-start escape.
        var backslashValid = parse("var x = (1 + \\\n\t2)\n");
        assertEquals(List.of(), errorMessages(backslashValid),
                () -> errorMessages(backslashValid).toString());

        // Nested multiline lambda snapshot inside an outer escaped call still restores `indentStackStack`.
        var lambdaInsideCall = recoverableParse("""
                func f():
                \tvar x = g(
                \t\tfunc():
                \t\t\treturn 1
                \tvar local_after = 2
                var after = 3
                """);
        assertEquals(List.of("f", "after"), topLevelMemberNames(lambdaInsideCall));
        var lambdaFn = assertInstanceOf(FunctionDeclaration.class, lambdaInsideCall.ast().statements().getFirst());
        assertEquals(2, lambdaFn.body().statements().size());
    }

    @Test
    void typeBracketBoundaryIsolatesOuterCommaAndBodylessLambda() {
        var multiline = recoverableParse("""
                var x = g(func() -> Array[
                ,
                \tnode.
                \tpa
                )
                """);
        var multilineCall = assertInstanceOf(CallExpression.class,
                assertInstanceOf(VariableDeclaration.class, multiline.ast().statements().getFirst()).value());
        assertEquals(2, multilineCall.arguments().size());
        var bodylessLambda = assertInstanceOf(LambdaExpression.class, multilineCall.arguments().getFirst());
        assertTrue(bodylessLambda.body().statements().isEmpty());
        assertInstanceOf(AttributeExpression.class, multilineCall.arguments().get(1));

        var validDictType = parse("var d: Dictionary[String, int] = {}\n");
        assertEquals(List.of(), errorMessages(validDictType), () -> errorMessages(validDictType).toString());
    }

    @Test
    void depthTripUnclosedContainersAndTypeBracketsTerminateWithoutCascade() {
        var unclosedMatch = recoverableParse("func f():\n\tmatch x:\n\t\t[\n\t\t\t"
                + "[".repeat(254) + "1\n\t\t_: return 7\n\tvar local_after = 7\nvar after = 2\n");
        findErrorByCode(unclosedMatch, DiagnosticCodes.NESTING_DEPTH_EXCEEDED);
        assertEquals(0, errorsOf(unclosedMatch).stream()
                .filter(d -> DiagnosticCodes.MISSING_CLOSER_MATCH_ARRAY_PATTERN.equals(d.code()))
                .count());
        assertEquals(List.of("f", "after"), topLevelMemberNames(unclosedMatch));
        var fn = assertInstanceOf(FunctionDeclaration.class, unclosedMatch.ast().statements().getFirst());
        var fnStatements = fn.body().statements();
        assertEquals(2, fnStatements.size());
        var match = assertInstanceOf(MatchStatement.class, fnStatements.getFirst());
        assertEquals(2, match.sections().size());
        assertInstanceOf(ReturnStatement.class, match.sections().get(1).body().statements().getFirst());
        assertEquals("local_after", assertInstanceOf(VariableDeclaration.class, fnStatements.get(1)).name());

        var deepType = recoverableParse("var x: " + "Array[".repeat(260) + "int" + "]".repeat(260)
                + "\nvar after = 2\n");
        findErrorByCode(deepType, DiagnosticCodes.NESTING_DEPTH_EXCEEDED);
        assertEquals(List.of("x", "after"), topLevelMemberNames(deepType));
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void lazyBracketOracleCapsScansAtOneAndParserReuseMaintainsI4StackBalance() {
        var parser = new GdScriptParser();

        // Healthy parse never invokes the bracket oracle (`scanCount == 0`).
        var healthy = parser.parse("var x = (1 +\n\t2)\n");
        assertEquals(List.of(), errorMessages(healthy));
        assertEquals(0, parser.bracketOracleScanCount());

        // Large 250-line unclosed multiline container scans the source at most once (`I2`).
        var longBody = new StringBuilder("func f():\n\tvar arr = [\n");
        for (var i = 0; i < 240; i++) {
            longBody.append("\t\t").append(i).append(",\n");
        }
        longBody.append("var after = 2\n");
        var largeResult = parser.parse(longBody.toString());
        assertTrue(parser.bracketOracleScanCount() <= 1,
                () -> "expected <= 1 oracle scan, got " + parser.bracketOracleScanCount());
        assertEquals(List.of("f", "after"), topLevelMemberNames(largeResult));

        // Reusing the same parser across EOF cuts, mismatched closers, depth trips, and lambda
        // escapes must always restore all parser and tokenizer stacks (`I4`).
        for (var input : List.of(
                "var eof = (\n",
                "var mismatch = [1)\n",
                "var deep = " + "(".repeat(280) + "1\nvar ok = 1\n",
                "var lam = g(func(): super., 1)\n",
                "var clean = 42\n"
        )) {
            var res = parser.parse(input);
            assertNotNull(res.ast());
        }
        var finalClean = parser.parse("var final_ok = 1\n");
        assertEquals(List.of(), errorMessages(finalClean));
        assertEquals(List.of("final_ok"), topLevelMemberNames(finalClean));
    }

    @Test
    void truncatedLambdasInsideIndentedSuitesRecoverWithoutCrashingAtEof() {
        var parser = new GdScriptParser();
        for (var source : List.of(
                "func f():\n\tvar x = g(func(): pass",
                "func f():\n\tvar x = g(func():\n\t\tpass",
                "func f():\n\tvar x = g(func()",
                "func f():\n\tvar x = g(func():\n\t\tvar y = h(func(): pass"
        )) {
            var result = parser.parse(source);
            var fn = onlyFunction(result);
            assertEquals("f", fn.name());
            var x = assertInstanceOf(VariableDeclaration.class, fn.body().statements().getFirst());
            assertEquals("x", x.name());
            assertInstanceOf(CallExpression.class, x.value());
            assertFalse(errorsOf(result).isEmpty());
        }
        // Reusing the same parser immediately afterward confirms clean layout/stack restoration.
        var clean = parser.parse("var after_eof = 1\n");
        assertEquals(List.of(), errorMessages(clean));
        assertEquals(List.of("after_eof"), topLevelMemberNames(clean));
    }

    @Test
    void depthTripInsidePairedMultilineContainersAcceptsEqualAndDeeperContinuations() {
        var equalIndent = recoverableParse("func f():\n\tvar x = ["
                + "(".repeat(260) + "\n\t1\n\t" + ")".repeat(260) + "]\n");
        assertEquals(List.of(DiagnosticCodes.NESTING_DEPTH_EXCEEDED),
                errorsOf(equalIndent).stream().map(AstDiagnostic::code).toList());
        assertEquals("f", onlyFunction(equalIndent).name());

        var deeperIndent = recoverableParse("func f():\n\tvar x = ["
                + "(".repeat(260) + "\n\t\t1\n\t\t" + ")".repeat(260) + "]\n");
        assertEquals(List.of(DiagnosticCodes.NESTING_DEPTH_EXCEEDED),
                errorsOf(deeperIndent).stream().map(AstDiagnostic::code).toList());

        var inMatch = recoverableParse("func f():\n\tmatch x:\n\t\t["
                + "(".repeat(260) + "\n\t\t1\n\t\t" + ")".repeat(260)
                + "]:\n\t\t\tpass\n\t\t_: return 7\n\tvar local_after = 7\nvar after = 2\n");
        findErrorByCode(inMatch, DiagnosticCodes.NESTING_DEPTH_EXCEEDED);
        assertEquals(List.of("f", "after"), topLevelMemberNames(inMatch));
        var fn = assertInstanceOf(FunctionDeclaration.class, inMatch.ast().statements().getFirst());
        var match = assertInstanceOf(MatchStatement.class, fn.body().statements().getFirst());
        assertEquals(2, match.sections().size());
        assertInstanceOf(ReturnStatement.class, match.sections().get(1).body().statements().getFirst());
        assertEquals("local_after", assertInstanceOf(VariableDeclaration.class, fn.body().statements().get(1)).name());

        var unclosed = recoverableParse("func f():\n\tvar x = ["
                + "(".repeat(260) + "\n\t1\n\tvar y = 2\nvar after = 3\n");
        findErrorByCode(unclosed, DiagnosticCodes.NESTING_DEPTH_EXCEEDED);
        assertEquals(List.of("f", "after"), topLevelMemberNames(unclosed));
        var unclosedFn = assertInstanceOf(FunctionDeclaration.class, unclosed.ast().statements().getFirst());
        assertEquals(List.of("x", "y"), unclosedFn.body().statements().stream()
                .filter(VariableDeclaration.class::isInstance)
                .map(s -> ((VariableDeclaration) s).name())
                .toList());

        var shallowerContinuation = recoverableParse("func f():\n\tmatch x:\n\t\t"
                + "[".repeat(260) + "\n\t1\n\t\t" + "]".repeat(260) + ": pass\n"
                + "\t\t_:\n\t\t\tpass\n\tvar local_after = 1\nvar after = 2\n");
        assertEquals(List.of(DiagnosticCodes.NESTING_DEPTH_EXCEEDED),
                errorsOf(shallowerContinuation).stream().map(AstDiagnostic::code).toList());
        assertEquals(List.of("f", "after"), topLevelMemberNames(shallowerContinuation));
        var shallowFn = assertInstanceOf(FunctionDeclaration.class, shallowerContinuation.ast().statements().getFirst());
        var shallowMatch = assertInstanceOf(MatchStatement.class, shallowFn.body().statements().getFirst());
        assertEquals(2, shallowMatch.sections().size());
        assertEquals("local_after", assertInstanceOf(VariableDeclaration.class, shallowFn.body().statements().get(1)).name());

        var shallowerControl = parse("""
                func f():
                \tmatch x:
                \t\t[[
                \t1
                \t\t]]: pass
                \t\t_:
                \t\t\tpass
                \tvar local_after = 1
                var after = 2
                """);
        assertEquals(List.of("f", "after"), topLevelMemberNames(shallowerControl));
        var shallowCtrlFn = assertInstanceOf(FunctionDeclaration.class, shallowerControl.ast().statements().getFirst());
        var shallowCtrlMatch = assertInstanceOf(MatchStatement.class, shallowCtrlFn.body().statements().getFirst());
        assertEquals(2, shallowCtrlMatch.sections().size());
        assertEquals("local_after", assertInstanceOf(VariableDeclaration.class, shallowCtrlFn.body().statements().get(1)).name());
    }

    @Test
    void suffixChainYieldsAtUnclosedMultilineEscapesAndPreservesMatchedControls() {
        var missingNameEscape = recoverableParse("""
                func f():
                \tvar x = (a.
                \tvar y = 2
                var after = 3
                """);
        findErrorByCode(missingNameEscape, DiagnosticCodes.ATTRIBUTE_MISSING_NAME);
        findErrorByCode(missingNameEscape, DiagnosticCodes.MISSING_CLOSER_GROUPING);
        assertEquals(List.of("f", "after"), topLevelMemberNames(missingNameEscape));
        var fn1 = assertInstanceOf(FunctionDeclaration.class, missingNameEscape.ast().statements().getFirst());
        var x1 = assertInstanceOf(VariableDeclaration.class, fn1.body().statements().getFirst());
        var y1 = assertInstanceOf(VariableDeclaration.class, fn1.body().statements().get(1));
        assertEquals("y", y1.name());
        var attr1 = assertInstanceOf(AttributeExpression.class, x1.value());
        assertInstanceOf(MissingAttributeStep.class, attr1.steps().getFirst());

        var matchedNameControl = parse("""
                func f():
                \tvar x = (a.
                \tfoo)
                """);
        var xMatched = assertInstanceOf(VariableDeclaration.class,
                onlyFunction(matchedNameControl).body().statements().getFirst());
        var attrMatched = assertInstanceOf(AttributeExpression.class, xMatched.value());
        var prop = assertInstanceOf(AttributePropertyStep.class, attrMatched.steps().getFirst());
        assertEquals("foo", prop.name());

        var subscriptEscapeTop = recoverableParse("""
                func f():
                \tvar x = (foo.bar
                [2]
                """);
        findErrorByCode(subscriptEscapeTop, DiagnosticCodes.MISSING_CLOSER_GROUPING);
        var fn2 = onlyFunction(subscriptEscapeTop);
        var x2 = assertInstanceOf(VariableDeclaration.class, fn2.body().statements().getFirst());
        var attr2 = assertInstanceOf(AttributeExpression.class, x2.value());
        assertEquals(1, attr2.steps().size());
        assertEquals("bar", assertInstanceOf(AttributePropertyStep.class, attr2.steps().getFirst()).name());

        var subscriptEscapeInSuite = recoverableParse("""
                func outer():
                \tif true:
                \t\tvar x = (foo.bar
                \t[2]
                """);
        findErrorByCode(subscriptEscapeInSuite, DiagnosticCodes.MISSING_CLOSER_GROUPING);
        var outerFn = onlyFunction(subscriptEscapeInSuite);
        assertEquals(2, outerFn.body().statements().size());
        assertInstanceOf(IfStatement.class, outerFn.body().statements().getFirst());
        var escapedArrayStmt = assertInstanceOf(ExpressionStatement.class, outerFn.body().statements().get(1));
        assertInstanceOf(ArrayExpression.class, escapedArrayStmt.expression());

        var matchedSubscriptControl = parse("""
                func f():
                \tvar x = (foo.bar
                [2])
                """);
        var xSub = assertInstanceOf(VariableDeclaration.class,
                onlyFunction(matchedSubscriptControl).body().statements().getFirst());
        var attrSub = assertInstanceOf(AttributeExpression.class, xSub.value());
        var subStep = assertInstanceOf(AttributeSubscriptStep.class, attrSub.steps().getFirst());
        assertEquals("bar", subStep.name());
        assertEquals(1, subStep.arguments().size());
    }

    @Test
    void unclosedMatchPatternsDoNotStealOuterDeclarationsWhileClosedPatternsKeepBindings() {
        var unclosedArray = recoverableParse("""
                func f():
                \tmatch x:
                \t\t[1,
                \tvar local_after = 7
                var after = 2
                """);
        findErrorByCode(unclosedArray, DiagnosticCodes.MISSING_CLOSER_MATCH_ARRAY_PATTERN);
        assertEquals(List.of("f", "after"), topLevelMemberNames(unclosedArray));
        var fnArr = assertInstanceOf(FunctionDeclaration.class, unclosedArray.ast().statements().getFirst());
        assertInstanceOf(MatchStatement.class, fnArr.body().statements().getFirst());
        assertEquals("local_after", assertInstanceOf(VariableDeclaration.class, fnArr.body().statements().get(1)).name());

        var unclosedDict = recoverableParse("""
                func f():
                \tmatch x:
                \t\t{"a": 1,
                \tvar local_after = 7
                var after = 2
                """);
        findErrorByCode(unclosedDict, DiagnosticCodes.MISSING_CLOSER_MATCH_DICTIONARY_PATTERN);
        assertEquals(List.of("f", "after"), topLevelMemberNames(unclosedDict));
        var fnDict = assertInstanceOf(FunctionDeclaration.class, unclosedDict.ast().statements().getFirst());
        assertInstanceOf(MatchStatement.class, fnDict.body().statements().getFirst());
        assertEquals("local_after", assertInstanceOf(VariableDeclaration.class, fnDict.body().statements().get(1)).name());

        var closedArrayBinding = parse("""
                func f():
                \tmatch x:
                \t\t[
                \t\t\tvar captured,
                \t\t\t1
                \t\t]:
                \t\t\tpass
                """);
        var closedArrMatch = assertInstanceOf(MatchStatement.class,
                onlyFunction(closedArrayBinding).body().statements().getFirst());
        var closedArrPat = assertInstanceOf(ArrayExpression.class,
                closedArrMatch.sections().getFirst().patterns().getFirst());
        assertEquals("captured", assertInstanceOf(PatternBindingExpression.class,
                closedArrPat.elements().getFirst()).name());

        var closedDictBinding = parse("""
                func f():
                \tmatch x:
                \t\t{
                \t\t\t"k": var captured,
                \t\t}:
                \t\t\tpass
                """);
        var closedDictMatch = assertInstanceOf(MatchStatement.class,
                onlyFunction(closedDictBinding).body().statements().getFirst());
        var closedDictPat = assertInstanceOf(DictionaryExpression.class,
                closedDictMatch.sections().getFirst().patterns().getFirst());
        assertEquals("captured", assertInstanceOf(PatternBindingExpression.class,
                closedDictPat.entries().getFirst().value()).name());
    }

    @Test
    void damagedDictionaryTypeLeavesEnclosingCallCommaForOuterArguments() {
        var damaged = recoverableParse("var x = g(func() -> Dictionary[String, node.pa)\n");
        findErrorByCode(damaged, DiagnosticCodes.MISSING_CLOSER_TYPE_BRACKET);
        var x = assertInstanceOf(VariableDeclaration.class, damaged.ast().statements().getFirst());
        var call = assertInstanceOf(CallExpression.class, x.value());
        assertEquals(2, call.arguments().size());
        assertInstanceOf(LambdaExpression.class, call.arguments().getFirst());
        var secondArg = assertInstanceOf(AttributeExpression.class, call.arguments().get(1));
        assertEquals("pa", assertInstanceOf(AttributePropertyStep.class, secondArg.steps().getFirst()).name());

        var valid = parse("var x = g(func() -> Dictionary[String, int]: pass, node.pa)\n");
        var validX = assertInstanceOf(VariableDeclaration.class, valid.ast().statements().getFirst());
        var validCall = assertInstanceOf(CallExpression.class, validX.value());
        assertEquals(2, validCall.arguments().size());
        var lambda = assertInstanceOf(LambdaExpression.class, validCall.arguments().getFirst());
        var returnType = assertInstanceOf(TypeRef.class, lambda.returnType());
        assertEquals("Dictionary[String, int]", returnType.sourceText());
        assertInstanceOf(AttributeExpression.class, validCall.arguments().get(1));
    }

    @Test
    void inlineLambdaDefersEnclosingCallReplacementCloserWithoutOrphanDiagnostic() {
        var mismatched = recoverableParse("var x = g(func(): pass]\n");
        var codes = errorsOf(mismatched).stream().map(AstDiagnostic::code).toList();
        assertEquals(List.of(DiagnosticCodes.MISMATCHED_CLOSER_CALL), codes);

        var damagedInline = recoverableParse("var x = g(func(): super.]\n");
        assertEquals(List.of(DiagnosticCodes.SUPER_MISSING_NAME, DiagnosticCodes.MISMATCHED_CLOSER_CALL),
                errorsOf(damagedInline).stream().map(AstDiagnostic::code).toList());
        var damagedX = assertInstanceOf(VariableDeclaration.class, damagedInline.ast().statements().getFirst());
        var damagedCall = assertInstanceOf(CallExpression.class, damagedX.value());
        assertEquals(1, damagedCall.arguments().size());
        var damagedLambda = assertInstanceOf(LambdaExpression.class, damagedCall.arguments().getFirst());
        assertEquals(1, damagedLambda.body().statements().size());
        assertInstanceOf(ExpressionStatement.class, damagedLambda.body().statements().getFirst());

        var multilineLambda = recoverableParse("""
                var x = g(func():
                \tpass]
                \treturn 1
                )
                """);
        assertEquals(List.of(DiagnosticCodes.UNEXPECTED_CLOSER),
                errorsOf(multilineLambda).stream().map(AstDiagnostic::code).toList());
        var multiX = assertInstanceOf(VariableDeclaration.class, multilineLambda.ast().statements().getFirst());
        var multiCall = assertInstanceOf(CallExpression.class, multiX.value());
        var multiLambda = assertInstanceOf(LambdaExpression.class, multiCall.arguments().getFirst());
        assertEquals(2, multiLambda.body().statements().size());
        assertInstanceOf(PassStatement.class, multiLambda.body().statements().getFirst());
        assertInstanceOf(ReturnStatement.class, multiLambda.body().statements().get(1));

        var nestedInlineInMultiline = recoverableParse("""
                var x = g(func():
                \tvar cb = func(): pass]
                \treturn 1
                )
                """);
        assertEquals(List.of(DiagnosticCodes.UNEXPECTED_CLOSER),
                errorsOf(nestedInlineInMultiline).stream().map(AstDiagnostic::code).toList());
        var nestedX = assertInstanceOf(VariableDeclaration.class, nestedInlineInMultiline.ast().statements().getFirst());
        var nestedCall = assertInstanceOf(CallExpression.class, nestedX.value());
        var outerLambda = assertInstanceOf(LambdaExpression.class, nestedCall.arguments().getFirst());
        assertEquals(2, outerLambda.body().statements().size());
        assertEquals("cb", assertInstanceOf(VariableDeclaration.class, outerLambda.body().statements().getFirst()).name());
        assertInstanceOf(ReturnStatement.class, outerLambda.body().statements().get(1));

        var multilineMatchedControl = parse("var x = foo(func():\n\treturn 1)\n");
        assertEquals(List.of(), errorMessages(multilineMatchedControl));

        var orphanInFunction = recoverableParse("func f():\n\tpass]\n");
        findErrorByCode(orphanInFunction, DiagnosticCodes.UNEXPECTED_CLOSER);

        var orphanTopLambda = recoverableParse("var f = func(): pass]\n");
        findErrorByCode(orphanTopLambda, DiagnosticCodes.UNEXPECTED_CLOSER);
    }

    @Test
    void escapedMultilineLinesSurfaceLexicalIndentationDiagnosticsWhileClosedMultilineStaysClean() {
        var wrongChar = recoverableParse("""
                func f():
                \tvar x = [1,
                    var y = 1
                var after = 1
                """);
        var wrongCharDiag = findErrorByCode(wrongChar, DiagnosticCodes.LEXICAL_ERROR);
        assertTrue(wrongCharDiag.message().contains("space character for indentation"));
        findErrorByCode(wrongChar, DiagnosticCodes.MISSING_CLOSER_ARRAY);
        assertEquals(List.of("f", "after"), topLevelMemberNames(wrongChar));
        var fn1 = assertInstanceOf(FunctionDeclaration.class, wrongChar.ast().statements().getFirst());
        assertEquals("y", assertInstanceOf(VariableDeclaration.class, fn1.body().statements().get(1)).name());

        var badDedent = recoverableParse("""
                func f():
                    if true:
                        var x = [1,
                      var y = 1
                var after = 1
                """);
        var badDedentDiag = findErrorByCode(badDedent, DiagnosticCodes.LEXICAL_ERROR);
        assertTrue(badDedentDiag.message().contains("Unindent doesn't match"));
        assertEquals(List.of("f", "after"), topLevelMemberNames(badDedent));

        var mixed = recoverableParse("""
                func f():
                \tvar x = [1,
                \t var y = 1
                var after = 1
                """);
        var mixedDiag = findErrorByCode(mixed, DiagnosticCodes.LEXICAL_ERROR);
        assertTrue(mixedDiag.message().contains("Mixed use of tabs and spaces"));
        assertEquals(List.of("f", "after"), topLevelMemberNames(mixed));

        var closedMultiline = parse("""
                func f():
                \tvar x = [
                    1
                \t]
                """);
        assertEquals(List.of(), errorMessages(closedMultiline));
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void lineIndentationMetadataScansScaleLinearlyAcrossWideIndentAndManyTokens() {
        var parser = new GdScriptParser();
        for (var k : List.of(64, 512)) {
            var indent = " ".repeat(k);
            var source = "func f():\n"
                    + indent + "var x = " + "(".repeat(260) + "\n"
                    + indent + "1 + " + "g(0) + ".repeat(k) + "1\n";
            var result = parser.parse(source);
            findErrorByCode(result, DiagnosticCodes.NESTING_DEPTH_EXCEEDED);
            assertTrue(parser.lineIndentScanSteps() <= 2 * k + 8,
                    () -> "expected linear indent scan for k=" + k + ", got " + parser.lineIndentScanSteps());
        }
    }

    @Test
    void backslashContinuationAcrossCommentLinesPreservesContainerAndMemberNames() {
        var commentInArray = recoverableParse("""
                func f():
                \tvar x = [1,
                \t\t# note
                \t\t2,
                \tvar y = 3
                """);
        findErrorByCode(commentInArray, DiagnosticCodes.MISSING_CLOSER_ARRAY);
        assertTrue(errorsOf(commentInArray).stream().noneMatch(d -> DiagnosticCodes.LEXICAL_ERROR.equals(d.code())));
        var fn1 = onlyFunction(commentInArray);
        var x = assertInstanceOf(VariableDeclaration.class, fn1.body().statements().getFirst());
        var y = assertInstanceOf(VariableDeclaration.class, fn1.body().statements().get(1));
        assertEquals("y", y.name());
        var arr = assertInstanceOf(ArrayExpression.class, x.value());
        assertEquals(2, arr.elements().size());

        var continuedMemberAcrossComment = recoverableParse("""
                func f():
                \tvar x = (a.\\
                \t\t# continuation note
                \t\tmatch
                \tvar y = 2
                """);
        findErrorByCode(continuedMemberAcrossComment, DiagnosticCodes.MISSING_CLOSER_GROUPING);
        assertTrue(errorsOf(continuedMemberAcrossComment).stream()
                .noneMatch(d -> DiagnosticCodes.ATTRIBUTE_MISSING_NAME.equals(d.code())));
        var fn2 = onlyFunction(continuedMemberAcrossComment);
        var x2 = assertInstanceOf(VariableDeclaration.class, fn2.body().statements().getFirst());
        var y2 = assertInstanceOf(VariableDeclaration.class, fn2.body().statements().get(1));
        assertEquals("y", y2.name());
        var attr = assertInstanceOf(AttributeExpression.class, x2.value());
        assertEquals("match", assertInstanceOf(AttributePropertyStep.class, attr.steps().getFirst()).name());
    }

    // -----------------------------------------------------------------------------------------
    // Expression folding, dictionary literals, comma-delimited lists/patterns, property blocks,
    // header/suite recovery, and diagnostic attribution.
    // -----------------------------------------------------------------------------------------

    @Test
    void phase4DamagedContinuationAndNegativeGuards() {
        // `super. + 1` folds `+ 1` onto the damaged attribute chain and attributes `SUPER_MISSING_NAME`.
        var superPlus = recoverableParse("func f():\n\tsuper. + 1\n");
        var superDiag = findErrorByCode(superPlus, DiagnosticCodes.SUPER_MISSING_NAME);
        var superStmt = assertInstanceOf(ExpressionStatement.class,
                onlyFunction(superPlus).body().statements().getFirst());
        assertEquals(new RecoveryLink(superStmt, "expression"), superDiag.recovery());
        var superBin = assertInstanceOf(BinaryExpression.class, superStmt.expression());
        assertEquals("+", superBin.operator());
        var superChain = assertInstanceOf(AttributeExpression.class, superBin.left());
        assertInstanceOf(MissingAttributeStep.class, superChain.steps().getFirst());

        // `foo(1+)(2)` preserves outer call and both argument ranges accurately.
        var doubleCall = recoverableParse("func f():\n\tfoo(1+)(2)\n");
        var outerCall = assertInstanceOf(CallExpression.class,
                assertInstanceOf(ExpressionStatement.class,
                        onlyFunction(doubleCall).body().statements().getFirst()).expression());
        var innerCall = assertInstanceOf(CallExpression.class, outerCall.callee());
        assertTrue(innerCall.arguments().getFirst().range().startByte()
                < outerCall.arguments().getFirst().range().startByte());

        // Negative guard: `self.b.(1)` keeps MissingAttributeStep as the CallExpression callee
        // instead of folding `(1)` into a named call on `b`, with only the missing-name error.
        var missingAttrCall = recoverableParse("var x = self.b.(1)\n");
        assertEquals(1, errorsOf(missingAttrCall).size(), () -> errorsOf(missingAttrCall).toString());
        findErrorByCode(missingAttrCall, DiagnosticCodes.ATTRIBUTE_MISSING_NAME);
        var varDecl = assertInstanceOf(VariableDeclaration.class, missingAttrCall.ast().statements().getFirst());
        var call = assertInstanceOf(CallExpression.class, varDecl.value());
        var chain = assertInstanceOf(AttributeExpression.class, call.callee());
        assertEquals("b", assertInstanceOf(AttributePropertyStep.class, chain.steps().getFirst()).name());
        assertInstanceOf(MissingAttributeStep.class, chain.steps().get(1));
        assertEquals("1", assertInstanceOf(LiteralExpression.class, call.arguments().getFirst()).sourceText());
    }

    @Test
    void phase4DictionaryStateMachineCrossLineAndNestedIsolation() {
        // Cross-line missing separator `{"a"\n\t"b": 1}` leaves first entry with zero-width MISSING
        // value and keeps `"b": 1` intact without emitting duplicate missing-value diagnostics.
        var crossLineMissing = recoverableParse("var d = {\n\t\"a\"\n\t\"b\": 1\n}\n");
        assertEquals(List.of(
                DiagnosticCodes.MISSING_SEPARATOR_DICTIONARY,
                DiagnosticCodes.MISSING_SEPARATOR_COMMA
        ), errorsOf(crossLineMissing).stream().map(AstDiagnostic::code).toList(),
                () -> errorsOf(crossLineMissing).toString());
        var dict1 = assertInstanceOf(DictionaryExpression.class,
                assertInstanceOf(VariableDeclaration.class, crossLineMissing.ast().statements().getFirst()).value());
        assertEquals(2, dict1.entries().size());
        var firstMissing = assertInstanceOf(ErrorExpression.class, dict1.entries().getFirst().value());
        assertEquals(CstIssueKind.MISSING, firstMissing.kind());
        assertEquals("dictionary.value", firstMissing.nodeType());
        assertEquals("1", assertInstanceOf(LiteralExpression.class, dict1.entries().get(1).value()).sourceText());

        // With comma `{"a",\n\t"b": 1}`, only the single MISSING_SEPARATOR_DICTIONARY fires.
        var crossLineWithComma = recoverableParse("var d = {\n\t\"a\",\n\t\"b\": 1\n}\n");
        assertEquals(1, errorsOf(crossLineWithComma).size(), () -> errorsOf(crossLineWithComma).toString());
        findErrorByCode(crossLineWithComma, DiagnosticCodes.MISSING_SEPARATOR_DICTIONARY);

        // Healthy cross-line dictionary values after explicit `:` or `=` remain legal with 0 errors.
        var healthyCrossLine = parse("""
                var py = {
                \t"a":
                \t\t1,
                \t"b":
                \t\t2
                }
                var lua = {
                \ta =
                \t\t1,
                \tb =
                \t\t2
                }
                """);
        assertEquals(List.of(), errorsOf(healthyCrossLine), () -> errorsOf(healthyCrossLine).toString());

        // Nested dictionary failure isolates inside the inner dictionary; outer `"ok": 2` survives.
        var nested = recoverableParse("var d = {\"outer\": {name}, \"ok\": 2}\n");
        assertEquals(1, errorsOf(nested).size(), () -> errorsOf(nested).toString());
        findErrorByCode(nested, DiagnosticCodes.MISSING_SEPARATOR_DICTIONARY);
        var outerDict = assertInstanceOf(DictionaryExpression.class,
                assertInstanceOf(VariableDeclaration.class, nested.ast().statements().getFirst()).value());
        assertEquals(2, outerDict.entries().size());
        var innerDict = assertInstanceOf(DictionaryExpression.class, outerDict.entries().getFirst().value());
        assertEquals(1, innerDict.entries().size());
        assertInstanceOf(ErrorExpression.class, innerDict.entries().getFirst().value());
        assertEquals("2", assertInstanceOf(LiteralExpression.class, outerDict.entries().get(1).value()).sourceText());

        // Grouping key range includes real closing parenthesis `(a)`.
        var groupedKey = parse("var d = {(a): 1}\n");
        assertEquals(List.of(), errorsOf(groupedKey));
        var groupedDict = assertInstanceOf(DictionaryExpression.class,
                assertInstanceOf(VariableDeclaration.class, groupedKey.ast().statements().getFirst()).value());
        assertEquals(new Range(9, 15, new Point(0, 9), new Point(0, 15)),
                groupedDict.entries().getFirst().range());
    }

    @Test
    void phase4MissingCommaSyncAcrossListsAndNegativeProtection() {
        // Array `[1 2]` emits one MISSING_SEPARATOR_COMMA and retains both elements.
        var arrayRes = recoverableParse("var a = [1 2]\n");
        assertEquals(1, errorsOf(arrayRes).size(), () -> errorsOf(arrayRes).toString());
        findErrorByCode(arrayRes, DiagnosticCodes.MISSING_SEPARATOR_COMMA);
        var array = assertInstanceOf(ArrayExpression.class,
                assertInstanceOf(VariableDeclaration.class, arrayRes.ast().statements().getFirst()).value());
        assertEquals(List.of("1", "2"), array.elements().stream()
                .map(e -> assertInstanceOf(LiteralExpression.class, e).sourceText()).toList());

        // Function and signal parameter lists emit one MISSING_SEPARATOR_COMMA and keep both params.
        var fnRes = recoverableParse("func f(a b):\n\tpass\n");
        assertEquals(1, errorsOf(fnRes).size(), () -> errorsOf(fnRes).toString());
        findErrorByCode(fnRes, DiagnosticCodes.MISSING_SEPARATOR_COMMA);
        assertEquals(List.of("a", "b"),
                onlyFunction(fnRes).parameters().stream().map(Parameter::name).toList());

        var sigRes = recoverableParse("signal s(a b)\n");
        assertEquals(1, errorsOf(sigRes).size(), () -> errorsOf(sigRes).toString());
        findErrorByCode(sigRes, DiagnosticCodes.MISSING_SEPARATOR_COMMA);
        var sig = assertInstanceOf(SignalStatement.class, sigRes.ast().statements().getFirst());
        assertEquals(List.of("a", "b"),
                sig.parameters().stream().map(Parameter::name).toList());

        // Match dictionary pattern `{1+ var x: 3, 2: 3}` skips bad tail to comma and keeps `2: 3`.
        var dictComma = recoverableParse("func f():\n\tmatch x:\n\t\t{1+ var x: 3, 2: 3}: pass\n");
        var dictPattern = assertInstanceOf(DictionaryExpression.class,
                assertInstanceOf(MatchStatement.class, onlyFunction(dictComma).body().statements().getFirst())
                        .sections().getFirst().patterns().getFirst());
        assertEquals(2, dictPattern.entries().size());
        assertNull(dictPattern.entries().getFirst().value());
        assertEquals("2", assertInstanceOf(LiteralExpression.class, dictPattern.entries().get(1).key()).sourceText());
        assertEquals("3", assertInstanceOf(LiteralExpression.class, dictPattern.entries().get(1).value()).sourceText());

        // Without real `}`, `{1+ var x: 3` stops at layout boundary and protects outer `var after = 1`.
        var unclosedDictPattern = recoverableParse("func f():\n\tmatch x:\n\t\t{1+ var x: 3\nvar after = 1\n");
        assertEquals(2, unclosedDictPattern.ast().statements().size(),
                () -> unclosedDictPattern.ast().statements().toString());
        assertEquals("after", assertInstanceOf(VariableDeclaration.class,
                unclosedDictPattern.ast().statements().get(1)).name());

        // Negative: unclosed cross-line `print(1\n\tprint(2)` never absorbs `print(2)` as missing-comma arg.
        var crossLineCall = recoverableParse("func f():\n\tprint(1\n\tprint(2)\n");
        var fnBody = onlyFunction(crossLineCall).body().statements();
        assertEquals(2, fnBody.size(), () -> fnBody.toString());
        assertTrue(errorsOf(crossLineCall).stream()
                .noneMatch(d -> DiagnosticCodes.MISSING_SEPARATOR_COMMA.equals(d.code())));
    }

    @Test
    void phase4PropertyBlockDrainsDuplicatesAndUnknownAccessorsWithAttribution() {
        // Legal property forms remain 0-diagnostic.
        var legal = parse("""
                var ref_prop: get = _get, set = _set
                var block_prop: int:
                \tget:
                \t\treturn 1
                \tset(value):
                \t\tpass
                """);
        assertEquals(List.of(), errorsOf(legal), () -> errorsOf(legal).toString());

        // Single-line duplicate get/set keeps variable declaration and subsequent members.
        var singleLine = recoverableParse("""
                var x: get = g1, get = g2, set = s1, set = s2
                var after_var = 1
                func after_fn(): pass
                enum AfterEnum { A }
                """);
        assertEquals(4, singleLine.ast().statements().size());
        var xDecl = assertInstanceOf(VariableDeclaration.class, singleLine.ast().statements().getFirst());
        var dupGetter = findErrorByCode(singleLine, DiagnosticCodes.PROPERTY_DUPLICATE_GETTER);
        var dupSetter = findErrorByCode(singleLine, DiagnosticCodes.PROPERTY_DUPLICATE_SETTER);
        assertSame(xDecl, dupGetter.recovery().owner());
        assertEquals("variable.property", dupGetter.recovery().facet());
        assertSame(xDecl, dupSetter.recovery().owner());
        assertEquals("variable.property", dupSetter.recovery().facet());
        assertEquals("after_var", assertInstanceOf(VariableDeclaration.class,
                singleLine.ast().statements().get(1)).name());
        assertEquals("after_fn", assertInstanceOf(FunctionDeclaration.class,
                singleLine.ast().statements().get(2)).name());
        assertEquals("AfterEnum", assertInstanceOf(EnumDeclaration.class,
                singleLine.ast().statements().get(3)).name());

        // Indented triple get + mixed get/set/get + unknown accessor (nested suite & line) drain
        // all parts cleanly without borrowing outer DEDENT or killing following members.
        var tripleAndUnknown = recoverableParse("""
                var p1:
                \tget:
                \t\tpass
                \tget:
                \t\tpass
                \tget:
                \t\tpass
                var p2:
                \tget:
                \t\tpass
                \tset(v):
                \t\tpass
                \tget:
                \t\tpass
                \tfoo:
                \t\tvar inner = 1
                \tbar
                var ok = 42
                func g():
                \tpass
                enum E { X }
                """);
        assertEquals(5, tripleAndUnknown.ast().statements().size(),
                () -> tripleAndUnknown.ast().statements().toString());
        var p1 = assertInstanceOf(VariableDeclaration.class, tripleAndUnknown.ast().statements().getFirst());
        var p2 = assertInstanceOf(VariableDeclaration.class, tripleAndUnknown.ast().statements().get(1));
        assertEquals(2, errorsOf(tripleAndUnknown).stream()
                .filter(d -> DiagnosticCodes.PROPERTY_DUPLICATE_GETTER.equals(d.code())
                        && d.recovery() != null && d.recovery().owner() == p1)
                .count());
        var invalidAccessors = errorsOf(tripleAndUnknown).stream()
                .filter(d -> DiagnosticCodes.PROPERTY_INVALID_ACCESSOR.equals(d.code()))
                .toList();
        assertEquals(2, invalidAccessors.size(), () -> errorsOf(tripleAndUnknown).toString());
        for (var inv : invalidAccessors) {
            assertSame(p2, inv.recovery().owner());
            assertEquals("variable.property", inv.recovery().facet());
        }
        assertEquals("ok", assertInstanceOf(VariableDeclaration.class,
                tripleAndUnknown.ast().statements().get(2)).name());
        assertEquals("g", assertInstanceOf(FunctionDeclaration.class,
                tripleAndUnknown.ast().statements().get(3)).name());
        assertEquals("E", assertInstanceOf(EnumDeclaration.class,
                tripleAndUnknown.ast().statements().get(4)).name());
    }

    @Test
    void phase4UnclosedSignatureYieldAndNegativeControls() {
        // Legal bodyless signature and legal multiline parameter type both pass with 0 diagnostics.
        var legalBodyless = parse("func f(a)\nvar x = 1\n");
        assertEquals(List.of(), errorsOf(legalBodyless), () -> errorsOf(legalBodyless).toString());
        assertEquals(2, legalBodyless.ast().statements().size());
        var bodylessFn = assertInstanceOf(FunctionDeclaration.class, legalBodyless.ast().statements().getFirst());
        assertTrue(bodylessFn.body().statements().isEmpty());

        var legalMultilineType = parse("func f(\n\ta:\n\tint\n):\n\tpass\n");
        assertEquals(List.of(), errorsOf(legalMultilineType), () -> errorsOf(legalMultilineType).toString());

        // Negative: unclosed param list followed by `:` and same-indent outer member must NOT steal `var after`.
        var noDeepSuite = recoverableParse("func f(a, b:\nvar after = 1\n");
        assertEquals(2, noDeepSuite.ast().statements().size(),
                () -> noDeepSuite.ast().statements().toString());
        assertEquals("after", assertInstanceOf(VariableDeclaration.class,
                noDeepSuite.ast().statements().get(1)).name());
    }

    @Test
    void phase4MatchSectionMissingColonWithBodyAndSibling() {
        // Missing `:` followed by indented body retains both the current section's body and later `2:`.
        var withBody = recoverableParse("""
                func f():
                \tmatch x:
                \t\t1
                \t\t\tpass
                \t\t2:
                \t\t\tpass
                """);
        assertEquals(1, errorsOf(withBody).size(), () -> errorsOf(withBody).toString());
        var colonErr = findErrorByCode(withBody, DiagnosticCodes.MISSING_SEPARATOR_MATCH_COLON);
        var match = assertInstanceOf(MatchStatement.class,
                onlyFunction(withBody).body().statements().getFirst());
        assertEquals(2, match.sections().size());
        var firstSection = assertInstanceOf(MatchSection.class, match.sections().getFirst());
        assertSame(firstSection, colonErr.recovery().owner());
        assertEquals("match.section", colonErr.recovery().facet());
        assertInstanceOf(PassStatement.class, firstSection.body().statements().getFirst());
        assertInstanceOf(PassStatement.class, match.sections().get(1).body().statements().getFirst());

        // Missing `:` without an indented body does not steal `2:` or ancestor DEDENT.
        var withoutBody = recoverableParse("""
                func f():
                \tmatch x:
                \t\t1
                \t\t2:
                \t\t\tpass
                \tvar after = 1
                """);
        findErrorByCode(withoutBody, DiagnosticCodes.MISSING_SEPARATOR_MATCH_COLON);
        var fnBody = onlyFunction(withoutBody).body().statements();
        assertEquals(2, fnBody.size());
        var match2 = assertInstanceOf(MatchStatement.class, fnBody.getFirst());
        assertEquals(2, match2.sections().size());
        assertTrue(match2.sections().getFirst().body().statements().isEmpty());
        assertEquals(1, match2.sections().get(1).body().statements().size());
        assertEquals("after", assertInstanceOf(VariableDeclaration.class, fnBody.get(1)).name());
    }

    @Test
    void phase4MissingHeaderNamesAndControlNegative() {
        // `for in arr:` retains ForStatement, iterable, and body with FOR_MISSING_ITERATOR.
        var forRes = recoverableParse("func f():\n\tfor in arr:\n\t\tpass\n");
        var forErr = findErrorByCode(forRes, DiagnosticCodes.FOR_MISSING_ITERATOR);
        var forStmt = assertInstanceOf(ForStatement.class,
                onlyFunction(forRes).body().statements().getFirst());
        assertSame(forStmt, forErr.recovery().owner());
        assertEquals("for.iterator", forErr.recovery().facet());
        assertEquals("", forStmt.iterator());
        assertEquals("arr", assertInstanceOf(IdentifierExpression.class, forStmt.iterable()).name());
        assertInstanceOf(PassStatement.class, forStmt.body().statements().getFirst());

        // `const A` retains VariableDeclaration(CONST, "A", Missing("constant.value")).
        var constRes = recoverableParse("const A\nvar ok = 1\n");
        var constErr = findErrorByCode(constRes, DiagnosticCodes.SLOT_CONSTANT_VALUE);
        var constDecl = assertInstanceOf(VariableDeclaration.class, constRes.ast().statements().getFirst());
        assertEquals(DeclarationKind.CONST, constDecl.kind());
        assertEquals("A", constDecl.name());
        assertSame(constDecl, constErr.recovery().owner());
        assertEquals("constant.value", constErr.recovery().facet());
        var constMarker = assertInstanceOf(ErrorExpression.class, constDecl.value());
        assertEquals(CstIssueKind.MISSING, constMarker.kind());
        assertEquals("constant.value", constMarker.nodeType());
        assertEquals("ok", assertInstanceOf(VariableDeclaration.class, constRes.ast().statements().get(1)).name());

        // `var = 1` retains VariableDeclaration(VAR, "", 1) with DECLARATION_MISSING_NAME.
        var varRes = recoverableParse("var = 1\n");
        var varErr = findErrorByCode(varRes, DiagnosticCodes.DECLARATION_MISSING_NAME);
        var varDecl = assertInstanceOf(VariableDeclaration.class, varRes.ast().statements().getFirst());
        assertEquals(DeclarationKind.VAR, varDecl.kind());
        assertEquals("", varDecl.name());
        assertEquals("1", assertInstanceOf(LiteralExpression.class, varDecl.value()).sourceText());
        assertSame(varDecl, varErr.recovery().owner());
        assertEquals("declaration.name", varErr.recovery().facet());

        // Class-body `func (a: int):\n\tpass\n` retains FunctionDeclaration with DECLARATION_MISSING_NAME.
        var fnRes = recoverableParse("func (a: int):\n\tpass\n");
        var fnErr = findErrorByCode(fnRes, DiagnosticCodes.DECLARATION_MISSING_NAME);
        var fnDecl = assertInstanceOf(FunctionDeclaration.class, fnRes.ast().statements().getFirst());
        assertEquals("", fnDecl.name());
        assertEquals(List.of("a"), fnDecl.parameters().stream().map(Parameter::name).toList());
        assertInstanceOf(PassStatement.class, fnDecl.body().statements().getFirst());
        assertSame(fnDecl, fnErr.recovery().owner());
        assertEquals("function.name", fnErr.recovery().facet());

        // Negative: legal anonymous enum has 0 diagnostics; suite-level bare `func` stays distinct.
        assertEquals(List.of(), errorsOf(parse("enum { A, B }\n")));
        var suiteBareFunc = recoverableParse("func f():\n\tfunc ()\n\tpass\n");
        assertEquals(2, onlyFunction(suiteBareFunc).body().statements().size());
    }

    @Test
    void phase4PrefixAnnotationProgressAndDanglingSeparation() {
        // Same-logical-line prefix annotations advance cleanly without failing.
        var sameLine = parse("func f(): @warning_ignore(\"a\") pass\n");
        assertEquals(List.of(), errorsOf(sameLine), () -> errorsOf(sameLine).toString());
        var fnBody = onlyFunction(sameLine).body().statements();
        assertEquals(2, fnBody.size());
        assertInstanceOf(AnnotationStatement.class, fnBody.getFirst());
        assertInstanceOf(PassStatement.class, fnBody.get(1));

        // 120 trailing annotations at scope end never falsely consume circuit-breaker budget.
        var danglingMany = parse("func f():\n" + "\t@warning_ignore(\"a\")\n".repeat(120));
        assertTrue(errorsOf(danglingMany).stream()
                .noneMatch(d -> DiagnosticCodes.STATEMENT_TOO_MANY_ERRORS.equals(d.code())),
                () -> errorsOf(danglingMany).toString());
        assertEquals(120, onlyFunction(danglingMany).body().statements().size());
    }

    @Test
    void phase4LinearScanBoundsAndParserReuseBalanceInvariant() {
        // Large missing-comma and missing-separator lists stay single-pass without rescanning.
        var parser = new GdScriptParser();
        for (var n : List.of(128, 512)) {
            var missingCommaCall = "var x = foo(" + "1 ".repeat(n) + ")\n";
            var callRes = parser.parse(missingCommaCall);
            assertTrue(parser.bracketOracleScanCount() <= 1,
                    () -> "unexpected rescan for missing-comma call n=" + n + ": " + parser.bracketOracleScanCount());
            var call = assertInstanceOf(CallExpression.class,
                    assertInstanceOf(VariableDeclaration.class, callRes.ast().statements().getFirst()).value());
            assertEquals(n, call.arguments().size());

            var missingSepDict = "var d = {" + "\"k\"\n".repeat(n) + "}\n";
            var dictRes = parser.parse(missingSepDict);
            assertTrue(parser.bracketOracleScanCount() <= 1,
                    () -> "unexpected rescan for missing-separator dict n=" + n + ": " + parser.bracketOracleScanCount());
            var dict = assertInstanceOf(DictionaryExpression.class,
                    assertInstanceOf(VariableDeclaration.class, dictRes.ast().statements().getFirst()).value());
            assertEquals(n, dict.entries().size());
        }

        // Repeated recovery parses followed by a clean parse leave stacks balanced and reusable.
        for (var sample : List.of(
                "var x: get = g1, get = g2, foo\n",
                "func f(a, b:\n\tpass\n",
                "func f():\n\tmatch x:\n\t\t1\n\t\t\tpass\n",
                "for in arr:\n",
                "const A\nvar = 1\nfunc ():\n\tpass\n")) {
            parser.parse(sample);
        }
        var clean = parser.parse("var ok = 1\n");
        assertEquals(List.of(), errorsOf(clean));
        assertEquals("ok", assertInstanceOf(VariableDeclaration.class, clean.ast().statements().getFirst()).name());
    }

    @Test
    @Timeout(5)
    void propertyBlockSemicolonUnclosedDrainAndMixedReferenceRecovery() {
        // Semicolon inside an indented property block must terminate and attribute to `x`.
        var semiRes = recoverableParse("var x:\n\t;\nvar after = 1\n");
        var semiProp = assertInstanceOf(VariableDeclaration.class, semiRes.ast().statements().getFirst());
        assertEquals("x", semiProp.name());
        assertEquals("after", assertInstanceOf(VariableDeclaration.class, semiRes.ast().statements().get(1)).name());
        var semiDiag = findErrorByCode(semiRes, DiagnosticCodes.PROPERTY_INVALID_ACCESSOR);
        assertEquals(new RecoveryLink(semiProp, "variable.property"), semiDiag.recovery());

        // Single-line reference list ending with comma + newline must not swallow `var after = 1`.
        var inlineNewlineRes = recoverableParse("var x: get = g,\nvar after = 1\n");
        assertEquals(2, inlineNewlineRes.ast().statements().size(),
                () -> inlineNewlineRes.ast().statements().toString());
        assertEquals("x", assertInstanceOf(VariableDeclaration.class, inlineNewlineRes.ast().statements().getFirst()).name());
        assertEquals("after", assertInstanceOf(VariableDeclaration.class, inlineNewlineRes.ast().statements().get(1)).name());

        // Unclosed `(` inside an unknown property line must still stop at NEWLINE/DEDENT.
        var unclosedParenRes = recoverableParse("var x:\n\tunknown(\n\t\tpass\nvar after = 1\n");
        var unclosedProp = assertInstanceOf(VariableDeclaration.class, unclosedParenRes.ast().statements().getFirst());
        assertEquals("x", unclosedProp.name());
        assertEquals("after", assertInstanceOf(VariableDeclaration.class, unclosedParenRes.ast().statements().get(1)).name());
        assertEquals(new RecoveryLink(unclosedProp, "variable.property"),
                findErrorByCode(unclosedParenRes, DiagnosticCodes.PROPERTY_INVALID_ACCESSOR).recovery());

        // Indented property block starting with `= fn` continues draining through unknown block items.
        var mixedRefThenUnknown = recoverableParse("var x:\n\tget = getter\n\tunknown:\n\t\tpass\nvar after = 1\n");
        var mixedProp = assertInstanceOf(VariableDeclaration.class, mixedRefThenUnknown.ast().statements().getFirst());
        assertEquals("x", mixedProp.name());
        assertEquals("after", assertInstanceOf(VariableDeclaration.class, mixedRefThenUnknown.ast().statements().get(1)).name());
        assertEquals(new RecoveryLink(mixedProp, "variable.property"),
                findErrorByCode(mixedRefThenUnknown, DiagnosticCodes.PROPERTY_INVALID_ACCESSOR).recovery());

        // Mixing `= fn` with a subsequent block `set(v):` in an indented block still reports an error.
        var mixedRefThenBlockSet = recoverableParse("var x:\n\tget = getter\n\tset(v):\n\t\tpass\nvar after = 1\n");
        assertEquals(2, mixedRefThenBlockSet.ast().statements().size());
        assertEquals("after", assertInstanceOf(VariableDeclaration.class, mixedRefThenBlockSet.ast().statements().get(1)).name());

        // Positive control: multi-line indented comma-separated property references stay diagnostic-free.
        var legalMultilineRef = parse("var x:\n\tget = g,\n\tset = s\nvar after = 1\n");
        assertEquals(List.of(), errorsOf(legalMultilineRef), () -> errorsOf(legalMultilineRef).toString());
        assertEquals(2, legalMultilineRef.ast().statements().size());
    }

    @Test
    void badSeparatedItemSkipStopsAtExpectedCloserEvenWithUnclosedLocalParen() {
        var res = recoverableParse("func f():\n\tmatch x:\n\t\t{1+ var y(}: pass\n\t\t_: pass\nvar after = 1\n");
        assertEquals(2, res.ast().statements().size(), () -> res.ast().statements().toString());
        var fn = assertInstanceOf(FunctionDeclaration.class, res.ast().statements().getFirst());
        var match = assertInstanceOf(MatchStatement.class, fn.body().statements().getFirst());
        assertEquals(2, match.sections().size(), () -> match.sections().toString());
        assertInstanceOf(PassStatement.class, match.sections().getFirst().body().statements().getFirst());
        assertInstanceOf(PassStatement.class, match.sections().get(1).body().statements().getFirst());
        assertEquals("after", assertInstanceOf(VariableDeclaration.class, res.ast().statements().get(1)).name());
    }

    @Test
    void nestedLambdaUnclosedParameterListYieldsAndPositiveControlsStayClean() {
        // Positive controls: balanced nested lambda signatures keep their typed parameters cleanly.
        var posColon = parse("var x = g(func(a:\n\tint): pass, 2)\n");
        assertEquals(List.of(), errorsOf(posColon), () -> errorsOf(posColon).toString());
        var posColonCall = assertInstanceOf(CallExpression.class,
                assertInstanceOf(VariableDeclaration.class, posColon.ast().statements().getFirst()).value());
        assertEquals(2, posColonCall.arguments().size());
        var posColonLambda = assertInstanceOf(LambdaExpression.class, posColonCall.arguments().getFirst());
        assertEquals("int", posColonLambda.parameters().getFirst().type().sourceText());

        var posArrow = parse("var x = g(func(a:\n\tint) -> int:\n\treturn a, 2)\n");
        assertEquals(List.of(), errorsOf(posArrow), () -> errorsOf(posArrow).toString());
        var posArrowCall = assertInstanceOf(CallExpression.class,
                assertInstanceOf(VariableDeclaration.class, posArrow.ast().statements().getFirst()).value());
        assertEquals(2, posArrowCall.arguments().size());
        var posArrowLambda = assertInstanceOf(LambdaExpression.class, posArrowCall.arguments().getFirst());
        assertEquals("int", posArrowLambda.parameters().getFirst().type().sourceText());
        assertEquals("int", posArrowLambda.returnType().sourceText());
        assertInstanceOf(ReturnStatement.class, posArrowLambda.body().statements().getFirst());

        var posBodylessFn = parse("func f(a:\n\tint\n)\n");
        assertEquals(List.of(), errorsOf(posBodylessFn), () -> errorsOf(posBodylessFn).toString());
        var bodylessFn = onlyFunction(posBodylessFn);
        assertEquals("int", bodylessFn.parameters().getFirst().type().sourceText());

        // Negative: outer `g(` stole the only `)`, so inner `func(a:` yields `:` to its suite.
        var neg = recoverableParse("var x = g(func(a:\n\tpass\n)\nvar ok = 1\n");
        findErrorByCode(neg, DiagnosticCodes.MISSING_CLOSER_PARAMETER_LIST);
        assertEquals(2, neg.ast().statements().size(), () -> neg.ast().statements().toString());
        var negCall = assertInstanceOf(CallExpression.class,
                assertInstanceOf(VariableDeclaration.class, neg.ast().statements().getFirst()).value());
        var negLambda = assertInstanceOf(LambdaExpression.class, negCall.arguments().getFirst());
        assertEquals("a", negLambda.parameters().getFirst().name());
        assertNull(negLambda.parameters().getFirst().type());
        assertInstanceOf(PassStatement.class, negLambda.body().statements().getFirst());
        assertEquals("ok", assertInstanceOf(VariableDeclaration.class, neg.ast().statements().get(1)).name());
    }

    @Test
    void matchSectionMissingColonAfterLambdaGuardPreservesNextBranch() {
        var inlineGuard = recoverableParse("func f():\n\tmatch x:\n\t\t1 when func(): pass\n\t\t2: pass\n");
        var inlineMatch = assertInstanceOf(MatchStatement.class,
                onlyFunction(inlineGuard).body().statements().getFirst());
        assertEquals(2, inlineMatch.sections().size(), () -> inlineMatch.sections().toString());
        var inlineFirst = inlineMatch.sections().getFirst();
        assertInstanceOf(LambdaExpression.class, inlineFirst.guard());
        assertEquals(List.of(), inlineFirst.body().statements());
        assertEquals(new RecoveryLink(inlineFirst, "match.section"),
                findErrorByCode(inlineGuard, DiagnosticCodes.MISSING_SEPARATOR_MATCH_COLON).recovery());
        assertInstanceOf(PassStatement.class, inlineMatch.sections().get(1).body().statements().getFirst());

        var multilineGuard = recoverableParse("func f():\n\tmatch x:\n\t\t1 when func():\n\t\t\tpass\n\t\t2: pass\n");
        var multiMatch = assertInstanceOf(MatchStatement.class,
                onlyFunction(multilineGuard).body().statements().getFirst());
        assertEquals(2, multiMatch.sections().size(), () -> multiMatch.sections().toString());
        var multiFirst = multiMatch.sections().getFirst();
        assertInstanceOf(LambdaExpression.class, multiFirst.guard());
        assertEquals(List.of(), multiFirst.body().statements());
        assertEquals(new RecoveryLink(multiFirst, "match.section"),
                findErrorByCode(multilineGuard, DiagnosticCodes.MISSING_SEPARATOR_MATCH_COLON).recovery());
        assertInstanceOf(PassStatement.class, multiMatch.sections().get(1).body().statements().getFirst());
    }

    @Test
    void endStatementDrainsSameLineResidueWithoutStealingSemicolonOrOuterComma() {
        var sameLineGarbage = recoverableParse("func f():\n\tsuper. 0 var y = 1\n\tvar z = 2\n");
        var body = onlyFunction(sameLineGarbage).body().statements();
        assertEquals(2, body.size(), () -> body.toString());
        var superStmt = assertInstanceOf(ExpressionStatement.class, body.getFirst());
        var superChain = assertInstanceOf(AttributeExpression.class, superStmt.expression());
        assertInstanceOf(MissingAttributeStep.class, superChain.steps().getFirst());
        assertEquals("z", assertInstanceOf(VariableDeclaration.class, body.get(1)).name());

        var afterSemicolon = recoverableParse("func f():\n\tsuper. 0 var y = 1; var z = 2\n");
        var semiBody = onlyFunction(afterSemicolon).body().statements();
        assertEquals(2, semiBody.size(), () -> semiBody.toString());
        assertInstanceOf(ExpressionStatement.class, semiBody.getFirst());
        assertEquals("z", assertInstanceOf(VariableDeclaration.class, semiBody.get(1)).name());

        var lambdaInCall = recoverableParse("var r = f(func(): super. 0 var y = 1, 2)\n");
        var outerCall = assertInstanceOf(CallExpression.class,
                assertInstanceOf(VariableDeclaration.class, lambdaInCall.ast().statements().getFirst()).value());
        assertInstanceOf(LambdaExpression.class, outerCall.arguments().getFirst());
        assertEquals("2", assertInstanceOf(LiteralExpression.class, outerCall.arguments().getLast()).sourceText());
    }

    @Test
    void arrayPatternMissingCommaRecognizesWildcardStarterAndSynchronizeReclaimsIndentedContinuation() {
        var arrayWildcard = recoverableParse("func f():\n\tmatch x:\n\t\t[1 _]: pass\n");
        assertEquals(List.of(DiagnosticCodes.MISSING_SEPARATOR_COMMA),
                errorsOf(arrayWildcard).stream().map(AstDiagnostic::code).toList());
        var match = assertInstanceOf(MatchStatement.class,
                onlyFunction(arrayWildcard).body().statements().getFirst());
        var pattern = assertInstanceOf(ArrayExpression.class, match.sections().getFirst().patterns().getFirst());
        assertEquals(2, pattern.elements().size(), () -> pattern.elements().toString());
        assertEquals("1", assertInstanceOf(LiteralExpression.class, pattern.elements().getFirst()).sourceText());
        assertEquals("_", assertInstanceOf(IdentifierExpression.class, pattern.elements().get(1)).name());

        var deeperCall = recoverableParse("func f():\n\tprint(1\n\t\t1 + 2\n\tprint(2)\n");
        assertEquals(1, deeperCall.ast().statements().size(), () -> deeperCall.ast().statements().toString());
        var fnBody = onlyFunction(deeperCall).body().statements();
        assertEquals(3, fnBody.size(), () -> fnBody.toString());
        assertInstanceOf(ExpressionStatement.class, fnBody.getFirst());
        var midExpr = assertInstanceOf(ExpressionStatement.class, fnBody.get(1));
        assertInstanceOf(BinaryExpression.class, midExpr.expression());
        assertInstanceOf(ExpressionStatement.class, fnBody.get(2));
        assertFalse(errorsOf(deeperCall).stream().anyMatch(d -> d.message().contains("class body")),
                () -> errorsOf(deeperCall).toString());

        var deeperArray = recoverableParse("func f():\n\tvar a = [1\n\t\t2\nvar y = 3\n");
        assertEquals(2, deeperArray.ast().statements().size(), () -> deeperArray.ast().statements().toString());
        var arrayFnBody = assertInstanceOf(FunctionDeclaration.class, deeperArray.ast().statements().getFirst())
                .body().statements();
        assertEquals(2, arrayFnBody.size(), () -> arrayFnBody.toString());
        assertEquals("a", assertInstanceOf(VariableDeclaration.class, arrayFnBody.getFirst()).name());
        assertEquals("2", assertInstanceOf(LiteralExpression.class,
                assertInstanceOf(ExpressionStatement.class, arrayFnBody.get(1)).expression()).sourceText());
        assertEquals("y", assertInstanceOf(VariableDeclaration.class, deeperArray.ast().statements().get(1)).name());
    }

    @Test
    void dictionaryMultilineKeyDoesNotStealNextEntryAndPreservesValidMultilineForms() {
        var multiKeyNoComma = recoverableParse("var d = {\"a\"\n\t+ 1 \"b\": 2}\n");
        var dict1 = assertInstanceOf(DictionaryExpression.class,
                assertInstanceOf(VariableDeclaration.class, multiKeyNoComma.ast().statements().getFirst()).value());
        assertEquals(2, dict1.entries().size(), () -> dict1.entries().toString());
        assertInstanceOf(BinaryExpression.class, dict1.entries().getFirst().key());
        var missingVal1 = assertInstanceOf(ErrorExpression.class, dict1.entries().getFirst().value());
        assertEquals(CstIssueKind.MISSING, missingVal1.kind());
        assertEquals("\"b\"", assertInstanceOf(LiteralExpression.class, dict1.entries().get(1).key()).sourceText());
        assertEquals("2", assertInstanceOf(LiteralExpression.class, dict1.entries().get(1).value()).sourceText());

        var sameLineNextKey = recoverableParse("var d = {\"a\" \"b\": 2}\n");
        var dict2 = assertInstanceOf(DictionaryExpression.class,
                assertInstanceOf(VariableDeclaration.class, sameLineNextKey.ast().statements().getFirst()).value());
        assertEquals(2, dict2.entries().size(), () -> dict2.entries().toString());
        assertEquals("\"a\"", assertInstanceOf(LiteralExpression.class, dict2.entries().getFirst().key()).sourceText());
        assertEquals(CstIssueKind.MISSING,
                assertInstanceOf(ErrorExpression.class, dict2.entries().getFirst().value()).kind());
        assertEquals("\"b\"", assertInstanceOf(LiteralExpression.class, dict2.entries().get(1).key()).sourceText());

        var validMultiKey = parse("var d = {\"a\"\n\t+ \"b\": 1}\n");
        assertEquals(List.of(), errorsOf(validMultiKey), () -> errorsOf(validMultiKey).toString());
        var dict3 = assertInstanceOf(DictionaryExpression.class,
                assertInstanceOf(VariableDeclaration.class, validMultiKey.ast().statements().getFirst()).value());
        assertEquals(1, dict3.entries().size());

        var validMultiValue = parse("var d = {\"a\":\n\t1 + 2}\n");
        assertEquals(List.of(), errorsOf(validMultiValue), () -> errorsOf(validMultiValue).toString());
        var dict4 = assertInstanceOf(DictionaryExpression.class,
                assertInstanceOf(VariableDeclaration.class, validMultiValue.ast().statements().getFirst()).value());
        assertEquals(1, dict4.entries().size());

        var sameLineMissingSep = recoverableParse("var d = {1 2}\n");
        findErrorByCode(sameLineMissingSep, DiagnosticCodes.MISSING_SEPARATOR_DICTIONARY);
        var dict5 = assertInstanceOf(DictionaryExpression.class,
                assertInstanceOf(VariableDeclaration.class, sameLineMissingSep.ast().statements().getFirst()).value());
        assertEquals(1, dict5.entries().size());
        assertEquals("2", assertInstanceOf(LiteralExpression.class, dict5.entries().getFirst().value()).sourceText());
    }

    @Test
    void isolatedPropertySuiteAndDuplicateParameterDiagnosticsAttributeToOuterOwners() {
        var isolatedProp = recoverableParse("var x:\n\tget:\n\t\tvar = 1\n\t\tvar y = [1 2]\n");
        var propVar = assertInstanceOf(VariableDeclaration.class, isolatedProp.ast().statements().getFirst());
        var missingNameDiag = findErrorByCode(isolatedProp, DiagnosticCodes.DECLARATION_MISSING_NAME);
        var missingCommaDiag = findErrorByCode(isolatedProp, DiagnosticCodes.MISSING_SEPARATOR_COMMA);
        assertEquals(new RecoveryLink(propVar, "variable.property"), missingNameDiag.recovery());
        assertEquals(new RecoveryLink(propVar, "variable.property"), missingCommaDiag.recovery());

        var dupParamArray = recoverableParse("func f(a, a = [1 2]): pass\n");
        var fn = onlyFunction(dupParamArray);
        assertEquals(1, fn.parameters().size());
        var dupCommaDiag = findErrorByCode(dupParamArray, DiagnosticCodes.MISSING_SEPARATOR_COMMA);
        assertEquals(new RecoveryLink(fn, "function.signature"), dupCommaDiag.recovery());

        // Unsupported codes inside a dropped duplicate parameter degrade cleanly without throwing.
        var dupParamSlot = recoverableParse("func f(a, a = ): pass\n");
        var slotDiag = findErrorByCode(dupParamSlot, DiagnosticCodes.SLOT_PARAMETER_DEFAULT);
        assertNull(slotDiag.recovery());
    }

    @Test
    void unexpectedIndentBalancingKeepsSuiteAndClassBodyOwnershipWithoutSynchronizeGuessing() {
        var suiteSibling = recoverableParse("func f():\n\tpass\n\t\tvar garbage = 0\n\tvar ok = 1\nvar top = 2\n");
        assertEquals(2, suiteSibling.ast().statements().size(), () -> suiteSibling.ast().statements().toString());
        var fn = assertInstanceOf(FunctionDeclaration.class, suiteSibling.ast().statements().getFirst());
        assertEquals(3, fn.body().statements().size(), () -> fn.body().statements().toString());
        assertInstanceOf(PassStatement.class, fn.body().statements().getFirst());
        assertEquals("garbage", assertInstanceOf(VariableDeclaration.class, fn.body().statements().get(1)).name());
        assertEquals("ok", assertInstanceOf(VariableDeclaration.class, fn.body().statements().get(2)).name());
        assertEquals("top", assertInstanceOf(VariableDeclaration.class, suiteSibling.ast().statements().get(1)).name());

        var nestedClass = recoverableParse("class C:\n\tvar a = 1\n\t\tvar b = 2\n\tvar c = 3\nvar top = 4\n");
        assertEquals(2, nestedClass.ast().statements().size(), () -> nestedClass.ast().statements().toString());
        var innerClass = assertInstanceOf(ClassDeclaration.class, nestedClass.ast().statements().getFirst());
        assertEquals(List.of("a", "b", "c"), innerClass.body().statements().stream()
                .map(stmt -> assertInstanceOf(VariableDeclaration.class, stmt).name())
                .toList());
        assertEquals("top", assertInstanceOf(VariableDeclaration.class, nestedClass.ast().statements().get(1)).name());
    }

    @Test
    void propertyBlockLocalDedentStopAndIndentedReferenceListMissingComma() {
        var nestedGarbage = recoverableParse("var x:\n\tget = g\n\t\tjunk\n\tget = g2\nvar after = 1\n");
        assertEquals(2, nestedGarbage.ast().statements().size(), () -> nestedGarbage.ast().statements().toString());
        var propVar = assertInstanceOf(VariableDeclaration.class, nestedGarbage.ast().statements().getFirst());
        assertEquals("after", assertInstanceOf(VariableDeclaration.class, nestedGarbage.ast().statements().get(1)).name());
        var invalidDiag = findErrorByCode(nestedGarbage, DiagnosticCodes.PROPERTY_INVALID_ACCESSOR);
        var dupGetterDiag = findErrorByCode(nestedGarbage, DiagnosticCodes.PROPERTY_DUPLICATE_GETTER);
        assertEquals(new RecoveryLink(propVar, "variable.property"), invalidDiag.recovery());
        assertEquals(new RecoveryLink(propVar, "variable.property"), dupGetterDiag.recovery());

        var missingCommaRef = recoverableParse("var x:\n\tget = g\n\tset = s\n");
        var missingCommaProp = assertInstanceOf(VariableDeclaration.class, missingCommaRef.ast().statements().getFirst());
        var missingCommaDiag = findErrorByCode(missingCommaRef, DiagnosticCodes.MISSING_SEPARATOR_COMMA);
        assertEquals(new RecoveryLink(missingCommaProp, "variable.property"), missingCommaDiag.recovery());

        var validCommaRef = parse("var x:\n\tget = g,\n\tset = s\n");
        assertEquals(List.of(), errorsOf(validCommaRef), () -> errorsOf(validCommaRef).toString());
    }

    @Test
    void unclosedParameterListYieldsReturnArrowAndSingleLineColonWithLinearSignatureScanning() {
        var arrowLambda = recoverableParse("var x = g(func(a -> int:\n\tpass\n)\nvar ok = 1\n");
        assertEquals(2, arrowLambda.ast().statements().size(), () -> arrowLambda.ast().statements().toString());
        var initCall = assertInstanceOf(CallExpression.class,
                assertInstanceOf(VariableDeclaration.class, arrowLambda.ast().statements().getFirst()).value());
        var lambda = assertInstanceOf(LambdaExpression.class, initCall.arguments().getFirst());
        assertEquals(1, lambda.parameters().size());
        assertEquals("a", lambda.parameters().getFirst().name());
        assertEquals("int", lambda.returnType().sourceText());
        assertInstanceOf(PassStatement.class, lambda.body().statements().getFirst());
        assertEquals("ok", assertInstanceOf(VariableDeclaration.class, arrowLambda.ast().statements().get(1)).name());

        var singleLineColonFn = recoverableParse("func f(a = 1: pass\nvar ok = 1\n");
        assertEquals(2, singleLineColonFn.ast().statements().size(), () -> singleLineColonFn.ast().statements().toString());
        var fn = assertInstanceOf(FunctionDeclaration.class, singleLineColonFn.ast().statements().getFirst());
        assertEquals(1, fn.parameters().size());
        assertEquals("a", fn.parameters().getFirst().name());
        assertEquals("1", assertInstanceOf(LiteralExpression.class, fn.parameters().getFirst().defaultValue()).sourceText());
        assertInstanceOf(PassStatement.class, fn.body().statements().getFirst());
        findErrorByCode(singleLineColonFn, DiagnosticCodes.MISSING_CLOSER_PARAMETER_LIST);
        assertEquals("ok", assertInstanceOf(VariableDeclaration.class, singleLineColonFn.ast().statements().get(1)).name());

        var inlineTypeParser = new GdScriptParser();
        inlineTypeParser.parse("var x = g(func(" + "a: int, ".repeat(200) + "z)" + " ".repeat(2000));
        assertEquals(0, inlineTypeParser.signatureContinuationScanSteps());

        var multilineColonParser = new GdScriptParser();
        multilineColonParser.parse("var x = g(func(" + "a:\n\tint, ".repeat(50) + "z:\n\tpass\n)" + " ".repeat(1000));
        assertTrue(multilineColonParser.signatureContinuationScanSteps() <= 1050,
                () -> "Expected linear scan budget <= 1050, got: " + multilineColonParser.signatureContinuationScanSteps());
    }

    @Test
    void lineContinuationDrainsEndStatementLogicalLineAndPreservesDictionaryValueAfterPeek() {
        var superVar = recoverableParse("func f():\n\tsuper. \\\n\tvar y = 1\n\tvar ok = 2\n");
        var fn1Body = onlyFunction(superVar).body().statements();
        assertEquals(2, fn1Body.size(), () -> fn1Body.toString());
        assertInstanceOf(ExpressionStatement.class, fn1Body.getFirst());
        assertEquals("ok", assertInstanceOf(VariableDeclaration.class, fn1Body.get(1)).name());

        var superZeroVar = recoverableParse("func f():\n\tsuper. \\\n\t0 var y = 1\n\tvar ok = 2\n");
        var fn2Body = onlyFunction(superZeroVar).body().statements();
        assertEquals(2, fn2Body.size(), () -> fn2Body.toString());
        assertInstanceOf(ExpressionStatement.class, fn2Body.getFirst());
        assertEquals("ok", assertInstanceOf(VariableDeclaration.class, fn2Body.get(1)).name());

        var dictContinuation = recoverableParse("var d = {1 \\\n\t2}\n");
        assertEquals(List.of(DiagnosticCodes.MISSING_SEPARATOR_DICTIONARY),
                errorsOf(dictContinuation).stream().map(AstDiagnostic::code).toList());
        var dict = assertInstanceOf(DictionaryExpression.class,
                assertInstanceOf(VariableDeclaration.class, dictContinuation.ast().statements().getFirst()).value());
        assertEquals(1, dict.entries().size(), () -> dict.entries().toString());
        assertEquals("1", assertInstanceOf(LiteralExpression.class, dict.entries().getFirst().key()).sourceText());
        assertEquals("2", assertInstanceOf(LiteralExpression.class, dict.entries().getFirst().value()).sourceText());
    }

    @Test
    void unclosedDictionaryEscapeDoesNotClearSuppressedNewlineBeforeReplay() {
        var result = recoverableParse("func f():\n\tif true:\n\t\tvar d = {1\n\tprint(2)\n\tvar ok = 1\nvar top = 2\n");
        assertEquals(2, result.ast().statements().size(), () -> result.ast().statements().toString());
        var fn = assertInstanceOf(FunctionDeclaration.class, result.ast().statements().getFirst());
        assertEquals(3, fn.body().statements().size(), () -> fn.body().statements().toString());
        assertInstanceOf(IfStatement.class, fn.body().statements().getFirst());
        var printStmt = assertInstanceOf(ExpressionStatement.class, fn.body().statements().get(1));
        assertInstanceOf(CallExpression.class, printStmt.expression());
        assertEquals("ok", assertInstanceOf(VariableDeclaration.class, fn.body().statements().get(2)).name());
        assertEquals("top", assertInstanceOf(VariableDeclaration.class, result.ast().statements().get(1)).name());
    }

    @Test
    void signatureContinuationLookaheadSkipsCommentOnlyLinesAfterBackslash() {
        var unclosedOuter = recoverableParse("var x = g(func(a:\n\tint) \\\n\t# comment\n\t: pass\n");
        assertEquals(List.of(DiagnosticCodes.MISSING_CLOSER_CALL),
                errorsOf(unclosedOuter).stream().map(AstDiagnostic::code).toList());
        var call = assertInstanceOf(CallExpression.class,
                assertInstanceOf(VariableDeclaration.class, unclosedOuter.ast().statements().getFirst()).value());
        var lambda = assertInstanceOf(LambdaExpression.class, call.arguments().getFirst());
        assertEquals(1, lambda.parameters().size());
        assertEquals("a", lambda.parameters().getFirst().name());
        assertEquals("int", lambda.parameters().getFirst().type().sourceText());
        assertInstanceOf(PassStatement.class, lambda.body().statements().getFirst());

        var validOuter = parse("var x = g(func(a:\n\tint) \\\n\t# comment\n\t: pass)\n");
        assertEquals(List.of(), errorsOf(validOuter), () -> errorsOf(validOuter).toString());
    }

    @Test
    void circuitBreakerDrainsClaimedUnexpectedIndentsAndOwnSuiteDedent() {
        var source = "class C:\n\tfunc f():\n\t\tpass\n\t\t\t"
                + "var\n\t\t\t".repeat(102)
                + "var extra = 0\n\t\tvar ok = 1\n\tvar inner = 2\nvar top = 3\n";
        var result = recoverableParse(source);
        findErrorByCode(result, DiagnosticCodes.STATEMENT_TOO_MANY_ERRORS);
        assertEquals(2, result.ast().statements().size(), () -> result.ast().statements().toString());
        var innerClass = assertInstanceOf(ClassDeclaration.class, result.ast().statements().getFirst());
        assertEquals(2, innerClass.body().statements().size(), () -> innerClass.body().statements().toString());
        assertEquals("f", assertInstanceOf(FunctionDeclaration.class, innerClass.body().statements().getFirst()).name());
        assertEquals("inner", assertInstanceOf(VariableDeclaration.class, innerClass.body().statements().get(1)).name());
        assertEquals("top", assertInstanceOf(VariableDeclaration.class, result.ast().statements().get(1)).name());
    }

    @Test
    void indentedPropertyReferenceTrailingCommaReportsInvalidAccessor() {
        var singleTrailingComma = recoverableParse("var x:\n\tget = g,\nvar after = 1\n");
        assertEquals(2, singleTrailingComma.ast().statements().size(), () -> singleTrailingComma.ast().statements().toString());
        var prop1 = assertInstanceOf(VariableDeclaration.class, singleTrailingComma.ast().statements().getFirst());
        assertEquals("after", assertInstanceOf(VariableDeclaration.class, singleTrailingComma.ast().statements().get(1)).name());
        var diag1 = findErrorByCode(singleTrailingComma, DiagnosticCodes.PROPERTY_INVALID_ACCESSOR);
        assertEquals(new RecoveryLink(prop1, "variable.property"), diag1.recovery());

        var twoItemsTrailingComma = recoverableParse("var x:\n\tget = g,\n\tset = s,\nvar after = 1\n");
        assertEquals(2, twoItemsTrailingComma.ast().statements().size(), () -> twoItemsTrailingComma.ast().statements().toString());
        var prop2 = assertInstanceOf(VariableDeclaration.class, twoItemsTrailingComma.ast().statements().getFirst());
        assertEquals("after", assertInstanceOf(VariableDeclaration.class, twoItemsTrailingComma.ast().statements().get(1)).name());
        var diag2 = findErrorByCode(twoItemsTrailingComma, DiagnosticCodes.PROPERTY_INVALID_ACCESSOR);
        assertEquals(new RecoveryLink(prop2, "variable.property"), diag2.recovery());

        var valid = parse("var x:\n\tget = g,\n\tset = s\n");
        assertEquals(List.of(), errorsOf(valid), () -> errorsOf(valid).toString());
    }

    @Test
    void lambdaCircuitBreakerYieldsOuterCallCommaAndPreservesSubsequentDeclarations() {
        var source = "var x = g(func():\n" + "\tvar\n".repeat(102) + "\tpass, 2)\nvar after=1\n";
        var result = recoverableParse(source);
        findErrorByCode(result, DiagnosticCodes.STATEMENT_TOO_MANY_ERRORS);
        assertFalse(errorsOf(result).stream().anyMatch(d -> DiagnosticCodes.MISSING_CLOSER_CALL.equals(d.code())),
                () -> errorsOf(result).toString());
        assertEquals(2, result.ast().statements().size(), () -> result.ast().statements().toString());
        var call = assertInstanceOf(CallExpression.class,
                assertInstanceOf(VariableDeclaration.class, result.ast().statements().getFirst()).value());
        assertEquals(2, call.arguments().size(), () -> call.arguments().toString());
        assertInstanceOf(LambdaExpression.class, call.arguments().getFirst());
        assertEquals("2", assertInstanceOf(LiteralExpression.class, call.arguments().get(1)).sourceText());
        assertEquals("after", assertInstanceOf(VariableDeclaration.class, result.ast().statements().get(1)).name());
    }

    @Test
    void propertyRecoveryConsumesInternalCommentsWithoutLeakingIntoClassBody() {
        var internalComment = recoverableParse("class A:\n\tvar z:\n\t\tget = g # c\n\t\t\tpass\n");
        var clsA = assertInstanceOf(ClassDeclaration.class, internalComment.ast().statements().getFirst());
        assertEquals(1, clsA.body().statements().size(), () -> clsA.body().statements().toString());
        var propZ = assertInstanceOf(VariableDeclaration.class, clsA.body().statements().getFirst());
        assertEquals("z", propZ.name());
        assertTrue(clsA.body().range().startByte() <= propZ.range().startByte()
                        && propZ.range().endByte() <= clsA.body().range().endByte(),
                () -> "class body " + clsA.body().range() + " must enclose " + propZ.range());

        var outerComment = parse("class A:\n\tvar z:\n\t\tget = g\n\t# outer\n\tvar ok = 1\n");
        assertEquals(List.of(), errorsOf(outerComment), () -> errorsOf(outerComment).toString());
        var clsA2 = assertInstanceOf(ClassDeclaration.class, outerComment.ast().statements().getFirst());
        assertEquals(3, clsA2.body().statements().size(), () -> clsA2.body().statements().toString());
        assertEquals("z", assertInstanceOf(VariableDeclaration.class, clsA2.body().statements().getFirst()).name());
        assertEquals("# outer", assertInstanceOf(CommentStatement.class, clsA2.body().statements().get(1)).text());
        assertEquals("ok", assertInstanceOf(VariableDeclaration.class, clsA2.body().statements().get(2)).name());
    }

    @Test
    void backslashLookaheadRejectsTrailingSpaceOrInlineCommentBeforeNewline() {
        for (var source : List.of(
                "var x = g(func(a:\n\tpass\n) \\ \n: pass\nvar after=1\n",
                "var x = g(func(a:\n\tpass\n) \\ # c\n: pass\nvar after=1\n")) {
            var result = recoverableParse(source);
            findErrorByCode(result, DiagnosticCodes.MISSING_CLOSER_PARAMETER_LIST);
            var nonComments = result.ast().statements().stream()
                    .filter(s -> !(s instanceof CommentStatement))
                    .toList();
            assertEquals(2, nonComments.size(), () -> source + " -> " + nonComments);
            var call = assertInstanceOf(CallExpression.class,
                    assertInstanceOf(VariableDeclaration.class, nonComments.getFirst()).value());
            var lambda = assertInstanceOf(LambdaExpression.class, call.arguments().getFirst());
            assertTrue(lambda.body().statements().stream().anyMatch(PassStatement.class::isInstance),
                    () -> source + " -> " + lambda.body().statements());
            assertEquals("after", assertInstanceOf(VariableDeclaration.class, nonComments.get(1)).name());
        }
    }

    @Test
    void leadingUnknownPropertyAccessorDoesNotLockStyleForLaterValidAccessors() {
        for (var source : List.of(
                "var x:\n\toops = g\n\tget:\n\t\tpass\nvar after=1\n",
                "var x:\n\toops:\n\t\tpass\n\tget = g,\n\tset = s\nvar after=1\n")) {
            var result = recoverableParse(source);
            assertEquals(2, result.ast().statements().size(), () -> source + " -> " + result.ast().statements());
            var propX = assertInstanceOf(VariableDeclaration.class, result.ast().statements().getFirst());
            assertEquals("after", assertInstanceOf(VariableDeclaration.class, result.ast().statements().get(1)).name());
            var errors = errorsOf(result);
            assertEquals(1, errors.size(), () -> source + " -> " + errors);
            assertEquals(DiagnosticCodes.PROPERTY_INVALID_ACCESSOR, errors.getFirst().code());
            assertEquals(new RecoveryLink(propX, "variable.property"), errors.getFirst().recovery());
        }
    }

    @Test
    void lambdaCircuitBreakerKeepsMatchBranchCommaAndRespectsTypedAncestorClosers() {
        var matchBranchSource = "var x = g(func():\n" + "\tvar\n".repeat(102)
                + "\tmatch 1:\n\t\t1, 2: pass\n, 3)\nvar after=1\n";
        var matchRes = recoverableParse(matchBranchSource);
        findErrorByCode(matchRes, DiagnosticCodes.STATEMENT_TOO_MANY_ERRORS);
        assertFalse(errorsOf(matchRes).stream().anyMatch(d -> DiagnosticCodes.MISSING_CLOSER_CALL.equals(d.code())),
                () -> errorsOf(matchRes).toString());
        assertEquals(2, matchRes.ast().statements().size(), () -> matchRes.ast().statements().toString());
        var matchCall = assertInstanceOf(CallExpression.class,
                assertInstanceOf(VariableDeclaration.class, matchRes.ast().statements().getFirst()).value());
        assertEquals(2, matchCall.arguments().size(), () -> matchCall.arguments().toString());
        assertInstanceOf(LambdaExpression.class, matchCall.arguments().getFirst());
        assertEquals("3", assertInstanceOf(LiteralExpression.class, matchCall.arguments().get(1)).sourceText());
        assertEquals("after", assertInstanceOf(VariableDeclaration.class, matchRes.ast().statements().get(1)).name());

        var unclosedBracketSource = "var x = g(func():\n" + "\tvar\n".repeat(102) + "\t[1)\nvar after=1\n";
        var bracketRes = recoverableParse(unclosedBracketSource);
        findErrorByCode(bracketRes, DiagnosticCodes.STATEMENT_TOO_MANY_ERRORS);
        assertFalse(errorsOf(bracketRes).stream().anyMatch(d -> DiagnosticCodes.MISSING_CLOSER_CALL.equals(d.code())),
                () -> errorsOf(bracketRes).toString());
        assertEquals(2, bracketRes.ast().statements().size(), () -> bracketRes.ast().statements().toString());
        var bracketCall = assertInstanceOf(CallExpression.class,
                assertInstanceOf(VariableDeclaration.class, bracketRes.ast().statements().getFirst()).value());
        assertEquals(1, bracketCall.arguments().size(), () -> bracketCall.arguments().toString());
        assertInstanceOf(LambdaExpression.class, bracketCall.arguments().getFirst());
        assertEquals("after", assertInstanceOf(VariableDeclaration.class, bracketRes.ast().statements().get(1)).name());
    }

    @Test
    void incompletePropertyAccessorHeaderDoesNotLockStyleForSubsequentValidAccessors() {
        var missingGetterTarget = recoverableParse("var p:\n\tget =\n\tset(v):\n\t\tpass\nvar ok=1\n");
        assertEquals(2, missingGetterTarget.ast().statements().size(),
                () -> missingGetterTarget.ast().statements().toString());
        assertEquals("ok", assertInstanceOf(VariableDeclaration.class, missingGetterTarget.ast().statements().get(1)).name());
        var errors1 = errorMessages(missingGetterTarget);
        assertTrue(errors1.contains("Expected getter function name after \"=\"."), () -> errors1.toString());
        assertFalse(errors1.contains("Expected \"=\" after \"set\""), () -> errors1.toString());

        var unclosedSetterHeader = recoverableParse("var p:\n\tset(v\n\tget = g,\n\tset = s\nvar ok=1\n");
        assertEquals(2, unclosedSetterHeader.ast().statements().size(),
                () -> unclosedSetterHeader.ast().statements().toString());
        assertEquals("ok", assertInstanceOf(VariableDeclaration.class, unclosedSetterHeader.ast().statements().get(1)).name());
        var errors2 = errorMessages(unclosedSetterHeader);
        assertFalse(errors2.contains("Expected \"(\" after \"set\"."), () -> errors2.toString());
        assertFalse(errors2.contains("Expected \":\" or \"(\" after \"get\"."), () -> errors2.toString());
    }

    @Test
    void lambdaCircuitBreakerIgnoresAttributeMatchCallAndSettlesUnclosedLocalBracketsOnHardLineStarts() {
        var attrMatchSource = "var x = g(func():\n" + "\tvar\n".repeat(102)
                + "\tif obj.match():\n\t\tpass, 2)\nvar after=1\n";
        var attrMatchRes = recoverableParse(attrMatchSource);
        findErrorByCode(attrMatchRes, DiagnosticCodes.STATEMENT_TOO_MANY_ERRORS);
        assertFalse(errorsOf(attrMatchRes).stream().anyMatch(d -> DiagnosticCodes.MISSING_CLOSER_CALL.equals(d.code())),
                () -> errorsOf(attrMatchRes).toString());
        assertEquals(2, attrMatchRes.ast().statements().size(), () -> attrMatchRes.ast().statements().toString());
        var attrMatchCall = assertInstanceOf(CallExpression.class,
                assertInstanceOf(VariableDeclaration.class, attrMatchRes.ast().statements().getFirst()).value());
        assertEquals(2, attrMatchCall.arguments().size(), () -> attrMatchCall.arguments().toString());
        assertInstanceOf(LambdaExpression.class, attrMatchCall.arguments().getFirst());
        assertEquals("2", assertInstanceOf(LiteralExpression.class, attrMatchCall.arguments().get(1)).sourceText());
        assertEquals("after", assertInstanceOf(VariableDeclaration.class, attrMatchRes.ast().statements().get(1)).name());

        for (var unclosedSource : List.of(
                "var x = g(func():\n" + "\tvar\n".repeat(102) + "\tvar z = (1\n\tpass, 2)\nvar after=1\n",
                "var x = g(func():\n" + "\tvar\n".repeat(102) + "\tvar z = [1\n\tpass, 2)\nvar after=1\n")) {
            var res = recoverableParse(unclosedSource);
            findErrorByCode(res, DiagnosticCodes.STATEMENT_TOO_MANY_ERRORS);
            assertFalse(errorsOf(res).stream().anyMatch(d -> DiagnosticCodes.MISSING_CLOSER_CALL.equals(d.code())),
                    () -> unclosedSource + " -> " + errorsOf(res));
            assertEquals(2, res.ast().statements().size(), () -> unclosedSource + " -> " + res.ast().statements());
            var call = assertInstanceOf(CallExpression.class,
                    assertInstanceOf(VariableDeclaration.class, res.ast().statements().getFirst()).value());
            assertEquals(2, call.arguments().size(), () -> unclosedSource + " -> " + call.arguments());
            assertInstanceOf(LambdaExpression.class, call.arguments().getFirst());
            assertEquals("2", assertInstanceOf(LiteralExpression.class, call.arguments().get(1)).sourceText());
            assertEquals("after", assertInstanceOf(VariableDeclaration.class, res.ast().statements().get(1)).name());
        }

        var validMultilineGroupingSource = "var x = g(func():\n" + "\tvar\n".repeat(102)
                + "\tvar z = (\n\t\t1,\n\t\t2\n\t), 3)\nvar after=1\n";
        var validGroupingRes = recoverableParse(validMultilineGroupingSource);
        findErrorByCode(validGroupingRes, DiagnosticCodes.STATEMENT_TOO_MANY_ERRORS);
        assertFalse(errorsOf(validGroupingRes).stream().anyMatch(d -> DiagnosticCodes.MISSING_CLOSER_CALL.equals(d.code())),
                () -> errorsOf(validGroupingRes).toString());
        assertEquals(2, validGroupingRes.ast().statements().size(), () -> validGroupingRes.ast().statements().toString());
        var groupingCall = assertInstanceOf(CallExpression.class,
                assertInstanceOf(VariableDeclaration.class, validGroupingRes.ast().statements().getFirst()).value());
        assertEquals(2, groupingCall.arguments().size(), () -> groupingCall.arguments().toString());
        assertInstanceOf(LambdaExpression.class, groupingCall.arguments().getFirst());
        assertEquals("3", assertInstanceOf(LiteralExpression.class, groupingCall.arguments().get(1)).sourceText());
        assertEquals("after", assertInstanceOf(VariableDeclaration.class, validGroupingRes.ast().statements().get(1)).name());
    }

    @Test
    void lambdaCircuitBreakerHandlesMultilineMatchHeaderExitedSubsuiteAndIfLineContinuations() {
        var prefix = "var x = g(func():\n" + "\tvar\n".repeat(102);

        var multilineMatchRes = recoverableParse(
                prefix + "\tmatch (\n\t\t1\n\t):\n\t\t1, 2: pass\n, 3)\nvar after=1\n");
        findErrorByCode(multilineMatchRes, DiagnosticCodes.STATEMENT_TOO_MANY_ERRORS);
        assertFalse(errorMessages(multilineMatchRes).stream().anyMatch(m -> m.contains("Unexpected \":\"")),
                () -> errorMessages(multilineMatchRes).toString());
        assertEquals(2, multilineMatchRes.ast().statements().size(),
                () -> multilineMatchRes.ast().statements().toString());
        var multilineMatchCall = assertInstanceOf(CallExpression.class,
                assertInstanceOf(VariableDeclaration.class, multilineMatchRes.ast().statements().getFirst()).value());
        assertEquals(2, multilineMatchCall.arguments().size(), () -> multilineMatchCall.arguments().toString());
        assertInstanceOf(LambdaExpression.class, multilineMatchCall.arguments().getFirst());
        assertEquals("3", assertInstanceOf(LiteralExpression.class, multilineMatchCall.arguments().get(1)).sourceText());
        assertEquals("after", assertInstanceOf(VariableDeclaration.class, multilineMatchRes.ast().statements().get(1)).name());

        for (var exitedSubsuiteNegative : List.of(
                prefix + "\tif true:\n\t\tvar z = (1\n\t, 2)\nvar after=1\n",
                prefix + "\tif true:\n\t\tvar z = [1\n\t, 2)\nvar after=1\n")) {
            var res = recoverableParse(exitedSubsuiteNegative);
            findErrorByCode(res, DiagnosticCodes.STATEMENT_TOO_MANY_ERRORS);
            assertFalse(errorsOf(res).stream().anyMatch(d -> DiagnosticCodes.MISSING_CLOSER_CALL.equals(d.code())),
                    () -> exitedSubsuiteNegative + " -> " + errorsOf(res));
            assertEquals(2, res.ast().statements().size(), () -> exitedSubsuiteNegative + " -> " + res.ast().statements());
            var call = assertInstanceOf(CallExpression.class,
                    assertInstanceOf(VariableDeclaration.class, res.ast().statements().getFirst()).value());
            assertEquals(2, call.arguments().size(), () -> exitedSubsuiteNegative + " -> " + call.arguments());
            assertInstanceOf(LambdaExpression.class, call.arguments().getFirst());
            assertEquals("2", assertInstanceOf(LiteralExpression.class, call.arguments().get(1)).sourceText());
            assertEquals("after", assertInstanceOf(VariableDeclaration.class, res.ast().statements().get(1)).name());
        }

        var pairedParenRes = recoverableParse(prefix + "\tif true:\n\t\tvar z = (\n\t1\n\t), 2)\nvar after=1\n");
        findErrorByCode(pairedParenRes, DiagnosticCodes.STATEMENT_TOO_MANY_ERRORS);
        assertFalse(errorsOf(pairedParenRes).stream().anyMatch(d -> DiagnosticCodes.MISSING_CLOSER_CALL.equals(d.code())),
                () -> errorsOf(pairedParenRes).toString());
        assertEquals(2, pairedParenRes.ast().statements().size());
        var pairedParenCall = assertInstanceOf(CallExpression.class,
                assertInstanceOf(VariableDeclaration.class, pairedParenRes.ast().statements().getFirst()).value());
        assertEquals(2, pairedParenCall.arguments().size());
        assertEquals("2", assertInstanceOf(LiteralExpression.class, pairedParenCall.arguments().get(1)).sourceText());
        assertEquals("after", assertInstanceOf(VariableDeclaration.class, pairedParenRes.ast().statements().get(1)).name());

        var pairedCallRes = recoverableParse(prefix + "\tif true:\n\t\tvar z = f(\n\t1\n\t, 2\n\t), 3)\nvar after=1\n");
        findErrorByCode(pairedCallRes, DiagnosticCodes.STATEMENT_TOO_MANY_ERRORS);
        assertFalse(errorsOf(pairedCallRes).stream().anyMatch(d -> DiagnosticCodes.MISSING_CLOSER_CALL.equals(d.code())),
                () -> errorsOf(pairedCallRes).toString());
        assertEquals(2, pairedCallRes.ast().statements().size());
        var pairedCallOuter = assertInstanceOf(CallExpression.class,
                assertInstanceOf(VariableDeclaration.class, pairedCallRes.ast().statements().getFirst()).value());
        assertEquals(2, pairedCallOuter.arguments().size());
        assertEquals("3", assertInstanceOf(LiteralExpression.class, pairedCallOuter.arguments().get(1)).sourceText());
        assertEquals("after", assertInstanceOf(VariableDeclaration.class, pairedCallRes.ast().statements().get(1)).name());

        for (var ifHeaderPositive : List.of(
                prefix + "\tvar z = (1\n\tif \"a\nb\":\n\t\tpass, 2)\nvar after=1\n",
                prefix + "\tvar z = (1\n\tif true \\\n\t# c\n\t:\n\t\tpass, 2)\nvar after=1\n")) {
            var res = recoverableParse(ifHeaderPositive);
            findErrorByCode(res, DiagnosticCodes.STATEMENT_TOO_MANY_ERRORS);
            assertFalse(errorsOf(res).stream().anyMatch(d -> DiagnosticCodes.MISSING_CLOSER_CALL.equals(d.code())),
                    () -> ifHeaderPositive + " -> " + errorsOf(res));
            assertEquals(2, res.ast().statements().size(), () -> ifHeaderPositive + " -> " + res.ast().statements());
            var call = assertInstanceOf(CallExpression.class,
                    assertInstanceOf(VariableDeclaration.class, res.ast().statements().getFirst()).value());
            assertEquals(2, call.arguments().size(), () -> ifHeaderPositive + " -> " + call.arguments());
            assertEquals("2", assertInstanceOf(LiteralExpression.class, call.arguments().get(1)).sourceText());
            assertEquals("after", assertInstanceOf(VariableDeclaration.class, res.ast().statements().get(1)).name());
        }

        var invalidBackslashIfRes = recoverableParse(
                prefix + "\tvar z = (1\n\tif true \\ # bad\n\t:\n\t\tpass, 2), 3)\nvar after=1\n");
        findErrorByCode(invalidBackslashIfRes, DiagnosticCodes.STATEMENT_TOO_MANY_ERRORS);
        assertEquals(2, invalidBackslashIfRes.ast().statements().size(),
                () -> invalidBackslashIfRes.ast().statements().toString());
        var invalidBackslashCall = assertInstanceOf(CallExpression.class,
                assertInstanceOf(VariableDeclaration.class, invalidBackslashIfRes.ast().statements().getFirst()).value());
        assertEquals(2, invalidBackslashCall.arguments().size(), () -> invalidBackslashCall.arguments().toString());
        assertEquals("3", assertInstanceOf(LiteralExpression.class, invalidBackslashCall.arguments().get(1)).sourceText());
        assertEquals("after", assertInstanceOf(VariableDeclaration.class, invalidBackslashIfRes.ast().statements().get(1)).name());
    }

    @Test
    void propertyBadAccessorContinuesSiblingAccessorsAndUnknownFormDoesNotLockStyle() {
        for (var source : List.of(
                "var p: get = g, 123, get = h\nvar after = 1\n",
                "var p: int: 123, get = g, get = h\nvar after = 1\n")) {
            var res = recoverableParse(source);
            assertEquals(2, res.ast().statements().size(), () -> source + " -> " + res.ast().statements());
            var prop = assertInstanceOf(VariableDeclaration.class, res.ast().statements().getFirst());
            assertEquals("after", assertInstanceOf(VariableDeclaration.class, res.ast().statements().get(1)).name());
            findErrorByCode(res, DiagnosticCodes.PROPERTY_INVALID_ACCESSOR);
            findErrorByCode(res, DiagnosticCodes.PROPERTY_DUPLICATE_GETTER);
            assertFalse(errorMessages(res).stream().anyMatch(m -> m.contains("end of property")),
                    () -> source + " -> " + errorMessages(res));
            assertEquals(source.indexOf("get = h") + "get = h".length(), prop.range().endByte());
        }

        var indentedUnknownRes = recoverableParse("var p:\n\toops = f, get = g, get = h\nvar after = 1\n");
        assertEquals(2, indentedUnknownRes.ast().statements().size(),
                () -> indentedUnknownRes.ast().statements().toString());
        assertEquals("after", assertInstanceOf(VariableDeclaration.class, indentedUnknownRes.ast().statements().get(1)).name());
        findErrorByCode(indentedUnknownRes, DiagnosticCodes.PROPERTY_INVALID_ACCESSOR);
        findErrorByCode(indentedUnknownRes, DiagnosticCodes.PROPERTY_DUPLICATE_GETTER);

        var unknownFollowedByBlockRes = recoverableParse(
                "var p:\n\toops = f\n\tget:\n\t\treturn 1\nvar after = 1\n");
        assertEquals(2, unknownFollowedByBlockRes.ast().statements().size(),
                () -> unknownFollowedByBlockRes.ast().statements().toString());
        assertEquals("after", assertInstanceOf(VariableDeclaration.class, unknownFollowedByBlockRes.ast().statements().get(1)).name());
        var errors = errorsOf(unknownFollowedByBlockRes);
        assertEquals(1, errors.size(), () -> errors.toString());
        assertEquals(DiagnosticCodes.PROPERTY_INVALID_ACCESSOR, errors.getFirst().code());
        assertFalse(errorMessages(unknownFollowedByBlockRes).stream().anyMatch(m -> m.contains("Expected \"=\" after \"get\"")),
                () -> errorMessages(unknownFollowedByBlockRes).toString());
    }

    @Test
    void circuitBreakerLocalBracketStackOperationsStayLinear() {
        var n = 2_000;
        var source = "func f():\n" + "\tvar\n".repeat(102) + "\t" + "(".repeat(n) + "1" + "]".repeat(n) + "\nvar after = 1\n";
        var parser = new GdScriptParser();
        var result = parser.parse(source);
        assertEquals(2, result.ast().statements().size(), () -> result.ast().statements().toString());
        assertEquals("after", assertInstanceOf(VariableDeclaration.class, result.ast().statements().get(1)).name());
        assertTrue(parser.circuitBreakerStackOps() <= 4 * n,
                () -> "Expected linear circuit-breaker stack ops <= " + (4 * n) + " but got " + parser.circuitBreakerStackOps());
    }

    @Test
    void lambdaCircuitBreakerHandlesContinuationIndentedMatchAndTypedCloserIsolation() {
        var prefix = "var x = g(func():\n" + "\tvar\n".repeat(102);

        for (var matchSource : List.of(
                prefix + "\tmatch (\n\t\t1):\n\t\t1, 2: pass\n, 3)\nvar after = 1\n",
                prefix + "\tmatch (\n\t\t1):\n\t\t\t1, 2: pass\n, 3)\nvar after = 1\n")) {
            var res = recoverableParse(matchSource);
            findErrorByCode(res, DiagnosticCodes.STATEMENT_TOO_MANY_ERRORS);
            assertFalse(errorMessages(res).stream().anyMatch(m -> m.contains("Unexpected \":\"")),
                    () -> matchSource + " -> " + errorMessages(res));
            assertEquals(2, res.ast().statements().size(), () -> matchSource + " -> " + res.ast().statements());
            var call = assertInstanceOf(CallExpression.class,
                    assertInstanceOf(VariableDeclaration.class, res.ast().statements().getFirst()).value());
            assertEquals(2, call.arguments().size(), () -> matchSource + " -> " + call.arguments());
            assertInstanceOf(LambdaExpression.class, call.arguments().getFirst());
            assertEquals("3", assertInstanceOf(LiteralExpression.class, call.arguments().get(1)).sourceText());
            assertEquals("after", assertInstanceOf(VariableDeclaration.class, res.ast().statements().get(1)).name());
        }

        var mismatchedTypeSource = prefix + "\tif true:\n\t\tvar z = [\n\t\t\t1\n\t, 2]\nvar after = 1\n";
        var arrayRes = recoverableParse(mismatchedTypeSource);
        findErrorByCode(arrayRes, DiagnosticCodes.STATEMENT_TOO_MANY_ERRORS);
        findErrorByCode(arrayRes, DiagnosticCodes.MISSING_CLOSER_CALL);
        assertFalse(errorsOf(arrayRes).stream().anyMatch(d -> DiagnosticCodes.MISMATCHED_CLOSER_CALL.equals(d.code())),
                () -> errorsOf(arrayRes).toString());
        assertEquals(2, arrayRes.ast().statements().size(), () -> arrayRes.ast().statements().toString());
        var arrayCall = assertInstanceOf(CallExpression.class,
                assertInstanceOf(VariableDeclaration.class, arrayRes.ast().statements().getFirst()).value());
        assertEquals(1, arrayCall.arguments().size(), () -> arrayCall.arguments().toString());
        assertInstanceOf(LambdaExpression.class, arrayCall.arguments().getFirst());
        assertEquals("after", assertInstanceOf(VariableDeclaration.class, arrayRes.ast().statements().get(1)).name());
    }

    @Test
    void bareUnknownPropertyItemContinuesSiblingAccessorsWithoutDisruptingBlockForm() {
        var inlineBareUnknown = recoverableParse("var p: int: oops, get = g, get = h\nvar after = 1\n");
        assertEquals(2, inlineBareUnknown.ast().statements().size(),
                () -> inlineBareUnknown.ast().statements().toString());
        assertEquals("after", assertInstanceOf(VariableDeclaration.class, inlineBareUnknown.ast().statements().get(1)).name());
        findErrorByCode(inlineBareUnknown, DiagnosticCodes.PROPERTY_INVALID_ACCESSOR);
        findErrorByCode(inlineBareUnknown, DiagnosticCodes.PROPERTY_DUPLICATE_GETTER);
        assertFalse(errorMessages(inlineBareUnknown).stream().anyMatch(m -> m.contains("Unexpected \"Newline\"")),
                () -> errorMessages(inlineBareUnknown).toString());

        var indentedBareUnknown = recoverableParse("var p:\n\toops, get = g, get = h\nvar after = 1\n");
        assertEquals(2, indentedBareUnknown.ast().statements().size(),
                () -> indentedBareUnknown.ast().statements().toString());
        assertEquals("after", assertInstanceOf(VariableDeclaration.class, indentedBareUnknown.ast().statements().get(1)).name());
        findErrorByCode(indentedBareUnknown, DiagnosticCodes.PROPERTY_INVALID_ACCESSOR);
        findErrorByCode(indentedBareUnknown, DiagnosticCodes.PROPERTY_DUPLICATE_GETTER);

        for (var blockControl : List.of(
                "var p:\n\toops:\n\t\tpass\n\tget:\n\t\treturn 1\nvar after = 1\n",
                "var p:\n\tget:\n\t\treturn 1\n\toops:\n\t\tpass\nvar after = 1\n")) {
            var res = recoverableParse(blockControl);
            assertEquals(2, res.ast().statements().size(), () -> blockControl + " -> " + res.ast().statements());
            assertEquals("after", assertInstanceOf(VariableDeclaration.class, res.ast().statements().get(1)).name());
            var errors = errorsOf(res);
            assertEquals(1, errors.size(), () -> blockControl + " -> " + errors);
            assertEquals(DiagnosticCodes.PROPERTY_INVALID_ACCESSOR, errors.getFirst().code());
        }
    }

    @Test
    void lambdaCircuitBreakerHandlesSteppedMatchHeaderEmptyMatchAndPairedCloserProtection() {
        var prefix = "var x = g(func():\n" + "\tvar\n".repeat(102);

        for (var matchSource : List.of(
                prefix + "\tmatch (\n\t\t(\n\t\t\t1)):\n\t\t1, 2: pass\n, 3)\nvar after = 1\n",
                prefix + "\tmatch (\n\t\t1):\n, 3)\nvar after = 1\n")) {
            var res = recoverableParse(matchSource);
            findErrorByCode(res, DiagnosticCodes.STATEMENT_TOO_MANY_ERRORS);
            assertFalse(errorMessages(res).stream().anyMatch(m -> m.contains("Unexpected \":\"")),
                    () -> matchSource + " -> " + errorMessages(res));
            assertEquals(2, res.ast().statements().size(), () -> matchSource + " -> " + res.ast().statements());
            var call = assertInstanceOf(CallExpression.class,
                    assertInstanceOf(VariableDeclaration.class, res.ast().statements().getFirst()).value());
            assertEquals(2, call.arguments().size(), () -> matchSource + " -> " + call.arguments());
            assertInstanceOf(LambdaExpression.class, call.arguments().getFirst());
            assertEquals("3", assertInstanceOf(LiteralExpression.class, call.arguments().get(1)).sourceText());
            assertEquals("after", assertInstanceOf(VariableDeclaration.class, res.ast().statements().get(1)).name());
        }

        for (var pairedInnerSource : List.of(
                prefix + "\tif true:\n\t\tvar z = f(\n\t\t\t1\n\t, 2\n\t\t)\n\tpass\nvar after = 1\n",
                prefix + "\tif true:\n\t\tvar z = f(\n\t\t\t1\n\t, 2\n\t)\n\tpass\nvar after = 1\n")) {
            var res = recoverableParse(pairedInnerSource);
            findErrorByCode(res, DiagnosticCodes.STATEMENT_TOO_MANY_ERRORS);
            findErrorByCode(res, DiagnosticCodes.MISSING_CLOSER_CALL);
            assertFalse(errorMessages(res).stream().anyMatch(m -> m.contains("Unexpected \"Indent\"")),
                    () -> pairedInnerSource + " -> " + errorMessages(res));
            assertEquals(2, res.ast().statements().size(), () -> pairedInnerSource + " -> " + res.ast().statements());
            var call = assertInstanceOf(CallExpression.class,
                    assertInstanceOf(VariableDeclaration.class, res.ast().statements().getFirst()).value());
            assertEquals(1, call.arguments().size(), () -> pairedInnerSource + " -> " + call.arguments());
            assertInstanceOf(LambdaExpression.class, call.arguments().getFirst());
            assertEquals("after", assertInstanceOf(VariableDeclaration.class, res.ast().statements().get(1)).name());
        }

        var topCloserStolenSource = prefix + "\tif true:\n\t\tvar z = (1\n\t, 2\n)\nvar after = 1\n";
        var topCloserRes = recoverableParse(topCloserStolenSource);
        findErrorByCode(topCloserRes, DiagnosticCodes.STATEMENT_TOO_MANY_ERRORS);
        assertFalse(errorsOf(topCloserRes).stream().anyMatch(d -> DiagnosticCodes.MISSING_CLOSER_CALL.equals(d.code())),
                () -> errorsOf(topCloserRes).toString());
        assertEquals(2, topCloserRes.ast().statements().size(), () -> topCloserRes.ast().statements().toString());
        var topCall = assertInstanceOf(CallExpression.class,
                assertInstanceOf(VariableDeclaration.class, topCloserRes.ast().statements().getFirst()).value());
        assertEquals(2, topCall.arguments().size(), () -> topCall.arguments().toString());
        assertInstanceOf(LambdaExpression.class, topCall.arguments().getFirst());
        assertEquals("2", assertInstanceOf(LiteralExpression.class, topCall.arguments().get(1)).sourceText());
        assertEquals("after", assertInstanceOf(VariableDeclaration.class, topCloserRes.ast().statements().get(1)).name());
    }

    @Test
    void lambdaCircuitBreakerProtectsMultilineMatchPatternAndCommaOwningCall() {
        var prefix = "var x = g(func():\n" + "\tvar\n".repeat(102);

        for (var matchSource : List.of(
                prefix + "\tmatch 1:\n\t\t[\n\t\t\t1\n\t\t\t], 2: pass\n, 3)\nvar after = 1\n",
                prefix + "\tmatch 1:\n\t\t[1\n, 3)\nvar after = 1\n")) {
            var res = recoverableParse(matchSource);
            findErrorByCode(res, DiagnosticCodes.STATEMENT_TOO_MANY_ERRORS);
            assertFalse(errorMessages(res).stream().anyMatch(m -> m.contains("Unexpected \":\"")),
                    () -> matchSource + " -> " + errorMessages(res));
            assertEquals(2, res.ast().statements().size(), () -> matchSource + " -> " + res.ast().statements());
            var call = assertInstanceOf(CallExpression.class,
                    assertInstanceOf(VariableDeclaration.class, res.ast().statements().getFirst()).value());
            assertEquals(2, call.arguments().size(), () -> matchSource + " -> " + call.arguments());
            assertInstanceOf(LambdaExpression.class, call.arguments().getFirst());
            assertEquals("3", assertInstanceOf(LiteralExpression.class, call.arguments().get(1)).sourceText());
            assertEquals("after", assertInstanceOf(VariableDeclaration.class, res.ast().statements().get(1)).name());
        }

        var pairedCallSource = prefix + "\tif true:\n\t\tvar z = f(1\n\t, 2)\n\tpass\nvar after = 1\n";
        var pairedCallRes = recoverableParse(pairedCallSource);
        findErrorByCode(pairedCallRes, DiagnosticCodes.STATEMENT_TOO_MANY_ERRORS);
        findErrorByCode(pairedCallRes, DiagnosticCodes.MISSING_CLOSER_CALL);
        assertFalse(errorMessages(pairedCallRes).stream().anyMatch(m -> m.contains("Unexpected \"Indent\"")),
                () -> errorMessages(pairedCallRes).toString());
        assertEquals(2, pairedCallRes.ast().statements().size(), () -> pairedCallRes.ast().statements().toString());
        var pairedCall = assertInstanceOf(CallExpression.class,
                assertInstanceOf(VariableDeclaration.class, pairedCallRes.ast().statements().getFirst()).value());
        assertEquals(1, pairedCall.arguments().size(), () -> pairedCall.arguments().toString());
        assertInstanceOf(LambdaExpression.class, pairedCall.arguments().getFirst());
        assertEquals("after", assertInstanceOf(VariableDeclaration.class, pairedCallRes.ast().statements().get(1)).name());

        for (var groupingSource : List.of(
                prefix + "\tif true:\n\t\tvar z = (1\n\t, 2)\nvar after = 1\n",
                prefix + "\tif true:\n\t\tvar z = (\"a\nb\"\n\t, 2)\nvar after = 1\n")) {
            var groupingRes = recoverableParse(groupingSource);
            findErrorByCode(groupingRes, DiagnosticCodes.STATEMENT_TOO_MANY_ERRORS);
            assertFalse(errorsOf(groupingRes).stream().anyMatch(d -> DiagnosticCodes.MISSING_CLOSER_CALL.equals(d.code())),
                    () -> groupingSource + " -> " + errorsOf(groupingRes));
            assertEquals(2, groupingRes.ast().statements().size(), () -> groupingSource + " -> " + groupingRes.ast().statements());
            var groupingCall = assertInstanceOf(CallExpression.class,
                    assertInstanceOf(VariableDeclaration.class, groupingRes.ast().statements().getFirst()).value());
            assertEquals(2, groupingCall.arguments().size(), () -> groupingSource + " -> " + groupingCall.arguments());
            assertInstanceOf(LambdaExpression.class, groupingCall.arguments().getFirst());
            assertEquals("2", assertInstanceOf(LiteralExpression.class, groupingCall.arguments().get(1)).sourceText());
            assertEquals("after", assertInstanceOf(VariableDeclaration.class, groupingRes.ast().statements().get(1)).name());
        }
    }

    @Test
    void blockPropertyBadItemsStopAtSiblingCommaWithoutSplittingNestedParenCommas() {
        for (var badWithComma : List.of(
                "var p:\n\tget:\n\t\tpass\n\toops, get:\n\t\tpass\nvar after = 1\n",
                "var p:\n\tget:\n\t\tpass\n\t123, get:\n\t\tpass\nvar after = 1\n")) {
            var res = recoverableParse(badWithComma);
            assertEquals(2, res.ast().statements().size(), () -> badWithComma + " -> " + res.ast().statements());
            assertEquals("after", assertInstanceOf(VariableDeclaration.class, res.ast().statements().get(1)).name());
            findErrorByCode(res, DiagnosticCodes.PROPERTY_INVALID_ACCESSOR);
            findErrorByCode(res, DiagnosticCodes.PROPERTY_DUPLICATE_GETTER);
        }

        var nestedCommaControl = recoverableParse(
                "var p:\n\toops(1, 2):\n\t\tpass\n\tget:\n\t\treturn 1\nvar after = 1\n");
        assertEquals(2, nestedCommaControl.ast().statements().size(),
                () -> nestedCommaControl.ast().statements().toString());
        assertEquals("after", assertInstanceOf(VariableDeclaration.class, nestedCommaControl.ast().statements().get(1)).name());
        var errors = errorsOf(nestedCommaControl);
        assertEquals(1, errors.size(), () -> errors.toString());
        assertEquals(DiagnosticCodes.PROPERTY_INVALID_ACCESSOR, errors.getFirst().code());
    }

    @Test
    void circuitBreakerOracleResolutionPerExitedSubsuiteOpenerStaysConstant() {
        var n = 2_000;
        var prefix = "var x = g(func():\n" + "\tvar\n".repeat(102);

        var callSource = prefix + "\tif true:\n\t\tvar z = f(1\n\t"
                + ", 2".repeat(n) + "\n\t)\n\tpass\n" + "\n".repeat(n) + "var after = 1\n";
        var callParser = new GdScriptParser();
        var callRes = callParser.parse(callSource);
        assertTrue(callParser.circuitBreakerOracleResolutions() <= 4,
                () -> "Expected O(1) oracle resolutions <= 4, got " + callParser.circuitBreakerOracleResolutions());
        findErrorByCode(callRes, DiagnosticCodes.MISSING_CLOSER_CALL);
        assertEquals(2, callRes.ast().statements().size(), () -> callRes.ast().statements().toString());
        var call = assertInstanceOf(CallExpression.class,
                assertInstanceOf(VariableDeclaration.class, callRes.ast().statements().getFirst()).value());
        assertEquals(1, call.arguments().size(), () -> call.arguments().toString());
        assertEquals("after", assertInstanceOf(VariableDeclaration.class, callRes.ast().statements().get(1)).name());

        var groupingSource = prefix + "\tif true:\n\t\tvar z = (1\n\t"
                + ", 2".repeat(n) + "\n\t)\n)\nvar after = 1\n";
        var groupingParser = new GdScriptParser();
        var groupingRes = groupingParser.parse(groupingSource);
        assertTrue(groupingParser.circuitBreakerOracleResolutions() <= 4,
                () -> "Expected O(1) oracle resolutions <= 4, got " + groupingParser.circuitBreakerOracleResolutions());
        assertEquals(2, groupingRes.ast().statements().size(), () -> groupingRes.ast().statements().toString());
        assertEquals("after", assertInstanceOf(VariableDeclaration.class, groupingRes.ast().statements().get(1)).name());
    }

    @Test
    void lambdaCircuitBreakerDistinguishesSubscriptBracketsAndKeywordMemberCalls() {
        var arrayPrefix = "var x = [func():\n" + "\tvar\n".repeat(102);

        for (var subscriptSource : List.of(
                arrayPrefix + "\tif true:\n\t\tvar z = a[1\n\t, 2]\nvar after = 1\n",
                arrayPrefix + "\tif true:\n\t\tvar z = obj.match[1\n\t, 2]\nvar after = 1\n")) {
            var res = recoverableParse(subscriptSource);
            findErrorByCode(res, DiagnosticCodes.STATEMENT_TOO_MANY_ERRORS);
            assertFalse(errorsOf(res).stream().anyMatch(d -> DiagnosticCodes.MISSING_CLOSER_ARRAY.equals(d.code())),
                    () -> subscriptSource + " -> " + errorsOf(res));
            assertEquals(2, res.ast().statements().size(), () -> subscriptSource + " -> " + res.ast().statements());
            var array = assertInstanceOf(ArrayExpression.class,
                    assertInstanceOf(VariableDeclaration.class, res.ast().statements().getFirst()).value());
            assertEquals(2, array.elements().size(), () -> subscriptSource + " -> " + array.elements());
            assertInstanceOf(LambdaExpression.class, array.elements().getFirst());
            assertEquals("2", assertInstanceOf(LiteralExpression.class, array.elements().get(1)).sourceText());
            assertEquals("after", assertInstanceOf(VariableDeclaration.class, res.ast().statements().get(1)).name());
        }

        for (var commaOwningBracketSource : List.of(
                arrayPrefix + "\tif true:\n\t\tvar z = [1\n\t, 2]\n\tpass\nvar after = 1\n",
                arrayPrefix + "\tif true:\n\t\tvar d: Dictionary[String\n\t, int]\n\tpass\nvar after = 1\n")) {
            var res = recoverableParse(commaOwningBracketSource);
            findErrorByCode(res, DiagnosticCodes.STATEMENT_TOO_MANY_ERRORS);
            findErrorByCode(res, DiagnosticCodes.MISSING_CLOSER_ARRAY);
            assertFalse(errorMessages(res).stream().anyMatch(m -> m.contains("Unexpected \"Indent\"")),
                    () -> commaOwningBracketSource + " -> " + errorMessages(res));
            assertEquals(2, res.ast().statements().size(), () -> commaOwningBracketSource + " -> " + res.ast().statements());
            var array = assertInstanceOf(ArrayExpression.class,
                    assertInstanceOf(VariableDeclaration.class, res.ast().statements().getFirst()).value());
            assertEquals(1, array.elements().size(), () -> commaOwningBracketSource + " -> " + array.elements());
            assertInstanceOf(LambdaExpression.class, array.elements().getFirst());
            assertEquals("after", assertInstanceOf(VariableDeclaration.class, res.ast().statements().get(1)).name());
        }

        var callPrefix = "var x = g(func():\n" + "\tvar\n".repeat(102);

        for (var memberCallSource : List.of(
                callPrefix + "\tif true:\n\t\tvar z = obj.match(1\n\t, 2)\n\tpass\nvar after = 1\n",
                callPrefix + "\tif true:\n\t\tvar z = obj.if(1\n\t, 2)\n\tpass\nvar after = 1\n")) {
            var res = recoverableParse(memberCallSource);
            findErrorByCode(res, DiagnosticCodes.STATEMENT_TOO_MANY_ERRORS);
            findErrorByCode(res, DiagnosticCodes.MISSING_CLOSER_CALL);
            assertFalse(errorMessages(res).stream().anyMatch(m -> m.contains("Unexpected \"Indent\"")),
                    () -> memberCallSource + " -> " + errorMessages(res));
            assertEquals(2, res.ast().statements().size(), () -> memberCallSource + " -> " + res.ast().statements());
            var call = assertInstanceOf(CallExpression.class,
                    assertInstanceOf(VariableDeclaration.class, res.ast().statements().getFirst()).value());
            assertEquals(1, call.arguments().size(), () -> memberCallSource + " -> " + call.arguments());
            assertInstanceOf(LambdaExpression.class, call.arguments().getFirst());
            assertEquals("after", assertInstanceOf(VariableDeclaration.class, res.ast().statements().get(1)).name());
        }

        var matchGroupingSource = callPrefix + "\tif true:\n\t\tmatch (1\n\t, 2)\nvar after = 1\n";
        var matchGroupingRes = recoverableParse(matchGroupingSource);
        findErrorByCode(matchGroupingRes, DiagnosticCodes.STATEMENT_TOO_MANY_ERRORS);
        assertFalse(errorsOf(matchGroupingRes).stream().anyMatch(d -> DiagnosticCodes.MISSING_CLOSER_CALL.equals(d.code())),
                () -> errorsOf(matchGroupingRes).toString());
        assertEquals(2, matchGroupingRes.ast().statements().size(), () -> matchGroupingRes.ast().statements().toString());
        var matchGroupingCall = assertInstanceOf(CallExpression.class,
                assertInstanceOf(VariableDeclaration.class, matchGroupingRes.ast().statements().getFirst()).value());
        assertEquals(2, matchGroupingCall.arguments().size(), () -> matchGroupingCall.arguments().toString());
        assertInstanceOf(LambdaExpression.class, matchGroupingCall.arguments().getFirst());
        assertEquals("2", assertInstanceOf(LiteralExpression.class, matchGroupingCall.arguments().get(1)).sourceText());
        assertEquals("after", assertInstanceOf(VariableDeclaration.class, matchGroupingRes.ast().statements().get(1)).name());
    }

    @Test
    void lambdaCircuitBreakerDistinguishesExpressionMatchCallsAndTypeColonContext() {
        var callPrefix = "var x = g(func():\n" + "\tvar\n".repeat(102);

        var exprMatchCallSource = callPrefix + "\tif true:\n\t\tvar z = match(1\n\t, 2)\n\tpass\nvar after = 1\n";
        var exprMatchCallRes = recoverableParse(exprMatchCallSource);
        findErrorByCode(exprMatchCallRes, DiagnosticCodes.STATEMENT_TOO_MANY_ERRORS);
        findErrorByCode(exprMatchCallRes, DiagnosticCodes.MISSING_CLOSER_CALL);
        assertFalse(errorMessages(exprMatchCallRes).stream().anyMatch(m -> m.contains("Unexpected \"Indent\"")),
                () -> errorMessages(exprMatchCallRes).toString());
        assertEquals(2, exprMatchCallRes.ast().statements().size(), () -> exprMatchCallRes.ast().statements().toString());
        var exprMatchCall = assertInstanceOf(CallExpression.class,
                assertInstanceOf(VariableDeclaration.class, exprMatchCallRes.ast().statements().getFirst()).value());
        assertEquals(1, exprMatchCall.arguments().size(), () -> exprMatchCall.arguments().toString());
        assertInstanceOf(LambdaExpression.class, exprMatchCall.arguments().getFirst());
        assertEquals("after", assertInstanceOf(VariableDeclaration.class, exprMatchCallRes.ast().statements().get(1)).name());

        for (var stmtMatchGrouping : List.of(
                callPrefix + "\tif true:\n\t\tmatch (1\n\t, 2)\nvar after = 1\n",
                callPrefix + "\tif true:\n\t\tif true: match (1\n\t, 2)\nvar after = 1\n")) {
            var res = recoverableParse(stmtMatchGrouping);
            findErrorByCode(res, DiagnosticCodes.STATEMENT_TOO_MANY_ERRORS);
            assertFalse(errorsOf(res).stream().anyMatch(d -> DiagnosticCodes.MISSING_CLOSER_CALL.equals(d.code())),
                    () -> stmtMatchGrouping + " -> " + errorsOf(res));
            assertEquals(2, res.ast().statements().size(), () -> stmtMatchGrouping + " -> " + res.ast().statements());
            var call = assertInstanceOf(CallExpression.class,
                    assertInstanceOf(VariableDeclaration.class, res.ast().statements().getFirst()).value());
            assertEquals(2, call.arguments().size(), () -> stmtMatchGrouping + " -> " + call.arguments());
            assertInstanceOf(LambdaExpression.class, call.arguments().getFirst());
            assertEquals("2", assertInstanceOf(LiteralExpression.class, call.arguments().get(1)).sourceText());
            assertEquals("after", assertInstanceOf(VariableDeclaration.class, res.ast().statements().get(1)).name());
        }

        var arrayPrefix = "var x = [func():\n" + "\tvar\n".repeat(102);

        for (var suiteColonSubscript : List.of(
                arrayPrefix + "\tif true:\n\t\tif true: Dictionary[1\n\t, 2]\nvar after = 1\n",
                arrayPrefix + "\tif true:\n\t\tif cond: Dictionary[1\n\t, 2]\nvar after = 1\n")) {
            var res = recoverableParse(suiteColonSubscript);
            findErrorByCode(res, DiagnosticCodes.STATEMENT_TOO_MANY_ERRORS);
            assertFalse(errorsOf(res).stream().anyMatch(d -> DiagnosticCodes.MISSING_CLOSER_ARRAY.equals(d.code())),
                    () -> suiteColonSubscript + " -> " + errorsOf(res));
            assertEquals(2, res.ast().statements().size(), () -> suiteColonSubscript + " -> " + res.ast().statements());
            var array = assertInstanceOf(ArrayExpression.class,
                    assertInstanceOf(VariableDeclaration.class, res.ast().statements().getFirst()).value());
            assertEquals(2, array.elements().size(), () -> suiteColonSubscript + " -> " + array.elements());
            assertInstanceOf(LambdaExpression.class, array.elements().getFirst());
            assertEquals("2", assertInstanceOf(LiteralExpression.class, array.elements().get(1)).sourceText());
            assertEquals("after", assertInstanceOf(VariableDeclaration.class, res.ast().statements().get(1)).name());
        }

        for (var typeContextDict : List.of(
                arrayPrefix + "\tif true:\n\t\tvar d: Dictionary[String\n\t, int]\n\tpass\nvar after = 1\n",
                arrayPrefix + "\tif true:\n\t\tfunc h() -> Dictionary[String\n\t, int]:\n\t\t\tpass\nvar after = 1\n")) {
            var res = recoverableParse(typeContextDict);
            findErrorByCode(res, DiagnosticCodes.STATEMENT_TOO_MANY_ERRORS);
            findErrorByCode(res, DiagnosticCodes.MISSING_CLOSER_ARRAY);
            assertFalse(errorMessages(res).stream().anyMatch(m -> m.contains("Unexpected \"Indent\"")),
                    () -> typeContextDict + " -> " + errorMessages(res));
            assertEquals(2, res.ast().statements().size(), () -> typeContextDict + " -> " + res.ast().statements());
            var array = assertInstanceOf(ArrayExpression.class,
                    assertInstanceOf(VariableDeclaration.class, res.ast().statements().getFirst()).value());
            assertEquals(1, array.elements().size(), () -> typeContextDict + " -> " + array.elements());
            assertInstanceOf(LambdaExpression.class, array.elements().getFirst());
            assertEquals("after", assertInstanceOf(VariableDeclaration.class, res.ast().statements().get(1)).name());
        }
    }

    @Test
    @Timeout(5)
    void matchSectionMissingColonAlwaysAdvancesAndKeepsFollowingBranches() {
        var withFollowUp = recoverableParse("func f():\n\tmatch 0:\n\t\t1: pass\n\t\t= 1\n\t\t2: pass\nvar after = 1\n");
        assertEquals(2, withFollowUp.ast().statements().size(), () -> withFollowUp.ast().statements().toString());
        var fn1 = assertInstanceOf(FunctionDeclaration.class, withFollowUp.ast().statements().getFirst());
        var match1 = assertInstanceOf(MatchStatement.class, fn1.body().statements().getFirst());
        assertEquals(3, match1.sections().size(), () -> match1.sections().toString());
        assertInstanceOf(PassStatement.class, match1.sections().getFirst().body().statements().getFirst());
        assertInstanceOf(PassStatement.class, match1.sections().get(2).body().statements().getFirst());
        findErrorByCode(withFollowUp, DiagnosticCodes.MISSING_SEPARATOR_MATCH_COLON);
        assertEquals("after", assertInstanceOf(VariableDeclaration.class, withFollowUp.ast().statements().get(1)).name());

        var atMatchEnd = recoverableParse("func f():\n\tmatch 0:\n\t\t1: pass\n\t\t= 1\n");
        assertEquals(1, atMatchEnd.ast().statements().size(), () -> atMatchEnd.ast().statements().toString());
        var fn2 = assertInstanceOf(FunctionDeclaration.class, atMatchEnd.ast().statements().getFirst());
        var match2 = assertInstanceOf(MatchStatement.class, fn2.body().statements().getFirst());
        assertEquals(2, match2.sections().size(), () -> match2.sections().toString());
        assertInstanceOf(PassStatement.class, match2.sections().getFirst().body().statements().getFirst());
        findErrorByCode(atMatchEnd, DiagnosticCodes.MISSING_SEPARATOR_MATCH_COLON);
    }

    @Test
    void lambdaCircuitBreakerHandlesLineStartMatchCallAnnotationPrefixAndIsNotType() {
        var callPrefix = "var x = g(func():\n" + "\tvar\n".repeat(102);

        var lineStartMatchCall = recoverableParse(callPrefix + "\tvar z = f(\n\tmatch(1), 2)\n, 3)\nvar after = 1\n");
        findErrorByCode(lineStartMatchCall, DiagnosticCodes.STATEMENT_TOO_MANY_ERRORS);
        assertFalse(errorsOf(lineStartMatchCall).stream().anyMatch(d -> DiagnosticCodes.MISSING_CLOSER_CALL.equals(d.code())),
                () -> errorsOf(lineStartMatchCall).toString());
        assertEquals(2, lineStartMatchCall.ast().statements().size(), () -> lineStartMatchCall.ast().statements().toString());
        var matchCallOuter = assertInstanceOf(CallExpression.class,
                assertInstanceOf(VariableDeclaration.class, lineStartMatchCall.ast().statements().getFirst()).value());
        assertEquals(2, matchCallOuter.arguments().size(), () -> matchCallOuter.arguments().toString());
        assertInstanceOf(LambdaExpression.class, matchCallOuter.arguments().getFirst());
        assertEquals("3", assertInstanceOf(LiteralExpression.class, matchCallOuter.arguments().get(1)).sourceText());
        assertEquals("after", assertInstanceOf(VariableDeclaration.class, lineStartMatchCall.ast().statements().get(1)).name());

        var lineStartMatchStmt = recoverableParse(
                callPrefix + "\tvar z = (1\n\tmatch 1:\n\t\t1, 2: pass\n, 3)\nvar after = 1\n");
        findErrorByCode(lineStartMatchStmt, DiagnosticCodes.STATEMENT_TOO_MANY_ERRORS);
        assertFalse(errorMessages(lineStartMatchStmt).stream().anyMatch(m -> m.contains("Unexpected \":\"")),
                () -> errorMessages(lineStartMatchStmt).toString());
        assertEquals(2, lineStartMatchStmt.ast().statements().size(), () -> lineStartMatchStmt.ast().statements().toString());
        var matchStmtOuter = assertInstanceOf(CallExpression.class,
                assertInstanceOf(VariableDeclaration.class, lineStartMatchStmt.ast().statements().getFirst()).value());
        assertEquals(2, matchStmtOuter.arguments().size(), () -> matchStmtOuter.arguments().toString());
        assertInstanceOf(LambdaExpression.class, matchStmtOuter.arguments().getFirst());
        assertEquals("3", assertInstanceOf(LiteralExpression.class, matchStmtOuter.arguments().get(1)).sourceText());
        assertEquals("after", assertInstanceOf(VariableDeclaration.class, lineStartMatchStmt.ast().statements().get(1)).name());

        var annotatedMatch = recoverableParse(
                callPrefix + "\t@warning_ignore(\"unused_variable\") match 1:\n\t\t1, 2: pass\n, 3)\nvar after = 1\n");
        findErrorByCode(annotatedMatch, DiagnosticCodes.STATEMENT_TOO_MANY_ERRORS);
        assertFalse(errorMessages(annotatedMatch).stream().anyMatch(m -> m.contains("Unexpected \":\"")),
                () -> errorMessages(annotatedMatch).toString());
        assertEquals(2, annotatedMatch.ast().statements().size(), () -> annotatedMatch.ast().statements().toString());
        var annotatedMatchOuter = assertInstanceOf(CallExpression.class,
                assertInstanceOf(VariableDeclaration.class, annotatedMatch.ast().statements().getFirst()).value());
        assertEquals(2, annotatedMatchOuter.arguments().size(), () -> annotatedMatchOuter.arguments().toString());
        assertInstanceOf(LambdaExpression.class, annotatedMatchOuter.arguments().getFirst());
        assertEquals("3", assertInstanceOf(LiteralExpression.class, annotatedMatchOuter.arguments().get(1)).sourceText());
        assertEquals("after", assertInstanceOf(VariableDeclaration.class, annotatedMatch.ast().statements().get(1)).name());

        var arrayPrefix = "var x = [func():\n" + "\tvar\n".repeat(102);

        for (var typeContextSource : List.of(
                arrayPrefix + "\t@export var d: Dictionary[String\n\t, int]\n\tpass\nvar after = 1\n",
                arrayPrefix + "\tif true:\n\t\tvar d = v is not Dictionary[String\n\t, int]\n\tpass\nvar after = 1\n")) {
            var res = recoverableParse(typeContextSource);
            findErrorByCode(res, DiagnosticCodes.STATEMENT_TOO_MANY_ERRORS);
            findErrorByCode(res, DiagnosticCodes.MISSING_CLOSER_ARRAY);
            assertFalse(errorMessages(res).stream().anyMatch(m -> m.contains("Unexpected \"Indent\"")),
                    () -> typeContextSource + " -> " + errorMessages(res));
            assertEquals(2, res.ast().statements().size(), () -> typeContextSource + " -> " + res.ast().statements());
            var array = assertInstanceOf(ArrayExpression.class,
                    assertInstanceOf(VariableDeclaration.class, res.ast().statements().getFirst()).value());
            assertEquals(1, array.elements().size(), () -> typeContextSource + " -> " + array.elements());
            assertInstanceOf(LambdaExpression.class, array.elements().getFirst());
            assertEquals("after", assertInstanceOf(VariableDeclaration.class, res.ast().statements().get(1)).name());
        }

        var bareNotSubscript = recoverableParse(
                arrayPrefix + "\tif true:\n\t\tvar d = not Dictionary[1\n\t, 2]\nvar after = 1\n");
        findErrorByCode(bareNotSubscript, DiagnosticCodes.STATEMENT_TOO_MANY_ERRORS);
        assertFalse(errorsOf(bareNotSubscript).stream().anyMatch(d -> DiagnosticCodes.MISSING_CLOSER_ARRAY.equals(d.code())),
                () -> errorsOf(bareNotSubscript).toString());
        assertEquals(2, bareNotSubscript.ast().statements().size(), () -> bareNotSubscript.ast().statements().toString());
        var bareNotArray = assertInstanceOf(ArrayExpression.class,
                assertInstanceOf(VariableDeclaration.class, bareNotSubscript.ast().statements().getFirst()).value());
        assertEquals(2, bareNotArray.elements().size(), () -> bareNotArray.elements().toString());
        assertInstanceOf(LambdaExpression.class, bareNotArray.elements().getFirst());
        assertEquals("2", assertInstanceOf(LiteralExpression.class, bareNotArray.elements().get(1)).sourceText());
        assertEquals("after", assertInstanceOf(VariableDeclaration.class, bareNotSubscript.ast().statements().get(1)).name());
    }

    @Test
    void indentedPropertyDoubleCommaReportsSingleInvalidAccessorWithoutDuplicateMissingComma() {
        var doubleCommaMiddle = recoverableParse("var x:\n\tget = g,, set = s\nvar after = 1\n");
        assertEquals(2, doubleCommaMiddle.ast().statements().size(), () -> doubleCommaMiddle.ast().statements().toString());
        assertEquals("after", assertInstanceOf(VariableDeclaration.class, doubleCommaMiddle.ast().statements().get(1)).name());
        var midErrors = errorsOf(doubleCommaMiddle);
        assertEquals(1, midErrors.size(), () -> midErrors.toString());
        assertEquals(DiagnosticCodes.PROPERTY_INVALID_ACCESSOR, midErrors.getFirst().code());
        assertFalse(midErrors.stream().anyMatch(d -> DiagnosticCodes.MISSING_SEPARATOR_COMMA.equals(d.code())),
                () -> midErrors.toString());

        var doubleCommaTrailing = recoverableParse("var x:\n\tget = g,,\nvar after = 1\n");
        assertEquals(2, doubleCommaTrailing.ast().statements().size(), () -> doubleCommaTrailing.ast().statements().toString());
        assertEquals("after", assertInstanceOf(VariableDeclaration.class, doubleCommaTrailing.ast().statements().get(1)).name());
        var trailingErrors = errorsOf(doubleCommaTrailing);
        assertEquals(1, trailingErrors.size(), () -> trailingErrors.toString());
        assertEquals(DiagnosticCodes.PROPERTY_INVALID_ACCESSOR, trailingErrors.getFirst().code());

        var missingCommaControl = recoverableParse("var x:\n\tget = g\n\tset = s\nvar after = 1\n");
        assertEquals(2, missingCommaControl.ast().statements().size(), () -> missingCommaControl.ast().statements().toString());
        assertEquals("after", assertInstanceOf(VariableDeclaration.class, missingCommaControl.ast().statements().get(1)).name());
        findErrorByCode(missingCommaControl, DiagnosticCodes.MISSING_SEPARATOR_COMMA);
    }

    @Test
    void lambdaCircuitBreakerProtectsMatchedDictMatchKeysAndMultilineAnnotationArgs() {
        var callPrefix = "var x = g(func():\n" + "\tvar\n".repeat(102);

        for (var dictMatchKeySource : List.of(
                callPrefix + "\tvar z = {\n\tmatch(1): 2, 4: 5}\n, 3)\nvar after = 1\n",
                callPrefix + "\tvar z = {\n\tmatch(1):\n\t\t2, 4: 5}\n, 3)\nvar after = 1\n")) {
            var res = recoverableParse(dictMatchKeySource);
            findErrorByCode(res, DiagnosticCodes.STATEMENT_TOO_MANY_ERRORS);
            assertFalse(errorsOf(res).stream().anyMatch(d -> DiagnosticCodes.MISMATCHED_CLOSER_CALL.equals(d.code())),
                    () -> dictMatchKeySource + " -> " + errorsOf(res));
            assertEquals(2, res.ast().statements().size(), () -> dictMatchKeySource + " -> " + res.ast().statements());
            var call = assertInstanceOf(CallExpression.class,
                    assertInstanceOf(VariableDeclaration.class, res.ast().statements().getFirst()).value());
            assertEquals(2, call.arguments().size(), () -> dictMatchKeySource + " -> " + call.arguments());
            assertInstanceOf(LambdaExpression.class, call.arguments().getFirst());
            assertEquals("3", assertInstanceOf(LiteralExpression.class, call.arguments().get(1)).sourceText());
            assertEquals("after", assertInstanceOf(VariableDeclaration.class, res.ast().statements().get(1)).name());
        }

        var unclosedDictThenRealMatch = recoverableParse(
                callPrefix + "\tvar z = {1\n\tmatch 1:\n\t\t1, 2: pass\n, 3)\nvar after = 1\n");
        findErrorByCode(unclosedDictThenRealMatch, DiagnosticCodes.STATEMENT_TOO_MANY_ERRORS);
        assertFalse(errorMessages(unclosedDictThenRealMatch).stream().anyMatch(m -> m.contains("Unexpected \":\"")),
                () -> errorMessages(unclosedDictThenRealMatch).toString());
        assertEquals(2, unclosedDictThenRealMatch.ast().statements().size(),
                () -> unclosedDictThenRealMatch.ast().statements().toString());
        var unclosedDictOuter = assertInstanceOf(CallExpression.class,
                assertInstanceOf(VariableDeclaration.class, unclosedDictThenRealMatch.ast().statements().getFirst()).value());
        assertEquals(2, unclosedDictOuter.arguments().size(), () -> unclosedDictOuter.arguments().toString());
        assertInstanceOf(LambdaExpression.class, unclosedDictOuter.arguments().getFirst());
        assertEquals("3", assertInstanceOf(LiteralExpression.class, unclosedDictOuter.arguments().get(1)).sourceText());
        assertEquals("after", assertInstanceOf(VariableDeclaration.class, unclosedDictThenRealMatch.ast().statements().get(1)).name());

        for (var multilineAnnotationMatch : List.of(
                callPrefix + "\t@warning_ignore(\n\t\"unused_variable\") match 1:\n\t\t1, 2: pass\n, 3)\nvar after = 1\n",
                callPrefix + "\t@warning_ignore(\n\t\t\"unused_variable\"\n\t) match 1:\n\t\t1, 2: pass\n, 3)\nvar after = 1\n")) {
            var res = recoverableParse(multilineAnnotationMatch);
            findErrorByCode(res, DiagnosticCodes.STATEMENT_TOO_MANY_ERRORS);
            assertFalse(errorMessages(res).stream().anyMatch(m -> m.contains("Unexpected \":\"")),
                    () -> multilineAnnotationMatch + " -> " + errorMessages(res));
            assertEquals(2, res.ast().statements().size(),
                    () -> multilineAnnotationMatch + " -> " + res.ast().statements());
            var call = assertInstanceOf(CallExpression.class,
                    assertInstanceOf(VariableDeclaration.class, res.ast().statements().getFirst()).value());
            assertEquals(2, call.arguments().size(), () -> multilineAnnotationMatch + " -> " + call.arguments());
            assertInstanceOf(LambdaExpression.class, call.arguments().getFirst());
            assertEquals("3", assertInstanceOf(LiteralExpression.class, call.arguments().get(1)).sourceText());
            assertEquals("after", assertInstanceOf(VariableDeclaration.class, res.ast().statements().get(1)).name());
        }
    }

    @Test
    void lambdaCircuitBreakerSettlesReplacedDictCloserBeforeRealMatchStatement() {
        var callPrefix = "var x = g(func():\n" + "\tvar\n".repeat(102);
        var replacedCloserSource = callPrefix + "\tvar z = {1\n\tmatch 1:\n\t\t1, 2: pass\n\t, 3])\nvar after = 1\n";
        var res = recoverableParse(replacedCloserSource);
        findErrorByCode(res, DiagnosticCodes.STATEMENT_TOO_MANY_ERRORS);
        assertFalse(errorMessages(res).stream().anyMatch(m -> m.contains("Unexpected \":\"")),
                () -> errorMessages(res).toString());
        assertEquals(2, res.ast().statements().size(), () -> res.ast().statements().toString());
        var call = assertInstanceOf(CallExpression.class,
                assertInstanceOf(VariableDeclaration.class, res.ast().statements().getFirst()).value());
        assertEquals(2, call.arguments().size(), () -> call.arguments().toString());
        assertInstanceOf(LambdaExpression.class, call.arguments().getFirst());
        assertEquals("3", assertInstanceOf(LiteralExpression.class, call.arguments().get(1)).sourceText());
        assertEquals("after", assertInstanceOf(VariableDeclaration.class, res.ast().statements().get(1)).name());
    }

    @Test
    void lambdaCircuitBreakerHandlesMultilineHeaderJumpAnnotationDepthAndLocalReplacedClosers() {
        var callPrefix = "var x = g(func():\n" + "\tvar\n".repeat(102);

        for (var multilineHeaderSource : List.of(
                callPrefix + "\tvar z = (1\n\tmatch (\n\t\t1\n\t):\n\t\t1, 2: pass\n\t, 3)\nvar after = 1\n",
                callPrefix + "\tvar z = (1\n\tmatch (1):\n\t\t1, 2: pass\n\t, 3)\nvar after = 1\n",
                callPrefix + "\tvar z = (1\n\tif (\n\t\ttrue\n\t):\n\t\tpass\n\t, 3)\nvar after = 1\n")) {
            var res = recoverableParse(multilineHeaderSource);
            findErrorByCode(res, DiagnosticCodes.STATEMENT_TOO_MANY_ERRORS);
            assertFalse(errorsOf(res).stream().anyMatch(d -> DiagnosticCodes.MISSING_CLOSER_CALL.equals(d.code())),
                    () -> multilineHeaderSource + " -> " + errorsOf(res));
            assertEquals(2, res.ast().statements().size(),
                    () -> multilineHeaderSource + " -> " + res.ast().statements());
            var call = assertInstanceOf(CallExpression.class,
                    assertInstanceOf(VariableDeclaration.class, res.ast().statements().getFirst()).value());
            assertEquals(2, call.arguments().size(), () -> multilineHeaderSource + " -> " + call.arguments());
            assertInstanceOf(LambdaExpression.class, call.arguments().getFirst());
            assertEquals("3", assertInstanceOf(LiteralExpression.class, call.arguments().get(1)).sourceText());
            assertEquals("after", assertInstanceOf(VariableDeclaration.class, res.ast().statements().get(1)).name());
        }

        for (var annotationDepthSource : List.of(
                callPrefix + "\t@warning_ignore(\n\t\t\"x\") match 1:\n\t\t1, 2: pass\n\t, 3)\nvar after = 1\n",
                callPrefix + "\t@warning_ignore(\n\t\t\"x\"\n\t)\n\tmatch 1:\n\t\t1, 2: pass\n\t, 3)\nvar after = 1\n")) {
            var res = recoverableParse(annotationDepthSource);
            findErrorByCode(res, DiagnosticCodes.STATEMENT_TOO_MANY_ERRORS);
            assertFalse(errorMessages(res).stream().anyMatch(m -> m.contains("Unexpected \":\"")),
                    () -> annotationDepthSource + " -> " + errorMessages(res));
            assertEquals(2, res.ast().statements().size(),
                    () -> annotationDepthSource + " -> " + res.ast().statements());
            var call = assertInstanceOf(CallExpression.class,
                    assertInstanceOf(VariableDeclaration.class, res.ast().statements().getFirst()).value());
            assertEquals(2, call.arguments().size(), () -> annotationDepthSource + " -> " + call.arguments());
            assertInstanceOf(LambdaExpression.class, call.arguments().getFirst());
            assertEquals("3", assertInstanceOf(LiteralExpression.class, call.arguments().get(1)).sourceText());
            assertEquals("after", assertInstanceOf(VariableDeclaration.class, res.ast().statements().get(1)).name());
        }

        for (var localReplacedSource : List.of(
                callPrefix + "\tvar z = {1: 2]\n\t, 3)\nvar after = 1\n",
                callPrefix + "\tvar z = {1: 2}\n\t, 3)\nvar after = 1\n",
                callPrefix + "\tvar z = {\n\t\tmatch(1):\n\t\t\t2, 4: 5]\n\t, 3)\nvar after = 1\n",
                callPrefix + "\tvar z = {\n\tmatch(1):\n\t\t2, 4: 5]\n\t, 3)\nvar after = 1\n")) {
            var res = recoverableParse(localReplacedSource);
            findErrorByCode(res, DiagnosticCodes.STATEMENT_TOO_MANY_ERRORS);
            assertFalse(errorsOf(res).stream().anyMatch(d -> DiagnosticCodes.MISMATCHED_CLOSER_CALL.equals(d.code())),
                    () -> localReplacedSource + " -> " + errorsOf(res));
            assertEquals(2, res.ast().statements().size(),
                    () -> localReplacedSource + " -> " + res.ast().statements());
            var call = assertInstanceOf(CallExpression.class,
                    assertInstanceOf(VariableDeclaration.class, res.ast().statements().getFirst()).value());
            assertEquals(2, call.arguments().size(), () -> localReplacedSource + " -> " + call.arguments());
            assertInstanceOf(LambdaExpression.class, call.arguments().getFirst());
            assertEquals("3", assertInstanceOf(LiteralExpression.class, call.arguments().get(1)).sourceText());
            assertEquals("after", assertInstanceOf(VariableDeclaration.class, res.ast().statements().get(1)).name());
        }
    }

    @Test
    void lambdaCircuitBreakerPreservesAnnotationDepthAcrossInnerMatchAndUnwindsSkippedLocalOpeners() {
        var callPrefix = "var x = g(func():\n" + "\tvar\n".repeat(102);

        for (var innerMatchAnnotationSource : List.of(
                callPrefix + "\t@warning_ignore(\n\t\twarnings.match) match 1:\n\t\t1, 2: pass\n\t, 3)\nvar after = 1\n",
                callPrefix + "\t@warning_ignore(\n\t\tmatch(\"x\")) match 1:\n\t\t1, 2: pass\n\t, 3)\nvar after = 1\n")) {
            var res = recoverableParse(innerMatchAnnotationSource);
            findErrorByCode(res, DiagnosticCodes.STATEMENT_TOO_MANY_ERRORS);
            assertFalse(errorMessages(res).stream().anyMatch(m -> m.contains("Unexpected \":\"")),
                    () -> innerMatchAnnotationSource + " -> " + errorMessages(res));
            assertEquals(2, res.ast().statements().size(),
                    () -> innerMatchAnnotationSource + " -> " + res.ast().statements());
            var call = assertInstanceOf(CallExpression.class,
                    assertInstanceOf(VariableDeclaration.class, res.ast().statements().getFirst()).value());
            assertEquals(2, call.arguments().size(), () -> innerMatchAnnotationSource + " -> " + call.arguments());
            assertInstanceOf(LambdaExpression.class, call.arguments().getFirst());
            assertEquals("3", assertInstanceOf(LiteralExpression.class, call.arguments().get(1)).sourceText());
            assertEquals("after", assertInstanceOf(VariableDeclaration.class, res.ast().statements().get(1)).name());
        }

        for (var nestedLocalCloserSource : List.of(
                callPrefix + "\tvar z = [{1: 2]\n\t, 3)\nvar after = 1\n",
                callPrefix + "\tvar z = [({1: 2]\n\t, 3)\nvar after = 1\n",
                callPrefix + "\tvar z = [{1: 2}]\n\t, 3)\nvar after = 1\n")) {
            var res = recoverableParse(nestedLocalCloserSource);
            findErrorByCode(res, DiagnosticCodes.STATEMENT_TOO_MANY_ERRORS);
            assertEquals(2, res.ast().statements().size(),
                    () -> nestedLocalCloserSource + " -> " + res.ast().statements());
            var call = assertInstanceOf(CallExpression.class,
                    assertInstanceOf(VariableDeclaration.class, res.ast().statements().getFirst()).value());
            assertEquals(2, call.arguments().size(), () -> nestedLocalCloserSource + " -> " + call.arguments());
            assertInstanceOf(LambdaExpression.class, call.arguments().getFirst());
            assertEquals("3", assertInstanceOf(LiteralExpression.class, call.arguments().get(1)).sourceText());
            assertEquals("after", assertInstanceOf(VariableDeclaration.class, res.ast().statements().get(1)).name());
        }
    }

    private static AstDiagnostic findErrorByCode(AstMappingResult result, String code) {
        return errorsOf(result).stream()
                .filter(d -> code.equals(d.code()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing diagnostic " + code + " in: " + errorsOf(result)));
    }
}
