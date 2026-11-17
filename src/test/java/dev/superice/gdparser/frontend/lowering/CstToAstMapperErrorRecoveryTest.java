package dev.superice.gdparser.frontend.lowering;

import dev.superice.gdparser.frontend.ast.AstDiagnosticSeverity;
import dev.superice.gdparser.frontend.ast.AstMappingResult;
import dev.superice.gdparser.frontend.ast.AttributeExpression;
import dev.superice.gdparser.frontend.ast.AttributePropertyStep;
import dev.superice.gdparser.frontend.ast.BinaryExpression;
import dev.superice.gdparser.frontend.ast.CallExpression;
import dev.superice.gdparser.frontend.ast.ErrorExpression;
import dev.superice.gdparser.frontend.ast.ErrorStatement;
import dev.superice.gdparser.frontend.ast.ExpressionStatement;
import dev.superice.gdparser.frontend.ast.ForStatement;
import dev.superice.gdparser.frontend.ast.FunctionDeclaration;
import dev.superice.gdparser.frontend.ast.IdentifierExpression;
import dev.superice.gdparser.frontend.ast.MissingAttributeStep;
import dev.superice.gdparser.frontend.ast.PassStatement;
import dev.superice.gdparser.frontend.ast.Statement;
import dev.superice.gdparser.frontend.ast.VariableDeclaration;
import dev.superice.gdparser.frontend.cst.CstIssueKind;
import dev.superice.gdparser.infra.treesitter.GdParserFacade;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Error-recovery lowering behavior: partial attribute chains keep their analyzable prefix with
/// an explicit missing-step marker, other CST `ERROR`/missing-placeholder structures become
/// `Error*` nodes, sibling mapping continues, and diagnostics keep carrying the error spans.
@SuppressWarnings("deprecation") // The legacy backend remains a regression reference.
class CstToAstMapperErrorRecoveryTest {

    private static GdParserFacade parserFacade;
    private static CstToAstMapper mapper;

    @BeforeAll
    static void setUp() {
        parserFacade = GdParserFacade.withDefaultLanguage();
        mapper = new CstToAstMapper();
    }

    @Test
    void receiverDotShouldMapToChainWithMissingStepMarker() {
        var result = map("func f():\n\tvar x = node. + 1");

        var declaration = assertInstanceOf(VariableDeclaration.class, bodyStatement(result, 0, 0),
                "The variable statement must not be swallowed by an error node");
        var value = assertInstanceOf(BinaryExpression.class, declaration.value());
        var attribute = assertInstanceOf(AttributeExpression.class, value.left());
        assertInstanceOf(IdentifierExpression.class, attribute.base());
        assertEquals(1, attribute.steps().size());
        var missingStep = assertInstanceOf(MissingAttributeStep.class, attribute.steps().getFirst());
        assertEquals(missingStep.range().startByte(), missingStep.range().endByte(),
                "The missing-step marker must be zero-width at the position after the dot");

        var missingDiagnostics = result.diagnostics().stream()
                .filter(diagnostic -> diagnostic.severity() == AstDiagnosticSeverity.ERROR)
                .toList();
        assertEquals(1, missingDiagnostics.size());
        var diagnostic = missingDiagnostics.getFirst();
        assertEquals("identifier", diagnostic.nodeType(), "Diagnostic should name the expected symbol");
        assertTrue(diagnostic.message().contains("identifier"),
                "MISSING diagnostic message should carry the expected token name: " + diagnostic.message());
        assertEquals(missingStep.range(), diagnostic.range(),
                "Diagnostic must keep carrying the error node range");
    }

    @Test
    void receiverDotInsideCallArgumentShouldKeepChain() {
        var result = map("func f():\n\tprint(node.)");

        var statement = assertInstanceOf(ExpressionStatement.class, bodyStatement(result, 0, 0));
        var call = assertInstanceOf(CallExpression.class, statement.expression());
        var attribute = assertInstanceOf(AttributeExpression.class, call.arguments().getFirst());
        assertEquals(1, attribute.steps().size());
        assertInstanceOf(MissingAttributeStep.class, attribute.steps().getFirst());
        assertTrue(result.diagnostics().stream().anyMatch(diagnostic ->
                diagnostic.severity() == AstDiagnosticSeverity.ERROR
                        && diagnostic.message().contains("identifier")));
    }

    @Test
    void completeAttributeChainShouldProduceNoMarkerOrDiagnostic() {
        var result = map("func f():\n\tnode.par");

        assertTrue(result.diagnostics().isEmpty(), () -> "Unexpected diagnostics: " + result.diagnostics());
        var statement = assertInstanceOf(ExpressionStatement.class, bodyStatement(result, 0, 0));
        var attribute = assertInstanceOf(AttributeExpression.class, statement.expression());
        assertEquals(1, attribute.steps().size());
        var step = assertInstanceOf(AttributePropertyStep.class, attribute.steps().getFirst());
        assertEquals("par", step.name());
    }

    @Test
    void cstErrorNodeShouldMapToErrorStatementAndSiblingsContinue() {
        var result = map("func _ready(: pass");

        assertEquals(2, result.ast().statements().size(), () -> "Sibling after the error must still be mapped: " + result.ast().statements());
        var errorStatement = assertInstanceOf(ErrorStatement.class, result.ast().statements().getFirst());
        assertEquals(CstIssueKind.ERROR, errorStatement.kind());
        assertEquals("ERROR", errorStatement.nodeType());
        assertEquals("func _ready(:", errorStatement.sourceText());
        assertEquals(0, errorStatement.range().startByte());
        assertEquals(13, errorStatement.range().endByte());
        assertInstanceOf(PassStatement.class, result.ast().statements().get(1));

        assertTrue(result.diagnostics().stream().anyMatch(diagnostic ->
                diagnostic.severity() == AstDiagnosticSeverity.ERROR
                        && diagnostic.range().equals(errorStatement.range())),
                "Diagnostics must keep carrying the error node range");
    }

    @Test
    void missingPlaceholderShouldMapToErrorExpressionInsideStatement() {
        var result = map("func f():\n\tfor i in:\n\t\tpass");

        var forStatement = assertInstanceOf(ForStatement.class, bodyStatement(result, 0, 0),
                "The for statement must stay analyzable even though the iterated expression is missing");
        assertEquals("i", forStatement.iterator());
        var missing = assertInstanceOf(ErrorExpression.class, forStatement.iterable());
        assertEquals(CstIssueKind.MISSING, missing.kind());
        assertEquals(missing.range().startByte(), missing.range().endByte(),
                "Missing placeholder must be zero-width where the expression was expected");

        assertTrue(result.diagnostics().stream().anyMatch(diagnostic ->
                diagnostic.severity() == AstDiagnosticSeverity.ERROR
                        && diagnostic.message().contains("identifier")
                        && diagnostic.range().equals(missing.range())),
                () -> "Expected a MISSING diagnostic naming the expected symbol: " + result.diagnostics());
    }

    @Test
    void missingFieldDiagnosticShouldCarryErrorNodeRange() {
        // `elif :` has no condition; the placeholder or the synthesized missing-field marker must
        // line up with the diagnostic that reports it.
        var result = map("func f():\n\tif a:\n\t\tpass\n\telif :\n\t\tpass");

        var ifStatement = assertInstanceOf(dev.superice.gdparser.frontend.ast.IfStatement.class, bodyStatement(result, 0, 0));
        assertEquals(1, ifStatement.elifClauses().size());
        var condition = assertInstanceOf(ErrorExpression.class, ifStatement.elifClauses().getFirst().condition());
        assertEquals(CstIssueKind.MISSING, condition.kind());

        assertTrue(result.diagnostics().stream().anyMatch(diagnostic ->
                diagnostic.severity() == AstDiagnosticSeverity.ERROR
                        && diagnostic.range().equals(condition.range())),
                () -> "Diagnostic must carry exactly the error node range " + condition.range()
                        + ": " + result.diagnostics());
    }

    @Test
    void errorNodeInsideBodyShouldMapToErrorStatement() {
        var result = map("func f():\n\tx = ");

        var statements = bodyStatements(result, 0);
        assertEquals(1, statements.size());
        var errorStatement = assertInstanceOf(ErrorStatement.class, statements.getFirst());
        assertEquals(CstIssueKind.ERROR, errorStatement.kind());
        assertNotNull(errorStatement.sourceText());
    }

    @Test
    void eofDanglingDotShouldRecoverFunctionAndChain() {
        var result = map("func f():\n\tnode.");

        var function = assertInstanceOf(FunctionDeclaration.class, result.ast().statements().getFirst(),
                "The enclosing function must survive end-of-file recovery");
        assertEquals("f", function.name());
        assertEquals(1, function.body().statements().size());
        var statement = assertInstanceOf(ExpressionStatement.class, function.body().statements().getFirst());
        var attribute = assertInstanceOf(AttributeExpression.class, statement.expression());
        assertEquals("node", assertInstanceOf(IdentifierExpression.class, attribute.base()).name());
        assertEquals(1, attribute.steps().size());
        var marker = assertInstanceOf(MissingAttributeStep.class, attribute.steps().getFirst());
        assertEquals(16, marker.range().startByte());
        assertEquals(16, marker.range().endByte());

        var errors = result.diagnostics().stream()
                .filter(diagnostic -> diagnostic.severity() == AstDiagnosticSeverity.ERROR)
                .toList();
        assertEquals(1, errors.size(), () -> "Expected exactly the synthesized MISSING diagnostic: " + result.diagnostics());
        assertEquals("Missing identifier", errors.getFirst().message());
        assertEquals(marker.range(), errors.getFirst().range());
    }

    @Test
    void eofDanglingDotShouldKeepPrecedingBodyStatements() {
        var result = map("func f():\n\tpass\n\tnode.");

        var function = assertInstanceOf(FunctionDeclaration.class, result.ast().statements().getFirst());
        assertEquals(2, function.body().statements().size());
        assertInstanceOf(PassStatement.class, function.body().statements().getFirst());
        var statement = assertInstanceOf(ExpressionStatement.class, function.body().statements().get(1));
        var attribute = assertInstanceOf(AttributeExpression.class, statement.expression());
        assertEquals(1, attribute.steps().size());
        assertInstanceOf(MissingAttributeStep.class, attribute.steps().getFirst());
    }

    @Test
    void eofDanglingDotShouldRecoverVariableInitializer() {
        var result = map("func f():\n\tvar x = node.");

        var function = assertInstanceOf(FunctionDeclaration.class, result.ast().statements().getFirst());
        var declaration = assertInstanceOf(VariableDeclaration.class, function.body().statements().getFirst());
        assertEquals("x", declaration.name());
        var attribute = assertInstanceOf(AttributeExpression.class, declaration.value());
        assertInstanceOf(MissingAttributeStep.class, attribute.steps().getLast());
    }

    @Test
    void eofDanglingDotShouldRecoverTypedVariableInitializer() {
        var result = map("func f():\n\tvar x: int = node.");

        var function = assertInstanceOf(FunctionDeclaration.class, result.ast().statements().getFirst());
        var declaration = assertInstanceOf(VariableDeclaration.class, function.body().statements().getFirst());
        assertEquals("x", declaration.name());
        assertNotNull(declaration.type());
        assertEquals("int", declaration.type().sourceText());
        assertInstanceOf(MissingAttributeStep.class,
                assertInstanceOf(AttributeExpression.class, declaration.value()).steps().getLast());
    }

    @Test
    void eofDanglingDotShouldRecoverReturnValue() {
        var result = map("func f():\n\treturn node.");

        var function = assertInstanceOf(FunctionDeclaration.class, result.ast().statements().getFirst());
        var returnStatement = assertInstanceOf(dev.superice.gdparser.frontend.ast.ReturnStatement.class,
                function.body().statements().getFirst());
        var attribute = assertInstanceOf(AttributeExpression.class, returnStatement.value());
        assertInstanceOf(MissingAttributeStep.class, attribute.steps().getLast());
    }

    @Test
    void eofDanglingDotShouldRecoverStaticFunctionAndReturnType() {
        var result = map("static func f() -> int:\n\tnode.");

        var function = assertInstanceOf(FunctionDeclaration.class, result.ast().statements().getFirst());
        assertEquals("f", function.name());
        assertTrue(function.isStatic());
        assertNotNull(function.returnType());
        assertEquals("int", function.returnType().sourceText());
        var statement = assertInstanceOf(ExpressionStatement.class, function.body().statements().getFirst());
        assertInstanceOf(MissingAttributeStep.class,
                assertInstanceOf(AttributeExpression.class, statement.expression()).steps().getLast());
    }

    @Test
    void eofDanglingDotShouldKeepAnnotationAndExtendsSiblings() {
        var result = map("@tool\nextends Node\nfunc f():\n\tnode.");

        assertEquals(3, result.ast().statements().size());
        assertInstanceOf(dev.superice.gdparser.frontend.ast.AnnotationStatement.class, result.ast().statements().get(0));
        assertInstanceOf(dev.superice.gdparser.frontend.ast.ExtendsStatement.class, result.ast().statements().get(1));
        var function = assertInstanceOf(FunctionDeclaration.class, result.ast().statements().get(2));
        var statement = assertInstanceOf(ExpressionStatement.class, function.body().statements().getFirst());
        assertInstanceOf(MissingAttributeStep.class,
                assertInstanceOf(AttributeExpression.class, statement.expression()).steps().getLast());
    }

    @Test
    void topLevelDanglingDotShouldRecoverBareChain() {
        var result = map("node.");

        assertEquals(1, result.ast().statements().size());
        var statement = assertInstanceOf(ExpressionStatement.class, result.ast().statements().getFirst());
        var attribute = assertInstanceOf(AttributeExpression.class, statement.expression());
        assertEquals("node", assertInstanceOf(IdentifierExpression.class, attribute.base()).name());
        assertInstanceOf(MissingAttributeStep.class, attribute.steps().getLast());
    }

    @Test
    void topLevelAssignmentToDanglingDotShouldRecover() {
        var result = map("x = node.");

        var statement = assertInstanceOf(ExpressionStatement.class, result.ast().statements().getFirst());
        var assignment = assertInstanceOf(dev.superice.gdparser.frontend.ast.AssignmentExpression.class, statement.expression());
        assertEquals("=", assignment.operator());
        assertEquals("x", assertInstanceOf(IdentifierExpression.class, assignment.left()).name());
        var attribute = assertInstanceOf(AttributeExpression.class, assignment.right());
        assertInstanceOf(MissingAttributeStep.class, attribute.steps().getLast());
    }

    @Test
    void topLevelMultiStepDanglingDotShouldRecoverWholeChain() {
        var result = map("x = a.b.");

        var statement = assertInstanceOf(ExpressionStatement.class, result.ast().statements().getFirst());
        var assignment = assertInstanceOf(dev.superice.gdparser.frontend.ast.AssignmentExpression.class, statement.expression());
        var attribute = assertInstanceOf(AttributeExpression.class, assignment.right());
        assertEquals("a", assertInstanceOf(IdentifierExpression.class, attribute.base()).name());
        assertEquals(2, attribute.steps().size());
        assertEquals("b", assertInstanceOf(AttributePropertyStep.class, attribute.steps().getFirst()).name());
        assertInstanceOf(MissingAttributeStep.class, attribute.steps().getLast());
    }

    @Test
    void eofDanglingDotOnSelfShouldRecover() {
        var result = map("func f():\n\tself.");

        var function = assertInstanceOf(FunctionDeclaration.class, result.ast().statements().getFirst());
        var statement = assertInstanceOf(ExpressionStatement.class, function.body().statements().getFirst());
        var attribute = assertInstanceOf(AttributeExpression.class, statement.expression());
        assertInstanceOf(dev.superice.gdparser.frontend.ast.SelfExpression.class, attribute.base());
        assertInstanceOf(MissingAttributeStep.class, attribute.steps().getLast());
    }

    @Test
    void eofUnclosedCallShouldStaySingleErrorStatement() {
        var result = map("func f():\n\tnode.f(");

        var function = assertInstanceOf(FunctionDeclaration.class, result.ast().statements().getFirst());
        var statements = function.body().statements();
        assertEquals(1, statements.size());
        var errorStatement = assertInstanceOf(ErrorStatement.class, statements.getFirst());
        assertEquals(CstIssueKind.ERROR, errorStatement.kind());
        assertTrue(result.diagnostics().stream().anyMatch(diagnostic ->
                diagnostic.severity() == AstDiagnosticSeverity.ERROR
                        && diagnostic.range().equals(errorStatement.range())));
    }

    @Test
    void eofDanglingDotShouldRecoverMultiStepChainInsideFunction() {
        var result = map("func f():\n\ta.b.");

        var function = assertInstanceOf(FunctionDeclaration.class, result.ast().statements().getFirst());
        var statement = assertInstanceOf(ExpressionStatement.class, function.body().statements().getFirst());
        var attribute = assertInstanceOf(AttributeExpression.class, statement.expression());
        assertEquals("a", assertInstanceOf(IdentifierExpression.class, attribute.base()).name());
        assertEquals(2, attribute.steps().size());
        assertEquals("b", assertInstanceOf(AttributePropertyStep.class, attribute.steps().getFirst()).name());
        assertInstanceOf(MissingAttributeStep.class, attribute.steps().getLast());
    }

    @Test
    void eofAwaitPrefixShouldNotBeSplitIntoJunk() {
        // The chain belongs to the unrecognized `await` prefix; recovery must decline the whole
        // error rather than emit a free-floating chain statement.
        var result = map("func f():\n\tawait node.");

        var statements = result.ast().statements();
        assertEquals(1, statements.size());
        assertInstanceOf(ErrorStatement.class, statements.getFirst());
    }

    @Test
    void eofAwaitPrefixAfterCompleteStatementShouldStillDecline() {
        var result = map("func f():\n\tpass\n\tawait node.");

        var statements = result.ast().statements();
        assertEquals(1, statements.size());
        assertInstanceOf(ErrorStatement.class, statements.getFirst());
    }

    @Test
    void eofOperatorBeforeChainShouldDecline() {
        var result = map("func f():\n\tpass\n\t+ node.");

        var statements = result.ast().statements();
        assertEquals(1, statements.size());
        assertInstanceOf(ErrorStatement.class, statements.getFirst());
    }

    @Test
    void eofTruncatedArrayPrefixShouldNotBeSplitIntoJunk() {
        var result = map("func f():\n\tvar x = [1,\n\tnode.");

        var statements = result.ast().statements();
        assertEquals(1, statements.size());
        assertInstanceOf(ErrorStatement.class, statements.getFirst());
    }

    @Test
    void eofAssignmentShouldKeepChainedTarget() {
        var result = map("func f():\n\tx.y = node.");

        var function = assertInstanceOf(FunctionDeclaration.class, result.ast().statements().getFirst());
        assertEquals(1, function.body().statements().size());
        var statement = assertInstanceOf(ExpressionStatement.class, function.body().statements().getFirst());
        var assignment = assertInstanceOf(dev.superice.gdparser.frontend.ast.AssignmentExpression.class, statement.expression());
        var target = assertInstanceOf(AttributeExpression.class, assignment.left());
        assertEquals("x", assertInstanceOf(IdentifierExpression.class, target.base()).name());
        assertEquals(1, target.steps().size());
        assertEquals("y", assertInstanceOf(AttributePropertyStep.class, target.steps().getFirst()).name());
        assertInstanceOf(MissingAttributeStep.class,
                assertInstanceOf(AttributeExpression.class, assignment.right()).steps().getLast());

        var errors = result.diagnostics().stream()
                .filter(diagnostic -> diagnostic.severity() == AstDiagnosticSeverity.ERROR)
                .toList();
        assertEquals(1, errors.size(), () -> "Expected exactly the synthesized MISSING diagnostic: " + result.diagnostics());
    }

    @Test
    void eofCallStepInsideChainShouldBePreserved() {
        var result = map("a.foo().b.");

        var statement = assertInstanceOf(ExpressionStatement.class, result.ast().statements().getFirst());
        var attribute = assertInstanceOf(AttributeExpression.class, statement.expression());
        assertEquals("a", assertInstanceOf(IdentifierExpression.class, attribute.base()).name());
        assertEquals(3, attribute.steps().size());
        var callStep = assertInstanceOf(dev.superice.gdparser.frontend.ast.AttributeCallStep.class, attribute.steps().get(0));
        assertEquals("foo", callStep.name());
        assertEquals("b", assertInstanceOf(AttributePropertyStep.class, attribute.steps().get(1)).name());
        assertInstanceOf(MissingAttributeStep.class, attribute.steps().get(2));
    }

    @Test
    void eofCompleteNestedStatementShouldBeKeptInBody() {
        var result = map("func f():\n\tif x:\n\t\tpass\n\tnode.");

        var function = assertInstanceOf(FunctionDeclaration.class, result.ast().statements().getFirst());
        assertEquals(2, function.body().statements().size());
        assertInstanceOf(dev.superice.gdparser.frontend.ast.IfStatement.class, function.body().statements().getFirst());
        var statement = assertInstanceOf(ExpressionStatement.class, function.body().statements().get(1));
        assertInstanceOf(MissingAttributeStep.class,
                assertInstanceOf(AttributeExpression.class, statement.expression()).steps().getLast());
    }

    private static AstMappingResult map(String source) {
        var root = parserFacade.parseCstRoot(source);
        return mapper.map(source, root);
    }

    private static java.util.List<Statement> bodyStatements(AstMappingResult result, int statementIndex) {
        var function = assertInstanceOf(FunctionDeclaration.class, result.ast().statements().get(statementIndex));
        return function.body().statements();
    }

    private static Statement bodyStatement(AstMappingResult result, int statementIndex, int bodyIndex) {
        return bodyStatements(result, statementIndex).get(bodyIndex);
    }
}
