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
import dev.superice.gdparser.frontend.ast.BreakStatement;
import dev.superice.gdparser.frontend.ast.BreakpointStatement;
import dev.superice.gdparser.frontend.ast.CallExpression;
import dev.superice.gdparser.frontend.ast.ClassDeclaration;
import dev.superice.gdparser.frontend.ast.ClassNameStatement;
import dev.superice.gdparser.frontend.ast.CommentStatement;
import dev.superice.gdparser.frontend.ast.ConditionalExpression;
import dev.superice.gdparser.frontend.ast.ConstructorDeclaration;
import dev.superice.gdparser.frontend.ast.ContinueStatement;
import dev.superice.gdparser.frontend.ast.DeclarationKind;
import dev.superice.gdparser.frontend.ast.DictEntry;
import dev.superice.gdparser.frontend.ast.DictionaryExpression;
import dev.superice.gdparser.frontend.ast.EnumDeclaration;
import dev.superice.gdparser.frontend.ast.ErrorExpression;
import dev.superice.gdparser.frontend.ast.ExpressionStatement;
import dev.superice.gdparser.frontend.ast.ExtendsStatement;
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
import dev.superice.gdparser.frontend.ast.PreloadExpression;
import dev.superice.gdparser.frontend.ast.Range;
import dev.superice.gdparser.frontend.ast.RecoveredInfixExpression;
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
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

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
    /// Embedded assignment (`!canAssign`): reports `ASSIGNMENT_DISALLOWED_IN_EXPRESSION` at the
    /// operator while retaining the inner `AssignmentExpression(left, right)` in the AST so neither
    /// the assignee (`b`) nor the right-hand side is lost.
    @Test
    void chainedAssignmentKeepsOuterAssignment() {
        var result = recoverableParse("func f():\n\ta = b = 1\n");
        var errors = errorMessages(result);
        assertEquals(List.of("Assignment is not allowed inside an expression."), errors,
                errors::toString);
        var error = errorsOf(result).getFirst();
        assertEquals(DiagnosticCodes.ASSIGNMENT_DISALLOWED_IN_EXPRESSION, error.code());
        assertEquals(1, error.range().startPoint().row(), () -> error.range().toString());
        assertEquals(7, error.range().startPoint().column(), () -> error.range().toString());
        var body = onlyFunction(result).body().statements();
        assertEquals(1, body.size(), () -> "body: " + body);
        var statement = assertInstanceOf(ExpressionStatement.class, body.getFirst());
        assertSame(statement, requireNotNull(error.recovery()).owner());
        assertEquals("expression", requireNotNull(error.recovery()).facet());
        var outer = assertInstanceOf(AssignmentExpression.class, statement.expression());
        assertEquals("=", outer.operator());
        assertEquals("a", assertInstanceOf(IdentifierExpression.class, outer.left()).name());
        var inner = assertInstanceOf(AssignmentExpression.class, outer.right());
        assertEquals("=", inner.operator());
        assertEquals("b", assertInstanceOf(IdentifierExpression.class, inner.left()).name());
        assertEquals("1", assertInstanceOf(LiteralExpression.class, inner.right()).sourceText());
    }

    /// Call-argument embedded assignment: `g(a = 1, 2)` and `g((a = 1), 2)` both preserve the inner
    /// `AssignmentExpression("=", a, 1)` as argument 0 and `2` as argument 1, with the diagnostic
    /// bound to the enclosing `ExpressionStatement`.
    @Test
    void assignmentInsideCallArgument() {
        var result = recoverableParse("func f():\n\tg(a = 1, 2)\n");
        var errors = errorMessages(result);
        assertEquals(List.of("Assignment is not allowed inside an expression."), errors,
                errors::toString);
        var error = errorsOf(result).getFirst();
        assertEquals(DiagnosticCodes.ASSIGNMENT_DISALLOWED_IN_EXPRESSION, error.code());
        var body = onlyFunction(result).body().statements();
        var statement = assertInstanceOf(ExpressionStatement.class, body.getFirst());
        assertSame(statement, requireNotNull(error.recovery()).owner());
        var call = assertInstanceOf(CallExpression.class, statement.expression());
        assertEquals(2, call.arguments().size(), () -> "args: " + call.arguments());
        var inner = assertInstanceOf(AssignmentExpression.class, call.arguments().getFirst());
        assertEquals("=", inner.operator());
        assertEquals("a", assertInstanceOf(IdentifierExpression.class, inner.left()).name());
        assertEquals("1", assertInstanceOf(LiteralExpression.class, inner.right()).sourceText());
        assertEquals("2", assertInstanceOf(LiteralExpression.class, call.arguments().get(1)).sourceText());

        var groupedResult = recoverableParse("func f():\n\tg((a = 1), 2)\n");
        assertEquals(List.of("Assignment is not allowed inside an expression."),
                errorMessages(groupedResult), () -> errorMessages(groupedResult).toString());
        var groupedCall = assertInstanceOf(CallExpression.class,
                assertInstanceOf(ExpressionStatement.class,
                        onlyFunction(groupedResult).body().statements().getFirst()).expression());
        assertEquals(2, groupedCall.arguments().size());
        var groupedInner = assertInstanceOf(AssignmentExpression.class, groupedCall.arguments().getFirst());
        assertEquals("a", assertInstanceOf(IdentifierExpression.class, groupedInner.left()).name());
        assertEquals("1", assertInstanceOf(LiteralExpression.class, groupedInner.right()).sourceText());
        assertEquals("2", assertInstanceOf(LiteralExpression.class, groupedCall.arguments().get(1)).sourceText());
    }

    /// Initializer embedded assignment: `var x = y = 1` keeps `AssignmentExpression("=", y, 1)` as
    /// `x.value()` and attributes `ASSIGNMENT_DISALLOWED_IN_EXPRESSION` to `x` (`variable.initializer`).
    @Test
    void assignmentInsideVariableInitializer() {
        var result = recoverableParse("func f():\n\tvar x = y = 1\n");
        var errors = errorMessages(result);
        assertEquals(List.of("Assignment is not allowed inside an expression."), errors,
                errors::toString);
        var error = errorsOf(result).getFirst();
        assertEquals(DiagnosticCodes.ASSIGNMENT_DISALLOWED_IN_EXPRESSION, error.code());
        var body = onlyFunction(result).body().statements();
        var x = assertInstanceOf(VariableDeclaration.class, body.getFirst());
        assertSame(x, requireNotNull(error.recovery()).owner());
        assertEquals("variable.initializer", requireNotNull(error.recovery()).facet());
        var inner = assertInstanceOf(AssignmentExpression.class, x.value());
        assertEquals("=", inner.operator());
        assertEquals("y", assertInstanceOf(IdentifierExpression.class, inner.left()).name());
        assertEquals("1", assertInstanceOf(LiteralExpression.class, inner.right()).sourceText());
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
    // Surface lvalue & direct-call callee guards (L-30a): damaged nodes preserved without duplicate
    // diagnostics on already-diagnosed missing-target / missing-callee / embedded-assignment markers.
    // -----------------------------------------------------------------------------------------

    @Test
    void surfaceAssignmentTargetAndDirectCallGuards() {
        // Valid surface lvalues and direct calls stay clean.
        for (var validSource : List.of(
                "func f():\n\tx = 1\n\t(x) = 2\n\tf().x = 3\n\tf()[0] = 4\n\tobj.arr[i] = 5\n",
                "func f():\n\tfoo()\n\t(foo)()\n\tobj.method()\n\tf(1).call(2)\n"
        )) {
            var clean = new GdScriptParser().parse(validSource);
            assertTrue(errorsOf(clean).isEmpty(), () -> validSource + " -> " + errorsOf(clean));
        }

        // Invalid lvalues emit ASSIGNMENT_INVALID_TARGET while retaining AssignmentExpression.
        for (var invalidLvalue : List.of("1 = 2", "\"s\" = 2", "(1 + 2) = 3", "f() = 2", "obj.foo() = 2")) {
            var res = recoverableParse("func f():\n\t" + invalidLvalue + "\n\tvar ok = 1\n");
            var err = findErrorByCode(res, DiagnosticCodes.ASSIGNMENT_INVALID_TARGET);
            var exprStmt = assertInstanceOf(ExpressionStatement.class, onlyFunction(res).body().statements().getFirst());
            assertInstanceOf(AssignmentExpression.class, exprStmt.expression());
            assertSame(exprStmt, err.recovery().owner());
            assertEquals("expression", err.recovery().facet());
            assertEquals(2, onlyFunction(res).body().statements().size());
        }

        // Invalid direct calls emit CALL_INVALID_CALLEE while retaining CallExpression.
        for (var invalidCall : List.of("f(1)(2)", "a.b(1)(2)", "table[i](2)", "obj.arr[i](2)", "1(2)", "(a + b)(2)")) {
            var res = recoverableParse("func f():\n\t" + invalidCall + "\n\tvar ok = 1\n");
            var err = findErrorByCode(res, DiagnosticCodes.CALL_INVALID_CALLEE);
            var exprStmt = assertInstanceOf(ExpressionStatement.class, onlyFunction(res).body().statements().getFirst());
            assertInstanceOf(CallExpression.class, exprStmt.expression());
            assertSame(exprStmt, err.recovery().owner());
            assertEquals("expression", err.recovery().facet());
            assertEquals(2, onlyFunction(res).body().statements().size());
        }

        // Marker suppression: no secondary ASSIGNMENT_INVALID_TARGET / CALL_INVALID_CALLEE when the
        // target/callee is already a missing marker or embedded disallowed assignment.
        var missingGroupAssign = recoverableParse("func f():\n\t() = 2\n");
        assertEquals(List.of(DiagnosticCodes.SLOT_GROUPING),
                errorsOf(missingGroupAssign).stream().map(AstDiagnostic::code).toList());

        var missingAttrAssign = recoverableParse("func f():\n\tself. = 2\n");
        assertEquals(List.of(DiagnosticCodes.ATTRIBUTE_MISSING_NAME),
                errorsOf(missingAttrAssign).stream().map(AstDiagnostic::code).toList());

        var embeddedInvalidAssign = recoverableParse("func f():\n\tg(1 = 2)\n");
        assertEquals(List.of(DiagnosticCodes.ASSIGNMENT_DISALLOWED_IN_EXPRESSION),
                errorsOf(embeddedInvalidAssign).stream().map(AstDiagnostic::code).toList());

        var missingGroupCall = recoverableParse("func f():\n\t()(2)\n");
        assertEquals(List.of(DiagnosticCodes.SLOT_GROUPING),
                errorsOf(missingGroupCall).stream().map(AstDiagnostic::code).toList());

        var missingAttrCall = recoverableParse("func f():\n\tself.b.(1)\n");
        assertEquals(List.of(DiagnosticCodes.ATTRIBUTE_MISSING_NAME),
                errorsOf(missingAttrCall).stream().map(AstDiagnostic::code).toList());
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
        // Bare return, semicolon return, and healthy value: no marker, no diagnostics.
        var bare = parse("func f():\n\treturn\n\treturn;\n");
        assertEquals(List.of(), errorMessages(bare), () -> errorMessages(bare).toString());
        assertNull(assertInstanceOf(ReturnStatement.class,
                onlyFunction(bare).body().statements().getFirst()).value());
        assertNull(assertInstanceOf(ReturnStatement.class,
                onlyFunction(bare).body().statements().get(1)).value());

        // Lambda-internal zero-consumption: `return` before the call's comma stays bare and
        // produces no diagnostic.
        var inCall = parse("var x = call(func(): return, 1)\n");
        assertEquals(List.of(), errorMessages(inCall), () -> errorMessages(inCall).toString());
        var call = assertInstanceOf(CallExpression.class,
                assertInstanceOf(VariableDeclaration.class, inCall.ast().statements().getFirst()).value());
        var lambda = assertInstanceOf(LambdaExpression.class, call.arguments().getFirst());
        assertNull(assertInstanceOf(ReturnStatement.class,
                lambda.body().statements().getFirst()).value());

        // `return =3`: `=` opens no expression; the value slot emits SLOT_RETURN_VALUE bound to
        // `return.value` and keeps an ERROR fragment over `=3` without a duplicate tail error.
        var result = recoverableParse("func f():\n\treturn =3\n");
        var returnDiag = findErrorByCode(result, DiagnosticCodes.SLOT_RETURN_VALUE);
        assertEquals(1, errorMessages(result).size(), () -> errorMessages(result).toString());
        var returnStmt = assertInstanceOf(ReturnStatement.class,
                onlyFunction(result).body().statements().getFirst());
        assertNotNull(returnDiag.recovery());
        assertSame(returnStmt, returnDiag.recovery().owner());
        assertEquals("return.value", returnDiag.recovery().facet());
        var fragment = assertInstanceOf(ErrorExpression.class, returnStmt.value());
        assertEquals(CstIssueKind.ERROR, fragment.kind());
        assertEquals("ERROR", fragment.nodeType());
        assertEquals("=3", fragment.sourceText());

        // Orphan closer `return )` keeps an ERROR fragment over `)` and binds `return.value`.
        var orphanCloser = recoverableParse("func f():\n\treturn )\n");
        var orphanDiag = findErrorByCode(orphanCloser, DiagnosticCodes.SLOT_RETURN_VALUE);
        assertEquals(1, errorMessages(orphanCloser).size(), () -> errorMessages(orphanCloser).toString());
        var orphanStmt = assertInstanceOf(ReturnStatement.class,
                onlyFunction(orphanCloser).body().statements().getFirst());
        assertNotNull(orphanDiag.recovery());
        assertSame(orphanStmt, orphanDiag.recovery().owner());
        assertEquals("return.value", orphanDiag.recovery().facet());
        var orphanFragment = assertInstanceOf(ErrorExpression.class, orphanStmt.value());
        assertEquals(CstIssueKind.ERROR, orphanFragment.kind());
        assertEquals(")", orphanFragment.sourceText());

        // Inline lambda `return =3` stops before `,` and preserves the enclosing call's second arg.
        var lambdaBadReturn = recoverableParse("var x = call(func(): return =3, 1)\n");
        var lambdaDiag = findErrorByCode(lambdaBadReturn, DiagnosticCodes.SLOT_RETURN_VALUE);
        assertEquals(1, errorMessages(lambdaBadReturn).size(), () -> errorMessages(lambdaBadReturn).toString());
        var outerCall = assertInstanceOf(CallExpression.class,
                assertInstanceOf(VariableDeclaration.class, lambdaBadReturn.ast().statements().getFirst()).value());
        assertEquals(2, outerCall.arguments().size());
        var outerLambda = assertInstanceOf(LambdaExpression.class, outerCall.arguments().getFirst());
        var lambdaReturn = assertInstanceOf(ReturnStatement.class, outerLambda.body().statements().getFirst());
        assertSame(lambdaReturn, requireNotNull(lambdaDiag.recovery()).owner());
        assertEquals("return.value", lambdaDiag.recovery().facet());
        assertEquals("=3", assertInstanceOf(ErrorExpression.class, lambdaReturn.value()).sourceText());
        assertEquals("1", assertInstanceOf(LiteralExpression.class, outerCall.arguments().get(1)).sourceText());

        // `return 1 2` keeps `1` as the return value and sends `2` to the statement tail.
        var trailingReturn = recoverableParse("func f():\n\treturn 1 2\n");
        assertEquals(List.of("Expected end of statement after return statement, found \"Literal\" instead."),
                errorMessages(trailingReturn), () -> errorMessages(trailingReturn).toString());
        var trailingStmt = assertInstanceOf(ReturnStatement.class,
                onlyFunction(trailingReturn).body().statements().getFirst());
        assertEquals("1", assertInstanceOf(LiteralExpression.class, trailingStmt.value()).sourceText());
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

        // Colon-witnessed empty entry `{:}` preserves one entry with both MISSING markers.
        var bothMissing = recoverableParse("var d = {:}\n");
        var emptyColonDict = assertInstanceOf(DictionaryExpression.class,
                assertInstanceOf(VariableDeclaration.class, bothMissing.ast().statements().getFirst()).value());
        assertEquals(1, emptyColonDict.entries().size());
        var colonEntry = emptyColonDict.entries().getFirst();
        var colonKey = assertInstanceOf(ErrorExpression.class, colonEntry.key());
        var colonVal = assertInstanceOf(ErrorExpression.class, colonEntry.value());
        assertEquals(CstIssueKind.MISSING, colonKey.kind());
        assertEquals("dictionary.key", colonKey.nodeType());
        assertEquals(CstIssueKind.MISSING, colonVal.kind());
        assertEquals("dictionary.value", colonVal.nodeType());
        assertTrue(colonEntry.range().startByte() <= colonKey.range().startByte());
        assertTrue(colonVal.range().endByte() <= colonEntry.range().endByte());
        assertEquals(2, errorMessages(bothMissing).size());

        // Mid-list `{:}` preserves surviving neighbors on both sides.
        var midColon = recoverableParse("var d = {\"a\": 1, :, \"b\": 2}\n");
        var midDict = assertInstanceOf(DictionaryExpression.class,
                assertInstanceOf(VariableDeclaration.class, midColon.ast().statements().getFirst()).value());
        assertEquals(3, midDict.entries().size());
        assertEquals("\"a\"", assertInstanceOf(LiteralExpression.class, midDict.entries().getFirst().key()).sourceText());
        assertEquals("dictionary.key", assertInstanceOf(ErrorExpression.class, midDict.entries().get(1).key()).nodeType());
        assertEquals("dictionary.value", assertInstanceOf(ErrorExpression.class, midDict.entries().get(1).value()).nodeType());
        assertEquals("\"b\"", assertInstanceOf(LiteralExpression.class, midDict.entries().get(2).key()).sourceText());

        // Legal empty `{}` and trailing-comma `{"a": 1,}` create no spurious entries.
        var emptyDictControl = parse("var d = {}\nvar e = {\"a\": 1,}\n");
        assertEquals(List.of(), errorMessages(emptyDictControl), () -> errorMessages(emptyDictControl).toString());
        assertTrue(assertInstanceOf(DictionaryExpression.class,
                assertInstanceOf(VariableDeclaration.class, emptyDictControl.ast().statements().getFirst()).value())
                .entries().isEmpty());
        assertEquals(1, assertInstanceOf(DictionaryExpression.class,
                assertInstanceOf(VariableDeclaration.class, emptyDictControl.ast().statements().get(1)).value())
                .entries().size());

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

        // `{1+ var x: 3, 2: 3}: pass` retains both the damaged bare-key entry and the surviving `2: 3` entry.
        var multiEntryTailResult = recoverableParse("func f():\n\tmatch x:\n\t\t{1+ var x: 3, 2: 3}: pass\n");
        assertEquals(List.of("Expected expression after \"+\" operator."),
                errorMessages(multiEntryTailResult), () -> errorMessages(multiEntryTailResult).toString());
        var multiMatch = assertInstanceOf(MatchStatement.class,
                onlyFunction(multiEntryTailResult).body().statements().getFirst());
        var multiSection = multiMatch.sections().getFirst();
        var multiDict = assertInstanceOf(DictionaryExpression.class, multiSection.patterns().getFirst());
        assertEquals(2, multiDict.entries().size());
        assertInstanceOf(BinaryExpression.class, multiDict.entries().getFirst().key());
        assertNull(multiDict.entries().getFirst().value());
        assertEquals("2", assertInstanceOf(LiteralExpression.class, multiDict.entries().get(1).key()).sourceText());
        assertEquals("3", assertInstanceOf(LiteralExpression.class, multiDict.entries().get(1).value()).sourceText());
        assertInstanceOf(PassStatement.class, multiSection.body().statements().getFirst());
    }

    @Test
    void matchPatternMissingBindNameAndRestErrorsPreservePositions() {
        // Top-level `var :` preserves an ERROR fragment in the section without a spurious "No pattern found".
        var topBind = recoverableParse("func f(v):\n\tmatch v:\n\t\tvar :\n\t\t\tpass\n");
        var topBindDiag = findErrorByCode(topBind, DiagnosticCodes.MATCH_BIND_MISSING_NAME);
        assertEquals(1, errorMessages(topBind).size(), () -> errorMessages(topBind).toString());
        var topMatch = assertInstanceOf(MatchStatement.class,
                onlyFunction(topBind).body().statements().getFirst());
        var topSection = topMatch.sections().getFirst();
        assertSame(topSection, requireNotNull(topBindDiag.recovery()).owner());
        assertEquals("match.pattern", topBindDiag.recovery().facet());
        var topError = assertInstanceOf(ErrorExpression.class, topSection.patterns().getFirst());
        assertEquals(CstIssueKind.ERROR, topError.kind());
        assertEquals("var", topError.sourceText());
        assertInstanceOf(PassStatement.class, topSection.body().statements().getFirst());

        // Array `[1, var , 3]` keeps element slot count and positions intact.
        var arrayBind = recoverableParse("func f(v):\n\tmatch v:\n\t\t[1, var , 3]:\n\t\t\tpass\n");
        var arrayBindDiag = findErrorByCode(arrayBind, DiagnosticCodes.MATCH_BIND_MISSING_NAME);
        assertEquals(1, errorMessages(arrayBind).size(), () -> errorMessages(arrayBind).toString());
        var arraySection = assertInstanceOf(MatchStatement.class,
                onlyFunction(arrayBind).body().statements().getFirst()).sections().getFirst();
        assertSame(arraySection, requireNotNull(arrayBindDiag.recovery()).owner());
        var arrayPattern = assertInstanceOf(ArrayExpression.class, arraySection.patterns().getFirst());
        assertEquals(3, arrayPattern.elements().size());
        assertEquals("1", assertInstanceOf(LiteralExpression.class, arrayPattern.elements().getFirst()).sourceText());
        assertEquals("var", assertInstanceOf(ErrorExpression.class, arrayPattern.elements().get(1)).sourceText());
        assertEquals("3", assertInstanceOf(LiteralExpression.class, arrayPattern.elements().get(2)).sourceText());

        // Dictionary `{"a": var }` keeps the entry instead of dropping it.
        var dictBind = recoverableParse("func f(v):\n\tmatch v:\n\t\t{\"a\": var }:\n\t\t\tpass\n");
        var dictBindDiag = findErrorByCode(dictBind, DiagnosticCodes.MATCH_BIND_MISSING_NAME);
        assertEquals(1, errorMessages(dictBind).size(), () -> errorMessages(dictBind).toString());
        var dictSection = assertInstanceOf(MatchStatement.class,
                onlyFunction(dictBind).body().statements().getFirst()).sections().getFirst();
        assertSame(dictSection, requireNotNull(dictBindDiag.recovery()).owner());
        var dictPattern = assertInstanceOf(DictionaryExpression.class, dictSection.patterns().getFirst());
        assertEquals(1, dictPattern.entries().size());
        var dictBindError = assertInstanceOf(ErrorExpression.class, dictPattern.entries().getFirst().value());
        assertEquals(CstIssueKind.ERROR, dictBindError.kind());
        assertEquals("var", dictBindError.sourceText());

        // Non-last and duplicate `..` keep trailing elements/entries and emit MATCH_REST_NOT_LAST.
        var nonLastDict = recoverableParse("func f(v):\n\tmatch v:\n\t\t{.., \"k\": 1}:\n\t\t\tpass\n");
        var nonLastDictDiag = findErrorByCode(nonLastDict, DiagnosticCodes.MATCH_REST_NOT_LAST);
        assertEquals(1, errorMessages(nonLastDict).size(), () -> errorMessages(nonLastDict).toString());
        var nonLastDictSection = assertInstanceOf(MatchStatement.class,
                onlyFunction(nonLastDict).body().statements().getFirst()).sections().getFirst();
        assertSame(nonLastDictSection, requireNotNull(nonLastDictDiag.recovery()).owner());
        var nonLastDictExpr = assertInstanceOf(DictionaryExpression.class, nonLastDictSection.patterns().getFirst());
        assertEquals(1, nonLastDictExpr.entries().size());
        assertEquals("\"k\"", assertInstanceOf(LiteralExpression.class, nonLastDictExpr.entries().getFirst().key()).sourceText());
        assertEquals("1", assertInstanceOf(LiteralExpression.class, nonLastDictExpr.entries().getFirst().value()).sourceText());

        var dupRestArray = recoverableParse("func f(v):\n\tmatch v:\n\t\t[.., ..]:\n\t\t\tpass\n");
        var dupRestDiag = findErrorByCode(dupRestArray, DiagnosticCodes.MATCH_REST_NOT_LAST);
        assertEquals(1, errorMessages(dupRestArray).size(), () -> errorMessages(dupRestArray).toString());
        var dupRestSection = assertInstanceOf(MatchStatement.class,
                onlyFunction(dupRestArray).body().statements().getFirst()).sections().getFirst();
        assertSame(dupRestSection, requireNotNull(dupRestDiag.recovery()).owner());
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
        // folding subsequent `(2)` / `[2]` suffixes and preserves both argument/index slots; the
        // outer call on `foo(1+)` also reports `CALL_INVALID_CALLEE` on the non-identifier callee.
        var callResult = recoverableParse("func f():\n\tfoo(1+)(2)\n");
        assertEquals(List.of(
                        "Expected expression after \"+\" operator.",
                        "Cannot call on an expression. Use \".call()\" if it's a Callable."),
                errorMessages(callResult),
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
        // its parameter list, while trailing `+ 2` after `pass` inside the inline lambda reports
        // `STATEMENT_TRAILING` owned by `PassStatement` without breaking the enclosing declaration.
        var lambdaDefault = recoverableParse("var x = func(a = 1+): pass + 2\n");
        assertEquals(List.of(
                        "Expected expression after \"+\" operator.",
                        "Expected end of statement after \"pass\", found \"+\" instead."
                ),
                errorMessages(lambdaDefault), () -> errorMessages(lambdaDefault).toString());
        var variable = assertInstanceOf(VariableDeclaration.class,
                lambdaDefault.ast().statements().getFirst());
        var lambda = assertInstanceOf(LambdaExpression.class, variable.value());
        var passStmt = assertInstanceOf(PassStatement.class, lambda.body().statements().getFirst());
        var trailingDiag = findErrorByCode(lambdaDefault, DiagnosticCodes.STATEMENT_TRAILING);
        assertSame(passStmt, requireNotNull(trailingDiag.recovery()).owner());
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
        // `var x = a = 1`: the inner `=` is diagnosed once and preserves `AssignmentExpression("=", a, 1)`.
        var result = recoverableParse("var x = a = 1\n");
        assertEquals(List.of("Assignment is not allowed inside an expression."), errorMessages(result),
                () -> errorMessages(result).toString());
        var variable = assertInstanceOf(VariableDeclaration.class, result.ast().statements().getFirst());
        var inner = assertInstanceOf(AssignmentExpression.class, variable.value());
        assertEquals("=", inner.operator());
        assertEquals("a", assertInstanceOf(IdentifierExpression.class, inner.left()).name());
        assertEquals("1", assertInstanceOf(LiteralExpression.class, inner.right()).sourceText());

        // `return a =` and `var x = a =`: the disallowed embedded `=` reports once at `=` AND the
        // empty RHS reports `SLOT_ASSIGNMENT_VALUE` with a `MISSING` `assignment.value` marker.
        var returnResult = recoverableParse("func f():\n\treturn a =\n");
        var returnErrors = errorsOf(returnResult);
        assertEquals(2, returnErrors.size(), () -> returnErrors.toString());
        assertEquals(DiagnosticCodes.ASSIGNMENT_DISALLOWED_IN_EXPRESSION, returnErrors.getFirst().code());
        assertEquals(DiagnosticCodes.SLOT_ASSIGNMENT_VALUE, returnErrors.get(1).code());
        var returnStatement = assertInstanceOf(ReturnStatement.class,
                onlyFunction(returnResult).body().statements().getFirst());
        assertSame(returnStatement, requireNotNull(returnErrors.getFirst().recovery()).owner());
        assertSame(returnStatement, requireNotNull(returnErrors.get(1).recovery()).owner());
        var returnAssign = assertInstanceOf(AssignmentExpression.class, returnStatement.value());
        assertEquals("a", assertInstanceOf(IdentifierExpression.class, returnAssign.left()).name());
        var returnRhs = assertInstanceOf(ErrorExpression.class, returnAssign.right());
        assertEquals(CstIssueKind.MISSING, returnRhs.kind());
        assertEquals("assignment.value", returnRhs.nodeType());

        var varTrailingResult = recoverableParse("var x = a =\n");
        var varErrors = errorsOf(varTrailingResult);
        assertEquals(2, varErrors.size(), () -> varErrors.toString());
        assertEquals(DiagnosticCodes.ASSIGNMENT_DISALLOWED_IN_EXPRESSION, varErrors.getFirst().code());
        assertEquals(DiagnosticCodes.SLOT_ASSIGNMENT_VALUE, varErrors.get(1).code());
        var varDecl = assertInstanceOf(VariableDeclaration.class, varTrailingResult.ast().statements().getFirst());
        assertSame(varDecl, requireNotNull(varErrors.getFirst().recovery()).owner());
        assertSame(varDecl, requireNotNull(varErrors.get(1).recovery()).owner());
        var varAssign = assertInstanceOf(AssignmentExpression.class, varDecl.value());
        assertEquals("a", assertInstanceOf(IdentifierExpression.class, varAssign.left()).name());
        var varRhs = assertInstanceOf(ErrorExpression.class, varAssign.right());
        assertEquals(CstIssueKind.MISSING, varRhs.kind());
        assertEquals("assignment.value", varRhs.nodeType());
    }

    @Test
    void recoveredInfixAndTernaryElseRecovery() {
        // `x as`, `x is`, `x is not`: preserve `RecoveredInfixExpression` with zero-width `MISSING`
        // type marker and attribute `MISSING_TYPE_CAST` / `MISSING_TYPE_TYPE_TEST` to the variable.
        var castMissing = recoverableParse("var a = x as\n");
        var castVar = assertInstanceOf(VariableDeclaration.class, castMissing.ast().statements().getFirst());
        var castRecovered = assertInstanceOf(RecoveredInfixExpression.class, castVar.value());
        assertEquals("as", castRecovered.operator());
        assertEquals("x", assertInstanceOf(IdentifierExpression.class, castRecovered.operand()).name());
        assertEquals(CstIssueKind.MISSING, castRecovered.problem().kind());
        assertEquals("cast.type", castRecovered.problem().nodeType());
        assertEquals(0, castRecovered.problem().range().endByte() - castRecovered.problem().range().startByte());
        var castErr = errorsOf(castMissing).getFirst();
        assertEquals(DiagnosticCodes.MISSING_TYPE_CAST, castErr.code());
        assertSame(castVar, requireNotNull(castErr.recovery()).owner());

        var isNotMissing = recoverableParse("var c = x is not\n");
        var isNotVar = assertInstanceOf(VariableDeclaration.class, isNotMissing.ast().statements().getFirst());
        var isNotRecovered = assertInstanceOf(RecoveredInfixExpression.class, isNotVar.value());
        assertEquals("is not", isNotRecovered.operator());
        assertEquals(CstIssueKind.MISSING, isNotRecovered.problem().kind());
        assertEquals("type_test.type", isNotRecovered.problem().nodeType());
        assertEquals(DiagnosticCodes.MISSING_TYPE_TYPE_TEST, errorsOf(isNotMissing).getFirst().code());

        // Damaged inner types (`Array[]`, `void`) produce an `ERROR` marker over the consumed slice
        // without emitting a duplicate `MISSING_TYPE_CAST` / `MISSING_TYPE_TYPE_TEST`.
        var damagedCast = recoverableParse("var d = x as Array[]\n");
        var damagedCastErrors = errorsOf(damagedCast);
        assertEquals(1, damagedCastErrors.size(), () -> damagedCastErrors.toString());
        assertFalse(damagedCastErrors.stream().anyMatch(e -> DiagnosticCodes.MISSING_TYPE_CAST.equals(e.code())));
        var damagedCastNode = assertInstanceOf(RecoveredInfixExpression.class,
                assertInstanceOf(VariableDeclaration.class, damagedCast.ast().statements().getFirst()).value());
        assertEquals(CstIssueKind.ERROR, damagedCastNode.problem().kind());
        assertEquals("Array[]", damagedCastNode.problem().sourceText());

        var voidTest = recoverableParse("var e = x is void\n");
        var voidTestErrors = errorsOf(voidTest);
        assertEquals(1, voidTestErrors.size(), () -> voidTestErrors.toString());
        assertFalse(voidTestErrors.stream().anyMatch(e -> DiagnosticCodes.MISSING_TYPE_TYPE_TEST.equals(e.code())));
        var voidTestNode = assertInstanceOf(RecoveredInfixExpression.class,
                assertInstanceOf(VariableDeclaration.class, voidTest.ast().statements().getFirst()).value());
        assertEquals("is", voidTestNode.operator());
        assertEquals(CstIssueKind.ERROR, voidTestNode.problem().kind());
        assertEquals("void", voidTestNode.problem().sourceText());

        // `x ?`: returns `RecoveredInfixExpression(x, "?", ERROR "?")` with registered code.
        var question = recoverableParse("var q = x ?\n");
        var qVar = assertInstanceOf(VariableDeclaration.class, question.ast().statements().getFirst());
        var qRecovered = assertInstanceOf(RecoveredInfixExpression.class, qVar.value());
        assertEquals("?", qRecovered.operator());
        assertEquals("x", assertInstanceOf(IdentifierExpression.class, qRecovered.operand()).name());
        assertEquals(CstIssueKind.ERROR, qRecovered.problem().kind());
        assertEquals("?", qRecovered.problem().sourceText());
        var qErr = errorsOf(question).getFirst();
        assertEquals(DiagnosticCodes.SYNTAX_UNEXPECTED_TOKEN, qErr.code());
        assertSame(qVar, requireNotNull(qErr.recovery()).owner());

        // Cross-line missing `else` inside brackets must not steal the next line's expression.
        var crossLineArray = recoverableParse("var arr = [\n\t1 if c\n\t2\n]\n");
        var arrVar = assertInstanceOf(VariableDeclaration.class, crossLineArray.ast().statements().getFirst());
        var arr = assertInstanceOf(ArrayExpression.class, arrVar.value());
        assertEquals(2, arr.elements().size(), () -> "elements: " + arr.elements());
        var cond = assertInstanceOf(ConditionalExpression.class, arr.elements().getFirst());
        var missingFalse = assertInstanceOf(ErrorExpression.class, cond.right());
        assertEquals(CstIssueKind.MISSING, missingFalse.kind());
        assertEquals("ternary.else", missingFalse.nodeType());
        assertEquals(missingFalse.range().startByte(), missingFalse.range().endByte());
        assertEquals("2", assertInstanceOf(LiteralExpression.class, arr.elements().get(1)).sourceText());

        // Unclosed grouping + cross-line missing `else` leaves the following top-level statement intact.
        var crossLineTopLevel = recoverableParse("var a = (1 if c\nvar b = 2\n");
        assertEquals(2, crossLineTopLevel.ast().statements().size(),
                () -> "statements: " + crossLineTopLevel.ast().statements());
        var aVar = assertInstanceOf(VariableDeclaration.class, crossLineTopLevel.ast().statements().getFirst());
        var aCond = assertInstanceOf(ConditionalExpression.class, aVar.value());
        assertEquals(CstIssueKind.MISSING, assertInstanceOf(ErrorExpression.class, aCond.right()).kind());
        var bVar = assertInstanceOf(VariableDeclaration.class, crossLineTopLevel.ast().statements().get(1));
        assertEquals("b", bVar.name());
    }

    @Test
    void statementTrailingDiagnosticsAnchorGarbageAndBindOwner() {
        // `var q = a..(1)`: trailing error spans `..(1)` (9..14), `q.range()` stops at `a` (0..9),
        // and the diagnostic binds by identity to `q` (`statement.trailing`).
        var qSource = "var q = a..(1)\n";
        var qResult = recoverableParse(qSource);
        var qErr = findErrorByCode(qResult, DiagnosticCodes.STATEMENT_TRAILING);
        var qVar = assertInstanceOf(VariableDeclaration.class, qResult.ast().statements().getFirst());
        assertEquals(0, qVar.range().startByte());
        assertEquals(9, qVar.range().endByte());
        assertEquals(9, qErr.range().startByte());
        assertEquals(14, qErr.range().endByte());
        assertEquals("..(1)", qSource.substring(qErr.range().startByte(), qErr.range().endByte()));
        assertSame(qVar, requireNotNull(qErr.recovery()).owner());
        assertEquals("statement.trailing", requireNotNull(qErr.recovery()).facet());

        // `return 1 2`: trailing error anchors at `2`, not `1`; AST range stops at `1`.
        var retSource = "func f():\n\treturn 1 2\n";
        var retResult = recoverableParse(retSource);
        var retErr = findErrorByCode(retResult, DiagnosticCodes.STATEMENT_TRAILING);
        var retStmt = assertInstanceOf(ReturnStatement.class,
                onlyFunction(retResult).body().statements().getFirst());
        assertEquals(retSource.indexOf('2'), retErr.range().startByte());
        assertEquals(retSource.indexOf('2') + 1, retErr.range().endByte());
        assertTrue(retStmt.range().endByte() < retErr.range().startByte());
        assertSame(retStmt, requireNotNull(retErr.recovery()).owner());
        assertEquals("statement.trailing", requireNotNull(retErr.recovery()).facet());

        // Delayed-commit provisional tail frames: class_name, class_name+extends, nested class extends,
        // single-line property, and bodyless function/constructor all keep AST end before trailing text.
        var classNameOnly = recoverableParse("class_name Foo extra\n");
        var cnOnly = assertInstanceOf(ClassNameStatement.class, classNameOnly.ast().statements().getFirst());
        var cnOnlyErr = findErrorByCode(classNameOnly, DiagnosticCodes.STATEMENT_TRAILING);
        assertSame(cnOnly, requireNotNull(cnOnlyErr.recovery()).owner());
        assertEquals("statement.trailing", requireNotNull(cnOnlyErr.recovery()).facet());
        assertEquals(14, cnOnly.range().endByte());
        assertEquals(15, cnOnlyErr.range().startByte());
        assertEquals(20, cnOnlyErr.range().endByte());

        var classNameExt = recoverableParse("class_name Foo extends Bar extra\n");
        var cnExt = assertInstanceOf(ClassNameStatement.class, classNameExt.ast().statements().getFirst());
        var cnExtErr = findErrorByCode(classNameExt, DiagnosticCodes.STATEMENT_TRAILING);
        assertSame(cnExt, requireNotNull(cnExtErr.recovery()).owner());
        assertEquals(26, cnExt.range().endByte());
        assertEquals(27, cnExtErr.range().startByte());

        var nestedExt = recoverableParse("class Inner:\n\textends Base extra\n\tpass\n");
        var innerClass = assertInstanceOf(ClassDeclaration.class, nestedExt.ast().statements().getFirst());
        var nestedExtErr = findErrorByCode(nestedExt, DiagnosticCodes.STATEMENT_TRAILING);
        assertSame(innerClass, requireNotNull(nestedExtErr.recovery()).owner());
        assertEquals("statement.trailing", requireNotNull(nestedExtErr.recovery()).facet());

        var propResult = recoverableParse("var p: get = g, set = s extra\n");
        var propVar = assertInstanceOf(VariableDeclaration.class, propResult.ast().statements().getFirst());
        var propErr = findErrorByCode(propResult, DiagnosticCodes.STATEMENT_TRAILING);
        assertSame(propVar, requireNotNull(propErr.recovery()).owner());
        assertEquals(23, propVar.range().endByte());
        assertEquals(24, propErr.range().startByte());
        assertEquals(29, propErr.range().endByte());

        var bodylessFn = recoverableParse("func f(x) -> int extra\n");
        var fnDecl = assertInstanceOf(FunctionDeclaration.class, bodylessFn.ast().statements().getFirst());
        var fnErr = findErrorByCode(bodylessFn, DiagnosticCodes.STATEMENT_TRAILING);
        assertSame(fnDecl, requireNotNull(fnErr.recovery()).owner());
        assertEquals(16, fnDecl.range().endByte());
        assertEquals(17, fnErr.range().startByte());

        var bodylessInit = recoverableParse("func _init(x) extra\n");
        var initDecl = assertInstanceOf(ConstructorDeclaration.class, bodylessInit.ast().statements().getFirst());
        var initErr = findErrorByCode(bodylessInit, DiagnosticCodes.STATEMENT_TRAILING);
        assertSame(initDecl, requireNotNull(initErr.recovery()).owner());
        assertEquals(13, initDecl.range().endByte());
        assertEquals(14, initErr.range().startByte());

        // Top-level & suite statements: extends, const, signal, enum, pass, break, continue, breakpoint, assert.
        var topDecls = recoverableParse("""
                extends Node extra
                const C = 1 extra
                signal sig() extra
                enum E { A } extra
                pass extra
                """);
        var topErrors = errorsOf(topDecls);
        assertEquals(5, topErrors.size(), () -> topErrors.toString());
        assertSame(assertInstanceOf(ExtendsStatement.class, topDecls.ast().statements().get(0)),
                requireNotNull(topErrors.get(0).recovery()).owner());
        assertSame(assertInstanceOf(VariableDeclaration.class, topDecls.ast().statements().get(1)),
                requireNotNull(topErrors.get(1).recovery()).owner());
        assertSame(assertInstanceOf(SignalStatement.class, topDecls.ast().statements().get(2)),
                requireNotNull(topErrors.get(2).recovery()).owner());
        assertSame(assertInstanceOf(EnumDeclaration.class, topDecls.ast().statements().get(3)),
                requireNotNull(topErrors.get(3).recovery()).owner());
        assertSame(assertInstanceOf(PassStatement.class, topDecls.ast().statements().get(4)),
                requireNotNull(topErrors.get(4).recovery()).owner());

        var suiteStmts = recoverableParse("""
                func f():
                \tfor i in [1]:
                \t\tbreak extra
                \t\tcontinue extra
                \tbreakpoint extra
                \tassert(true) extra
                \tpass extra
                """);
        var suiteErrors = errorsOf(suiteStmts);
        assertEquals(5, suiteErrors.size(), () -> suiteErrors.toString());
        var fnBody = onlyFunction(suiteStmts).body().statements();
        var loopBody = assertInstanceOf(ForStatement.class, fnBody.get(0)).body().statements();
        assertSame(assertInstanceOf(BreakStatement.class, loopBody.get(0)),
                requireNotNull(suiteErrors.get(0).recovery()).owner());
        assertSame(assertInstanceOf(ContinueStatement.class, loopBody.get(1)),
                requireNotNull(suiteErrors.get(1).recovery()).owner());
        assertSame(assertInstanceOf(BreakpointStatement.class, fnBody.get(1)),
                requireNotNull(suiteErrors.get(2).recovery()).owner());
        assertSame(assertInstanceOf(AssertStatement.class, fnBody.get(2)),
                requireNotNull(suiteErrors.get(3).recovery()).owner());
        assertSame(assertInstanceOf(PassStatement.class, fnBody.get(3)),
                requireNotNull(suiteErrors.get(4).recovery()).owner());

        // Inline lambda trailing garbage before an enclosing delimiter/comma reports
        // `STATEMENT_TRAILING` on the inner statement while preserving the remaining call arguments.
        var lambdaSource = "var x = call(func(): return 1 2, 3)\n";
        var lambdaBoundary = recoverableParse(lambdaSource);
        var lambdaTrailing = findErrorByCode(lambdaBoundary, DiagnosticCodes.STATEMENT_TRAILING);
        assertEquals("2", lambdaSource.substring(lambdaTrailing.range().startByte(), lambdaTrailing.range().endByte()));
        var lambdaCall = assertInstanceOf(CallExpression.class,
                requireNotNull(assertInstanceOf(VariableDeclaration.class,
                        lambdaBoundary.ast().statements().getFirst()).value()));
        assertEquals(2, lambdaCall.arguments().size());
        var innerLambda = assertInstanceOf(LambdaExpression.class, lambdaCall.arguments().getFirst());
        var innerReturn = assertInstanceOf(ReturnStatement.class, innerLambda.body().statements().getFirst());
        assertSame(innerReturn, requireNotNull(lambdaTrailing.recovery()).owner());
        assertEquals("statement.trailing", requireNotNull(lambdaTrailing.recovery()).facet());
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

        // Default-slot diagnostics inside a dropped duplicate parameter fall back to the outer signature.
        var dupParamSlot = recoverableParse("func f(a, a = ): pass\n");
        var slotDiag = findErrorByCode(dupParamSlot, DiagnosticCodes.SLOT_PARAMETER_DEFAULT);
        assertEquals(new RecoveryLink(onlyFunction(dupParamSlot), "function.signature"), slotDiag.recovery());
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

    @Test
    void requiredExplicitTypeSlotsReportContextualCodesWithoutDoubleReportingDamagedTypes() {
        var paramRes = recoverableParse("func f(x:, y: int): pass\n");
        var paramFn = onlyFunction(paramRes);
        assertEquals(2, paramFn.parameters().size());
        var paramMissing = findErrorByCode(paramRes, DiagnosticCodes.MISSING_TYPE_PARAMETER);
        assertEquals(new RecoveryLink(paramFn.parameters().getFirst(), "parameter.type"), paramMissing.recovery());
        assertEquals("int", paramFn.parameters().get(1).type().sourceText());
        assertInstanceOf(PassStatement.class, paramFn.body().statements().getFirst());

        var signalRes = recoverableParse("signal s(x:)\n");
        var signalStmt = assertInstanceOf(SignalStatement.class, signalRes.ast().statements().getFirst());
        assertEquals(1, signalStmt.parameters().size());
        var sigMissing = findErrorByCode(signalRes, DiagnosticCodes.MISSING_TYPE_SIGNAL_PARAMETER);
        assertEquals(new RecoveryLink(signalStmt.parameters().getFirst(), "parameter.type"), sigMissing.recovery());

        var lambdaRes = recoverableParse("var fn = func(x:): return x\n");
        var lambdaVar = assertInstanceOf(VariableDeclaration.class, lambdaRes.ast().statements().getFirst());
        var lambdaExpr = assertInstanceOf(LambdaExpression.class, lambdaVar.value());
        var lambdaMissing = findErrorByCode(lambdaRes, DiagnosticCodes.MISSING_TYPE_LAMBDA_PARAMETER);
        assertEquals(new RecoveryLink(lambdaExpr.parameters().getFirst(), "parameter.type"), lambdaMissing.recovery());

        var varSemicolonRes = recoverableParse("func f():\n\tvar x:;\n\tpass\n");
        var varFn = onlyFunction(varSemicolonRes);
        var localVar = assertInstanceOf(VariableDeclaration.class, varFn.body().statements().getFirst());
        var varMissing = findErrorByCode(varSemicolonRes, DiagnosticCodes.MISSING_TYPE_VARIABLE);
        assertEquals(new RecoveryLink(localVar, "variable.type"), varMissing.recovery());
        assertInstanceOf(PassStatement.class, varFn.body().statements().get(1));

        var constRes = recoverableParse("const X:\n");
        var constDecl = assertInstanceOf(VariableDeclaration.class, constRes.ast().statements().getFirst());
        var constMissing = findErrorByCode(constRes, DiagnosticCodes.MISSING_TYPE_CONSTANT);
        var constValueMissing = findErrorByCode(constRes, DiagnosticCodes.SLOT_CONSTANT_VALUE);
        assertEquals(new RecoveryLink(constDecl, "constant.type"), constMissing.recovery());
        assertEquals(new RecoveryLink(constDecl, "constant.value"), constValueMissing.recovery());

        // Duplicate dropped parameter falls back to enclosing signature / signal.parameter frame.
        var dupFuncRes = recoverableParse("func f(x:, x:): pass\n");
        var dupFn = onlyFunction(dupFuncRes);
        assertEquals(1, dupFn.parameters().size());
        var dupParamMissings = errorsOf(dupFuncRes).stream()
                .filter(d -> DiagnosticCodes.MISSING_TYPE_PARAMETER.equals(d.code()))
                .toList();
        assertEquals(2, dupParamMissings.size(), () -> errorsOf(dupFuncRes).toString());
        assertEquals(new RecoveryLink(dupFn.parameters().getFirst(), "parameter.type"),
                dupParamMissings.getFirst().recovery());
        assertEquals(new RecoveryLink(dupFn, "function.signature"), dupParamMissings.get(1).recovery());

        var dupSigRes = recoverableParse("signal s(x:, x:)\n");
        var dupSig = assertInstanceOf(SignalStatement.class, dupSigRes.ast().statements().getFirst());
        var dupSigMissings = errorsOf(dupSigRes).stream()
                .filter(d -> DiagnosticCodes.MISSING_TYPE_SIGNAL_PARAMETER.equals(d.code()))
                .toList();
        assertEquals(2, dupSigMissings.size(), () -> errorsOf(dupSigRes).toString());
        assertEquals(new RecoveryLink(dupSig.parameters().getFirst(), "parameter.type"),
                dupSigMissings.getFirst().recovery());
        assertEquals(new RecoveryLink(dupSig, "signal.parameter"), dupSigMissings.get(1).recovery());

        // Already-diagnosed container/void errors must not emit duplicate MISSING_TYPE_* diagnostics.
        for (var damagedTypeSource : List.of(
                "var x: Array[]\n",
                "var x: void\n",
                "var x: Array[void]\n",
                "const X: Array[] = []\n",
                "const X: void = 1\n",
                "func f(x: Array[]): pass\n",
                "func f(x: void): pass\n",
                "func f() -> Array[]: pass\n",
                "signal s(x: void)\n",
                "func f():\n\tfor i: Array[] in xs:\n\t\tpass\n",
                "func f():\n\tfor i: void in xs:\n\t\tpass\n"
        )) {
            var res = recoverableParse(damagedTypeSource);
            var missingTypeCount = errorsOf(res).stream()
                    .filter(d -> d.code() != null
                            && d.code().startsWith("parse.missing_type.")
                            && !DiagnosticCodes.MISSING_TYPE_GENERIC_ARGUMENT.equals(d.code()))
                    .count();
            assertEquals(0, missingTypeCount,
                    () -> "Unexpected outer missing-type diagnostic for " + damagedTypeSource + ": " + errorsOf(res));
        }

        // Valid spaced inferred types and property forms must never emit missing-type diagnostics.
        for (var validSource : List.of(
                "var x : = 1\n",
                "const X : = 1\n",
                "func f(x : = 1): pass\n",
                "var x:\n\tget:\n\t\treturn 1\n",
                "var x: get = fn\n"
        )) {
            var res = new GdScriptParser().parse(validSource);
            assertTrue(errorsOf(res).isEmpty(), () -> validSource + " -> " + errorsOf(res));
        }
    }

    @Test
    void scopeAwareAnnotationsValidateLevelsTargetsDanglingAndPropertyRecovery() {
        // Script-header solo directives, prefix root-class annotations, standalone directives,
        // and custom user annotations remain clean.
        for (var validSource : List.of(
                "@tool\n",
                "@icon(\"res://icon.svg\")\n",
                "@static_unload\n",
                "@abstract\n",
                "@warning_ignore(\"unused_variable\")\n",
                "@tool\n@abstract\nclass_name RootClass\n",
                "@export_category(\"Cat\")\n@export_group(\"Grp\", \"g_\")\n@export_subgroup(\"Sub\")\n@export var g_x = 1\n",
                "@warning_ignore_start(\"unused_variable\")\nvar a = 1\n@warning_ignore_restore(\"unused_variable\")\n",
                "func f():\n\t@warning_ignore_start(\"unused\")\n\tvar x = 1\n\t@warning_ignore_restore(\"unused\")\n",
                "@custom(\"arg\")\nvar custom_var = 1\n",
                "func f():\n\t@custom\n\tvar local_var = 1\n",
                "var x:\n\tget:\n\t\t@warning_ignore(\"unused\")\n\t\tvar local_var = 1\n\t\treturn local_var\n"
        )) {
            var clean = new GdScriptParser().parse(validSource);
            assertTrue(errorsOf(clean).isEmpty(), () -> validSource + " -> " + errorsOf(clean));
        }

        // Script-header validation errors (duplicate, missing/bad @icon, standalone newline/pairing).
        var scriptErrors = recoverableParse("""
                @tool
                @tool
                @icon("")
                @static_unload
                @static_unload
                @export_group("Grp") var bad = 1
                @warning_ignore_start("unused")
                @warning_ignore_start("unused")
                @warning_ignore_restore("other")
                """);
        assertTrue(errorsOf(scriptErrors).stream().anyMatch(d -> d.message().contains("\"@tool\" annotation can only be used once")));
        assertTrue(errorsOf(scriptErrors).stream().anyMatch(d -> d.message().contains("must contain the path to the icon")));
        assertTrue(errorsOf(scriptErrors).stream().anyMatch(d -> d.message().contains("can only be used once per script")));
        assertTrue(errorsOf(scriptErrors).stream().anyMatch(d -> d.message().contains("Expected newline after a standalone annotation.")));
        assertTrue(errorsOf(scriptErrors).stream().anyMatch(d -> d.message().contains("already being ignored")));
        assertTrue(errorsOf(scriptErrors).stream().anyMatch(d -> d.message().contains("not being ignored")));

        // Invalid level vs invalid target attribution (`annotation.target`).
        var invalidLevel = recoverableParse("""
                var head = 1
                @tool
                var after_tool = 2
                class Outer:
                \t@static_unload
                \tclass Inner:
                \t\tpass
                func f():
                \t@export_group("Bad")
                \t@export
                \tvar local = 1
                """);
        var levelCodes = errorsOf(invalidLevel).stream()
                .filter(d -> DiagnosticCodes.ANNOTATION_INVALID_LEVEL.equals(d.code()))
                .toList();
        assertEquals(4, levelCodes.size(), () -> errorsOf(invalidLevel).toString());
        for (var diag : levelCodes) {
            assertNotNull(diag.recovery());
            assertInstanceOf(AnnotationStatement.class, diag.recovery().owner());
            assertEquals("annotation.target", diag.recovery().facet());
        }

        var invalidTarget = recoverableParse("""
                @export
                func f(): pass
                @rpc
                var x = 1
                @abstract
                var y = 2
                @onready
                const C = 1
                @rpc
                class Inner: pass
                """);
        var targetCodes = errorsOf(invalidTarget).stream()
                .filter(d -> DiagnosticCodes.ANNOTATION_INVALID_TARGET.equals(d.code()))
                .toList();
        assertEquals(5, targetCodes.size(), () -> errorsOf(invalidTarget).toString());
        for (var diag : targetCodes) {
            assertNotNull(diag.recovery());
            assertInstanceOf(AnnotationStatement.class, diag.recovery().owner());
            assertEquals("annotation.target", diag.recovery().facet());
        }

        var staticUnloadAfterExtends = recoverableParse("extends Node\n@static_unload\nclass Inner: pass\n");
        assertEquals(
                List.of(DiagnosticCodes.ANNOTATION_INVALID_LEVEL),
                errorsOf(staticUnloadAfterExtends).stream().map(AstDiagnostic::code).toList()
        );

        // Scope-bound dangling annotations: nested class, if-suite, lambda, failed local `var`, root EOF.
        var nestedDangling = recoverableParse("""
                class Inner:
                \t@export
                var outer = 1
                func f(cond):
                \tif cond:
                \t\t@warning_ignore("a")
                \t@warning_ignore("b")
                \tvar
                \tvar ok = 1
                var lam = g(func(): @x, 2)
                var tail = 1
                @warning_ignore("c")
                """);
        var danglingCodes = errorsOf(nestedDangling).stream()
                .filter(d -> DiagnosticCodes.ANNOTATION_DANGLING.equals(d.code()))
                .toList();
        assertEquals(5, danglingCodes.size(), () -> errorsOf(nestedDangling).toString());
        for (var diag : danglingCodes) {
            assertNotNull(diag.recovery());
            assertInstanceOf(AnnotationStatement.class, diag.recovery().owner());
            assertEquals("annotation.target", diag.recovery().facet());
        }
        var fDecl = assertInstanceOf(FunctionDeclaration.class, nestedDangling.ast().statements().get(2));
        var okVar = fDecl.body().statements().stream()
                .filter(VariableDeclaration.class::isInstance)
                .map(VariableDeclaration.class::cast)
                .filter(v -> "ok".equals(v.name()))
                .findFirst()
                .orElseThrow();
        assertEquals("ok", okVar.name());
        assertTrue(fDecl.body().statements().stream()
                .anyMatch(s -> s instanceof AnnotationStatement ann && "warning_ignore".equals(ann.name())));

        // Property accessor recovery children: both dangling (`@warning_ignore`) and invalid-level
        // (`@export`) preserve source order on the owning `VariableDeclaration`, attribute to
        // `variable.property`, and nested argument errors attribute to `AnnotationStatement`.
        var accessorMixed = recoverableParse("""
                var prop:
                \tget:
                \t\t@warning_ignore(1+)
                \tset(v):
                \t\t@export
                \t\tvar local = 1
                var sibling = 2
                """);
        var prop = assertInstanceOf(VariableDeclaration.class, accessorMixed.ast().statements().getFirst());
        assertEquals(2, prop.recoveryAnnotations().size());
        assertEquals("warning_ignore", prop.recoveryAnnotations().get(0).name());
        assertEquals("export", prop.recoveryAnnotations().get(1).name());
        var propSibling = assertInstanceOf(VariableDeclaration.class, accessorMixed.ast().statements().get(1));
        assertEquals("sibling", propSibling.name());
        assertTrue(propSibling.recoveryAnnotations().isEmpty());

        var argErr = findErrorByCode(accessorMixed, DiagnosticCodes.SLOT_BINARY_RIGHT);
        assertSame(prop.recoveryAnnotations().getFirst(), argErr.recovery().owner());
        assertEquals("annotation.arguments", argErr.recovery().facet());

        var propDangling = findErrorByCode(accessorMixed, DiagnosticCodes.ANNOTATION_DANGLING);
        assertSame(prop, propDangling.recovery().owner());
        assertEquals("variable.property", propDangling.recovery().facet());

        var propLevel = findErrorByCode(accessorMixed, DiagnosticCodes.ANNOTATION_INVALID_LEVEL);
        assertSame(prop, propLevel.recovery().owner());
        assertEquals("variable.property", propLevel.recovery().facet());
    }

    private static AstDiagnostic findErrorByCode(AstMappingResult result, String code) {
        return errorsOf(result).stream()
                .filter(d -> code.equals(d.code()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing diagnostic " + code + " in: " + errorsOf(result)));
    }

    @Test
    void returnAndForRecoveryStopBeforeUnshieldedEnclosingCallBoundaries() {
        var unclosedBracketInCall = recoverableParse("func f(): g(func(): return =[, 1)\n");
        assertTrue(errorsOf(unclosedBracketInCall).stream()
                        .noneMatch(d -> DiagnosticCodes.MISSING_CLOSER_CALL.equals(d.code())),
                () -> errorsOf(unclosedBracketInCall).toString());
        var call1 = assertInstanceOf(CallExpression.class,
                assertInstanceOf(ExpressionStatement.class,
                        onlyFunction(unclosedBracketInCall).body().statements().getFirst()).expression());
        assertEquals(2, call1.arguments().size());
        assertInstanceOf(LambdaExpression.class, call1.arguments().getFirst());
        assertEquals("1", assertInstanceOf(LiteralExpression.class, call1.arguments().get(1)).sourceText());

        var unclosedBraceInCall = recoverableParse("func f(): g(func(): return ={1, 2)\n");
        assertTrue(errorsOf(unclosedBraceInCall).stream()
                        .noneMatch(d -> DiagnosticCodes.MISSING_CLOSER_CALL.equals(d.code())),
                () -> errorsOf(unclosedBraceInCall).toString());
        var call2 = assertInstanceOf(CallExpression.class,
                assertInstanceOf(ExpressionStatement.class,
                        onlyFunction(unclosedBraceInCall).body().statements().getFirst()).expression());
        assertEquals(2, call2.arguments().size());
        assertInstanceOf(LambdaExpression.class, call2.arguments().getFirst());
        assertEquals("2", assertInstanceOf(LiteralExpression.class, call2.arguments().get(1)).sourceText());

        var shieldedTupleControl = recoverableParse("func f(): g(func(): return =(1, 2), 3)\n");
        assertTrue(errorsOf(shieldedTupleControl).stream()
                        .noneMatch(d -> DiagnosticCodes.MISSING_CLOSER_CALL.equals(d.code())),
                () -> errorsOf(shieldedTupleControl).toString());
        var call3 = assertInstanceOf(CallExpression.class,
                assertInstanceOf(ExpressionStatement.class,
                        onlyFunction(shieldedTupleControl).body().statements().getFirst()).expression());
        assertEquals(2, call3.arguments().size());
        assertEquals("3", assertInstanceOf(LiteralExpression.class, call3.arguments().get(1)).sourceText());

        var forBadTypeInCall = recoverableParse("func f(): g(func(): for x: 3, 1)\n");
        assertTrue(errorsOf(forBadTypeInCall).stream()
                        .noneMatch(d -> DiagnosticCodes.MISSING_CLOSER_CALL.equals(d.code())),
                () -> errorsOf(forBadTypeInCall).toString());
        var call4 = assertInstanceOf(CallExpression.class,
                assertInstanceOf(ExpressionStatement.class,
                        onlyFunction(forBadTypeInCall).body().statements().getFirst()).expression());
        assertEquals(2, call4.arguments().size());
        assertInstanceOf(LambdaExpression.class, call4.arguments().getFirst());
        assertEquals("1", assertInstanceOf(LiteralExpression.class, call4.arguments().get(1)).sourceText());

        var forBadTypeWithInControl = recoverableParse("func f(): g(func(): for x: 3 in xs: pass, 1)\n");
        var call5 = assertInstanceOf(CallExpression.class,
                assertInstanceOf(ExpressionStatement.class,
                        onlyFunction(forBadTypeWithInControl).body().statements().getFirst()).expression());
        assertEquals(2, call5.arguments().size());
        var forLambda = assertInstanceOf(LambdaExpression.class, call5.arguments().getFirst());
        var forStmt = assertInstanceOf(ForStatement.class, forLambda.body().statements().getFirst());
        assertEquals("xs", assertInstanceOf(IdentifierExpression.class, forStmt.iterable()).name());
        assertEquals("1", assertInstanceOf(LiteralExpression.class, call5.arguments().get(1)).sourceText());
    }

    @Test
    void sameLogicalLineLexicalErrorsClaimSlotOwnershipWhileLaterLineErrorsStayStandalone() {
        var returnUnterminated = recoverableParse("func f():\n\treturn \"oops");
        var fn = onlyFunction(returnUnterminated);
        var ret = assertInstanceOf(ReturnStatement.class, fn.body().statements().getFirst());
        var retErrVal = assertInstanceOf(ErrorExpression.class, ret.value());
        assertEquals(CstIssueKind.ERROR, retErrVal.kind());
        var retLex = findErrorByCode(returnUnterminated, DiagnosticCodes.LEXICAL_ERROR);
        assertNotNull(retLex.recovery());
        assertSame(ret, retLex.recovery().owner());
        assertEquals("return.value", retLex.recovery().facet());

        var varLexSlot = recoverableParse("var x = §\n");
        var varDecl = assertInstanceOf(VariableDeclaration.class, varLexSlot.ast().statements().getFirst());
        assertEquals(CstIssueKind.ERROR, assertInstanceOf(ErrorExpression.class, varDecl.value()).kind());
        var varLex = findErrorByCode(varLexSlot, DiagnosticCodes.LEXICAL_ERROR);
        assertNotNull(varLex.recovery());
        assertSame(varDecl, varLex.recovery().owner());
        assertEquals("variable.initializer", varLex.recovery().facet());

        var varNextLineLex = recoverableParse("var x = 1\n§\n");
        assertNull(findErrorByCode(varNextLineLex, DiagnosticCodes.LEXICAL_ERROR).recovery());

        var bareReturnNextLineLex = recoverableParse("func f():\n\treturn\n\t§\n");
        var bareRet = assertInstanceOf(ReturnStatement.class,
                onlyFunction(bareReturnNextLineLex).body().statements().getFirst());
        assertNull(bareRet.value());
        assertNull(findErrorByCode(bareReturnNextLineLex, DiagnosticCodes.LEXICAL_ERROR).recovery());

        var valueReturnNextLineLex = recoverableParse("func f():\n\treturn 1\n\t§\n");
        var valRet = assertInstanceOf(ReturnStatement.class,
                onlyFunction(valueReturnNextLineLex).body().statements().getFirst());
        assertEquals("1", assertInstanceOf(LiteralExpression.class, valRet.value()).sourceText());
        assertNull(findErrorByCode(valueReturnNextLineLex, DiagnosticCodes.LEXICAL_ERROR).recovery());
    }

    @Test
    void discardedExpressionsAndMatchAnnotationsRetainSurvivingOwnersAndPropertyRecovery() {
        var badNodePath = recoverableParse("func f():\n\t$foo//\n");
        var fn = onlyFunction(badNodePath);
        var nodePathDiag = findErrorByCode(badNodePath, DiagnosticCodes.NODE_PATH_INVALID);
        assertNotNull(nodePathDiag.recovery());
        assertSame(fn, nodePathDiag.recovery().owner());
        assertEquals("suite.body", nodePathDiag.recovery().facet());

        var matchAnnotationArg = recoverableParse("func f():\n\tmatch x:\n\t\t@warning_ignore(,)\n\t\t_: pass\n");
        var slotDiag = findErrorByCode(matchAnnotationArg, DiagnosticCodes.SLOT_ANNOTATION_ARGUMENT);
        assertNotNull(slotDiag.recovery());
        assertSame(
                assertInstanceOf(MatchStatement.class, onlyFunction(matchAnnotationArg).body().statements().getFirst()),
                slotDiag.recovery().owner()
        );
        assertEquals("match.section", slotDiag.recovery().facet());

        var fnMatchAnnotations = recoverableParse("func f():\n\tmatch x:\n\t\t@export\n\t\t@warning_ignore(\"a\")\n");
        var fnMatchStmt = assertInstanceOf(MatchStatement.class,
                onlyFunction(fnMatchAnnotations).body().statements().getFirst());
        var fnMatchLevel = findErrorByCode(fnMatchAnnotations, DiagnosticCodes.ANNOTATION_INVALID_LEVEL);
        assertSame(fnMatchStmt, requireNotNull(fnMatchLevel.recovery()).owner());
        assertEquals("match.section", requireNotNull(fnMatchLevel.recovery()).facet());
        var fnMatchDangling = findErrorByCode(fnMatchAnnotations, DiagnosticCodes.ANNOTATION_DANGLING);
        assertSame(fnMatchStmt, requireNotNull(fnMatchDangling.recovery()).owner());
        assertEquals("match.section", requireNotNull(fnMatchDangling.recovery()).facet());

        var accessorMatchInvalidLevel = recoverableParse("""
                var prop:
                \tget:
                \t\tmatch x:
                \t\t\t@export
                \t\t\t_: pass
                """);
        var prop1 = assertInstanceOf(VariableDeclaration.class, accessorMatchInvalidLevel.ast().statements().getFirst());
        assertEquals(List.of("export"), prop1.recoveryAnnotations().stream().map(AnnotationStatement::name).toList());
        var matchInvalidDiag = findErrorByCode(accessorMatchInvalidLevel, DiagnosticCodes.ANNOTATION_INVALID_LEVEL);
        assertNotNull(matchInvalidDiag.recovery());
        assertSame(prop1, matchInvalidDiag.recovery().owner());
        assertEquals("variable.property", matchInvalidDiag.recovery().facet());

        var accessorMatchDangling = recoverableParse("""
                var prop:
                \tset(v):
                \t\tmatch x:
                \t\t\t@warning_ignore("a")
                """);
        var prop2 = assertInstanceOf(VariableDeclaration.class, accessorMatchDangling.ast().statements().getFirst());
        assertEquals(List.of("warning_ignore"), prop2.recoveryAnnotations().stream().map(AnnotationStatement::name).toList());
        var matchDanglingDiag = findErrorByCode(accessorMatchDangling, DiagnosticCodes.ANNOTATION_DANGLING);
        assertNotNull(matchDanglingDiag.recovery());
        assertSame(prop2, matchDanglingDiag.recovery().owner());
        assertEquals("variable.property", matchDanglingDiag.recovery().facet());

        var cleanAccessor = parse("""
                var prop:
                \tget:
                \t\t@warning_ignore("unused_variable")
                \t\treturn 1
                """);
        assertTrue(errorsOf(cleanAccessor).isEmpty(), () -> errorsOf(cleanAccessor).toString());
        var cleanProp = assertInstanceOf(VariableDeclaration.class, cleanAccessor.ast().statements().getFirst());
        assertTrue(cleanProp.recoveryAnnotations().isEmpty());
    }

    @Test
    void inlineLambdaTrailingGarbageAttributesToInnerStatementWhileControlsStayClean() {
        var source = "func f(): g(func(): return 1 2, 3)\n";
        var result = recoverableParse(source);
        var fn = onlyFunction(result);
        var call = assertInstanceOf(CallExpression.class,
                assertInstanceOf(ExpressionStatement.class, fn.body().statements().getFirst()).expression());
        assertEquals(2, call.arguments().size());
        var lambda = assertInstanceOf(LambdaExpression.class, call.arguments().getFirst());
        var ret = assertInstanceOf(ReturnStatement.class, lambda.body().statements().getFirst());
        assertEquals("1", assertInstanceOf(LiteralExpression.class, ret.value()).sourceText());
        assertEquals("3", assertInstanceOf(LiteralExpression.class, call.arguments().get(1)).sourceText());

        var trailing = findErrorByCode(result, DiagnosticCodes.STATEMENT_TRAILING);
        assertEquals("2", source.substring(trailing.range().startByte(), trailing.range().endByte()));
        assertNotNull(trailing.recovery());
        assertSame(ret, trailing.recovery().owner());
        assertEquals("statement.trailing", trailing.recovery().facet());

        var cleanValue = parse("func f(): g(func(): return 1, 3)\n");
        assertTrue(errorsOf(cleanValue).isEmpty(), () -> errorsOf(cleanValue).toString());
        var cleanBare = parse("func f(): g(func(): return, 1)\n");
        assertTrue(errorsOf(cleanBare).isEmpty(), () -> errorsOf(cleanBare).toString());
    }

    @Test
    void lexicalSlotValuesRemainWithinParentEnvelopesAcrossMatchAndControlConstructs() {
        var matchInline = recoverableParse("func f(): g(func(): match §, 1)\n");
        var call = assertInstanceOf(CallExpression.class,
                assertInstanceOf(ExpressionStatement.class,
                        onlyFunction(matchInline).body().statements().getFirst()).expression());
        assertEquals(2, call.arguments().size());
        assertEquals("1", assertInstanceOf(LiteralExpression.class, call.arguments().get(1)).sourceText());
        var lambda = assertInstanceOf(LambdaExpression.class, call.arguments().getFirst());
        var matchStmt = assertInstanceOf(MatchStatement.class, lambda.body().statements().getFirst());
        var matchVal = assertInstanceOf(ErrorExpression.class, matchStmt.value());
        assertEquals(CstIssueKind.ERROR, matchVal.kind());
        assertTrue(matchStmt.range().startByte() <= matchVal.range().startByte()
                        && matchVal.range().endByte() <= matchStmt.range().endByte(),
                () -> "parent=" + matchStmt.range() + " child=" + matchVal.range());
        var lexDiag = findErrorByCode(matchInline, DiagnosticCodes.LEXICAL_ERROR);
        assertNotNull(lexDiag.recovery());
        assertSame(matchStmt, lexDiag.recovery().owner());
        assertEquals("match.value", lexDiag.recovery().facet());

        for (var source : List.of(
                "func f(): g(func(): if §, 1)\n",
                "func f(): g(func(): while §, 1)\n",
                "func f():\n\tmatch §:\n\t\tpass\n",
                "func f():\n\treturn =3 §"
        )) {
            var res = recoverableParse(source);
            assertEnvelopeRecursive(res.ast(), source);
        }
    }

    private static void assertEnvelopeRecursive(Node parent, String source) {
        for (var child : parent.getChildren()) {
            assertTrue(parent.range().startByte() <= child.range().startByte()
                            && child.range().endByte() <= parent.range().endByte(),
                    () -> "Envelope violation in `" + source + "`: parent=" + parent.range()
                            + " child=" + child.range());
            assertEnvelopeRecursive(child, source);
        }
    }

    @Test
    void lexicallyDamagedContainerItemsArePreservedWithoutBreakingEmptyOrTrailingCommaControls() {
        var callTwo = recoverableParse("var x = f(1, §)\n");
        var callTwoExpr = assertInstanceOf(CallExpression.class,
                assertInstanceOf(VariableDeclaration.class, callTwo.ast().statements().getFirst()).value());
        assertEquals(2, callTwoExpr.arguments().size());
        assertEquals(CstIssueKind.ERROR, assertInstanceOf(ErrorExpression.class, callTwoExpr.arguments().get(1)).kind());
        assertNotNull(findErrorByCode(callTwo, DiagnosticCodes.LEXICAL_ERROR).recovery());

        var callOne = recoverableParse("var x = f(§)\n");
        var callOneExpr = assertInstanceOf(CallExpression.class,
                assertInstanceOf(VariableDeclaration.class, callOne.ast().statements().getFirst()).value());
        assertEquals(1, callOneExpr.arguments().size());
        assertEquals(CstIssueKind.ERROR, assertInstanceOf(ErrorExpression.class, callOneExpr.arguments().getFirst()).kind());

        var arrTwo = recoverableParse("var x = [1, §]\n");
        var arrTwoExpr = assertInstanceOf(ArrayExpression.class,
                assertInstanceOf(VariableDeclaration.class, arrTwo.ast().statements().getFirst()).value());
        assertEquals(2, arrTwoExpr.elements().size());
        assertEquals(CstIssueKind.ERROR, assertInstanceOf(ErrorExpression.class, arrTwoExpr.elements().get(1)).kind());

        var arrOne = recoverableParse("var x = [§]\n");
        var arrOneExpr = assertInstanceOf(ArrayExpression.class,
                assertInstanceOf(VariableDeclaration.class, arrOne.ast().statements().getFirst()).value());
        assertEquals(1, arrOneExpr.elements().size());
        assertEquals(CstIssueKind.ERROR, assertInstanceOf(ErrorExpression.class, arrOneExpr.elements().getFirst()).kind());

        var arrMultiline = recoverableParse("var x = [\n\t1,\n\t§\n]\n");
        var arrMultiExpr = assertInstanceOf(ArrayExpression.class,
                assertInstanceOf(VariableDeclaration.class, arrMultiline.ast().statements().getFirst()).value());
        assertEquals(2, arrMultiExpr.elements().size());
        assertEquals(CstIssueKind.ERROR, assertInstanceOf(ErrorExpression.class, arrMultiExpr.elements().get(1)).kind());

        var dictTwo = recoverableParse("var x = {1: 2, §}\n");
        var dictTwoExpr = assertInstanceOf(DictionaryExpression.class,
                assertInstanceOf(VariableDeclaration.class, dictTwo.ast().statements().getFirst()).value());
        assertEquals(2, dictTwoExpr.entries().size());
        assertEquals(CstIssueKind.ERROR,
                assertInstanceOf(ErrorExpression.class, dictTwoExpr.entries().get(1).key()).kind());

        var assertMsg = recoverableParse("func f():\n\tassert(1, §)\n");
        var assertStmt = assertInstanceOf(AssertStatement.class,
                onlyFunction(assertMsg).body().statements().getFirst());
        assertEquals(CstIssueKind.ERROR, assertInstanceOf(ErrorExpression.class, assertStmt.message()).kind());

        var annTwo = recoverableParse("@warning_ignore(\"a\", §)\nvar x = 1\n");
        var annStmt = assertInstanceOf(AnnotationStatement.class, annTwo.ast().statements().getFirst());
        assertEquals(2, annStmt.arguments().size());
        assertEquals(CstIssueKind.ERROR, assertInstanceOf(ErrorExpression.class, annStmt.arguments().get(1)).kind());

        var matchPatterns = recoverableParse("func f():\n\tmatch x:\n\t\t[1, §]: pass\n\t\t{1: 2, §}: pass\n");
        var matchStmt = assertInstanceOf(MatchStatement.class,
                onlyFunction(matchPatterns).body().statements().getFirst());
        var arrPat = assertInstanceOf(ArrayExpression.class,
                matchStmt.sections().get(0).patterns().getFirst());
        assertEquals(2, arrPat.elements().size());
        assertEquals(CstIssueKind.ERROR, assertInstanceOf(ErrorExpression.class, arrPat.elements().get(1)).kind());
        var dictPat = assertInstanceOf(DictionaryExpression.class,
                matchStmt.sections().get(1).patterns().getFirst());
        assertEquals(2, dictPat.entries().size());
        assertEquals(CstIssueKind.ERROR,
                assertInstanceOf(ErrorExpression.class, dictPat.entries().get(1).key()).kind());

        for (var cleanSource : List.of(
                "var a = f()\n",
                "var b = []\n",
                "var c = {}\n",
                "@ann()\nvar d = 1\n",
                "var e = f(1,)\n",
                "var f = [1,]\n",
                "var g = {\"a\": 1,}\n",
                "var h = [\n\t1,\n]\n",
                "var i = f(\n\t1,\n)\n"
        )) {
            var cleanRes = parse(cleanSource);
            assertTrue(errorsOf(cleanRes).isEmpty(), () -> cleanSource + " -> " + errorsOf(cleanRes));
        }
    }

    @Test
    void malformedReturnFragmentClaimsSameLineTrailingLexicalErrorWithoutCrossingNewline() {
        var inCall = recoverableParse("func f(): g(func(): return =3 §, 1)\n");
        var call = assertInstanceOf(CallExpression.class,
                assertInstanceOf(ExpressionStatement.class,
                        onlyFunction(inCall).body().statements().getFirst()).expression());
        assertEquals(2, call.arguments().size());
        assertEquals("1", assertInstanceOf(LiteralExpression.class, call.arguments().get(1)).sourceText());
        var lambda = assertInstanceOf(LambdaExpression.class, call.arguments().getFirst());
        var ret = assertInstanceOf(ReturnStatement.class, lambda.body().statements().getFirst());
        var retErr = assertInstanceOf(ErrorExpression.class, ret.value());
        assertEquals("=3 §", retErr.sourceText());
        var slotDiag = findErrorByCode(inCall, DiagnosticCodes.SLOT_RETURN_VALUE);
        assertSame(ret, requireNotNull(slotDiag.recovery()).owner());
        assertEquals("return.value", requireNotNull(slotDiag.recovery()).facet());
        var lexDiag = findErrorByCode(inCall, DiagnosticCodes.LEXICAL_ERROR);
        assertSame(ret, requireNotNull(lexDiag.recovery()).owner());
        assertEquals("return.value", requireNotNull(lexDiag.recovery()).facet());

        var atEof = recoverableParse("func f(): return =3 §");
        var eofRet = assertInstanceOf(ReturnStatement.class,
                onlyFunction(atEof).body().statements().getFirst());
        assertEquals("=3 §", assertInstanceOf(ErrorExpression.class, eofRet.value()).sourceText());
        var eofLex = findErrorByCode(atEof, DiagnosticCodes.LEXICAL_ERROR);
        assertSame(eofRet, requireNotNull(eofLex.recovery()).owner());
        assertEquals("return.value", requireNotNull(eofLex.recovery()).facet());

        var nextLine = recoverableParse("func f():\n\treturn =3\n\t§\n");
        var nextRet = assertInstanceOf(ReturnStatement.class,
                onlyFunction(nextLine).body().statements().getFirst());
        assertEquals("=3", assertInstanceOf(ErrorExpression.class, nextRet.value()).sourceText());
        var nextLex = findErrorByCode(nextLine, DiagnosticCodes.LEXICAL_ERROR);
        assertNull(nextLex.recovery());
    }

    @Test
    void accessorMatchRecoveryAnnotationsPreserveSourceOrderAndEnvelope() {
        var warningThenExport = recoverableParse("""
                var prop:
                \tget:
                \t\tmatch x:
                \t\t\t@warning_ignore("unused_variable")
                \t\t\t@export
                """);
        var prop1 = assertInstanceOf(VariableDeclaration.class, warningThenExport.ast().statements().getFirst());
        assertEquals(List.of("warning_ignore", "export"),
                prop1.recoveryAnnotations().stream().map(AnnotationStatement::name).toList());
        assertEnvelopeRecursive(prop1, "warning_ignore then export");
        assertSame(prop1, requireNotNull(findErrorByCode(warningThenExport, DiagnosticCodes.ANNOTATION_DANGLING)
                .recovery()).owner());
        assertEquals("variable.property", requireNotNull(findErrorByCode(warningThenExport,
                DiagnosticCodes.ANNOTATION_DANGLING).recovery()).facet());
        assertSame(prop1, requireNotNull(findErrorByCode(warningThenExport, DiagnosticCodes.ANNOTATION_INVALID_LEVEL)
                .recovery()).owner());
        assertEquals("variable.property", requireNotNull(findErrorByCode(warningThenExport,
                DiagnosticCodes.ANNOTATION_INVALID_LEVEL).recovery()).facet());

        var exportThenWarning = recoverableParse("""
                var prop:
                \tget:
                \t\tmatch x:
                \t\t\t@export
                \t\t\t@warning_ignore("unused_variable")
                """);
        var prop2 = assertInstanceOf(VariableDeclaration.class, exportThenWarning.ast().statements().getFirst());
        assertEquals(List.of("export", "warning_ignore"),
                prop2.recoveryAnnotations().stream().map(AnnotationStatement::name).toList());
        assertEnvelopeRecursive(prop2, "export then warning_ignore");
    }

    @Test
    void commaShieldResolutionStaysLinearAcrossDeepUnmatchedOpenersAndManyCommas() {
        var openerCount = 1_000;
        var commaItems = IntStream.rangeClosed(1, 1_000)
                .mapToObj(Integer::toString)
                .collect(Collectors.joining(","));
        var source = "func f(): g(func(): return ="
                + "[".repeat(openerCount)
                + "(" + commaItems + "), 4)\n";
        var parser = new GdScriptParser();
        var result = parser.parse(source);
        assertTrue(parser.localDelimiterOracleResolutions() <= openerCount + 5,
                () -> "Expected linear oracle resolutions <= " + (openerCount + 5)
                        + ", got " + parser.localDelimiterOracleResolutions());
        var call = assertInstanceOf(CallExpression.class,
                assertInstanceOf(ExpressionStatement.class,
                        onlyFunction(result).body().statements().getFirst()).expression());
        assertEquals(2, call.arguments().size());
        assertInstanceOf(LambdaExpression.class, call.arguments().getFirst());
        assertEquals("4", assertInstanceOf(LiteralExpression.class, call.arguments().get(1)).sourceText());
    }

    @Test
    void explicitLineContinuationReturnAndVariableSlotsClaimTrailingLexicalErrorsWhileHardNewlinesStayStandalone() {
        var continuedCallSource = "func f(): g(func(): return =3 \\\n §, 1)\n";
        var continuedCallResult = recoverableParse(continuedCallSource);
        assertEnvelopeRecursive(continuedCallResult.ast(), continuedCallSource);
        var outerCall = assertInstanceOf(CallExpression.class,
                assertInstanceOf(ExpressionStatement.class,
                        onlyFunction(continuedCallResult).body().statements().getFirst()).expression());
        assertEquals(2, outerCall.arguments().size());
        var lambda = assertInstanceOf(LambdaExpression.class, outerCall.arguments().getFirst());
        var innerReturn = assertInstanceOf(ReturnStatement.class, lambda.body().statements().getFirst());
        var returnErr = assertInstanceOf(ErrorExpression.class, innerReturn.value());
        assertEquals("=3 \\\n §", returnErr.sourceText());
        assertEquals("1", assertInstanceOf(LiteralExpression.class, outerCall.arguments().get(1)).sourceText());
        var continuedCallLexical = findErrorByCode(continuedCallResult, DiagnosticCodes.LEXICAL_ERROR);
        assertNotNull(continuedCallLexical.recovery());
        assertSame(innerReturn, continuedCallLexical.recovery().owner());
        assertEquals("return.value", continuedCallLexical.recovery().facet());

        var continuedBareReturnSource = "func f():\n\treturn \\\n\t§\n";
        var continuedBareReturnResult = recoverableParse(continuedBareReturnSource);
        assertEnvelopeRecursive(continuedBareReturnResult.ast(), continuedBareReturnSource);
        var bareReturn = assertInstanceOf(ReturnStatement.class,
                onlyFunction(continuedBareReturnResult).body().statements().getFirst());
        var bareReturnErr = assertInstanceOf(ErrorExpression.class, bareReturn.value());
        assertEquals("§", bareReturnErr.sourceText());
        var bareReturnLexical = findErrorByCode(continuedBareReturnResult, DiagnosticCodes.LEXICAL_ERROR);
        assertNotNull(bareReturnLexical.recovery());
        assertSame(bareReturn, bareReturnLexical.recovery().owner());
        assertEquals("return.value", bareReturnLexical.recovery().facet());

        var continuedVarSource = "var x = \\\n\t§\n";
        var continuedVarResult = recoverableParse(continuedVarSource);
        assertEnvelopeRecursive(continuedVarResult.ast(), continuedVarSource);
        var continuedVar = assertInstanceOf(VariableDeclaration.class, continuedVarResult.ast().statements().getFirst());
        var varErr = assertInstanceOf(ErrorExpression.class, continuedVar.value());
        assertEquals("§", varErr.sourceText());
        var varLexical = findErrorByCode(continuedVarResult, DiagnosticCodes.LEXICAL_ERROR);
        assertNotNull(varLexical.recovery());
        assertSame(continuedVar, varLexical.recovery().owner());
        assertEquals("variable.initializer", varLexical.recovery().facet());

        var hardNewlineReturnSource = "func f():\n\treturn =3\n\t§\n";
        var hardNewlineReturnResult = recoverableParse(hardNewlineReturnSource);
        assertEnvelopeRecursive(hardNewlineReturnResult.ast(), hardNewlineReturnSource);
        var hardReturn = assertInstanceOf(ReturnStatement.class,
                onlyFunction(hardNewlineReturnResult).body().statements().getFirst());
        assertEquals("=3", assertInstanceOf(ErrorExpression.class, hardReturn.value()).sourceText());
        assertNull(findErrorByCode(hardNewlineReturnResult, DiagnosticCodes.LEXICAL_ERROR).recovery());

        var hardNewlineEmptyReturnSource = "func f():\n\treturn\n\t§\n";
        var hardNewlineEmptyReturnResult = recoverableParse(hardNewlineEmptyReturnSource);
        assertEnvelopeRecursive(hardNewlineEmptyReturnResult.ast(), hardNewlineEmptyReturnSource);
        var emptyReturn = assertInstanceOf(ReturnStatement.class,
                onlyFunction(hardNewlineEmptyReturnResult).body().statements().getFirst());
        assertNull(emptyReturn.value());
        assertNull(findErrorByCode(hardNewlineEmptyReturnResult, DiagnosticCodes.LEXICAL_ERROR).recovery());

        var hardNewlineVarSource = "var x = 1\n§\n";
        var hardNewlineVarResult = recoverableParse(hardNewlineVarSource);
        assertEnvelopeRecursive(hardNewlineVarResult.ast(), hardNewlineVarSource);
        var hardVar = assertInstanceOf(VariableDeclaration.class, hardNewlineVarResult.ast().statements().getFirst());
        assertEquals("1", assertInstanceOf(LiteralExpression.class, hardVar.value()).sourceText());
        assertNull(findErrorByCode(hardNewlineVarResult, DiagnosticCodes.LEXICAL_ERROR).recovery());
    }

    @Test
    void manyDuplicatePropertyGettersWithRecoveryAnnotationsPreserveOrderEnvelopeAndOwnership() {
        var getterCount = 200;
        var source = "var prop:\n" + "\tget:\n\t\tmatch x:\n\t\t\t@export\n".repeat(getterCount);
        var result = recoverableParse(source);
        assertEnvelopeRecursive(result.ast(), source);

        var prop = assertInstanceOf(VariableDeclaration.class, result.ast().statements().getFirst());
        assertEquals(getterCount, prop.recoveryAnnotations().size());
        var previousStart = -1;
        for (var annotation : prop.recoveryAnnotations()) {
            assertEquals("export", annotation.name());
            assertTrue(annotation.range().startByte() > previousStart);
            previousStart = annotation.range().startByte();
        }
        assertTrue(prop.range().endByte() >= prop.recoveryAnnotations().getLast().range().endByte());

        var invalidLevelErrors = errorsOf(result).stream()
                .filter(diagnostic -> DiagnosticCodes.ANNOTATION_INVALID_LEVEL.equals(diagnostic.code()))
                .toList();
        assertEquals(getterCount, invalidLevelErrors.size());
        for (var error : invalidLevelErrors) {
            assertNotNull(error.recovery());
            assertSame(prop, error.recovery().owner());
            assertEquals("variable.property", error.recovery().facet());
        }
    }

    @Test
    void queuedErrorsAndMalformedBackslashPreserveContinuationAndDoNotSwallowNextLine() {
        var badBackslashSource = "func f():\n\treturn \\\n\t\\ \n\tvar x = 1\n";
        var badBackslashResult = recoverableParse(badBackslashSource);
        assertEnvelopeRecursive(badBackslashResult.ast(), badBackslashSource);
        var badBackslashStmts = onlyFunction(badBackslashResult).body().statements();
        assertEquals(2, badBackslashStmts.size());
        var badBackslashRet = assertInstanceOf(ReturnStatement.class, badBackslashStmts.getFirst());
        assertInstanceOf(ErrorExpression.class, badBackslashRet.value());
        var survivedVar = assertInstanceOf(VariableDeclaration.class, badBackslashStmts.get(1));
        assertEquals("x", survivedVar.name());
        var badBackslashLex = findErrorByCode(badBackslashResult, DiagnosticCodes.LEXICAL_ERROR);
        assertNotNull(badBackslashLex.recovery());
        assertSame(badBackslashRet, badBackslashLex.recovery().owner());
        assertEquals("return.value", badBackslashLex.recovery().facet());

        var crThenLexSource = "func f():\n\treturn \\\n\t\r§\n";
        var crThenLexResult = recoverableParse(crThenLexSource);
        assertEnvelopeRecursive(crThenLexResult.ast(), crThenLexSource);
        var crThenLexRet = assertInstanceOf(ReturnStatement.class,
                onlyFunction(crThenLexResult).body().statements().getFirst());
        assertInstanceOf(ErrorExpression.class, crThenLexRet.value());
        var crLexErrors = errorsOf(crThenLexResult).stream()
                .filter(d -> DiagnosticCodes.LEXICAL_ERROR.equals(d.code()))
                .toList();
        assertEquals(2, crLexErrors.size());
        for (var err : crLexErrors) {
            assertNotNull(err.recovery());
            assertSame(crThenLexRet, err.recovery().owner());
            assertEquals("return.value", err.recovery().facet());
        }

        var crThenNewlineSource = "func f():\n\treturn \\\n\t\r \n\tvar x = 1\n";
        var crThenNewlineResult = recoverableParse(crThenNewlineSource);
        assertEnvelopeRecursive(crThenNewlineResult.ast(), crThenNewlineSource);
        var crNewlineStmts = onlyFunction(crThenNewlineResult).body().statements();
        assertEquals(2, crNewlineStmts.size());
        var crNewlineRet = assertInstanceOf(ReturnStatement.class, crNewlineStmts.getFirst());
        assertInstanceOf(ErrorExpression.class, crNewlineRet.value());
        var crSurvivedVar = assertInstanceOf(VariableDeclaration.class, crNewlineStmts.get(1));
        assertEquals("x", crSurvivedVar.name());
    }

    @Test
    void explicitContinuationAcrossSkippedLexicalErrorsDrainsStatementTailWithoutCrossingHardNewline() {
        var withPrefixSource = "func f():\n\tpass 0\\\n\t§ var local = 1\n\tpass\n";
        var withPrefixResult = recoverableParse(withPrefixSource);
        assertEnvelopeRecursive(withPrefixResult.ast(), withPrefixSource);
        var withPrefixStmts = onlyFunction(withPrefixResult).body().statements();
        assertEquals(2, withPrefixStmts.size());
        var firstPass1 = assertInstanceOf(PassStatement.class, withPrefixStmts.getFirst());
        assertInstanceOf(PassStatement.class, withPrefixStmts.get(1));
        var trailing1 = findErrorByCode(withPrefixResult, DiagnosticCodes.STATEMENT_TRAILING);
        assertEquals("0\\\n\t§ var local = 1", utf8Slice(withPrefixSource, trailing1.range()));
        assertSame(firstPass1, requireNotNull(trailing1.recovery()).owner());
        assertEquals("statement.trailing", requireNotNull(trailing1.recovery()).facet());
        var lex1 = findErrorByCode(withPrefixResult, DiagnosticCodes.LEXICAL_ERROR);
        assertSame(firstPass1, requireNotNull(lex1.recovery()).owner());
        assertEquals("statement.trailing", requireNotNull(lex1.recovery()).facet());

        var directContinuationSource = "func f():\n\tpass \\\n\t§ var local = 1\n\tpass\n";
        var directContinuationResult = recoverableParse(directContinuationSource);
        assertEnvelopeRecursive(directContinuationResult.ast(), directContinuationSource);
        var directStmts = onlyFunction(directContinuationResult).body().statements();
        assertEquals(2, directStmts.size());
        var firstPass2 = assertInstanceOf(PassStatement.class, directStmts.getFirst());
        assertInstanceOf(PassStatement.class, directStmts.get(1));
        var trailing2 = findErrorByCode(directContinuationResult, DiagnosticCodes.STATEMENT_TRAILING);
        assertEquals("var local = 1", utf8Slice(directContinuationSource, trailing2.range()));
        assertSame(firstPass2, requireNotNull(trailing2.recovery()).owner());
        assertEquals("statement.trailing", requireNotNull(trailing2.recovery()).facet());
        var lex2 = findErrorByCode(directContinuationResult, DiagnosticCodes.LEXICAL_ERROR);
        assertSame(firstPass2, requireNotNull(lex2.recovery()).owner());
        assertEquals("statement.trailing", requireNotNull(lex2.recovery()).facet());

        var hardNewlineAfterLexSource = "func f():\n\tpass 0\\\n\t§\n\tvar local = 1\n\tpass\n";
        var hardNewlineAfterLexResult = recoverableParse(hardNewlineAfterLexSource);
        assertEnvelopeRecursive(hardNewlineAfterLexResult.ast(), hardNewlineAfterLexSource);
        var hardStmts = onlyFunction(hardNewlineAfterLexResult).body().statements();
        assertEquals(3, hardStmts.size());
        var firstPass3 = assertInstanceOf(PassStatement.class, hardStmts.getFirst());
        assertEquals("local", assertInstanceOf(VariableDeclaration.class, hardStmts.get(1)).name());
        assertInstanceOf(PassStatement.class, hardStmts.get(2));
        var trailing3 = findErrorByCode(hardNewlineAfterLexResult, DiagnosticCodes.STATEMENT_TRAILING);
        assertEquals("0", utf8Slice(hardNewlineAfterLexSource, trailing3.range()));
        assertSame(firstPass3, requireNotNull(trailing3.recovery()).owner());
        assertEquals("statement.trailing", requireNotNull(trailing3.recovery()).facet());
    }

    @Test
    void lexicalOnlyStatementTailsClaimStatementTrailingWithoutExtendingAstRangeOrTouchingHardNewline() {
        var sameLineSource = "func f():\n\treturn 1 §\n";
        var sameLineResult = recoverableParse(sameLineSource);
        assertEnvelopeRecursive(sameLineResult.ast(), sameLineSource);
        var ret1 = assertInstanceOf(ReturnStatement.class,
                onlyFunction(sameLineResult).body().statements().getFirst());
        assertEquals("return 1", utf8Slice(sameLineSource, ret1.range()));
        assertTrue(errorsOf(sameLineResult).stream()
                .noneMatch(d -> DiagnosticCodes.STATEMENT_TRAILING.equals(d.code())));
        var lex1 = findErrorByCode(sameLineResult, DiagnosticCodes.LEXICAL_ERROR);
        assertSame(ret1, requireNotNull(lex1.recovery()).owner());
        assertEquals("statement.trailing", requireNotNull(lex1.recovery()).facet());

        var inlineLambdaSource = "func f(): g(func(): return 1 §, 2)\n";
        var inlineLambdaResult = recoverableParse(inlineLambdaSource);
        assertEnvelopeRecursive(inlineLambdaResult.ast(), inlineLambdaSource);
        var outerCall = assertInstanceOf(CallExpression.class,
                assertInstanceOf(ExpressionStatement.class,
                        onlyFunction(inlineLambdaResult).body().statements().getFirst()).expression());
        assertEquals(2, outerCall.arguments().size());
        var lambda = assertInstanceOf(LambdaExpression.class, outerCall.arguments().getFirst());
        assertEquals("2", assertInstanceOf(LiteralExpression.class, outerCall.arguments().get(1)).sourceText());
        var innerRet = assertInstanceOf(ReturnStatement.class, lambda.body().statements().getFirst());
        assertEquals("return 1", utf8Slice(inlineLambdaSource, innerRet.range()));
        assertTrue(errorsOf(inlineLambdaResult).stream()
                .noneMatch(d -> DiagnosticCodes.STATEMENT_TRAILING.equals(d.code())));
        var lex2 = findErrorByCode(inlineLambdaResult, DiagnosticCodes.LEXICAL_ERROR);
        assertSame(innerRet, requireNotNull(lex2.recovery()).owner());
        assertEquals("statement.trailing", requireNotNull(lex2.recovery()).facet());

        var continuedTailSource = "func f():\n\treturn 1 \\\n\t§\n";
        var continuedTailResult = recoverableParse(continuedTailSource);
        assertEnvelopeRecursive(continuedTailResult.ast(), continuedTailSource);
        var ret3 = assertInstanceOf(ReturnStatement.class,
                onlyFunction(continuedTailResult).body().statements().getFirst());
        assertEquals("return 1", utf8Slice(continuedTailSource, ret3.range()));
        assertTrue(errorsOf(continuedTailResult).stream()
                .noneMatch(d -> DiagnosticCodes.STATEMENT_TRAILING.equals(d.code())));
        var lex3 = findErrorByCode(continuedTailResult, DiagnosticCodes.LEXICAL_ERROR);
        assertSame(ret3, requireNotNull(lex3.recovery()).owner());
        assertEquals("statement.trailing", requireNotNull(lex3.recovery()).facet());

        var eofTailSource = "func f(): return 1 §";
        var eofTailResult = recoverableParse(eofTailSource);
        assertEnvelopeRecursive(eofTailResult.ast(), eofTailSource);
        var ret4 = assertInstanceOf(ReturnStatement.class,
                onlyFunction(eofTailResult).body().statements().getFirst());
        assertEquals("return 1", utf8Slice(eofTailSource, ret4.range()));
        var lex4 = findErrorByCode(eofTailResult, DiagnosticCodes.LEXICAL_ERROR);
        assertSame(ret4, requireNotNull(lex4.recovery()).owner());
        assertEquals("statement.trailing", requireNotNull(lex4.recovery()).facet());

        var hardNewlineSource = "func f():\n\treturn 1\n\t§\n";
        var hardNewlineResult = recoverableParse(hardNewlineSource);
        assertEnvelopeRecursive(hardNewlineResult.ast(), hardNewlineSource);
        var ret5 = assertInstanceOf(ReturnStatement.class,
                onlyFunction(hardNewlineResult).body().statements().getFirst());
        assertEquals("return 1", utf8Slice(hardNewlineSource, ret5.range()));
        assertNull(findErrorByCode(hardNewlineResult, DiagnosticCodes.LEXICAL_ERROR).recovery());
    }

    @Test
    void laterSkippedErrorContinuationPropagatesToTokenAndResetsAcrossMultilineHardNewlines() {
        var continuedBatchSource = "func f():\n\tpass 0 §\\\n\t§ var local = 1\n\tpass\n";
        var continuedBatchResult = recoverableParse(continuedBatchSource);
        assertEnvelopeRecursive(continuedBatchResult.ast(), continuedBatchSource);
        var continuedStmts = onlyFunction(continuedBatchResult).body().statements();
        assertEquals(2, continuedStmts.size());
        var firstPass1 = assertInstanceOf(PassStatement.class, continuedStmts.getFirst());
        assertInstanceOf(PassStatement.class, continuedStmts.get(1));
        var trailing1 = findErrorByCode(continuedBatchResult, DiagnosticCodes.STATEMENT_TRAILING);
        assertEquals("0 §\\\n\t§ var local = 1", utf8Slice(continuedBatchSource, trailing1.range()));
        assertSame(firstPass1, requireNotNull(trailing1.recovery()).owner());
        assertEquals("statement.trailing", requireNotNull(trailing1.recovery()).facet());
        var lexErrors1 = errorsOf(continuedBatchResult).stream()
                .filter(d -> DiagnosticCodes.LEXICAL_ERROR.equals(d.code()))
                .toList();
        assertEquals(2, lexErrors1.size());
        for (var lex : lexErrors1) {
            assertSame(firstPass1, requireNotNull(lex.recovery()).owner());
            assertEquals("statement.trailing", requireNotNull(lex.recovery()).facet());
        }

        var hardNewlineAfterSecondSource = "func f():\n\tpass 0 §\\\n\t§\n\tvar local = 1\n\tpass\n";
        var hardNewlineAfterSecondResult = recoverableParse(hardNewlineAfterSecondSource);
        assertEnvelopeRecursive(hardNewlineAfterSecondResult.ast(), hardNewlineAfterSecondSource);
        var hardStmts = onlyFunction(hardNewlineAfterSecondResult).body().statements();
        assertEquals(3, hardStmts.size());
        var firstPass2 = assertInstanceOf(PassStatement.class, hardStmts.getFirst());
        assertEquals("local", assertInstanceOf(VariableDeclaration.class, hardStmts.get(1)).name());
        assertInstanceOf(PassStatement.class, hardStmts.get(2));
        var trailing2 = findErrorByCode(hardNewlineAfterSecondResult, DiagnosticCodes.STATEMENT_TRAILING);
        assertEquals("0", utf8Slice(hardNewlineAfterSecondSource, trailing2.range()));
        assertSame(firstPass2, requireNotNull(trailing2.recovery()).owner());
        assertEquals("statement.trailing", requireNotNull(trailing2.recovery()).facet());

        var multilineContinuedDictSource = "func f():\n\tvar d = {\n\t\t1 §\\\n\t\t§ 2\n\t}\n";
        var multilineContinuedDictResult = recoverableParse(multilineContinuedDictSource);
        assertEnvelopeRecursive(multilineContinuedDictResult.ast(), multilineContinuedDictSource);
        var continuedDictVar = assertInstanceOf(VariableDeclaration.class,
                onlyFunction(multilineContinuedDictResult).body().statements().getFirst());
        var continuedDict = assertInstanceOf(DictionaryExpression.class, continuedDictVar.value());
        assertEquals(1, continuedDict.entries().size());
        assertEquals("1", assertInstanceOf(LiteralExpression.class,
                continuedDict.entries().getFirst().key()).sourceText());
        assertEquals("2", assertInstanceOf(LiteralExpression.class,
                continuedDict.entries().getFirst().value()).sourceText());

        var multilineHardBreakDictSource = "func f():\n\tvar d = {\n\t\t1 §\\\n\t\t§\n\t\t§ 2\n\t}\n";
        var multilineHardBreakDictResult = recoverableParse(multilineHardBreakDictSource);
        assertEnvelopeRecursive(multilineHardBreakDictResult.ast(), multilineHardBreakDictSource);
        var brokenDictVar = assertInstanceOf(VariableDeclaration.class,
                onlyFunction(multilineHardBreakDictResult).body().statements().getFirst());
        var brokenDict = assertInstanceOf(DictionaryExpression.class, brokenDictVar.value());
        assertEquals(2, brokenDict.entries().size());
        assertEquals("1", assertInstanceOf(LiteralExpression.class,
                brokenDict.entries().getFirst().key()).sourceText());
        assertInstanceOf(ErrorExpression.class, brokenDict.entries().getFirst().value());
        assertEquals("2", assertInstanceOf(LiteralExpression.class,
                brokenDict.entries().get(1).key()).sourceText());
    }

    @Test
    void hardNewlineBeforeLaterBackslashContinuationResetsLogicalLineInMultilineAndSuiteTails() {
        for (var twoEntrySource : List.of(
                "func f():\n\tvar d = {\n\t\t1\n\t\t§\\\n\t\t§ 2\n\t}\n",
                "func f():\n\tvar d = {\n\t\t1\n\t\t\\\n\t\t2\n\t}\n",
                "func f():\n\tvar d = {\n\t\t1\n\t\t# comment\n\t\t§\\\n\t\t§ 2\n\t}\n"
        )) {
            var result = recoverableParse(twoEntrySource);
            assertEnvelopeRecursive(result.ast(), twoEntrySource);
            var varDecl = assertInstanceOf(VariableDeclaration.class,
                    onlyFunction(result).body().statements().getFirst());
            var dict = assertInstanceOf(DictionaryExpression.class, varDecl.value());
            assertEquals(2, dict.entries().size(), () -> "Expected 2 entries in:\n" + twoEntrySource);
            assertEquals("1", assertInstanceOf(LiteralExpression.class,
                    dict.entries().getFirst().key()).sourceText());
            assertInstanceOf(ErrorExpression.class, dict.entries().getFirst().value());
            assertEquals("2", assertInstanceOf(LiteralExpression.class,
                    dict.entries().get(1).key()).sourceText());
        }

        for (var singleEntrySource : List.of(
                "func f():\n\tvar d = {\n\t\t1 \\\n\t\t§\\\n\t\t§ 2\n\t}\n",
                "func f():\n\tvar d = {\n\t\t1 \\\n\t\t# comment\n\t\t§\\\n\t\t§ 2\n\t}\n"
        )) {
            var result = recoverableParse(singleEntrySource);
            assertEnvelopeRecursive(result.ast(), singleEntrySource);
            var varDecl = assertInstanceOf(VariableDeclaration.class,
                    onlyFunction(result).body().statements().getFirst());
            var dict = assertInstanceOf(DictionaryExpression.class, varDecl.value());
            assertEquals(1, dict.entries().size(), () -> "Expected 1 entry in:\n" + singleEntrySource);
            assertEquals("1", assertInstanceOf(LiteralExpression.class,
                    dict.entries().getFirst().key()).sourceText());
            assertEquals("2", assertInstanceOf(LiteralExpression.class,
                    dict.entries().getFirst().value()).sourceText());
        }

        var multilineStringTailSource = "func f():\n\tvar x = 1 \\\n\t\"\"\"a\nb\"\"\"\n";
        var multilineStringTailResult = recoverableParse(multilineStringTailSource);
        assertEnvelopeRecursive(multilineStringTailResult.ast(), multilineStringTailSource);
        var stmts = onlyFunction(multilineStringTailResult).body().statements();
        assertEquals(1, stmts.size());
        var xDecl = assertInstanceOf(VariableDeclaration.class, stmts.getFirst());
        var trailing = findErrorByCode(multilineStringTailResult, DiagnosticCodes.STATEMENT_TRAILING);
        assertEquals("\"\"\"a\nb\"\"\"", utf8Slice(multilineStringTailSource, trailing.range()));
        assertSame(xDecl, requireNotNull(trailing.recovery()).owner());
        assertEquals("statement.trailing", requireNotNull(trailing.recovery()).facet());
    }

    @Test
    void layoutReplaySplitsPreAndPostNewlineLexicalBatchesAcrossLfAndCrlf() {
        for (var newline : List.of("\n", "\r\n")) {
            var bothSides = ("func f():\n\tvar x = (1 §\n§\nvar after = 3\n").replace("\n", newline);
            var bothResult = recoverableParse(bothSides);
            assertEnvelopeRecursive(bothResult.ast(), bothSides);
            var fnBoth = assertInstanceOf(FunctionDeclaration.class, bothResult.ast().statements().getFirst());
            var varXBoth = assertInstanceOf(VariableDeclaration.class, fnBoth.body().statements().getFirst());
            var varAfterBoth = assertInstanceOf(VariableDeclaration.class, bothResult.ast().statements().get(1));
            assertEquals("x", varXBoth.name());
            assertEquals("after", varAfterBoth.name());
            var missingCloserBoth = findErrorByCode(bothResult, DiagnosticCodes.MISSING_CLOSER_GROUPING);
            assertSame(varXBoth, requireNotNull(missingCloserBoth.recovery()).owner());
            assertEquals("variable.initializer", requireNotNull(missingCloserBoth.recovery()).facet());
            var lexBoth = errorsOf(bothResult).stream()
                    .filter(d -> DiagnosticCodes.LEXICAL_ERROR.equals(d.code()))
                    .toList();
            assertEquals(2, lexBoth.size());
            assertNotNull(lexBoth.getFirst().recovery());
            assertSame(varXBoth, lexBoth.getFirst().recovery().owner());
            assertNull(lexBoth.get(1).recovery());

            var preOnly = ("func f():\n\tvar x = (1 §\nvar after = 3\n").replace("\n", newline);
            var preResult = recoverableParse(preOnly);
            assertEnvelopeRecursive(preResult.ast(), preOnly);
            var fnPre = assertInstanceOf(FunctionDeclaration.class, preResult.ast().statements().getFirst());
            var varXPre = assertInstanceOf(VariableDeclaration.class, fnPre.body().statements().getFirst());
            assertEquals("after", assertInstanceOf(VariableDeclaration.class, preResult.ast().statements().get(1)).name());
            var lexPre = findErrorByCode(preResult, DiagnosticCodes.LEXICAL_ERROR);
            assertNotNull(lexPre.recovery());
            assertSame(varXPre, lexPre.recovery().owner());

            var postOnly = ("func f():\n\tvar x = (1\n§\nvar after = 3\n").replace("\n", newline);
            var postResult = recoverableParse(postOnly);
            assertEnvelopeRecursive(postResult.ast(), postOnly);
            assertEquals("after", assertInstanceOf(VariableDeclaration.class, postResult.ast().statements().get(1)).name());
            assertNull(findErrorByCode(postResult, DiagnosticCodes.LEXICAL_ERROR).recovery());

            var closedGrouping = ("func f():\n\tvar x = (1 + §\n§\n2)\n").replace("\n", newline);
            var closedResult = recoverableParse(closedGrouping);
            assertEnvelopeRecursive(closedResult.ast(), closedGrouping);
            var varXClosed = assertInstanceOf(VariableDeclaration.class,
                    onlyFunction(closedResult).body().statements().getFirst());
            assertTrue(errorsOf(closedResult).stream()
                    .noneMatch(d -> DiagnosticCodes.MISSING_CLOSER_GROUPING.equals(d.code())));
            var lexClosed = errorsOf(closedResult).stream()
                    .filter(d -> DiagnosticCodes.LEXICAL_ERROR.equals(d.code()))
                    .toList();
            assertEquals(2, lexClosed.size());
            for (var lex : lexClosed) {
                assertNotNull(lex.recovery());
                assertSame(varXClosed, lex.recovery().owner());
            }

            var splitUnjoinedGrouping = ("func f():\n\tvar x = (1 §\n§\n2)\n").replace("\n", newline);
            var splitUnjoinedResult = recoverableParse(splitUnjoinedGrouping);
            assertEnvelopeRecursive(splitUnjoinedResult.ast(), splitUnjoinedGrouping);
            var fnSplit = assertInstanceOf(FunctionDeclaration.class,
                    splitUnjoinedResult.ast().statements().getFirst());
            var varXSplit = assertInstanceOf(VariableDeclaration.class, fnSplit.body().statements().getFirst());
            var lexSplit = errorsOf(splitUnjoinedResult).stream()
                    .filter(d -> DiagnosticCodes.LEXICAL_ERROR.equals(d.code()))
                    .toList();
            assertEquals(2, lexSplit.size());
            assertNotNull(lexSplit.getFirst().recovery());
            assertSame(varXSplit, lexSplit.getFirst().recovery().owner());
            assertNull(lexSplit.get(1).recovery());
        }
    }

    @Test
    void unclosedMultilineSlotClaimsInScopePostNewlinePrefixAcrossLfAndCrlf() {
        for (var newline : List.of("\n", "\r\n")) {
            // 1. Indented § inside unclosed `(1 +` claimed as RHS ErrorExpression(ERROR), dedented § stays standalone.
            var singleIndented = ("func f():\n\tvar x = (1 +\n\t\t§\n§\nvar after = 3\n").replace("\n", newline);
            var singleRes = recoverableParse(singleIndented);
            assertEnvelopeRecursive(singleRes.ast(), singleIndented);
            var fnSingle = assertInstanceOf(FunctionDeclaration.class, singleRes.ast().statements().getFirst());
            var varXSingle = assertInstanceOf(VariableDeclaration.class, fnSingle.body().statements().getFirst());
            var varAfterSingle = assertInstanceOf(VariableDeclaration.class, singleRes.ast().statements().get(1));
            assertEquals("x", varXSingle.name());
            assertEquals("after", varAfterSingle.name());
            var binSingle = assertInstanceOf(BinaryExpression.class, varXSingle.value());
            var rhsSingle = assertInstanceOf(ErrorExpression.class, binSingle.right());
            assertEquals(CstIssueKind.ERROR, rhsSingle.kind());
            assertEquals("§", utf8Slice(singleIndented, rhsSingle.range()));
            var closerSingle = findErrorByCode(singleRes, DiagnosticCodes.MISSING_CLOSER_GROUPING);
            assertSame(varXSingle, requireNotNull(closerSingle.recovery()).owner());
            assertEquals("variable.initializer", requireNotNull(closerSingle.recovery()).facet());
            var lexSingle = errorsOf(singleRes).stream()
                    .filter(d -> DiagnosticCodes.LEXICAL_ERROR.equals(d.code()))
                    .toList();
            assertEquals(2, lexSingle.size());
            assertSame(varXSingle, requireNotNull(lexSingle.getFirst().recovery()).owner());
            assertEquals("variable.initializer", requireNotNull(lexSingle.getFirst().recovery()).facet());
            assertNull(lexSingle.get(1).recovery());

            // 2. Immediately dedented § after `(1 +` crosses escape boundary: zero-width MISSING RHS, standalone §.
            var immediateDedent = ("func f():\n\tvar x = (1 +\n§\nvar after = 3\n").replace("\n", newline);
            var immRes = recoverableParse(immediateDedent);
            assertEnvelopeRecursive(immRes.ast(), immediateDedent);
            var fnImm = assertInstanceOf(FunctionDeclaration.class, immRes.ast().statements().getFirst());
            var varXImm = assertInstanceOf(VariableDeclaration.class, fnImm.body().statements().getFirst());
            assertEquals("after", assertInstanceOf(VariableDeclaration.class, immRes.ast().statements().get(1)).name());
            var binImm = assertInstanceOf(BinaryExpression.class, varXImm.value());
            var rhsImm = assertInstanceOf(ErrorExpression.class, binImm.right());
            assertEquals(CstIssueKind.MISSING, rhsImm.kind());
            assertEquals(rhsImm.range().startByte(), rhsImm.range().endByte());
            assertNull(findErrorByCode(immRes, DiagnosticCodes.LEXICAL_ERROR).recovery());

            // 3. Multiple indented § claimed into RHS ErrorExpression(ERROR), trailing dedented § stays standalone.
            var multiIndented = ("func f():\n\tvar x = (1 +\n\t\t§\n\t\t§\n§\nvar after = 3\n").replace("\n", newline);
            var multiRes = recoverableParse(multiIndented);
            assertEnvelopeRecursive(multiRes.ast(), multiIndented);
            var fnMulti = assertInstanceOf(FunctionDeclaration.class, multiRes.ast().statements().getFirst());
            var varXMulti = assertInstanceOf(VariableDeclaration.class, fnMulti.body().statements().getFirst());
            assertEquals("after", assertInstanceOf(VariableDeclaration.class, multiRes.ast().statements().get(1)).name());
            var binMulti = assertInstanceOf(BinaryExpression.class, varXMulti.value());
            var rhsMulti = assertInstanceOf(ErrorExpression.class, binMulti.right());
            assertEquals(CstIssueKind.ERROR, rhsMulti.kind());
            assertEquals("§" + newline + "\t\t§", utf8Slice(multiIndented, rhsMulti.range()));
            var lexMulti = errorsOf(multiRes).stream()
                    .filter(d -> DiagnosticCodes.LEXICAL_ERROR.equals(d.code()))
                    .toList();
            assertEquals(3, lexMulti.size());
            assertSame(varXMulti, requireNotNull(lexMulti.getFirst().recovery()).owner());
            assertEquals("variable.initializer", requireNotNull(lexMulti.getFirst().recovery()).facet());
            assertSame(varXMulti, requireNotNull(lexMulti.get(1).recovery()).owner());
            assertEquals("variable.initializer", requireNotNull(lexMulti.get(1).recovery()).facet());
            assertNull(lexMulti.get(2).recovery());

            // 4. Same-line § plus indented § both claimed into RHS ErrorExpression(ERROR), dedented § standalone.
            var sameAndIndented = ("func f():\n\tvar x = (1 + §\n\t\t§\n§\nvar after = 3\n").replace("\n", newline);
            var sameRes = recoverableParse(sameAndIndented);
            assertEnvelopeRecursive(sameRes.ast(), sameAndIndented);
            var fnSame = assertInstanceOf(FunctionDeclaration.class, sameRes.ast().statements().getFirst());
            var varXSame = assertInstanceOf(VariableDeclaration.class, fnSame.body().statements().getFirst());
            assertEquals("after", assertInstanceOf(VariableDeclaration.class, sameRes.ast().statements().get(1)).name());
            var binSame = assertInstanceOf(BinaryExpression.class, varXSame.value());
            var rhsSame = assertInstanceOf(ErrorExpression.class, binSame.right());
            assertEquals(CstIssueKind.ERROR, rhsSame.kind());
            assertEquals("§" + newline + "\t\t§", utf8Slice(sameAndIndented, rhsSame.range()));
            var lexSame = errorsOf(sameRes).stream()
                    .filter(d -> DiagnosticCodes.LEXICAL_ERROR.equals(d.code()))
                    .toList();
            assertEquals(3, lexSame.size());
            assertSame(varXSame, requireNotNull(lexSame.getFirst().recovery()).owner());
            assertEquals("variable.initializer", requireNotNull(lexSame.getFirst().recovery()).facet());
            assertSame(varXSame, requireNotNull(lexSame.get(1).recovery()).owner());
            assertEquals("variable.initializer", requireNotNull(lexSame.get(1).recovery()).facet());
            assertNull(lexSame.get(2).recovery());

            // 5. Closed grouping across lines claims both § into variable.initializer without MISSING_CLOSER_GROUPING.
            var closed = ("func f():\n\tvar x = (1 +\n\t\t§\n§\n2)\n").replace("\n", newline);
            var closedRes = recoverableParse(closed);
            assertEnvelopeRecursive(closedRes.ast(), closed);
            var varXClosed = assertInstanceOf(VariableDeclaration.class,
                    onlyFunction(closedRes).body().statements().getFirst());
            assertTrue(errorsOf(closedRes).stream()
                    .noneMatch(d -> DiagnosticCodes.MISSING_CLOSER_GROUPING.equals(d.code())));
            var binClosed = assertInstanceOf(BinaryExpression.class, varXClosed.value());
            assertInstanceOf(LiteralExpression.class, binClosed.right());
            var lexClosed = errorsOf(closedRes).stream()
                    .filter(d -> DiagnosticCodes.LEXICAL_ERROR.equals(d.code()))
                    .toList();
            assertEquals(2, lexClosed.size());
            for (var lex : lexClosed) {
                assertSame(varXClosed, requireNotNull(lex.recovery()).owner());
                assertEquals("variable.initializer", requireNotNull(lex.recovery()).facet());
            }

            // 6. Complete `(1` before an indented `\t§` does not synthesize a bogus RHS node, while
            //    claiming the in-scope `\t§` to `x` (`variable.initializer`) per I5; a dedented `§` stays standalone.
            var completeIndented = ("func f():\n\tvar x = (1\n\t§\n\tvar after = 3\n").replace("\n", newline);
            var compRes = recoverableParse(completeIndented);
            assertEnvelopeRecursive(compRes.ast(), completeIndented);
            var fnComp = onlyFunction(compRes);
            var varXComp = assertInstanceOf(VariableDeclaration.class, fnComp.body().statements().getFirst());
            var varAfterComp = assertInstanceOf(VariableDeclaration.class, fnComp.body().statements().get(1));
            assertEquals("after", varAfterComp.name());
            assertInstanceOf(LiteralExpression.class, varXComp.value());
            var compLex = findErrorByCode(compRes, DiagnosticCodes.LEXICAL_ERROR);
            assertSame(varXComp, requireNotNull(compLex.recovery()).owner());
            assertEquals("variable.initializer", requireNotNull(compLex.recovery()).facet());

            var completeDedent = ("func f():\n\tvar x = (1\n§\nvar after = 3\n").replace("\n", newline);
            var compDedRes = recoverableParse(completeDedent);
            assertEnvelopeRecursive(compDedRes.ast(), completeDedent);
            var fnCompDed = assertInstanceOf(FunctionDeclaration.class, compDedRes.ast().statements().getFirst());
            var varXCompDed = assertInstanceOf(VariableDeclaration.class, fnCompDed.body().statements().getFirst());
            assertEquals("after",
                    assertInstanceOf(VariableDeclaration.class, compDedRes.ast().statements().get(1)).name());
            assertInstanceOf(LiteralExpression.class, varXCompDed.value());
            assertNull(findErrorByCode(compDedRes, DiagnosticCodes.LEXICAL_ERROR).recovery());
        }
    }

    @Test
    void unclosedContainerLoopsContinuationProvenanceAndPhysicalOrderCutoffAcrossLfAndCrlf() {
        for (var newline : List.of("\n", "\r\n")) {
            // Finding 1a: unclosed array recovers indented § as 2nd ErrorExpression(ERROR), dedented § standalone.
            var arrSrc = ("func f():\n\tvar x = [1,\n\t\t§\n§\nvar after = 3\n").replace("\n", newline);
            var arrRes = recoverableParse(arrSrc);
            assertEnvelopeRecursive(arrRes.ast(), arrSrc);
            var fnArr = assertInstanceOf(FunctionDeclaration.class, arrRes.ast().statements().getFirst());
            var varXArr = assertInstanceOf(VariableDeclaration.class, fnArr.body().statements().getFirst());
            assertEquals("after", assertInstanceOf(VariableDeclaration.class, arrRes.ast().statements().get(1)).name());
            var arrExpr = assertInstanceOf(ArrayExpression.class, varXArr.value());
            assertEquals(2, arrExpr.elements().size());
            var arrElem1 = assertInstanceOf(ErrorExpression.class, arrExpr.elements().get(1));
            assertEquals(CstIssueKind.ERROR, arrElem1.kind());
            assertEquals("§", utf8Slice(arrSrc, arrElem1.range()));
            var arrLex = errorsOf(arrRes).stream()
                    .filter(d -> DiagnosticCodes.LEXICAL_ERROR.equals(d.code()))
                    .toList();
            assertEquals(2, arrLex.size());
            assertSame(varXArr, requireNotNull(arrLex.getFirst().recovery()).owner());
            assertEquals("variable.initializer", requireNotNull(arrLex.getFirst().recovery()).facet());
            assertNull(arrLex.get(1).recovery());

            // Finding 1b: unclosed array with expression-starter escape token does NOT swallow print(1).
            var arrEscapeSrc = ("func f():\n\tif true:\n\t\tvar x = [1,\n\t\t\t§\n\t§\n\tprint(1)\n").replace("\n", newline);
            var arrEscapeRes = recoverableParse(arrEscapeSrc);
            assertEnvelopeRecursive(arrEscapeRes.ast(), arrEscapeSrc);
            var fnArrEsc = onlyFunction(arrEscapeRes);
            var ifArrEsc = assertInstanceOf(IfStatement.class, fnArrEsc.body().statements().getFirst());
            var varXArrEsc = assertInstanceOf(VariableDeclaration.class, ifArrEsc.body().statements().getFirst());
            var arrEscExpr = assertInstanceOf(ArrayExpression.class, varXArrEsc.value());
            assertEquals(2, arrEscExpr.elements().size());
            assertEquals("§", utf8Slice(arrEscapeSrc,
                    assertInstanceOf(ErrorExpression.class, arrEscExpr.elements().get(1)).range()));
            var printStmt = assertInstanceOf(ExpressionStatement.class, fnArrEsc.body().statements().get(1));
            assertInstanceOf(CallExpression.class, printStmt.expression());
            var arrEscLex = errorsOf(arrEscapeRes).stream()
                    .filter(d -> DiagnosticCodes.LEXICAL_ERROR.equals(d.code()))
                    .toList();
            assertEquals(2, arrEscLex.size());
            assertSame(varXArrEsc, requireNotNull(arrEscLex.getFirst().recovery()).owner());
            assertTrue(arrEscLex.get(1).recovery() == null || arrEscLex.get(1).recovery().owner() != varXArrEsc);

            var arrTopPrintSrc = ("func f():\n\tvar x = [1,\n\t\t§\n§\nprint(1)\n").replace("\n", newline);
            var arrTopPrintRes = recoverableParse(arrTopPrintSrc);
            assertEnvelopeRecursive(arrTopPrintRes.ast(), arrTopPrintSrc);
            var fnTopPrint = assertInstanceOf(FunctionDeclaration.class, arrTopPrintRes.ast().statements().getFirst());
            var varXTopPrint = assertInstanceOf(VariableDeclaration.class, fnTopPrint.body().statements().getFirst());
            assertEquals(2, assertInstanceOf(ArrayExpression.class, varXTopPrint.value()).elements().size());
            var arrTopPrintLex = errorsOf(arrTopPrintRes).stream()
                    .filter(d -> DiagnosticCodes.LEXICAL_ERROR.equals(d.code()))
                    .toList();
            assertEquals(2, arrTopPrintLex.size());
            assertSame(varXTopPrint, requireNotNull(arrTopPrintLex.getFirst().recovery()).owner());
            assertNull(arrTopPrintLex.get(1).recovery());
            assertTrue(errorMessages(arrTopPrintRes).stream().anyMatch(m -> m.contains("\"print\"")));

            // Finding 1c: negative control (dedented § only) keeps [1] and standalone §.
            var arrNegSrc = ("func f():\n\tvar x = [1,\n§\nvar after = 3\n").replace("\n", newline);
            var arrNegRes = recoverableParse(arrNegSrc);
            assertEnvelopeRecursive(arrNegRes.ast(), arrNegSrc);
            var fnArrNeg = assertInstanceOf(FunctionDeclaration.class, arrNegRes.ast().statements().getFirst());
            var varXArrNeg = assertInstanceOf(VariableDeclaration.class, fnArrNeg.body().statements().getFirst());
            assertEquals("after", assertInstanceOf(VariableDeclaration.class, arrNegRes.ast().statements().get(1)).name());
            assertEquals(1, assertInstanceOf(ArrayExpression.class, varXArrNeg.value()).elements().size());
            assertNull(findErrorByCode(arrNegRes, DiagnosticCodes.LEXICAL_ERROR).recovery());

            // Finding 1d: unclosed dictionary recovers pending key + zero-width MISSING value.
            var dictSrc = ("func f():\n\tvar x = {1: 2,\n\t\t§\n§\nvar after = 3\n").replace("\n", newline);
            var dictRes = recoverableParse(dictSrc);
            assertEnvelopeRecursive(dictRes.ast(), dictSrc);
            var fnDict = assertInstanceOf(FunctionDeclaration.class, dictRes.ast().statements().getFirst());
            var varXDict = assertInstanceOf(VariableDeclaration.class, fnDict.body().statements().getFirst());
            assertEquals("after", assertInstanceOf(VariableDeclaration.class, dictRes.ast().statements().get(1)).name());
            var dictExpr = assertInstanceOf(DictionaryExpression.class, varXDict.value());
            assertEquals(2, dictExpr.entries().size());
            var dictEntry1 = dictExpr.entries().get(1);
            var dictKey1 = assertInstanceOf(ErrorExpression.class, dictEntry1.key());
            assertEquals(CstIssueKind.ERROR, dictKey1.kind());
            assertEquals("§", utf8Slice(dictSrc, dictKey1.range()));
            var dictVal1 = assertInstanceOf(ErrorExpression.class, dictEntry1.value());
            assertEquals(CstIssueKind.MISSING, dictVal1.kind());
            assertEquals(dictKey1.range().endByte(), dictVal1.range().startByte());
            assertEquals(dictVal1.range().startByte(), dictVal1.range().endByte());
            var dictLex = errorsOf(dictRes).stream()
                    .filter(d -> DiagnosticCodes.LEXICAL_ERROR.equals(d.code()))
                    .toList();
            assertEquals(2, dictLex.size());
            assertSame(varXDict, requireNotNull(dictLex.getFirst().recovery()).owner());
            assertEquals("variable.initializer", requireNotNull(dictLex.getFirst().recovery()).facet());
            assertNull(dictLex.get(1).recovery());

            // Finding 1e: unclosed call arguments recovers pending 2nd arg.
            var callSrc = ("func f():\n\tvar x = f(1,\n\t\t§\n§\nvar after = 3\n").replace("\n", newline);
            var callRes = recoverableParse(callSrc);
            assertEnvelopeRecursive(callRes.ast(), callSrc);
            var fnCall = assertInstanceOf(FunctionDeclaration.class, callRes.ast().statements().getFirst());
            var varXCall = assertInstanceOf(VariableDeclaration.class, fnCall.body().statements().getFirst());
            assertEquals("after", assertInstanceOf(VariableDeclaration.class, callRes.ast().statements().get(1)).name());
            var callExpr = assertInstanceOf(CallExpression.class, varXCall.value());
            assertEquals(2, callExpr.arguments().size());
            var callArg1 = assertInstanceOf(ErrorExpression.class, callExpr.arguments().get(1));
            assertEquals(CstIssueKind.ERROR, callArg1.kind());
            assertEquals("§", utf8Slice(callSrc, callArg1.range()));
            var callLex = errorsOf(callRes).stream()
                    .filter(d -> DiagnosticCodes.LEXICAL_ERROR.equals(d.code()))
                    .toList();
            assertEquals(2, callLex.size());
            assertSame(varXCall, requireNotNull(callLex.getFirst().recovery()).owner());
            assertNull(callLex.get(1).recovery());

            // Finding 1f: sibling unclosed multiline loops (assert message, annotation args, match patterns).
            var assertSrc = ("func f():\n\tassert(true,\n\t\t§\n§\nvar after = 3\n").replace("\n", newline);
            var assertRes = recoverableParse(assertSrc);
            assertEnvelopeRecursive(assertRes.ast(), assertSrc);
            var assertStmt = assertInstanceOf(AssertStatement.class,
                    assertInstanceOf(FunctionDeclaration.class, assertRes.ast().statements().getFirst())
                            .body().statements().getFirst());
            assertEquals("§", utf8Slice(assertSrc,
                    assertInstanceOf(ErrorExpression.class, assertStmt.message()).range()));
            assertEquals("after", assertInstanceOf(VariableDeclaration.class, assertRes.ast().statements().get(1)).name());
            var assertLex = errorsOf(assertRes).stream()
                    .filter(d -> DiagnosticCodes.LEXICAL_ERROR.equals(d.code()))
                    .toList();
            assertEquals(2, assertLex.size());
            assertSame(assertStmt, requireNotNull(assertLex.getFirst().recovery()).owner());
            assertNull(assertLex.get(1).recovery());

            var annSrc = ("func f():\n\t@warning_ignore(\"a\",\n\t\t§\n§\nvar after = 3\n").replace("\n", newline);
            var annRes = recoverableParse(annSrc);
            assertEnvelopeRecursive(annRes.ast(), annSrc);
            var annStmt = assertInstanceOf(AnnotationStatement.class,
                    assertInstanceOf(FunctionDeclaration.class, annRes.ast().statements().getFirst())
                            .body().statements().getFirst());
            assertEquals(2, annStmt.arguments().size());
            assertEquals("§", utf8Slice(annSrc,
                    assertInstanceOf(ErrorExpression.class, annStmt.arguments().get(1)).range()));
            assertEquals("after", assertInstanceOf(VariableDeclaration.class, annRes.ast().statements().get(1)).name());
            var annLex = errorsOf(annRes).stream()
                    .filter(d -> DiagnosticCodes.LEXICAL_ERROR.equals(d.code()))
                    .toList();
            assertEquals(2, annLex.size());
            assertSame(annStmt, requireNotNull(annLex.getFirst().recovery()).owner());
            assertNull(annLex.get(1).recovery());

            var matchSrc = ("func f():\n\tmatch x:\n\t\t[1,\n\t\t\t§\n§\nvar after = 3\n").replace("\n", newline);
            var matchRes = recoverableParse(matchSrc);
            assertEnvelopeRecursive(matchRes.ast(), matchSrc);
            var matchStmt = assertInstanceOf(MatchStatement.class,
                    assertInstanceOf(FunctionDeclaration.class, matchRes.ast().statements().getFirst())
                            .body().statements().getFirst());
            var patArr = assertInstanceOf(ArrayExpression.class, matchStmt.sections().getFirst().patterns().getFirst());
            assertEquals(2, patArr.elements().size());
            assertEquals("§", utf8Slice(matchSrc,
                    assertInstanceOf(ErrorExpression.class, patArr.elements().get(1)).range()));
            assertEquals("after", assertInstanceOf(VariableDeclaration.class, matchRes.ast().statements().get(1)).name());
            var matchLex = errorsOf(matchRes).stream()
                    .filter(d -> DiagnosticCodes.LEXICAL_ERROR.equals(d.code()))
                    .toList();
            assertEquals(2, matchLex.size());
            assertSame(matchStmt.sections().getFirst(), requireNotNull(matchLex.getFirst().recovery()).owner());
            assertNull(matchLex.get(1).recovery());

            // Finding 2a: explicit `\` continuation links second § to x while hard newline leaves 3rd § standalone.
            var contSrc = ("func f():\n\tvar x = (1 +\n\t\t§ \\\n§\n§\nvar after = 3\n").replace("\n", newline);
            var contRes = recoverableParse(contSrc);
            assertEnvelopeRecursive(contRes.ast(), contSrc);
            var fnCont = assertInstanceOf(FunctionDeclaration.class, contRes.ast().statements().getFirst());
            var varXCont = assertInstanceOf(VariableDeclaration.class, fnCont.body().statements().getFirst());
            assertEquals("after", assertInstanceOf(VariableDeclaration.class, contRes.ast().statements().get(1)).name());
            var binCont = assertInstanceOf(BinaryExpression.class, varXCont.value());
            var rhsCont = assertInstanceOf(ErrorExpression.class, binCont.right());
            assertEquals(CstIssueKind.ERROR, rhsCont.kind());
            assertEquals("§ \\" + newline + "§", utf8Slice(contSrc, rhsCont.range()));
            var contLex = errorsOf(contRes).stream()
                    .filter(d -> DiagnosticCodes.LEXICAL_ERROR.equals(d.code()))
                    .toList();
            assertEquals(3, contLex.size());
            assertSame(varXCont, requireNotNull(contLex.get(0).recovery()).owner());
            assertSame(varXCont, requireNotNull(contLex.get(1).recovery()).owner());
            assertNull(contLex.get(2).recovery());

            // Finding 2b: multiple errors on the continued physical line stay in-scope until next hard newline.
            var multiContSrc = ("func f():\n\tvar x = (1 +\n\t\t§ \\\n§ §\n§\nvar after = 3\n").replace("\n", newline);
            var multiContRes = recoverableParse(multiContSrc);
            assertEnvelopeRecursive(multiContRes.ast(), multiContSrc);
            var fnMultiCont = assertInstanceOf(FunctionDeclaration.class, multiContRes.ast().statements().getFirst());
            var varXMultiCont = assertInstanceOf(VariableDeclaration.class, fnMultiCont.body().statements().getFirst());
            assertEquals("after", assertInstanceOf(VariableDeclaration.class, multiContRes.ast().statements().get(1)).name());
            var binMultiCont = assertInstanceOf(BinaryExpression.class, varXMultiCont.value());
            var rhsMultiCont = assertInstanceOf(ErrorExpression.class, binMultiCont.right());
            assertEquals(CstIssueKind.ERROR, rhsMultiCont.kind());
            assertEquals("§ \\" + newline + "§ §", utf8Slice(multiContSrc, rhsMultiCont.range()));
            var multiContLex = errorsOf(multiContRes).stream()
                    .filter(d -> DiagnosticCodes.LEXICAL_ERROR.equals(d.code()))
                    .toList();
            assertEquals(4, multiContLex.size());
            assertSame(varXMultiCont, requireNotNull(multiContLex.get(0).recovery()).owner());
            assertSame(varXMultiCont, requireNotNull(multiContLex.get(1).recovery()).owner());
            assertSame(varXMultiCont, requireNotNull(multiContLex.get(2).recovery()).owner());
            assertNull(multiContLex.get(3).recovery());

            // Finding 3: LIFO stray-CR emission order across comment lines resolves cutoff in physical source order.
            var lifoSrc = ("func f():\n\tvar x = (1 +\n\t\t\r # in\n\r # out\nvar after = 3\n").replace("\n", newline);
            var lifoRes = recoverableParse(lifoSrc);
            assertEnvelopeRecursive(lifoRes.ast(), lifoSrc);
            var fnLifo = assertInstanceOf(FunctionDeclaration.class, lifoRes.ast().statements().getFirst());
            var varXLifo = assertInstanceOf(VariableDeclaration.class, fnLifo.body().statements().getFirst());
            assertEquals("after", assertInstanceOf(VariableDeclaration.class, lifoRes.ast().statements().get(1)).name());
            var binLifo = assertInstanceOf(BinaryExpression.class, varXLifo.value());
            var rhsLifo = assertInstanceOf(ErrorExpression.class, binLifo.right());
            assertEquals(CstIssueKind.ERROR, rhsLifo.kind());
            assertEquals("\r", utf8Slice(lifoSrc, rhsLifo.range()));
            var lifoLex = errorsOf(lifoRes).stream()
                    .filter(d -> DiagnosticCodes.LEXICAL_ERROR.equals(d.code()))
                    .toList();
            assertEquals(2, lifoLex.size());
            // Global emission order remains LIFO (# out emitted first, # in second), while physical cutoff owns # in.
            assertTrue(lifoLex.get(0).range().startByte() > lifoLex.get(1).range().startByte());
            assertNull(lifoLex.get(0).recovery());
            assertSame(varXLifo, requireNotNull(lifoLex.get(1).recovery()).owner());
            assertEquals("variable.initializer", requireNotNull(lifoLex.get(1).recovery()).facet());
        }
    }

    @Test
    void unclosedMultilineCommaGuardContinuationProvenanceAndHeaderOwnersAcrossLfAndCrlf() {
        for (var newline : List.of("\n", "\r\n")) {
            // Finding 1a: recovered assert.message must not consume outer array comma.
            var assertMsgCommaSrc = ("var xs = [func():\n\tassert(true,\n\t\t§\n, 2]\n").replace("\n", newline);
            var assertMsgCommaRes = recoverableParse(assertMsgCommaSrc);
            assertEnvelopeRecursive(assertMsgCommaRes.ast(), assertMsgCommaSrc);
            var xsAssertMsg = assertInstanceOf(ArrayExpression.class,
                    assertInstanceOf(VariableDeclaration.class, assertMsgCommaRes.ast().statements().getFirst()).value());
            assertEquals(2, xsAssertMsg.elements().size(), () -> xsAssertMsg.elements().toString());
            var lambdaAssertMsg = assertInstanceOf(LambdaExpression.class, xsAssertMsg.elements().getFirst());
            var assertMsgStmt = assertInstanceOf(AssertStatement.class, lambdaAssertMsg.body().statements().getFirst());
            assertEquals("§", utf8Slice(assertMsgCommaSrc,
                    assertInstanceOf(ErrorExpression.class, assertMsgStmt.message()).range()));
            assertEquals("2", assertInstanceOf(LiteralExpression.class, xsAssertMsg.elements().get(1)).sourceText());
            assertFalse(errorsOf(assertMsgCommaRes).stream()
                    .anyMatch(d -> DiagnosticCodes.STATEMENT_TRAILING.equals(d.code())));
            var assertMsgLex = errorsOf(assertMsgCommaRes).stream()
                    .filter(d -> DiagnosticCodes.LEXICAL_ERROR.equals(d.code()))
                    .toList();
            assertEquals(1, assertMsgLex.size());
            assertSame(assertMsgStmt, requireNotNull(assertMsgLex.getFirst().recovery()).owner());
            assertEquals("assert.message", requireNotNull(assertMsgLex.getFirst().recovery()).facet());

            // Finding 1b: unclosed assert without message must not consume outer array comma.
            var assertNoMsgCommaSrc = ("var xs = [func():\n\tassert(true\n, 2]\n").replace("\n", newline);
            var assertNoMsgCommaRes = recoverableParse(assertNoMsgCommaSrc);
            assertEnvelopeRecursive(assertNoMsgCommaRes.ast(), assertNoMsgCommaSrc);
            var xsAssertNoMsg = assertInstanceOf(ArrayExpression.class,
                    assertInstanceOf(VariableDeclaration.class, assertNoMsgCommaRes.ast().statements().getFirst()).value());
            assertEquals(2, xsAssertNoMsg.elements().size(), () -> xsAssertNoMsg.elements().toString());
            var lambdaAssertNoMsg = assertInstanceOf(LambdaExpression.class, xsAssertNoMsg.elements().getFirst());
            var assertNoMsgStmt = assertInstanceOf(AssertStatement.class, lambdaAssertNoMsg.body().statements().getFirst());
            assertNull(assertNoMsgStmt.message());
            assertEquals("2", assertInstanceOf(LiteralExpression.class, xsAssertNoMsg.elements().get(1)).sourceText());

            // Finding 1c: unclosed preload inside enclosing array must not consume outer array comma.
            var preloadCommaSrc = ("class C:\n\tfunc f():\n\t\tvar xs = [preload(\"res://a.gd\"\n\t, 2]\n")
                    .replace("\n", newline);
            var preloadCommaRes = recoverableParse(preloadCommaSrc);
            assertEnvelopeRecursive(preloadCommaRes.ast(), preloadCommaSrc);
            var clsPreload = assertInstanceOf(ClassDeclaration.class, preloadCommaRes.ast().statements().getFirst());
            var fnPreload = assertInstanceOf(FunctionDeclaration.class, clsPreload.body().statements().getFirst());
            var xsPreload = assertInstanceOf(ArrayExpression.class,
                    assertInstanceOf(VariableDeclaration.class, fnPreload.body().statements().getFirst()).value());
            assertEquals(2, xsPreload.elements().size(), () -> xsPreload.elements().toString());
            assertInstanceOf(PreloadExpression.class, xsPreload.elements().getFirst());
            assertEquals("2", assertInstanceOf(LiteralExpression.class, xsPreload.elements().get(1)).sourceText());

            // Finding 2a (positive): explicit `\` after in-scope lexical error keeps continuation across hard newline.
            var contPosSrc = ("func f():\n\tvar x = (1 +\n\t\t§ \\\n2\nvar after = 3\n").replace("\n", newline);
            var contPosRes = recoverableParse(contPosSrc);
            assertEnvelopeRecursive(contPosRes.ast(), contPosSrc);
            var fnContPos = assertInstanceOf(FunctionDeclaration.class, contPosRes.ast().statements().getFirst());
            var varXContPos = assertInstanceOf(VariableDeclaration.class, fnContPos.body().statements().getFirst());
            assertEquals("after", assertInstanceOf(VariableDeclaration.class, contPosRes.ast().statements().get(1)).name());
            var binContPos = assertInstanceOf(BinaryExpression.class, varXContPos.value());
            assertEquals("2", assertInstanceOf(LiteralExpression.class, binContPos.right()).sourceText());
            var contPosLex = errorsOf(contPosRes).stream()
                    .filter(d -> DiagnosticCodes.LEXICAL_ERROR.equals(d.code()))
                    .toList();
            assertEquals(1, contPosLex.size());
            assertSame(varXContPos, requireNotNull(contPosLex.getFirst().recovery()).owner());
            assertEquals("variable.initializer", requireNotNull(contPosLex.getFirst().recovery()).facet());

            // Finding 2b (negative): dedented `§ \` already escaped enclosing suite, so `2` must not be swallowed.
            var contNegSrc = ("func f():\n\tvar x = (1 +\n§ \\\n2\nvar after = 3\n").replace("\n", newline);
            var contNegRes = recoverableParse(contNegSrc);
            assertEnvelopeRecursive(contNegRes.ast(), contNegSrc);
            var fnContNeg = assertInstanceOf(FunctionDeclaration.class, contNegRes.ast().statements().getFirst());
            var varXContNeg = assertInstanceOf(VariableDeclaration.class, fnContNeg.body().statements().getFirst());
            assertEquals("after", assertInstanceOf(VariableDeclaration.class, contNegRes.ast().statements().get(1)).name());
            var binContNeg = assertInstanceOf(BinaryExpression.class, varXContNeg.value());
            assertEquals(CstIssueKind.MISSING, assertInstanceOf(ErrorExpression.class, binContNeg.right()).kind());
            var contNegLex = errorsOf(contNegRes).stream()
                    .filter(d -> DiagnosticCodes.LEXICAL_ERROR.equals(d.code()))
                    .toList();
            assertEquals(1, contNegLex.size());
            assertNull(contNegLex.getFirst().recovery());

            // Finding 2c (negative): mixed in-scope then dedented `\` still cuts off at dedented line.
            var contMixedSrc = ("func f():\n\tvar x = (1 +\n\t\t§\n§ \\\n2\nvar after = 3\n").replace("\n", newline);
            var contMixedRes = recoverableParse(contMixedSrc);
            assertEnvelopeRecursive(contMixedRes.ast(), contMixedSrc);
            var fnContMixed = assertInstanceOf(FunctionDeclaration.class, contMixedRes.ast().statements().getFirst());
            var varXContMixed = assertInstanceOf(VariableDeclaration.class, fnContMixed.body().statements().getFirst());
            assertEquals("after", assertInstanceOf(VariableDeclaration.class, contMixedRes.ast().statements().get(1)).name());
            var binContMixed = assertInstanceOf(BinaryExpression.class, varXContMixed.value());
            var rhsContMixed = assertInstanceOf(ErrorExpression.class, binContMixed.right());
            assertEquals(CstIssueKind.ERROR, rhsContMixed.kind());
            assertEquals("§", utf8Slice(contMixedSrc, rhsContMixed.range()));
            var contMixedLex = errorsOf(contMixedRes).stream()
                    .filter(d -> DiagnosticCodes.LEXICAL_ERROR.equals(d.code()))
                    .toList();
            assertEquals(2, contMixedLex.size());
            assertSame(varXContMixed, requireNotNull(contMixedLex.get(0).recovery()).owner());
            assertNull(contMixedLex.get(1).recovery());

            // Finding 2d (control): row 5.14 explicit `\` directly before top-level declaration still stops cleanly.
            var contCtrlSrc = ("func f():\n\tvar x = (1\n\\\nvar after = 3\n").replace("\n", newline);
            var contCtrlRes = recoverableParse(contCtrlSrc);
            assertEnvelopeRecursive(contCtrlRes.ast(), contCtrlSrc);
            var fnContCtrl = assertInstanceOf(FunctionDeclaration.class, contCtrlRes.ast().statements().getFirst());
            var varXContCtrl = assertInstanceOf(VariableDeclaration.class, fnContCtrl.body().statements().getFirst());
            assertEquals("1", assertInstanceOf(LiteralExpression.class, varXContCtrl.value()).sourceText());
            assertEquals("after", assertInstanceOf(VariableDeclaration.class, contCtrlRes.ast().statements().get(1)).name());

            // Finding 3a: dedented `§ func after(): pass` stops unclosed multiline array inside function.
            var errPrefixFuncSrc = ("func f():\n\tvar x = [1,\n\t\t§\n§ func after(): pass\n").replace("\n", newline);
            var errPrefixFuncRes = recoverableParse(errPrefixFuncSrc);
            assertEnvelopeRecursive(errPrefixFuncRes.ast(), errPrefixFuncSrc);
            assertEquals(2, errPrefixFuncRes.ast().statements().size(), () -> errPrefixFuncRes.ast().statements().toString());
            var fnPrefix = assertInstanceOf(FunctionDeclaration.class, errPrefixFuncRes.ast().statements().getFirst());
            var varXPrefix = assertInstanceOf(VariableDeclaration.class, fnPrefix.body().statements().getFirst());
            var arrPrefix = assertInstanceOf(ArrayExpression.class, varXPrefix.value());
            assertEquals(2, arrPrefix.elements().size());
            assertEquals("§", utf8Slice(errPrefixFuncSrc,
                    assertInstanceOf(ErrorExpression.class, arrPrefix.elements().get(1)).range()));
            var afterFunc = assertInstanceOf(FunctionDeclaration.class, errPrefixFuncRes.ast().statements().get(1));
            assertEquals("after", afterFunc.name());
            var prefixFuncLex = errorsOf(errPrefixFuncRes).stream()
                    .filter(d -> DiagnosticCodes.LEXICAL_ERROR.equals(d.code()))
                    .toList();
            assertEquals(2, prefixFuncLex.size());
            assertSame(varXPrefix, requireNotNull(prefixFuncLex.get(0).recovery()).owner());
            assertEquals("variable.initializer", requireNotNull(prefixFuncLex.get(0).recovery()).facet());
            assertNull(prefixFuncLex.get(1).recovery());

            // Finding 3b: top-level `§ func after(): pass` also stops unclosed multiline array without swallowing `after`.
            var errPrefixTopSrc = ("var x = [1,\n\t§\n§ func after(): pass\n").replace("\n", newline);
            var errPrefixTopRes = recoverableParse(errPrefixTopSrc);
            assertEnvelopeRecursive(errPrefixTopRes.ast(), errPrefixTopSrc);
            assertEquals(2, errPrefixTopRes.ast().statements().size(), () -> errPrefixTopRes.ast().statements().toString());
            var varXTop = assertInstanceOf(VariableDeclaration.class, errPrefixTopRes.ast().statements().getFirst());
            var arrTop = assertInstanceOf(ArrayExpression.class, varXTop.value());
            assertEquals(2, arrTop.elements().size());
            assertEquals("§", utf8Slice(errPrefixTopSrc,
                    assertInstanceOf(ErrorExpression.class, arrTop.elements().get(1)).range()));
            assertEquals("after", assertInstanceOf(FunctionDeclaration.class, errPrefixTopRes.ast().statements().get(1)).name());
            var prefixTopLex = errorsOf(errPrefixTopRes).stream()
                    .filter(d -> DiagnosticCodes.LEXICAL_ERROR.equals(d.code()))
                    .toList();
            assertEquals(2, prefixTopLex.size());
            assertSame(varXTop, requireNotNull(prefixTopLex.get(0).recovery()).owner());
            assertNull(prefixTopLex.get(1).recovery());

            // Finding 3c (control): valid multiline lambda inside top-level array stays clean.
            var lambdaCtrlSrc = ("var xs = [\n\tfunc(): pass\n]\n").replace("\n", newline);
            var lambdaCtrlRes = parse(lambdaCtrlSrc);
            assertEquals(0, errorsOf(lambdaCtrlRes).size(), () -> errorsOf(lambdaCtrlRes).toString());
            var xsLambdaCtrl = assertInstanceOf(ArrayExpression.class,
                    assertInstanceOf(VariableDeclaration.class, lambdaCtrlRes.ast().statements().getFirst()).value());
            assertEquals(1, xsLambdaCtrl.elements().size());
            assertInstanceOf(LambdaExpression.class, xsLambdaCtrl.elements().getFirst());

            // Finding 4a: signal parameter list attributes in-scope § to signal.parameter and leaves dedented § standalone.
            for (var signalCase : List.of(
                    new Object[]{"class C:\n\tsignal changed(value,\n\t\t§\n§\nvar after = 3\n", 1},
                    new Object[]{"class C:\n\tsignal changed(\n\t\t§\n§\nvar after = 3\n", 0})) {
                var sigSrc = ((String) signalCase[0]).replace("\n", newline);
                var expectedParams = (int) signalCase[1];
                var sigRes = recoverableParse(sigSrc);
                assertEnvelopeRecursive(sigRes.ast(), sigSrc);
                assertEquals(2, sigRes.ast().statements().size(), () -> sigSrc + " -> " + sigRes.ast().statements());
                var clsSig = assertInstanceOf(ClassDeclaration.class, sigRes.ast().statements().getFirst());
                var sigStmt = assertInstanceOf(SignalStatement.class, clsSig.body().statements().getFirst());
                assertEquals("changed", sigStmt.name());
                assertEquals(expectedParams, sigStmt.parameters().size());
                assertEquals("after", assertInstanceOf(VariableDeclaration.class, sigRes.ast().statements().get(1)).name());
                var sigLex = errorsOf(sigRes).stream()
                        .filter(d -> DiagnosticCodes.LEXICAL_ERROR.equals(d.code()))
                        .toList();
                assertEquals(2, sigLex.size());
                assertSame(sigStmt, requireNotNull(sigLex.get(0).recovery()).owner());
                assertEquals("signal.parameter", requireNotNull(sigLex.get(0).recovery()).facet());
                assertNull(sigLex.get(1).recovery());
            }

            // Finding 4b: function signature attributes in-scope § to function.signature and leaves dedented § standalone.
            for (var funcCase : List.of(
                    new Object[]{"class C:\n\tfunc method(value,\n\t\t§\n§\nvar after = 3\n", 1},
                    new Object[]{"class C:\n\tfunc method(\n\t\t§\n§\nvar after = 3\n", 0})) {
                var fnSrc = ((String) funcCase[0]).replace("\n", newline);
                var expectedParams = (int) funcCase[1];
                var fnRes = recoverableParse(fnSrc);
                assertEnvelopeRecursive(fnRes.ast(), fnSrc);
                assertEquals(2, fnRes.ast().statements().size(), () -> fnSrc + " -> " + fnRes.ast().statements());
                var clsFn = assertInstanceOf(ClassDeclaration.class, fnRes.ast().statements().getFirst());
                var methodDecl = assertInstanceOf(FunctionDeclaration.class, clsFn.body().statements().getFirst());
                assertEquals("method", methodDecl.name());
                assertEquals(expectedParams, methodDecl.parameters().size());
                assertEquals("after", assertInstanceOf(VariableDeclaration.class, fnRes.ast().statements().get(1)).name());
                var fnLex = errorsOf(fnRes).stream()
                        .filter(d -> DiagnosticCodes.LEXICAL_ERROR.equals(d.code()))
                        .toList();
                assertEquals(2, fnLex.size());
                assertSame(methodDecl, requireNotNull(fnLex.get(0).recovery()).owner());
                assertEquals("function.signature", requireNotNull(fnLex.get(0).recovery()).facet());
                assertNull(fnLex.get(1).recovery());
            }

            // Finding 4c: enum body attributes in-scope § to enum.body and leaves dedented § standalone.
            for (var enumCase : List.of(
                    new Object[]{"class C:\n\tenum E {\n\t\tA,\n\t\t§\n§\nvar after = 3\n", 1},
                    new Object[]{"class C:\n\tenum E {\n\t\t§\n§\nvar after = 3\n", 0})) {
                var enumSrc = ((String) enumCase[0]).replace("\n", newline);
                var expectedMembers = (int) enumCase[1];
                var enumRes = recoverableParse(enumSrc);
                assertEnvelopeRecursive(enumRes.ast(), enumSrc);
                assertEquals(2, enumRes.ast().statements().size(), () -> enumSrc + " -> " + enumRes.ast().statements());
                var clsEnum = assertInstanceOf(ClassDeclaration.class, enumRes.ast().statements().getFirst());
                var enumDecl = assertInstanceOf(EnumDeclaration.class, clsEnum.body().statements().getFirst());
                assertEquals("E", enumDecl.name());
                assertEquals(expectedMembers, enumDecl.members().size());
                assertEquals("after", assertInstanceOf(VariableDeclaration.class, enumRes.ast().statements().get(1)).name());
                var enumLex = errorsOf(enumRes).stream()
                        .filter(d -> DiagnosticCodes.LEXICAL_ERROR.equals(d.code()))
                        .toList();
                assertEquals(2, enumLex.size());
                assertSame(enumDecl, requireNotNull(enumLex.get(0).recovery()).owner());
                assertEquals("enum.body", requireNotNull(enumLex.get(0).recovery()).facet());
                assertNull(enumLex.get(1).recovery());
            }
        }
    }

    @Test
    void errorBeforeOperandContinuationStrayCrBackslashStopAndEofHeaderAttributionAcrossLfAndCrlf() {
        for (var newline : List.of("\n", "\r\n")) {
            // Finding 1: explicit `\` followed by lexical error on the continued line keeps RHS operand in-scope.
            var errBeforeOpSrc = ("func f():\n\tvar x = (1 +\n\t\t\\\n§ 2\nvar after = 3\n").replace("\n", newline);
            var errBeforeOpRes = recoverableParse(errBeforeOpSrc);
            assertEnvelopeRecursive(errBeforeOpRes.ast(), errBeforeOpSrc);
            assertEquals(2, errBeforeOpRes.ast().statements().size(), () -> errBeforeOpRes.ast().statements().toString());
            var fnErrBeforeOp = assertInstanceOf(FunctionDeclaration.class, errBeforeOpRes.ast().statements().getFirst());
            var varXErrBeforeOp = assertInstanceOf(VariableDeclaration.class, fnErrBeforeOp.body().statements().getFirst());
            var binErrBeforeOp = assertInstanceOf(BinaryExpression.class, varXErrBeforeOp.value());
            assertEquals("2", assertInstanceOf(LiteralExpression.class, binErrBeforeOp.right()).sourceText());
            assertEquals("after", assertInstanceOf(VariableDeclaration.class, errBeforeOpRes.ast().statements().get(1)).name());
            var errBeforeOpLex = errorsOf(errBeforeOpRes).stream()
                    .filter(d -> DiagnosticCodes.LEXICAL_ERROR.equals(d.code()))
                    .toList();
            assertEquals(1, errBeforeOpLex.size());
            assertSame(varXErrBeforeOp, requireNotNull(errBeforeOpLex.getFirst().recovery()).owner());
            assertEquals("variable.initializer", requireNotNull(errBeforeOpLex.getFirst().recovery()).facet());

            // Finding 1 negative control: dedented `§ \` before `§ 2` already escaped suite baseline.
            var dedentBeforeContSrc = ("func f():\n\tvar x = (1 +\n§ \\\n§ 2\nvar after = 3\n").replace("\n", newline);
            var dedentBeforeContRes = recoverableParse(dedentBeforeContSrc);
            assertEnvelopeRecursive(dedentBeforeContRes.ast(), dedentBeforeContSrc);
            assertEquals(2, dedentBeforeContRes.ast().statements().size(),
                    () -> dedentBeforeContRes.ast().statements().toString());
            var fnDedentCont = assertInstanceOf(FunctionDeclaration.class, dedentBeforeContRes.ast().statements().getFirst());
            var varXDedentCont = assertInstanceOf(VariableDeclaration.class, fnDedentCont.body().statements().getFirst());
            var binDedentCont = assertInstanceOf(BinaryExpression.class, varXDedentCont.value());
            assertEquals(CstIssueKind.MISSING, assertInstanceOf(ErrorExpression.class, binDedentCont.right()).kind());
            assertEquals("after",
                    assertInstanceOf(VariableDeclaration.class, dedentBeforeContRes.ast().statements().get(1)).name());
            var dedentContLex = errorsOf(dedentBeforeContRes).stream()
                    .filter(d -> DiagnosticCodes.LEXICAL_ERROR.equals(d.code()))
                    .toList();
            assertEquals(2, dedentContLex.size());
            assertNull(dedentContLex.get(0).recovery());
            assertNull(dedentContLex.get(1).recovery());

            // Finding 2: stray `\r` or malformed `\` before `func after(): pass` anchors stop before the line error.
            for (var template : List.of(
                    "var x = [1,\n\t§\n\rfunc after(): pass\n",
                    "var x = [1,\n\t§\n\\ func after(): pass\n",
                    "func f():\n\tvar x = [1,\n\t\t§\n\rfunc after(): pass\n",
                    "func f():\n\tvar x = [1,\n\t\t§\n\\ func after(): pass\n")) {
                var stopSrc = template.replace("\n", newline);
                var stopRes = recoverableParse(stopSrc);
                assertEnvelopeRecursive(stopRes.ast(), stopSrc);
                assertEquals(2, stopRes.ast().statements().size(), () -> stopSrc + " -> " + stopRes.ast().statements());
                var varXStop = stopRes.ast().statements().getFirst() instanceof FunctionDeclaration outerFn
                        ? assertInstanceOf(VariableDeclaration.class, outerFn.body().statements().getFirst())
                        : assertInstanceOf(VariableDeclaration.class, stopRes.ast().statements().getFirst());
                var arrStop = assertInstanceOf(ArrayExpression.class, varXStop.value());
                assertEquals(2, arrStop.elements().size());
                assertEquals("§", utf8Slice(stopSrc,
                        assertInstanceOf(ErrorExpression.class, arrStop.elements().get(1)).range()));
                assertEquals("after", assertInstanceOf(FunctionDeclaration.class, stopRes.ast().statements().get(1)).name());
                var stopLex = errorsOf(stopRes).stream()
                        .filter(d -> DiagnosticCodes.LEXICAL_ERROR.equals(d.code()))
                        .toList();
                assertEquals(2, stopLex.size());
                assertSame(varXStop, requireNotNull(stopLex.get(0).recovery()).owner());
                assertEquals("variable.initializer", requireNotNull(stopLex.get(0).recovery()).facet());
                assertNull(stopLex.get(1).recovery());
            }

            // Finding 3a: signal parameter lists attribute in-scope pending lexical errors at EOF (with/without trailing newline).
            for (var suffix : List.of("", "\n")) {
                for (var sigCase : List.of(
                        new Object[]{"signal changed(value,\n\t§" + suffix, 1},
                        new Object[]{"signal changed(\n\t§" + suffix, 0},
                        new Object[]{"signal changed(value\n\t§" + suffix, 1})) {
                    var sigSrc = ((String) sigCase[0]).replace("\n", newline);
                    var expectedParams = (int) sigCase[1];
                    var sigRes = recoverableParse(sigSrc);
                    assertEnvelopeRecursive(sigRes.ast(), sigSrc);
                    var sigStmt = assertInstanceOf(SignalStatement.class, sigRes.ast().statements().getFirst());
                    assertEquals("changed", sigStmt.name());
                    assertEquals(expectedParams, sigStmt.parameters().size());
                    var sigLex = errorsOf(sigRes).stream()
                            .filter(d -> DiagnosticCodes.LEXICAL_ERROR.equals(d.code()))
                            .toList();
                    assertEquals(1, sigLex.size());
                    assertSame(sigStmt, requireNotNull(sigLex.getFirst().recovery()).owner());
                    assertEquals("signal.parameter", requireNotNull(sigLex.getFirst().recovery()).facet());
                }

                var classSigSrc = ("class C:\n\tsignal changed(value,\n\t\t§\n§" + suffix).replace("\n", newline);
                var classSigRes = recoverableParse(classSigSrc);
                assertEnvelopeRecursive(classSigRes.ast(), classSigSrc);
                var clsSig = assertInstanceOf(ClassDeclaration.class, classSigRes.ast().statements().getFirst());
                var clsSigStmt = assertInstanceOf(SignalStatement.class, clsSig.body().statements().getFirst());
                assertEquals(1, clsSigStmt.parameters().size());
                var clsSigLex = errorsOf(classSigRes).stream()
                        .filter(d -> DiagnosticCodes.LEXICAL_ERROR.equals(d.code()))
                        .toList();
                assertEquals(2, clsSigLex.size());
                assertSame(clsSigStmt, requireNotNull(clsSigLex.get(0).recovery()).owner());
                assertEquals("signal.parameter", requireNotNull(clsSigLex.get(0).recovery()).facet());
                assertNull(clsSigLex.get(1).recovery());

                // Finding 3b: function signatures attribute in-scope pending lexical errors at EOF.
                for (var fnCase : List.of(
                        new Object[]{"func method(value,\n\t§" + suffix, 1},
                        new Object[]{"func method(\n\t§" + suffix, 0},
                        new Object[]{"func method(value\n\t§" + suffix, 1})) {
                    var fnSrc = ((String) fnCase[0]).replace("\n", newline);
                    var expectedParams = (int) fnCase[1];
                    var fnRes = recoverableParse(fnSrc);
                    assertEnvelopeRecursive(fnRes.ast(), fnSrc);
                    var fnDecl = assertInstanceOf(FunctionDeclaration.class, fnRes.ast().statements().getFirst());
                    assertEquals("method", fnDecl.name());
                    assertEquals(expectedParams, fnDecl.parameters().size());
                    var fnLex = errorsOf(fnRes).stream()
                            .filter(d -> DiagnosticCodes.LEXICAL_ERROR.equals(d.code()))
                            .toList();
                    assertEquals(1, fnLex.size());
                    assertSame(fnDecl, requireNotNull(fnLex.getFirst().recovery()).owner());
                    assertEquals("function.signature", requireNotNull(fnLex.getFirst().recovery()).facet());
                }

                var classFnSrc = ("class C:\n\tfunc method(value,\n\t\t§\n§" + suffix).replace("\n", newline);
                var classFnRes = recoverableParse(classFnSrc);
                assertEnvelopeRecursive(classFnRes.ast(), classFnSrc);
                var clsFn = assertInstanceOf(ClassDeclaration.class, classFnRes.ast().statements().getFirst());
                var clsFnDecl = assertInstanceOf(FunctionDeclaration.class, clsFn.body().statements().getFirst());
                assertEquals(1, clsFnDecl.parameters().size());
                var clsFnLex = errorsOf(classFnRes).stream()
                        .filter(d -> DiagnosticCodes.LEXICAL_ERROR.equals(d.code()))
                        .toList();
                assertEquals(2, clsFnLex.size());
                assertSame(clsFnDecl, requireNotNull(clsFnLex.get(0).recovery()).owner());
                assertEquals("function.signature", requireNotNull(clsFnLex.get(0).recovery()).facet());
                assertNull(clsFnLex.get(1).recovery());

                // Finding 3c: enum bodies attribute in-scope pending lexical errors at EOF.
                for (var enumCase : List.of(
                        new Object[]{"enum E { A,\n\t§" + suffix, 1},
                        new Object[]{"enum E {\n\t§" + suffix, 0},
                        new Object[]{"enum E { A\n\t§" + suffix, 1})) {
                    var enumSrc = ((String) enumCase[0]).replace("\n", newline);
                    var expectedMembers = (int) enumCase[1];
                    var enumRes = recoverableParse(enumSrc);
                    assertEnvelopeRecursive(enumRes.ast(), enumSrc);
                    var enumDecl = assertInstanceOf(EnumDeclaration.class, enumRes.ast().statements().getFirst());
                    assertEquals("E", enumDecl.name());
                    assertEquals(expectedMembers, enumDecl.members().size());
                    var enumLex = errorsOf(enumRes).stream()
                            .filter(d -> DiagnosticCodes.LEXICAL_ERROR.equals(d.code()))
                            .toList();
                    assertEquals(1, enumLex.size());
                    assertSame(enumDecl, requireNotNull(enumLex.getFirst().recovery()).owner());
                    assertEquals("enum.body", requireNotNull(enumLex.getFirst().recovery()).facet());
                }

                var classEnumSrc = ("class C:\n\tenum E {\n\t\tA,\n\t\t§\n§" + suffix).replace("\n", newline);
                var classEnumRes = recoverableParse(classEnumSrc);
                assertEnvelopeRecursive(classEnumRes.ast(), classEnumSrc);
                var clsEnum = assertInstanceOf(ClassDeclaration.class, classEnumRes.ast().statements().getFirst());
                var clsEnumDecl = assertInstanceOf(EnumDeclaration.class, clsEnum.body().statements().getFirst());
                assertEquals(1, clsEnumDecl.members().size());
                var clsEnumLex = errorsOf(classEnumRes).stream()
                        .filter(d -> DiagnosticCodes.LEXICAL_ERROR.equals(d.code()))
                        .toList();
                assertEquals(2, clsEnumLex.size());
                assertSame(clsEnumDecl, requireNotNull(clsEnumLex.get(0).recovery()).owner());
                assertEquals("enum.body", requireNotNull(clsEnumLex.get(0).recovery()).facet());
                assertNull(clsEnumLex.get(1).recovery());

                // Finding 3d: lambda parameter list attributes in-scope pending lexical errors at EOF.
                for (var lambdaCase : List.of(
                        new Object[]{"var f = func(value,\n\t§" + suffix, 1},
                        new Object[]{"var f = func(\n\t§" + suffix, 0})) {
                    var lamSrc = ((String) lambdaCase[0]).replace("\n", newline);
                    var expectedParams = (int) lambdaCase[1];
                    var lamRes = recoverableParse(lamSrc);
                    assertEnvelopeRecursive(lamRes.ast(), lamSrc);
                    var varLam = assertInstanceOf(VariableDeclaration.class, lamRes.ast().statements().getFirst());
                    var lamExpr = assertInstanceOf(LambdaExpression.class, varLam.value());
                    assertEquals(expectedParams, lamExpr.parameters().size());
                    var lamLex = errorsOf(lamRes).stream()
                            .filter(d -> DiagnosticCodes.LEXICAL_ERROR.equals(d.code()))
                            .toList();
                    assertEquals(1, lamLex.size());
                    assertSame(lamExpr, requireNotNull(lamLex.getFirst().recovery()).owner());
                    assertEquals("function.signature", requireNotNull(lamLex.getFirst().recovery()).facet());
                }
            }
        }
    }

    @Test
    @Timeout(5)
    void errorPrefixedLineStartCheckIsLinearOverLongLexicalErrorRuns() {
        var n = 2000;
        var source = "func f():\n\tvar x = [1,\n\t\t§\n" + "§".repeat(n) + " func after(): pass\n";
        var parser = new GdScriptParser();
        var result = parser.parse(source);
        assertEnvelopeRecursive(result.ast(), source);
        assertEquals(2, result.ast().statements().size(), () -> result.ast().statements().toString());
        var outerFn = assertInstanceOf(FunctionDeclaration.class, result.ast().statements().getFirst());
        var varX = assertInstanceOf(VariableDeclaration.class, outerFn.body().statements().getFirst());
        var arr = assertInstanceOf(ArrayExpression.class, varX.value());
        assertEquals(2, arr.elements().size());
        assertEquals("§", utf8Slice(source,
                assertInstanceOf(ErrorExpression.class, arr.elements().get(1)).range()));
        assertEquals("after", assertInstanceOf(FunctionDeclaration.class, result.ast().statements().get(1)).name());
        var lexErrors = result.diagnostics().stream()
                .filter(d -> DiagnosticCodes.LEXICAL_ERROR.equals(d.code()))
                .toList();
        assertEquals(n + 1, lexErrors.size());
        assertSame(varX, requireNotNull(lexErrors.getFirst().recovery()).owner());
        for (var i = 1; i < lexErrors.size(); i++) {
            assertNull(lexErrors.get(i).recovery());
        }
        assertTrue(parser.stagedErrorPrefixCoverageSteps() <= 4 * n + 64,
                () -> "Expected linear prefix check steps, got " + parser.stagedErrorPrefixCoverageSteps());
    }

    @Test
    void malformedContinuationEndsOracleContinuationAndStrayCrLeadingIndentMatchesTokenizerAcrossLfAndCrlf() {
        for (var newline : List.of("\n", "\r\n")) {
            // Finding 1: malformed continuation lines end Oracle continuation so trailing declaration survives.
            for (var template : List.of(
                    "var x = [1, \\\n\\ \nfunc after(): pass\n",
                    "var x = [1, \\\n\r # bad\nfunc after(): pass\n")) {
                var badContSrc = template.replace("\n", newline);
                var badContRes = recoverableParse(badContSrc);
                assertEnvelopeRecursive(badContRes.ast(), badContSrc);
                var varXBad = assertInstanceOf(VariableDeclaration.class, badContRes.ast().statements().getFirst());
                var arrBad = assertInstanceOf(ArrayExpression.class, varXBad.value());
                assertTrue(arrBad.elements().size() >= 1 && arrBad.elements().size() <= 2);
                assertEquals("after",
                        assertInstanceOf(FunctionDeclaration.class, badContRes.ast().statements().getLast()).name());
                var badContLex = errorsOf(badContRes).stream()
                        .filter(d -> DiagnosticCodes.LEXICAL_ERROR.equals(d.code()))
                        .toList();
                assertEquals(1, badContLex.size());
                assertSame(varXBad, requireNotNull(badContLex.getFirst().recovery()).owner());
            }

            // Finding 1 controls: blank / comment-only continuation lines keep valid continuation.
            for (var validTemplate : List.of(
                    "var x = [1, \\\n   # comment\n2]\n",
                    "var x = [1, \\\n\n2]\n")) {
                var validSrc = validTemplate.replace("\n", newline);
                var validRes = parse(validSrc);
                assertEquals(0, errorsOf(validRes).size(), () -> validSrc + " -> " + errorsOf(validRes));
                var arrValid = assertInstanceOf(ArrayExpression.class,
                        assertInstanceOf(VariableDeclaration.class, validRes.ast().statements().getFirst()).value());
                assertEquals(2, arrValid.elements().size());
                assertEquals("2", assertInstanceOf(LiteralExpression.class, arrValid.elements().get(1)).sourceText());
            }

            // Finding 2: stray CR followed by tabs counts leading indentation for signal, func, and enum owners.
            for (var suffix : List.of("", "\n")) {
                var sigCrSrc = ("class C:\n\tsignal changed(value,\n\r\t\t§" + suffix).replace("\n", newline);
                var sigCrRes = recoverableParse(sigCrSrc);
                assertEnvelopeRecursive(sigCrRes.ast(), sigCrSrc);
                var clsSigCr = assertInstanceOf(ClassDeclaration.class, sigCrRes.ast().statements().getFirst());
                var sigCrStmt = assertInstanceOf(SignalStatement.class, clsSigCr.body().statements().getFirst());
                assertEquals(1, sigCrStmt.parameters().size());
                var sigCrLex = errorsOf(sigCrRes).stream()
                        .filter(d -> DiagnosticCodes.LEXICAL_ERROR.equals(d.code()))
                        .toList();
                assertEquals(2, sigCrLex.size());
                for (var lex : sigCrLex) {
                    assertSame(sigCrStmt, requireNotNull(lex.recovery()).owner());
                    assertEquals("signal.parameter", requireNotNull(lex.recovery()).facet());
                }

                var fnCrSrc = ("class C:\n\tfunc method(value,\n\r\t\t§" + suffix).replace("\n", newline);
                var fnCrRes = recoverableParse(fnCrSrc);
                assertEnvelopeRecursive(fnCrRes.ast(), fnCrSrc);
                var clsFnCr = assertInstanceOf(ClassDeclaration.class, fnCrRes.ast().statements().getFirst());
                var fnCrDecl = assertInstanceOf(FunctionDeclaration.class, clsFnCr.body().statements().getFirst());
                assertEquals(1, fnCrDecl.parameters().size());
                var fnCrLex = errorsOf(fnCrRes).stream()
                        .filter(d -> DiagnosticCodes.LEXICAL_ERROR.equals(d.code()))
                        .toList();
                assertEquals(2, fnCrLex.size());
                for (var lex : fnCrLex) {
                    assertSame(fnCrDecl, requireNotNull(lex.recovery()).owner());
                    assertEquals("function.signature", requireNotNull(lex.recovery()).facet());
                }

                var enumCrSrc = ("class C:\n\tenum E {\n\t\tA,\n\r\t\t§" + suffix).replace("\n", newline);
                var enumCrRes = recoverableParse(enumCrSrc);
                assertEnvelopeRecursive(enumCrRes.ast(), enumCrSrc);
                var clsEnumCr = assertInstanceOf(ClassDeclaration.class, enumCrRes.ast().statements().getFirst());
                var enumCrDecl = assertInstanceOf(EnumDeclaration.class, clsEnumCr.body().statements().getFirst());
                assertEquals(1, enumCrDecl.members().size());
                var enumCrLex = errorsOf(enumCrRes).stream()
                        .filter(d -> DiagnosticCodes.LEXICAL_ERROR.equals(d.code()))
                        .toList();
                assertEquals(2, enumCrLex.size());
                for (var lex : enumCrLex) {
                    assertSame(enumCrDecl, requireNotNull(lex.recovery()).owner());
                    assertEquals("enum.body", requireNotNull(lex.recovery()).facet());
                }

                // Finding 2 negative control: dedented `\r§` at column 1 stays standalone (`recovery == null`).
                var dedentCrSrc = ("class C:\n\tsignal changed(value,\n\r§" + suffix).replace("\n", newline);
                var dedentCrRes = recoverableParse(dedentCrSrc);
                assertEnvelopeRecursive(dedentCrRes.ast(), dedentCrSrc);
                var dedentCrLex = errorsOf(dedentCrRes).stream()
                        .filter(d -> DiagnosticCodes.LEXICAL_ERROR.equals(d.code()))
                        .toList();
                assertEquals(2, dedentCrLex.size());
                assertNull(dedentCrLex.get(0).recovery());
                assertNull(dedentCrLex.get(1).recovery());
            }
        }
    }

    @Test
    void multiErrorDeclarationLinePrefixAndAdvancePostNewlineMultilineFilterAcrossLfAndCrlf() {
        for (var newline : List.of("\n", "\r\n")) {
            // Finding 1: preserve the earliest line-head anchor across multi-error declaration prefixes.
            for (var prefixTemplate : List.of(
                    "var x = [1,\n\r§func after(): pass\n",
                    "var x = [1,\n§§func after(): pass\n")) {
                var src = prefixTemplate.replace("\n", newline);
                var res = recoverableParse(src);
                assertEnvelopeRecursive(res.ast(), src);
                assertEquals(2, res.ast().statements().size(), () -> src + " -> " + res.ast().statements());
                var varX = assertInstanceOf(VariableDeclaration.class, res.ast().statements().getFirst());
                var arr = assertInstanceOf(ArrayExpression.class, varX.value());
                assertEquals(1, arr.elements().size());
                var afterFn = assertInstanceOf(FunctionDeclaration.class, res.ast().statements().get(1));
                assertEquals("after", afterFn.name());
                var declLineStartByte = src.indexOf(newline) + newline.length();
                assertTrue(arr.range().endByte() <= declLineStartByte,
                        () -> "Array range " + arr.range() + " must not extend onto declaration line at "
                                + declLineStartByte);
                assertEquals(0, arr.range().endPoint().row());
                var lexErrors = errorsOf(res).stream()
                        .filter(d -> DiagnosticCodes.LEXICAL_ERROR.equals(d.code()))
                        .toList();
                assertEquals(2, lexErrors.size());
                assertNull(lexErrors.get(0).recovery());
                assertNull(lexErrors.get(1).recovery());
            }

            // Finding 2a: unclosed array with dedented error before resumed element keeps dedented § standalone.
            var unclosedDedentSrc = ("func f():\n\tvar x = [1,\n§\n\t\t2\n\tpass\n").replace("\n", newline);
            var unclosedDedentRes = recoverableParse(unclosedDedentSrc);
            assertEnvelopeRecursive(unclosedDedentRes.ast(), unclosedDedentSrc);
            var fnUnclosed = onlyFunction(unclosedDedentRes);
            assertEquals(2, fnUnclosed.body().statements().size());
            var varXUnclosed = assertInstanceOf(VariableDeclaration.class, fnUnclosed.body().statements().getFirst());
            var arrUnclosed = assertInstanceOf(ArrayExpression.class, varXUnclosed.value());
            assertEquals(2, arrUnclosed.elements().size());
            assertEquals("1", assertInstanceOf(LiteralExpression.class, arrUnclosed.elements().get(0)).sourceText());
            assertEquals("2", assertInstanceOf(LiteralExpression.class, arrUnclosed.elements().get(1)).sourceText());
            assertInstanceOf(PassStatement.class, fnUnclosed.body().statements().get(1));
            var missingUnclosed = findErrorByCode(unclosedDedentRes, DiagnosticCodes.MISSING_CLOSER_ARRAY);
            assertSame(varXUnclosed, requireNotNull(missingUnclosed.recovery()).owner());
            var lexUnclosed = errorsOf(unclosedDedentRes).stream()
                    .filter(d -> DiagnosticCodes.LEXICAL_ERROR.equals(d.code()))
                    .toList();
            assertEquals(1, lexUnclosed.size());
            assertNull(lexUnclosed.getFirst().recovery());

            // Finding 2b: matched array control claims § to variable.initializer with no MISSING_CLOSER_ARRAY.
            var matchedSrc = ("func f():\n\tvar x = [1,\n§\n\t\t2]\n\tpass\n").replace("\n", newline);
            var matchedRes = recoverableParse(matchedSrc);
            assertEnvelopeRecursive(matchedRes.ast(), matchedSrc);
            var fnMatched = onlyFunction(matchedRes);
            assertEquals(2, fnMatched.body().statements().size());
            var varXMatched = assertInstanceOf(VariableDeclaration.class, fnMatched.body().statements().getFirst());
            var arrMatched = assertInstanceOf(ArrayExpression.class, varXMatched.value());
            assertEquals(2, arrMatched.elements().size());
            assertEquals("1", assertInstanceOf(LiteralExpression.class, arrMatched.elements().get(0)).sourceText());
            assertEquals("2", assertInstanceOf(LiteralExpression.class, arrMatched.elements().get(1)).sourceText());
            assertInstanceOf(PassStatement.class, fnMatched.body().statements().get(1));
            assertTrue(errorsOf(matchedRes).stream()
                    .noneMatch(d -> DiagnosticCodes.MISSING_CLOSER_ARRAY.equals(d.code())));
            var lexMatched = errorsOf(matchedRes).stream()
                    .filter(d -> DiagnosticCodes.LEXICAL_ERROR.equals(d.code()))
                    .toList();
            assertEquals(1, lexMatched.size());
            assertSame(varXMatched, requireNotNull(lexMatched.getFirst().recovery()).owner());
            assertEquals("variable.initializer", requireNotNull(lexMatched.getFirst().recovery()).facet());

            // Finding 2c: unclosed array with indented + dedented errors before resumed element keeps prefix only.
            var mixedSrc = ("func f():\n\tvar x = [1,\n\t\t§\n§\n\t\t2\n\tpass\n").replace("\n", newline);
            var mixedRes = recoverableParse(mixedSrc);
            assertEnvelopeRecursive(mixedRes.ast(), mixedSrc);
            var fnMixed = onlyFunction(mixedRes);
            assertEquals(2, fnMixed.body().statements().size());
            var varXMixed = assertInstanceOf(VariableDeclaration.class, fnMixed.body().statements().getFirst());
            var arrMixed = assertInstanceOf(ArrayExpression.class, varXMixed.value());
            assertEquals(2, arrMixed.elements().size());
            assertEquals("1", assertInstanceOf(LiteralExpression.class, arrMixed.elements().get(0)).sourceText());
            assertEquals("2", assertInstanceOf(LiteralExpression.class, arrMixed.elements().get(1)).sourceText());
            assertInstanceOf(PassStatement.class, fnMixed.body().statements().get(1));
            var missingMixed = findErrorByCode(mixedRes, DiagnosticCodes.MISSING_CLOSER_ARRAY);
            assertSame(varXMixed, requireNotNull(missingMixed.recovery()).owner());
            var lexMixed = errorsOf(mixedRes).stream()
                    .filter(d -> DiagnosticCodes.LEXICAL_ERROR.equals(d.code()))
                    .toList();
            assertEquals(2, lexMixed.size());
            assertSame(varXMixed, requireNotNull(lexMixed.get(0).recovery()).owner());
            assertEquals("variable.initializer", requireNotNull(lexMixed.get(0).recovery()).facet());
            assertNull(lexMixed.get(1).recovery());
        }
    }

    @Test
    void consumeCloserSettlesMatchedFrameErrorsAndAdvanceFiltersPerLogicalLineAcrossLfAndCrlf() {
        for (var newline : List.of("\n", "\r\n")) {
            // Finding 1a: inner matched `)` settles § against the grouping frame before popping to missing outer `[`.
            var matchedInnerSrc = ("func f():\n\tvar x = [(1\n§\n\t\t)\n\tpass\n").replace("\n", newline);
            var matchedInnerRes = recoverableParse(matchedInnerSrc);
            assertEnvelopeRecursive(matchedInnerRes.ast(), matchedInnerSrc);
            var fnMatchedInner = onlyFunction(matchedInnerRes);
            assertEquals(2, fnMatchedInner.body().statements().size());
            var varXMatchedInner = assertInstanceOf(VariableDeclaration.class,
                    fnMatchedInner.body().statements().getFirst());
            var arrMatchedInner = assertInstanceOf(ArrayExpression.class, varXMatchedInner.value());
            assertEquals(1, arrMatchedInner.elements().size());
            assertInstanceOf(PassStatement.class, fnMatchedInner.body().statements().get(1));
            var missingOuterArr = findErrorByCode(matchedInnerRes, DiagnosticCodes.MISSING_CLOSER_ARRAY);
            assertSame(varXMatchedInner, requireNotNull(missingOuterArr.recovery()).owner());
            var lexMatchedInner = errorsOf(matchedInnerRes).stream()
                    .filter(d -> DiagnosticCodes.LEXICAL_ERROR.equals(d.code()))
                    .toList();
            assertEquals(1, lexMatchedInner.size());
            assertSame(varXMatchedInner, requireNotNull(lexMatchedInner.getFirst().recovery()).owner());
            assertEquals("variable.initializer", requireNotNull(lexMatchedInner.getFirst().recovery()).facet());

            // Finding 1b: control with outer `]` still links § to variable.initializer without MISSING_CLOSER_ARRAY.
            var matchedOuterSrc = ("func f():\n\tvar x = [(1\n§\n\t\t)]\n\tpass\n").replace("\n", newline);
            var matchedOuterRes = recoverableParse(matchedOuterSrc);
            assertEnvelopeRecursive(matchedOuterRes.ast(), matchedOuterSrc);
            var fnMatchedOuter = onlyFunction(matchedOuterRes);
            assertEquals(2, fnMatchedOuter.body().statements().size());
            var varXMatchedOuter = assertInstanceOf(VariableDeclaration.class,
                    fnMatchedOuter.body().statements().getFirst());
            assertInstanceOf(PassStatement.class, fnMatchedOuter.body().statements().get(1));
            assertTrue(errorsOf(matchedOuterRes).stream()
                    .noneMatch(d -> DiagnosticCodes.MISSING_CLOSER_ARRAY.equals(d.code())));
            var lexMatchedOuter = errorsOf(matchedOuterRes).stream()
                    .filter(d -> DiagnosticCodes.LEXICAL_ERROR.equals(d.code()))
                    .toList();
            assertEquals(1, lexMatchedOuter.size());
            assertSame(varXMatchedOuter, requireNotNull(lexMatchedOuter.getFirst().recovery()).owner());
            assertEquals("variable.initializer", requireNotNull(lexMatchedOuter.getFirst().recovery()).facet());

            // Finding 1c: mismatched inner `}` settles § against grouping's own oracle fact before popping.
            var mismatchedInnerSrc = ("func f():\n\tvar x = [(1\n§\n\t\t}\n\tpass\n").replace("\n", newline);
            var mismatchedInnerRes = recoverableParse(mismatchedInnerSrc);
            assertEnvelopeRecursive(mismatchedInnerRes.ast(), mismatchedInnerSrc);
            var fnMismatched = onlyFunction(mismatchedInnerRes);
            assertEquals(2, fnMismatched.body().statements().size());
            var varXMismatched = assertInstanceOf(VariableDeclaration.class,
                    fnMismatched.body().statements().getFirst());
            assertInstanceOf(PassStatement.class, fnMismatched.body().statements().get(1));
            var lexMismatched = errorsOf(mismatchedInnerRes).stream()
                    .filter(d -> DiagnosticCodes.LEXICAL_ERROR.equals(d.code()))
                    .toList();
            assertEquals(1, lexMismatched.size());
            assertSame(varXMismatched, requireNotNull(lexMismatched.getFirst().recovery()).owner());
            assertEquals("variable.initializer", requireNotNull(lexMismatched.getFirst().recovery()).facet());

            // Finding 2a: dedented line-3 § stays standalone while line-4 § on resumed element line links to x.
            for (var template : List.of(
                    "func f():\n\tvar x = [1,\n§\n\t\t§ 2\n\tpass\n",
                    "func f():\n\tvar x = [1,\n§\n\t\t§\n\t\t2\n\tpass\n")) {
                var splitSrc = template.replace("\n", newline);
                var splitRes = recoverableParse(splitSrc);
                assertEnvelopeRecursive(splitRes.ast(), splitSrc);
                var fnSplit = onlyFunction(splitRes);
                assertEquals(2, fnSplit.body().statements().size());
                var varXSplit = assertInstanceOf(VariableDeclaration.class, fnSplit.body().statements().getFirst());
                var arrSplit = assertInstanceOf(ArrayExpression.class, varXSplit.value());
                assertEquals(2, arrSplit.elements().size());
                assertEquals("1", assertInstanceOf(LiteralExpression.class, arrSplit.elements().get(0)).sourceText());
                assertEquals("2", assertInstanceOf(LiteralExpression.class, arrSplit.elements().get(1)).sourceText());
                assertInstanceOf(PassStatement.class, fnSplit.body().statements().get(1));
                assertSame(varXSplit, requireNotNull(
                        findErrorByCode(splitRes, DiagnosticCodes.MISSING_CLOSER_ARRAY).recovery()).owner());
                var lexSplit = errorsOf(splitRes).stream()
                        .filter(d -> DiagnosticCodes.LEXICAL_ERROR.equals(d.code()))
                        .toList();
                assertEquals(2, lexSplit.size());
                assertNull(lexSplit.get(0).recovery());
                assertSame(varXSplit, requireNotNull(lexSplit.get(1).recovery()).owner());
                assertEquals("variable.initializer", requireNotNull(lexSplit.get(1).recovery()).facet());
            }

            // Finding 2b: continued dedented line-4 § inherits in-scope line 3, hard-broken line-5 § is standalone.
            var contBreakSrc = ("func f():\n\tvar x = [1,\n\t\t§ \\\n§\n§\n\t\t2\n\tpass\n").replace("\n", newline);
            var contBreakRes = recoverableParse(contBreakSrc);
            assertEnvelopeRecursive(contBreakRes.ast(), contBreakSrc);
            var fnContBreak = onlyFunction(contBreakRes);
            assertEquals(2, fnContBreak.body().statements().size());
            var varXContBreak = assertInstanceOf(VariableDeclaration.class,
                    fnContBreak.body().statements().getFirst());
            var arrContBreak = assertInstanceOf(ArrayExpression.class, varXContBreak.value());
            assertEquals(2, arrContBreak.elements().size());
            assertEquals("1", assertInstanceOf(LiteralExpression.class, arrContBreak.elements().get(0)).sourceText());
            assertEquals("2", assertInstanceOf(LiteralExpression.class, arrContBreak.elements().get(1)).sourceText());
            assertInstanceOf(PassStatement.class, fnContBreak.body().statements().get(1));
            assertSame(varXContBreak, requireNotNull(
                    findErrorByCode(contBreakRes, DiagnosticCodes.MISSING_CLOSER_ARRAY).recovery()).owner());
            var lexContBreak = errorsOf(contBreakRes).stream()
                    .filter(d -> DiagnosticCodes.LEXICAL_ERROR.equals(d.code()))
                    .toList();
            assertEquals(3, lexContBreak.size());
            assertSame(varXContBreak, requireNotNull(lexContBreak.get(0).recovery()).owner());
            assertEquals("variable.initializer", requireNotNull(lexContBreak.get(0).recovery()).facet());
            assertSame(varXContBreak, requireNotNull(lexContBreak.get(1).recovery()).owner());
            assertEquals("variable.initializer", requireNotNull(lexContBreak.get(1).recovery()).facet());
            assertNull(lexContBreak.get(2).recovery());
        }
    }

    @Test
    void logicalLineStartRowDrivesMultilineLexicalOwnershipAcrossBareBackslashesAndCommentLines() {
        for (var newline : List.of("\n", "\r\n")) {
            // Bare indented backslash starts a logical line whose continued error belongs to x.
            var dedentThenIndentedContSrc = ("func f():\n\tvar x = [1,\n§\n\t\t\\\n§\n\t\t2\n\tpass\n")
                    .replace("\n", newline);
            var dedentThenIndentedContRes = recoverableParse(dedentThenIndentedContSrc);
            assertEnvelopeRecursive(dedentThenIndentedContRes.ast(), dedentThenIndentedContSrc);
            var fn1 = onlyFunction(dedentThenIndentedContRes);
            assertEquals(2, fn1.body().statements().size());
            var varX1 = assertInstanceOf(VariableDeclaration.class, fn1.body().statements().getFirst());
            var arr1 = assertInstanceOf(ArrayExpression.class, varX1.value());
            assertEquals(2, arr1.elements().size());
            assertEquals("1", assertInstanceOf(LiteralExpression.class, arr1.elements().get(0)).sourceText());
            assertEquals("2", assertInstanceOf(LiteralExpression.class, arr1.elements().get(1)).sourceText());
            assertInstanceOf(PassStatement.class, fn1.body().statements().get(1));
            assertSame(varX1, requireNotNull(
                    findErrorByCode(dedentThenIndentedContRes, DiagnosticCodes.MISSING_CLOSER_ARRAY).recovery()).owner());
            var lex1 = errorsOf(dedentThenIndentedContRes).stream()
                    .filter(d -> DiagnosticCodes.LEXICAL_ERROR.equals(d.code()))
                    .toList();
            assertEquals(2, lex1.size());
            assertNull(lex1.get(0).recovery());
            assertSame(varX1, requireNotNull(lex1.get(1).recovery()).owner());
            assertEquals("variable.initializer", requireNotNull(lex1.get(1).recovery()).facet());

            // Bare dedented backslash starts a dedented logical line whose continued error stays standalone.
            var indentedThenDedentContSrc = ("func f():\n\tvar x = [1,\n\t\t§\n\\\n§\n\t\t2\n\tpass\n")
                    .replace("\n", newline);
            var indentedThenDedentContRes = recoverableParse(indentedThenDedentContSrc);
            assertEnvelopeRecursive(indentedThenDedentContRes.ast(), indentedThenDedentContSrc);
            var fn2 = onlyFunction(indentedThenDedentContRes);
            assertEquals(2, fn2.body().statements().size());
            var varX2 = assertInstanceOf(VariableDeclaration.class, fn2.body().statements().getFirst());
            var arr2 = assertInstanceOf(ArrayExpression.class, varX2.value());
            assertEquals(2, arr2.elements().size());
            assertEquals("1", assertInstanceOf(LiteralExpression.class, arr2.elements().get(0)).sourceText());
            assertEquals("2", assertInstanceOf(LiteralExpression.class, arr2.elements().get(1)).sourceText());
            assertInstanceOf(PassStatement.class, fn2.body().statements().get(1));
            assertSame(varX2, requireNotNull(
                    findErrorByCode(indentedThenDedentContRes, DiagnosticCodes.MISSING_CLOSER_ARRAY).recovery()).owner());
            var lex2 = errorsOf(indentedThenDedentContRes).stream()
                    .filter(d -> DiagnosticCodes.LEXICAL_ERROR.equals(d.code()))
                    .toList();
            assertEquals(2, lex2.size());
            assertSame(varX2, requireNotNull(lex2.get(0).recovery()).owner());
            assertEquals("variable.initializer", requireNotNull(lex2.get(0).recovery()).facet());
            assertNull(lex2.get(1).recovery());

            // Exit-path counterparts: dedented backslash stops ownership before `var after = 3`.
            for (var exitTemplate : List.of(
                    "func f():\n\tvar x = [1,\n\t\t§\n\\\n§\nvar after = 3\n",
                    "func f():\n\tvar x = [1,\n\t\t\\\n§\n§\nvar after = 3\n")) {
                var exitSrc = exitTemplate.replace("\n", newline);
                var exitRes = recoverableParse(exitSrc);
                assertEnvelopeRecursive(exitRes.ast(), exitSrc);
                assertEquals(2, exitRes.ast().statements().size());
                var fnExit = assertInstanceOf(FunctionDeclaration.class, exitRes.ast().statements().getFirst());
                var varXExit = assertInstanceOf(VariableDeclaration.class, fnExit.body().statements().getFirst());
                var arrExit = assertInstanceOf(ArrayExpression.class, varXExit.value());
                assertEquals(2, arrExit.elements().size());
                assertEquals("§", utf8Slice(exitSrc,
                        assertInstanceOf(ErrorExpression.class, arrExit.elements().get(1)).range()));
                assertEquals("after",
                        assertInstanceOf(VariableDeclaration.class, exitRes.ast().statements().get(1)).name());
                var lexExit = errorsOf(exitRes).stream()
                        .filter(d -> DiagnosticCodes.LEXICAL_ERROR.equals(d.code()))
                        .toList();
                assertEquals(2, lexExit.size());
                assertSame(varXExit, requireNotNull(lexExit.get(0).recovery()).owner());
                assertEquals("variable.initializer", requireNotNull(lexExit.get(0).recovery()).facet());
                assertNull(lexExit.get(1).recovery());
            }

            // Multi-error continuation across lines followed by a dedented standalone line.
            var multiContExitSrc = ("func f():\n\tvar x = [1,\n\t\t§ \\\n§ §\n§\nvar after = 3\n")
                    .replace("\n", newline);
            var multiContExitRes = recoverableParse(multiContExitSrc);
            assertEnvelopeRecursive(multiContExitRes.ast(), multiContExitSrc);
            assertEquals(2, multiContExitRes.ast().statements().size());
            var varXMulti = assertInstanceOf(VariableDeclaration.class,
                    assertInstanceOf(FunctionDeclaration.class, multiContExitRes.ast().statements().getFirst())
                            .body().statements().getFirst());
            assertEquals("after",
                    assertInstanceOf(VariableDeclaration.class, multiContExitRes.ast().statements().get(1)).name());
            var lexMulti = errorsOf(multiContExitRes).stream()
                    .filter(d -> DiagnosticCodes.LEXICAL_ERROR.equals(d.code()))
                    .toList();
            assertEquals(4, lexMulti.size());
            for (var i = 0; i < 3; i++) {
                assertSame(varXMulti, requireNotNull(lexMulti.get(i).recovery()).owner());
                assertEquals("variable.initializer", requireNotNull(lexMulti.get(i).recovery()).facet());
            }
            assertNull(lexMulti.get(3).recovery());

            // Stray CR inside vs outside grouping keeps inner CR in-scope and dedented CR standalone.
            var strayCrExitSrc = ("func f():\n\tvar x = (1 +\n\t\t\r # in\n\r # out\nvar after = 3\n")
                    .replace("\n", newline);
            var strayCrExitRes = recoverableParse(strayCrExitSrc);
            assertEnvelopeRecursive(strayCrExitRes.ast(), strayCrExitSrc);
            assertEquals(2, strayCrExitRes.ast().statements().size());
            var varXStray = assertInstanceOf(VariableDeclaration.class,
                    assertInstanceOf(FunctionDeclaration.class, strayCrExitRes.ast().statements().getFirst())
                            .body().statements().getFirst());
            assertEquals("after",
                    assertInstanceOf(VariableDeclaration.class, strayCrExitRes.ast().statements().get(1)).name());
            var lexStray = errorsOf(strayCrExitRes).stream()
                    .filter(d -> DiagnosticCodes.LEXICAL_ERROR.equals(d.code()))
                    .sorted(java.util.Comparator.comparingInt(d -> d.range().startByte()))
                    .toList();
            assertEquals(2, lexStray.size());
            assertEquals(2, lexStray.get(0).range().startPoint().row());
            assertSame(varXStray, requireNotNull(lexStray.get(0).recovery()).owner());
            assertEquals("variable.initializer", requireNotNull(lexStray.get(0).recovery()).facet());
            assertEquals(3, lexStray.get(1).range().startPoint().row());
            assertNull(lexStray.get(1).recovery());

            // Comment-line-after-backslash controls preserve logical line start indentation.
            var commentAfterIndentedSlashSrc = ("func f():\n\tvar x = [1,\n\t\t\\\n\t# comment\n§\n\t\t2\n\tpass\n")
                    .replace("\n", newline);
            var commentAfterIndentedSlashRes = recoverableParse(commentAfterIndentedSlashSrc);
            assertEnvelopeRecursive(commentAfterIndentedSlashRes.ast(), commentAfterIndentedSlashSrc);
            var fnCommentIn = onlyFunction(commentAfterIndentedSlashRes);
            assertEquals(2, fnCommentIn.body().statements().size());
            var varXCommentIn = assertInstanceOf(VariableDeclaration.class,
                    fnCommentIn.body().statements().getFirst());
            assertEquals(2, assertInstanceOf(ArrayExpression.class, varXCommentIn.value()).elements().size());
            assertInstanceOf(PassStatement.class, fnCommentIn.body().statements().get(1));
            var lexCommentIn = errorsOf(commentAfterIndentedSlashRes).stream()
                    .filter(d -> DiagnosticCodes.LEXICAL_ERROR.equals(d.code()))
                    .toList();
            assertEquals(1, lexCommentIn.size());
            assertSame(varXCommentIn, requireNotNull(lexCommentIn.getFirst().recovery()).owner());
            assertEquals("variable.initializer", requireNotNull(lexCommentIn.getFirst().recovery()).facet());

            var commentAfterDedentSlashSrc = ("func f():\n\tvar x = [1,\n\\\n\t\t# comment\n§\n\t\t2\n\tpass\n")
                    .replace("\n", newline);
            var commentAfterDedentSlashRes = recoverableParse(commentAfterDedentSlashSrc);
            assertEnvelopeRecursive(commentAfterDedentSlashRes.ast(), commentAfterDedentSlashSrc);
            var fnCommentOut = onlyFunction(commentAfterDedentSlashRes);
            assertEquals(2, fnCommentOut.body().statements().size());
            var varXCommentOut = assertInstanceOf(VariableDeclaration.class,
                    fnCommentOut.body().statements().getFirst());
            assertEquals(2, assertInstanceOf(ArrayExpression.class, varXCommentOut.value()).elements().size());
            assertInstanceOf(PassStatement.class, fnCommentOut.body().statements().get(1));
            var lexCommentOut = errorsOf(commentAfterDedentSlashRes).stream()
                    .filter(d -> DiagnosticCodes.LEXICAL_ERROR.equals(d.code()))
                    .toList();
            assertEquals(1, lexCommentOut.size());
            assertNull(lexCommentOut.getFirst().recovery());
        }
    }

    @Test
    void operandLogicalStartLineAndConsumeCloserInScopeClaimAcrossLfAndCrlf() {
        for (var newline : List.of("\n", "\r\n")) {
            // Finding 1a: earlier standalone `§` does not veto indented `\t\t\\\n2` operand on a later logical line.
            var allowedContSrc = ("func f():\n\tvar x = (1 +\n§\n\t\t\\\n2\nvar after = 3\n").replace("\n", newline);
            var allowedContRes = recoverableParse(allowedContSrc);
            assertEnvelopeRecursive(allowedContRes.ast(), allowedContSrc);
            assertEquals(2, allowedContRes.ast().statements().size());
            var fnAllowed = assertInstanceOf(FunctionDeclaration.class, allowedContRes.ast().statements().getFirst());
            var varXAllowed = assertInstanceOf(VariableDeclaration.class, fnAllowed.body().statements().getFirst());
            var binAllowed = assertInstanceOf(BinaryExpression.class, varXAllowed.value());
            assertEquals("1", assertInstanceOf(LiteralExpression.class, binAllowed.left()).sourceText());
            assertEquals("2", assertInstanceOf(LiteralExpression.class, binAllowed.right()).sourceText());
            assertEquals("after",
                    assertInstanceOf(VariableDeclaration.class, allowedContRes.ast().statements().get(1)).name());
            assertSame(varXAllowed, requireNotNull(
                    findErrorByCode(allowedContRes, DiagnosticCodes.MISSING_CLOSER_GROUPING).recovery()).owner());
            assertTrue(errorsOf(allowedContRes).stream()
                    .noneMatch(d -> DiagnosticCodes.SYNTAX_UNEXPECTED_TOKEN.equals(d.code())));
            var lexAllowed = errorsOf(allowedContRes).stream()
                    .filter(d -> DiagnosticCodes.LEXICAL_ERROR.equals(d.code()))
                    .toList();
            assertEquals(1, lexAllowed.size());
            assertNull(lexAllowed.getFirst().recovery());

            // Finding 1b: dedented continuation `§ \\\n2` and `§ \\\n§ 2` must NOT swallow `2` into `x`.
            for (var dedentContTemplate : List.of(
                    "func f():\n\tvar x = (1 +\n§ \\\n2\nvar after = 3\n",
                    "func f():\n\tvar x = (1 +\n§ \\\n§ 2\nvar after = 3\n")) {
                var dedentContSrc = dedentContTemplate.replace("\n", newline);
                var dedentContRes = recoverableParse(dedentContSrc);
                assertEnvelopeRecursive(dedentContRes.ast(), dedentContSrc);
                assertEquals(2, dedentContRes.ast().statements().size(),
                        () -> dedentContSrc + " -> " + dedentContRes.ast().statements());
                var fnDed = assertInstanceOf(FunctionDeclaration.class, dedentContRes.ast().statements().getFirst());
                assertEquals(1, fnDed.body().statements().size());
                var varXDed = assertInstanceOf(VariableDeclaration.class, fnDed.body().statements().getFirst());
                var binDed = assertInstanceOf(BinaryExpression.class, varXDed.value());
                assertEquals(CstIssueKind.MISSING, assertInstanceOf(ErrorExpression.class, binDed.right()).kind());
                assertTrue(errorsOf(dedentContRes).stream().anyMatch(d ->
                        DiagnosticCodes.SYNTAX_UNEXPECTED_TOKEN.equals(d.code())
                                && "2".equals(utf8Slice(dedentContSrc, d.range()))
                                && d.message().contains("class body")));
                assertEquals("after",
                        assertInstanceOf(VariableDeclaration.class, dedentContRes.ast().statements().get(1)).name());
                for (var lex : errorsOf(dedentContRes).stream()
                        .filter(d -> DiagnosticCodes.LEXICAL_ERROR.equals(d.code()))
                        .toList()) {
                    assertNull(lex.recovery());
                }
            }

            // Finding 1c: indented `\t\t\\\n§ 2` preserves `2` as RHS and links `§` to `x`.
            var inScopeContErrSrc = ("func f():\n\tvar x = (1 +\n\t\t\\\n§ 2\nvar after = 3\n")
                    .replace("\n", newline);
            var inScopeContErrRes = recoverableParse(inScopeContErrSrc);
            assertEnvelopeRecursive(inScopeContErrRes.ast(), inScopeContErrSrc);
            assertEquals(2, inScopeContErrRes.ast().statements().size());
            var fnInErr = assertInstanceOf(FunctionDeclaration.class, inScopeContErrRes.ast().statements().getFirst());
            var varXInErr = assertInstanceOf(VariableDeclaration.class, fnInErr.body().statements().getFirst());
            var binInErr = assertInstanceOf(BinaryExpression.class, varXInErr.value());
            assertEquals("2", assertInstanceOf(LiteralExpression.class, binInErr.right()).sourceText());
            assertEquals("after",
                    assertInstanceOf(VariableDeclaration.class, inScopeContErrRes.ast().statements().get(1)).name());
            var lexInErr = errorsOf(inScopeContErrRes).stream()
                    .filter(d -> DiagnosticCodes.LEXICAL_ERROR.equals(d.code()))
                    .toList();
            assertEquals(1, lexInErr.size());
            assertSame(varXInErr, requireNotNull(lexInErr.getFirst().recovery()).owner());
            assertEquals("variable.initializer", requireNotNull(lexInErr.getFirst().recovery()).facet());

            // Finding 2a: exiting unclosed `[1` before `var after = 3` claims in-scope post-newline `§` without synthetic items.
            var exitClaimSrc = ("func f():\n\tvar x = [1\n\t\t\\\n§\nvar after = 3\n").replace("\n", newline);
            var exitClaimRes = recoverableParse(exitClaimSrc);
            assertEnvelopeRecursive(exitClaimRes.ast(), exitClaimSrc);
            assertEquals(2, exitClaimRes.ast().statements().size());
            var fnExitClaim = assertInstanceOf(FunctionDeclaration.class, exitClaimRes.ast().statements().getFirst());
            var varXExitClaim = assertInstanceOf(VariableDeclaration.class, fnExitClaim.body().statements().getFirst());
            var arrExitClaim = assertInstanceOf(ArrayExpression.class, varXExitClaim.value());
            assertEquals(1, arrExitClaim.elements().size());
            assertEquals("1", assertInstanceOf(LiteralExpression.class, arrExitClaim.elements().getFirst()).sourceText());
            assertEquals("after",
                    assertInstanceOf(VariableDeclaration.class, exitClaimRes.ast().statements().get(1)).name());
            assertSame(varXExitClaim, requireNotNull(
                    findErrorByCode(exitClaimRes, DiagnosticCodes.MISSING_CLOSER_ARRAY).recovery()).owner());
            var lexExitClaim = errorsOf(exitClaimRes).stream()
                    .filter(d -> DiagnosticCodes.LEXICAL_ERROR.equals(d.code()))
                    .toList();
            assertEquals(1, lexExitClaim.size());
            assertSame(varXExitClaim, requireNotNull(lexExitClaim.getFirst().recovery()).owner());
            assertEquals("variable.initializer", requireNotNull(lexExitClaim.getFirst().recovery()).facet());

            // Finding 2b: mixed in-scope prefix + dedented suffix before `var after = 3` claims prefix only.
            for (var mixedExitTemplate : List.of(
                    "func f():\n\tvar x = [1\n\t\t\\\n§\n§\nvar after = 3\n",
                    "func f():\n\tvar x = [1\n\t\t§\n§\nvar after = 3\n")) {
                var mixedExitSrc = mixedExitTemplate.replace("\n", newline);
                var mixedExitRes = recoverableParse(mixedExitSrc);
                assertEnvelopeRecursive(mixedExitRes.ast(), mixedExitSrc);
                assertEquals(2, mixedExitRes.ast().statements().size());
                var fnMixedExit = assertInstanceOf(FunctionDeclaration.class,
                        mixedExitRes.ast().statements().getFirst());
                var varXMixedExit = assertInstanceOf(VariableDeclaration.class,
                        fnMixedExit.body().statements().getFirst());
                assertEquals(1, assertInstanceOf(ArrayExpression.class, varXMixedExit.value()).elements().size());
                assertEquals("after",
                        assertInstanceOf(VariableDeclaration.class, mixedExitRes.ast().statements().get(1)).name());
                var lexMixedExit = errorsOf(mixedExitRes).stream()
                        .filter(d -> DiagnosticCodes.LEXICAL_ERROR.equals(d.code()))
                        .toList();
                assertEquals(2, lexMixedExit.size());
                assertSame(varXMixedExit, requireNotNull(lexMixedExit.get(0).recovery()).owner());
                assertEquals("variable.initializer", requireNotNull(lexMixedExit.get(0).recovery()).facet());
                assertNull(lexMixedExit.get(1).recovery());
            }

            // Finding 2c: ancestor-closer / inner grouping inside `assert` links `\t\t§` to `assert.condition`.
            for (var assertTemplate : List.of(
                    "func f():\n\tassert((1\n\t\t§\n)\n",
                    "func f():\n\tassert([1\n\t\t§\n)\n")) {
                var assertSrc = assertTemplate.replace("\n", newline);
                var assertRes = recoverableParse(assertSrc);
                assertEnvelopeRecursive(assertRes.ast(), assertSrc);
                var fnAssert = onlyFunction(assertRes);
                var assertStmt = assertInstanceOf(AssertStatement.class, fnAssert.body().statements().getFirst());
                var lexAssert = errorsOf(assertRes).stream()
                        .filter(d -> DiagnosticCodes.LEXICAL_ERROR.equals(d.code()))
                        .toList();
                assertEquals(1, lexAssert.size());
                assertSame(assertStmt, requireNotNull(lexAssert.getFirst().recovery()).owner());
                assertEquals("assert.condition", requireNotNull(lexAssert.getFirst().recovery()).facet());
            }
        }
    }

    @Test
    void consumeCloserNonCloserExitDoesNotClaimEscapingDeclarationLinePrefixAcrossLfAndCrlf() {
        for (var newline : List.of("\n", "\r\n")) {
            // 1. Single declaration-line prefix `§` stays standalone with or without a later stray `)`.
            for (var hasStrayCloser : List.of(true, false)) {
                var src = ("func f():\n\tvar x = (1\n\t§ var after = 3\n" + (hasStrayCloser ? "\t)\n" : ""))
                        .replace("\n", newline);
                var res = recoverableParse(src);
                assertEnvelopeRecursive(res.ast(), src);
                var fn = onlyFunction(res);
                var varX = assertInstanceOf(VariableDeclaration.class, fn.body().statements().getFirst());
                var varAfter = assertInstanceOf(VariableDeclaration.class, fn.body().statements().get(1));
                assertEquals("x", varX.name());
                assertEquals("after", varAfter.name());
                var closerDiag = findErrorByCode(res, DiagnosticCodes.MISSING_CLOSER_GROUPING);
                assertSame(varX, requireNotNull(closerDiag.recovery()).owner());
                var lexDiags = errorsOf(res).stream()
                        .filter(d -> DiagnosticCodes.LEXICAL_ERROR.equals(d.code()))
                        .toList();
                assertEquals(1, lexDiags.size());
                assertNull(lexDiags.getFirst().recovery());
                assertEquals(hasStrayCloser, errorsOf(res).stream()
                        .anyMatch(d -> DiagnosticCodes.UNEXPECTED_CLOSER.equals(d.code())
                                && ")".equals(utf8Slice(src, d.range()))));
            }

            // 2. Earlier in-scope line links to `x`, while the escaping declaration-line prefix stays standalone.
            for (var hasStrayCloser : List.of(true, false)) {
                var src = ("func f():\n\tvar x = (1\n\t\t§\n\t§ var after = 3\n" + (hasStrayCloser ? "\t)\n" : ""))
                        .replace("\n", newline);
                var res = recoverableParse(src);
                assertEnvelopeRecursive(res.ast(), src);
                var fn = onlyFunction(res);
                var varX = assertInstanceOf(VariableDeclaration.class, fn.body().statements().getFirst());
                var varAfter = assertInstanceOf(VariableDeclaration.class, fn.body().statements().get(1));
                assertEquals("x", varX.name());
                assertEquals("after", varAfter.name());
                var closerDiag = findErrorByCode(res, DiagnosticCodes.MISSING_CLOSER_GROUPING);
                assertSame(varX, requireNotNull(closerDiag.recovery()).owner());
                var lexDiags = errorsOf(res).stream()
                        .filter(d -> DiagnosticCodes.LEXICAL_ERROR.equals(d.code()))
                        .toList();
                assertEquals(2, lexDiags.size());
                assertSame(varX, requireNotNull(lexDiags.get(0).recovery()).owner());
                assertEquals("variable.initializer", requireNotNull(lexDiags.get(0).recovery()).facet());
                assertNull(lexDiags.get(1).recovery());
                assertEquals(hasStrayCloser, errorsOf(res).stream()
                        .anyMatch(d -> DiagnosticCodes.UNEXPECTED_CLOSER.equals(d.code())
                                && ")".equals(utf8Slice(src, d.range()))));
            }
        }
    }

    @Test
    void consumeCloserNonCloserExitAtEofWithoutTrailingNewlinePreservesInScopeClaimAcrossLfAndCrlf() {
        for (var newline : List.of("\n", "\r\n")) {
            // 1. Indented `\t\t§` before EOF (with or without final newline) links to `x` (`variable.initializer`).
            for (var trailingNewline : List.of(false, true)) {
                var suffix = trailingNewline ? newline : "";
                var cases = List.of(
                        Map.entry("func f():\n\tvar x = (1\n\t\t§", DiagnosticCodes.MISSING_CLOSER_GROUPING),
                        Map.entry("func f():\n\tvar x = [1\n\t\t§", DiagnosticCodes.MISSING_CLOSER_ARRAY),
                        Map.entry("func f():\n\tvar x = f(1\n\t\t§", DiagnosticCodes.MISSING_CLOSER_CALL)
                );
                for (var entry : cases) {
                    var src = entry.getKey().replace("\n", newline) + suffix;
                    var res = recoverableParse(src);
                    assertEnvelopeRecursive(res.ast(), src);
                    var fn = onlyFunction(res);
                    var varX = assertInstanceOf(VariableDeclaration.class, fn.body().statements().getFirst());
                    var closerDiag = findErrorByCode(res, entry.getValue());
                    assertSame(varX, requireNotNull(closerDiag.recovery()).owner());
                    var lexDiags = errorsOf(res).stream()
                            .filter(d -> DiagnosticCodes.LEXICAL_ERROR.equals(d.code()))
                            .toList();
                    assertEquals(1, lexDiags.size());
                    assertSame(varX, requireNotNull(lexDiags.getFirst().recovery()).owner());
                    assertEquals("variable.initializer", requireNotNull(lexDiags.getFirst().recovery()).facet());
                }

                // 2. Dedented EOF control `(1\n§` stays standalone with or without final newline.
                var dedentSrc = "func f():\n\tvar x = (1\n§".replace("\n", newline) + suffix;
                var dedentRes = recoverableParse(dedentSrc);
                assertEnvelopeRecursive(dedentRes.ast(), dedentSrc);
                var fnDed = onlyFunction(dedentRes);
                var varXDed = assertInstanceOf(VariableDeclaration.class, fnDed.body().statements().getFirst());
                var closerDed = findErrorByCode(dedentRes, DiagnosticCodes.MISSING_CLOSER_GROUPING);
                assertSame(varXDed, requireNotNull(closerDed.recovery()).owner());
                var lexDed = errorsOf(dedentRes).stream()
                        .filter(d -> DiagnosticCodes.LEXICAL_ERROR.equals(d.code()))
                        .toList();
                assertEquals(1, lexDed.size());
                assertNull(lexDed.getFirst().recovery());
            }
        }
    }

    @Test
    void escapingDeclarationLogicalStartLineCutoffAppliesToCloserExitAndSlotRecoveryAcrossLfAndCrlf() {
        for (var newline : List.of("\n", "\r\n")) {
            // Finding 1a: `(1\n\t§ \\\n\tvar after = 3\n\t)\n` keeps continued-line `§` standalone.
            var f1SingleSrc = "func f():\n\tvar x = (1\n\t§ \\\n\tvar after = 3\n\t)\n".replace("\n", newline);
            var f1SingleRes = recoverableParse(f1SingleSrc);
            assertEnvelopeRecursive(f1SingleRes.ast(), f1SingleSrc);
            var fnF1Single = onlyFunction(f1SingleRes);
            var varXF1Single = assertInstanceOf(VariableDeclaration.class, fnF1Single.body().statements().getFirst());
            var varAfterF1Single = assertInstanceOf(VariableDeclaration.class, fnF1Single.body().statements().get(1));
            assertEquals("x", varXF1Single.name());
            assertEquals("after", varAfterF1Single.name());
            assertSame(varXF1Single, requireNotNull(
                    findErrorByCode(f1SingleRes, DiagnosticCodes.MISSING_CLOSER_GROUPING).recovery()).owner());
            var lexF1Single = errorsOf(f1SingleRes).stream()
                    .filter(d -> DiagnosticCodes.LEXICAL_ERROR.equals(d.code()))
                    .toList();
            assertEquals(1, lexF1Single.size());
            assertNull(lexF1Single.getFirst().recovery());

            // Finding 1b: `(1\n\t\t§\n\t§ \\\n\tvar after = 3\n\t)\n` links earlier `\t\t§` to `x`, leaves continued `\t§` standalone.
            var f1MixedSrc = "func f():\n\tvar x = (1\n\t\t§\n\t§ \\\n\tvar after = 3\n\t)\n".replace("\n", newline);
            var f1MixedRes = recoverableParse(f1MixedSrc);
            assertEnvelopeRecursive(f1MixedRes.ast(), f1MixedSrc);
            var fnF1Mixed = onlyFunction(f1MixedRes);
            var varXF1Mixed = assertInstanceOf(VariableDeclaration.class, fnF1Mixed.body().statements().getFirst());
            var varAfterF1Mixed = assertInstanceOf(VariableDeclaration.class, fnF1Mixed.body().statements().get(1));
            assertEquals("x", varXF1Mixed.name());
            assertEquals("after", varAfterF1Mixed.name());
            var lexF1Mixed = errorsOf(f1MixedRes).stream()
                    .filter(d -> DiagnosticCodes.LEXICAL_ERROR.equals(d.code()))
                    .toList();
            assertEquals(2, lexF1Mixed.size());
            assertSame(varXF1Mixed, requireNotNull(lexF1Mixed.get(0).recovery()).owner());
            assertEquals("variable.initializer", requireNotNull(lexF1Mixed.get(0).recovery()).facet());
            assertNull(lexF1Mixed.get(1).recovery());

            // Finding 2a: `(1 +\n\t§ var after = 3\n\t)\n` and without stray `)` keep `MISSING` RHS + `SLOT_BINARY_RIGHT` + `§` standalone.
            for (var hasStrayCloser : List.of(true, false)) {
                var f2Src = ("func f():\n\tvar x = (1 +\n\t§ var after = 3\n" + (hasStrayCloser ? "\t)\n" : ""))
                        .replace("\n", newline);
                var f2Res = recoverableParse(f2Src);
                assertEnvelopeRecursive(f2Res.ast(), f2Src);
                var fnF2 = onlyFunction(f2Res);
                var varXF2 = assertInstanceOf(VariableDeclaration.class, fnF2.body().statements().getFirst());
                var varAfterF2 = assertInstanceOf(VariableDeclaration.class, fnF2.body().statements().get(1));
                assertEquals("after", varAfterF2.name());
                var binF2 = assertInstanceOf(BinaryExpression.class, varXF2.value());
                assertEquals(CstIssueKind.MISSING, assertInstanceOf(ErrorExpression.class, binF2.right()).kind());
                assertSame(varXF2, requireNotNull(findErrorByCode(f2Res, DiagnosticCodes.SLOT_BINARY_RIGHT).recovery()).owner());
                assertSame(varXF2, requireNotNull(
                        findErrorByCode(f2Res, DiagnosticCodes.MISSING_CLOSER_GROUPING).recovery()).owner());
                var lexF2 = errorsOf(f2Res).stream()
                        .filter(d -> DiagnosticCodes.LEXICAL_ERROR.equals(d.code()))
                        .toList();
                assertEquals(1, lexF2.size());
                assertNull(lexF2.getFirst().recovery());
            }

            // Finding 2b: `(1 +\n\t\t§\n\t§ var after = 3\n\t)\n` links earlier `\t\t§` as RHS `ERROR("§")`, leaves line-4 `\t§` standalone.
            var f2MixedSrc = "func f():\n\tvar x = (1 +\n\t\t§\n\t§ var after = 3\n\t)\n".replace("\n", newline);
            var f2MixedRes = recoverableParse(f2MixedSrc);
            assertEnvelopeRecursive(f2MixedRes.ast(), f2MixedSrc);
            var fnF2Mixed = onlyFunction(f2MixedRes);
            var varXF2Mixed = assertInstanceOf(VariableDeclaration.class, fnF2Mixed.body().statements().getFirst());
            var varAfterF2Mixed = assertInstanceOf(VariableDeclaration.class, fnF2Mixed.body().statements().get(1));
            assertEquals("after", varAfterF2Mixed.name());
            var binF2Mixed = assertInstanceOf(BinaryExpression.class, varXF2Mixed.value());
            var rhsF2Mixed = assertInstanceOf(ErrorExpression.class, binF2Mixed.right());
            assertEquals(CstIssueKind.ERROR, rhsF2Mixed.kind());
            assertEquals("§", utf8Slice(f2MixedSrc, rhsF2Mixed.range()));
            var lexF2Mixed = errorsOf(f2MixedRes).stream()
                    .filter(d -> DiagnosticCodes.LEXICAL_ERROR.equals(d.code()))
                    .toList();
            assertEquals(2, lexF2Mixed.size());
            assertSame(varXF2Mixed, requireNotNull(lexF2Mixed.get(0).recovery()).owner());
            assertEquals("variable.initializer", requireNotNull(lexF2Mixed.get(0).recovery()).facet());
            assertNull(lexF2Mixed.get(1).recovery());

            // Finding 2c: `(1 +\n\t§ \\\n\tvar after = 3\n\t)\n` keeps continued `\t§` standalone with `MISSING` RHS.
            var f2ContSrc = "func f():\n\tvar x = (1 +\n\t§ \\\n\tvar after = 3\n\t)\n".replace("\n", newline);
            var f2ContRes = recoverableParse(f2ContSrc);
            assertEnvelopeRecursive(f2ContRes.ast(), f2ContSrc);
            var fnF2Cont = onlyFunction(f2ContRes);
            var varXF2Cont = assertInstanceOf(VariableDeclaration.class, fnF2Cont.body().statements().getFirst());
            var varAfterF2Cont = assertInstanceOf(VariableDeclaration.class, fnF2Cont.body().statements().get(1));
            assertEquals("after", varAfterF2Cont.name());
            var binF2Cont = assertInstanceOf(BinaryExpression.class, varXF2Cont.value());
            assertEquals(CstIssueKind.MISSING, assertInstanceOf(ErrorExpression.class, binF2Cont.right()).kind());
            assertSame(varXF2Cont, requireNotNull(
                    findErrorByCode(f2ContRes, DiagnosticCodes.SLOT_BINARY_RIGHT).recovery()).owner());
            var lexF2Cont = errorsOf(f2ContRes).stream()
                    .filter(d -> DiagnosticCodes.LEXICAL_ERROR.equals(d.code()))
                    .toList();
            assertEquals(1, lexF2Cont.size());
            assertNull(lexF2Cont.getFirst().recovery());

            // Finding 2d: real closer control `(1 +\n\t§\n\t)\n` claims `\t§` as binary RHS `ERROR("§")` linked to `x`.
            var closedCtrlSrc = "func f():\n\tvar x = (1 +\n\t§\n\t)\n".replace("\n", newline);
            var closedCtrlRes = recoverableParse(closedCtrlSrc);
            assertEnvelopeRecursive(closedCtrlRes.ast(), closedCtrlSrc);
            var varXClosed = assertInstanceOf(VariableDeclaration.class,
                    onlyFunction(closedCtrlRes).body().statements().getFirst());
            var binClosed = assertInstanceOf(BinaryExpression.class, varXClosed.value());
            var rhsClosed = assertInstanceOf(ErrorExpression.class, binClosed.right());
            assertEquals(CstIssueKind.ERROR, rhsClosed.kind());
            assertEquals("§", utf8Slice(closedCtrlSrc, rhsClosed.range()));
            var lexClosed = errorsOf(closedCtrlRes).stream()
                    .filter(d -> DiagnosticCodes.LEXICAL_ERROR.equals(d.code()))
                    .toList();
            assertEquals(1, lexClosed.size());
            assertSame(varXClosed, requireNotNull(lexClosed.getFirst().recovery()).owner());
            assertEquals("variable.initializer", requireNotNull(lexClosed.getFirst().recovery()).facet());
        }
    }

    @Test
    void escapingIfCutoffDistinguishesTernaryFromStatementHeadAcrossLfAndCrlf() {
        for (var newline : List.of("\n", "\r\n")) {
            // 1. Ternary `if ready else 2` inside `(1 +` claims `\t§` as binary RHS and parses cleanly.
            var ternarySrc = "func f():\n\tvar x = (1 +\n\t§ if ready else 2)\n".replace("\n", newline);
            var ternaryRes = recoverableParse(ternarySrc);
            assertEnvelopeRecursive(ternaryRes.ast(), ternarySrc);
            var fnTernary = onlyFunction(ternaryRes);
            assertEquals(1, fnTernary.body().statements().size());
            var varXTernary = assertInstanceOf(VariableDeclaration.class, fnTernary.body().statements().getFirst());
            var condTernary = assertInstanceOf(ConditionalExpression.class, varXTernary.value());
            var binTernary = assertInstanceOf(BinaryExpression.class, condTernary.left());
            var rhsTernary = assertInstanceOf(ErrorExpression.class, binTernary.right());
            assertEquals(CstIssueKind.ERROR, rhsTernary.kind());
            assertEquals("§", utf8Slice(ternarySrc, rhsTernary.range()));
            assertEquals("ready", assertInstanceOf(IdentifierExpression.class, condTernary.condition()).name());
            assertEquals("2", assertInstanceOf(LiteralExpression.class, condTernary.right()).sourceText());
            assertTrue(errorsOf(ternaryRes).stream()
                    .noneMatch(d -> DiagnosticCodes.SLOT_BINARY_RIGHT.equals(d.code())
                            || DiagnosticCodes.MISSING_CLOSER_GROUPING.equals(d.code())));
            var lexTernary = errorsOf(ternaryRes).stream()
                    .filter(d -> DiagnosticCodes.LEXICAL_ERROR.equals(d.code()))
                    .toList();
            assertEquals(1, lexTernary.size());
            assertSame(varXTernary, requireNotNull(lexTernary.getFirst().recovery()).owner());
            assertEquals("variable.initializer", requireNotNull(lexTernary.getFirst().recovery()).facet());

            // 2. Statement `if ready:` (with or without stray `)`) cuts off before `\t§`, preserving `IfStatement`.
            for (var hasStrayCloser : List.of(true, false)) {
                var stmtSrc = ("func f():\n\tvar x = (1 +\n\t§ if ready:\n\t\tpass\n"
                        + (hasStrayCloser ? "\t)\n" : "")).replace("\n", newline);
                var stmtRes = recoverableParse(stmtSrc);
                assertEnvelopeRecursive(stmtRes.ast(), stmtSrc);
                var fnStmt = onlyFunction(stmtRes);
                assertEquals(2, fnStmt.body().statements().size());
                var varXStmt = assertInstanceOf(VariableDeclaration.class, fnStmt.body().statements().getFirst());
                assertInstanceOf(IfStatement.class, fnStmt.body().statements().get(1));
                var binStmt = assertInstanceOf(BinaryExpression.class, varXStmt.value());
                assertEquals(CstIssueKind.MISSING, assertInstanceOf(ErrorExpression.class, binStmt.right()).kind());
                assertSame(varXStmt, requireNotNull(
                        findErrorByCode(stmtRes, DiagnosticCodes.SLOT_BINARY_RIGHT).recovery()).owner());
                assertSame(varXStmt, requireNotNull(
                        findErrorByCode(stmtRes, DiagnosticCodes.MISSING_CLOSER_GROUPING).recovery()).owner());
                var lexStmt = errorsOf(stmtRes).stream()
                        .filter(d -> DiagnosticCodes.LEXICAL_ERROR.equals(d.code()))
                        .toList();
                assertEquals(1, lexStmt.size());
                assertNull(lexStmt.getFirst().recovery());
            }
        }
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void nestedUnclosedGroupingsDoNotRescanLongLexicalPrefixAcrossDepths() {
        var errorCount = 1000;
        var prefix = "§ ".repeat(errorCount);
        var baselineSteps = -1;
        for (var depth : List.of(1, 10, 50)) {
            var src = "func f():\n\tvar x = " + "(".repeat(depth) + "1\n\t" + prefix + "var after = 3\n";
            var parser = new GdScriptParser();
            var res = parser.parse(src);
            assertEnvelopeRecursive(res.ast(), src);
            var fn = onlyFunction(res);
            assertEquals(2, fn.body().statements().size());
            var varX = assertInstanceOf(VariableDeclaration.class, fn.body().statements().getFirst());
            var varAfter = assertInstanceOf(VariableDeclaration.class, fn.body().statements().get(1));
            assertEquals("after", varAfter.name());
            var closerDiags = errorsOf(res).stream()
                    .filter(d -> DiagnosticCodes.MISSING_CLOSER_GROUPING.equals(d.code()))
                    .toList();
            assertEquals(depth, closerDiags.size());
            for (var closerDiag : closerDiags) {
                assertSame(varX, requireNotNull(closerDiag.recovery()).owner());
            }
            var lexDiags = errorsOf(res).stream()
                    .filter(d -> DiagnosticCodes.LEXICAL_ERROR.equals(d.code()))
                    .toList();
            assertEquals(errorCount, lexDiags.size());
            for (var lexDiag : lexDiags) {
                assertNull(lexDiag.recovery());
            }
            var steps = parser.stagedErrorPrefixCoverageSteps();
            assertTrue(steps <= 4 * errorCount + 64,
                    "Prefix coverage steps exceeded linear bound at depth " + depth + ": " + steps);
            if (baselineSteps < 0) {
                baselineSteps = steps;
            } else {
                assertEquals(baselineSteps, steps,
                        "Prefix coverage steps must not grow with nesting depth");
            }
        }
    }

    @Test
    void statementHeadColonScanDistinguishesLambdaFromAttributeCallAndGroupedLambdaAcrossLfAndCrlf() {
        for (var newline : List.of("\n", "\r\n")) {
            // 5 & 6. `obj.func():` (`.` before `func`) and `(func(): pass):` (grouped at depth > 0) are `if` heads.
            for (var condText : List.of("obj.func()", "(func(): pass)")) {
                var stmtSrc = ("func f():\n\tvar x = (1 +\n\t§ if " + condText + ":\n\t\tpass\n\t)\n")
                        .replace("\n", newline);
                var stmtRes = recoverableParse(stmtSrc);
                assertEnvelopeRecursive(stmtRes.ast(), stmtSrc);
                var fnStmt = onlyFunction(stmtRes);
                assertEquals(2, fnStmt.body().statements().size());
                var varXStmt = assertInstanceOf(VariableDeclaration.class, fnStmt.body().statements().getFirst());
                assertInstanceOf(IfStatement.class, fnStmt.body().statements().get(1));
                var binStmt = assertInstanceOf(BinaryExpression.class, varXStmt.value());
                assertEquals(CstIssueKind.MISSING, assertInstanceOf(ErrorExpression.class, binStmt.right()).kind());
                assertSame(varXStmt, requireNotNull(
                        findErrorByCode(stmtRes, DiagnosticCodes.SLOT_BINARY_RIGHT).recovery()).owner());
                assertSame(varXStmt, requireNotNull(
                        findErrorByCode(stmtRes, DiagnosticCodes.MISSING_CLOSER_GROUPING).recovery()).owner());
                var lexStmt = errorsOf(stmtRes).stream()
                        .filter(d -> DiagnosticCodes.LEXICAL_ERROR.equals(d.code()))
                        .toList();
                assertEquals(1, lexStmt.size());
                assertNull(lexStmt.getFirst().recovery());
            }

            // 7. Ternary `if func(): pass else 2` inside `(1 +` claims `§` as binary RHS without missing-RHS.
            var ternarySrc = "func f():\n\tvar x = (1 +\n\t§ if func(): pass\n\telse 2)\n".replace("\n", newline);
            var ternaryRes = recoverableParse(ternarySrc);
            assertEnvelopeRecursive(ternaryRes.ast(), ternarySrc);
            var fnTernary = onlyFunction(ternaryRes);
            assertEquals(1, fnTernary.body().statements().size());
            var varXTernary = assertInstanceOf(VariableDeclaration.class, fnTernary.body().statements().getFirst());
            var condTernary = assertInstanceOf(ConditionalExpression.class, varXTernary.value());
            var binTernary = assertInstanceOf(BinaryExpression.class, condTernary.left());
            var rhsTernary = assertInstanceOf(ErrorExpression.class, binTernary.right());
            assertEquals(CstIssueKind.ERROR, rhsTernary.kind());
            assertEquals("§", utf8Slice(ternarySrc, rhsTernary.range()));
            assertInstanceOf(LambdaExpression.class, condTernary.condition());
            assertEquals("2", assertInstanceOf(LiteralExpression.class, condTernary.right()).sourceText());
            assertTrue(errorsOf(ternaryRes).stream()
                    .noneMatch(d -> DiagnosticCodes.SLOT_BINARY_RIGHT.equals(d.code())
                            || DiagnosticCodes.MISSING_CLOSER_GROUPING.equals(d.code())));
            var lexTernary = errorsOf(ternaryRes).stream()
                    .filter(d -> DiagnosticCodes.LEXICAL_ERROR.equals(d.code()))
                    .toList();
            assertEquals(1, lexTernary.size());
            assertSame(varXTernary, requireNotNull(lexTernary.getFirst().recovery()).owner());
            assertEquals("variable.initializer", requireNotNull(lexTernary.getFirst().recovery()).facet());
        }
    }

    @Test
    void indentedBlockLambdaInIfConditionPreservesStatementHeadAndTernaryDistinctionAcrossLfAndCrlf() {
        for (var newline : List.of("\n", "\r\n")) {
            for (var stmtSrc : List.of(
                    ("func f():\n\tvar x = (1 +\n\tif func():\n\t\tpass\n\t:\n\t\tpass\n\t)\n").replace("\n", newline),
                    ("func f():\n\tvar x = (1 +\n\tif func():\n\t\tvar s = \"\"\"\ncol0\n\"\"\"\n\t:\n\t\tpass\n\t)\n")
                            .replace("\n", newline)
            )) {
                var res = recoverableParse(stmtSrc);
                assertEnvelopeRecursive(res.ast(), stmtSrc);
                var fn = onlyFunction(res);
                assertEquals(2, fn.body().statements().size());
                var varX = assertInstanceOf(VariableDeclaration.class, fn.body().statements().getFirst());
                var ifStmt = assertInstanceOf(IfStatement.class, fn.body().statements().get(1));
                assertInstanceOf(LambdaExpression.class, ifStmt.condition());
                assertInstanceOf(PassStatement.class, ifStmt.body().statements().getFirst());
                var bin = assertInstanceOf(BinaryExpression.class, varX.value());
                assertEquals(CstIssueKind.MISSING, assertInstanceOf(ErrorExpression.class, bin.right()).kind());
                assertSame(varX, requireNotNull(
                        findErrorByCode(res, DiagnosticCodes.SLOT_BINARY_RIGHT).recovery()).owner());
                assertSame(varX, requireNotNull(
                        findErrorByCode(res, DiagnosticCodes.MISSING_CLOSER_GROUPING).recovery()).owner());
                assertNotNull(findErrorByCode(res, DiagnosticCodes.UNEXPECTED_CLOSER));
            }

            // `if func():\n\t\tpass\n\tand ready:` still classifies as a statement head so `var x` exits
            // cleanly with `MISSING` RHS and the sibling `IfStatement(LambdaExpression)` survives.
            var andReadySrc = ("func f():\n\tvar x = (1 +\n\tif func():\n\t\tpass\n\tand ready:\n\t\tpass\n\t)\n")
                    .replace("\n", newline);
            var andReadyRes = recoverableParse(andReadySrc);
            assertEnvelopeRecursive(andReadyRes.ast(), andReadySrc);
            var andReadyFn = onlyFunction(andReadyRes);
            assertEquals(3, andReadyFn.body().statements().size());
            var andReadyVarX = assertInstanceOf(VariableDeclaration.class, andReadyFn.body().statements().getFirst());
            var andReadyIf = assertInstanceOf(IfStatement.class, andReadyFn.body().statements().get(1));
            assertInstanceOf(LambdaExpression.class, andReadyIf.condition());
            assertInstanceOf(PassStatement.class, andReadyFn.body().statements().get(2));
            var andReadyBin = assertInstanceOf(BinaryExpression.class, andReadyVarX.value());
            assertEquals(CstIssueKind.MISSING, assertInstanceOf(ErrorExpression.class, andReadyBin.right()).kind());
            assertSame(andReadyVarX, requireNotNull(
                    findErrorByCode(andReadyRes, DiagnosticCodes.SLOT_BINARY_RIGHT).recovery()).owner());
            assertSame(andReadyVarX, requireNotNull(
                    findErrorByCode(andReadyRes, DiagnosticCodes.MISSING_CLOSER_GROUPING).recovery()).owner());
        }
    }

    @Test
    void lambdaHeaderWithAdjacentLexicalErrorRemainsRecognizedAcrossLfAndCrlf() {
        for (var newline : List.of("\n", "\r\n")) {
            var beforeSrc = "var x = (1\nif §func(): pass\nelse 2)\n".replace("\n", newline);
            var beforeRes = recoverableParse(beforeSrc);
            assertEnvelopeRecursive(beforeRes.ast(), beforeSrc);
            assertEquals(1, beforeRes.ast().statements().size());
            var beforeVar = assertInstanceOf(VariableDeclaration.class, beforeRes.ast().statements().getFirst());
            var beforeCond = assertInstanceOf(ConditionalExpression.class, beforeVar.value());
            var beforeLambda = assertInstanceOf(LambdaExpression.class, beforeCond.condition());
            assertInstanceOf(PassStatement.class, beforeLambda.body().statements().getFirst());
            assertEquals("2", assertInstanceOf(LiteralExpression.class, beforeCond.right()).sourceText());
            var beforeErrors = errorsOf(beforeRes);
            assertEquals(1, beforeErrors.size());
            assertEquals(DiagnosticCodes.LEXICAL_ERROR, beforeErrors.getFirst().code());
            assertSame(beforeVar, requireNotNull(beforeErrors.getFirst().recovery()).owner());
            assertEquals("variable.initializer", requireNotNull(beforeErrors.getFirst().recovery()).facet());

            var betweenSrc = "var x = (1\nif func§(): pass\nelse 2)\n".replace("\n", newline);
            var betweenRes = recoverableParse(betweenSrc);
            assertEnvelopeRecursive(betweenRes.ast(), betweenSrc);
            assertEquals(1, betweenRes.ast().statements().size());
            var betweenVar = assertInstanceOf(VariableDeclaration.class, betweenRes.ast().statements().getFirst());
            var betweenCond = assertInstanceOf(ConditionalExpression.class, betweenVar.value());
            var betweenLambda = assertInstanceOf(LambdaExpression.class, betweenCond.condition());
            assertInstanceOf(PassStatement.class, betweenLambda.body().statements().getFirst());
            assertEquals("2", assertInstanceOf(LiteralExpression.class, betweenCond.right()).sourceText());
            var betweenErrors = errorsOf(betweenRes);
            assertEquals(1, betweenErrors.size());
            assertEquals(DiagnosticCodes.LEXICAL_ERROR, betweenErrors.getFirst().code());
            assertSame(betweenLambda, requireNotNull(betweenErrors.getFirst().recovery()).owner());
            assertEquals("function.signature", requireNotNull(betweenErrors.getFirst().recovery()).facet());
        }
    }

    @Test
    void statementHeadColonScanHandlesMemberElseAndNestedTernaryConditionAcrossLfAndCrlf() {
        for (var newline : List.of("\n", "\r\n")) {
            for (var stmtSrc : List.of(
                    "func f():\n\tvar x = (1 +\n\t§ if obj.else():\n\t\tpass\n\t)\n".replace("\n", newline),
                    "func f():\n\tvar x = (1 +\n\t§ if true if ready else false:\n\t\tpass\n\t)\n"
                            .replace("\n", newline)
            )) {
                var res = recoverableParse(stmtSrc);
                assertEnvelopeRecursive(res.ast(), stmtSrc);
                var fn = onlyFunction(res);
                assertEquals(2, fn.body().statements().size());
                var varX = assertInstanceOf(VariableDeclaration.class, fn.body().statements().getFirst());
                assertInstanceOf(IfStatement.class, fn.body().statements().get(1));
                var bin = assertInstanceOf(BinaryExpression.class, varX.value());
                assertEquals(CstIssueKind.MISSING, assertInstanceOf(ErrorExpression.class, bin.right()).kind());
                assertSame(varX, requireNotNull(
                        findErrorByCode(res, DiagnosticCodes.SLOT_BINARY_RIGHT).recovery()).owner());
                assertSame(varX, requireNotNull(
                        findErrorByCode(res, DiagnosticCodes.MISSING_CLOSER_GROUPING).recovery()).owner());
                var lex = errorsOf(res).stream()
                        .filter(d -> DiagnosticCodes.LEXICAL_ERROR.equals(d.code()))
                        .toList();
                assertEquals(1, lex.size());
                assertNull(lex.getFirst().recovery());
            }

            for (var ternarySrc : List.of(
                    "func f():\n\tvar x = (1 +\n\t§ if ready else 2)\n".replace("\n", newline),
                    "func f():\n\tvar x = (1 +\n\t§ if a if b else c else 2)\n".replace("\n", newline)
            )) {
                var res = recoverableParse(ternarySrc);
                assertEnvelopeRecursive(res.ast(), ternarySrc);
                var fn = onlyFunction(res);
                assertEquals(1, fn.body().statements().size());
                var varX = assertInstanceOf(VariableDeclaration.class, fn.body().statements().getFirst());
                var cond = assertInstanceOf(ConditionalExpression.class, varX.value());
                var bin = assertInstanceOf(BinaryExpression.class, cond.left());
                var rhs = assertInstanceOf(ErrorExpression.class, bin.right());
                assertEquals("§", utf8Slice(ternarySrc, rhs.range()));
                assertEquals("2", assertInstanceOf(LiteralExpression.class, cond.right()).sourceText());
                assertTrue(errorsOf(res).stream()
                        .noneMatch(d -> DiagnosticCodes.SLOT_BINARY_RIGHT.equals(d.code())
                                || DiagnosticCodes.MISSING_CLOSER_GROUPING.equals(d.code())));
                var lex = errorsOf(res).stream()
                        .filter(d -> DiagnosticCodes.LEXICAL_ERROR.equals(d.code()))
                        .toList();
                assertEquals(1, lex.size());
                assertSame(varX, requireNotNull(lex.getFirst().recovery()).owner());
                assertEquals("variable.initializer", requireNotNull(lex.getFirst().recovery()).facet());
            }
        }
    }

    @Test
    void matchedDictionaryKeyTernaryMissingElsePreservesColonSeparatorAcrossLfAndCrlf() {
        for (var newline : List.of("\n", "\r\n")) {
            var singleRes = recoverableParse("var x = {1 if ready: 3, 4: 5}\n".replace("\n", newline));
            var multiSrc = "var x = {1\nif ready: 3, 4: 5}\n".replace("\n", newline);
            var multiRes = recoverableParse(multiSrc);
            assertEnvelopeRecursive(multiRes.ast(), multiSrc);
            assertEquals(
                    errorsOf(singleRes).stream().map(AstDiagnostic::code).toList(),
                    errorsOf(multiRes).stream().map(AstDiagnostic::code).toList());
            assertTrue(errorsOf(multiRes).stream()
                    .noneMatch(d -> DiagnosticCodes.MISSING_SEPARATOR_DICTIONARY.equals(d.code())));
            var varX = assertInstanceOf(VariableDeclaration.class, multiRes.ast().statements().getFirst());
            var dict = assertInstanceOf(DictionaryExpression.class, varX.value());
            assertEquals(2, dict.entries().size());
            var firstCond = assertInstanceOf(ConditionalExpression.class, dict.entries().getFirst().key());
            assertEquals(CstIssueKind.MISSING,
                    assertInstanceOf(ErrorExpression.class, firstCond.right()).kind());
            assertEquals("3", assertInstanceOf(LiteralExpression.class, dict.entries().getFirst().value())
                    .sourceText());
            assertEquals("4", assertInstanceOf(LiteralExpression.class, dict.entries().get(1).key()).sourceText());
            assertEquals("5", assertInstanceOf(LiteralExpression.class, dict.entries().get(1).value()).sourceText());

            var unclosedSrc = "func f():\n\tvar x = {1\n\tif ready:\n\t\tpass\n".replace("\n", newline);
            var unclosedRes = recoverableParse(unclosedSrc);
            assertEnvelopeRecursive(unclosedRes.ast(), unclosedSrc);
            var fn = onlyFunction(unclosedRes);
            assertEquals(2, fn.body().statements().size());
            assertInstanceOf(VariableDeclaration.class, fn.body().statements().getFirst());
            assertInstanceOf(IfStatement.class, fn.body().statements().get(1));
        }
    }

    @Test
    void repeatedInlineIfInArrayDoesNotScanStatementHeadColons() {
        var count = 2000;
        var source = "var x = [" + "1 + if 1, ".repeat(count) + "]\n";
        var parser = new GdScriptParser();
        var result = parser.parse(source);
        assertEnvelopeRecursive(result.ast(), source);
        assertEquals(0, parser.statementHeadColonScanSteps());
        var varX = assertInstanceOf(VariableDeclaration.class, result.ast().statements().getFirst());
        var array = assertInstanceOf(ArrayExpression.class, varX.value());
        assertEquals(count, array.elements().size());
        assertEquals(count * 2, errorsOf(result).size());
    }

    @Test
    void blockLambdaTrailingLexicalErrorAndNodePathPrefixAndDictionaryKeyColonGuardsAcrossLfAndCrlf() {
        for (var newline : List.of("\n", "\r\n")) {
            // Finding 1: recoverable lexical error after `func():` does not turn block lambda into inline.
            var lambdaErrSrc = ("func f():\n\tvar x = (1 +\n\tif func(): §\n\t\tpass\n\t:\n\t\tpass\n\t)\n")
                    .replace("\n", newline);
            var lambdaErrRes = recoverableParse(lambdaErrSrc);
            assertEnvelopeRecursive(lambdaErrRes.ast(), lambdaErrSrc);
            var lambdaErrFn = onlyFunction(lambdaErrRes);
            assertEquals(2, lambdaErrFn.body().statements().size());
            var lambdaErrVar = assertInstanceOf(VariableDeclaration.class, lambdaErrFn.body().statements().getFirst());
            var lambdaErrIf = assertInstanceOf(IfStatement.class, lambdaErrFn.body().statements().get(1));
            var lambdaNode = assertInstanceOf(LambdaExpression.class, lambdaErrIf.condition());
            assertInstanceOf(PassStatement.class, lambdaNode.body().statements().getFirst());
            assertInstanceOf(PassStatement.class, lambdaErrIf.body().statements().getFirst());
            assertSame(lambdaErrVar, requireNotNull(
                    findErrorByCode(lambdaErrRes, DiagnosticCodes.SLOT_BINARY_RIGHT).recovery()).owner());
            var lambdaLex = errorsOf(lambdaErrRes).stream()
                    .filter(d -> DiagnosticCodes.LEXICAL_ERROR.equals(d.code()))
                    .toList();
            assertEquals(1, lambdaLex.size());
            assertNotNull(lambdaLex.getFirst().recovery());

            // Finding 2: shorthand node paths ($else, %else, $parent/else, $if, $parent/func()) keep statement head.
            for (var condText : List.of("$else", "%else", "$parent/else", "$if", "$parent/func()")) {
                var nodePathSrc = ("func f():\n\tvar x = (1 +\n\t§ if " + condText + ":\n\t\tpass\n\t)\n")
                        .replace("\n", newline);
                var nodePathRes = recoverableParse(nodePathSrc);
                assertEnvelopeRecursive(nodePathRes.ast(), nodePathSrc);
                var nodePathFn = onlyFunction(nodePathRes);
                assertEquals(2, nodePathFn.body().statements().size());
                var nodePathVar = assertInstanceOf(VariableDeclaration.class, nodePathFn.body().statements().getFirst());
                assertInstanceOf(IfStatement.class, nodePathFn.body().statements().get(1));
                var nodePathBin = assertInstanceOf(BinaryExpression.class, nodePathVar.value());
                assertEquals(CstIssueKind.MISSING,
                        assertInstanceOf(ErrorExpression.class, nodePathBin.right()).kind());
                assertSame(nodePathVar, requireNotNull(
                        findErrorByCode(nodePathRes, DiagnosticCodes.SLOT_BINARY_RIGHT).recovery()).owner());
                var nodePathLex = errorsOf(nodePathRes).stream()
                        .filter(d -> DiagnosticCodes.LEXICAL_ERROR.equals(d.code()))
                        .toList();
                assertEquals(1, nodePathLex.size());
                assertNull(nodePathLex.getFirst().recovery());
            }

            // Finding 3a: matched dict with suite `if ready:\n\t\tpass` or `\t\tfoo()` preserves sibling `IfStatement`.
            for (var bodyText : List.of("pass", "foo()")) {
                var dictStmtSrc = ("func f():\n\tvar x = {1\n\tif ready:\n\t\t" + bodyText + "\n\t}\n")
                        .replace("\n", newline);
                var dictStmtRes = recoverableParse(dictStmtSrc);
                assertEnvelopeRecursive(dictStmtRes.ast(), dictStmtSrc);
                var dictStmtFn = onlyFunction(dictStmtRes);
                assertEquals(2, dictStmtFn.body().statements().size());
                assertInstanceOf(VariableDeclaration.class, dictStmtFn.body().statements().getFirst());
                assertInstanceOf(IfStatement.class, dictStmtFn.body().statements().get(1));
            }

            // Finding 3b: matched dict key `1 +\n§ if ready: 3, 4: 5` stays consistent between Pratt and slot guards.
            var singleDictSrc = "var x = {1 + § if ready: 3, 4: 5}\n".replace("\n", newline);
            var singleDictRes = recoverableParse(singleDictSrc);
            var multiDictSrc = "var x = {1 +\n§ if ready: 3, 4: 5}\n".replace("\n", newline);
            var multiDictRes = recoverableParse(multiDictSrc);
            assertEnvelopeRecursive(multiDictRes.ast(), multiDictSrc);
            assertEquals(
                    errorsOf(singleDictRes).stream().map(AstDiagnostic::code).toList(),
                    errorsOf(multiDictRes).stream().map(AstDiagnostic::code).toList());
            assertTrue(errorsOf(multiDictRes).stream()
                    .noneMatch(d -> DiagnosticCodes.SLOT_BINARY_RIGHT.equals(d.code())
                            || DiagnosticCodes.MISSING_SEPARATOR_DICTIONARY.equals(d.code())));
            var multiDictVar = assertInstanceOf(VariableDeclaration.class, multiDictRes.ast().statements().getFirst());
            var multiDict = assertInstanceOf(DictionaryExpression.class, multiDictVar.value());
            assertEquals(2, multiDict.entries().size());
            var keyCond = assertInstanceOf(ConditionalExpression.class, multiDict.entries().getFirst().key());
            var keyBin = assertInstanceOf(BinaryExpression.class, keyCond.left());
            var keyRhs = assertInstanceOf(ErrorExpression.class, keyBin.right());
            assertEquals(CstIssueKind.ERROR, keyRhs.kind());
            assertEquals("§", utf8Slice(multiDictSrc, keyRhs.range()));
            assertEquals("3", assertInstanceOf(LiteralExpression.class, multiDict.entries().getFirst().value())
                    .sourceText());
            var multiDictLex = errorsOf(multiDictRes).stream()
                    .filter(d -> DiagnosticCodes.LEXICAL_ERROR.equals(d.code()))
                    .toList();
            assertEquals(1, multiDictLex.size());
            assertSame(multiDictVar, requireNotNull(multiDictLex.getFirst().recovery()).owner());
            assertEquals("variable.initializer", requireNotNull(multiDictLex.getFirst().recovery()).facet());

            // Finding 3c: matched dict with `1 +\n\t§ if ready:\n\t\tpass\n\t}` keeps `§` standalone & `IfStatement`.
            var dictIfStmtSrc = "func f():\n\tvar x = {1 +\n\t§ if ready:\n\t\tpass\n\t}\n".replace("\n", newline);
            var dictIfStmtRes = recoverableParse(dictIfStmtSrc);
            assertEnvelopeRecursive(dictIfStmtRes.ast(), dictIfStmtSrc);
            var dictIfFn = onlyFunction(dictIfStmtRes);
            assertEquals(2, dictIfFn.body().statements().size());
            var dictIfVar = assertInstanceOf(VariableDeclaration.class, dictIfFn.body().statements().getFirst());
            assertInstanceOf(IfStatement.class, dictIfFn.body().statements().get(1));
            assertSame(dictIfVar, requireNotNull(
                    findErrorByCode(dictIfStmtRes, DiagnosticCodes.SLOT_BINARY_RIGHT).recovery()).owner());
            var dictIfLex = errorsOf(dictIfStmtRes).stream()
                    .filter(d -> DiagnosticCodes.LEXICAL_ERROR.equals(d.code()))
                    .toList();
            assertEquals(1, dictIfLex.size());
            assertNull(dictIfLex.getFirst().recovery());

            // Finding 4 control: `(1 +\n\t§ \\\n\tif ready:\n\t\tpass\n\t)` still keeps `§` standalone & `IfStatement`.
            var contIfSrc = "func f():\n\tvar x = (1 +\n\t§ \\\n\tif ready:\n\t\tpass\n\t)\n".replace("\n", newline);
            var contIfRes = recoverableParse(contIfSrc);
            assertEnvelopeRecursive(contIfRes.ast(), contIfSrc);
            var contIfFn = onlyFunction(contIfRes);
            assertEquals(2, contIfFn.body().statements().size());
            var contIfVar = assertInstanceOf(VariableDeclaration.class, contIfFn.body().statements().getFirst());
            assertInstanceOf(IfStatement.class, contIfFn.body().statements().get(1));
            assertSame(contIfVar, requireNotNull(
                    findErrorByCode(contIfRes, DiagnosticCodes.SLOT_BINARY_RIGHT).recovery()).owner());
            var contIfLex = errorsOf(contIfRes).stream()
                    .filter(d -> DiagnosticCodes.LEXICAL_ERROR.equals(d.code()))
                    .toList();
            assertEquals(1, contIfLex.size());
            assertNull(contIfLex.getFirst().recovery());
        }
    }

    @Test
    void repeatedContinuedAndMultilineIfInArrayStayLinearAcrossLfAndCrlf() {
        var count = 2000;
        for (var newline : List.of("\n", "\r\n")) {
            var continuedSource = ("var x = [" + "1 \\\nif 1, ".repeat(count) + "]\n").replace("\n", newline);
            var continuedParser = new GdScriptParser();
            var continuedResult = continuedParser.parse(continuedSource);
            assertEnvelopeRecursive(continuedResult.ast(), continuedSource);
            assertEquals(0, continuedParser.statementHeadColonScanSteps());
            var continuedArray = assertInstanceOf(ArrayExpression.class,
                    assertInstanceOf(VariableDeclaration.class, continuedResult.ast().statements().getFirst()).value());
            assertEquals(count, continuedArray.elements().size());

            var newlineSource = ("var x = [" + "1\nif 1, ".repeat(count) + "]\n").replace("\n", newline);
            var newlineParser = new GdScriptParser();
            var newlineResult = newlineParser.parse(newlineSource);
            assertEnvelopeRecursive(newlineResult.ast(), newlineSource);
            assertTrue(newlineParser.statementHeadColonScanSteps() <= 16 * count,
                    () -> "Expected linear scan steps, got " + newlineParser.statementHeadColonScanSteps());
            var newlineArray = assertInstanceOf(ArrayExpression.class,
                    assertInstanceOf(VariableDeclaration.class, newlineResult.ast().statements().getFirst()).value());
            assertEquals(count, newlineArray.elements().size());
        }
    }

    @Test
    void nodePathPrefixAfterOperatorsDictionaryMatchValueAndErrorOnlyRadixLambdaBodyAcrossLfAndCrlf() {
        for (var newline : List.of("\n", "\r\n")) {
            // Finding 1 controls: prefix `%`, `$parent/%else`, `not %else`, `a and %else`, `obj.if()`.
            for (var condText : List.of("$parent/%else", "not %else", "a and %else", "obj.if()")) {
                var nodePathSrc = ("func f():\n\tvar x = (1 +\n\t§ if " + condText + ":\n\t\tpass\n\t)\n")
                        .replace("\n", newline);
                var nodePathRes = recoverableParse(nodePathSrc);
                assertEnvelopeRecursive(nodePathRes.ast(), nodePathSrc);
                var nodePathFn = onlyFunction(nodePathRes);
                assertEquals(2, nodePathFn.body().statements().size());
                var nodePathVar = assertInstanceOf(VariableDeclaration.class, nodePathFn.body().statements().getFirst());
                assertInstanceOf(IfStatement.class, nodePathFn.body().statements().get(1));
                var nodePathBin = assertInstanceOf(BinaryExpression.class, nodePathVar.value());
                assertEquals(CstIssueKind.MISSING,
                        assertInstanceOf(ErrorExpression.class, nodePathBin.right()).kind());
                assertSame(nodePathVar, requireNotNull(
                        findErrorByCode(nodePathRes, DiagnosticCodes.SLOT_BINARY_RIGHT).recovery()).owner());
                var nodePathLex = errorsOf(nodePathRes).stream()
                        .filter(d -> DiagnosticCodes.LEXICAL_ERROR.equals(d.code()))
                        .toList();
                assertEquals(1, nodePathLex.size());
                assertNull(nodePathLex.getFirst().recovery());
            }

            // Finding 2a: `match(0)` value after `:` in matched dictionary stays a dict entry (matches single-line).
            var singleMatchDictSrc = "var x = {1 + § if ready: match(0), 4: 5}\n".replace("\n", newline);
            var singleMatchDictRes = recoverableParse(singleMatchDictSrc);
            var multiMatchDictSrc = "var x = {1 +\n§ if ready: match(0), 4: 5}\n".replace("\n", newline);
            var multiMatchDictRes = recoverableParse(multiMatchDictSrc);
            assertEnvelopeRecursive(multiMatchDictRes.ast(), multiMatchDictSrc);
            assertEquals(
                    errorsOf(singleMatchDictRes).stream().map(AstDiagnostic::code).toList(),
                    errorsOf(multiMatchDictRes).stream().map(AstDiagnostic::code).toList());
            assertTrue(errorsOf(multiMatchDictRes).stream()
                    .noneMatch(d -> DiagnosticCodes.SLOT_BINARY_RIGHT.equals(d.code())
                            || DiagnosticCodes.MISSING_SEPARATOR_DICTIONARY.equals(d.code())));
            var matchDictVar = assertInstanceOf(VariableDeclaration.class,
                    multiMatchDictRes.ast().statements().getFirst());
            var matchDict = assertInstanceOf(DictionaryExpression.class, matchDictVar.value());
            assertEquals(2, matchDict.entries().size());
            var matchKeyCond = assertInstanceOf(ConditionalExpression.class, matchDict.entries().getFirst().key());
            var matchKeyBin = assertInstanceOf(BinaryExpression.class, matchKeyCond.left());
            var matchKeyRhs = assertInstanceOf(ErrorExpression.class, matchKeyBin.right());
            assertEquals(CstIssueKind.ERROR, matchKeyRhs.kind());
            assertEquals("§", utf8Slice(multiMatchDictSrc, matchKeyRhs.range()));
            var matchValCall = assertInstanceOf(CallExpression.class, matchDict.entries().getFirst().value());
            assertEquals("match", assertInstanceOf(IdentifierExpression.class, matchValCall.callee()).name());
            var matchDictLex = errorsOf(multiMatchDictRes).stream()
                    .filter(d -> DiagnosticCodes.LEXICAL_ERROR.equals(d.code()))
                    .toList();
            assertEquals(1, matchDictLex.size());
            assertSame(matchDictVar, requireNotNull(matchDictLex.getFirst().recovery()).owner());
            assertEquals("variable.initializer", requireNotNull(matchDictLex.getFirst().recovery()).facet());

            // Finding 2b: unindented next-line dictionary value after `:` preserves both dict entries.
            var unindentedDictSrc = "var x = {1\nif ready:\n3, 4: 5}\n".replace("\n", newline);
            var unindentedDictRes = recoverableParse(unindentedDictSrc);
            assertEnvelopeRecursive(unindentedDictRes.ast(), unindentedDictSrc);
            assertTrue(errorsOf(unindentedDictRes).stream()
                    .noneMatch(d -> DiagnosticCodes.MISSING_SEPARATOR_DICTIONARY.equals(d.code())));
            var unindentedVar = assertInstanceOf(VariableDeclaration.class,
                    unindentedDictRes.ast().statements().getFirst());
            var unindentedDict = assertInstanceOf(DictionaryExpression.class, unindentedVar.value());
            assertEquals(2, unindentedDict.entries().size());
            assertInstanceOf(ConditionalExpression.class, unindentedDict.entries().getFirst().key());
            assertEquals("3", assertInstanceOf(LiteralExpression.class, unindentedDict.entries().getFirst().value())
                    .sourceText());

            // Finding 2c control: genuinely indented `match z:` body inside matched dict keeps sibling `IfStatement`.
            var indentedMatchSrc = "func f():\n\tvar x = {1\n\tif ready:\n\t\tmatch z:\n\t\t\t1: pass\n\t}\n"
                    .replace("\n", newline);
            var indentedMatchRes = recoverableParse(indentedMatchSrc);
            assertEnvelopeRecursive(indentedMatchRes.ast(), indentedMatchSrc);
            var indentedMatchFn = onlyFunction(indentedMatchRes);
            assertEquals(2, indentedMatchFn.body().statements().size());
            assertInstanceOf(VariableDeclaration.class, indentedMatchFn.body().statements().getFirst());
            var indentedMatchIf = assertInstanceOf(IfStatement.class, indentedMatchFn.body().statements().get(1));
            assertInstanceOf(MatchStatement.class, indentedMatchIf.body().statements().getFirst());

            // Finding 3: error-only radix prefix (`0x`, `0b`, `0x_`, `0b__`) after lambda `:` keeps block lambda.
            for (var errorRadix : List.of("0x", "0b", "0x_", "0b__")) {
                var radixErrSrc = ("func f():\n\tvar x = (1 +\n\tif func(): " + errorRadix
                        + "\n\t\tpass\n\t:\n\t\tpass\n\t)\n").replace("\n", newline);
                var radixErrRes = recoverableParse(radixErrSrc);
                assertEnvelopeRecursive(radixErrRes.ast(), radixErrSrc);
                var radixErrFn = onlyFunction(radixErrRes);
                assertEquals(2, radixErrFn.body().statements().size());
                var radixErrVar = assertInstanceOf(VariableDeclaration.class, radixErrFn.body().statements().getFirst());
                var radixErrIf = assertInstanceOf(IfStatement.class, radixErrFn.body().statements().get(1));
                var radixLambda = assertInstanceOf(LambdaExpression.class, radixErrIf.condition());
                assertInstanceOf(PassStatement.class, radixLambda.body().statements().getFirst());
                assertInstanceOf(PassStatement.class, radixErrIf.body().statements().getFirst());
                assertSame(radixErrVar, requireNotNull(
                        findErrorByCode(radixErrRes, DiagnosticCodes.SLOT_BINARY_RIGHT).recovery()).owner());
                var radixLex = errorsOf(radixErrRes).stream()
                        .filter(d -> DiagnosticCodes.LEXICAL_ERROR.equals(d.code()))
                        .toList();
                assertFalse(radixLex.isEmpty());
                assertTrue(radixLex.stream().allMatch(d -> d.recovery() != null));
            }

            // Finding 3 control: `0x_1` has a valid hex digit, so it stays an inline lambda inside ConditionalExpression.
            var validUnderscoreRadixSrc = "var x = (1\nif func(): 0x_1\nelse 2)\n".replace("\n", newline);
            var validUnderscoreRadixRes = recoverableParse(validUnderscoreRadixSrc);
            assertEnvelopeRecursive(validUnderscoreRadixRes.ast(), validUnderscoreRadixSrc);
            var underscoreVar = assertInstanceOf(VariableDeclaration.class,
                    validUnderscoreRadixRes.ast().statements().getFirst());
            var underscoreCond = assertInstanceOf(ConditionalExpression.class, underscoreVar.value());
            assertInstanceOf(LambdaExpression.class, underscoreCond.condition());
        }
    }

    @Test
    void spacedNodePathIndentedDictContinuationAndSameLineMatchSuiteAcrossLfAndCrlf() {
        for (var newline : List.of("\n", "\r\n")) {
            // Finding 1: spaced shorthand node paths preserve `IfStatement` and keep `§` standalone.
            for (var condText : List.of("$parent / else", "$parent / %else")) {
                var spacedSrc = ("func f():\n\tvar x = (1 +\n\t§ if " + condText + ":\n\t\tpass\n\t)\n")
                        .replace("\n", newline);
                var spacedRes = recoverableParse(spacedSrc);
                assertEnvelopeRecursive(spacedRes.ast(), spacedSrc);
                var spacedFn = onlyFunction(spacedRes);
                assertEquals(2, spacedFn.body().statements().size());
                var spacedVar = assertInstanceOf(VariableDeclaration.class, spacedFn.body().statements().getFirst());
                assertInstanceOf(IfStatement.class, spacedFn.body().statements().get(1));
                assertSame(spacedVar, requireNotNull(
                        findErrorByCode(spacedRes, DiagnosticCodes.SLOT_BINARY_RIGHT).recovery()).owner());
                var spacedLex = errorsOf(spacedRes).stream()
                        .filter(d -> DiagnosticCodes.LEXICAL_ERROR.equals(d.code()))
                        .toList();
                assertEquals(1, spacedLex.size());
                assertNull(spacedLex.getFirst().recovery());
            }

            // Finding 1 control: `$a % func():` leaves node path after segment `a`, stays ternary (2 array items).
            var nodeModSrc = ("func f():\n\tvar x = [1\n\tif $a % func():\n\t\tpass\n\telse 3, 4]\n")
                    .replace("\n", newline);
            var nodeModRes = recoverableParse(nodeModSrc);
            assertEnvelopeRecursive(nodeModRes.ast(), nodeModSrc);
            var nodeModFn = onlyFunction(nodeModRes);
            assertEquals(1, nodeModFn.body().statements().size());
            var nodeModVar = assertInstanceOf(VariableDeclaration.class, nodeModFn.body().statements().getFirst());
            var nodeModArr = assertInstanceOf(ArrayExpression.class, nodeModVar.value());
            assertEquals(2, nodeModArr.elements().size());
            var nodeModCond = assertInstanceOf(ConditionalExpression.class, nodeModArr.elements().getFirst());
            assertInstanceOf(LambdaExpression.class, nodeModCond.right());
            assertEquals("4", assertInstanceOf(LiteralExpression.class, nodeModArr.elements().get(1)).sourceText());

            // Finding 3a: indented dictionary continuation (`\t3, 4: 5}`) keeps both entries.
            var indentedDictContSrc = "var x = {1\nif ready:\n\t3, 4: 5}\n".replace("\n", newline);
            var indentedDictContRes = recoverableParse(indentedDictContSrc);
            assertEnvelopeRecursive(indentedDictContRes.ast(), indentedDictContSrc);
            assertTrue(errorsOf(indentedDictContRes).stream()
                    .noneMatch(d -> DiagnosticCodes.MISSING_SEPARATOR_DICTIONARY.equals(d.code())));
            var indentedDictVar = assertInstanceOf(VariableDeclaration.class,
                    indentedDictContRes.ast().statements().getFirst());
            var indentedDict = assertInstanceOf(DictionaryExpression.class, indentedDictVar.value());
            assertEquals(2, indentedDict.entries().size());
            assertInstanceOf(ConditionalExpression.class, indentedDict.entries().getFirst().key());
            assertEquals("3", assertInstanceOf(LiteralExpression.class, indentedDict.entries().getFirst().value())
                    .sourceText());

            // Finding 3b: same-line `match + 1` in dict stays a dict entry matching single-line control.
            var singleMatchBinSrc = "var x = {1 + § if ready: match + 1, 4: 5}\n".replace("\n", newline);
            var singleMatchBinRes = recoverableParse(singleMatchBinSrc);
            var multiMatchBinSrc = "var x = {1 +\n§ if ready: match + 1, 4: 5}\n".replace("\n", newline);
            var multiMatchBinRes = recoverableParse(multiMatchBinSrc);
            assertEnvelopeRecursive(multiMatchBinRes.ast(), multiMatchBinSrc);
            assertEquals(
                    errorsOf(singleMatchBinRes).stream().map(AstDiagnostic::code).toList(),
                    errorsOf(multiMatchBinRes).stream().map(AstDiagnostic::code).toList());
            var matchBinVar = assertInstanceOf(VariableDeclaration.class,
                    multiMatchBinRes.ast().statements().getFirst());
            var matchBinDict = assertInstanceOf(DictionaryExpression.class, matchBinVar.value());
            assertEquals(2, matchBinDict.entries().size());
            assertInstanceOf(BinaryExpression.class, matchBinDict.entries().getFirst().value());

            // Finding 3c: same-line `if ready: match z:` inside matched dict keeps sibling `IfStatement(MatchStatement)`.
            var sameLineMatchSuiteSrc = "func f():\n\tvar x = {1\n\tif ready: match z:\n\t\t1: pass\n\t}\n"
                    .replace("\n", newline);
            var sameLineMatchSuiteRes = recoverableParse(sameLineMatchSuiteSrc);
            assertEnvelopeRecursive(sameLineMatchSuiteRes.ast(), sameLineMatchSuiteSrc);
            var sameLineMatchFn = onlyFunction(sameLineMatchSuiteRes);
            assertEquals(2, sameLineMatchFn.body().statements().size());
            assertInstanceOf(VariableDeclaration.class, sameLineMatchFn.body().statements().getFirst());
            var sameLineMatchIf = assertInstanceOf(IfStatement.class, sameLineMatchFn.body().statements().get(1));
            assertInstanceOf(MatchStatement.class, sameLineMatchIf.body().statements().getFirst());

            // Finding 3d: indented call with nested comma (`\t\tprint(1, 2)`) keeps sibling `IfStatement`.
            var callSuiteSrc = "func f():\n\tvar x = {1\n\tif ready:\n\t\tprint(1, 2)\n\t}\n"
                    .replace("\n", newline);
            var callSuiteRes = recoverableParse(callSuiteSrc);
            assertEnvelopeRecursive(callSuiteRes.ast(), callSuiteSrc);
            var callSuiteFn = onlyFunction(callSuiteRes);
            assertEquals(2, callSuiteFn.body().statements().size());
            assertInstanceOf(VariableDeclaration.class, callSuiteFn.body().statements().getFirst());
            var callSuiteIf = assertInstanceOf(IfStatement.class, callSuiteFn.body().statements().get(1));
            var callStmt = assertInstanceOf(ExpressionStatement.class, callSuiteIf.body().statements().getFirst());
            assertInstanceOf(CallExpression.class, callStmt.expression());
        }
    }

    @Test
    void groupedMatchSuiteAndContinuedDictionaryCommaAcrossLfAndCrlf() {
        for (var newline : List.of("\n", "\r\n")) {
            // Finding 1: `match (z):` and `match [z]:` preserve sibling `IfStatement(MatchStatement)`.
            for (var subject : List.of("(z)", "[z]")) {
                var matchSuiteSrc = ("func f():\n\tvar x = {1\n\tif ready: match " + subject
                        + ":\n\t\t1: pass\n\t}\n").replace("\n", newline);
                var matchSuiteRes = recoverableParse(matchSuiteSrc);
                assertEnvelopeRecursive(matchSuiteRes.ast(), matchSuiteSrc);
                var matchSuiteFn = onlyFunction(matchSuiteRes);
                assertEquals(2, matchSuiteFn.body().statements().size());
                assertInstanceOf(VariableDeclaration.class, matchSuiteFn.body().statements().getFirst());
                var matchSuiteIf = assertInstanceOf(IfStatement.class, matchSuiteFn.body().statements().get(1));
                assertInstanceOf(MatchStatement.class, matchSuiteIf.body().statements().getFirst());
            }

            // Finding 1 control: `match[0], 4: 5}` keeps both dictionary entries matching single-line control.
            var singleMatchSubscriptSrc = "var x = {1 + § if ready: match[0], 4: 5}\n".replace("\n", newline);
            var singleMatchSubscriptRes = recoverableParse(singleMatchSubscriptSrc);
            var multiMatchSubscriptSrc = "var x = {1 +\n§ if ready: match[0], 4: 5}\n".replace("\n", newline);
            var multiMatchSubscriptRes = recoverableParse(multiMatchSubscriptSrc);
            assertEnvelopeRecursive(multiMatchSubscriptRes.ast(), multiMatchSubscriptSrc);
            assertEquals(
                    errorsOf(singleMatchSubscriptRes).stream().map(AstDiagnostic::code).toList(),
                    errorsOf(multiMatchSubscriptRes).stream().map(AstDiagnostic::code).toList());
            var matchSubscriptVar = assertInstanceOf(VariableDeclaration.class,
                    multiMatchSubscriptRes.ast().statements().getFirst());
            var matchSubscriptDict = assertInstanceOf(DictionaryExpression.class, matchSubscriptVar.value());
            assertEquals(2, matchSubscriptDict.entries().size());
            assertInstanceOf(SubscriptExpression.class, matchSubscriptDict.entries().getFirst().value());

            // Finding 2: explicit `\` continuation before `,` or right after `:` preserves both dict entries.
            for (var contDictSrc : List.of(
                    "var x = {1\nif ready:\n\t3 \\\n\t, 4: 5}\n",
                    "var x = {1\nif ready: \\\n\t3, 4: 5}\n")) {
                var normalized = contDictSrc.replace("\n", newline);
                var contDictRes = recoverableParse(normalized);
                assertEnvelopeRecursive(contDictRes.ast(), normalized);
                assertEquals(List.of("Expected \"else\" after ternary operator condition."),
                        errorsOf(contDictRes).stream().map(AstDiagnostic::message).toList());
                var contVar = assertInstanceOf(VariableDeclaration.class, contDictRes.ast().statements().getFirst());
                var contDict = assertInstanceOf(DictionaryExpression.class, contVar.value());
                assertEquals(2, contDict.entries().size());
                assertInstanceOf(ConditionalExpression.class, contDict.entries().getFirst().key());
                assertEquals("3", assertInstanceOf(LiteralExpression.class, contDict.entries().getFirst().value())
                        .sourceText());
                assertEquals("4", assertInstanceOf(LiteralExpression.class, contDict.entries().get(1).key())
                        .sourceText());
                assertEquals("5", assertInstanceOf(LiteralExpression.class, contDict.entries().get(1).value())
                        .sourceText());
            }
        }
    }

    @Test
    void decimalMatchSubjectClosedSingleEntryDictValueAndHeaderColonLexicalAttributionAcrossLfAndCrlf() {
        for (var newline : List.of("\n", "\r\n")) {
            // Finding 1: `match .5:` keeps sibling `IfStatement(MatchStatement)`.
            var decimalMatchSrc = "func f():\n\tvar x = {1\n\tif ready: match .5:\n\t\t1: pass\n\t}\n"
                    .replace("\n", newline);
            var decimalMatchRes = recoverableParse(decimalMatchSrc);
            assertEnvelopeRecursive(decimalMatchRes.ast(), decimalMatchSrc);
            var decimalMatchFn = onlyFunction(decimalMatchRes);
            assertEquals(2, decimalMatchFn.body().statements().size());
            assertInstanceOf(VariableDeclaration.class, decimalMatchFn.body().statements().getFirst());
            var decimalMatchIf = assertInstanceOf(IfStatement.class, decimalMatchFn.body().statements().get(1));
            var decimalMatchStmt = assertInstanceOf(MatchStatement.class, decimalMatchIf.body().statements().getFirst());
            assertEquals(".5", assertInstanceOf(LiteralExpression.class, decimalMatchStmt.value()).sourceText());

            // Finding 1 controls: `match.member, 4: 5}` and `match.member}` stay dictionary values.
            var memberTwoEntrySrc = "var x = {1\nif ready: match.member, 4: 5}\n".replace("\n", newline);
            var memberTwoEntryRes = recoverableParse(memberTwoEntrySrc);
            assertEnvelopeRecursive(memberTwoEntryRes.ast(), memberTwoEntrySrc);
            var memberTwoDict = assertInstanceOf(DictionaryExpression.class,
                    assertInstanceOf(VariableDeclaration.class, memberTwoEntryRes.ast().statements().getFirst())
                            .value());
            assertEquals(2, memberTwoDict.entries().size());
            assertInstanceOf(AttributeExpression.class, memberTwoDict.entries().getFirst().value());

            var memberOneEntrySrc = "var x = {1\nif ready: match.member}\n".replace("\n", newline);
            var memberOneEntryRes = recoverableParse(memberOneEntrySrc);
            assertEnvelopeRecursive(memberOneEntryRes.ast(), memberOneEntrySrc);
            assertEquals(List.of("Expected \"else\" after ternary operator condition."),
                    errorsOf(memberOneEntryRes).stream().map(AstDiagnostic::message).toList());
            var memberOneDict = assertInstanceOf(DictionaryExpression.class,
                    assertInstanceOf(VariableDeclaration.class, memberOneEntryRes.ast().statements().getFirst())
                            .value());
            assertEquals(1, memberOneDict.entries().size());
            assertInstanceOf(AttributeExpression.class, memberOneDict.entries().getFirst().value());

            // Finding 2: `{1\nif ready:\n\t3}`, `{1\nif ready:\n\t3 \\\n\t}`, `{1\nif ready:\n\t{2: 3}}` keep value.
            for (var singleEntrySrc : List.of(
                    "var x = {1\nif ready:\n\t3}\n",
                    "var x = {1\nif ready:\n\t3 \\\n\t}\n",
                    "var x = {1\nif ready:\n\t{2: 3}}\n")) {
                var normalized = singleEntrySrc.replace("\n", newline);
                var singleRes = recoverableParse(normalized);
                assertEnvelopeRecursive(singleRes.ast(), normalized);
                assertEquals(List.of("Expected \"else\" after ternary operator condition."),
                        errorsOf(singleRes).stream().map(AstDiagnostic::message).toList());
                var singleVar = assertInstanceOf(VariableDeclaration.class, singleRes.ast().statements().getFirst());
                var singleDict = assertInstanceOf(DictionaryExpression.class, singleVar.value());
                assertEquals(1, singleDict.entries().size());
                assertInstanceOf(ConditionalExpression.class, singleDict.entries().getFirst().key());
            }

            // Finding 2 control: `{1\n\tif ready:\n\t\tfoo()\n\t}` still keeps sibling `IfStatement`.
            var fooControlSrc = "func f():\n\tvar x = {1\n\tif ready:\n\t\tfoo()\n\t}\n".replace("\n", newline);
            var fooControlRes = recoverableParse(fooControlSrc);
            assertEnvelopeRecursive(fooControlRes.ast(), fooControlSrc);
            var fooFn = onlyFunction(fooControlRes);
            assertEquals(2, fooFn.body().statements().size());
            assertInstanceOf(IfStatement.class, fooFn.body().statements().get(1));

            // Finding 3: lexical error before `:` in `match` (standalone & inside dict) attributes to `match.value`.
            var matchLexSrc = "func f():\n\tif ready: match (z) §:\n\t\t1: pass\n".replace("\n", newline);
            var matchLexRes = recoverableParse(matchLexSrc);
            assertEnvelopeRecursive(matchLexRes.ast(), matchLexSrc);
            var matchLexFn = onlyFunction(matchLexRes);
            var matchLexIf = assertInstanceOf(IfStatement.class, matchLexFn.body().statements().getFirst());
            var matchLexStmt = assertInstanceOf(MatchStatement.class, matchLexIf.body().statements().getFirst());
            var matchLexDiag = findErrorByCode(matchLexRes, DiagnosticCodes.LEXICAL_ERROR);
            assertSame(matchLexStmt, requireNotNull(matchLexDiag.recovery()).owner());
            assertEquals("match.value", requireNotNull(matchLexDiag.recovery()).facet());

            var dictMatchLexSrc = "func f():\n\tvar x = {1\n\tif ready: match (z) §:\n\t\t1: pass\n\t}\n"
                    .replace("\n", newline);
            var dictMatchLexRes = recoverableParse(dictMatchLexSrc);
            assertEnvelopeRecursive(dictMatchLexRes.ast(), dictMatchLexSrc);
            var dictMatchFn = onlyFunction(dictMatchLexRes);
            assertEquals(2, dictMatchFn.body().statements().size());
            var dictMatchIf = assertInstanceOf(IfStatement.class, dictMatchFn.body().statements().get(1));
            var dictMatchStmt = assertInstanceOf(MatchStatement.class, dictMatchIf.body().statements().getFirst());
            var dictMatchLexDiag = findErrorByCode(dictMatchLexRes, DiagnosticCodes.LEXICAL_ERROR);
            assertSame(dictMatchStmt, requireNotNull(dictMatchLexDiag.recovery()).owner());
            assertEquals("match.value", requireNotNull(dictMatchLexDiag.recovery()).facet());

            // Finding 3 sibling control headers: `if`, `elif`, `while`, `for ... in ...:`, `match` section/guard.
            var ifElifLexSrc = "func f():\n\tif cond §:\n\t\tpass\n\telif alt ¤:\n\t\tpass\n"
                    .replace("\n", newline);
            var ifElifLexRes = recoverableParse(ifElifLexSrc);
            assertEnvelopeRecursive(ifElifLexRes.ast(), ifElifLexSrc);
            var ifElifStmt = assertInstanceOf(IfStatement.class,
                    onlyFunction(ifElifLexRes).body().statements().getFirst());
            var ifElifLexDiags = errorsOf(ifElifLexRes).stream()
                    .filter(d -> DiagnosticCodes.LEXICAL_ERROR.equals(d.code()))
                    .toList();
            assertEquals(2, ifElifLexDiags.size());
            assertSame(ifElifStmt, requireNotNull(ifElifLexDiags.getFirst().recovery()).owner());
            assertEquals("if.condition", requireNotNull(ifElifLexDiags.getFirst().recovery()).facet());
            assertSame(ifElifStmt.elifClauses().getFirst(),
                    requireNotNull(ifElifLexDiags.get(1).recovery()).owner());
            assertEquals("elif.condition", requireNotNull(ifElifLexDiags.get(1).recovery()).facet());

            var whileLexSrc = "func f():\n\twhile cond §:\n\t\tpass\n".replace("\n", newline);
            var whileLexRes = recoverableParse(whileLexSrc);
            assertEnvelopeRecursive(whileLexRes.ast(), whileLexSrc);
            var whileLexStmt = assertInstanceOf(WhileStatement.class,
                    onlyFunction(whileLexRes).body().statements().getFirst());
            var whileLexDiag = findErrorByCode(whileLexRes, DiagnosticCodes.LEXICAL_ERROR);
            assertSame(whileLexStmt, requireNotNull(whileLexDiag.recovery()).owner());
            assertEquals("while.condition", requireNotNull(whileLexDiag.recovery()).facet());

            var forLexSrc = "func f():\n\tfor x § in xs ¤:\n\t\tpass\n".replace("\n", newline);
            var forLexRes = recoverableParse(forLexSrc);
            assertEnvelopeRecursive(forLexRes.ast(), forLexSrc);
            var forLexStmt = assertInstanceOf(ForStatement.class,
                    onlyFunction(forLexRes).body().statements().getFirst());
            var forLexDiags = errorsOf(forLexRes).stream()
                    .filter(d -> DiagnosticCodes.LEXICAL_ERROR.equals(d.code()))
                    .toList();
            assertEquals(2, forLexDiags.size());
            assertSame(forLexStmt, requireNotNull(forLexDiags.getFirst().recovery()).owner());
            assertEquals("for.iterator", requireNotNull(forLexDiags.getFirst().recovery()).facet());
            assertSame(forLexStmt, requireNotNull(forLexDiags.get(1).recovery()).owner());
            assertEquals("for.iterable", requireNotNull(forLexDiags.get(1).recovery()).facet());

            var sectionLexSrc = "func f():\n\tmatch z:\n\t\t1 §:\n\t\t\tpass\n\t\t2 when ok ¤:\n\t\t\tpass\n"
                    .replace("\n", newline);
            var sectionLexRes = recoverableParse(sectionLexSrc);
            assertEnvelopeRecursive(sectionLexRes.ast(), sectionLexSrc);
            var sectionMatch = assertInstanceOf(MatchStatement.class,
                    onlyFunction(sectionLexRes).body().statements().getFirst());
            var sectionLexDiags = errorsOf(sectionLexRes).stream()
                    .filter(d -> DiagnosticCodes.LEXICAL_ERROR.equals(d.code()))
                    .toList();
            assertEquals(2, sectionLexDiags.size());
            assertSame(sectionMatch.sections().getFirst(),
                    requireNotNull(sectionLexDiags.getFirst().recovery()).owner());
            assertEquals("match.section", requireNotNull(sectionLexDiags.getFirst().recovery()).facet());
            assertSame(sectionMatch.sections().get(1),
                    requireNotNull(sectionLexDiags.get(1).recovery()).owner());
            assertEquals("match.section", requireNotNull(sectionLexDiags.get(1).recovery()).facet());
        }
    }

    @Test
    void dictSuiteBodyAssignmentMatchColonErrorAndSuiteHeaderLexicalOwnershipAcrossLfAndCrlf() {
        for (var newline : List.of("\n", "\r\n")) {
            // Finding 1: `§ pass}`, `y = 2}`, `y += 2}` inside matched `{` preserve sibling `IfStatement`.
            var errPassSrc = "func f():\n\tvar x = {1\n\tif ready:\n\t\t§ pass}\n".replace("\n", newline);
            var errPassRes = recoverableParse(errPassSrc);
            assertEnvelopeRecursive(errPassRes.ast(), errPassSrc);
            var errPassFn = onlyFunction(errPassRes);
            assertEquals(2, errPassFn.body().statements().size());
            var errPassIf = assertInstanceOf(IfStatement.class, errPassFn.body().statements().get(1));
            assertInstanceOf(PassStatement.class, errPassIf.body().statements().getFirst());
            assertNotNull(findErrorByCode(errPassRes, DiagnosticCodes.LEXICAL_ERROR));

            for (var assignBody : List.of("y = 2", "y += 2")) {
                var assignSrc = ("func f():\n\tvar x = {1\n\tif ready:\n\t\t" + assignBody + "}\n")
                        .replace("\n", newline);
                var assignRes = recoverableParse(assignSrc);
                assertEnvelopeRecursive(assignRes.ast(), assignSrc);
                var assignFn = onlyFunction(assignRes);
                assertEquals(2, assignFn.body().statements().size());
                var assignIf = assertInstanceOf(IfStatement.class, assignFn.body().statements().get(1));
                var assignStmt = assertInstanceOf(ExpressionStatement.class, assignIf.body().statements().getFirst());
                assertInstanceOf(AssignmentExpression.class, assignStmt.expression());
            }

            // Finding 1 control: `{1\nif ready:\n\t3 == 4}` keeps dictionary value.
            var eqCtrlSrc = "var x = {1\nif ready:\n\t3 == 4}\n".replace("\n", newline);
            var eqCtrlRes = recoverableParse(eqCtrlSrc);
            assertEnvelopeRecursive(eqCtrlRes.ast(), eqCtrlSrc);
            assertEquals(List.of("Expected \"else\" after ternary operator condition."),
                    errorsOf(eqCtrlRes).stream().map(AstDiagnostic::message).toList());
            var eqCtrlDict = assertInstanceOf(DictionaryExpression.class,
                    assertInstanceOf(VariableDeclaration.class, eqCtrlRes.ast().statements().getFirst()).value());
            assertEquals(1, eqCtrlDict.entries().size());
            assertInstanceOf(BinaryExpression.class, eqCtrlDict.entries().getFirst().value());

            // Finding 2: `match .5: §` and `match .5: § # c` (and malformed `\` before `§`) keep `IfStatement(MatchStatement)`.
            for (var matchTail : List.of("§", "§ # c", "\\ §")) {
                var matchErrSrc = ("func f():\n\tvar x = {1\n\tif ready: match .5: " + matchTail
                        + "\n\t\t1: pass\n\t}\n").replace("\n", newline);
                var matchErrRes = recoverableParse(matchErrSrc);
                assertEnvelopeRecursive(matchErrRes.ast(), matchErrSrc);
                var matchErrFn = onlyFunction(matchErrRes);
                assertEquals(2, matchErrFn.body().statements().size());
                var matchErrIf = assertInstanceOf(IfStatement.class, matchErrFn.body().statements().get(1));
                var matchErrStmt = assertInstanceOf(MatchStatement.class, matchErrIf.body().statements().getFirst());
                assertEquals(".5", assertInstanceOf(LiteralExpression.class, matchErrStmt.value()).sourceText());
                assertEquals(1, matchErrStmt.sections().size());
            }

            // Finding 2 negative control: valid `\` continuation after `match .5:` matches single-line dict entries.
            var singleContNegSrc = "var x = {1\nif ready: match .5: 1, 4: 5}\n".replace("\n", newline);
            var singleContNegRes = recoverableParse(singleContNegSrc);
            var contNegSrc = "var x = {1\nif ready: match .5: \\\n\t1, 4: 5}\n".replace("\n", newline);
            var contNegRes = recoverableParse(contNegSrc);
            assertEnvelopeRecursive(contNegRes.ast(), contNegSrc);
            assertEquals(
                    errorsOf(singleContNegRes).stream().map(AstDiagnostic::code).toList(),
                    errorsOf(contNegRes).stream().map(AstDiagnostic::code).toList());
            var contNegDict = assertInstanceOf(DictionaryExpression.class,
                    assertInstanceOf(VariableDeclaration.class, contNegRes.ast().statements().getFirst()).value());
            assertEquals(3, contNegDict.entries().size());

            // Finding 3: header-line lexical errors across all suite/control headers keep exact owner and facet.
            var elsePreColonSrc = "func f():\n\tif ready:\n\t\tpass\n\telse §:\n\t\tpass\n".replace("\n", newline);
            var elsePreColonRes = recoverableParse(elsePreColonSrc);
            assertEnvelopeRecursive(elsePreColonRes.ast(), elsePreColonSrc);
            var elsePreColonIf = assertInstanceOf(IfStatement.class,
                    onlyFunction(elsePreColonRes).body().statements().getFirst());
            var elsePreColonDiag = findErrorByCode(elsePreColonRes, DiagnosticCodes.LEXICAL_ERROR);
            assertSame(elsePreColonIf, requireNotNull(elsePreColonDiag.recovery()).owner());
            assertEquals("suite.body", requireNotNull(elsePreColonDiag.recovery()).facet());

            var matchHeaderSrc = "func f():\n\tmatch z: §\n\t\t1: pass\n".replace("\n", newline);
            var matchHeaderRes = recoverableParse(matchHeaderSrc);
            assertEnvelopeRecursive(matchHeaderRes.ast(), matchHeaderSrc);
            var matchHeaderStmt = assertInstanceOf(MatchStatement.class,
                    onlyFunction(matchHeaderRes).body().statements().getFirst());
            var matchHeaderDiag = findErrorByCode(matchHeaderRes, DiagnosticCodes.LEXICAL_ERROR);
            assertSame(matchHeaderStmt, requireNotNull(matchHeaderDiag.recovery()).owner());
            assertEquals("match.section", requireNotNull(matchHeaderDiag.recovery()).facet());

            var matchEarlySrc = "func f():\n\tmatch z: §\n\tpass\n".replace("\n", newline);
            var matchEarlyRes = recoverableParse(matchEarlySrc);
            assertEnvelopeRecursive(matchEarlyRes.ast(), matchEarlySrc);
            var matchEarlyStmt = assertInstanceOf(MatchStatement.class,
                    onlyFunction(matchEarlyRes).body().statements().getFirst());
            var matchEarlyDiag = findErrorByCode(matchEarlyRes, DiagnosticCodes.LEXICAL_ERROR);
            assertSame(matchEarlyStmt, requireNotNull(matchEarlyDiag.recovery()).owner());
            assertEquals("match.section", requireNotNull(matchEarlyDiag.recovery()).facet());

            var ifHeaderSrc = "func f():\n\tif ready: §\n\t\tpass\n".replace("\n", newline);
            var ifHeaderRes = recoverableParse(ifHeaderSrc);
            assertEnvelopeRecursive(ifHeaderRes.ast(), ifHeaderSrc);
            var ifHeaderStmt = assertInstanceOf(IfStatement.class,
                    onlyFunction(ifHeaderRes).body().statements().getFirst());
            var ifHeaderDiag = findErrorByCode(ifHeaderRes, DiagnosticCodes.LEXICAL_ERROR);
            assertSame(ifHeaderStmt, requireNotNull(ifHeaderDiag.recovery()).owner());
            assertEquals("suite.body", requireNotNull(ifHeaderDiag.recovery()).facet());

            var elifHeaderSrc = "func f():\n\tif ready:\n\t\tpass\n\telif ok: §\n\t\tpass\n".replace("\n", newline);
            var elifHeaderRes = recoverableParse(elifHeaderSrc);
            assertEnvelopeRecursive(elifHeaderRes.ast(), elifHeaderSrc);
            var elifHeaderIf = assertInstanceOf(IfStatement.class,
                    onlyFunction(elifHeaderRes).body().statements().getFirst());
            var elifHeaderDiag = findErrorByCode(elifHeaderRes, DiagnosticCodes.LEXICAL_ERROR);
            assertSame(elifHeaderIf.elifClauses().getFirst(), requireNotNull(elifHeaderDiag.recovery()).owner());
            assertEquals("suite.body", requireNotNull(elifHeaderDiag.recovery()).facet());

            var elseHeaderSrc = "func f():\n\tif ready:\n\t\tpass\n\telse: §\n\t\tpass\n".replace("\n", newline);
            var elseHeaderRes = recoverableParse(elseHeaderSrc);
            assertEnvelopeRecursive(elseHeaderRes.ast(), elseHeaderSrc);
            var elseHeaderIf = assertInstanceOf(IfStatement.class,
                    onlyFunction(elseHeaderRes).body().statements().getFirst());
            var elseHeaderDiag = findErrorByCode(elseHeaderRes, DiagnosticCodes.LEXICAL_ERROR);
            assertSame(elseHeaderIf, requireNotNull(elseHeaderDiag.recovery()).owner());
            assertEquals("suite.body", requireNotNull(elseHeaderDiag.recovery()).facet());

            var whileHeaderSrc = "func f():\n\twhile ok: §\n\t\tpass\n".replace("\n", newline);
            var whileHeaderRes = recoverableParse(whileHeaderSrc);
            assertEnvelopeRecursive(whileHeaderRes.ast(), whileHeaderSrc);
            var whileHeaderStmt = assertInstanceOf(WhileStatement.class,
                    onlyFunction(whileHeaderRes).body().statements().getFirst());
            var whileHeaderDiag = findErrorByCode(whileHeaderRes, DiagnosticCodes.LEXICAL_ERROR);
            assertSame(whileHeaderStmt, requireNotNull(whileHeaderDiag.recovery()).owner());
            assertEquals("suite.body", requireNotNull(whileHeaderDiag.recovery()).facet());

            var forHeaderSrc = "func f():\n\tfor x in xs: §\n\t\tpass\n".replace("\n", newline);
            var forHeaderRes = recoverableParse(forHeaderSrc);
            assertEnvelopeRecursive(forHeaderRes.ast(), forHeaderSrc);
            var forHeaderStmt = assertInstanceOf(ForStatement.class,
                    onlyFunction(forHeaderRes).body().statements().getFirst());
            var forHeaderDiag = findErrorByCode(forHeaderRes, DiagnosticCodes.LEXICAL_ERROR);
            assertSame(forHeaderStmt, requireNotNull(forHeaderDiag.recovery()).owner());
            assertEquals("suite.body", requireNotNull(forHeaderDiag.recovery()).facet());

            var funcHeaderSrc = "func f(): §\n\tpass\n".replace("\n", newline);
            var funcHeaderRes = recoverableParse(funcHeaderSrc);
            assertEnvelopeRecursive(funcHeaderRes.ast(), funcHeaderSrc);
            var funcHeaderDecl = onlyFunction(funcHeaderRes);
            var funcHeaderDiag = findErrorByCode(funcHeaderRes, DiagnosticCodes.LEXICAL_ERROR);
            assertSame(funcHeaderDecl, requireNotNull(funcHeaderDiag.recovery()).owner());
            assertEquals("suite.body", requireNotNull(funcHeaderDiag.recovery()).facet());

            var classHeaderSrc = "class A: §\n\tpass\n".replace("\n", newline);
            var classHeaderRes = recoverableParse(classHeaderSrc);
            assertEnvelopeRecursive(classHeaderRes.ast(), classHeaderSrc);
            var classHeaderDecl = assertInstanceOf(ClassDeclaration.class,
                    classHeaderRes.ast().statements().getFirst());
            var classHeaderDiag = findErrorByCode(classHeaderRes, DiagnosticCodes.LEXICAL_ERROR);
            assertSame(classHeaderDecl, requireNotNull(classHeaderDiag.recovery()).owner());
            assertEquals("class.body", requireNotNull(classHeaderDiag.recovery()).facet());

            var matchSecHeaderSrc = "func f():\n\tmatch z:\n\t\t1: §\n\t\t\tpass\n".replace("\n", newline);
            var matchSecHeaderRes = recoverableParse(matchSecHeaderSrc);
            assertEnvelopeRecursive(matchSecHeaderRes.ast(), matchSecHeaderSrc);
            var matchSecHeaderMatch = assertInstanceOf(MatchStatement.class,
                    onlyFunction(matchSecHeaderRes).body().statements().getFirst());
            var matchSecHeaderDiag = findErrorByCode(matchSecHeaderRes, DiagnosticCodes.LEXICAL_ERROR);
            assertSame(matchSecHeaderMatch.sections().getFirst(),
                    requireNotNull(matchSecHeaderDiag.recovery()).owner());
            assertEquals("suite.body", requireNotNull(matchSecHeaderDiag.recovery()).facet());

            var propHeaderSrc = "var p:\n\tget: §\n\t\treturn 1\n".replace("\n", newline);
            var propHeaderRes = recoverableParse(propHeaderSrc);
            assertEnvelopeRecursive(propHeaderRes.ast(), propHeaderSrc);
            var propHeaderVar = assertInstanceOf(VariableDeclaration.class,
                    propHeaderRes.ast().statements().getFirst());
            var propHeaderDiag = findErrorByCode(propHeaderRes, DiagnosticCodes.LEXICAL_ERROR);
            assertSame(propHeaderVar, requireNotNull(propHeaderDiag.recovery()).owner());
            assertEquals("variable.property", requireNotNull(propHeaderDiag.recovery()).facet());

            // Finding 3 negative controls: next-physical-line/dedented lexical errors must not be claimed by header.
            var matchNextLineSrc = "func f():\n\tmatch z:\n§\n\t1: pass\n".replace("\n", newline);
            var matchNextLineRes = recoverableParse(matchNextLineSrc);
            assertEnvelopeRecursive(matchNextLineRes.ast(), matchNextLineSrc);
            var matchNextLineStmt = assertInstanceOf(MatchStatement.class,
                    onlyFunction(matchNextLineRes).body().statements().getFirst());
            var matchNextLineDiag = findErrorByCode(matchNextLineRes, DiagnosticCodes.LEXICAL_ERROR);
            if (matchNextLineDiag.recovery() != null) {
                assertFalse(matchNextLineStmt == matchNextLineDiag.recovery().owner());
            }

            var ifNextLineSrc = "func f():\n\tif ready:\n\t\tpass\n§\n".replace("\n", newline);
            var ifNextLineRes = recoverableParse(ifNextLineSrc);
            assertEnvelopeRecursive(ifNextLineRes.ast(), ifNextLineSrc);
            var ifNextLineStmt = assertInstanceOf(IfStatement.class,
                    onlyFunction(ifNextLineRes).body().statements().getFirst());
            var ifNextLineDiag = findErrorByCode(ifNextLineRes, DiagnosticCodes.LEXICAL_ERROR);
            if (ifNextLineDiag.recovery() != null) {
                assertFalse(ifNextLineStmt == ifNextLineDiag.recovery().owner());
            }
        }
    }

    @Test
    void dictInlineLambdaAssignmentAndSingleLineSuiteHeaderLexicalOwnershipAcrossLfAndCrlf() {
        for (var newline : List.of("\n", "\r\n")) {
            // Finding 1: inline lambda body assignment in dict keeps conditional key + lambda value.
            for (var lambdaExpr : List.of(
                    "func(): y = 2",
                    "func named(a = 1) -> void: y = 2",
                    "1 + func(): y = 2")) {
                var dictLambdaSrc = ("var x = {1\nif ready:\n\t" + lambdaExpr + "}\n").replace("\n", newline);
                var dictLambdaRes = recoverableParse(dictLambdaSrc);
                assertEnvelopeRecursive(dictLambdaRes.ast(), dictLambdaSrc);
                assertEquals(List.of("Expected \"else\" after ternary operator condition."),
                        errorsOf(dictLambdaRes).stream().map(AstDiagnostic::message).toList());
                var dictLambdaVar = assertInstanceOf(VariableDeclaration.class,
                        dictLambdaRes.ast().statements().getFirst());
                var dictLambda = assertInstanceOf(DictionaryExpression.class, dictLambdaVar.value());
                assertEquals(1, dictLambda.entries().size());
                assertInstanceOf(ConditionalExpression.class, dictLambda.entries().getFirst().key());
                if (lambdaExpr.startsWith("1 + ")) {
                    var bin = assertInstanceOf(BinaryExpression.class, dictLambda.entries().getFirst().value());
                    assertInstanceOf(LambdaExpression.class, bin.right());
                } else {
                    assertInstanceOf(LambdaExpression.class, dictLambda.entries().getFirst().value());
                }
            }

            // Finding 1 controls: outer assignment (including before/member of `func`) preserves sibling `IfStatement`.
            for (var assignStmt : List.of("y = 2", "y = func(): z = 3", "func.x = 2", "obj.func = 2")) {
                var outerAssignSrc = ("func f():\n\tvar x = {1\n\tif ready:\n\t\t" + assignStmt + "}\n")
                        .replace("\n", newline);
                var outerAssignRes = recoverableParse(outerAssignSrc);
                assertEnvelopeRecursive(outerAssignRes.ast(), outerAssignSrc);
                var outerAssignFn = onlyFunction(outerAssignRes);
                assertEquals(2, outerAssignFn.body().statements().size());
                var outerAssignIf = assertInstanceOf(IfStatement.class, outerAssignFn.body().statements().get(1));
                var outerAssignBodyStmt = assertInstanceOf(ExpressionStatement.class,
                        outerAssignIf.body().statements().getFirst());
                if (assignStmt.startsWith("func.")) {
                    assertInstanceOf(LambdaExpression.class, outerAssignBodyStmt.expression());
                } else {
                    assertInstanceOf(AssignmentExpression.class, outerAssignBodyStmt.expression());
                }
            }

            // Finding 2: header-line lexical errors before a real same-line token keep exact owner and facet.
            var matchInlineErrSrc = "func f():\n\tmatch z: § pass\n".replace("\n", newline);
            var matchInlineErrRes = recoverableParse(matchInlineErrSrc);
            assertEnvelopeRecursive(matchInlineErrRes.ast(), matchInlineErrSrc);
            var matchInlineFn = onlyFunction(matchInlineErrRes);
            assertEquals(2, matchInlineFn.body().statements().size());
            var matchInlineStmt = assertInstanceOf(MatchStatement.class, matchInlineFn.body().statements().getFirst());
            assertInstanceOf(PassStatement.class, matchInlineFn.body().statements().get(1));
            var matchInlineLex = findErrorByCode(matchInlineErrRes, DiagnosticCodes.LEXICAL_ERROR);
            assertSame(matchInlineStmt, requireNotNull(matchInlineLex.recovery()).owner());
            assertEquals("match.section", requireNotNull(matchInlineLex.recovery()).facet());

            var ifInlineErrSrc = "func f():\n\tif ready: § pass\n".replace("\n", newline);
            var ifInlineErrRes = recoverableParse(ifInlineErrSrc);
            assertEnvelopeRecursive(ifInlineErrRes.ast(), ifInlineErrSrc);
            var ifInlineStmt = assertInstanceOf(IfStatement.class,
                    onlyFunction(ifInlineErrRes).body().statements().getFirst());
            assertInstanceOf(PassStatement.class, ifInlineStmt.body().statements().getFirst());
            var ifInlineLex = findErrorByCode(ifInlineErrRes, DiagnosticCodes.LEXICAL_ERROR);
            assertSame(ifInlineStmt, requireNotNull(ifInlineLex.recovery()).owner());
            assertEquals("suite.body", requireNotNull(ifInlineLex.recovery()).facet());

            var whileInlineErrSrc = "func f():\n\twhile ok: § break\n".replace("\n", newline);
            var whileInlineErrRes = recoverableParse(whileInlineErrSrc);
            assertEnvelopeRecursive(whileInlineErrRes.ast(), whileInlineErrSrc);
            var whileInlineStmt = assertInstanceOf(WhileStatement.class,
                    onlyFunction(whileInlineErrRes).body().statements().getFirst());
            assertInstanceOf(BreakStatement.class, whileInlineStmt.body().statements().getFirst());
            var whileInlineLex = findErrorByCode(whileInlineErrRes, DiagnosticCodes.LEXICAL_ERROR);
            assertSame(whileInlineStmt, requireNotNull(whileInlineLex.recovery()).owner());
            assertEquals("suite.body", requireNotNull(whileInlineLex.recovery()).facet());

            var forInlineErrSrc = "func f():\n\tfor x in xs: § continue\n".replace("\n", newline);
            var forInlineErrRes = recoverableParse(forInlineErrSrc);
            assertEnvelopeRecursive(forInlineErrRes.ast(), forInlineErrSrc);
            var forInlineStmt = assertInstanceOf(ForStatement.class,
                    onlyFunction(forInlineErrRes).body().statements().getFirst());
            assertInstanceOf(ContinueStatement.class, forInlineStmt.body().statements().getFirst());
            var forInlineLex = findErrorByCode(forInlineErrRes, DiagnosticCodes.LEXICAL_ERROR);
            assertSame(forInlineStmt, requireNotNull(forInlineLex.recovery()).owner());
            assertEquals("suite.body", requireNotNull(forInlineLex.recovery()).facet());

            var funcInlineErrSrc = "func f(): § pass\n".replace("\n", newline);
            var funcInlineErrRes = recoverableParse(funcInlineErrSrc);
            assertEnvelopeRecursive(funcInlineErrRes.ast(), funcInlineErrSrc);
            var funcInlineDecl = onlyFunction(funcInlineErrRes);
            assertInstanceOf(PassStatement.class, funcInlineDecl.body().statements().getFirst());
            var funcInlineLex = findErrorByCode(funcInlineErrRes, DiagnosticCodes.LEXICAL_ERROR);
            assertSame(funcInlineDecl, requireNotNull(funcInlineLex.recovery()).owner());
            assertEquals("suite.body", requireNotNull(funcInlineLex.recovery()).facet());

            var matchSecInlineErrSrc = "func f():\n\tmatch z:\n\t\t1: § pass\n".replace("\n", newline);
            var matchSecInlineErrRes = recoverableParse(matchSecInlineErrSrc);
            assertEnvelopeRecursive(matchSecInlineErrRes.ast(), matchSecInlineErrSrc);
            var matchSecInlineMatch = assertInstanceOf(MatchStatement.class,
                    onlyFunction(matchSecInlineErrRes).body().statements().getFirst());
            var matchSecInlineSection = matchSecInlineMatch.sections().getFirst();
            assertInstanceOf(PassStatement.class, matchSecInlineSection.body().statements().getFirst());
            var matchSecInlineLex = findErrorByCode(matchSecInlineErrRes, DiagnosticCodes.LEXICAL_ERROR);
            assertSame(matchSecInlineSection, requireNotNull(matchSecInlineLex.recovery()).owner());
            assertEquals("suite.body", requireNotNull(matchSecInlineLex.recovery()).facet());

            var nestedClassInlineErrSrc = "class A: § pass\n".replace("\n", newline);
            var nestedClassInlineErrRes = recoverableParse(nestedClassInlineErrSrc);
            assertEnvelopeRecursive(nestedClassInlineErrRes.ast(), nestedClassInlineErrSrc);
            var nestedClassInlineDecl = assertInstanceOf(ClassDeclaration.class,
                    nestedClassInlineErrRes.ast().statements().getFirst());
            assertInstanceOf(PassStatement.class, nestedClassInlineDecl.body().statements().getFirst());
            var nestedClassInlineLex = findErrorByCode(nestedClassInlineErrRes, DiagnosticCodes.LEXICAL_ERROR);
            assertSame(nestedClassInlineDecl, requireNotNull(nestedClassInlineLex.recovery()).owner());
            assertEquals("class.body", requireNotNull(nestedClassInlineLex.recovery()).facet());

            // Finding 2 negative control: next-line error after single-line `if ready: pass` is not claimed by `IfStatement`.
            var ifSingleNextLineSrc = "func f():\n\tif ready: pass\n\t§\n".replace("\n", newline);
            var ifSingleNextLineRes = recoverableParse(ifSingleNextLineSrc);
            assertEnvelopeRecursive(ifSingleNextLineRes.ast(), ifSingleNextLineSrc);
            var ifSingleNextFn = onlyFunction(ifSingleNextLineRes);
            var ifSingleNextIf = assertInstanceOf(IfStatement.class, ifSingleNextFn.body().statements().getFirst());
            var ifSingleNextLex = findErrorByCode(ifSingleNextLineRes, DiagnosticCodes.LEXICAL_ERROR);
            if (ifSingleNextLex.recovery() != null) {
                assertFalse(ifSingleNextIf == ifSingleNextLex.recovery().owner());
            }
        }
    }

    @Test
    void dictMemberFuncWithLexicalErrorAndColonlessMatchSectionDrainLexicalOwnershipAcrossLfAndCrlf() {
        for (var newline : List.of("\n", "\r\n")) {
            // Finding 1: lexical error between `.` and `func()` must not classify member access as inline lambda.
            for (var errToken : List.of("§", "0x", "\\")) {
                var memberFuncErrSrc = ("func f():\n\tvar x = {1\n\tif ready:\n\t\tobj. " + errToken
                        + " func().x = 2}\n").replace("\n", newline);
                var memberFuncErrRes = recoverableParse(memberFuncErrSrc);
                assertEnvelopeRecursive(memberFuncErrRes.ast(), memberFuncErrSrc);
                var memberFuncFn = onlyFunction(memberFuncErrRes);
                assertEquals(2, memberFuncFn.body().statements().size());
                assertInstanceOf(VariableDeclaration.class, memberFuncFn.body().statements().getFirst());
                var memberFuncIf = assertInstanceOf(IfStatement.class, memberFuncFn.body().statements().get(1));
                assertFalse(memberFuncIf.body().statements().isEmpty());
                assertNotNull(findErrorByCode(memberFuncErrRes, DiagnosticCodes.LEXICAL_ERROR));
            }

            // Finding 1 controls: inline lambda values remain dictionary values.
            for (var lambdaCtrl : List.of("func(): y = 2", "1 + func(): y = 2")) {
                var lambdaCtrlSrc = ("var x = {1\nif ready:\n\t" + lambdaCtrl + "}\n").replace("\n", newline);
                var lambdaCtrlRes = recoverableParse(lambdaCtrlSrc);
                assertEnvelopeRecursive(lambdaCtrlRes.ast(), lambdaCtrlSrc);
                assertEquals(List.of("Expected \"else\" after ternary operator condition."),
                        errorsOf(lambdaCtrlRes).stream().map(AstDiagnostic::message).toList());
                var lambdaCtrlDict = assertInstanceOf(DictionaryExpression.class,
                        assertInstanceOf(VariableDeclaration.class, lambdaCtrlRes.ast().statements().getFirst())
                                .value());
                assertEquals(1, lambdaCtrlDict.entries().size());
            }

            // Finding 2: colonless `match` branch drain claims same-line lexical errors before advancing.
            for (var badBranch : List.of("1 § pass", "1 pass § foo", "1 pass §")) {
                var colonlessMatchSrc = ("func f():\n\tmatch z:\n\t\t" + badBranch + "\n\t\t2: pass\n")
                        .replace("\n", newline);
                var colonlessMatchRes = recoverableParse(colonlessMatchSrc);
                assertEnvelopeRecursive(colonlessMatchRes.ast(), colonlessMatchSrc);
                var colonlessMatch = assertInstanceOf(MatchStatement.class,
                        onlyFunction(colonlessMatchRes).body().statements().getFirst());
                assertEquals(2, colonlessMatch.sections().size());
                var firstSection = colonlessMatch.sections().getFirst();
                var lexDiag = findErrorByCode(colonlessMatchRes, DiagnosticCodes.LEXICAL_ERROR);
                assertSame(firstSection, requireNotNull(lexDiag.recovery()).owner());
                assertEquals("match.section", requireNotNull(lexDiag.recovery()).facet());
                var missingColonDiag = findErrorByCode(colonlessMatchRes, DiagnosticCodes.MISSING_SEPARATOR_MATCH_COLON);
                assertSame(firstSection, requireNotNull(missingColonDiag.recovery()).owner());
                assertEquals("match.section", requireNotNull(missingColonDiag.recovery()).facet());
                assertInstanceOf(PassStatement.class,
                        colonlessMatch.sections().get(1).body().statements().getFirst());
            }
        }
    }

    @Test
    void colonlessGuardedMatchBranchExplicitContinuationAndHardNewlineAcrossLfAndCrlf() {
        for (var newline : List.of("\n", "\r\n")) {
            for (var contTail : List.of("§ junk", "§ pass", "§")) {
                var contSrc = ("func f():\n\tmatch z:\n\t\t1 when ok \\\n\t\t" + contTail + "\n\t\t2: pass\n")
                        .replace("\n", newline);
                var contRes = recoverableParse(contSrc);
                assertEnvelopeRecursive(contRes.ast(), contSrc);
                var contMatch = assertInstanceOf(MatchStatement.class,
                        onlyFunction(contRes).body().statements().getFirst());
                assertEquals(2, contMatch.sections().size(), () -> contMatch.sections().toString());
                var firstSection = contMatch.sections().getFirst();
                var lexDiag = findErrorByCode(contRes, DiagnosticCodes.LEXICAL_ERROR);
                assertSame(firstSection, requireNotNull(lexDiag.recovery()).owner());
                assertEquals("match.section", requireNotNull(lexDiag.recovery()).facet());
                var missingColonDiag = findErrorByCode(contRes, DiagnosticCodes.MISSING_SEPARATOR_MATCH_COLON);
                assertSame(firstSection, requireNotNull(missingColonDiag.recovery()).owner());
                assertEquals("match.section", requireNotNull(missingColonDiag.recovery()).facet());
                assertInstanceOf(PassStatement.class, contMatch.sections().get(1).body().statements().getFirst());
            }

            // Hard-newline controls: `1 when ok\n\t\t2: pass` and `1 when func(): pass\n\t\t2: pass`.
            for (var hardTail : List.of("ok", "func(): pass")) {
                var hardSrc = ("func f():\n\tmatch z:\n\t\t1 when " + hardTail + "\n\t\t2: pass\n")
                        .replace("\n", newline);
                var hardRes = recoverableParse(hardSrc);
                assertEnvelopeRecursive(hardRes.ast(), hardSrc);
                var hardMatch = assertInstanceOf(MatchStatement.class,
                        onlyFunction(hardRes).body().statements().getFirst());
                assertEquals(2, hardMatch.sections().size(), () -> hardMatch.sections().toString());
                assertEquals(List.of(), hardMatch.sections().getFirst().body().statements());
                assertInstanceOf(PassStatement.class, hardMatch.sections().get(1).body().statements().getFirst());
            }
        }
    }

    @Test
    void colonlessParenthesizedGuardMatchBranchExplicitContinuationAndHardNewlineAcrossLfAndCrlf() {
        for (var newline : List.of("\n", "\r\n")) {
            for (var contTail : List.of("§ junk", "§ pass", "§")) {
                var contSrc = ("func f():\n\tmatch z:\n\t\t1 when (ok\n\t\t) \\\n\t\t"
                        + contTail + "\n\t\t2: pass\n").replace("\n", newline);
                var contRes = recoverableParse(contSrc);
                assertEnvelopeRecursive(contRes.ast(), contSrc);
                var contMatch = assertInstanceOf(MatchStatement.class,
                        onlyFunction(contRes).body().statements().getFirst());
                assertEquals(2, contMatch.sections().size(), () -> contMatch.sections().toString());
                var firstSection = contMatch.sections().getFirst();
                var lexDiag = findErrorByCode(contRes, DiagnosticCodes.LEXICAL_ERROR);
                assertSame(firstSection, requireNotNull(lexDiag.recovery()).owner());
                assertEquals("match.section", requireNotNull(lexDiag.recovery()).facet());
                var missingColonDiag = findErrorByCode(contRes, DiagnosticCodes.MISSING_SEPARATOR_MATCH_COLON);
                assertSame(firstSection, requireNotNull(missingColonDiag.recovery()).owner());
                assertEquals("match.section", requireNotNull(missingColonDiag.recovery()).facet());
                assertInstanceOf(PassStatement.class, contMatch.sections().get(1).body().statements().getFirst());
            }

            // Hard-newline control: `1 when (ok\n\t\t)\n\t\t2: pass`.
            var hardSrc = "func f():\n\tmatch z:\n\t\t1 when (ok\n\t\t)\n\t\t2: pass\n"
                    .replace("\n", newline);
            var hardRes = recoverableParse(hardSrc);
            assertEnvelopeRecursive(hardRes.ast(), hardSrc);
            var hardMatch = assertInstanceOf(MatchStatement.class,
                    onlyFunction(hardRes).body().statements().getFirst());
            assertEquals(2, hardMatch.sections().size(), () -> hardMatch.sections().toString());
            assertEquals(List.of(), hardMatch.sections().getFirst().body().statements());
            assertInstanceOf(PassStatement.class, hardMatch.sections().get(1).body().statements().getFirst());
        }
    }

    @Test
    void matchBranchPassLexicalErrorOwnershipAndNextLineNegativeControlAcrossLfAndCrlf() {
        for (var newline : List.of("\n", "\r\n")) {
            for (var body : List.of(
                    "\t\tpass §\n\t\t2: pass\n",
                    "\t\tpass § junk\n\t\t2: pass\n",
                    "\t\t1: pass\n\t\tpass §\n",
                    "\t\t§ pass\n\t\t2: pass\n"
            )) {
                var src = ("func f():\n\tmatch z:\n" + body).replace("\n", newline);
                var res = recoverableParse(src);
                assertEnvelopeRecursive(res.ast(), src);
                var matchStmt = assertInstanceOf(MatchStatement.class,
                        onlyFunction(res).body().statements().getFirst());
                var lexDiag = findErrorByCode(res, DiagnosticCodes.LEXICAL_ERROR);
                assertSame(matchStmt, requireNotNull(lexDiag.recovery()).owner(), () -> src);
                assertEquals("match.section", requireNotNull(lexDiag.recovery()).facet(), () -> src);
                assertTrue(matchStmt.range().startByte() <= lexDiag.range().startByte()
                        && lexDiag.range().endByte() <= matchStmt.range().endByte(), () -> src);
            }

            // Negative control: `§` on the next logical line belongs to `2: pass`, not `MatchStatement`.
            var negSrc = "func f():\n\tmatch z:\n\t\tpass\n\t\t§ 2: pass\n".replace("\n", newline);
            var negRes = recoverableParse(negSrc);
            assertEnvelopeRecursive(negRes.ast(), negSrc);
            var negMatch = assertInstanceOf(MatchStatement.class,
                    onlyFunction(negRes).body().statements().getFirst());
            assertEquals(1, negMatch.sections().size(), () -> negMatch.sections().toString());
            var section = negMatch.sections().getFirst();
            var lexDiag = findErrorByCode(negRes, DiagnosticCodes.LEXICAL_ERROR);
            assertFalse(negMatch == requireNotNull(lexDiag.recovery()).owner());
            assertSame(section, requireNotNull(lexDiag.recovery()).owner());
            assertEquals("match.pattern", requireNotNull(lexDiag.recovery()).facet());
            assertInstanceOf(PassStatement.class, section.body().statements().getFirst());
        }
    }

    private static String utf8Slice(String source, Range range) {
        var bytes = source.getBytes(StandardCharsets.UTF_8);
        return new String(bytes, range.startByte(), range.endByte() - range.startByte(), StandardCharsets.UTF_8);
    }
}
