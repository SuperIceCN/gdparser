package dev.superice.gdparser.frontend.gdscript;

import dev.superice.gdparser.frontend.ast.AnnotationStatement;
import dev.superice.gdparser.frontend.ast.AstDiagnostic;
import dev.superice.gdparser.frontend.ast.AstDiagnosticSeverity;
import dev.superice.gdparser.frontend.ast.AstMappingResult;
import dev.superice.gdparser.frontend.ast.Block;
import dev.superice.gdparser.frontend.ast.ClassDeclaration;
import dev.superice.gdparser.frontend.ast.CommentStatement;
import dev.superice.gdparser.frontend.ast.ElifClause;
import dev.superice.gdparser.frontend.ast.ErrorExpression;
import dev.superice.gdparser.frontend.ast.ErrorStatement;
import dev.superice.gdparser.frontend.ast.ExpressionStatement;
import dev.superice.gdparser.frontend.ast.FunctionDeclaration;
import dev.superice.gdparser.frontend.ast.IdentifierExpression;
import dev.superice.gdparser.frontend.ast.LiteralExpression;
import dev.superice.gdparser.frontend.ast.Parameter;
import dev.superice.gdparser.frontend.ast.PassStatement;
import dev.superice.gdparser.frontend.ast.Point;
import dev.superice.gdparser.frontend.ast.Range;
import dev.superice.gdparser.frontend.ast.RecoveryLink;
import dev.superice.gdparser.frontend.ast.ReturnStatement;
import dev.superice.gdparser.frontend.ast.SignalStatement;
import dev.superice.gdparser.frontend.ast.SourceFile;
import dev.superice.gdparser.frontend.ast.Statement;
import dev.superice.gdparser.frontend.ast.VariableDeclaration;
import dev.superice.gdparser.frontend.ast.DeclarationKind;
import dev.superice.gdparser.frontend.cst.CstIssueKind;
import dev.superice.gdparser.frontend.gdscript.RecoveryTransactionTracker.AttributionPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Unit tests for the diagnostic registry (`DiagnosticCodes`) and provisional recovery frame
/// tracker (`RecoveryTransactionTracker`).
@Timeout(value = 10, unit = TimeUnit.SECONDS)
class DiagnosticRecoveryFoundationTest {

    private static final Range RANGE = new Range(0, 1, new Point(0, 0), new Point(0, 1));
    private static final Range ZERO_RANGE = new Range(0, 0, new Point(0, 0), new Point(0, 0));

    @Test
    void everyPublicConstantInDiagnosticCodesIsRegistered() throws IllegalAccessException {
        var constantValues = new HashSet<String>();
        for (var field : DiagnosticCodes.class.getDeclaredFields()) {
            if (Modifier.isPublic(field.getModifiers())
                    && Modifier.isStatic(field.getModifiers())
                    && Modifier.isFinal(field.getModifiers())
                    && field.getType() == String.class) {
                var value = (String) field.get(null);
                assertNotNull(value);
                assertTrue(constantValues.add(value), () -> "Duplicate diagnostic code constant: " + value);
                assertTrue(DiagnosticCodes.isRegistered(value), () -> "Unregistered constant: " + value);
                assertEquals(value, DiagnosticCodes.requireRegistered(value));
            }
        }
        assertEquals(constantValues, DiagnosticCodes.registeredCodes());
    }

    @Test
    void registryRejectsUnregisteredAndNullCodes() {
        assertFalse(DiagnosticCodes.isRegistered(null));
        assertFalse(DiagnosticCodes.isRegistered(""));
        assertFalse(DiagnosticCodes.isRegistered("parse.unknown.code"));
        assertThrows(IllegalArgumentException.class,
                () -> DiagnosticCodes.requireRegistered("parse.unknown.code"));
        assertThrows(UnsupportedOperationException.class,
                () -> DiagnosticCodes.registeredCodes().add("parse.injected"));
    }

    @Test
    void allowsNullRecoveryIsRestrictedToExplicitStandaloneExceptions() {
        var expectedStandalone = Set.of(
                DiagnosticCodes.LEXICAL_ERROR,
                DiagnosticCodes.UNEXPECTED_CLOSER,
                DiagnosticCodes.SYNTAX_UNEXPECTED_TOKEN,
                DiagnosticCodes.NESTING_DEPTH_EXCEEDED,
                DiagnosticCodes.STATEMENT_TOO_MANY_ERRORS
        );
        for (var code : DiagnosticCodes.registeredCodes()) {
            assertEquals(expectedStandalone.contains(code), DiagnosticCodes.allowsNullRecovery(code),
                    () -> "Unexpected allowsNullRecovery result for " + code);
        }
        assertFalse(DiagnosticCodes.allowsNullRecovery("parse.unknown.code"));
    }

    @Test
    void slotCodeDerivesRegisteredCodesAndRejectsUnknownSlots() {
        assertEquals(DiagnosticCodes.SLOT_VARIABLE_INITIALIZER, DiagnosticCodes.slotCode("variable.initializer"));
        assertEquals(DiagnosticCodes.SLOT_RETURN_VALUE, DiagnosticCodes.slotCode("return.value"));
        assertEquals(DiagnosticCodes.SLOT_DICTIONARY_PATTERN_KEY, DiagnosticCodes.slotCode("dictionary_pattern.key"));

        assertThrows(IllegalArgumentException.class, () -> DiagnosticCodes.slotCode("nonexistent.slot"));
    }

    @Test
    void facetAndOwnerPairingValidationRejectsCatchAllsAndMismatches() {
        // Positive cases
        assertTrue(DiagnosticCodes.isRegisteredFacet("variable.initializer"));
        assertTrue(DiagnosticCodes.isRegisteredFacet("statement.trailing"));
        assertTrue(DiagnosticCodes.isValidPairing(
                DiagnosticCodes.MISSING_CLOSER_GROUPING, VariableDeclaration.class, "variable.initializer"));
        assertTrue(DiagnosticCodes.isValidPairing(
                DiagnosticCodes.SLOT_RETURN_VALUE, ReturnStatement.class, "return.value"));
        assertTrue(DiagnosticCodes.isValidPairing(
                DiagnosticCodes.STATEMENT_TRAILING, PassStatement.class, "statement.trailing"));
        assertTrue(DiagnosticCodes.isValidPairing(
                DiagnosticCodes.ANNOTATION_DANGLING, AnnotationStatement.class, "annotation.target"));
        assertTrue(DiagnosticCodes.isValidPairing(
                DiagnosticCodes.ANNOTATION_DANGLING, VariableDeclaration.class, "variable.property"));
        assertTrue(DiagnosticCodes.isValidPairing(
                DiagnosticCodes.LEXICAL_ERROR, VariableDeclaration.class, "variable.initializer"));

        var validVar = new VariableDeclaration(DeclarationKind.VAR, "x", null, null, false, "variable_statement", RANGE);
        assertTrue(DiagnosticCodes.isValidRecovery(
                DiagnosticCodes.SLOT_VARIABLE_INITIALIZER,
                new RecoveryLink(validVar, "variable.initializer")
        ));

        // Negative cases: SourceFile is never a catch-all owner, Block/trivia are not statement.trailing owners,
        // interfaces are rejected, and mismatched code/facet/owner pairings are rejected.
        assertFalse(DiagnosticCodes.isRegisteredFacet(null));
        assertFalse(DiagnosticCodes.isRegisteredFacet("unknown.facet"));
        assertFalse(DiagnosticCodes.isValidOwnerForFacet("variable.initializer", SourceFile.class));
        assertFalse(DiagnosticCodes.isValidOwnerForFacet("expression", SourceFile.class));
        assertFalse(DiagnosticCodes.isValidOwnerForFacet("statement.trailing", SourceFile.class));
        assertFalse(DiagnosticCodes.isValidOwnerForFacet("statement.trailing", Block.class));
        assertFalse(DiagnosticCodes.isValidOwnerForFacet("statement.trailing", CommentStatement.class));
        assertFalse(DiagnosticCodes.isValidOwnerForFacet("statement.trailing", ErrorStatement.class));
        assertFalse(DiagnosticCodes.isValidOwnerForFacet("statement.trailing", Statement.class));

        assertFalse(DiagnosticCodes.isValidPairing(
                DiagnosticCodes.SLOT_VARIABLE_INITIALIZER, ReturnStatement.class, "return.value"));
        assertFalse(DiagnosticCodes.isValidPairing(
                DiagnosticCodes.MISSING_CLOSER_ASSERT, VariableDeclaration.class, "variable.initializer"));
        assertFalse(DiagnosticCodes.isValidPairing(
                DiagnosticCodes.MISSING_CLOSER_GROUPING, ReturnStatement.class, "variable.initializer"));
        assertFalse(DiagnosticCodes.isValidPairing(
                "parse.unregistered", VariableDeclaration.class, "variable.initializer"));
    }

    @Test
    void boundaryCodesKeepClosedPairingsAndStandaloneEligibilitySeparate() {
        assertTrue(DiagnosticCodes.allowsNullRecovery(DiagnosticCodes.UNEXPECTED_CLOSER));
        assertTrue(DiagnosticCodes.isValidPairing(
                DiagnosticCodes.UNEXPECTED_CLOSER, PassStatement.class, "statement.trailing"));
        assertTrue(DiagnosticCodes.isValidPairing(
                DiagnosticCodes.UNEXPECTED_CLOSER, FunctionDeclaration.class, "suite.body"));
        assertTrue(DiagnosticCodes.isValidPairing(
                DiagnosticCodes.UNEXPECTED_CLOSER, ClassDeclaration.class, "class.body"));
        assertFalse(DiagnosticCodes.isValidPairing(
                DiagnosticCodes.UNEXPECTED_CLOSER, SourceFile.class, "class.body"));

        assertTrue(DiagnosticCodes.allowsNullRecovery(DiagnosticCodes.STATEMENT_TOO_MANY_ERRORS));
        assertTrue(DiagnosticCodes.isValidPairing(
                DiagnosticCodes.STATEMENT_TOO_MANY_ERRORS, FunctionDeclaration.class, "suite.body"));
        assertFalse(DiagnosticCodes.isValidPairing(
                DiagnosticCodes.STATEMENT_TOO_MANY_ERRORS, ClassDeclaration.class, "class.body"));
        assertFalse(DiagnosticCodes.isValidPairing(
                DiagnosticCodes.STATEMENT_TOO_MANY_ERRORS, SourceFile.class, "suite.body"));

        assertFalse(DiagnosticCodes.allowsNullRecovery(DiagnosticCodes.MISSING_CLOSER_GROUPING));
        assertTrue(DiagnosticCodes.isValidPairing(
                DiagnosticCodes.MISSING_CLOSER_GROUPING, FunctionDeclaration.class, "function.signature"));
        assertTrue(DiagnosticCodes.isValidPairing(
                DiagnosticCodes.MISSING_CLOSER_GROUPING, SignalStatement.class, "signal.parameter"));
        assertTrue(DiagnosticCodes.isValidPairing(
                DiagnosticCodes.SLOT_RETURN_VALUE, FunctionDeclaration.class, "function.signature"));
        assertTrue(DiagnosticCodes.isValidPairing(
                DiagnosticCodes.MISSING_TYPE_LAMBDA_PARAMETER, SignalStatement.class, "signal.parameter"));
        for (var code : List.of(DiagnosticCodes.PROPERTY_DUPLICATE_GETTER, DiagnosticCodes.CLASS_DUPLICATE_EXTENDS,
                DiagnosticCodes.MISSING_CLOSER_ENUM, DiagnosticCodes.MISMATCHED_CLOSER_ENUM,
                DiagnosticCodes.SLOT_ENUM_VALUE)) {
            assertFalse(DiagnosticCodes.isValidPairing(code, FunctionDeclaration.class, "function.signature"));
            assertFalse(DiagnosticCodes.isValidPairing(code, SignalStatement.class, "signal.parameter"));
        }
        assertFalse(DiagnosticCodes.isValidPairing(
                DiagnosticCodes.FUNCTION_INVALID_STATIC_INIT, SignalStatement.class, "signal.parameter"));
    }

    @Test
    void commitAndRebindAttachToSurvivingOwnerByIdentity() {
        var tracker = new RecoveryTransactionTracker();
        var frame = tracker.pushFrame("parameter.default");
        tracker.emitError(
                "Expected closing \")\".",
                ZERO_RANGE,
                DiagnosticCodes.MISSING_CLOSER_GROUPING,
                AttributionPolicy.CURRENT_FRAME,
                null
        );
        assertSame(frame, tracker.popFrame());
        assertTrue(tracker.isBalanced());

        var provisionalParam = new Parameter("args", null, new ErrorExpression(CstIssueKind.MISSING, "parameter.default", "", ZERO_RANGE), false, RANGE);
        tracker.commit(frame, provisionalParam);

        var finalParam = new Parameter("args", null, new ErrorExpression(CstIssueKind.MISSING, "parameter.default", "", ZERO_RANGE), true, RANGE);
        tracker.rebind(frame, finalParam);

        var ast = new SourceFile(List.of(new FunctionDeclaration(
                "f", List.of(finalParam), null, false, new Block(List.of(new PassStatement(RANGE)), RANGE), RANGE
        )), RANGE);

        var finalized = tracker.finalizeDiagnostics(ast);
        assertEquals(1, finalized.size());
        var recovery = finalized.getFirst().recovery();
        assertNotNull(recovery);
        assertSame(finalParam, recovery.owner());
        assertEquals("parameter.default", recovery.facet());
    }

    @Test
    void abandonedChildFallsBackOnlyAlongAuthorizedParentChainWithFacetSwitch() {
        var tracker = new RecoveryTransactionTracker();

        // Authorized chain: annotation.target -> (parent: variableFrame, fallbackFacet: variable.property)
        var variableFrame = tracker.pushFrame("variable.initializer");
        var annotationFrame = tracker.pushChildFrame("annotation.target", variableFrame, "variable.property");
        tracker.emitError(
                "Dangling annotation in property accessor.",
                RANGE,
                DiagnosticCodes.ANNOTATION_DANGLING,
                AttributionPolicy.CURRENT_FRAME,
                null
        );
        assertSame(annotationFrame, tracker.popFrame());
        assertSame(variableFrame, tracker.popFrame());
        assertTrue(tracker.isBalanced());

        var variable = new VariableDeclaration(DeclarationKind.VAR, "prop", null, null, false, "variable_statement", RANGE);
        tracker.abandon(annotationFrame);
        tracker.commit(variableFrame, variable);

        var ast = new SourceFile(List.of(variable), RANGE);
        var finalized = tracker.finalizeDiagnostics(ast);
        assertEquals(1, finalized.size());
        var recovery = finalized.getFirst().recovery();
        assertNotNull(recovery);
        assertSame(variable, recovery.owner());
        assertEquals("variable.property", recovery.facet());
    }

    @Test
    void unauthorizedParentIsNotAutoClimbedWhenChildIsAbandoned() {
        var tracker = new RecoveryTransactionTracker();

        var outerVariableFrame = tracker.pushFrame("variable.initializer");
        // Pushed via pushFrame (no authorizedFallback to outerVariableFrame).
        var innerReturnFrame = tracker.pushFrame("return.value");
        tracker.emitError(
                "Expected closing \")\".",
                ZERO_RANGE,
                DiagnosticCodes.MISSING_CLOSER_GROUPING,
                AttributionPolicy.CURRENT_FRAME,
                null
        );
        assertSame(innerReturnFrame, tracker.popFrame());
        assertSame(outerVariableFrame, tracker.popFrame());
        assertTrue(tracker.isBalanced());

        var outerVariable = new VariableDeclaration(DeclarationKind.VAR, "cb", null, null, false, "variable_statement", RANGE);
        tracker.abandon(innerReturnFrame);
        tracker.commit(outerVariableFrame, outerVariable);

        var ast = new SourceFile(List.of(outerVariable), RANGE);
        var finalized = tracker.finalizeDiagnostics(ast);
        assertEquals(1, finalized.size());
        assertEquals(DiagnosticCodes.MISSING_CLOSER_GROUPING, finalized.getFirst().code());
        assertNull(finalized.getFirst().recovery());
    }

    @Test
    void multiHopMemoizedFallbackChainResolvesLinearlyWithoutOverflow() {
        var tracker = new RecoveryTransactionTracker();
        var rootVariable = new VariableDeclaration(DeclarationKind.VAR, "root", null, null, false, "variable_statement", RANGE);

        var rootFrame = tracker.pushFrame("variable.initializer");
        tracker.commit(rootFrame, rootVariable);

        // Build a 5,000-hop authorized fallback chain where only the root survives.
        var parent = rootFrame;
        var parentFallbackFacet = "variable.property";
        var frames = new ArrayList<RecoveryTransactionTracker.ProvisionalFrame>(5_001);
        frames.add(rootFrame);
        for (var i = 0; i < 5_000; i++) {
            var child = tracker.pushChildFrame("annotation.target", parent, parentFallbackFacet);
            frames.add(child);
            tracker.abandon(child);
            parent = child;
            parentFallbackFacet = "annotation.target";
        }
        // Emit 5,000 diagnostics from deep frames; memoization must keep resolution linear.
        for (var i = 1; i < frames.size(); i++) {
            tracker.emitError(
                    "Dangling annotation #" + i,
                    RANGE,
                    DiagnosticCodes.ANNOTATION_DANGLING,
                    AttributionPolicy.TARGET_FRAME,
                    frames.get(i)
            );
        }
        while (!tracker.isBalanced()) {
            tracker.popFrame();
        }

        var ast = new SourceFile(List.of(rootVariable), RANGE);
        var finalized = tracker.finalizeDiagnostics(ast);
        assertEquals(5_000, finalized.size());
        for (var diagnostic : finalized) {
            assertNotNull(diagnostic.recovery());
            assertSame(rootVariable, diagnostic.recovery().owner());
            assertEquals("variable.property", diagnostic.recovery().facet());
        }
    }

    @Test
    void abandonedFallbackChainsVisitAndCacheEachFrameOnlyOncePerCode() {
        var source = "var root = 1";
        var parser = new GdScriptParser();
        var tracker = new RecoveryTransactionTracker();
        for (var n : List.of(256, 512, 1024, 2048)) {
            var ast = parser.parse(source).ast();
            var owner = assertInstanceOf(VariableDeclaration.class, ast.statements().getFirst());
            var parent = tracker.pushFrame("variable.initializer");
            tracker.commit(parent, owner);
            var frames = new ArrayList<RecoveryTransactionTracker.ProvisionalFrame>(n);
            for (var i = 0; i < n; i++) {
                var child = tracker.pushChildFrame("annotation.target", parent,
                        i == 0 ? "variable.property" : "annotation.target");
                tracker.abandon(child);
                frames.add(child);
                parent = child;
            }
            // Resolve deepest first for a full walk followed by cache hits at every shallower depth.
            for (var frame : frames.reversed()) {
                tracker.emitError("Dangling annotation", owner.range(), DiagnosticCodes.ANNOTATION_DANGLING,
                        AttributionPolicy.TARGET_FRAME, frame);
            }
            for (var i = 0; i <= n; i++) {
                tracker.popFrame();
            }
            assertTrue(tracker.isBalanced());
            var diagnostics = tracker.finalizeDiagnostics(ast);
            var steps = tracker.fallbackResolutionSteps();
            // Each discarded frame is visited and cached once, then each remaining diagnostic hits the cache.
            var limit = 3L * n - 1;
            assertTrue(steps >= 2L * n && steps <= limit, () -> "fallback steps: " + steps);
            assertEquals(n, diagnostics.size());
            for (var diagnostic : diagnostics) {
                assertEquals(owner.range(), diagnostic.range());
                assertSame(owner, diagnostic.recovery().owner());
                assertEquals("variable.property", diagnostic.recovery().facet());
            }
            GdScriptParserRangeInvariantTest.assertDiagnosticInvariants(source,
                    new AstMappingResult(ast, diagnostics), () -> "discarded frames: " + n);
            assertTrue(tracker.isBalanced());
            assertTrue(parser.bracketOracleScanCount() <= 1);
            assertTrue(parser.parse(source).diagnostics().isEmpty());
            tracker.reset();
            assertEquals(0, tracker.fallbackResolutionSteps());
        }
    }

    @Test
    void invalidOwnerFacetPairingClearsRecoveryAndRejectsSourceFileCatchAll() {
        var tracker = new RecoveryTransactionTracker();
        var exprFrame = tracker.pushFrame("expression");
        tracker.emitError(
                "Expected expression.",
                ZERO_RANGE,
                DiagnosticCodes.MISSING_CLOSER_GROUPING,
                AttributionPolicy.CURRENT_FRAME,
                null
        );
        tracker.popFrame();

        // SourceFile is not a valid owner for "expression"; finalizeDiagnostics must clear recovery.
        var ast = new SourceFile(List.of(new PassStatement(RANGE)), RANGE);
        tracker.commit(exprFrame, ast);

        var finalized = tracker.finalizeDiagnostics(ast);
        assertEquals(1, finalized.size());
        assertEquals(DiagnosticCodes.MISSING_CLOSER_GROUPING, finalized.getFirst().code());
        assertNull(finalized.getFirst().recovery());
    }

    @Test
    void lexicalLookaheadStagingDoesNotPolluteOpenPreviousFrame() {
        var tracker = new RecoveryTransactionTracker();
        var stmt = new ExpressionStatement(new IdentifierExpression("ok", RANGE), RANGE);
        var exprFrame = tracker.pushFrame("expression");
        tracker.commit(exprFrame, stmt);

        // Lookahead scans a lexical error into its own batch while `exprFrame` is still open.
        var lookaheadBatch = tracker.openLexicalBatch();
        var lexRange = new Range(5, 6, new Point(1, 0), new Point(1, 1));
        tracker.stageLexicalError(lookaheadBatch, "Invalid character.", lexRange, DiagnosticCodes.LEXICAL_ERROR);
        tracker.popFrame();

        // Next statement claims its lookahead batch.
        var nextStmt = new ReturnStatement(new LiteralExpression("integer", "1", RANGE), RANGE);
        var returnFrame = tracker.pushFrame("return.value");
        tracker.claimLexicalBatch(lookaheadBatch, returnFrame);
        assertTrue(lookaheadBatch.isSettled());
        tracker.commit(returnFrame, nextStmt);
        tracker.popFrame();

        // Also test an unclaimed lexical error batch that stays standalone, preserving emission order.
        var orphanBatch = tracker.openLexicalBatch();
        var orphanLexRange = new Range(10, 11, new Point(2, 0), new Point(2, 1));
        tracker.stageLexicalError(orphanBatch, "Orphan lexical error.", orphanLexRange, DiagnosticCodes.LEXICAL_ERROR);
        tracker.flushLexicalBatchAsStandalone(orphanBatch);
        assertTrue(orphanBatch.isSettled());

        var ast = new SourceFile(List.of(stmt, nextStmt), RANGE);
        var finalized = tracker.finalizeDiagnostics(ast);
        assertEquals(2, finalized.size());
        assertEquals("Invalid character.", finalized.get(0).message());
        assertNotNull(finalized.get(0).recovery());
        assertSame(nextStmt, finalized.get(0).recovery().owner());
        assertEquals("return.value", finalized.get(0).recovery().facet());

        assertEquals("Orphan lexical error.", finalized.get(1).message());
        assertEquals(DiagnosticCodes.LEXICAL_ERROR, finalized.get(1).code());
        assertNull(finalized.get(1).recovery());
    }

    @Test
    void defensiveChecksRejectBadPoliciesUnbalancedFramesAndAbandonedCommits() {
        var tracker = new RecoveryTransactionTracker();
        assertTrue(tracker.isBalanced());
        assertThrows(IllegalStateException.class, tracker::popFrame);
        assertThrows(IllegalArgumentException.class, () -> tracker.pushFrame("unregistered.facet"));

        var frame = tracker.pushFrame("if.condition");
        assertFalse(tracker.isBalanced());

        assertThrows(IllegalArgumentException.class, () -> tracker.emitError(
                "Cannot be standalone.",
                ZERO_RANGE,
                DiagnosticCodes.SLOT_IF_CONDITION,
                AttributionPolicy.STANDALONE,
                null
        ));
        assertThrows(IllegalArgumentException.class, () -> tracker.emitError(
                "CURRENT_FRAME cannot pass targetFrame.",
                ZERO_RANGE,
                DiagnosticCodes.SLOT_IF_CONDITION,
                AttributionPolicy.CURRENT_FRAME,
                frame
        ));
        assertThrows(IllegalArgumentException.class, () -> tracker.emitError(
                "TARGET_FRAME requires targetFrame.",
                ZERO_RANGE,
                DiagnosticCodes.SLOT_IF_CONDITION,
                AttributionPolicy.TARGET_FRAME,
                null
        ));
        assertThrows(IllegalArgumentException.class, () -> tracker.emitError(
                "Unregistered code.",
                ZERO_RANGE,
                "parse.fake",
                AttributionPolicy.CURRENT_FRAME,
                null
        ));

        tracker.abandon(frame);
        var dummyNode = new PassStatement(RANGE);
        assertThrows(IllegalStateException.class, () -> tracker.commit(frame, dummyNode));
        assertThrows(IllegalStateException.class, () -> tracker.rebind(frame, dummyNode));

        assertSame(frame, tracker.popFrame());
        assertTrue(tracker.isBalanced());

        // Cross-tracker frame rejection
        var otherTracker = new RecoveryTransactionTracker();
        var foreignFrame = otherTracker.pushFrame("elif.condition");
        assertThrows(IllegalArgumentException.class, () -> tracker.commit(foreignFrame,
                new ElifClause(new IdentifierExpression("x", RANGE), new Block(List.of(), RANGE), RANGE)));

        tracker.reset();
        assertTrue(tracker.isBalanced());
        var legacy = new AstDiagnostic(AstDiagnosticSeverity.ERROR, "msg", "ERROR", RANGE);
        assertNull(legacy.code());

        // Emitting a CURRENT_FRAME diagnostic that requires an owner when no frame is open throws.
        assertThrows(IllegalStateException.class, () -> tracker.emitError(
                "Expected conditional expression.",
                ZERO_RANGE,
                DiagnosticCodes.SLOT_IF_CONDITION,
                AttributionPolicy.CURRENT_FRAME,
                null
        ));
    }

    @Test
    void propertyAccessorFallbackHostsBodyDiagnosticsWhileExcludingClassOnlyCodes() {
        assertTrue(DiagnosticCodes.isValidPairing(
                DiagnosticCodes.MISSING_CLOSER_GROUPING, VariableDeclaration.class, "variable.property"));
        assertTrue(DiagnosticCodes.isValidPairing(
                DiagnosticCodes.SLOT_RETURN_VALUE, VariableDeclaration.class, "variable.property"));
        assertTrue(DiagnosticCodes.isValidPairing(
                DiagnosticCodes.STATEMENT_TRAILING, VariableDeclaration.class, "variable.property"));

        assertFalse(DiagnosticCodes.isValidPairing(
                DiagnosticCodes.CLASS_DUPLICATE_EXTENDS, VariableDeclaration.class, "variable.property"));
        assertFalse(DiagnosticCodes.isValidPairing(
                DiagnosticCodes.FUNCTION_INVALID_STATIC_INIT, VariableDeclaration.class, "variable.property"));
        assertFalse(DiagnosticCodes.isValidPairing(
                DiagnosticCodes.MISSING_CLOSER_GROUPING, SourceFile.class, "variable.property"));

        var tracker = new RecoveryTransactionTracker();
        var propertyFrame = tracker.pushFrame("variable.initializer");
        var getterReturnFrame = tracker.pushChildFrame("return.value", propertyFrame, "variable.property");
        tracker.emitError(
                "Expected closing \")\".",
                ZERO_RANGE,
                DiagnosticCodes.MISSING_CLOSER_GROUPING,
                AttributionPolicy.CURRENT_FRAME,
                null
        );
        var getterTrailingFrame = tracker.pushChildFrame("statement.trailing", propertyFrame, "variable.property");
        tracker.emitError(
                "Unexpected trailing tokens.",
                RANGE,
                DiagnosticCodes.STATEMENT_TRAILING,
                AttributionPolicy.CURRENT_FRAME,
                null
        );
        assertSame(getterTrailingFrame, tracker.popFrame());
        assertSame(getterReturnFrame, tracker.popFrame());
        assertSame(propertyFrame, tracker.popFrame());

        tracker.abandon(getterReturnFrame);
        tracker.abandon(getterTrailingFrame);
        var prop = new VariableDeclaration(DeclarationKind.VAR, "prop", null, null, false, "variable_statement", RANGE);
        tracker.commit(propertyFrame, prop);

        var finalized = tracker.finalizeDiagnostics(new SourceFile(List.of(prop), RANGE));
        assertEquals(2, finalized.size());
        for (var diagnostic : finalized) {
            assertNotNull(diagnostic.recovery());
            assertSame(prop, diagnostic.recovery().owner());
            assertEquals("variable.property", diagnostic.recovery().facet());
        }
    }

    @Test
    void mixedCodeFallbackSkipsIncompatibleParentAndSelectsCompatibleAncestor() {
        var tracker = new RecoveryTransactionTracker();
        var propertyVar = new VariableDeclaration(
                DeclarationKind.VAR, "prop", null, null, false, "variable_statement", RANGE);
        var lambdaParam = new Parameter("v", null, new LiteralExpression("integer", "1", RANGE), false, RANGE);

        var propertyFrame = tracker.pushFrame("variable.initializer");
        tracker.commit(propertyFrame, propertyVar);
        var paramFrame = tracker.pushChildFrame("parameter.default", propertyFrame, "variable.property");
        tracker.commit(paramFrame, lambdaParam);
        var childFrame = tracker.pushChildFrame("annotation.target", paramFrame, "parameter.default");
        tracker.abandon(childFrame);

        // Emit incompatible-with-parameter code AND compatible-with-parameter code on the same child frame.
        tracker.emitError(
                "Dangling annotation.",
                RANGE,
                DiagnosticCodes.ANNOTATION_DANGLING,
                AttributionPolicy.TARGET_FRAME,
                childFrame
        );
        tracker.emitError(
                "Invalid character.",
                RANGE,
                DiagnosticCodes.LEXICAL_ERROR,
                AttributionPolicy.TARGET_FRAME,
                childFrame
        );

        assertSame(childFrame, tracker.popFrame());
        assertSame(paramFrame, tracker.popFrame());
        assertSame(propertyFrame, tracker.popFrame());

        var ast = new SourceFile(List.of(
                propertyVar,
                new FunctionDeclaration("f", List.of(lambdaParam), null, false, new Block(List.of(), RANGE), RANGE)
        ), RANGE);
        var finalized = tracker.finalizeDiagnostics(ast);
        assertEquals(2, finalized.size());

        // ANNOTATION_DANGLING cannot attach to Parameter/"parameter.default", so it climbs to VariableDeclaration/"variable.property".
        assertNotNull(finalized.get(0).recovery());
        assertSame(propertyVar, finalized.get(0).recovery().owner());
        assertEquals("variable.property", finalized.get(0).recovery().facet());

        // LEXICAL_ERROR is valid on Parameter/"parameter.default", so it stops at the immediate surviving parent.
        assertNotNull(finalized.get(1).recovery());
        assertSame(lambdaParam, finalized.get(1).recovery().owner());
        assertEquals("parameter.default", finalized.get(1).recovery().facet());
    }

    @Test
    void lexicalBatchClaimFlushAndLargeScaleLinearSmoke() {
        var tracker = new RecoveryTransactionTracker();
        var batchA = tracker.openLexicalBatch();
        var batchB = tracker.openLexicalBatch();
        var range1 = new Range(10, 11, new Point(1, 0), new Point(1, 1));
        var range2 = new Range(25, 26, new Point(2, 0), new Point(2, 1));
        tracker.stageLexicalError(batchA, "First lexical error.", range1, DiagnosticCodes.LEXICAL_ERROR);
        tracker.stageLexicalError(batchB, "Second lexical error.", range2, DiagnosticCodes.LEXICAL_ERROR);

        var stmt = new ReturnStatement(new LiteralExpression("integer", "1", RANGE), RANGE);
        var returnFrame = tracker.pushFrame("return.value");

        // Flush-then-claim invariant: flushing batchA settles it permanently as standalone, so a
        // subsequent claim attempt on batchA is a no-op while claiming batchB binds batchB.
        tracker.flushLexicalBatchAsStandalone(batchA);
        assertTrue(batchA.isSettled());
        tracker.claimLexicalBatch(batchA, returnFrame);
        tracker.claimLexicalBatch(batchB, returnFrame);
        assertTrue(batchB.isSettled());
        tracker.commit(returnFrame, stmt);
        assertSame(returnFrame, tracker.popFrame());

        // Staging into a settled batch or using a foreign-tracker batch is rejected.
        assertThrows(IllegalStateException.class, () -> tracker.stageLexicalError(
                batchA, "Too late.", RANGE, DiagnosticCodes.LEXICAL_ERROR));
        var otherTracker = new RecoveryTransactionTracker();
        var foreignBatch = otherTracker.openLexicalBatch();
        assertThrows(IllegalArgumentException.class, () -> tracker.stageLexicalError(
                foreignBatch, "Foreign.", RANGE, DiagnosticCodes.LEXICAL_ERROR));
        assertThrows(IllegalArgumentException.class, () -> tracker.claimLexicalBatch(foreignBatch, returnFrame));
        assertThrows(IllegalArgumentException.class, () -> tracker.flushLexicalBatchAsStandalone(foreignBatch));

        var finalized = tracker.finalizeDiagnostics(new SourceFile(List.of(stmt), RANGE));
        assertEquals(2, finalized.size());
        assertEquals("First lexical error.", finalized.get(0).message());
        assertNull(finalized.get(0).recovery());
        assertEquals("Second lexical error.", finalized.get(1).message());
        assertNotNull(finalized.get(1).recovery());
        assertSame(stmt, finalized.get(1).recovery().owner());
        assertEquals("return.value", finalized.get(1).recovery().facet());

        // Large-scale O(1) batch smoke test: 5,000 staged errors across 1,000 batches (half flushed,
        // half claimed) preserve exact emission order without quadratic shifting.
        var scaleTracker = new RecoveryTransactionTracker();
        var scaleStmt = new ReturnStatement(new LiteralExpression("integer", "2", RANGE), RANGE);
        var scaleFrame = scaleTracker.pushFrame("return.value");
        scaleTracker.commit(scaleFrame, scaleStmt);
        for (var i = 0; i < 1_000; i++) {
            var batch = scaleTracker.openLexicalBatch();
            for (var j = 0; j < 5; j++) {
                scaleTracker.stageLexicalError(batch, "Err " + i + "-" + j, RANGE, DiagnosticCodes.LEXICAL_ERROR);
            }
            if (i % 2 == 0) {
                scaleTracker.flushLexicalBatchAsStandalone(batch);
            } else {
                scaleTracker.claimLexicalBatch(batch, scaleFrame);
            }
        }
        assertSame(scaleFrame, scaleTracker.popFrame());
        var scaleFinalized = scaleTracker.finalizeDiagnostics(new SourceFile(List.of(scaleStmt), RANGE));
        assertEquals(5_000, scaleFinalized.size());
        for (var i = 0; i < 5_000; i++) {
            var batchIndex = i / 5;
            if (batchIndex % 2 == 0) {
                assertNull(scaleFinalized.get(i).recovery());
            } else {
                assertNotNull(scaleFinalized.get(i).recovery());
                assertSame(scaleStmt, scaleFinalized.get(i).recovery().owner());
            }
        }
    }

    @Test
    void finalizeDiagnosticsResetsTrackerStateOnBothCleanAndDamagedParses() {
        var tracker = new RecoveryTransactionTracker();
        var cleanNode = new PassStatement(RANGE);
        var cleanFrame = tracker.pushFrame("statement.trailing");
        tracker.commit(cleanFrame, cleanNode);
        assertSame(cleanFrame, tracker.popFrame());

        var cleanAst = new SourceFile(List.of(cleanNode), RANGE);
        assertEquals(List.of(), tracker.finalizeDiagnostics(cleanAst));
        assertTrue(tracker.isBalanced());
        assertThrows(IllegalArgumentException.class, () -> tracker.commit(cleanFrame, cleanNode));

        var stmt = new ReturnStatement(new LiteralExpression("integer", "1", RANGE), RANGE);
        var damagedFrame = tracker.pushFrame("return.value");
        tracker.emitError(
                "Expected closing \")\".",
                ZERO_RANGE,
                DiagnosticCodes.MISSING_CLOSER_GROUPING,
                AttributionPolicy.CURRENT_FRAME,
                null
        );
        tracker.commit(damagedFrame, stmt);
        assertSame(damagedFrame, tracker.popFrame());

        var finalized = tracker.finalizeDiagnostics(new SourceFile(List.of(stmt), RANGE));
        assertEquals(1, finalized.size());
        assertNotNull(finalized.getFirst().recovery());
        assertSame(stmt, finalized.getFirst().recovery().owner());
        assertTrue(tracker.isBalanced());
        assertThrows(IllegalArgumentException.class, () -> tracker.commit(damagedFrame, stmt));
    }
}
