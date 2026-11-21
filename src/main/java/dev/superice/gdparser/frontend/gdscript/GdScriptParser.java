package dev.superice.gdparser.frontend.gdscript;

import dev.superice.gdparser.frontend.ast.AnnotationStatement;
import dev.superice.gdparser.frontend.ast.ArrayExpression;
import dev.superice.gdparser.frontend.ast.AssertStatement;
import dev.superice.gdparser.frontend.ast.AssignmentExpression;
import dev.superice.gdparser.frontend.ast.AstMappingResult;
import dev.superice.gdparser.frontend.ast.AttributeCallStep;
import dev.superice.gdparser.frontend.ast.AttributeExpression;
import dev.superice.gdparser.frontend.ast.AttributePropertyStep;
import dev.superice.gdparser.frontend.ast.AttributeStep;
import dev.superice.gdparser.frontend.ast.AttributeSubscriptStep;
import dev.superice.gdparser.frontend.ast.AwaitExpression;
import dev.superice.gdparser.frontend.ast.BinaryExpression;
import dev.superice.gdparser.frontend.ast.Block;
import dev.superice.gdparser.frontend.ast.BreakStatement;
import dev.superice.gdparser.frontend.ast.BreakpointStatement;
import dev.superice.gdparser.frontend.ast.CallExpression;
import dev.superice.gdparser.frontend.ast.CastExpression;
import dev.superice.gdparser.frontend.ast.ClassDeclaration;
import dev.superice.gdparser.frontend.ast.ClassNameStatement;
import dev.superice.gdparser.frontend.ast.CommentStatement;
import dev.superice.gdparser.frontend.ast.ConditionalExpression;
import dev.superice.gdparser.frontend.ast.ConstructorDeclaration;
import dev.superice.gdparser.frontend.ast.ContinueStatement;
import dev.superice.gdparser.frontend.ast.DeclarationKind;
import dev.superice.gdparser.frontend.ast.DictEntry;
import dev.superice.gdparser.frontend.ast.DictionaryExpression;
import dev.superice.gdparser.frontend.ast.ElifClause;
import dev.superice.gdparser.frontend.ast.EnumDeclaration;
import dev.superice.gdparser.frontend.ast.EnumMember;
import dev.superice.gdparser.frontend.ast.ErrorExpression;
import dev.superice.gdparser.frontend.ast.Expression;
import dev.superice.gdparser.frontend.ast.ExpressionStatement;
import dev.superice.gdparser.frontend.ast.ExtendsStatement;
import dev.superice.gdparser.frontend.ast.ForStatement;
import dev.superice.gdparser.frontend.ast.FunctionDeclaration;
import dev.superice.gdparser.frontend.ast.GetNodeExpression;
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
import dev.superice.gdparser.frontend.ast.PreloadExpression;
import dev.superice.gdparser.frontend.ast.Range;
import dev.superice.gdparser.frontend.ast.RecoveredInfixExpression;
import dev.superice.gdparser.frontend.ast.RegionDirectiveStatement;
import dev.superice.gdparser.frontend.ast.ReturnStatement;
import dev.superice.gdparser.frontend.ast.SelfExpression;
import dev.superice.gdparser.frontend.ast.SignalStatement;
import dev.superice.gdparser.frontend.ast.SourceFile;
import dev.superice.gdparser.frontend.ast.Statement;
import dev.superice.gdparser.frontend.ast.SubscriptExpression;
import dev.superice.gdparser.frontend.ast.TypeRef;
import dev.superice.gdparser.frontend.ast.TypeTestExpression;
import dev.superice.gdparser.frontend.ast.UnaryExpression;
import dev.superice.gdparser.frontend.ast.VariableDeclaration;
import dev.superice.gdparser.frontend.ast.WhileStatement;
import dev.superice.gdparser.frontend.cst.CstIssueKind;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.BooleanSupplier;

/// Recursive-descent + Pratt parser producing `frontend.ast` directly from a GDScript source.
/// Port of `GDScriptParser` (`vendor/godot/gdscript_parser.h` / `gdscript_parser.cpp`), scoped to
/// the syntax layer: program prefix, class-body declarations, suites, annotations, types and the
/// expression subset declarations depend on (literals, identifiers, `self`, builtin constants,
/// unary `+`/`-`, binary operators, call/attribute/subscript chains). Semantic analysis, the
/// annotation registry (name/target/arity validation), completion hooks and symbol tables are cut;
/// the full Pratt rule table and control-flow statements are ported separately.
///
/// Behavior deviations from Godot, by design:
/// - `PI`/`TAU`/`INF`/`NAN` produce `IdentifierExpression` (numeric folding is cut with the
///   constant-reduction pass) to match the established AST contract.
/// - Class-body `pass` produces a `PassStatement` (Godot emits no node) because this AST keeps it.
/// - Standalone strings at program prefix / class body are consumed as docstrings and produce no
///   node, exactly like Godot; the reference tree-sitter path keeps them as expression statements,
///   which is a registered parity divergence.
/// - An `extends` line inside a nested class body sets the class's superclass (Godot behavior);
///   the tree-sitter path instead emits an `ExtendsStatement` inside the body (registered
///   divergence).
/// - Member/enum duplicate-name checks are cut with the symbol-table machinery; the unambiguous
///   purely syntactic checks (duplicate parameter names, mandatory-after-optional, rest-parameter
///   rules) are ported.
/// - Annotation name/target/arity validation is cut; only the syntax is parsed.
/// - A failed operand produces a marker/partial node carried by `ParseOutcome` instead of a bare
///   null, so damaged structure survives for editor consumers; markers are `ErrorExpression` with
///   `CstIssueKind.MISSING`/`ERROR` (zero-width at the failure point / consumed-span fragment).
public final class GdScriptParser {

    /// `AstDiagnostic.nodeType` for parser/tokenizer syntax diagnostics. There is no CST node to
    /// reference, so the uniform error-node tag is used (mirroring structural-issue diagnostics
    /// of the reference path).
    private static final String DIAGNOSTIC_NODE_TYPE = "ERROR";

    private record QueuedToken(
            @NotNull GdToken token,
            boolean followedContinuation,
            boolean explicitContinuation,
            int logicalStartLine,
            @Nullable RecoveryTransactionTracker.LexicalBatch precedingErrors,
            @Nullable RecoveryTransactionTracker.LexicalBatch postNewlineErrors
    ) {
        private QueuedToken(@NotNull GdToken token, boolean followedContinuation) {
            this(token, followedContinuation, followedContinuation, token.startLine(), null, null);
        }
    }

    private static final class LocalBracketOpener {
        final @NotNull GdTokenType openerType;
        final int depth;
        final int startByte;
        final int openerLine;
        final boolean allowsComma;
        boolean oracleResolved;
        boolean oracleMissing;
        @Nullable BracketBalanceOracle.OpenerStatus oracleStatus;
        int pairedCloserByte = -1;
        int pairedCloserLine = -1;
        int pairedCloserIndent = -1;
        int openerIndent = -1;

        LocalBracketOpener(
                @NotNull GdTokenType openerType,
                int depth,
                int startByte,
                int openerLine,
                boolean allowsComma
        ) {
            this.openerType = openerType;
            this.depth = depth;
            this.startByte = startByte;
            this.openerLine = openerLine;
            this.allowsComma = allowsComma;
        }
    }

    private GdTokenizer tokenizer = GdTokenizer.managed("");
    private byte[] sourceBytes = new byte[0];
    private SourceTextIndex index;
    private GdToken current;
    private boolean currentFollowedContinuation;
    private boolean currentExplicitContinuation;
    private int currentLogicalStartLine = 1;
    private RecoveryTransactionTracker.@Nullable LexicalBatch currentPrecedingErrors;
    private RecoveryTransactionTracker.@Nullable LexicalBatch currentPostNewlineErrors;
    private GdToken previous;
    private final Deque<Boolean> multilineStack = new ArrayDeque<>();
    /// Start byte of the token that opened each multiline frame, parallel to `multilineStack`.
    private final Deque<Integer> multilineStartStack = new ArrayDeque<>();
    /// Explicit structural frames for open delimiters, lambda boundaries, suites, and class bodies.
    private final Deque<StructuralFrame> structuralStack = new ArrayDeque<>();
    /// Number of active ancestor/current frames expecting `)`, `]`, or `}` respectively.
    private final int[] openCloserCounts = new int[3];
    /// Single-step lookahead buffer used when peeking past a `NEWLINE` without consuming it.
    private final Deque<QueuedToken> tokenQueue = new ArrayDeque<>();
    /// Source spans of closed grouping frames. Comments inside them are grouping-internal: the
    /// reference path materializes them as unknown-expression placeholders, which this parser
    /// cannot reproduce (registered parity difference), so every drain skips them. Recording
    /// spans (instead of discarding at the pop) keeps sequence comments pending before the
    /// grouping from blocking the skip.
    private final List<int[]> groupingCommentSpans = new ArrayList<>();
    /// Deferred comment prefixes of enclosing lambda bodies, innermost last. Restored to the
    /// cursor head when each body finishes (see {@link #deferCommentsBefore}).
    private final Deque<List<GdComment>> deferredCommentStack = new ArrayDeque<>();
    /// Lazy single-pass lexical bracket-balance oracle for cross-line multiline disambiguation.
    private @Nullable BracketBalanceOracle bracketOracle;
    private final RecoveryTransactionTracker recoveryTracker = new RecoveryTransactionTracker();
    /// Active outer property recovery frame while draining a discarded property accessor suite,
    /// so inner diagnostics fall back onto the owning `VariableDeclaration`'s `variable.property`
    /// facet instead of leaking onto unreachable local nodes.
    private RecoveryTransactionTracker.@Nullable ProvisionalFrame isolatedFallbackFrame;
    /// Active outer lambda body frame while parsing a lambda suite, so discarded failed inner
    /// statements fall back onto the owning `LambdaExpression`'s `suite.body` facet instead of
    /// losing construct ownership.
    private RecoveryTransactionTracker.@Nullable ProvisionalFrame lambdaBodyFallbackFrame;
    /// Active innermost suite body frame while parsing a statement suite, so discarded failed
    /// inner expression statements inherit the immediate surviving suite owner instead of
    /// dropping diagnostics as unowned.
    private RecoveryTransactionTracker.@Nullable ProvisionalFrame suiteBodyFallbackFrame;
    /// Active provisional `"class.extends"` frame for the first script-level `extends` or
    /// `class_name ... extends` declaration, so a duplicate top-level `extends` attributes there.
    private RecoveryTransactionTracker.@Nullable ProvisionalFrame scriptExtendsFrame;

    /// Annotations parsed but not yet flushed into a statement sequence. They flush (in source
    /// order) right before the next dispatched statement, and the flushed run that sits on the
    /// same physical line as the statement keyword extends that statement's range, matching the
    /// reference grammar's `annotations` span behavior.
    private final List<PendingAnnotation> pendingAnnotations = new ArrayList<>();
    /// Active `@warning_ignore_start` warning names mapped to their 1-based start line.
    private final Map<String, Integer> activeWarningIgnoreStarts = new HashMap<>();
    /// Unattached/invalid-level annotations collected inside an isolated property accessor suite
    /// so the owning `VariableDeclaration` retains them as recovery children in source order.
    private @Nullable List<AnnotationStatement> activePropertyRecoveryAnnotations;
    /// Start byte of the statement currently being dispatched (see {@link #computeStatementStart}).
    private int statementStartByte;
    /// Start byte of the parameter currently being parsed; the signature loops set this because a
    /// variadic `...` marker extends the parameter range to the marker start.
    private int parameterStartByte;
    /// Whether the script-level `extends` was already used (set by both the standalone and the
    /// same-line `class_name … extends` forms; Godot tracks it on the root class node).
    private boolean scriptExtendsUsed;
    private boolean scriptToolSeen;
    private boolean scriptIconSeen;
    private boolean scriptStaticUnloadSeen;
    private boolean scriptAbstractSeen;
    /// Loop-context flags for the ported `break`/`continue` checks (vendor `can_break`/
    /// `can_continue`): enabled around loop suites, disabled around lambda bodies, both with
    /// save/restore so nested constructs never leak state into siblings.
    private boolean canBreak;
    private boolean canContinue;
    /// Lambda-boundary states (vendor `in_lambda`/`lambda_ended`): they participate in
    /// `isStatementEnd()` because a lambda body may end a statement without a newline token.
    private boolean inLambda;
    private boolean lambdaEnded;
    /// Name of the innermost enclosing function/lambda (vendor `current_function->identifier`),
    /// used by the ported constructor-return-value check; `null` outside any function.
    private @Nullable String currentFunctionName;
    /// Comment trivia in source order plus the consumption cursor. Comments never enter the token
    /// stream; sequence boundaries materialize or discard them per the attribution rules (trailing
    /// comments land right after their statement, whole-line comments hold source position, and
    /// less-indented comments at a dedent boundary stay pending for the outer sequence).
    /// A lambda body temporarily defers the prefix before its `func` keyword (see
    /// {@link #deferCommentsBefore}) and restores it afterwards, which is why the list is mutable.
    private List<GdComment> comments = List.of();
    private int commentCursor;
    /// Right-edge override for expression spans ending in a lambda, or -1. A lambda body's
    /// trailing `end_statement` may already have consumed the following layout token (NEWLINE/
    /// DEDENT) before the enclosing expression captures its extent; the reference path never
    /// spans enclosing extents over that token, so the lambda records its body end here. Any
    /// token consumed afterwards (see `advance`) invalidates the override.
    private int trailingLambdaEndByte = -1;
    /// Right-edge override for expression/return slots that claimed same-logical-line lexical
    /// errors from `currentPrecedingErrors`, or -1. Cleared on the next `advance()` so parent
    /// constructs retain the UTF-8 byte envelope over the damaged lexical token.
    private int claimedLexicalEndByte = -1;
    private int localDelimiterOracleResolutions;
    /// Shared budget for every input-driven recursion entry (expressions via `parsePrecedence`,
    /// suites, match patterns, nested classes). Godot has no explicit limit — its C++ stack
    /// tolerates far deeper inputs — but on the JVM a pathological input such as 4096 nested
    /// `(` overflows the stack before any diagnostic can be produced, violating the recovery
    /// contract that illegal sources must yield diagnostics, never exceptions. 256 keeps
    /// roughly an order of magnitude below the overflow point on a default JVM thread stack
    /// while real-world nesting rarely exceeds a few dozen levels.
    private static final int MAX_NESTING_DEPTH = 256;
    private int nestingDepth;
    /// Sticky companion of the depth budget: set when the budget trips, cleared by
    /// `synchronize()` at the recovered boundary. While set, lambda body suites are not opened
    /// and statement dispatch bails straight to synchronization — the unwinding frames of the
    /// aborted construct must not let a body suite absorb following sibling lines (the depth
    /// counter alone cannot express this: it re-admits nesting as the frames unwind).
    private boolean nestingBudgetExceeded;
    /// Line indentation of the construct that tripped the depth budget. While poisoned,
    /// `synchronize()` consumes the whole aborted region — every deeper-indented line plus its
    /// layout tokens — and stops at the first line dedenting back to this width.
    private int poisonedLineIndent;
    /// Per-line caches for indentation width and first non-whitespace byte offset, lazily
    /// populated once per physical line so repeated layout queries remain O(1) amortized.
    private int[] cachedLineIndent = new int[0];
    private int[] cachedLineFirstContentByte = new int[0];
    private static final int[] EMPTY_INT_ARRAY = new int[0];
    private int lineIndentScanSteps;
    private int stagedErrorPrefixCoverageSteps;
    private @Nullable GdToken cachedPrefixCoverageToken;
    private @Nullable RecoveryTransactionTracker.LexicalBatch cachedPrefixCoveragePreBatch;
    private int cachedPrefixCoveragePreCount = -1;
    private @Nullable RecoveryTransactionTracker.LexicalBatch cachedPrefixCoveragePostBatch;
    private int cachedPrefixCoveragePostCount = -1;
    private boolean cachedPrefixCoverageResult;
    private @Nullable GdToken cachedIfStatementHeadToken;
    private int cachedIfStatementHeadDictCloserByte = -2;
    private boolean cachedIfStatementHeadResult;
    private int statementHeadColonScanSteps;
    private int signatureContinuationScanSteps;
    private int circuitBreakerStackOps;
    private int circuitBreakerOracleResolutions;
    private int forIteratorTypeSyncSteps;

    public GdScriptParser() {
    }

    /// Parses the given source. Every call fully reinitializes the parser state, so an instance
    /// may be reused sequentially; instances are not thread-safe.
    ///
    /// @return the mapped AST plus all accumulated diagnostics (tokenizer and parser errors).
    public @NotNull AstMappingResult parse(@NotNull String source) {
        tokenizer = GdTokenizer.managed(source);
        sourceBytes = source.getBytes(StandardCharsets.UTF_8);
        index = tokenizer.sourceTextIndex();
        var lineCount = Math.max(1, index.lineCount());
        cachedLineIndent = new int[lineCount];
        Arrays.fill(cachedLineIndent, -1);
        cachedLineFirstContentByte = new int[lineCount];
        Arrays.fill(cachedLineFirstContentByte, -1);
        lineIndentScanSteps = 0;
        stagedErrorPrefixCoverageSteps = 0;
        cachedPrefixCoverageToken = null;
        cachedPrefixCoveragePreBatch = null;
        cachedPrefixCoveragePreCount = -1;
        cachedPrefixCoveragePostBatch = null;
        cachedPrefixCoveragePostCount = -1;
        cachedPrefixCoverageResult = false;
        cachedIfStatementHeadToken = null;
        cachedIfStatementHeadDictCloserByte = -2;
        cachedIfStatementHeadResult = false;
        statementHeadColonScanSteps = 0;
        signatureContinuationScanSteps = 0;
        circuitBreakerStackOps = 0;
        circuitBreakerOracleResolutions = 0;
        forIteratorTypeSyncSteps = 0;
        multilineStack.clear();
        multilineStartStack.clear();
        structuralStack.clear();
        tokenQueue.clear();
        Arrays.fill(openCloserCounts, 0);
        recoveryTracker.reset();
        isolatedFallbackFrame = null;
        lambdaBodyFallbackFrame = null;
        suiteBodyFallbackFrame = null;
        scriptExtendsFrame = null;
        pendingAnnotations.clear();
        activeWarningIgnoreStarts.clear();
        activePropertyRecoveryAnnotations = null;
        scriptExtendsUsed = false;
        scriptToolSeen = false;
        scriptIconSeen = false;
        scriptStaticUnloadSeen = false;
        scriptAbstractSeen = false;
        canBreak = false;
        canContinue = false;
        inLambda = false;
        lambdaEnded = false;
        currentFunctionName = null;
        comments = tokenizer.modifiableComments();
        commentCursor = 0;
        groupingCommentSpans.clear();
        deferredCommentStack.clear();
        bracketOracle = null;
        trailingLambdaEndByte = -1;
        claimedLexicalEndByte = -1;
        localDelimiterOracleResolutions = 0;
        nestingDepth = 0;
        nestingBudgetExceeded = false;
        poisonedLineIndent = 0;
        previous = new GdToken(GdTokenType.EMPTY, "", GdLiteralKind.NONE, null, 0, 0, 1, 1, 1, 1);
        var leadingPendingSync = false;
        var lastLeadingErrorLine = 0;
        var token = tokenizer.scan();
        while (token.isError() || token.type() == GdTokenType.NEWLINE) {
            if (token.isError()) {
                leadingPendingSync = true;
                recoveryTracker.emitError(
                        token.errorMessage(),
                        tokenRange(token),
                        DiagnosticCodes.LEXICAL_ERROR,
                        RecoveryTransactionTracker.AttributionPolicy.STANDALONE,
                        null
                );
                // Queued errors drain LIFO, so keep the furthest physical end line seen so far.
                lastLeadingErrorLine = Math.max(lastLeadingErrorLine, token.endLine());
            } else {
                // Discarding a leading newline ends the offending leading line.
                leadingPendingSync = false;
                lastLeadingErrorLine = 0;
            }
            token = tokenizer.scan();
        }
        if (lastLeadingErrorLine > 0 && token.type() != GdTokenType.EOF
                && token.startLine() > lastLeadingErrorLine) {
            // Leading lexical errors occurred only on earlier blank/trivia lines; do not let them
            // suppress the first healthy declaration on a subsequent physical line.
            leadingPendingSync = false;
        }
        current = token;
        currentFollowedContinuation = token.type() != GdTokenType.EOF && tokenizer.isLineContinuation();
        currentExplicitContinuation = currentFollowedContinuation;
        currentLogicalStartLine = tokenizer.tokenLogicalStartLine();
        currentPrecedingErrors = null;
        currentPostNewlineErrors = null;

        var statements = parseProgram(leadingPendingSync);
        // Vendor invariant (debug-only there): the multiline stack must be balanced by the end
        // of the parse. Surfacing an imbalance here beats silently mis-parsing the next file.
        if (!multilineStack.isEmpty() || !multilineStartStack.isEmpty()) {
            throw new IllegalStateException("GDScript parser bug: unbalanced multiline stack at end of parse.");
        }
        if (!structuralStack.isEmpty()
                || openCloserCounts[0] != 0 || openCloserCounts[1] != 0 || openCloserCounts[2] != 0) {
            throw new IllegalStateException("GDScript parser bug: unbalanced structural stack at end of parse.");
        }
        if (!tokenQueue.isEmpty()) {
            throw new IllegalStateException("GDScript parser bug: unconsumed lookahead tokens at end of parse.");
        }
        if (!tokenizer.hasBalancedPersistentState()
                || (check(GdTokenType.EOF) && !tokenizer.hasBalancedLayoutState())) {
            throw new IllegalStateException("GDScript parser bug: unbalanced tokenizer layout state at end of parse.");
        }
        if (!recoveryTracker.isBalanced()) {
            throw new IllegalStateException("GDScript parser bug: unbalanced recovery frame stack at end of parse.");
        }
        var fileRange = statements.isEmpty()
                ? range(index.utf8Length(), index.utf8Length())
                : range(0, index.utf8Length());
        var ast = new SourceFile(statements, fileRange);
        // Resolve provisional recovery links against surviving AST owners by object identity.
        return new AstMappingResult(ast, recoveryTracker.finalizeDiagnostics(ast));
    }

    // ---------------------------------------------------------------------------------------------
    // Cursor, diagnostics and recovery — port of the cursor block in gdscript_parser.cpp.
    // ---------------------------------------------------------------------------------------------

    /// Scans until a non-ERROR token, converting each ERROR token into a staged lexical diagnostic
    /// (the parser never observes ERROR tokens, mirroring `GDScriptParser::advance`). Staging
    /// prevents lookahead errors from binding to a still-open previous-statement frame.
    private @NotNull QueuedToken scanSkippingErrors() {
        RecoveryTransactionTracker.LexicalBatch sameLineBatch = null;
        RecoveryTransactionTracker.LexicalBatch postNewlineBatch = null;
        var hardNewlinesAtStart = tokenizer.hardNewlineCount();
        var latestErrorLineContinued = false;
        var previousErrorEndLine = -1;
        var token = tokenizer.scan();
        while (token.isError()) {
            var crossedHardNewline = tokenizer.hardNewlineCount() != hardNewlinesAtStart;
            var tokenFollowedContinuation = tokenizer.isErrorLineContinuation();
            var errCont = !crossedHardNewline && tokenFollowedContinuation;
            RecoveryTransactionTracker.LexicalBatch targetBatch;
            if (!crossedHardNewline) {
                if (sameLineBatch == null) {
                    sameLineBatch = recoveryTracker.openLexicalBatch(errCont);
                }
                targetBatch = sameLineBatch;
            } else {
                if (postNewlineBatch == null) {
                    postNewlineBatch = recoveryTracker.openLexicalBatch(false);
                }
                targetBatch = postNewlineBatch;
            }
            if (tokenFollowedContinuation) {
                latestErrorLineContinued = true;
            } else if (previousErrorEndLine >= 0 && token.startLine() > previousErrorEndLine) {
                latestErrorLineContinued = false;
            }
            previousErrorEndLine = token.endLine();
            recoveryTracker.stageLexicalError(
                    targetBatch,
                    token.errorMessage(),
                    tokenRange(token),
                    DiagnosticCodes.LEXICAL_ERROR,
                    tokenizer.errorLogicalStartLine()
            );
            token = tokenizer.scan();
        }
        var crossedHardNewline = tokenizer.hardNewlineCount() != hardNewlinesAtStart;
        var explicitContinuation = tokenizer.isLineContinuation()
                || (latestErrorLineContinued
                        && token.type() != GdTokenType.NEWLINE
                        && token.type() != GdTokenType.INDENT
                        && token.type() != GdTokenType.DEDENT
                        && token.type() != GdTokenType.EOF
                        && token.startLine() == previousErrorEndLine);
        var followedContinuation = !crossedHardNewline && explicitContinuation;
        return new QueuedToken(token, followedContinuation, explicitContinuation,
                tokenizer.tokenLogicalStartLine(), sameLineBatch, postNewlineBatch);
    }

    private @NotNull QueuedToken nextScannedToken() {
        if (!tokenQueue.isEmpty()) {
            return tokenQueue.removeFirst();
        }
        return scanSkippingErrors();
    }

    private @NotNull GdTokenType peekAfterCurrent() {
        if (tokenQueue.isEmpty()) {
            tokenQueue.addLast(scanSkippingErrors());
        }
        return tokenQueue.getFirst().token().type();
    }

    /// Port of `GDScriptParser::advance`. Godot soft-fails on EOF (`ERR_FAIL_COND_V_MSG`,
    /// no movement); this port throws instead, so every call site that can legally sit at EOF
    /// must guard with `isAtEnd()` first (all loops ported from Godot already do).
    private void advance() {
        if (current.type() == GdTokenType.EOF) {
            throw new IllegalStateException("GDScript parser bug: trying to advance past the end of stream.");
        }
        settlePendingLexicalErrorsOnAdvance(true);
        // Vendor clears the lambda-end marker on every real token advance (a stale marker would
        // otherwise end a following statement early, e.g. `return` after a lambda argument).
        lambdaEnded = false;
        trailingLambdaEndByte = -1;
        claimedLexicalEndByte = -1;
        previous = current;
        var next = nextScannedToken();
        current = next.token();
        currentFollowedContinuation = next.followedContinuation();
        currentExplicitContinuation = next.explicitContinuation();
        currentLogicalStartLine = next.logicalStartLine();
        currentPrecedingErrors = next.precedingErrors();
        currentPostNewlineErrors = next.postNewlineErrors();
    }

    private void settlePendingLexicalErrorsOnAdvance(boolean filterMultilinePostNewline) {
        if (hasUnsettledLexicalBatch(currentPrecedingErrors)) {
            var active = recoveryTracker.activeFrame();
            if (active != null) {
                recoveryTracker.claimLexicalBatch(currentPrecedingErrors, active);
            } else {
                recoveryTracker.flushLexicalBatchAsStandalone(currentPrecedingErrors);
            }
        }
        if (hasUnsettledLexicalBatch(currentPostNewlineErrors)) {
            var active = recoveryTracker.activeFrame();
            if (active != null) {
                if (filterMultilinePostNewline && currentEffectiveMultiline() && !isLayoutOrEmptyPrevious()) {
                    var inScopeMask = inScopePostNewlineAdvanceErrorMask();
                    if (inScopeMask != null) {
                        recoveryTracker.claimLexicalBatchSubset(currentPostNewlineErrors, inScopeMask, active);
                    }
                    if (hasUnsettledLexicalBatch(currentPostNewlineErrors)) {
                        recoveryTracker.flushLexicalBatchAsStandalone(currentPostNewlineErrors);
                    }
                } else {
                    recoveryTracker.claimLexicalBatch(currentPostNewlineErrors, active);
                }
            } else {
                recoveryTracker.flushLexicalBatchAsStandalone(currentPostNewlineErrors);
            }
        }
    }

    /// Returns `previous` as a non-null token. `parse(...)` initializes `previous` to an `EMPTY`
    /// sentinel before parsing starts, and `advance()` only ever replaces it with non-null tokens
    /// produced by `tokenizer.scan()`.
    private @NotNull GdToken requirePreviousToken() {
        return Objects.requireNonNull(previous, "previous token must be initialized before parsing");
    }

    /// End byte of the consumed span of the expression most recently parsed at this level:
    /// normally the last consumed token's end, overridden by a trailing lambda's body end when
    /// it consumed a following layout token, and extended over any claimed same-logical-line
    /// lexical errors so parent ranges keep the UTF-8 envelope.
    private int extentEnd() {
        var baseEnd = trailingLambdaEndByte >= 0 ? trailingLambdaEndByte : previous.endByte();
        return Math.max(baseEnd, claimedLexicalEndByte);
    }

    private boolean hasUnsettledLexicalBatch(RecoveryTransactionTracker.@Nullable LexicalBatch batch) {
        return batch != null && !batch.isSettled() && !batch.isEmpty();
    }

    /// Checks the lexical batches belonging to the token about to be consumed. Match the
    /// advance-time multiline filter so a later line's out-of-scope errors do not taint this node.
    private boolean hasLexicalDamageOnAdvance(boolean filterMultilinePostNewline) {
        return hasUnsettledLexicalBatch(currentPrecedingErrors)
                || (hasUnsettledLexicalBatch(currentPostNewlineErrors)
                        && (!filterMultilinePostNewline || !currentEffectiveMultiline()
                                || isLayoutOrEmptyPrevious() || inScopePostNewlineAdvanceErrorMask() != null));
    }

    private void flushOutOfScopeDedentedErrors(int baselineIndent) {
        if (baselineIndent <= 0 || current.type() != GdTokenType.DEDENT) {
            return;
        }
        flushDedentedBatchSubset(currentPrecedingErrors, baselineIndent);
        flushDedentedBatchSubset(currentPostNewlineErrors, baselineIndent);
    }

    private void flushDedentedBatchSubset(
            @Nullable RecoveryTransactionTracker.LexicalBatch batch,
            int baselineIndent
    ) {
        if (!hasUnsettledLexicalBatch(batch)) {
            return;
        }
        var n = batch.entryCount();
        var mask = new boolean[n];
        var any = false;
        for (var i = 0; i < n; i++) {
            if (batch.entryStartByte(i) < sourceBytes.length
                    && lineIndentForOneBasedLine(batch.entryLogicalStartLine(i)) < baselineIndent) {
                mask[i] = true;
                any = true;
            }
        }
        if (any) {
            recoveryTracker.claimLexicalBatchSubset(batch, mask, null);
        }
    }

    private boolean isLayoutOrEmptyPrevious() {
        var prevType = requirePreviousToken().type();
        return prevType == GdTokenType.NEWLINE
                || prevType == GdTokenType.INDENT
                || prevType == GdTokenType.DEDENT
                || prevType == GdTokenType.SEMICOLON
                || prevType == GdTokenType.EMPTY;
    }

    private int escapingLinePrefixCutoffByte(boolean requireNonExpressionStatementStarter) {
        if (isAtEnd()
                || current.type() == GdTokenType.NEWLINE
                || current.type() == GdTokenType.INDENT
                || current.type() == GdTokenType.DEDENT
                || current.startByte() >= sourceBytes.length
                || previous == null
                || previous.type() == GdTokenType.EMPTY) {
            return Integer.MAX_VALUE;
        }
        var prevEndLine = previous.endLine();
        if (currentLogicalStartLine <= prevEndLine) {
            return Integer.MAX_VALUE;
        }
        if (requireNonExpressionStatementStarter
                && (!isPotentialOracleStopStarter(current.type()) || isExpressionStarter())) {
            return Integer.MAX_VALUE;
        }
        if (!isLineStartOrPrecededOnlyByStagedErrors(current)) {
            return Integer.MAX_VALUE;
        }
        if (requireNonExpressionStatementStarter
                && current.type() == GdTokenType.IF
                && !isIfStatementHeadCached(current, matchedDictionaryKeyCloserByte())) {
            return Integer.MAX_VALUE;
        }
        return index.lineStartByte(currentLogicalStartLine - 1);
    }

    private boolean @Nullable [] inScopePostNewlineSlotErrorMask() {
        if (!currentEffectiveMultiline()
                || !hasUnsettledLexicalBatch(currentPostNewlineErrors)
                || isLayoutOrEmptyPrevious()) {
            return null;
        }
        var maxEndByte = escapingLinePrefixCutoffByte(true);
        return inScopePostNewlineErrorMask(true, maxEndByte < Integer.MAX_VALUE, maxEndByte);
    }

    /// Classifies post-newline errors per logical line's starting physical row when `advance()`
    /// consumes a resumed valid token inside an unclosed multiline delimiter, so a dedented line
    /// does not drop a later indented line's errors before `effectiveRecoveryEnd()`.
    private boolean @Nullable [] inScopePostNewlineAdvanceErrorMask() {
        return inScopePostNewlineErrorMask(false, false);
    }

    private boolean @Nullable [] inScopePostNewlineErrorMask(
            boolean stopOnOutOfScope,
            boolean requireIndentScopeWhenMatched
    ) {
        return inScopePostNewlineErrorMask(stopOnOutOfScope, requireIndentScopeWhenMatched, Integer.MAX_VALUE);
    }

    private boolean @Nullable [] inScopePostNewlineErrorMask(
            boolean stopOnOutOfScope,
            boolean requireIndentScopeWhenMatched,
            int maxEndByte
    ) {
        if (!currentEffectiveMultiline()
                || !hasUnsettledLexicalBatch(currentPostNewlineErrors)
                || isLayoutOrEmptyPrevious()) {
            return null;
        }
        var total = currentPostNewlineErrors.entryCount();
        var activeFrame = currentMultilineBracketedFrame();
        var fact = activeFrame != null ? bracketOracle().factFor(activeFrame.openerStartByte) : null;
        var hasMissingFact = activeFrame != null
                && fact != null
                && fact.status() == BracketBalanceOracle.OpenerStatus.MISSING;
        if (activeFrame == null || (!hasMissingFact && !requireIndentScopeWhenMatched)) {
            var mask = new boolean[total];
            Arrays.fill(mask, true);
            return mask;
        }
        var effectiveRecoveryEnd = Math.min(
                hasMissingFact ? fact.effectiveRecoveryEnd() : Integer.MAX_VALUE,
                maxEndByte
        );
        if (currentPostNewlineErrors.minStartByte() >= effectiveRecoveryEnd) {
            return null;
        }
        var mask = new boolean[total];
        // Walk entries in cached physical source order so LIFO-queued whitespace errors cannot invert cutoff order.
        var order = currentPostNewlineErrors.physicalOrderIndices();
        var startBytes = currentPostNewlineErrors.physicalOrderStartBytes();
        var openerLine = index.pointAt(activeFrame.openerStartByte).row() + 1;
        var prevEndLine = requirePreviousToken().endLine();
        var suiteBaseline = enclosingSuiteBaseline(activeFrame);
        var claimedAny = false;
        for (var i = 0; i < order.length; i++) {
            var idx = order[i];
            var entryStartByte = startBytes[i];
            if (entryStartByte >= effectiveRecoveryEnd) {
                break;
            }
            var logicalLine = currentPostNewlineErrors.entryLogicalStartLine(idx);
            var inScope = suiteBaseline <= 0
                    || logicalLine <= openerLine
                    || logicalLine <= prevEndLine
                    || (entryStartByte < sourceBytes.length
                    && lineIndentForOneBasedLine(logicalLine) >= suiteBaseline);
            if (!inScope) {
                if (stopOnOutOfScope) {
                    break;
                }
                continue;
            }
            mask[idx] = true;
            claimedAny = true;
        }
        return claimedAny ? mask : null;
    }

    private boolean canClaimPostNewlineSlotErrors() {
        return inScopePostNewlineSlotErrorMask() != null;
    }

    private boolean isExplicitlyContinuedInScope(int openerLine, int prevEndLine, int suiteBaseline) {
        return currentExplicitContinuation
                && (suiteBaseline <= 0
                || currentLogicalStartLine <= openerLine
                || currentLogicalStartLine <= prevEndLine
                || lineIndentForOneBasedLine(currentLogicalStartLine) >= suiteBaseline);
    }

    private void claimInScopeMultilineLexicalErrorsWithoutSlot() {
        var active = recoveryTracker.activeFrame();
        if (hasCurrentLogicalLineLexicalErrors() && currentPrecedingErrors != null) {
            if (active != null) {
                recoveryTracker.claimLexicalBatch(currentPrecedingErrors, active);
            } else {
                recoveryTracker.flushLexicalBatchAsStandalone(currentPrecedingErrors);
            }
            claimedLexicalEndByte = Math.max(claimedLexicalEndByte,
                    Math.max(extentEnd(), currentPrecedingErrors.maxEndByte()));
        }
        var postNewlineMask = inScopePostNewlineSlotErrorMask();
        if (postNewlineMask != null && currentPostNewlineErrors != null) {
            var claimedSpan = recoveryTracker.claimLexicalBatchSubset(
                    currentPostNewlineErrors, postNewlineMask, active);
            if (claimedSpan != null) {
                claimedLexicalEndByte = Math.max(claimedLexicalEndByte,
                        Math.max(extentEnd(), claimedSpan.endByte()));
            }
        }
    }

    private boolean hasCurrentLogicalLineLexicalErrors() {
        if (!hasUnsettledLexicalBatch(currentPrecedingErrors) || isLayoutOrEmptyPrevious()) {
            return false;
        }
        return currentPrecedingErrors.firstStartLine() <= previous.endLine()
                || currentEffectiveMultiline()
                || currentPrecedingErrors.followedContinuation();
    }

    private boolean hasSameLogicalLineHeaderLexicalErrors() {
        if (!hasUnsettledLexicalBatch(currentPrecedingErrors) || isLayoutOrEmptyPrevious()) {
            return false;
        }
        var headerEndLine = requirePreviousToken().endLine();
        var count = currentPrecedingErrors.entryCount();
        for (var i = 0; i < count; i++) {
            if (currentPrecedingErrors.entryLogicalStartLine(i) <= headerEndLine) {
                return true;
            }
        }
        return false;
    }

    private RecoveryTransactionTracker.@Nullable LexicalSpan claimSameLogicalLineHeaderLexicalErrors(
            RecoveryTransactionTracker.@NotNull ProvisionalFrame targetFrame
    ) {
        if (!hasUnsettledLexicalBatch(currentPrecedingErrors) || isLayoutOrEmptyPrevious()) {
            return null;
        }
        var headerEndLine = requirePreviousToken().endLine();
        var count = currentPrecedingErrors.entryCount();
        var mask = new boolean[count];
        var any = false;
        for (var i = 0; i < count; i++) {
            if (currentPrecedingErrors.entryLogicalStartLine(i) <= headerEndLine) {
                mask[i] = true;
                any = true;
            }
        }
        if (!any) {
            return null;
        }
        var claimedSpan = recoveryTracker.claimLexicalBatchSubset(currentPrecedingErrors, mask, targetFrame);
        if (claimedSpan != null) {
            claimedLexicalEndByte = Math.max(claimedLexicalEndByte, claimedSpan.endByte());
        }
        return claimedSpan;
    }

    private boolean hasCurrentSlotLexicalErrors() {
        return hasCurrentLogicalLineLexicalErrors() || canClaimPostNewlineSlotErrors();
    }

    private @NotNull ErrorExpression claimLexicalErrorSlotValue(int spanStartFloor, boolean includeSpanFloor) {
        return claimLexicalErrorSlotValue(spanStartFloor, includeSpanFloor, true);
    }

    private @NotNull ErrorExpression claimLexicalErrorSlotValue(
            int spanStartFloor,
            boolean includeSpanFloor,
            boolean includeMultilinePostNewline
    ) {
        var primary = hasCurrentLogicalLineLexicalErrors() ? currentPrecedingErrors : null;
        var postNewlineMask = includeMultilinePostNewline ? inScopePostNewlineSlotErrorMask() : null;
        var postNewline = postNewlineMask != null ? currentPostNewlineErrors : null;
        if (primary == null && postNewline == null) {
            throw new IllegalStateException("claimLexicalErrorSlotValue called without unsettled slot lexical errors");
        }
        var batchMinStart = Integer.MAX_VALUE;
        var batchMaxEnd = Integer.MIN_VALUE;
        var active = recoveryTracker.activeFrame();
        if (primary != null) {
            batchMinStart = primary.minStartByte();
            batchMaxEnd = primary.maxEndByte();
            if (active != null) {
                recoveryTracker.claimLexicalBatch(primary, active);
            } else {
                recoveryTracker.flushLexicalBatchAsStandalone(primary);
            }
        }
        if (postNewline != null) {
            var claimedSpan = recoveryTracker.claimLexicalBatchSubset(postNewline, postNewlineMask, active);
            if (claimedSpan != null) {
                batchMinStart = Math.min(batchMinStart, claimedSpan.startByte());
                batchMaxEnd = Math.max(batchMaxEnd, claimedSpan.endByte());
            }
        }
        var errStart = includeSpanFloor
                ? Math.min(spanStartFloor, batchMinStart)
                : batchMinStart;
        var errEnd = Math.max(errStart, Math.max(extentEnd(), batchMaxEnd));
        claimedLexicalEndByte = Math.max(claimedLexicalEndByte, errEnd);
        var errRange = range(errStart, errEnd);
        return new ErrorExpression(CstIssueKind.ERROR, DIAGNOSTIC_NODE_TYPE,
                errStart < errEnd ? slice(errStart, errEnd) : "", errRange);
    }

    private boolean match(GdTokenType type) {
        if (!check(type)) {
            return false;
        }
        advance();
        return true;
    }

    /// Port of `GDScriptParser::check`: an `IDENTIFIER` check accepts every token Godot's
    /// `Token::is_identifier()` accepts (`match`/`when` and the builtin constants double as
    /// names, e.g. `String.match()` or `Vector2.INF`).
    private boolean check(GdTokenType type) {
        if (type == GdTokenType.IDENTIFIER) {
            return isIdentifier(current);
        }
        return current.type() == type;
    }

    /// Port of `GDScriptTokenizer::Token::is_identifier`.
    private static boolean isIdentifier(GdToken token) {
        return switch (token.type()) {
            case IDENTIFIER, MATCH, WHEN, CONST_PI, CONST_TAU, CONST_INF, CONST_NAN -> true;
            default -> false;
        };
    }

    /// Port of `GDScriptTokenizer::Token::is_node_name`: nearly every keyword may name an
    /// attribute member (used by `parse_attribute` and the `$`/`%` node paths).
    private static boolean isNodeName(GdToken token) {
        return switch (token.type()) {
            case IDENTIFIER, AND, AS, ASSERT, AWAIT, BREAK, BREAKPOINT, CLASS_NAME, CLASS, CONST,
                 CONST_PI, CONST_INF, CONST_NAN, CONST_TAU, CONTINUE, ELIF, ELSE, ENUM, EXTENDS,
                 FOR, FUNC, IF, IN, IS, MATCH, NAMESPACE, NOT, OR, PASS, PRELOAD, RETURN, SELF,
                 SIGNAL, STATIC, SUPER, TRAIT, UNDERSCORE, VAR, VOID, WHILE, WHEN, YIELD -> true;
            default -> false;
        };
    }

    private boolean consume(GdTokenType type, String errorMessage, @NotNull String code) {
        if (match(type)) {
            return true;
        }
        emitInCurrentFrameOrLegacy(errorMessage, code);
        return false;
    }

    private boolean isAtEnd() {
        return check(GdTokenType.EOF);
    }

    private static int closerIndex(GdTokenType type) {
        return switch (type) {
            case PARENTHESIS_CLOSE -> 0;
            case BRACKET_CLOSE -> 1;
            case BRACE_CLOSE -> 2;
            default -> -1;
        };
    }

    private static boolean isCloserToken(GdTokenType type) {
        return closerIndex(type) >= 0;
    }

    private boolean currentEffectiveMultiline() {
        return !multilineStack.isEmpty() && multilineStack.peekLast();
    }

    private @NotNull StructuralFrame pushStructuralFrame(@NotNull StructuralFrame frame) {
        structuralStack.addLast(frame);
        if (frame.expectedCloser != null) {
            openCloserCounts[closerIndex(frame.expectedCloser)]++;
        }
        return frame;
    }

    private @NotNull StructuralFrame pushBracketedFrame(
            @NotNull StructuralRole role,
            @NotNull GdTokenType openerType,
            @NotNull GdTokenType expectedCloser,
            int openerStartByte,
            boolean ownsMultilineFrame,
            boolean recordCommentSpan,
            @NotNull String constructDescription,
            @NotNull String missingCloserMessage,
            @NotNull String missingCloserCode,
            @NotNull String mismatchedCloserCode
    ) {
        var effectiveMultiline = ownsMultilineFrame || currentEffectiveMultiline();
        return pushStructuralFrame(StructuralFrame.bracketed(
                role,
                openerType,
                expectedCloser,
                openerStartByte,
                lineIndentOf(openerStartByte),
                effectiveMultiline,
                ownsMultilineFrame,
                recordCommentSpan,
                constructDescription,
                missingCloserMessage,
                missingCloserCode,
                mismatchedCloserCode
        ));
    }

    private void popStructuralFrame(@NotNull StructuralFrame expected) {
        var popped = structuralStack.pollLast();
        if (popped != expected) {
            throw new IllegalStateException("GDScript parser bug: unbalanced structural stack pop.");
        }
        if (expected.expectedCloser != null) {
            openCloserCounts[closerIndex(expected.expectedCloser)]--;
        }
        expected.settled = true;
    }

    private boolean hasMatchingAncestorCloser(GdTokenType closerType) {
        var idx = closerIndex(closerType);
        return idx >= 0 && openCloserCounts[idx] > 0;
    }

    private void markCurrentFramePendingSync() {
        if (!structuralStack.isEmpty()) {
            structuralStack.peekLast().pendingSync = true;
        }
    }

    private boolean consumeCurrentFramePendingSync() {
        if (structuralStack.isEmpty()) {
            return false;
        }
        var frame = structuralStack.peekLast();
        var pending = frame.pendingSync;
        frame.pendingSync = false;
        return pending;
    }

    private boolean hasCommaOwningAncestor() {
        return hasCommaOwningFrame(null);
    }

    private boolean hasEnclosingCommaOwner(@NotNull StructuralFrame excludedFrame) {
        return hasCommaOwningFrame(excludedFrame);
    }

    private boolean hasCommaOwningFrame(@Nullable StructuralFrame excludedFrame) {
        var it = structuralStack.descendingIterator();
        var seenExcluded = excludedFrame == null;
        while (it.hasNext()) {
            var frame = it.next();
            if (!seenExcluded) {
                if (frame == excludedFrame) {
                    seenExcluded = true;
                }
                continue;
            }
            switch (frame.role) {
                case CLASS_BODY -> {
                    return false;
                }
                case SUITE, MATCH_BODY -> {
                    // An indented control-flow suite or match body owns its block boundaries; only
                    // a lambda's own direct body suite may still terminate at an enclosing comma.
                    if (frame.effectiveMultiline
                            && (!it.hasNext() || it.next().role != StructuralRole.LAMBDA)) {
                        return false;
                    }
                }
                case LAMBDA, GROUPING, SUBSCRIPT -> {
                    // Inline lambda boundary, grouping, and subscript do not own commas; continue
                    // to the enclosing container.
                }
                case TYPE_BRACKET -> {
                    if (frame.allowsTypeComma && frame.separatorPhase == SeparatorPhase.EXPECT_SEPARATOR_OR_CLOSER) {
                        return true;
                    }
                }
                case ARRAY, DICTIONARY, CALL, PARAMETER_LIST, ANNOTATION, ASSERT, PRELOAD,
                     MATCH_ARRAY_PATTERN, MATCH_DICTIONARY_PATTERN, ENUM -> {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean canDeferReplacementCloserToEnclosingDelimiter() {
        var it = structuralStack.descendingIterator();
        while (it.hasNext()) {
            var frame = it.next();
            if (frame.role == StructuralRole.CLASS_BODY || frame.role == StructuralRole.MATCH_BODY) {
                return false;
            }
            if (frame.role == StructuralRole.SUITE && frame.effectiveMultiline) {
                return false;
            }
            if (frame.isBracketed()) {
                return true;
            }
        }
        return false;
    }

    private boolean isAtAncestorOwnedCloser() {
        return isCloserToken(current.type())
                && (hasMatchingAncestorCloser(current.type())
                        || canDeferReplacementCloserToEnclosingDelimiter());
    }

    private boolean isVirtualLambdaBoundary() {
        if (current.type() == GdTokenType.COMMA) {
            return hasCommaOwningAncestor();
        }
        if (isCloserToken(current.type())) {
            return isAtAncestorOwnedCloser();
        }
        return current.type() == GdTokenType.DEDENT;
    }

    private static final class LocalDelimiterEntry {
        final @NotNull GdTokenType openerType;
        final int startByte;
        int maxVerifiedCloserByte = -1;

        LocalDelimiterEntry(@NotNull GdTokenType openerType, int startByte) {
            this.openerType = openerType;
            this.startByte = startByte;
        }
    }

    private final class LocalDelimiterStack {
        private final ArrayList<LocalDelimiterEntry> stack = new ArrayList<>();
        private int resolvedPrefix;
        private int parens;
        private int brackets;
        private int braces;

        boolean isEmpty() {
            return stack.isEmpty();
        }

        boolean hasMatchingOpener(@NotNull GdTokenType closerType) {
            return switch (closerType) {
                case PARENTHESIS_CLOSE -> parens > 0;
                case BRACKET_CLOSE -> brackets > 0;
                case BRACE_CLOSE -> braces > 0;
                default -> false;
            };
        }

        boolean hasVerifiedCommaShield(int commaByte) {
            if (stack.isEmpty()) {
                return false;
            }
            while (resolvedPrefix < stack.size()) {
                var entry = stack.get(resolvedPrefix);
                var prevMax = resolvedPrefix > 0 ? stack.get(resolvedPrefix - 1).maxVerifiedCloserByte : -1;
                var fact = bracketOracle().factFor(entry.startByte);
                localDelimiterOracleResolutions++;
                var verifiedCloserByte = fact != null
                        && fact.openerByte() == entry.startByte
                        && fact.openerType() == entry.openerType
                        && fact.status() == BracketBalanceOracle.OpenerStatus.MATCHED
                        ? fact.pairedCloserByte()
                        : -1;
                entry.maxVerifiedCloserByte = Math.max(prevMax, verifiedCloserByte);
                resolvedPrefix++;
            }
            return stack.getLast().maxVerifiedCloserByte > commaByte;
        }

        void consume(@NotNull GdToken token) {
            switch (token.type()) {
                case PARENTHESIS_OPEN -> {
                    stack.add(new LocalDelimiterEntry(GdTokenType.PARENTHESIS_OPEN, token.startByte()));
                    parens++;
                }
                case BRACKET_OPEN -> {
                    stack.add(new LocalDelimiterEntry(GdTokenType.BRACKET_OPEN, token.startByte()));
                    brackets++;
                }
                case BRACE_OPEN -> {
                    stack.add(new LocalDelimiterEntry(GdTokenType.BRACE_OPEN, token.startByte()));
                    braces++;
                }
                case PARENTHESIS_CLOSE -> popToOpener(GdTokenType.PARENTHESIS_OPEN);
                case BRACKET_CLOSE -> popToOpener(GdTokenType.BRACKET_OPEN);
                case BRACE_CLOSE -> popToOpener(GdTokenType.BRACE_OPEN);
                default -> {
                }
            }
        }

        private void popToOpener(@NotNull GdTokenType targetOpener) {
            if (!hasMatchingOpener(matchingCloserFor(targetOpener))) {
                return;
            }
            while (!stack.isEmpty()) {
                var popped = stack.removeLast();
                switch (popped.openerType) {
                    case PARENTHESIS_OPEN -> parens--;
                    case BRACKET_OPEN -> brackets--;
                    case BRACE_OPEN -> braces--;
                    default -> {
                    }
                }
                if (popped.openerType == targetOpener) {
                    break;
                }
            }
            resolvedPrefix = Math.min(resolvedPrefix, stack.size());
        }
    }

    int localDelimiterOracleResolutions() {
        return localDelimiterOracleResolutions;
    }

    private void emitUnexpectedCloser(@NotNull GdToken token) {
        recoveryTracker.emitError(
                "Unexpected closing \"%s\".".formatted(token.type().displayName()),
                tokenRange(token),
                DiagnosticCodes.UNEXPECTED_CLOSER,
                RecoveryTransactionTracker.AttributionPolicy.STANDALONE,
                null
        );
    }

    int bracketOracleScanCount() {
        return bracketOracle == null ? 0 : bracketOracle.scanCount();
    }

    int lineIndentScanSteps() {
        return lineIndentScanSteps;
    }

    int stagedErrorPrefixCoverageSteps() {
        return stagedErrorPrefixCoverageSteps;
    }

    int statementHeadColonScanSteps() {
        return statementHeadColonScanSteps;
    }

    int signatureContinuationScanSteps() {
        return signatureContinuationScanSteps;
    }

    int circuitBreakerStackOps() {
        return circuitBreakerStackOps;
    }

    int circuitBreakerOracleResolutions() {
        return circuitBreakerOracleResolutions;
    }

    /// Number of discarded lookahead tokens during same-line `for` type recovery in this parse.
    int forIteratorTypeSyncSteps() {
        return forIteratorTypeSyncSteps;
    }

    private @Nullable StructuralFrame enclosingLambdaDelimiterFrame() {
        var seenLambda = false;
        var it = structuralStack.descendingIterator();
        while (it.hasNext()) {
            var frame = it.next();
            if (!seenLambda) {
                if (frame.role == StructuralRole.LAMBDA) {
                    seenLambda = true;
                }
                continue;
            }
            if (frame.isBracketed()) {
                return frame;
            }
        }
        return null;
    }

    private boolean isOracleCloserMissing(int openerStartByte) {
        var fact = bracketOracle().factFor(openerStartByte);
        return fact == null || fact.status() == BracketBalanceOracle.OpenerStatus.MISSING;
    }

    private @NotNull BracketBalanceOracle bracketOracle() {
        if (bracketOracle == null) {
            bracketOracle = new BracketBalanceOracle(tokenizer.codePoints(), tokenizer.byteOffsets());
        }
        return bracketOracle;
    }

    private @Nullable StructuralFrame currentMultilineBracketedFrame() {
        var it = structuralStack.descendingIterator();
        while (it.hasNext()) {
            var frame = it.next();
            if (frame.role == StructuralRole.LAMBDA || frame.role == StructuralRole.SUITE
                    || frame.role == StructuralRole.MATCH_BODY || frame.role == StructuralRole.CLASS_BODY) {
                return null;
            }
            if (frame.isBracketed() && frame.ownsMultilineFrame) {
                return frame;
            }
        }
        return null;
    }

    private int enclosingSuiteBaseline(@NotNull StructuralFrame targetFrame) {
        var it = structuralStack.descendingIterator();
        var foundTarget = false;
        while (it.hasNext()) {
            var frame = it.next();
            if (!foundTarget) {
                if (frame == targetFrame) {
                    foundTarget = true;
                }
                continue;
            }
            if (frame.role == StructuralRole.SUITE || frame.role == StructuralRole.MATCH_BODY
                    || frame.role == StructuralRole.CLASS_BODY) {
                return Math.max(0, frame.indentBaseline);
            }
        }
        return 0;
    }

    private boolean isLineStartToken(@NotNull GdToken token) {
        var row = token.startLine() - 1;
        if (row < 0 || row >= cachedLineFirstContentByte.length) {
            return false;
        }
        ensureLineMetadataCached(row);
        return token.startByte() <= cachedLineFirstContentByte[row];
    }

    private int matchedDictionaryKeyCloserByte() {
        var activeFrame = currentMultilineBracketedFrame();
        if (activeFrame == null
                || activeFrame.role != StructuralRole.DICTIONARY
                || !activeFrame.parsingDictionaryKey) {
            return -1;
        }
        var fact = bracketOracle().factFor(activeFrame.openerStartByte);
        if (fact == null
                || fact.status() != BracketBalanceOracle.OpenerStatus.MATCHED
                || fact.pairedCloserByte() <= activeFrame.openerStartByte) {
            return -1;
        }
        return fact.pairedCloserByte();
    }

    private boolean isIfStatementHeadCached(@NotNull GdToken token, int dictCloserByte) {
        if (token != cachedIfStatementHeadToken || dictCloserByte != cachedIfStatementHeadDictCloserByte) {
            cachedIfStatementHeadToken = token;
            cachedIfStatementHeadDictCloserByte = dictCloserByte;
            cachedIfStatementHeadResult = isStatementHeadColonLineOnSourceBytes(
                    token.endByte(), true, token.startByte(), dictCloserByte);
        }
        return cachedIfStatementHeadResult;
    }

    private boolean isMultilineLineHeadIfStatement(@NotNull GdToken token) {
        return token.type() == GdTokenType.IF
                && currentEffectiveMultiline()
                && !isLayoutOrEmptyPrevious()
                && currentLogicalStartLine > requirePreviousToken().endLine()
                && isLineStartOrPrecededOnlyByStagedErrors(token)
                && isIfStatementHeadCached(token, matchedDictionaryKeyCloserByte());
    }

    private boolean isLineStartOrPrecededOnlyByStagedErrors(@NotNull GdToken token) {
        if (isLineStartToken(token)) {
            return true;
        }
        var preActive = hasUnsettledLexicalBatch(currentPrecedingErrors);
        var postActive = hasUnsettledLexicalBatch(currentPostNewlineErrors);
        var preBatch = preActive ? currentPrecedingErrors : null;
        var postBatch = postActive ? currentPostNewlineErrors : null;
        var preCount = preBatch != null ? preBatch.entryCount() : 0;
        var postCount = postBatch != null ? postBatch.entryCount() : 0;
        if (token == cachedPrefixCoverageToken
                && preBatch == cachedPrefixCoveragePreBatch
                && preCount == cachedPrefixCoveragePreCount
                && postBatch == cachedPrefixCoveragePostBatch
                && postCount == cachedPrefixCoveragePostCount) {
            return cachedPrefixCoverageResult;
        }
        var row = token.startLine() - 1;
        if (row < 0 || row >= cachedLineFirstContentByte.length) {
            return cachePrefixCoverage(token, preBatch, preCount, postBatch, postCount, false);
        }
        ensureLineMetadataCached(row);
        var b = cachedLineFirstContentByte[row];
        var limit = Math.min(token.startByte(), sourceBytes.length);
        var preStarts = preActive ? currentPrecedingErrors.physicalOrderStartBytes() : EMPTY_INT_ARRAY;
        var preEnds = preActive ? currentPrecedingErrors.physicalOrderEndBytes() : EMPTY_INT_ARRAY;
        var postStarts = postActive ? currentPostNewlineErrors.physicalOrderStartBytes() : EMPTY_INT_ARRAY;
        var postEnds = postActive ? currentPostNewlineErrors.physicalOrderEndBytes() : EMPTY_INT_ARRAY;
        var preIdx = 0;
        var postIdx = 0;
        while (b < limit) {
            var byteVal = sourceBytes[b];
            if (byteVal == ' ' || byteVal == '\t' || byteVal == '\r') {
                stagedErrorPrefixCoverageSteps++;
                b++;
                continue;
            }
            while (preIdx < preStarts.length && preEnds[preIdx] <= b) {
                stagedErrorPrefixCoverageSteps++;
                preIdx++;
            }
            while (postIdx < postStarts.length && postEnds[postIdx] <= b) {
                stagedErrorPrefixCoverageSteps++;
                postIdx++;
            }
            var errorEnd = -1;
            if (preIdx < preStarts.length && preStarts[preIdx] <= b) {
                stagedErrorPrefixCoverageSteps++;
                errorEnd = Math.max(errorEnd, preEnds[preIdx]);
            }
            if (postIdx < postStarts.length && postStarts[postIdx] <= b) {
                stagedErrorPrefixCoverageSteps++;
                errorEnd = Math.max(errorEnd, postEnds[postIdx]);
            }
            if (errorEnd <= b) {
                return cachePrefixCoverage(token, preBatch, preCount, postBatch, postCount, false);
            }
            b = errorEnd;
        }
        return cachePrefixCoverage(token, preBatch, preCount, postBatch, postCount, true);
    }

    private boolean cachePrefixCoverage(
            @NotNull GdToken token,
            @Nullable RecoveryTransactionTracker.LexicalBatch preBatch,
            int preCount,
            @Nullable RecoveryTransactionTracker.LexicalBatch postBatch,
            int postCount,
            boolean result
    ) {
        cachedPrefixCoverageToken = token;
        cachedPrefixCoveragePreBatch = preBatch;
        cachedPrefixCoveragePreCount = preCount;
        cachedPrefixCoveragePostBatch = postBatch;
        cachedPrefixCoveragePostCount = postCount;
        cachedPrefixCoverageResult = result;
        return result;
    }

    private static boolean isPotentialOracleStopStarter(GdTokenType type) {
        return switch (type) {
            case CLASS, CLASS_NAME, EXTENDS, ENUM, VAR, CONST, SIGNAL, STATIC, FUNC, ANNOTATION,
                 RETURN, PASS, BREAK, CONTINUE, BREAKPOINT, WHILE, FOR, MATCH, ASSERT, IF -> true;
            default -> false;
        };
    }

    private boolean isAtUnclosedMultilineStop() {
        if (!currentEffectiveMultiline() || isAtEnd()
                || current.type() == GdTokenType.NEWLINE
                || current.type() == GdTokenType.INDENT
                || current.type() == GdTokenType.DEDENT) {
            return false;
        }
        var activeFrame = currentMultilineBracketedFrame();
        if (activeFrame == null) {
            return false;
        }
        var openerLine = index.pointAt(activeFrame.openerStartByte).row() + 1;
        if (current.startLine() <= openerLine) {
            return false;
        }
        var suiteBaseline = enclosingSuiteBaseline(activeFrame);
        var potentialDedentEscape = suiteBaseline > 0
                && !isExplicitlyContinuedInScope(openerLine, previous.endLine(), suiteBaseline)
                && current.startByte() < sourceBytes.length
                && current.startLine() > previous.endLine()
                && isLineStartOrPrecededOnlyByStagedErrors(current)
                && lineIndentOf(current.startByte()) < suiteBaseline;
        if (!potentialDedentEscape && !isPotentialOracleStopStarter(current.type())) {
            return false;
        }
        var fact = bracketOracle().factFor(activeFrame.openerStartByte);
        if (fact == null || fact.status() != BracketBalanceOracle.OpenerStatus.MISSING) {
            return false;
        }
        return potentialDedentEscape || current.startByte() >= fact.effectiveRecoveryEnd();
    }

    private void replayLayoutIfExitedMultiline() {
        if (tokenizer.isMultilineMode()) {
            return;
        }
        var replayed = tokenizer.reconcileLayoutForReplay(current);
        if (replayed.isEmpty()) {
            return;
        }
        var layoutTokens = new ArrayList<GdToken>(replayed.size());
        for (var token : replayed) {
            if (token.isError()) {
                recoveryTracker.stageLexicalError(
                        token.errorMessage() != null ? token.errorMessage() : "Unknown error",
                        tokenRange(token),
                        DiagnosticCodes.LEXICAL_ERROR
                );
            } else {
                layoutTokens.add(token);
            }
        }
        if (layoutTokens.isEmpty()) {
            return;
        }
        tokenQueue.addFirst(new QueuedToken(
                current, currentFollowedContinuation, currentExplicitContinuation, currentLogicalStartLine,
                null, currentPostNewlineErrors));
        for (var i = layoutTokens.size() - 1; i >= 1; i--) {
            tokenQueue.addFirst(new QueuedToken(layoutTokens.get(i), false));
        }
        current = layoutTokens.getFirst();
        currentFollowedContinuation = false;
        currentExplicitContinuation = false;
        currentLogicalStartLine = current.startLine();
        currentPostNewlineErrors = null;
    }

    private static GdTokenType matchingCloserFor(GdTokenType opener) {
        return switch (opener) {
            case PARENTHESIS_OPEN -> GdTokenType.PARENTHESIS_CLOSE;
            case BRACKET_OPEN -> GdTokenType.BRACKET_CLOSE;
            case BRACE_OPEN -> GdTokenType.BRACE_CLOSE;
            default -> throw new IllegalArgumentException("Not a bracket opener: " + opener);
        };
    }

    private void ensureLocalOpenerOracleResolved(@NotNull LocalBracketOpener opener) {
        if (opener.oracleResolved) {
            return;
        }
        opener.oracleResolved = true;
        circuitBreakerOracleResolutions++;
        var fact = bracketOracle().factFor(opener.startByte);
        opener.oracleStatus = fact != null ? fact.status() : null;
        if (fact == null || fact.status() == BracketBalanceOracle.OpenerStatus.MISSING) {
            opener.oracleMissing = true;
            return;
        }
        opener.oracleMissing = false;
        opener.pairedCloserByte = fact.pairedCloserByte();
        opener.pairedCloserLine = fact.pairedCloserLine();
        if (opener.pairedCloserByte >= 0 && opener.pairedCloserLine > 0) {
            opener.pairedCloserIndent = lineIndentForOneBasedLine(opener.pairedCloserLine);
            opener.openerIndent = lineIndentForOneBasedLine(opener.openerLine);
        }
    }

    private @Nullable LocalBracketOpener popReplacedLocalOpenerAt(
            @NotNull ArrayDeque<LocalBracketOpener> deque,
            int closerByte
    ) {
        if (deque.isEmpty()) {
            return null;
        }
        var top = deque.peekLast();
        ensureLocalOpenerOracleResolved(top);
        if (top.oracleStatus == BracketBalanceOracle.OpenerStatus.REPLACED
                && top.pairedCloserByte == closerByte) {
            circuitBreakerStackOps++;
            deque.removeLast();
            return top;
        }
        return null;
    }

    private void unwindSkippedInnerLocalOpeners(
            int closedOpenerStartByte,
            @NotNull ArrayDeque<LocalBracketOpener> deque
    ) {
        while (!deque.isEmpty()) {
            circuitBreakerStackOps++;
            var top = deque.peekLast();
            if (top.startByte <= closedOpenerStartByte) {
                break;
            }
            ensureLocalOpenerOracleResolved(top);
            if (!top.oracleMissing) {
                break;
            }
            circuitBreakerStackOps++;
            deque.removeLast();
        }
    }

    private static boolean isCommaOwningParenPredecessor(
            @Nullable GdToken token,
            boolean wasMemberName,
            boolean wasStatementStart
    ) {
        if (token == null) {
            return false;
        }
        if (wasMemberName && isNodeName(token)) {
            return true;
        }
        return switch (token.type()) {
            case IDENTIFIER, WHEN, FUNC, ANNOTATION, PRELOAD, ASSERT, SUPER,
                 PARENTHESIS_CLOSE, BRACKET_CLOSE -> true;
            case MATCH -> !wasStatementStart;
            default -> false;
        };
    }

    private static boolean isSubscriptBracketPredecessor(
            @Nullable GdToken token,
            boolean wasMemberName,
            boolean wasAfterTypeIntroducer,
            boolean wasStatementStart
    ) {
        if (token == null) {
            return false;
        }
        if (wasMemberName && isNodeName(token)) {
            return true;
        }
        if (token.type() == GdTokenType.IDENTIFIER
                && token.source().equals("Dictionary")
                && wasAfterTypeIntroducer) {
            return false;
        }
        return switch (token.type()) {
            case IDENTIFIER, WHEN, CONST_PI, CONST_TAU, CONST_INF, CONST_NAN,
                 SELF, LITERAL, PARENTHESIS_CLOSE, BRACKET_CLOSE, BRACE_CLOSE -> true;
            case MATCH -> !wasStatementStart;
            default -> false;
        };
    }

    private boolean settleExitedSubsuiteOpeners(
            @NotNull ArrayDeque<LocalBracketOpener> deque,
            int remainingDepth,
            boolean ancestorSameKindCloserMissing,
            int suiteIndentBaseline
    ) {
        var settledAny = false;
        while (!deque.isEmpty()) {
            circuitBreakerStackOps++;
            var top = deque.peekLast();
            if (top.depth <= remainingDepth) {
                break;
            }
            ensureLocalOpenerOracleResolved(top);
            if (!top.oracleMissing) {
                if (current.type() == GdTokenType.COMMA && top.allowsComma) {
                    break;
                }
                // Only settle an oracle-paired grouping opener when an enclosing same-kind
                // delimiter lacks a closer and the paired closer sits shallower than the opener's
                // own line, either on the current outer separator line or shallower than the suite.
                var canSettlePairedGrouping = ancestorSameKindCloserMissing
                        && top.pairedCloserByte >= 0
                        && top.pairedCloserIndent < top.openerIndent
                        && ((current.startByte() < top.pairedCloserByte
                                        && top.pairedCloserLine == current.startLine())
                                || (suiteIndentBaseline > 0
                                        && top.pairedCloserIndent < suiteIndentBaseline));
                if (!canSettlePairedGrouping) {
                    break;
                }
            }
            circuitBreakerStackOps++;
            deque.removeLast();
            settledAny = true;
        }
        return settledAny;
    }

    private boolean settleHardStatementOpeners(
            @NotNull ArrayDeque<LocalBracketOpener> deque,
            int remainingDepth
    ) {
        var settledAny = false;
        while (!deque.isEmpty()) {
            circuitBreakerStackOps++;
            if (deque.peekLast().depth < remainingDepth) {
                break;
            }
            circuitBreakerStackOps++;
            deque.removeLast();
            settledAny = true;
        }
        return settledAny;
    }

    private boolean hasExitedSubsuiteOpener(
            @NotNull ArrayDeque<LocalBracketOpener> deque,
            int remainingDepth
    ) {
        if (deque.isEmpty()) {
            return false;
        }
        circuitBreakerStackOps++;
        return deque.peekLast().depth > remainingDepth;
    }

    private boolean isCircuitBreakerHardStatementStarter(
            @NotNull GdTokenType type,
            @NotNull ArrayDeque<LocalBracketOpener> localBraces) {
        return switch (type) {
            case CLASS, CLASS_NAME, EXTENDS, ENUM, VAR, CONST, SIGNAL, STATIC, ANNOTATION,
                 RETURN, PASS, BREAK, CONTINUE, BREAKPOINT, WHILE, FOR, ASSERT -> true;
            case IF -> isStatementHeadColonLineOnSourceBytes(current.endByte(), true, current.startByte(), -1);
            case MATCH -> {
                if (!localBraces.isEmpty()) {
                    var brace = localBraces.peekLast();
                    ensureLocalOpenerOracleResolved(brace);
                    if (brace.oracleStatus == BracketBalanceOracle.OpenerStatus.MATCHED
                            && brace.pairedCloserByte >= 0
                            && current.startByte() < brace.pairedCloserByte) {
                        yield false;
                    }
                }
                yield isStatementHeadColonLineOnSourceBytes(current.endByte(), false, current.startByte(), -1);
            }
            default -> false;
        };
    }

    /// Distinguishes a statement-head `if <cond>:` / `match <expr>:` on the current logical line
    /// from an inline `if <cond> else <rhs>` or expression-position `match(arg)` at local bracket
    /// depth 0 without mutating parser/tokenizer state. Aligns `\` line-continuation (strict `\n`
    /// or `\r\n`, followed by any whitespace, blank lines, and `#...` comment lines), UTF-8
    /// identifier/lexical-error boundaries, node paths, nested ternaries, block lambdas, and
    /// matched dictionary-key separators with `GdTokenizer`.
    private boolean isStatementHeadColonLineOnSourceBytes(
            int pos,
            boolean stopAtElse,
            int headTokenStartByte,
            int dictCloserByte
    ) {
        var len = sourceBytes.length;
        var headLineIndent = lineIndentOf(headTokenStartByte);
        var depth = 0;
        var nestedTernaryDepth = 0;
        var lastNonWsByte = 0;
        var afterOperand = false;
        final var nodePathNone = 0;
        final var nodePathExpectSegment = 1;
        final var nodePathAfterSegment = 2;
        var nodePathState = nodePathNone;
        while (pos < len) {
            statementHeadColonScanSteps++;
            var b = sourceBytes[pos] & 0xFF;
            if (b == '\n' || b == '#') {
                return false;
            }
            if (b == '\r') {
                if (pos + 1 < len && sourceBytes[pos + 1] == '\n') {
                    return false;
                }
                pos++;
                continue;
            }
            if (b == ' ' || b == '\t') {
                pos++;
                continue;
            }
            if (b == '\\') {
                var continuedPos = skipLineContinuationOnSourceBytes(pos);
                if (continuedPos > pos) {
                    pos = continuedPos;
                    continue;
                }
                pos++;
                continue;
            }
            if (isStringStartAt(pos)) {
                pos = skipStringBytes(pos);
                lastNonWsByte = '"';
                nodePathState = nodePathState == nodePathExpectSegment ? nodePathAfterSegment : nodePathNone;
                afterOperand = true;
                continue;
            }
            if (b == '(' || b == '[' || b == '{') {
                nodePathState = nodePathNone;
                if (depth == 0) {
                    var fact = bracketOracle().factFor(pos);
                    if (fact != null
                            && fact.status() == BracketBalanceOracle.OpenerStatus.MATCHED
                            && fact.pairedCloserByte() > pos) {
                        pos = fact.pairedCloserByte() + 1;
                        lastNonWsByte = sourceBytes[fact.pairedCloserByte()] & 0xFF;
                        afterOperand = true;
                        continue;
                    }
                }
                depth++;
                lastNonWsByte = b;
                afterOperand = false;
                pos++;
                continue;
            }
            if (b == ')' || b == ']' || b == '}') {
                nodePathState = nodePathNone;
                if (depth == 0) {
                    return false;
                }
                depth--;
                lastNonWsByte = b;
                afterOperand = true;
                pos++;
                continue;
            }
            if (b == ',' && depth == 0) {
                return false;
            }
            if (b == ':' && depth == 0) {
                if (stopAtElse) {
                    return dictCloserByte < 0
                            || isDictionaryColonStatementHeadTail(
                            pos + 1, headTokenStartByte, headLineIndent, dictCloserByte);
                }
                var tail = pos + 1;
                while (tail < len) {
                    statementHeadColonScanSteps++;
                    var tb = sourceBytes[tail] & 0xFF;
                    if (tb == ' ' || tb == '\t') {
                        tail++;
                        continue;
                    }
                    if (tb == '\r') {
                        if (tail + 1 < len && sourceBytes[tail + 1] == '\n') {
                            break;
                        }
                        tail++;
                        continue;
                    }
                    if (tb == '\n' || tb == '#') {
                        break;
                    }
                    if (tb == '\\') {
                        // An explicit line continuation stops the single-line header check so
                        // `match .5: \\\n\t1: pass` is not misread as ending at `:`.
                        if (skipLineContinuationOnSourceBytes(tail) > tail) {
                            return false;
                        }
                        tail++;
                        continue;
                    }
                    var errorRadixEnd = GdTokenizer.errorOnlyRadixLiteralEnd(sourceBytes, tail);
                    if (errorRadixEnd >= 0) {
                        tail = errorRadixEnd;
                        continue;
                    }
                    var tcp = utf8CodePointAt(tail);
                    if (!isValidNonErrorStartCodePoint(tcp)) {
                        tail += utf8ByteLengthAt(tail);
                        continue;
                    }
                    return false;
                }
                if (tail < len && sourceBytes[tail] == '#') {
                    while (tail < len && sourceBytes[tail] != '\n') {
                        statementHeadColonScanSteps++;
                        tail++;
                    }
                }
                return tail == len
                        || sourceBytes[tail] == '\n'
                        || (sourceBytes[tail] == '\r' && tail + 1 < len && sourceBytes[tail + 1] == '\n');
            }
            if (b == '$') {
                nodePathState = nodePathExpectSegment;
                afterOperand = false;
                lastNonWsByte = '$';
                pos++;
                continue;
            }
            if (b == '%') {
                if (nodePathState == nodePathExpectSegment
                        || (nodePathState == nodePathNone && !afterOperand)) {
                    nodePathState = nodePathExpectSegment;
                } else {
                    nodePathState = nodePathNone;
                }
                afterOperand = false;
                lastNonWsByte = '%';
                pos++;
                continue;
            }
            if (b == '/') {
                if (nodePathState == nodePathAfterSegment
                        || (nodePathState == nodePathExpectSegment && lastNonWsByte == '$')) {
                    nodePathState = nodePathExpectSegment;
                } else {
                    nodePathState = nodePathNone;
                }
                afterOperand = false;
                lastNonWsByte = '/';
                pos++;
                continue;
            }
            var errorRadixEnd = GdTokenizer.errorOnlyRadixLiteralEnd(sourceBytes, pos);
            if (errorRadixEnd >= 0) {
                pos = errorRadixEnd;
                continue;
            }
            if (b >= '0' && b <= '9') {
                nodePathState = nodePathNone;
                afterOperand = true;
                lastNonWsByte = b;
                pos++;
                continue;
            }
            var cp = utf8CodePointAt(pos);
            var cpBytes = utf8ByteLengthAt(pos);
            if (GdTokenizer.isUnicodeIdentifierStart(cp)) {
                var start = pos;
                pos += cpBytes;
                while (pos < len) {
                    var nextCp = utf8CodePointAt(pos);
                    if (!GdTokenizer.isUnicodeIdentifierContinue(nextCp)) {
                        break;
                    }
                    pos += utf8ByteLengthAt(pos);
                }
                if (nodePathState == nodePathExpectSegment) {
                    nodePathState = nodePathAfterSegment;
                    afterOperand = true;
                    lastNonWsByte = sourceBytes[pos - 1] & 0xFF;
                    continue;
                }
                nodePathState = nodePathNone;
                if (depth == 0 && lastNonWsByte != '.') {
                    if (isKeywordBytes(start, pos, "func")) {
                        var afterLambda = advancePastLambdaAfterFunc(pos, headLineIndent);
                        if (afterLambda == -2) {
                            return false;
                        }
                        if (afterLambda >= 0) {
                            pos = afterLambda;
                            lastNonWsByte = ')';
                            afterOperand = true;
                            continue;
                        }
                    }
                    if (stopAtElse) {
                        if (isKeywordBytes(start, pos, "if")) {
                            nestedTernaryDepth++;
                        } else if (isKeywordBytes(start, pos, "else")) {
                            if (nestedTernaryDepth > 0) {
                                nestedTernaryDepth--;
                            } else {
                                return false;
                            }
                        }
                    }
                }
                afterOperand = lastNonWsByte == '.' || !isNonOperandKeywordBytes(start, pos);
                lastNonWsByte = sourceBytes[pos - 1] & 0xFF;
                continue;
            }
            if (!isValidNonErrorStartCodePoint(cp)) {
                pos += cpBytes;
                continue;
            }
            var isTrailingDecimalDot = b == '.'
                    && lastNonWsByte >= '0' && lastNonWsByte <= '9'
                    && (pos + 1 >= len
                    || (sourceBytes[pos + 1] != '.'
                    && !GdTokenizer.isUnicodeIdentifierStart(utf8CodePointAt(pos + 1))));
            nodePathState = nodePathNone;
            afterOperand = isTrailingDecimalDot;
            lastNonWsByte = b;
            pos++;
        }
        return false;
    }

    private boolean isNonOperandKeywordBytes(int start, int end) {
        return switch (end - start) {
            case 2 -> isKeywordBytes(start, end, "as")
                    || isKeywordBytes(start, end, "if")
                    || isKeywordBytes(start, end, "in")
                    || isKeywordBytes(start, end, "is")
                    || isKeywordBytes(start, end, "or");
            case 3 -> isKeywordBytes(start, end, "and")
                    || isKeywordBytes(start, end, "for")
                    || isKeywordBytes(start, end, "not")
                    || isKeywordBytes(start, end, "var");
            case 4 -> isKeywordBytes(start, end, "elif")
                    || isKeywordBytes(start, end, "else")
                    || isKeywordBytes(start, end, "enum")
                    || isKeywordBytes(start, end, "func")
                    || isKeywordBytes(start, end, "pass")
                    || isKeywordBytes(start, end, "void");
            case 5 -> isKeywordBytes(start, end, "await")
                    || isKeywordBytes(start, end, "break")
                    || isKeywordBytes(start, end, "class")
                    || isKeywordBytes(start, end, "const")
                    || isKeywordBytes(start, end, "trait")
                    || isKeywordBytes(start, end, "while")
                    || isKeywordBytes(start, end, "yield");
            case 6 -> isKeywordBytes(start, end, "assert")
                    || isKeywordBytes(start, end, "return")
                    || isKeywordBytes(start, end, "signal")
                    || isKeywordBytes(start, end, "static");
            case 7 -> isKeywordBytes(start, end, "extends")
                    || isKeywordBytes(start, end, "preload");
            case 8 -> isKeywordBytes(start, end, "continue");
            case 9 -> isKeywordBytes(start, end, "namespace");
            case 10 -> isKeywordBytes(start, end, "breakpoint")
                    || isKeywordBytes(start, end, "class_name");
            default -> false;
        };
    }

    private boolean isDictionaryColonStatementHeadTail(
            int posAfterColon,
            int headTokenStartByte,
            int headLineIndent,
            int dictCloserByte
    ) {
        var tail = skipSpacesContinuationsAndLexicalErrorsOnSourceBytes(posAfterColon);
        var len = sourceBytes.length;
        if (tail < len && sourceBytes[tail] == '#') {
            while (tail < len && sourceBytes[tail] != '\n') {
                statementHeadColonScanSteps++;
                tail++;
            }
        }
        if (tail >= len) {
            return false;
        }
        var b = sourceBytes[tail] & 0xFF;
        if (b == '\n' || (b == '\r' && tail + 1 < len && sourceBytes[tail + 1] == '\n')) {
            tail += b == '\r' ? 2 : 1;
            while (tail < len) {
                var nextLineIndent = 0;
                while (tail < len) {
                    statementHeadColonScanSteps++;
                    var c = sourceBytes[tail];
                    if (c == '\t') {
                        nextLineIndent += GdTokenizer.TAB_SIZE;
                        tail++;
                    } else if (c == ' ') {
                        nextLineIndent++;
                        tail++;
                    } else if (c == '\r' && (tail + 1 >= len || sourceBytes[tail + 1] != '\n')) {
                        tail++;
                    } else {
                        break;
                    }
                }
                if (tail >= len) {
                    return false;
                }
                var first = sourceBytes[tail] & 0xFF;
                if (first == '\r' && tail + 1 < len && sourceBytes[tail + 1] == '\n') {
                    tail += 2;
                    continue;
                }
                if (first == '\n') {
                    tail++;
                    continue;
                }
                if (first == '#') {
                    while (tail < len && sourceBytes[tail] != '\n') {
                        statementHeadColonScanSteps++;
                        tail++;
                    }
                    if (tail < len) {
                        tail++;
                    }
                    continue;
                }
                if (nextLineIndent <= headLineIndent) {
                    return false;
                }
                var suiteHead = skipSpacesContinuationsAndLexicalErrorsOnSourceBytes(tail);
                if (suiteHead < len && startsStatementOrMatchSuiteOnLine(suiteHead, suiteHead)) {
                    return true;
                }
                return !hasDictionaryValueEvidenceOnSingleLine(tail, dictCloserByte);
            }
            return false;
        }
        return startsStatementOrMatchSuiteOnLine(tail, headTokenStartByte);
    }

    private boolean startsStatementOrMatchSuiteOnLine(int tokenStart, int headTokenStartByte) {
        var len = sourceBytes.length;
        var cp = utf8CodePointAt(tokenStart);
        if (!GdTokenizer.isUnicodeIdentifierStart(cp)) {
            return false;
        }
        var end = tokenStart + utf8ByteLengthAt(tokenStart);
        while (end < len && GdTokenizer.isUnicodeIdentifierContinue(utf8CodePointAt(end))) {
            end += utf8ByteLengthAt(end);
        }
        if (isStatementOnlyKeywordAfterColon(tokenStart, end)) {
            return true;
        }
        if (!isKeywordBytes(tokenStart, end, "match")) {
            return false;
        }
        var afterMatch = skipSpacesContinuationsAndLexicalErrorsOnSourceBytes(end);
        if (afterMatch >= len) {
            return false;
        }
        var nextByte = sourceBytes[afterMatch] & 0xFF;
        if ((nextByte == '.'
                && !(afterMatch + 1 < len
                && sourceBytes[afterMatch + 1] >= '0'
                && sourceBytes[afterMatch + 1] <= '9'))
                || nextByte == ','
                || nextByte == '}') {
            return false;
        }
        return isStatementHeadColonLineOnSourceBytes(afterMatch, false, headTokenStartByte, -1);
    }

    private boolean hasDictionaryValueEvidenceOnSingleLine(int pos, int dictCloserByte) {
        var len = sourceBytes.length;
        var depth = 0;
        var sawDepthZeroAssignment = false;
        var inInlineLambdaBody = false;
        var lastNonWsByte = 0;
        while (pos < len) {
            statementHeadColonScanSteps++;
            var b = sourceBytes[pos] & 0xFF;
            if (b == '\n' || b == '#' || (b == '\r' && pos + 1 < len && sourceBytes[pos + 1] == '\n')) {
                return false;
            }
            if (b == ' ' || b == '\t' || b == '\r') {
                pos++;
                continue;
            }
            if (b == '\\') {
                var cont = skipLineContinuationOnSourceBytes(pos);
                if (cont > pos) {
                    pos = cont;
                    continue;
                }
                pos++;
                continue;
            }
            if (isStringStartAt(pos)) {
                pos = skipStringBytes(pos);
                lastNonWsByte = '"';
                continue;
            }
            if (b == '(' || b == '[' || b == '{') {
                if (depth == 0) {
                    var fact = bracketOracle().factFor(pos);
                    if (fact != null
                            && fact.status() == BracketBalanceOracle.OpenerStatus.MATCHED
                            && fact.pairedCloserByte() > pos) {
                        var closerByte = fact.pairedCloserByte();
                        lastNonWsByte = sourceBytes[closerByte] & 0xFF;
                        pos = closerByte + 1;
                        continue;
                    }
                }
                depth++;
                lastNonWsByte = b;
                pos++;
                continue;
            }
            if (b == ')' || b == ']' || b == '}') {
                if (depth == 0) {
                    if (b == '}') {
                        return pos == dictCloserByte && !sawDepthZeroAssignment;
                    }
                    lastNonWsByte = b;
                    pos++;
                    continue;
                }
                depth--;
                lastNonWsByte = b;
                pos++;
                continue;
            }
            if (depth == 0) {
                if (b == ',') {
                    return true;
                }
                if (b == '=') {
                    if (pos + 1 < len && sourceBytes[pos + 1] == '=') {
                        lastNonWsByte = '=';
                        pos += 2;
                        continue;
                    }
                    if (!inInlineLambdaBody) {
                        sawDepthZeroAssignment = true;
                    }
                    lastNonWsByte = '=';
                    pos++;
                    continue;
                }
                if (b == '!' || b == '<' || b == '>') {
                    if ((b == '<' && pos + 2 < len && sourceBytes[pos + 1] == '<'
                            && sourceBytes[pos + 2] == '=')
                            || (b == '>' && pos + 2 < len && sourceBytes[pos + 1] == '>'
                            && sourceBytes[pos + 2] == '=')) {
                        if (!inInlineLambdaBody) {
                            sawDepthZeroAssignment = true;
                        }
                        lastNonWsByte = '=';
                        pos += 3;
                        continue;
                    }
                    if (pos + 1 < len && sourceBytes[pos + 1] == '=') {
                        lastNonWsByte = '=';
                        pos += 2;
                        continue;
                    }
                }
                var errorRadixEnd = GdTokenizer.errorOnlyRadixLiteralEnd(sourceBytes, pos);
                if (errorRadixEnd >= 0) {
                    pos = errorRadixEnd;
                    continue;
                }
                var cp = utf8CodePointAt(pos);
                var cpBytes = utf8ByteLengthAt(pos);
                if (GdTokenizer.isUnicodeIdentifierStart(cp)) {
                    var identEnd = pos + cpBytes;
                    while (identEnd < len && GdTokenizer.isUnicodeIdentifierContinue(utf8CodePointAt(identEnd))) {
                        statementHeadColonScanSteps++;
                        identEnd += utf8ByteLengthAt(identEnd);
                    }
                    if (!inInlineLambdaBody
                            && lastNonWsByte != '.'
                            && isKeywordBytes(pos, identEnd, "func")
                            && isLambdaHeaderBytes(identEnd)) {
                        inInlineLambdaBody = true;
                    }
                    pos = identEnd;
                    lastNonWsByte = 'a';
                    continue;
                }
                if (!isValidNonErrorStartCodePoint(cp)) {
                    pos += cpBytes;
                    continue;
                }
            }
            lastNonWsByte = b;
            pos += utf8ByteLengthAt(pos);
        }
        return false;
    }

    private boolean isStatementOnlyKeywordAfterColon(int start, int end) {
        return switch (end - start) {
            case 2 -> isKeywordBytes(start, end, "if");
            case 3 -> isKeywordBytes(start, end, "var") || isKeywordBytes(start, end, "for");
            case 4 -> isKeywordBytes(start, end, "pass")
                    || isKeywordBytes(start, end, "elif")
                    || isKeywordBytes(start, end, "else")
                    || isKeywordBytes(start, end, "enum");
            case 5 -> isKeywordBytes(start, end, "const")
                    || isKeywordBytes(start, end, "break")
                    || isKeywordBytes(start, end, "while")
                    || isKeywordBytes(start, end, "class");
            case 6 -> isKeywordBytes(start, end, "return")
                    || isKeywordBytes(start, end, "assert")
                    || isKeywordBytes(start, end, "signal")
                    || isKeywordBytes(start, end, "static");
            case 8 -> isKeywordBytes(start, end, "continue");
            case 10 -> isKeywordBytes(start, end, "breakpoint");
            default -> false;
        };
    }

    private int skipLineContinuationOnSourceBytes(int pos) {
        var len = sourceBytes.length;
        var nextPos = pos + 1;
        if (nextPos < len && sourceBytes[nextPos] == '\r'
                && nextPos + 1 < len && sourceBytes[nextPos + 1] == '\n') {
            nextPos += 2;
        } else if (nextPos < len && sourceBytes[nextPos] == '\n') {
            nextPos++;
        } else {
            return pos;
        }
        while (nextPos < len) {
            while (nextPos < len
                    && (sourceBytes[nextPos] == ' ' || sourceBytes[nextPos] == '\t' || sourceBytes[nextPos] == '\r')) {
                statementHeadColonScanSteps++;
                nextPos++;
            }
            if (nextPos < len && sourceBytes[nextPos] == '#') {
                while (nextPos < len && sourceBytes[nextPos] != '\n') {
                    statementHeadColonScanSteps++;
                    nextPos++;
                }
            }
            if (nextPos < len && sourceBytes[nextPos] == '\n') {
                statementHeadColonScanSteps++;
                nextPos++;
                continue;
            }
            break;
        }
        return nextPos;
    }

    private int skipSpacesContinuationsAndLexicalErrorsOnSourceBytes(int pos) {
        var len = sourceBytes.length;
        while (pos < len) {
            statementHeadColonScanSteps++;
            var c = sourceBytes[pos] & 0xFF;
            if (c == ' ' || c == '\t') {
                pos++;
                continue;
            }
            if (c == '\r') {
                if (pos + 1 < len && sourceBytes[pos + 1] == '\n') {
                    break;
                }
                pos++;
                continue;
            }
            if (c == '\\') {
                var continuedPos = skipLineContinuationOnSourceBytes(pos);
                if (continuedPos > pos) {
                    pos = continuedPos;
                    continue;
                }
                pos++;
                continue;
            }
            if (c == '\n' || c == '#') {
                break;
            }
            var errorRadixEnd = GdTokenizer.errorOnlyRadixLiteralEnd(sourceBytes, pos);
            if (errorRadixEnd >= 0) {
                pos = errorRadixEnd;
                continue;
            }
            var cp = utf8CodePointAt(pos);
            if (!isValidNonErrorStartCodePoint(cp)) {
                pos += utf8ByteLengthAt(pos);
                continue;
            }
            break;
        }
        return pos;
    }

    private boolean isLambdaHeaderBytes(int funcEnd) {
        return findLambdaParamOpen(funcEnd) >= 0;
    }

    private int findLambdaParamOpen(int funcEnd) {
        var len = sourceBytes.length;
        var look = skipSpacesContinuationsAndLexicalErrorsOnSourceBytes(funcEnd);
        if (look < len && (sourceBytes[look] & 0xFF) == '(') {
            return look;
        }
        if (look < len && GdTokenizer.isUnicodeIdentifierStart(utf8CodePointAt(look))) {
            look += utf8ByteLengthAt(look);
            while (look < len) {
                var nextCp = utf8CodePointAt(look);
                if (!GdTokenizer.isUnicodeIdentifierContinue(nextCp)) {
                    break;
                }
                look += utf8ByteLengthAt(look);
            }
            look = skipSpacesContinuationsAndLexicalErrorsOnSourceBytes(look);
            if (look < len && (sourceBytes[look] & 0xFF) == '(') {
                return look;
            }
        }
        return -1;
    }

    /// Inspects a depth-0 `func` token to see whether it starts an unparenthesized lambda header.
    /// Returns `-1` if `func` is not a lambda header, `-2` for an inline or header-terminated
    /// lambda (cannot be followed by an enclosing suite colon), or a byte offset `>= 0` pointing
    /// to the first non-blank, non-comment line at indent `<= headLineIndent` after an indented
    /// block lambda body.
    private int advancePastLambdaAfterFunc(int funcEnd, int headLineIndent) {
        var len = sourceBytes.length;
        var paramOpen = findLambdaParamOpen(funcEnd);
        if (paramOpen < 0) {
            return -1;
        }

        var p = paramOpen;
        var paramFact = bracketOracle().factFor(paramOpen);
        if (paramFact != null
                && paramFact.status() == BracketBalanceOracle.OpenerStatus.MATCHED
                && paramFact.pairedCloserByte() > paramOpen) {
            statementHeadColonScanSteps++;
            p = paramFact.pairedCloserByte() + 1;
        } else {
            var parenDepth = 1;
            p++;
            while (p < len && parenDepth > 0) {
                statementHeadColonScanSteps++;
                var b = sourceBytes[p] & 0xFF;
                if (b == '\n' || b == '#') {
                    return -2;
                }
                if (b == '\\') {
                    var cont = skipLineContinuationOnSourceBytes(p);
                    if (cont > p) {
                        p = cont;
                        continue;
                    }
                    p++;
                    continue;
                }
                if (isStringStartAt(p)) {
                    p = skipStringBytes(p);
                    continue;
                }
                if (b == '(') {
                    parenDepth++;
                } else if (b == ')') {
                    parenDepth--;
                }
                p++;
            }
            if (parenDepth > 0) {
                return -2;
            }
        }

        var subDepth = 0;
        var foundColon = false;
        while (p < len) {
            statementHeadColonScanSteps++;
            var b = sourceBytes[p] & 0xFF;
            if (b == '\n' || b == '#') {
                return -2;
            }
            if (b == ' ' || b == '\t' || b == '\r') {
                p++;
                continue;
            }
            if (b == '\\') {
                var cont = skipLineContinuationOnSourceBytes(p);
                if (cont > p) {
                    p = cont;
                    continue;
                }
                p++;
                continue;
            }
            if (isStringStartAt(p)) {
                p = skipStringBytes(p);
                continue;
            }
            if (b == '(' || b == '[' || b == '{') {
                if (subDepth == 0) {
                    var fact = bracketOracle().factFor(p);
                    if (fact != null
                            && fact.status() == BracketBalanceOracle.OpenerStatus.MATCHED
                            && fact.pairedCloserByte() > p) {
                        p = fact.pairedCloserByte() + 1;
                        continue;
                    }
                }
                subDepth++;
                p++;
                continue;
            }
            if (b == ')' || b == ']' || b == '}') {
                if (subDepth == 0) {
                    return -2;
                }
                subDepth--;
                p++;
                continue;
            }
            if (b == ':' && subDepth == 0) {
                p++;
                foundColon = true;
                break;
            }
            p += utf8ByteLengthAt(p);
        }
        if (!foundColon) {
            return -2;
        }

        var sawNewline = false;
        while (p < len) {
            statementHeadColonScanSteps++;
            var b = sourceBytes[p] & 0xFF;
            if (b == ' ' || b == '\t') {
                p++;
                continue;
            }
            if (b == '\r') {
                p++;
                continue;
            }
            if (b == '\\') {
                var cont = skipLineContinuationOnSourceBytes(p);
                if (cont > p) {
                    p = cont;
                    continue;
                }
                p++;
                continue;
            }
            if (b == '#') {
                while (p < len && sourceBytes[p] != '\n') {
                    statementHeadColonScanSteps++;
                    p++;
                }
                continue;
            }
            if (b == '\n') {
                p++;
                sawNewline = true;
                break;
            }
            var errorRadixEnd = GdTokenizer.errorOnlyRadixLiteralEnd(sourceBytes, p);
            if (errorRadixEnd >= 0) {
                p = errorRadixEnd;
                continue;
            }
            var cp = utf8CodePointAt(p);
            if (!isValidNonErrorStartCodePoint(cp)) {
                p += utf8ByteLengthAt(p);
                continue;
            }
            return -2;
        }
        if (!sawNewline) {
            return -2;
        }

        while (p < len) {
            var width = 0;
            while (p < len) {
                statementHeadColonScanSteps++;
                var b = sourceBytes[p];
                if (b == '\t') {
                    width += GdTokenizer.TAB_SIZE;
                    p++;
                } else if (b == ' ') {
                    width++;
                    p++;
                } else if (b == '\r' && (p + 1 >= len || sourceBytes[p + 1] != '\n')) {
                    p++;
                } else {
                    break;
                }
            }
            if (p >= len) {
                return -2;
            }
            var first = sourceBytes[p] & 0xFF;
            if (first == '\r' && p + 1 < len && sourceBytes[p + 1] == '\n') {
                p += 2;
                continue;
            }
            if (first == '\n') {
                p++;
                continue;
            }
            if (first == '#') {
                while (p < len && sourceBytes[p] != '\n') {
                    statementHeadColonScanSteps++;
                    p++;
                }
                if (p < len) {
                    p++;
                }
                continue;
            }
            if (width <= headLineIndent) {
                return p;
            }
            while (p < len) {
                statementHeadColonScanSteps++;
                var c = sourceBytes[p] & 0xFF;
                if (c == '\n') {
                    p++;
                    break;
                }
                if (c == '#') {
                    while (p < len && sourceBytes[p] != '\n') {
                        statementHeadColonScanSteps++;
                        p++;
                    }
                    if (p < len) {
                        p++;
                    }
                    break;
                }
                if (c == '\\') {
                    var cont = skipLineContinuationOnSourceBytes(p);
                    if (cont > p) {
                        p = cont;
                        continue;
                    }
                    p++;
                    continue;
                }
                if (isStringStartAt(p)) {
                    p = skipStringBytes(p);
                    continue;
                }
                if (c == '(' || c == '[' || c == '{') {
                    var fact = bracketOracle().factFor(p);
                    if (fact != null
                            && fact.status() == BracketBalanceOracle.OpenerStatus.MATCHED
                            && fact.pairedCloserByte() > p) {
                        p = fact.pairedCloserByte() + 1;
                        continue;
                    }
                }
                p++;
            }
        }
        return -2;
    }

    private boolean isStringStartAt(int pos) {
        var b = sourceBytes[pos] & 0xFF;
        return b == '"' || b == '\''
                || ((b == 'r' || b == '&' || b == '^') && pos + 1 < sourceBytes.length
                && (sourceBytes[pos + 1] == '"' || sourceBytes[pos + 1] == '\''));
    }

    private boolean isKeywordBytes(int start, int end, @NotNull String keyword) {
        var len = keyword.length();
        if (end - start != len) {
            return false;
        }
        for (var i = 0; i < len; i++) {
            if ((sourceBytes[start + i] & 0xFF) != keyword.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    private int utf8CodePointAt(int pos) {
        var b0 = sourceBytes[pos] & 0xFF;
        if (b0 < 0x80) {
            return b0;
        }
        var len = sourceBytes.length;
        if ((b0 & 0xE0) == 0xC0 && pos + 1 < len) {
            var b1 = sourceBytes[pos + 1] & 0xFF;
            if ((b1 & 0xC0) == 0x80) {
                return ((b0 & 0x1F) << 6) | (b1 & 0x3F);
            }
        } else if ((b0 & 0xF0) == 0xE0 && pos + 2 < len) {
            var b1 = sourceBytes[pos + 1] & 0xFF;
            var b2 = sourceBytes[pos + 2] & 0xFF;
            if ((b1 & 0xC0) == 0x80 && (b2 & 0xC0) == 0x80) {
                return ((b0 & 0x0F) << 12) | ((b1 & 0x3F) << 6) | (b2 & 0x3F);
            }
        } else if ((b0 & 0xF8) == 0xF0 && pos + 3 < len) {
            var b1 = sourceBytes[pos + 1] & 0xFF;
            var b2 = sourceBytes[pos + 2] & 0xFF;
            var b3 = sourceBytes[pos + 3] & 0xFF;
            if ((b1 & 0xC0) == 0x80 && (b2 & 0xC0) == 0x80 && (b3 & 0xC0) == 0x80) {
                return ((b0 & 0x07) << 18) | ((b1 & 0x3F) << 12) | ((b2 & 0x3F) << 6) | (b3 & 0x3F);
            }
        }
        return b0;
    }

    private int utf8ByteLengthAt(int pos) {
        var b0 = sourceBytes[pos] & 0xFF;
        if (b0 < 0x80) {
            return 1;
        }
        var len = sourceBytes.length;
        if ((b0 & 0xE0) == 0xC0 && pos + 1 < len && (sourceBytes[pos + 1] & 0xC0) == 0x80) {
            return 2;
        }
        if ((b0 & 0xF0) == 0xE0 && pos + 2 < len
                && (sourceBytes[pos + 1] & 0xC0) == 0x80
                && (sourceBytes[pos + 2] & 0xC0) == 0x80) {
            return 3;
        }
        if ((b0 & 0xF8) == 0xF0 && pos + 3 < len
                && (sourceBytes[pos + 1] & 0xC0) == 0x80
                && (sourceBytes[pos + 2] & 0xC0) == 0x80
                && (sourceBytes[pos + 3] & 0xC0) == 0x80) {
            return 4;
        }
        return 1;
    }

    private static boolean isValidNonErrorStartCodePoint(int cp) {
        if (cp >= '0' && cp <= '9') {
            return true;
        }
        if (GdTokenizer.isUnicodeIdentifierStart(cp)) {
            return true;
        }
        return switch (cp) {
            case '"', '\'', '@', '~', ',', ':', ';', '$', '?', '`',
                 '(', '[', '{', ')', ']', '}',
                 '!', '.', '+', '-', '*', '/', '%', '^', '&', '|', '=', '<', '>' -> true;
            default -> false;
        };
    }

    private int skipStringBytes(int pos) {
        var len = sourceBytes.length;
        var isRaw = false;
        var b = sourceBytes[pos] & 0xFF;
        if (b == 'r') {
            isRaw = true;
            pos++;
        } else if (b == '&' || b == '^') {
            pos++;
        }
        if (pos >= len) {
            return pos;
        }
        var quote = sourceBytes[pos++] & 0xFF;
        var isTriple = pos + 1 < len
                && (sourceBytes[pos] & 0xFF) == quote
                && (sourceBytes[pos + 1] & 0xFF) == quote;
        if (isTriple) {
            pos += 2;
        }
        while (pos < len) {
            var c = sourceBytes[pos] & 0xFF;
            if (c == '\\') {
                pos++;
                if (pos >= len) {
                    return pos;
                }
                if (isRaw) {
                    if ((sourceBytes[pos] & 0xFF) == quote || (sourceBytes[pos] & 0xFF) == '\\') {
                        pos++;
                    }
                } else {
                    pos++;
                }
                continue;
            }
            if (c == quote) {
                if (isTriple) {
                    if (pos + 2 < len
                            && (sourceBytes[pos + 1] & 0xFF) == quote
                            && (sourceBytes[pos + 2] & 0xFF) == quote) {
                        return pos + 3;
                    }
                    pos++;
                    continue;
                }
                return pos + 1;
            }
            pos++;
        }
        return pos;
    }

    /// Settles a contiguous chain of open bracketed frames iteratively in one forward scan after
    /// the nesting budget has been tripped, keeping nested unpushed openers balanced against
    /// matching closers, stopping only at an ancestor-owned closer below the chain or a real outer
    /// layout/declaration boundary, and reconciling any suppressed multiline layout on exit.
    private boolean unwindPoisonedBracketChain(@NotNull StructuralFrame targetFrame) {
        var chain = new ArrayDeque<StructuralFrame>();
        var it = structuralStack.descendingIterator();
        while (it.hasNext()) {
            var frame = it.next();
            if (!frame.isBracketed()) {
                break;
            }
            chain.addLast(frame);
        }
        if (chain.isEmpty() || chain.getFirst() != targetFrame) {
            popStructuralFrame(targetFrame);
            if (targetFrame.ownsMultilineFrame) {
                popMultiline(targetFrame.recordCommentSpan, extentEnd());
                replayLayoutIfExitedMultiline();
            }
            return false;
        }

        // `chain` is a local ArrayDeque (null elements are rejected on insert) and the early
        // return above guarantees `chain` is non-empty, so `getLast()` cannot be null and fails
        // fast if that invariant is ever violated.
        var outermost = chain.getLast();
        var outermostFact = bracketOracle().factFor(outermost.openerStartByte);
        var outermostIsMissing = outermostFact == null
                || outermostFact.status() == BracketBalanceOracle.OpenerStatus.MISSING;
        var suiteBaseline = enclosingSuiteBaseline(outermost);
        var rejectionLine = current.startLine();
        var unpushedClosers = new ArrayDeque<GdTokenType>();
        var targetClosedCleanly = false;

        while (!chain.isEmpty() && !isAtEnd()) {
            var type = current.type();
            if (type == GdTokenType.PARENTHESIS_OPEN
                    || type == GdTokenType.BRACKET_OPEN
                    || type == GdTokenType.BRACE_OPEN) {
                unpushedClosers.addLast(matchingCloserFor(type));
                advance();
                continue;
            }
            if (isCloserToken(type)) {
                if (!unpushedClosers.isEmpty()) {
                    var expectedUnpushed = unpushedClosers.getLast();
                    if (expectedUnpushed == type) {
                        unpushedClosers.removeLast();
                        advance();
                        continue;
                    }
                    if (unpushedClosers.contains(type)) {
                        while (!unpushedClosers.isEmpty() && unpushedClosers.getLast() != type) {
                            unpushedClosers.removeLast();
                        }
                        if (!unpushedClosers.isEmpty()) {
                            unpushedClosers.removeLast();
                        }
                        advance();
                        continue;
                    }
                    unpushedClosers.clear();
                }

                // `chain` is still guaranteed non-empty here: the loop header checked
                // `!chain.isEmpty()`, `chain` is method-local and untouched by the opener and
                // `unpushedClosers` branches above, and every removal path below `continue`s back
                // to the loop header. Use `getFirst()` to express non-nullability and fail fast.
                var top = chain.getFirst();
                if (top.expectedCloser == type) {
                    chain.removeFirst();
                    popStructuralFrame(top);
                    if (top.ownsMultilineFrame) {
                        popMultiline(top.recordCommentSpan, current.startByte());
                    }
                    if (top == targetFrame) {
                        targetClosedCleanly = true;
                    }
                    advance();
                    continue;
                }

                StructuralFrame matchedInChain = null;
                for (var candidate : chain) {
                    if (candidate.expectedCloser == type) {
                        matchedInChain = candidate;
                        break;
                    }
                }
                if (matchedInChain != null) {
                    while (!chain.isEmpty()) {
                        var abandoned = chain.removeFirst();
                        popStructuralFrame(abandoned);
                        if (abandoned.ownsMultilineFrame) {
                            popMultiline(abandoned.recordCommentSpan,
                                    abandoned == matchedInChain ? current.startByte() : extentEnd());
                        }
                        if (abandoned == matchedInChain) {
                            if (abandoned == targetFrame) {
                                targetClosedCleanly = true;
                            }
                            break;
                        }
                    }
                    advance();
                    continue;
                }

                if (hasMatchingAncestorCloser(type)) {
                    // Closer belongs to an enclosing healthy frame below the poisoned chain.
                    break;
                }

                chain.removeFirst();
                popStructuralFrame(top);
                if (top.ownsMultilineFrame) {
                    popMultiline(top.recordCommentSpan, current.startByte());
                }
                advance();
                continue;
            }

            if (type == GdTokenType.DEDENT) {
                break;
            }
            if (type == GdTokenType.NEWLINE && !tokenizer.isMultilineMode()) {
                break;
            }
            if (current.startLine() > rejectionLine && !currentFollowedContinuation
                    && current.startByte() < sourceBytes.length && isLineStartToken(current)) {
                var indent = lineIndentOf(current.startByte());
                if (outermostIsMissing) {
                    if (indent < suiteBaseline
                            || outermostFact == null
                            || current.startByte() >= outermostFact.effectiveRecoveryEnd()
                            || (indent <= outermost.indentBaseline && type != GdTokenType.COMMA)) {
                        break;
                    }
                } else if (current.startByte() > outermostFact.pairedCloserByte()) {
                    break;
                }
            }
            advance();
        }

        while (!chain.isEmpty()) {
            var remaining = chain.removeFirst();
            popStructuralFrame(remaining);
            if (remaining.ownsMultilineFrame) {
                popMultiline(remaining.recordCommentSpan, extentEnd());
            }
        }
        replayLayoutIfExitedMultiline();
        nestingBudgetExceeded = false;
        return targetClosedCleanly;
    }

    /// Unified 4-way closer settlement for all delimiter-enclosed constructs:
    /// 1) exact closer match on current frame: restore layout and consume closer;
    /// 2) closer owned by an ancestor frame: emit zero-width missing closer, settle current frame,
    ///    and leave the closer token for the ancestor;
    /// 3) unowned closer token: restore layout, emit mismatched closer over the token span, and
    ///    consume that single wrong token;
    /// 4) non-closer or EOF: emit zero-width missing closer (unless suppressed for empty grouping
    ///    at a non-closer boundary) without consuming lookahead, replaying suppressed layout if
    ///    multiline suppression just ended.
    private boolean consumeCloser(@NotNull StructuralFrame frame, boolean suppressMissingOnNonCloser) {
        if (frame.settled) {
            return false;
        }
        if (nestingBudgetExceeded) {
            return unwindPoisonedBracketChain(frame);
        }
        if (current.type() == frame.expectedCloser) {
            settlePendingLexicalErrorsOnAdvance(false);
            popStructuralFrame(frame);
            if (frame.ownsMultilineFrame) {
                popMultiline(frame.recordCommentSpan, current.startByte());
            }
            advance();
            return true;
        }
        if (isCloserToken(current.type()) && hasMatchingAncestorCloser(current.type())) {
            claimInScopePostNewlineErrorsBeforeCloserExit(false);
            popStructuralFrame(frame);
            if (frame.ownsMultilineFrame) {
                popMultiline(frame.recordCommentSpan, extentEnd());
                replayLayoutIfExitedMultiline();
            }
            var point = extentEnd();
            recoveryTracker.emitError(
                    Objects.requireNonNull(frame.missingCloserMessage),
                    range(point, point),
                    Objects.requireNonNull(frame.missingCloserCode),
                    RecoveryTransactionTracker.AttributionPolicy.CURRENT_FRAME,
                    null
            );
            return false;
        }
        if (isCloserToken(current.type())) {
            settlePendingLexicalErrorsOnAdvance(true);
            popStructuralFrame(frame);
            if (frame.ownsMultilineFrame) {
                popMultiline(frame.recordCommentSpan, current.startByte());
            }
            recoveryTracker.emitError(
                    "Expected \"%s\" to close %s, found \"%s\" instead.".formatted(
                            Objects.requireNonNull(frame.expectedCloser).displayName(),
                            frame.constructDescription,
                            current.type().displayName()),
                    tokenRange(current),
                    Objects.requireNonNull(frame.mismatchedCloserCode),
                    RecoveryTransactionTracker.AttributionPolicy.CURRENT_FRAME,
                    null
            );
            advance();
            return false;
        }
        claimInScopePostNewlineErrorsBeforeCloserExit(true);
        popStructuralFrame(frame);
        if (frame.ownsMultilineFrame) {
            popMultiline(frame.recordCommentSpan, extentEnd());
            replayLayoutIfExitedMultiline();
        }
        if (!suppressMissingOnNonCloser) {
            var point = extentEnd();
            recoveryTracker.emitError(
                    Objects.requireNonNull(frame.missingCloserMessage),
                    range(point, point),
                    Objects.requireNonNull(frame.missingCloserCode),
                    RecoveryTransactionTracker.AttributionPolicy.CURRENT_FRAME,
                    null
            );
        }
        return false;
    }

    private void claimInScopePostNewlineErrorsBeforeCloserExit(boolean capAtCurrentLinePrefix) {
        var maxEndByte = capAtCurrentLinePrefix
                ? escapingLinePrefixCutoffByte(false)
                : Integer.MAX_VALUE;
        var mask = inScopePostNewlineErrorMask(true, true, maxEndByte);
        if (mask == null || currentPostNewlineErrors == null) {
            return;
        }
        var active = recoveryTracker.activeFrame();
        var claimedSpan = recoveryTracker.claimLexicalBatchSubset(currentPostNewlineErrors, mask, active);
        if (claimedSpan != null) {
            claimedLexicalEndByte = Math.max(claimedLexicalEndByte,
                    Math.max(extentEnd(), claimedSpan.endByte()));
        }
    }

    private boolean consumeCloser(@NotNull StructuralFrame frame) {
        return consumeCloser(frame, false);
    }

    /// An exact closer settles both lexical batches unfiltered. Look ahead only while that token
    /// still belongs to this frame; a token left for an ancestor or sibling cannot taint it.
    private boolean hasLexicalDamageOnExactCloser(@NotNull StructuralFrame frame) {
        return !frame.settled && !nestingBudgetExceeded && current.type() == frame.expectedCloser
                && hasLexicalDamageOnAdvance(false);
    }

    /// Emits a coded standalone diagnostic anchored at `previous`.
    private void pushError(String message, @NotNull String code) {
        pushError(message, previous, code);
    }

    private void pushError(String message, GdToken origin, @NotNull String code) {
        recoveryTracker.emitLegacyError(message, tokenRange(origin), code);
    }

    /// Marker for an opened expression slot whose parse produced no node: a zero-width MISSING
    /// node at the failure point for `Missing`, otherwise an ERROR fragment over the consumed span
    /// (`Failed` may be zero-consumption when the depth budget rejected the slot before any token
    /// moved, in which case the fragment degenerates to zero-width at `extentEnd()`). Never moves
    /// the cursor; the right edge uses `extentEnd()` like every enclosing node.
    private @NotNull ErrorExpression slotMarker(ParseOutcome<?> outcome, int slotStartByte, String slotName) {
        var end = extentEnd();
        return switch (outcome) {
            case ParseOutcome.Missing<?> _ ->
                    new ErrorExpression(CstIssueKind.MISSING, slotName, "", range(end, end));
            case ParseOutcome.Failed<?> _ -> {
                var start = Math.min(slotStartByte, end);
                yield new ErrorExpression(CstIssueKind.ERROR, DIAGNOSTIC_NODE_TYPE,
                        start < end ? slice(start, end) : "", range(start, end));
            }
            default -> throw new IllegalStateException("slotMarker requires a node-less outcome, got: " + outcome);
        };
    }

    /// Keeps a slot's carried node or materializes a marker when none could be built.
    /// Only `Missing` needs a context message; the other damaged outcomes already have a cause.
    private @NotNull Expression absorbSlot(ParseOutcome<Expression> outcome, int slotStartByte, String slotName, String contextMessage) {
        return switch (outcome) {
            case ParseOutcome.Ok<Expression>(var node) -> node;
            case ParseOutcome.EnclosedDamage<Expression>(var node) -> node;
            case ParseOutcome.Damaged<Expression>(var node) -> node;
            case ParseOutcome.Missing<Expression> _ -> {
                if (hasCurrentSlotLexicalErrors()) {
                    yield claimLexicalErrorSlotValue(slotStartByte, false);
                }
                recoveryTracker.emitError(
                        contextMessage,
                        tokenRange(previous),
                        DiagnosticCodes.slotCode(slotName),
                        RecoveryTransactionTracker.AttributionPolicy.CURRENT_FRAME,
                        null
                );
                yield slotMarker(outcome, slotStartByte, slotName);
            }
            case ParseOutcome.Failed<Expression> _ -> {
                if (hasCurrentSlotLexicalErrors()) {
                    yield claimLexicalErrorSlotValue(slotStartByte, true);
                }
                yield slotMarker(outcome, slotStartByte, slotName);
            }
        };
    }

    /// Opens an owner context for a facet that may hold a damaged expression. Inside an isolated
    /// property suite, child frames inherit the owning `variable.property` fallback; inside a
    /// regular or lambda statement suite, child frames inherit the immediate `suite.body` fallback
    /// so discarded failed constructs never drop diagnostics.
    private void pushRecoveryOwner(String facet) {
        if (isolatedFallbackFrame != null) {
            recoveryTracker.pushChildFrame(facet, isolatedFallbackFrame, "variable.property");
        } else if (suiteBodyFallbackFrame != null) {
            recoveryTracker.pushChildFrame(facet, suiteBodyFallbackFrame, "suite.body");
        } else if (lambdaBodyFallbackFrame != null) {
            recoveryTracker.pushChildFrame(facet, lambdaBodyFallbackFrame, "suite.body");
        } else {
            recoveryTracker.pushFrame(facet);
        }
    }

    /// Opens a parameter slot owner anchored to its enclosing signature/signal frame so dropped
    /// duplicate parameters still attribute supported diagnostics to the outer declaration.
    private void pushParameterSlotOwner(
            @NotNull String facet,
            RecoveryTransactionTracker.@Nullable ProvisionalFrame signatureFrame
    ) {
        if (signatureFrame != null) {
            recoveryTracker.pushChildFrame(facet, signatureFrame, signatureFrame.facet());
        } else {
            pushRecoveryOwner(facet);
        }
    }

    /// Closes the innermost owner context and returns it for later binding. The frame is popped
    /// right after its facet's expression parse (before any suite runs), so closer diagnostics
    /// from a following body cannot mis-bind to this facet.
    private @NotNull RecoveryTransactionTracker.ProvisionalFrame popRecoveryOwner() {
        return recoveryTracker.popFrame();
    }

    /// Commits a stashed frame to the built owner node. Frames whose owner is never built are
    /// abandoned or fall back via their authorized parent chain; unreachable links are swept at
    /// the end of parsing.
    private void bindRecoveryOwner(@NotNull RecoveryTransactionTracker.ProvisionalFrame frame, @NotNull Node owner) {
        recoveryTracker.commit(frame, owner);
    }

    /// Advances the comment cursor past every trivia comment starting before the current token
    /// WITHOUT materializing nodes — used at sequence boundaries where the reference path drops
    /// comments (suite-header lines, the match branch level).
    private void discardPendingComments() {
        while (commentCursor < comments.size() && comments.get(commentCursor).startByte() < current.startByte()) {
            commentCursor++;
        }
    }

    /// Discards only trailing (end-of-line) comments before the given byte offset, returning the
    /// end byte of the last discarded comment (`0` when none). Whole-line comments keep pending
    /// for the sequence that owns them. Used when entering a suite: a comment trailing the header
    /// line (`if x: # c`) is dropped by both paths.
    private int discardTrailingCommentsBefore(int byteOffset) {
        var floorByte = 0;
        while (commentCursor < comments.size() && !comments.get(commentCursor).wholeLine()
                && comments.get(commentCursor).startByte() < byteOffset) {
            floorByte = comments.get(commentCursor++).endByte();
        }
        return floorByte;
    }

    /// Moves the pending comment prefix before `limitByte` out of the cursor stream (see the
    /// `comments` field doc): those comments belong to the enclosing flow, not to the construct
    /// about to be parsed, and are restored afterwards by {@link #restoreDeferredComments}.
    private void deferCommentsBefore(int limitByte) {
        var deferred = new ArrayList<GdComment>();
        while (commentCursor < comments.size() && comments.get(commentCursor).startByte() < limitByte) {
            deferred.add(comments.get(commentCursor++));
        }
        deferredCommentStack.addLast(deferred);
    }

    /// Restores the innermost deferred prefix at the cursor head. Everything restored precedes
    /// anything consumed in the meantime, so byte order is preserved (nested lambdas restore
    /// inside-out, which keeps the nesting order correct).
    private void restoreDeferredComments() {
        comments.addAll(commentCursor, deferredCommentStack.removeLast());
    }

    /// Materializes one comment trivia as a statement node: `#region…` /
    /// `#endregion…` become `RegionDirectiveStatement` (start label is the text after the
    /// keyword, trimmed, `null` when empty; the end label is always `null`), everything else a
    /// `CommentStatement` with the full comment text (leading `#` included).
    private @NotNull Statement materializeComment(@NotNull GdComment comment) {
        var range = range(comment.startByte(), comment.endByte());
        var text = comment.text();
        if (text.startsWith("#endregion")) {
            return new RegionDirectiveStatement("end", null, range);
        }
        if (text.startsWith("#region")) {
            var label = text.substring("#region".length()).trim();
            return new RegionDirectiveStatement("start", label.isEmpty() ? null : label, range);
        }
        return new CommentStatement(text, range);
    }

    /// Boundary drain before a statement: pending annotations and pending comments (of either
    /// kind) merge in byte order up to `limitByte`. Whole-line comments reach a boundary only
    /// when the next statement belongs to the same sequence, and stray trailing comments (left
    /// over by prefix statements or error paths) take their byte position here.
    private @NotNull List<Statement> drainAnnotationsAndComments(int limitByte, boolean allowScriptRoot) {
        var drained = new ArrayList<Statement>();
        while (true) {
            skipGroupingInternalComments();
            var hasAnnotation = !pendingAnnotations.isEmpty();
            var hasComment = commentCursor < comments.size()
                    && comments.get(commentCursor).startByte() < limitByte;
            if (!hasAnnotation && !hasComment) {
                break;
            }
            var annotationStart = hasAnnotation
                    ? pendingAnnotations.getFirst().statement().range().startByte()
                    : Integer.MAX_VALUE;
            if (hasComment && comments.get(commentCursor).startByte() < annotationStart) {
                drained.add(materializeComment(comments.get(commentCursor++)));
            } else {
                var pending = pendingAnnotations.removeFirst();
                drained.add(materializePendingAnnotation(pending, false, allowScriptRoot));
            }
        }
        return drained;
    }

    private @NotNull List<Statement> drainCommentsWithPending(
            @NotNull List<PendingAnnotation> annotations,
            int limitByte
    ) {
        var drained = new ArrayList<Statement>();
        var annIndex = 0;
        while (true) {
            skipGroupingInternalComments();
            var hasAnnotation = annIndex < annotations.size();
            var hasComment = commentCursor < comments.size()
                    && comments.get(commentCursor).startByte() < limitByte;
            if (!hasAnnotation && !hasComment) {
                break;
            }
            var annotationStart = hasAnnotation
                    ? annotations.get(annIndex).statement().range().startByte()
                    : Integer.MAX_VALUE;
            if (hasComment && comments.get(commentCursor).startByte() < annotationStart) {
                drained.add(materializeComment(comments.get(commentCursor++)));
            } else {
                drained.add(annotations.get(annIndex++).statement());
            }
        }
        return drained;
    }

    /// Block-start anchor for indented suites/class bodies: the newline token's start, backed
    /// over an immediately preceding CRLF `\r` and spaces/tabs down to `minByte` (trailing header
    /// whitespace belongs to the block span on the reference path; a trailing header comment does
    /// not).
    private int layoutBlockStart(@NotNull GdToken newlineToken, int minByte) {
        var start = newlineToken.startByte();
        if (start > minByte && sourceBytes[start - 1] == '\r') {
            start--;
        }
        while (start > minByte && (sourceBytes[start - 1] == ' ' || sourceBytes[start - 1] == '\t')) {
            start--;
        }
        return start;
    }

    /// Drains trailing (end-of-line) comments passed since the last statement: they materialize
    /// as the same sequence's siblings right after the statement they follow.
    private @NotNull List<Statement> drainTrailingComments() {
        var drained = new ArrayList<Statement>();
        while (true) {
            skipGroupingInternalComments();
            if (commentCursor >= comments.size() || comments.get(commentCursor).wholeLine()
                    || comments.get(commentCursor).startByte() >= current.startByte()) {
                break;
            }
            drained.add(materializeComment(comments.get(commentCursor++)));
        }
        return drained;
    }

    /// Tail drain at a dedent boundary: whole-line comments materialize into this body only
    /// while their indentation reaches the body's indentation (merged with dangling annotations
    /// in byte order); less-indented comments stay pending for the outer sequence.
    private @NotNull List<Statement> drainTailWithAnnotations(int bodyIndent, boolean allowScriptRoot) {
        var drained = new ArrayList<Statement>();
        while (true) {
            skipGroupingInternalComments();
            var hasAnnotation = !pendingAnnotations.isEmpty();
            var hasComment = commentCursor < comments.size()
                    && comments.get(commentCursor).startByte() < current.startByte()
                    && comments.get(commentCursor).wholeLine()
                    && indentOf(comments.get(commentCursor)) >= bodyIndent;
            if (!hasAnnotation && !hasComment) {
                break;
            }
            var annotationStart = hasAnnotation
                    ? pendingAnnotations.getFirst().statement().range().startByte()
                    : Integer.MAX_VALUE;
            if (hasComment && comments.get(commentCursor).startByte() < annotationStart) {
                drained.add(materializeComment(comments.get(commentCursor++)));
            } else {
                var pending = pendingAnnotations.removeFirst();
                drained.add(materializePendingAnnotation(pending, true, allowScriptRoot));
            }
        }
        return drained;
    }

    /// Indentation width of a whole-line comment (its column equals its indentation, since the
    /// comment is the first non-whitespace content of its line).
    private int indentOf(@NotNull GdComment comment) {
        return index.pointAt(comment.startByte()).column();
    }

    /// Indentation width of the line containing the given byte offset (the offset's column when
    /// it sits right after the leading whitespace, as statement-start tokens do).
    private int indentOf(int byteOffset) {
        return index.pointAt(byteOffset).column();
    }

    /// Port of `GDScriptParser::synchronize`: same sync token set (`if` deliberately excluded
    /// because it can appear inside expressions); stops before the sync token without consuming.
    /// While a depth rejection is being unwound (nestingBudgetExceeded), the scan instead
    /// consumes the aborted construct's whole layout region: every deeper-indented line plus its
    /// INDENT/DEDENT tokens (balance-counted — a DEDENT beyond that balance belongs to a parsed
    /// enclosing suite), stopping at the first line that dedents back to the tripped line's
    /// indentation. This keeps garbage layout from ending outer scopes early.
    private void synchronize() {
        if (!structuralStack.isEmpty()) {
            structuralStack.peekLast().pendingSync = false;
        }
        var garbageIndents = 0;
        var localParens = 0;
        var localBrackets = 0;
        var localBraces = 0;
        while (!isAtEnd()) {
            if (nestingBudgetExceeded) {
                if (current.type() == GdTokenType.INDENT) {
                    garbageIndents++;
                    advance();
                    continue;
                }
                if (current.type() == GdTokenType.DEDENT) {
                    if (garbageIndents > 0) {
                        garbageIndents--;
                        advance();
                        continue;
                    }
                    break;
                }
                if (current.type() == GdTokenType.NEWLINE) {
                    advance();
                    continue;
                }
                // Line-start check: a layout token (NEWLINE/INDENT/DEDENT) always precedes the
                // first content token of a physical line, so gate on any of them.
                if ((previous.type() == GdTokenType.NEWLINE || previous.type() == GdTokenType.INDENT
                        || previous.type() == GdTokenType.DEDENT)
                        && lineIndentOf(current.startByte()) <= poisonedLineIndent) {
                    break;
                }
                advance();
                continue;
            }
            if (garbageIndents == 0
                    && (previous.type() == GdTokenType.NEWLINE || previous.type() == GdTokenType.SEMICOLON)) {
                break;
            }
            if (garbageIndents == 0 && localParens == 0 && localBrackets == 0 && localBraces == 0) {
                if (current.type() == GdTokenType.DEDENT) {
                    break;
                }
                if (current.type() == GdTokenType.COMMA && hasCommaOwningAncestor()) {
                    if (inLambda) {
                        lambdaEnded = true;
                    }
                    break;
                }
                if (isCloserToken(current.type())
                        && (hasMatchingAncestorCloser(current.type())
                                || (inLambda && canDeferReplacementCloserToEnclosingDelimiter()))) {
                    if (inLambda) {
                        lambdaEnded = true;
                    }
                    break;
                }
                switch (current.type()) {
                    case CLASS, FUNC, STATIC, VAR, CONST, SIGNAL, ENUM, FOR, WHILE, MATCH, RETURN, ANNOTATION -> {
                        // Stop before the sync token without consuming it.
                        return;
                    }
                    default -> {
                    }
                }
            }
            switch (current.type()) {
                case INDENT -> garbageIndents++;
                case DEDENT -> {
                    if (garbageIndents > 0) {
                        garbageIndents--;
                    }
                }
                case PARENTHESIS_OPEN -> localParens++;
                case PARENTHESIS_CLOSE -> {
                    if (localParens > 0) {
                        localParens--;
                    }
                }
                case BRACKET_OPEN -> localBrackets++;
                case BRACKET_CLOSE -> {
                    if (localBrackets > 0) {
                        localBrackets--;
                    }
                }
                case BRACE_OPEN -> localBraces++;
                case BRACE_CLOSE -> {
                    if (localBraces > 0) {
                        localBraces--;
                    }
                }
                default -> {
                }
            }
            advance();
        }
        // Reached a statement/layout boundary (or EOF): the poisoned region ends here.
        nestingBudgetExceeded = false;
        if (!structuralStack.isEmpty()) {
            structuralStack.peekLast().pendingSync = false;
        }
    }

    /// Enters one input-driven nesting level. On budget exhaustion this reports once and
    /// returns false; the caller must then unwind without recursing further (the remaining
    /// tokens are left for the enclosing recovery paths, which consume them iteratively).
    /// A true result must be paired with {@link #exitNestingLevel()} in a finally block.
    private boolean enterNestingLevel() {
        if (nestingDepth >= MAX_NESTING_DEPTH) {
            if (!nestingBudgetExceeded) {
                var policy = recoveryTracker.isBalanced()
                        ? RecoveryTransactionTracker.AttributionPolicy.STANDALONE
                        : RecoveryTransactionTracker.AttributionPolicy.CURRENT_FRAME;
                recoveryTracker.emitError(
                        "Maximum nesting depth exceeded.",
                        tokenRange(current),
                        DiagnosticCodes.NESTING_DEPTH_EXCEEDED,
                        policy,
                        null
                );
                nestingBudgetExceeded = true;
                var bracketOwner = currentMultilineBracketedFrame();
                poisonedLineIndent = bracketOwner != null
                        ? bracketOwner.indentBaseline
                        : lineIndentOf(current.startByte());
            }
            return false;
        }
        nestingDepth++;
        return true;
    }

    private void exitNestingLevel() {
        nestingDepth--;
    }

    /// Indentation width of the line containing the given byte offset, in the tokenizer's
    /// layout units (tab = 4, space = 1; see `GdTokenizer`'s indent accounting): the width of
    /// the leading whitespace before the line's first non-whitespace character.
    private int lineIndentOf(int byteOffset) {
        var row = index.pointAt(byteOffset).row();
        ensureLineMetadataCached(row);
        return cachedLineIndent[row];
    }

    private int lineIndentForOneBasedLine(int oneBasedLine) {
        var row = oneBasedLine - 1;
        ensureLineMetadataCached(row);
        return cachedLineIndent[row];
    }

    private void ensureLineMetadataCached(int row) {
        if (cachedLineIndent[row] >= 0) {
            return;
        }
        var offset = index.lineStartByte(row);
        var width = 0;
        while (offset < sourceBytes.length) {
            var b = sourceBytes[offset];
            if (b == '\t') {
                width += GdTokenizer.TAB_SIZE;
            } else if (b == ' ') {
                width += 1;
            } else if (b == '\r'
                    && (offset + 1 >= sourceBytes.length || sourceBytes[offset + 1] != '\n')) {
                // Match GdTokenizer.checkIndent: count spaces/tabs across stray CR without
                // treating CRLF's CR as part of the indentation run.
                offset++;
                lineIndentScanSteps++;
                continue;
            } else {
                break;
            }
            offset++;
            lineIndentScanSteps++;
        }
        cachedLineIndent[row] = width;
        cachedLineFirstContentByte[row] = offset;
    }

    /// Port of `GDScriptParser::push_multiline(true)`, including the raw consumption of already
    /// pending NEWLINE/INDENT/DEDENT tokens (which must not touch `previous`).
    private void pushMultiline() {
        pushMultiline(true);
    }

    /// Port of `GDScriptParser::push_multiline`. The raw consumption of pending layout tokens
    /// only happens when enabling multiline mode (vendor `push_multiline(true)`); disabling is a
    /// plain stack push (used to isolate lambda bodies from an enclosing multiline context).
    private void pushMultiline(boolean state) {
        multilineStack.addLast(state);
        multilineStartStack.addLast(current.startByte());
        tokenizer.setMultilineMode(state);
        if (!state) {
            return;
        }
        // Discard pending layout tokens without touching `previous`. Unlike Godot (which
        // raw-scans here and can silently drop an ERROR token sitting in `current`), errors
        // are reported immediately — our diagnostics contract requires every tokenizer
        // error to surface.
        while (current.type() == GdTokenType.NEWLINE
                || current.type() == GdTokenType.INDENT
                || current.type() == GdTokenType.DEDENT) {
            if (currentPrecedingErrors != null) {
                recoveryTracker.flushLexicalBatchAsStandalone(currentPrecedingErrors);
            }
            if (currentPostNewlineErrors != null) {
                recoveryTracker.flushLexicalBatchAsStandalone(currentPostNewlineErrors);
            }
            var next = nextScannedToken();
            current = next.token();
            currentFollowedContinuation = next.followedContinuation();
            currentExplicitContinuation = next.explicitContinuation();
            currentLogicalStartLine = next.logicalStartLine();
            currentPrecedingErrors = next.precedingErrors();
            currentPostNewlineErrors = next.postNewlineErrors();
        }
    }

    /// Port of `GDScriptParser::pop_multiline`.
    private void popMultiline() {
        popMultiline(true);
    }

    /// `recordCommentSpan` is on for grouping-close pops: the popped frame's span is recorded so
    /// later drains skip the grouping-internal comments within it. The lambda-isolation pop must
    /// NOT record: comments after the lambda body still belong to the enclosing sequence.
    private void popMultiline(boolean recordCommentSpan) {
        popMultiline(recordCommentSpan, current.startByte());
    }

    private void popMultiline(boolean recordCommentSpan, int spanEndByte) {
        if (multilineStack.isEmpty()) {
            throw new IllegalStateException("GDScript parser bug: popping the multiline stack without a matching push.");
        }
        multilineStack.removeLast();
        var frameStart = multilineStartStack.removeLast();
        tokenizer.setMultilineMode(multilineStack.peekLast() != null && multilineStack.peekLast());
        if (recordCommentSpan && spanEndByte > frameStart) {
            groupingCommentSpans.add(new int[]{frameStart, spanEndByte});
        }
    }

    /// Whether the comment lies inside a closed grouping span (grouping-internal trivia that
    /// this parser silently ignores — see {@link #groupingCommentSpans}).
    private boolean isGroupingInternal(@NotNull GdComment comment) {
        for (var span : groupingCommentSpans) {
            if (comment.startByte() >= span[0] && comment.startByte() < span[1]) {
                return true;
            }
        }
        return false;
    }

    /// Skips pending grouping-internal comments at the cursor head. Returns the number skipped.
    private int skipGroupingInternalComments() {
        var skipped = 0;
        while (commentCursor < comments.size() && isGroupingInternal(comments.get(commentCursor))) {
            commentCursor++;
            skipped++;
        }
        return skipped;
    }

    private boolean isStatementEndToken() {
        return check(GdTokenType.NEWLINE) || check(GdTokenType.SEMICOLON) || check(GdTokenType.EOF);
    }

    /// Port of `GDScriptParser::is_statement_end`: inline-lambda virtual boundaries count as
    /// statement end only when the lambda already ended or the cursor sits at a real lambda
    /// boundary (layout token or enclosing delimiter/comma), so same-line trailing garbage inside
    /// an inline lambda still routes through `finishStatementProvisional` as `STATEMENT_TRAILING`.
    private boolean isStatementEnd() {
        return lambdaEnded || (inLambda && isVirtualLambdaBoundary()) || isStatementEndToken();
    }

    /// Consumes trailing statement terminators (`NEWLINE`/`SEMICOLON`) or inline-lambda boundaries,
    /// binds any `STATEMENT_TRAILING` diagnostic over the consumed trailing garbage fragment to the
    /// already-constructed `owner`, and marks `pendingSync` when unexpected trailing tokens were found.
    private void finishStatement(@NotNull Statement owner, @NotNull String context) {
        var trailingFrame = finishStatementProvisional(context);
        if (trailingFrame != null) {
            bindRecoveryOwner(trailingFrame, owner);
        }
    }

    private @NotNull RecoveryTransactionTracker.ProvisionalFrame claimTrailingLexicalBatch(
            @Nullable RecoveryTransactionTracker.ProvisionalFrame trailingFrame
    ) {
        var batch = Objects.requireNonNull(currentPrecedingErrors);
        if (trailingFrame == null) {
            pushRecoveryOwner("statement.trailing");
            trailingFrame = popRecoveryOwner();
        }
        recoveryTracker.claimLexicalBatch(batch, trailingFrame);
        return trailingFrame;
    }

    /// Provisional variant of {@link #finishStatement} for declarations whose AST node is finalized
    /// after the statement tail runs (`class_name`, nested `extends`, single-line properties, and
    /// bodyless functions). Returns the `"statement.trailing"` frame when trailing garbage produced a
    /// diagnostic so the caller can bind it once the owner node is constructed, or `null` on a clean tail.
    private @Nullable RecoveryTransactionTracker.ProvisionalFrame finishStatementProvisional(@NotNull String context) {
        RecoveryTransactionTracker.ProvisionalFrame trailingFrame = null;
        var found = false;
        while ((isStatementEnd() || (isCloserToken(current.type()) && !hasMatchingAncestorCloser(current.type())))
                && !isAtEnd()) {
            if (isStatementEndToken()) {
                if (hasCurrentLogicalLineLexicalErrors()) {
                    trailingFrame = claimTrailingLexicalBatch(trailingFrame);
                }
                advance();
                found = true;
            } else if (isCloserToken(current.type()) && !hasMatchingAncestorCloser(current.type())) {
                if (inLambda && canDeferReplacementCloserToEnclosingDelimiter()) {
                    if (hasCurrentLogicalLineLexicalErrors()) {
                        trailingFrame = claimTrailingLexicalBatch(trailingFrame);
                    }
                    // Defer an enclosing container's mismatched closer to the outer frame rather
                    // than consuming it as a standalone orphan inside the inline lambda body.
                    if (!found) {
                        lambdaEnded = true;
                        found = true;
                    }
                    break;
                }
                if (hasCurrentLogicalLineLexicalErrors()) {
                    trailingFrame = claimTrailingLexicalBatch(trailingFrame);
                }
                advance();
                emitUnexpectedCloser(previous);
            } else if (lambdaEnded) {
                if (hasCurrentLogicalLineLexicalErrors()) {
                    trailingFrame = claimTrailingLexicalBatch(trailingFrame);
                }
                // Consume the virtual token, like the vendor.
                lambdaEnded = false;
                found = true;
                break;
            } else {
                if (hasCurrentLogicalLineLexicalErrors()) {
                    trailingFrame = claimTrailingLexicalBatch(trailingFrame);
                }
                // In a lambda: something else ends the statement; mark the lambda as done — but
                // only when no real end token was consumed (vendor's `if (!found)`), otherwise a
                // following body statement on the next line would be cut off.
                if (!found) {
                    lambdaEnded = true;
                    found = true;
                }
                break;
            }
        }
        if (isAtEnd() && hasCurrentLogicalLineLexicalErrors()) {
            trailingFrame = claimTrailingLexicalBatch(trailingFrame);
        }
        if (!found && !isAtEnd()) {
            var trailingStartToken = current;
            var trailingStart = trailingStartToken.startByte();
            var trailingEnd = trailingStartToken.endByte();
            var message = "Expected end of statement after %s, found \"%s\" instead."
                    .formatted(context, trailingStartToken.type().displayName());
            if (hasCurrentLogicalLineLexicalErrors()) {
                trailingFrame = claimTrailingLexicalBatch(trailingFrame);
            }
            if (previous.type() != GdTokenType.NEWLINE
                    && previous.type() != GdTokenType.SEMICOLON
                    && previous.type() != GdTokenType.INDENT
                    && previous.type() != GdTokenType.DEDENT
                    && (current.startLine() <= previous.endLine() || currentFollowedContinuation)) {
                // Drain remaining tokens on the same logical line (including explicit `\`
                // continuations) so separator-less trailing declarations (e.g., `super. 0 var y = 1`
                // or `super. \\\n var y = 1`) cannot survive as a second statement, while stopping
                // before layout tokens, comma-owning lists, or ancestor closers.
                var drainLine = previous.endLine();
                var localDelimiters = new LocalDelimiterStack();
                while (!isAtEnd()) {
                    if (current.type() == GdTokenType.NEWLINE
                            || current.type() == GdTokenType.SEMICOLON
                            || current.type() == GdTokenType.INDENT
                            || current.type() == GdTokenType.DEDENT
                            || (current.startLine() > drainLine && !currentFollowedContinuation)) {
                        break;
                    }
                    if (current.type() == GdTokenType.COMMA
                            && hasCommaOwningAncestor()
                            && !localDelimiters.hasVerifiedCommaShield(current.startByte())) {
                        if (inLambda) {
                            lambdaEnded = true;
                        }
                        break;
                    }
                    if (isCloserToken(current.type())
                            && !localDelimiters.hasMatchingOpener(current.type())
                            && isAtAncestorOwnedCloser()) {
                        if (inLambda) {
                            lambdaEnded = true;
                        }
                        break;
                    }
                    if (hasCurrentLogicalLineLexicalErrors()) {
                        trailingFrame = claimTrailingLexicalBatch(trailingFrame);
                    }
                    localDelimiters.consume(current);
                    advance();
                    trailingEnd = Math.max(trailingEnd, previous.endByte());
                    drainLine = previous.endLine();
                }
                if (hasCurrentLogicalLineLexicalErrors()) {
                    trailingFrame = claimTrailingLexicalBatch(trailingFrame);
                }
                if (isStatementEndToken() && !isAtEnd()) {
                    advance();
                }
            }
            if (trailingFrame == null) {
                pushRecoveryOwner("statement.trailing");
                trailingFrame = popRecoveryOwner();
            }
            recoveryTracker.emitError(
                    message,
                    range(trailingStart, trailingEnd),
                    DiagnosticCodes.STATEMENT_TRAILING,
                    RecoveryTransactionTracker.AttributionPolicy.TARGET_FRAME,
                    trailingFrame
            );
            markCurrentFramePendingSync();
            return trailingFrame;
        }
        return trailingFrame;
    }

    // ---------------------------------------------------------------------------------------------
    // Program structure — port of parse_program / parse_class_body and their helpers.
    // ---------------------------------------------------------------------------------------------

    /// Port of `GDScriptParser::parse_program`: prefix annotations/docstrings, then `class_name` /
    /// `extends` in any order with at most one each, then the class body. Pending annotations are
    /// flushed before the statement that follows them, preserving source order.
    private @NotNull List<Statement> parseProgram(boolean leadingPendingSync) {
        var rootFrame = pushStructuralFrame(StructuralFrame.nonBracketed(
                StructuralRole.CLASS_BODY, 0, 0, true, false, "script body"));
        rootFrame.pendingSync = leadingPendingSync;
        try {
            var statements = new ArrayList<Statement>();
            var classNameUsed = false;

            // First prefix phase: script-level annotations and standalone docstrings, in any order.
            var canHaveClassOrExtends = true;
            while (!check(GdTokenType.EOF)) {
                if (match(GdTokenType.ANNOTATION)) {
                    var parsed = parseScopedAnnotation();
                    var ann = parsed.statement();
                    var kinds = knownAnnotationKinds(ann.name());
                    if (kinds != -1 && (kinds & ANN_STANDALONE) != 0) {
                        validateStandaloneDirective(parsed);
                        statements.addAll(drainAnnotationsAndComments(ann.range().startByte(), true));
                        bindRecoveryOwner(parsed.targetFrame(), ann);
                        statements.add(ann);
                        if (!isWarningIgnoreRegionDirective(ann.name())) {
                            canHaveClassOrExtends = false;
                            break;
                        }
                    } else if (kinds != -1 && (kinds & ANN_SCRIPT) != 0 && (kinds & ANN_CLASS) == 0) {
                        statements.addAll(drainAnnotationsAndComments(ann.range().startByte(), true));
                        validateScriptOnlyAnnotation(parsed);
                        bindRecoveryOwner(parsed.targetFrame(), ann);
                        statements.add(ann);
                    } else if (kinds != -1 && (kinds & ANN_CLASS) != 0) {
                        pendingAnnotations.add(new PendingAnnotation(
                                ann, parsed.targetFrame(), true, false, false));
                    } else {
                        pendingAnnotations.add(new PendingAnnotation(
                                ann, parsed.targetFrame(), false, false, false));
                        canHaveClassOrExtends = false;
                        break;
                    }
                    if (consumeCurrentFramePendingSync() || nestingBudgetExceeded) {
                        synchronize();
                    }
                } else if (notStandaloneDocstring()) {
                    break;
                }
            }

            // Second prefix phase: class_name / extends. Their relative order is free; anything else
            // (except further docstrings, kept for compatibility) ends the prefix.
            while (canHaveClassOrExtends) {
                switch (current.type()) {
                    case CLASS_NAME -> {
                        advance();
                        if (classNameUsed) {
                            pushError("\"class_name\" can only be used once.", DiagnosticCodes.SYNTAX_UNEXPECTED_TOKEN);
                        } else {
                            classNameUsed = true;
                            statementStartByte = computeStatementStart(previous);
                            statements.addAll(drainAnnotationsAndComments(current.startByte(), true));
                            statements.add(parseClassName());
                            statements.addAll(drainTrailingComments());
                        }
                    }
                    case EXTENDS -> {
                        advance();
                        if (scriptExtendsUsed) {
                            if (scriptExtendsFrame != null) {
                                recoveryTracker.pushChildFrame(
                                        "class.extends",
                                        scriptExtendsFrame,
                                        "class.extends"
                                );
                                recoveryTracker.emitError(
                                        "\"extends\" can only be used once.",
                                        tokenRange(previous),
                                        DiagnosticCodes.CLASS_DUPLICATE_EXTENDS,
                                        RecoveryTransactionTracker.AttributionPolicy.CURRENT_FRAME,
                                        null
                                );
                                parseExtendsTarget();
                                recoveryTracker.popFrame();
                                var trailingFrame = finishStatementProvisional("superclass");
                                var owner = scriptExtendsFrame.committedOwner();
                                if (trailingFrame != null && owner != null) {
                                    bindRecoveryOwner(trailingFrame, owner);
                                }
                            } else {
                                pushError("\"extends\" can only be used once.", DiagnosticCodes.SYNTAX_UNEXPECTED_TOKEN);
                                parseExtendsTarget();
                                finishStatementProvisional("superclass");
                            }
                        } else {
                            scriptExtendsUsed = true;
                            statementStartByte = computeStatementStart(previous);
                            statements.addAll(drainAnnotationsAndComments(current.startByte(), true));
                            var extendsStatement = parseExtendsStatement();
                            statements.add(extendsStatement);
                            finishStatement(extendsStatement, "superclass");
                            statements.addAll(drainTrailingComments());
                        }
                    }
                    case LITERAL -> {
                        if (notStandaloneDocstring()) {
                            canHaveClassOrExtends = false;
                        }
                    }
                    default -> canHaveClassOrExtends = false;
                }
                if (consumeCurrentFramePendingSync() || nestingBudgetExceeded) {
                    synchronize();
                }
            }

            parseClassBody(true, true, statements, 0);
            // Safety net for anything left over at EOF (e.g. a trailing comment after the last
            // member on a newline-less last line): everything remaining belongs to the top level.
            statements.addAll(drainAnnotationsAndComments(Integer.MAX_VALUE, true));

            if (!check(GdTokenType.EOF)) {
                pushError("Expected end of file.", DiagnosticCodes.SYNTAX_UNEXPECTED_TOKEN);
            }
            statements.addAll(drainAnnotations(true));
            return List.copyOf(statements);
        } finally {
            popStructuralFrame(rootFrame);
        }
    }

    /// Port of `GDScriptParser::parse_class_name` (including the same-line `extends` tail).
    private @NotNull Statement parseClassName() {
        var start = statementStartByte;
        String name = null;
        pushRecoveryOwner("declaration.name");
        if (consume(GdTokenType.IDENTIFIER,
                "Expected identifier for the global class name after \"class_name\".",
                DiagnosticCodes.DECLARATION_MISSING_NAME)) {
            name = previous.source();
        }
        var nameFrame = popRecoveryOwner();

        String extendsTarget = null;
        RecoveryTransactionTracker.ProvisionalFrame extendsFrame = null;
        var end = previous.endByte();
        RecoveryTransactionTracker.ProvisionalFrame trailingFrame;
        if (match(GdTokenType.EXTENDS)) {
            pushRecoveryOwner("class.extends");
            if (scriptExtendsUsed) {
                // Keep the earlier script-level extends target as first-wins; consume the
                // duplicate target text without publishing a second valid extends target.
                recoveryTracker.emitError(
                        "\"extends\" can only be used once.",
                        tokenRange(previous),
                        DiagnosticCodes.CLASS_DUPLICATE_EXTENDS,
                        RecoveryTransactionTracker.AttributionPolicy.CURRENT_FRAME,
                        null
                );
                parseExtendsTarget();
                extendsFrame = popRecoveryOwner();
            } else {
                scriptExtendsUsed = true;
                extendsTarget = parseExtendsTarget();
                extendsFrame = popRecoveryOwner();
                scriptExtendsFrame = extendsFrame;
            }
            end = previous.endByte();
            trailingFrame = finishStatementProvisional("superclass");
        } else {
            trailingFrame = finishStatementProvisional("class_name statement");
        }
        var classNameStatement = new ClassNameStatement(name, extendsTarget, range(start, end));
        bindRecoveryOwner(nameFrame, classNameStatement);
        if (extendsFrame != null) {
            bindRecoveryOwner(extendsFrame, classNameStatement);
        }
        if (trailingFrame != null) {
            bindRecoveryOwner(trailingFrame, classNameStatement);
        }
        return classNameStatement;
    }

    /// Wraps {@link #parseExtendsTarget} into an `ExtendsStatement` spanning from the `extends`
    /// keyword (already consumed) to the target end.
    private @NotNull Statement parseExtendsStatement() {
        var start = statementStartByte;
        pushRecoveryOwner("class.extends");
        var target = parseExtendsTarget();
        var extendsFrame = popRecoveryOwner();
        scriptExtendsFrame = extendsFrame;
        var end = previous.endByte();
        var statement = new ExtendsStatement(target, range(start, end));
        bindRecoveryOwner(extendsFrame, statement);
        return statement;
    }

    /// Port of `GDScriptParser::parse_extends`: a string literal path or an identifier chain
    /// (a literal may also lead a chain after a `.`). Returns the raw source slice of the target,
    /// matching the reference path's trimmed-text extraction.
    private @Nullable String parseExtendsTarget() {
        GdToken firstToken = null;
        if (match(GdTokenType.LITERAL)) {
            firstToken = previous;
            if (previous.literalKind() != GdLiteralKind.STRING) {
                emitInCurrentFrameOrLegacy("Only strings or identifiers can be used after \"extends\", found \"%s\" instead."
                        .formatted(variantTypeName(previous.literalKind())),
                        DiagnosticCodes.SYNTAX_UNEXPECTED_TOKEN);
            }
            if (!match(GdTokenType.PERIOD)) {
                return slice(firstToken.startByte(), previous.endByte());
            }
        }

        if (!consume(GdTokenType.IDENTIFIER, "Expected superclass name after \"extends\".",
                DiagnosticCodes.SYNTAX_UNEXPECTED_TOKEN)) {
            return firstToken == null ? null : slice(firstToken.startByte(), previous.endByte());
        }
        if (firstToken == null) {
            firstToken = previous;
        }
        while (match(GdTokenType.PERIOD)) {
            if (!consume(GdTokenType.IDENTIFIER, "Expected superclass name after \".\".",
                    DiagnosticCodes.SYNTAX_UNEXPECTED_TOKEN)) {
                break;
            }
        }
        return slice(firstToken.startByte(), previous.endByte());
    }

    /// Port of `GDScriptParser::parse_standalone_string`, with the result flipped to match every
    /// caller: a plain string literal standing alone is consumed as a docstring (produces no node)
    /// and this returns false; other literal kinds are left alone and this returns true.
    private boolean notStandaloneDocstring() {
        if (check(GdTokenType.LITERAL) && current.literalKind() == GdLiteralKind.STRING) {
            advance();
            if (!match(GdTokenType.NEWLINE)) {
                pushError("Expected newline after comment string.", DiagnosticCodes.SYNTAX_UNEXPECTED_TOKEN);
            }
            return false;
        }
        return true;
    }

    /// Port of `GDScriptParser::parse_class_body`, including the Godot 3.x removed-keyword
    /// migration diagnostics and the `static` prefix handling. Unlike Godot, a class-body `pass`
    /// produces a `PassStatement` (this AST keeps it).
    /// `bodyIndent` is the indentation width of the body's members (0 for the top-level body):
    /// whole-line comments in the tail window before the closing dedent belong to this body only
    /// when they reach that indentation.
    private void parseClassBody(boolean multiline, boolean isRoot, List<Statement> statements, int bodyIndent) {
        var classEnd = false;
        var nextIsStatic = false;
        var unexpectedIndentDepth = 0;
        while (!classEnd && !isAtEnd()) {
            var token = current;
            switch (token.type()) {
                case VAR -> {
                    beginMember(statements, nextIsStatic, ANN_VARIABLE, "variable");
                    advance();
                    var variable = parseVariable(nextIsStatic, true);
                    if (variable != null) {
                        statements.add(variable);
                    } else {
                        markCurrentFramePendingSync();
                    }
                }
                case CONST -> {
                    beginMember(statements, false, ANN_CONSTANT, "constant");
                    advance();
                    var constant = parseConstant();
                    if (constant != null) {
                        statements.add(constant);
                    } else {
                        markCurrentFramePendingSync();
                    }
                }
                case SIGNAL -> {
                    beginMember(statements, false, ANN_SIGNAL, "signal");
                    advance();
                    var signal = parseSignal();
                    if (signal != null) {
                        statements.add(signal);
                    } else {
                        markCurrentFramePendingSync();
                    }
                }
                case FUNC -> {
                    beginMember(statements, nextIsStatic, ANN_FUNCTION, "function");
                    advance();
                    var function = parseFunction(nextIsStatic);
                    if (function != null) {
                        statements.add(function);
                    } else {
                        markCurrentFramePendingSync();
                    }
                }
                case CLASS -> {
                    beginMember(statements, false, ANN_CLASS, "class");
                    advance();
                    statements.add(parseNestedClass());
                }
                case ENUM -> {
                    beginDanglingClassItem(statements);
                    advance();
                    statements.add(parseEnum());
                }
                case STATIC -> {
                    var followedByMember = peekAfterCurrent();
                    if (followedByMember == GdTokenType.FUNC) {
                        beginMember(statements, false, ANN_FUNCTION, "function");
                    } else if (followedByMember == GdTokenType.VAR) {
                        beginMember(statements, false, ANN_VARIABLE, "variable");
                    } else {
                        beginDanglingClassItem(statements);
                    }
                    advance();
                    // Only a directly following `func`/`var` keeps the static flag; on any other
                    // token the flag is dropped so the error does not leak into the sibling
                    // declaration that synchronization recovers to (Godot keeps the flag there,
                    // which mislabels the recovered sibling — a recovery-only refinement).
                    nextIsStatic = check(GdTokenType.FUNC) || check(GdTokenType.VAR);
                    if (!nextIsStatic) {
                        pushError("Expected \"func\" or \"var\" after \"static\".",
                                DiagnosticCodes.SYNTAX_UNEXPECTED_TOKEN);
                        markCurrentFramePendingSync();
                    }
                }
                case ANNOTATION -> {
                    advance();
                    var parsed = parseScopedAnnotation();
                    var ann = parsed.statement();
                    var kinds = knownAnnotationKinds(ann.name());
                    var allowedAtClassBody = kinds == -1
                            || (kinds & (ANN_CLASS_LEVEL | ANN_STANDALONE)) != 0;
                    if (!allowedAtClassBody) {
                        emitInvalidAnnotationLevel(parsed, (kinds & ANN_SCRIPT) != 0);
                        pendingAnnotations.add(new PendingAnnotation(
                                ann, parsed.targetFrame(), false, true, false));
                    } else if (kinds != -1 && (kinds & ANN_STANDALONE) != 0) {
                        validateStandaloneDirective(parsed);
                        statements.addAll(drainAnnotationsAndCommentsAsDangling(ann.range().startByte(), isRoot));
                        bindRecoveryOwner(parsed.targetFrame(), ann);
                        statements.add(ann);
                    } else {
                        pendingAnnotations.add(new PendingAnnotation(
                                ann, parsed.targetFrame(), false, false, false));
                    }
                }
                case PASS -> {
                    beginDanglingClassItem(statements);
                    advance();
                    var passStatement = new PassStatement(range(statementStartByte, previous.endByte()));
                    statements.add(passStatement);
                    finishStatement(passStatement, "\"pass\"");
                }
                case INDENT -> {
                    if (multiline) {
                        advance();
                        pushError("Unexpected %s in class body.".formatted(debugName(previous)),
                                DiagnosticCodes.SYNTAX_UNEXPECTED_TOKEN);
                        unexpectedIndentDepth++;
                    } else {
                        reportUnexpectedClassBodyToken();
                    }
                }
                case DEDENT -> {
                    if (multiline && unexpectedIndentDepth > 0) {
                        unexpectedIndentDepth--;
                        advance();
                    } else if (bodyIndent > 0) {
                        classEnd = true;
                    } else {
                        advance();
                    }
                }
                case LITERAL -> {
                    if (notStandaloneDocstring()) {
                        reportUnexpectedClassBodyToken();
                    }
                }
                default -> reportUnexpectedClassBodyToken();
            }
            if (token.type() != GdTokenType.STATIC) {
                nextIsStatic = false;
            }
            // A comment trailing the member's own line lands right after it.
            statements.addAll(drainTrailingComments());
            if (consumeCurrentFramePendingSync() || nestingBudgetExceeded) {
                synchronize();
            }
            if (!multiline) {
                classEnd = true;
            }
        }
        // Tail-window comments belong to this body only when indented into it; dangling
        // annotations also stay in this scope (merged in byte order, see the suite flush).
        if (multiline) {
            statements.addAll(drainTailWithAnnotations(bodyIndent, isRoot));
        } else {
            statements.addAll(drainAnnotations(isRoot));
        }
    }

    /// Default branch of the class-body dispatch: consumes one token and reports either a Godot
    /// 3.x removed-keyword migration diagnostic or the generic unexpected-token error.
    private void reportUnexpectedClassBodyToken() {
        advance();
        if (isCloserToken(previous.type())) {
            emitUnexpectedCloser(previous);
            markCurrentFramePendingSync();
            return;
        }
        var message = switch (previous.source()) {
            case "export" ->
                    "The \"export\" keyword was removed in Godot 4. Use an export annotation (\"@export\", \"@export_range\", etc.) instead.";
            case "tool" -> "The \"tool\" keyword was removed in Godot 4. Use the \"@tool\" annotation instead.";
            case "onready" ->
                    "The \"onready\" keyword was removed in Godot 4. Use the \"@onready\" annotation instead.";
            case "remote" ->
                    "The \"remote\" keyword was removed in Godot 4. Use the \"@rpc\" annotation with \"any_peer\" instead.";
            case "remotesync" ->
                    "The \"remotesync\" keyword was removed in Godot 4. Use the \"@rpc\" annotation with \"any_peer\" and \"call_local\" instead.";
            case "puppet" ->
                    "The \"puppet\" keyword was removed in Godot 4. Use the \"@rpc\" annotation with \"authority\" instead.";
            case "puppetsync" ->
                    "The \"puppetsync\" keyword was removed in Godot 4. Use the \"@rpc\" annotation with \"authority\" and \"call_local\" instead.";
            case "master" ->
                    "The \"master\" keyword was removed in Godot 4. Use the \"@rpc\" annotation with \"any_peer\" and perform a check inside the function instead.";
            case "mastersync" ->
                    "The \"mastersync\" keyword was removed in Godot 4. Use the \"@rpc\" annotation with \"any_peer\" and \"call_local\", and perform a check inside the function instead.";
            default -> "Unexpected %s in class body.".formatted(debugName(previous));
        };
        pushError(message, DiagnosticCodes.SYNTAX_UNEXPECTED_TOKEN);
        markCurrentFramePendingSync();
    }

    /// Computes the statement start (unless a `static` prefix already did so for this member),
    /// validates any pending annotations against the upcoming declaration kind, and flushes
    /// pending annotations/comments into the sequence in byte order. The flush runs even after
    /// `static` so a comment between `static` and the member keeps its position.
    private void beginMember(
            List<Statement> statements,
            boolean alreadyStarted,
            int targetKindMask,
            @NotNull String targetKindLabel
    ) {
        if (!alreadyStarted) {
            statementStartByte = computeStatementStart(current);
        }
        validatePendingAnnotationsForTarget(targetKindMask, targetKindLabel);
        statements.addAll(drainAnnotationsAndComments(current.startByte(), false));
    }

    private void beginDanglingClassItem(List<Statement> statements) {
        statementStartByte = computeStatementStart(current);
        statements.addAll(drainAnnotationsAndCommentsAsDangling(current.startByte(), false));
    }

    /// The statement starts at the keyword, unless a contiguous run of pending annotations sits
    /// on the same physical line as the keyword — then it starts at the earliest such annotation
    /// (the reference grammar spans same-line annotations into the statement node).
    private int computeStatementStart(GdToken keyword) {
        var start = keyword.startByte();
        var keywordRow = keyword.startLine() - 1;
        for (var i = pendingAnnotations.size() - 1; i >= 0; i--) {
            var annotation = pendingAnnotations.get(i).statement();
            if (annotation.range().startPoint().row() != keywordRow) {
                break;
            }
            start = annotation.range().startByte();
        }
        return start;
    }

    private List<AnnotationStatement> drainAnnotations(boolean allowScriptRoot) {
        if (pendingAnnotations.isEmpty()) {
            return List.of();
        }
        var drained = new ArrayList<AnnotationStatement>(pendingAnnotations.size());
        for (var pending : pendingAnnotations) {
            drained.add(materializePendingAnnotation(pending, true, allowScriptRoot));
        }
        pendingAnnotations.clear();
        return List.copyOf(drained);
    }

    /// Port of `GDScriptParser::parse_class` (nested classes), including the optional superclass
    /// both after the name and as the first statement of an indented body.
    private @NotNull Statement parseNestedClass() {
        // Depth guard: nested classes recurse via parseClassBody. The placeholder declaration
        // keeps the member list well-formed; the skipped body is recovered by the outer loops.
        if (!enterNestingLevel()) {
            return new ClassDeclaration(null, null,
                    new Block(List.of(), range(current.startByte(), current.startByte())),
                    range(statementStartByte, current.startByte()));
        }
        try {
            return parseNestedClassImpl();
        } finally {
            exitNestingLevel();
        }
    }

    private @NotNull Statement parseNestedClassImpl() {
        var start = statementStartByte;
        String name = null;
        pushRecoveryOwner("declaration.name");
        if (consume(GdTokenType.IDENTIFIER,
                "Expected identifier for the class name after \"class\".",
                DiagnosticCodes.DECLARATION_MISSING_NAME)) {
            name = previous.source();
        }
        var nameFrame = popRecoveryOwner();

        var sawExtends = false;
        String extendsTarget = null;
        RecoveryTransactionTracker.ProvisionalFrame extendsFrame = null;
        if (match(GdTokenType.EXTENDS)) {
            sawExtends = true;
            pushRecoveryOwner("class.extends");
            extendsTarget = parseExtendsTarget();
            extendsFrame = popRecoveryOwner();
        }

        pushRecoveryOwner("class.body");
        consume(GdTokenType.COLON, "Expected \":\" after class declaration.",
                DiagnosticCodes.MISSING_SEPARATOR_SUITE_COLON);
        if (hasSameLogicalLineHeaderLexicalErrors()) {
            var activeClassFrame = recoveryTracker.activeFrame();
            if (activeClassFrame != null) {
                claimSameLogicalLineHeaderLexicalErrors(activeClassFrame);
            }
        }
        var headerEnd = Math.max(previous.endByte(), claimedLexicalEndByte);

        var multiline = match(GdTokenType.NEWLINE);
        int blockStart;
        if (multiline) {
            // A comment trailing the class-header line (`class Inner: # c`) is dropped.
            var commentEnd = discardTrailingCommentsBefore(previous.startByte());
            blockStart = layoutBlockStart(previous, Math.max(headerEnd, commentEnd));
            if (!consume(GdTokenType.INDENT, "Expected indented block after class declaration.",
                    DiagnosticCodes.SYNTAX_UNEXPECTED_TOKEN)) {
                var classBodyFrame = popRecoveryOwner();
                var earlyDecl = new ClassDeclaration(name, extendsTarget,
                        new Block(List.of(), range(blockStart, blockStart)),
                        range(start, Math.max(blockStart, headerEnd)));
                bindRecoveryOwner(nameFrame, earlyDecl);
                if (extendsFrame != null) {
                    bindRecoveryOwner(extendsFrame, earlyDecl);
                }
                bindRecoveryOwner(classBodyFrame, earlyDecl);
                return earlyDecl;
            }
        } else {
            blockStart = current.startByte();
        }
        var classBodyFrame = popRecoveryOwner();

        RecoveryTransactionTracker.ProvisionalFrame bodyExtendsFrame = null;
        RecoveryTransactionTracker.ProvisionalFrame extendsTrailingFrame = null;
        if (match(GdTokenType.EXTENDS)) {
            if (extendsFrame == null) {
                pushRecoveryOwner("class.extends");
            } else {
                recoveryTracker.pushFrame("class.extends");
            }
            if (sawExtends) {
                emitInCurrentFrameOrLegacy("Cannot use \"extends\" more than once in the same class.",
                        DiagnosticCodes.CLASS_DUPLICATE_EXTENDS);
                parseExtendsTarget();
            } else {
                sawExtends = true;
                extendsTarget = parseExtendsTarget();
            }
            bodyExtendsFrame = popRecoveryOwner();
            extendsTrailingFrame = finishStatementProvisional("superclass");
        }

        var bodyStatements = new ArrayList<Statement>();
        var bodyIndent = multiline ? indentOf(current.startByte()) : 0;
        var bodyLayoutBaseline = multiline && !check(GdTokenType.DEDENT) && !isAtEnd()
                ? lineIndentOf(current.startByte())
                : 0;
        var classFrame = pushStructuralFrame(StructuralFrame.nonBracketed(
                StructuralRole.CLASS_BODY, start, bodyLayoutBaseline, multiline, false, "class body"));
        try {
            parseClassBody(multiline, false, bodyStatements, bodyIndent);
        } finally {
            popStructuralFrame(classFrame);
        }
        var blockEnd = bodyStatements.isEmpty()
                ? blockStart
                : bodyStatements.getLast().range().endByte();
        var body = new Block(List.copyOf(bodyStatements), range(blockStart, blockEnd));

        var end = Math.max(blockEnd, headerEnd);
        RecoveryTransactionTracker.ProvisionalFrame dedentFrame = null;
        if (multiline) {
            flushOutOfScopeDedentedErrors(bodyLayoutBaseline);
            pushRecoveryOwner("class.body");
            consume(GdTokenType.DEDENT, "Missing unindent at the end of the class body.",
                    DiagnosticCodes.SYNTAX_UNEXPECTED_TOKEN);
            dedentFrame = popRecoveryOwner();
        }
        var classDecl = new ClassDeclaration(name, extendsTarget, body, range(start, end));
        bindRecoveryOwner(nameFrame, classDecl);
        if (extendsFrame != null) {
            bindRecoveryOwner(extendsFrame, classDecl);
        }
        if (bodyExtendsFrame != null) {
            bindRecoveryOwner(bodyExtendsFrame, classDecl);
        }
        bindRecoveryOwner(classBodyFrame, classDecl);
        if (dedentFrame != null) {
            bindRecoveryOwner(dedentFrame, classDecl);
        }
        if (extendsTrailingFrame != null) {
            bindRecoveryOwner(extendsTrailingFrame, classDecl);
        }
        return classDecl;
    }

    // ---------------------------------------------------------------------------------------------
    // Suites and statements — port of parse_suite / parse_statement (declaration-scoped subset).
    // ---------------------------------------------------------------------------------------------

    private record SuiteResult(@NotNull Block block,
                               RecoveryTransactionTracker.@Nullable ProvisionalFrame layoutFrame) {
    }

    private RecoveryTransactionTracker.@NotNull ProvisionalFrame emitSuiteLayoutError(
            RecoveryTransactionTracker.@Nullable ProvisionalFrame existing,
            @NotNull String message,
            @NotNull GdToken origin
    ) {
        var frame = existing;
        if (frame == null) {
            pushRecoveryOwner("suite.body");
            frame = popRecoveryOwner();
        }
        recoveryTracker.emitError(
                message,
                tokenRange(origin),
                DiagnosticCodes.SYNTAX_UNEXPECTED_TOKEN,
                RecoveryTransactionTracker.AttributionPolicy.TARGET_FRAME,
                frame
        );
        return frame;
    }

    /// Port of `GDScriptParser::parse_suite`: multi-line (NEWLINE + INDENT … DEDENT) or single-line
    /// (semicolon-separated) statement block. Failed statements are skipped; only those count
    /// toward the circuit breaker (`error_count++ > 100`, i.e. the 102nd failure aborts the
    /// suite) — `push_error` alone does not count.
    private @NotNull Block parseSuite(String context) {
        return parseSuiteWithLayout(context, false, false).block();
    }

    private @NotNull SuiteResult parseSuiteWithLayout(String context) {
        return parseSuiteWithLayout(context, false, false);
    }

    /// `forLambda` mirrors the vendor's `p_for_lambda`: the loop also stops when a lambda-end is
    /// pending, the closing DEDENT becomes optional in that case, and `lambdaEnded` is raised when
    /// the suite finishes so the enclosing statement ends without a newline token.
    private @NotNull SuiteResult parseSuiteWithLayout(String context, boolean forLambda) {
        return parseSuiteWithLayout(context, forLambda, false);
    }

    private @NotNull SuiteResult parseSuiteWithLayout(String context, boolean forLambda, boolean consumedNewline) {
        // Depth guard: nested control-flow suites recurse via parseStatement. The rejected
        // suite's tokens are not consumed here; the poisoned synchronize() pass that follows
        // (triggered by the enclosing statement's panic-mode tail or the match loop head)
        // consumes the whole region layout-aware.
        if (!enterNestingLevel()) {
            return new SuiteResult(new Block(List.of(), range(current.startByte(), current.startByte())), null);
        }
        try {
            return parseSuiteImpl(context, forLambda, consumedNewline);
        } finally {
            exitNestingLevel();
        }
    }

    private @NotNull SuiteResult parseSuiteImpl(String context, boolean forLambda, boolean consumedNewline) {
        // `previous` cannot be null here: `parse(...)` seeds it with an EMPTY sentinel before
        // parsing starts, `advance()` only assigns non-null tokens returned by `tokenizer.scan()`,
        // and every suite caller has already consumed at least its construct header token.
        var headerEnd = Math.max(requirePreviousToken().endByte(), claimedLexicalEndByte);
        RecoveryTransactionTracker.ProvisionalFrame layoutFrame = null;
        if (!consumedNewline && hasSameLogicalLineHeaderLexicalErrors()) {
            RecoveryTransactionTracker.ProvisionalFrame headerTarget;
            if (isolatedFallbackFrame != null) {
                headerTarget = isolatedFallbackFrame;
            } else {
                pushRecoveryOwner("suite.body");
                layoutFrame = popRecoveryOwner();
                headerTarget = layoutFrame;
            }
            var claimedSpan = claimSameLogicalLineHeaderLexicalErrors(headerTarget);
            if (claimedSpan != null) {
                headerEnd = Math.max(headerEnd, claimedSpan.endByte());
            }
        }
        var multiline = consumedNewline || match(GdTokenType.NEWLINE);
        int blockStart;
        var suiteIndent = -1;
        if (multiline) {
            var newlineToken = requirePreviousToken();
            // A comment trailing the suite-header line (`if x: # c`) is dropped by both paths.
            var commentEnd = discardTrailingCommentsBefore(newlineToken.startByte());
            // The reference path spans the body from right after the header content: trailing
            // whitespace before the newline is included, a trailing comment is not.
            blockStart = layoutBlockStart(newlineToken, Math.max(headerEnd, commentEnd));
            if (!match(GdTokenType.INDENT)) {
                layoutFrame = emitSuiteLayoutError(
                        layoutFrame,
                        "Expected indented block after %s.".formatted(context),
                        previous
                );
                return new SuiteResult(new Block(List.of(), range(blockStart, blockStart)), layoutFrame);
            }
            if (!check(GdTokenType.DEDENT)) {
                suiteIndent = indentOf(current.startByte());
            }
        } else {
            blockStart = current.startByte();
        }

        var suiteLayoutBaseline = multiline && !check(GdTokenType.DEDENT) && !isAtEnd()
                ? lineIndentOf(current.startByte())
                : 0;
        // Comments strictly before `blockStart` belong to the suite header (for instance on a `\`
        // continuation line before `:`), never inside this Block's byte envelope.
        while (commentCursor < comments.size() && comments.get(commentCursor).startByte() < blockStart) {
            commentCursor++;
        }
        var suiteFrame = pushStructuralFrame(StructuralFrame.nonBracketed(
                StructuralRole.SUITE, blockStart, suiteLayoutBaseline, multiline, false, context));
        var previousSuiteFallback = suiteBodyFallbackFrame;
        if (isolatedFallbackFrame == null) {
            if (forLambda && lambdaBodyFallbackFrame != null) {
                suiteBodyFallbackFrame = layoutFrame != null ? layoutFrame : lambdaBodyFallbackFrame;
            } else {
                if (layoutFrame == null) {
                    pushRecoveryOwner("suite.body");
                    layoutFrame = popRecoveryOwner();
                }
                suiteBodyFallbackFrame = layoutFrame;
            }
        } else {
            suiteBodyFallbackFrame = null;
        }
        var statements = new ArrayList<Statement>();
        var errorCount = 0;
        var unexpectedIndentDepth = 0;
        var trippedCircuitBreaker = false;
        var consumedPrefixOnLine = false;
        try {
            suiteLoop:
            do {
                if (isAtEnd() || (!multiline && !consumedPrefixOnLine
                        && previous.type() == GdTokenType.SEMICOLON && check(GdTokenType.NEWLINE))) {
                    break;
                }
                if (multiline && check(GdTokenType.DEDENT)) {
                    if (unexpectedIndentDepth > 0) {
                        unexpectedIndentDepth--;
                        advance();
                        continue;
                    }
                    break;
                }
                if (multiline && check(GdTokenType.INDENT)) {
                    advance();
                    layoutFrame = emitSuiteLayoutError(
                            layoutFrame,
                            "Expected statement, found \"%s\" instead.".formatted(previous.type().displayName()),
                            previous
                    );
                    unexpectedIndentDepth++;
                    if (errorCount++ > 100) {
                        recoveryTracker.emitError(
                                "Too many statement errors.",
                                tokenRange(previous),
                                DiagnosticCodes.STATEMENT_TOO_MANY_ERRORS,
                                RecoveryTransactionTracker.AttributionPolicy.STANDALONE,
                                null
                        );
                        trippedCircuitBreaker = true;
                        break;
                    }
                    continue;
                }
                consumedPrefixOnLine = false;
                switch (parseStatement()) {
                    case StatementProgress.Produced(var produced) -> statements.addAll(produced);
                    case StatementProgress.ConsumedPrefix _ ->
                            consumedPrefixOnLine = previous.type() != GdTokenType.NEWLINE;
                    case StatementProgress.Failed(var prefixStatements) -> {
                        statements.addAll(prefixStatements);
                        if (errorCount++ > 100) {
                            recoveryTracker.emitError(
                                    "Too many statement errors.",
                                    tokenRange(previous),
                                    DiagnosticCodes.STATEMENT_TOO_MANY_ERRORS,
                                    RecoveryTransactionTracker.AttributionPolicy.STANDALONE,
                                    null
                            );
                            trippedCircuitBreaker = true;
                            break suiteLoop;
                        }
                    }
                }
            } while ((multiline || consumedPrefixOnLine || previous.type() == GdTokenType.SEMICOLON)
                    && (!check(GdTokenType.DEDENT) || unexpectedIndentDepth > 0)
                    && !lambdaEnded
                    && !isAtEnd());
        } finally {
            suiteBodyFallbackFrame = previousSuiteFallback;
            popStructuralFrame(suiteFrame);
        }

        if (multiline) {
            if (trippedCircuitBreaker) {
                // Drain the aborted suite plus every extra unexpected INDENT it claimed so
                // enclosing declarations never inherit leftover statements or DEDENTs, while
                // yielding immediately at an outer inline-lambda separator or closer once
                // statement-head match tracking and hard-line/exited-subsuite settlement agree.
                var remainingDepth = unexpectedIndentDepth + 1;
                var localParens = new ArrayDeque<LocalBracketOpener>();
                var localBrackets = new ArrayDeque<LocalBracketOpener>();
                var localBraces = new ArrayDeque<LocalBracketOpener>();
                var lastToken = requirePreviousToken();
                var atLineStart = lastToken.type() == GdTokenType.NEWLINE
                        || lastToken.type() == GdTokenType.INDENT
                        || lastToken.type() == GdTokenType.DEDENT;
                var atStatementStart = atLineStart || lastToken.type() == GdTokenType.SEMICOLON;
                var matchHeaderDepth = -1;
                var matchSawColon = false;
                var pendingMatchBaseDepth = -1;
                var matchBodyDepths = new ArrayDeque<Integer>();
                var inMatchBranchHeader = false;
                var prevTokenWasPeriod = lastToken.type() == GdTokenType.PERIOD;
                var previousWasMemberName = false;
                var declTypeColonEligible = false;
                var prevTokenWasIs = lastToken.type() == GdTokenType.IS;
                var prevTokenWasTypeIntroducer = lastToken.type() == GdTokenType.FORWARD_ARROW
                        || lastToken.type() == GdTokenType.AS
                        || lastToken.type() == GdTokenType.IS;
                var previousWasAfterTypeIntroducer = false;
                var previousWasStatementStart = false;
                var annotationPrefixState = 0;
                var annotationParenDepth = 0;
                var annotationStartDepth = -1;
                while (remainingDepth > 0 && !isAtEnd()) {
                    var localOpenersEmpty = localParens.isEmpty() && localBrackets.isEmpty() && localBraces.isEmpty();
                    if (atLineStart && !currentFollowedContinuation && !localOpenersEmpty
                            && isCircuitBreakerHardStatementStarter(current.type(), localBraces)) {
                        var settledAny = settleHardStatementOpeners(localParens, remainingDepth);
                        settledAny |= settleHardStatementOpeners(localBrackets, remainingDepth);
                        settledAny |= settleHardStatementOpeners(localBraces, remainingDepth);
                        if (annotationPrefixState == 2 && localParens.size() < annotationParenDepth) {
                            annotationPrefixState = 0;
                            annotationStartDepth = -1;
                        }
                        localOpenersEmpty = localParens.isEmpty() && localBrackets.isEmpty() && localBraces.isEmpty();
                        if (settledAny && localOpenersEmpty) {
                            matchHeaderDepth = -1;
                            matchSawColon = false;
                            pendingMatchBaseDepth = -1;
                            annotationPrefixState = 0;
                            annotationStartDepth = -1;
                            while (!matchBodyDepths.isEmpty() && matchBodyDepths.peekLast() > remainingDepth) {
                                matchBodyDepths.removeLast();
                            }
                            inMatchBranchHeader = !matchBodyDepths.isEmpty()
                                    && matchBodyDepths.peekLast() == remainingDepth;
                        }
                    }
                    // Register a match body's branch depth on the first non-layout token after
                    // `match ...:\n` whenever it sits strictly deeper than the match statement's
                    // own line depth, independent of how many intermediate continuation indents or
                    // dedents occurred between the header and the first branch.
                    if (pendingMatchBaseDepth >= 0
                            && current.type() != GdTokenType.NEWLINE
                            && current.type() != GdTokenType.INDENT
                            && current.type() != GdTokenType.DEDENT) {
                        if (remainingDepth > pendingMatchBaseDepth) {
                            matchBodyDepths.addLast(remainingDepth);
                            inMatchBranchHeader = true;
                        }
                        pendingMatchBaseDepth = -1;
                    }
                    if (inLambda) {
                        var potentialOuterBoundary = (current.type() == GdTokenType.COMMA && hasCommaOwningAncestor())
                                || (isCloserToken(current.type()) && hasMatchingAncestorCloser(current.type()));
                        if (potentialOuterBoundary
                                && (hasExitedSubsuiteOpener(localParens, remainingDepth)
                                        || hasExitedSubsuiteOpener(localBrackets, remainingDepth)
                                        || hasExitedSubsuiteOpener(localBraces, remainingDepth))) {
                            var enclosingDelimiter = enclosingLambdaDelimiterFrame();
                            var ancestorCloserMissing = enclosingDelimiter != null
                                    && isOracleCloserMissing(enclosingDelimiter.openerStartByte);
                            var enclosingOpenerType = enclosingDelimiter != null
                                    ? enclosingDelimiter.openerType
                                    : null;
                            var settledAny = settleExitedSubsuiteOpeners(
                                    localParens, remainingDepth,
                                    ancestorCloserMissing && enclosingOpenerType == GdTokenType.PARENTHESIS_OPEN,
                                    suiteLayoutBaseline);
                            settledAny |= settleExitedSubsuiteOpeners(
                                    localBrackets, remainingDepth,
                                    ancestorCloserMissing && enclosingOpenerType == GdTokenType.BRACKET_OPEN,
                                    suiteLayoutBaseline);
                            settledAny |= settleExitedSubsuiteOpeners(
                                    localBraces, remainingDepth,
                                    ancestorCloserMissing && enclosingOpenerType == GdTokenType.BRACE_OPEN,
                                    suiteLayoutBaseline);
                            if (annotationPrefixState == 2 && localParens.size() < annotationParenDepth) {
                                annotationPrefixState = 0;
                                annotationStartDepth = -1;
                            }
                            localOpenersEmpty = localParens.isEmpty() && localBrackets.isEmpty() && localBraces.isEmpty();
                            if (settledAny && localOpenersEmpty) {
                                matchHeaderDepth = -1;
                                matchSawColon = false;
                                pendingMatchBaseDepth = -1;
                                annotationPrefixState = 0;
                                annotationStartDepth = -1;
                                while (!matchBodyDepths.isEmpty() && matchBodyDepths.peekLast() > remainingDepth) {
                                    matchBodyDepths.removeLast();
                                }
                                inMatchBranchHeader = !matchBodyDepths.isEmpty()
                                        && matchBodyDepths.peekLast() == remainingDepth;
                            }
                        }
                        if (!inMatchBranchHeader
                                && localOpenersEmpty
                                && current.type() == GdTokenType.COMMA
                                && hasCommaOwningAncestor()) {
                            lambdaEnded = true;
                            break;
                        }
                        if (isCloserToken(current.type())) {
                            var hasMatchingLocal = switch (current.type()) {
                                case PARENTHESIS_CLOSE -> {
                                    circuitBreakerStackOps++;
                                    yield !localParens.isEmpty();
                                }
                                case BRACKET_CLOSE -> {
                                    circuitBreakerStackOps++;
                                    yield !localBrackets.isEmpty();
                                }
                                case BRACE_CLOSE -> {
                                    circuitBreakerStackOps++;
                                    yield !localBraces.isEmpty();
                                }
                                default -> false;
                            };
                            // A multiline suite never defers an unmatched closer of another kind
                            // to an enclosing call/container; only an exact matching ancestor closer
                            // terminates the lambda body early.
                            if (!hasMatchingLocal && hasMatchingAncestorCloser(current.type())) {
                                lambdaEnded = true;
                                break;
                            }
                        }
                    }
                    var currentType = current.type();
                    var currentWasStatementStart = atStatementStart;
                    var currentTypeIsTypeColon = false;
                    if (atStatementStart && localOpenersEmpty
                            && (currentType == GdTokenType.VAR
                                    || currentType == GdTokenType.CONST
                                    || currentType == GdTokenType.FOR)) {
                        declTypeColonEligible = true;
                    }
                    switch (currentType) {
                        case MATCH -> {
                            if (atStatementStart && localOpenersEmpty) {
                                matchHeaderDepth = annotationStartDepth >= 0
                                        ? annotationStartDepth
                                        : remainingDepth;
                                matchSawColon = false;
                            } else if (matchSawColon) {
                                matchHeaderDepth = -1;
                                matchSawColon = false;
                            }
                            if (annotationPrefixState != 2) {
                                annotationStartDepth = -1;
                            }
                            atLineStart = false;
                            atStatementStart = false;
                        }
                        case COLON -> {
                            var isTypeColon = isIdentifier(requirePreviousToken())
                                    && ((localOpenersEmpty && declTypeColonEligible)
                                            || (!localParens.isEmpty()
                                                    && localBrackets.isEmpty()
                                                    && localBraces.isEmpty()));
                            currentTypeIsTypeColon = isTypeColon;
                            declTypeColonEligible = false;
                            if (annotationPrefixState != 2) {
                                annotationStartDepth = -1;
                            }
                            if (localOpenersEmpty) {
                                inMatchBranchHeader = false;
                                if (matchHeaderDepth >= 0 && remainingDepth >= matchHeaderDepth) {
                                    if (matchSawColon) {
                                        matchHeaderDepth = -1;
                                        matchSawColon = false;
                                    } else {
                                        matchSawColon = true;
                                    }
                                }
                            } else if (matchSawColon) {
                                matchHeaderDepth = -1;
                                matchSawColon = false;
                            }
                            atLineStart = false;
                            atStatementStart = localOpenersEmpty && !isTypeColon;
                        }
                        case NEWLINE -> {
                            declTypeColonEligible = false;
                            if (annotationPrefixState != 2) {
                                annotationPrefixState = 0;
                                annotationStartDepth = -1;
                            }
                            if (localOpenersEmpty) {
                                if (matchHeaderDepth >= 0 && matchSawColon) {
                                    pendingMatchBaseDepth = matchHeaderDepth;
                                }
                                matchHeaderDepth = -1;
                                matchSawColon = false;
                                inMatchBranchHeader = !matchBodyDepths.isEmpty()
                                        && matchBodyDepths.peekLast() == remainingDepth;
                            }
                            atLineStart = true;
                            atStatementStart = true;
                        }
                        case INDENT -> {
                            declTypeColonEligible = false;
                            if (annotationPrefixState != 2) {
                                annotationPrefixState = 0;
                                annotationStartDepth = -1;
                            }
                            remainingDepth++;
                            if (localOpenersEmpty) {
                                matchHeaderDepth = -1;
                                matchSawColon = false;
                                inMatchBranchHeader = !matchBodyDepths.isEmpty()
                                        && matchBodyDepths.peekLast() == remainingDepth;
                            }
                            atLineStart = true;
                            atStatementStart = true;
                        }
                        case DEDENT -> {
                            declTypeColonEligible = false;
                            if (annotationPrefixState != 2) {
                                annotationPrefixState = 0;
                                annotationStartDepth = -1;
                            }
                            remainingDepth--;
                            if (pendingMatchBaseDepth >= 0 && remainingDepth <= pendingMatchBaseDepth) {
                                pendingMatchBaseDepth = -1;
                            }
                            if (localOpenersEmpty) {
                                while (!matchBodyDepths.isEmpty() && matchBodyDepths.peekLast() > remainingDepth) {
                                    matchBodyDepths.removeLast();
                                }
                                matchHeaderDepth = -1;
                                matchSawColon = false;
                                inMatchBranchHeader = !matchBodyDepths.isEmpty()
                                        && matchBodyDepths.peekLast() == remainingDepth;
                            } else if (remainingDepth < matchHeaderDepth) {
                                matchHeaderDepth = -1;
                                matchSawColon = false;
                            }
                            atLineStart = true;
                            atStatementStart = true;
                        }
                        case SEMICOLON -> {
                            declTypeColonEligible = false;
                            if (localOpenersEmpty) {
                                annotationPrefixState = 0;
                                annotationStartDepth = -1;
                            }
                            matchHeaderDepth = -1;
                            matchSawColon = false;
                            pendingMatchBaseDepth = -1;
                            atLineStart = false;
                            atStatementStart = true;
                        }
                        case ANNOTATION -> {
                            if (atStatementStart && localOpenersEmpty) {
                                annotationPrefixState = 1;
                                if (annotationStartDepth < 0) {
                                    annotationStartDepth = remainingDepth;
                                }
                                atStatementStart = true;
                            } else {
                                if (annotationPrefixState != 2) {
                                    annotationPrefixState = 0;
                                    annotationStartDepth = -1;
                                }
                                atStatementStart = false;
                            }
                            if (matchSawColon) {
                                matchHeaderDepth = -1;
                                matchSawColon = false;
                            }
                            atLineStart = false;
                        }
                        case PARENTHESIS_OPEN -> {
                            if (matchSawColon) {
                                matchHeaderDepth = -1;
                                matchSawColon = false;
                            }
                            circuitBreakerStackOps++;
                            localParens.addLast(new LocalBracketOpener(
                                    GdTokenType.PARENTHESIS_OPEN, remainingDepth, current.startByte(),
                                    current.startLine(),
                                    isCommaOwningParenPredecessor(
                                            previous, previousWasMemberName, previousWasStatementStart)));
                            if (annotationPrefixState == 1) {
                                annotationPrefixState = 2;
                                annotationParenDepth = localParens.size();
                            } else if (annotationPrefixState != 2) {
                                annotationStartDepth = -1;
                            }
                            atLineStart = false;
                            atStatementStart = false;
                        }
                        case BRACKET_OPEN -> {
                            if (matchSawColon) {
                                matchHeaderDepth = -1;
                                matchSawColon = false;
                            }
                            if (annotationPrefixState != 2) {
                                annotationStartDepth = -1;
                            }
                            circuitBreakerStackOps++;
                            localBrackets.addLast(new LocalBracketOpener(
                                    GdTokenType.BRACKET_OPEN, remainingDepth, current.startByte(),
                                    current.startLine(),
                                    !isSubscriptBracketPredecessor(
                                            previous,
                                            previousWasMemberName,
                                            previousWasAfterTypeIntroducer,
                                            previousWasStatementStart)));
                            atLineStart = false;
                            atStatementStart = false;
                        }
                        case BRACE_OPEN -> {
                            if (matchSawColon) {
                                matchHeaderDepth = -1;
                                matchSawColon = false;
                            }
                            if (annotationPrefixState != 2) {
                                annotationStartDepth = -1;
                            }
                            circuitBreakerStackOps++;
                            localBraces.addLast(new LocalBracketOpener(
                                    GdTokenType.BRACE_OPEN, remainingDepth, current.startByte(),
                                    current.startLine(), true));
                            atLineStart = false;
                            atStatementStart = false;
                        }
                        case PARENTHESIS_CLOSE -> {
                            if (matchSawColon) {
                                matchHeaderDepth = -1;
                                matchSawColon = false;
                            }
                            circuitBreakerStackOps++;
                            LocalBracketOpener closedOpener;
                            if (!localParens.isEmpty()) {
                                closedOpener = localParens.removeLast();
                            } else {
                                closedOpener = popReplacedLocalOpenerAt(localBrackets, current.startByte());
                                if (closedOpener == null) {
                                    closedOpener = popReplacedLocalOpenerAt(localBraces, current.startByte());
                                }
                            }
                            if (closedOpener != null) {
                                unwindSkippedInnerLocalOpeners(closedOpener.startByte, localParens);
                                unwindSkippedInnerLocalOpeners(closedOpener.startByte, localBrackets);
                                unwindSkippedInnerLocalOpeners(closedOpener.startByte, localBraces);
                            }
                            atLineStart = false;
                            if (annotationPrefixState == 2 && localParens.size() < annotationParenDepth) {
                                annotationPrefixState = 0;
                                if (closedOpener != null
                                        && closedOpener.openerType == GdTokenType.PARENTHESIS_OPEN
                                        && localParens.size() == annotationParenDepth - 1) {
                                    atStatementStart = true;
                                } else {
                                    annotationStartDepth = -1;
                                    atStatementStart = false;
                                }
                            } else {
                                if (annotationPrefixState != 2) {
                                    annotationStartDepth = -1;
                                }
                                atStatementStart = false;
                            }
                        }
                        case BRACKET_CLOSE -> {
                            if (matchSawColon) {
                                matchHeaderDepth = -1;
                                matchSawColon = false;
                            }
                            circuitBreakerStackOps++;
                            LocalBracketOpener closedOpener;
                            if (!localBrackets.isEmpty()) {
                                closedOpener = localBrackets.removeLast();
                            } else {
                                closedOpener = popReplacedLocalOpenerAt(localParens, current.startByte());
                                if (closedOpener == null) {
                                    closedOpener = popReplacedLocalOpenerAt(localBraces, current.startByte());
                                }
                            }
                            if (closedOpener != null) {
                                unwindSkippedInnerLocalOpeners(closedOpener.startByte, localParens);
                                unwindSkippedInnerLocalOpeners(closedOpener.startByte, localBrackets);
                                unwindSkippedInnerLocalOpeners(closedOpener.startByte, localBraces);
                            }
                            if (annotationPrefixState == 2 && localParens.size() < annotationParenDepth) {
                                annotationPrefixState = 0;
                                annotationStartDepth = -1;
                            } else if (annotationPrefixState != 2) {
                                annotationStartDepth = -1;
                            }
                            atLineStart = false;
                            atStatementStart = false;
                        }
                        case BRACE_CLOSE -> {
                            if (matchSawColon) {
                                matchHeaderDepth = -1;
                                matchSawColon = false;
                            }
                            circuitBreakerStackOps++;
                            LocalBracketOpener closedOpener;
                            if (!localBraces.isEmpty()) {
                                closedOpener = localBraces.removeLast();
                            } else {
                                closedOpener = popReplacedLocalOpenerAt(localParens, current.startByte());
                                if (closedOpener == null) {
                                    closedOpener = popReplacedLocalOpenerAt(localBrackets, current.startByte());
                                }
                            }
                            if (closedOpener != null) {
                                unwindSkippedInnerLocalOpeners(closedOpener.startByte, localParens);
                                unwindSkippedInnerLocalOpeners(closedOpener.startByte, localBrackets);
                                unwindSkippedInnerLocalOpeners(closedOpener.startByte, localBraces);
                            }
                            if (annotationPrefixState == 2 && localParens.size() < annotationParenDepth) {
                                annotationPrefixState = 0;
                                annotationStartDepth = -1;
                            } else if (annotationPrefixState != 2) {
                                annotationStartDepth = -1;
                            }
                            atLineStart = false;
                            atStatementStart = false;
                        }
                        case IN, EQUAL -> {
                            declTypeColonEligible = false;
                            if (matchSawColon) {
                                matchHeaderDepth = -1;
                                matchSawColon = false;
                            }
                            if (annotationPrefixState != 2) {
                                annotationStartDepth = -1;
                            }
                            atLineStart = false;
                            atStatementStart = false;
                        }
                        default -> {
                            if (matchSawColon) {
                                matchHeaderDepth = -1;
                                matchSawColon = false;
                            }
                            if (annotationPrefixState != 2) {
                                annotationStartDepth = -1;
                            }
                            atLineStart = false;
                            atStatementStart = false;
                        }
                    }
                    if (annotationPrefixState == 1
                            && currentType != GdTokenType.ANNOTATION
                            && currentType != GdTokenType.PARENTHESIS_OPEN) {
                        annotationPrefixState = 0;
                        annotationStartDepth = -1;
                    }
                    if (currentType == GdTokenType.NEWLINE
                            || currentType == GdTokenType.INDENT
                            || currentType == GdTokenType.DEDENT) {
                        previousWasMemberName = false;
                        previousWasAfterTypeIntroducer = false;
                        previousWasStatementStart = false;
                        prevTokenWasIs = false;
                        if (localOpenersEmpty && !currentFollowedContinuation) {
                            prevTokenWasPeriod = false;
                            prevTokenWasTypeIntroducer = false;
                        }
                    } else {
                        previousWasMemberName = prevTokenWasPeriod && isNodeName(current);
                        previousWasAfterTypeIntroducer = prevTokenWasTypeIntroducer;
                        previousWasStatementStart = currentWasStatementStart;
                        prevTokenWasPeriod = currentType == GdTokenType.PERIOD;
                        var nextTypeIntroducer = currentType == GdTokenType.FORWARD_ARROW
                                || currentType == GdTokenType.AS
                                || currentType == GdTokenType.IS
                                || currentTypeIsTypeColon
                                || (currentType == GdTokenType.NOT && prevTokenWasIs);
                        prevTokenWasIs = currentType == GdTokenType.IS;
                        prevTokenWasTypeIntroducer = nextTypeIntroducer;
                    }
                    advance();
                }
            } else if (!lambdaEnded) {
                flushOutOfScopeDedentedErrors(suiteLayoutBaseline);
                if (!match(GdTokenType.DEDENT)) {
                    layoutFrame = emitSuiteLayoutError(
                            layoutFrame,
                            "Missing unindent at the end of %s.".formatted(context),
                            previous
                    );
                }
            } else {
                flushOutOfScopeDedentedErrors(suiteLayoutBaseline);
                match(GdTokenType.DEDENT);
            }
        } else if (previous.type() == GdTokenType.SEMICOLON) {
            if (!match(GdTokenType.NEWLINE)) {
                layoutFrame = emitSuiteLayoutError(
                        layoutFrame,
                        "Expected newline after \";\" at the end of %s.".formatted(context),
                        previous
                );
            }
        }
        if (forLambda) {
            lambdaEnded = true;
        }

        // Comments in the tail window before the closing dedent belong to this suite only when
        // they are indented into it; dangling annotations flush into the suite's own sequence
        // instead of escaping to an outer member (merged in byte order).
        if (multiline && suiteIndent >= 0) {
            statements.addAll(drainTailWithAnnotations(suiteIndent, false));
        } else {
            statements.addAll(drainAnnotations(false));
        }
        var blockEnd = statements.isEmpty() ? blockStart : statements.getLast().range().endByte();
        return new SuiteResult(new Block(List.copyOf(statements), range(blockStart, blockEnd)), layoutFrame);
    }

    /// Discard path of the expression-statement dispatch for `Failed` outcomes that already carry
    /// their root-cause diagnostic: inside a lambda the failure ends the lambda without consuming
    /// the token; outside, exactly one token is consumed (EOF-guarded, mirroring the vendor's
    /// soft-failing `advance()`) so the following `synchronize()` keeps its usual boundary. No
    /// `endStatement` call and no additional diagnostic.
    private void discardFailedStatement() {
        if (inLambda) {
            if (isVirtualLambdaBoundary()) {
                lambdaEnded = true;
            } else {
                if (!isAtEnd()) {
                    advance();
                }
                markCurrentFramePendingSync();
            }
        } else {
            if (!isAtEnd()) {
                advance();
            }
            markCurrentFramePendingSync();
        }
    }

    /// Keeps the return slot's carried node, or an `ERROR` fragment for `Failed`.
    private @NotNull Expression returnSlotValue(ParseOutcome<Expression> outcome, int slotStartByte) {
        return switch (outcome) {
            case ParseOutcome.Ok<Expression>(var node) -> node;
            case ParseOutcome.EnclosedDamage<Expression>(var node) -> node;
            case ParseOutcome.Damaged<Expression>(var node) -> node;
            case ParseOutcome.Failed<Expression> _ -> hasCurrentLogicalLineLexicalErrors()
                    ? claimLexicalErrorSlotValue(slotStartByte, true, false)
                    : slotMarker(outcome, slotStartByte, "return.value");
            default -> throw new IllegalStateException("Unexpected return slot outcome: " + outcome);
        };
    }

    /// True only at a genuine `return` statement boundary (explicit terminator, block dedent, EOF,
    /// or an inline-lambda virtual boundary owned by an enclosing call/container) with no
    /// unclaimed same-logical-line lexical errors sitting between `return` and that boundary.
    private boolean isGenuineReturnStatementEnd() {
        return !hasCurrentLogicalLineLexicalErrors()
                && (isStatementEndToken()
                        || check(GdTokenType.DEDENT)
                        || lambdaEnded
                        || (inLambda && isVirtualLambdaBoundary()));
    }

    /// Consumes a malformed non-expression fragment on the current `return` statement up to the
    /// nearest statement, layout, or enclosing-delimiter boundary and emits `SLOT_RETURN_VALUE`, or
    /// claims same-logical-line lexical errors into `return.value`.
    private @NotNull Expression recoverMissingReturnValue(int valueStart) {
        var initialLexicalBatch = hasCurrentLogicalLineLexicalErrors() ? currentPrecedingErrors : null;
        var fragmentStart = current.startByte();
        var drainLine = previous.endLine();
        var localDelimiters = new LocalDelimiterStack();
        while (!isAtEnd()) {
            if (current.type() == GdTokenType.NEWLINE
                    || current.type() == GdTokenType.SEMICOLON
                    || current.type() == GdTokenType.INDENT
                    || current.type() == GdTokenType.DEDENT) {
                break;
            }
            if (localDelimiters.isEmpty()
                    && ((current.startLine() > drainLine
                                    && !currentFollowedContinuation
                                    && !(currentPrecedingErrors != null && currentPrecedingErrors.followedContinuation()))
                            || isAtUnclosedMultilineStop())) {
                break;
            }
            if (current.type() == GdTokenType.COMMA
                    && hasCommaOwningAncestor()
                    && !localDelimiters.hasVerifiedCommaShield(current.startByte())) {
                break;
            }
            if (isCloserToken(current.type())
                    && !localDelimiters.hasMatchingOpener(current.type())
                    && isAtAncestorOwnedCloser()) {
                break;
            }
            localDelimiters.consume(current);
            advance();
            drainLine = previous.endLine();
        }
        var fragmentEnd = extentEnd();
        if (fragmentEnd > fragmentStart) {
            var spanStart = initialLexicalBatch != null
                    ? Math.min(fragmentStart, initialLexicalBatch.minStartByte())
                    : fragmentStart;
            var spanEnd = initialLexicalBatch != null
                    ? Math.max(fragmentEnd, initialLexicalBatch.maxEndByte())
                    : fragmentEnd;
            if (hasCurrentLogicalLineLexicalErrors()
                    && currentPrecedingErrors != null
                    && currentPrecedingErrors != initialLexicalBatch
                    && (currentPrecedingErrors.firstStartLine() <= previous.endLine()
                            || currentPrecedingErrors.followedContinuation()
                            || currentFollowedContinuation)) {
                var trailingBatch = currentPrecedingErrors;
                var active = recoveryTracker.activeFrame();
                if (active != null) {
                    recoveryTracker.claimLexicalBatch(trailingBatch, active);
                } else {
                    recoveryTracker.flushLexicalBatchAsStandalone(trailingBatch);
                }
                spanStart = Math.min(spanStart, trailingBatch.minStartByte());
                spanEnd = Math.max(spanEnd, trailingBatch.maxEndByte());
            }
            var fragmentRange = range(fragmentStart, fragmentEnd);
            recoveryTracker.emitError(
                    "Expected expression after \"return\".",
                    fragmentRange,
                    DiagnosticCodes.SLOT_RETURN_VALUE,
                    RecoveryTransactionTracker.AttributionPolicy.CURRENT_FRAME,
                    null
            );
            claimedLexicalEndByte = Math.max(claimedLexicalEndByte, spanEnd);
            return new ErrorExpression(CstIssueKind.ERROR, DIAGNOSTIC_NODE_TYPE,
                    slice(spanStart, spanEnd), range(spanStart, spanEnd));
        }
        if (initialLexicalBatch != null) {
            return claimLexicalErrorSlotValue(valueStart, false, false);
        }
        var markerRange = range(valueStart, valueStart);
        recoveryTracker.emitError(
                "Expected expression after \"return\".",
                markerRange,
                DiagnosticCodes.SLOT_RETURN_VALUE,
                RecoveryTransactionTracker.AttributionPolicy.CURRENT_FRAME,
                null
        );
        return new ErrorExpression(CstIssueKind.MISSING, "return.value", "", markerRange);
    }

    /// Explicit tri-state outcome of one `parseStatement()` step so legal prefix annotations
    /// (`ConsumedPrefix`) never consume the suite circuit-breaker budget reserved for `Failed`.
    private sealed interface StatementProgress {
        record Produced(@NotNull List<Statement> statements) implements StatementProgress {
        }

        record ConsumedPrefix() implements StatementProgress {
            static final ConsumedPrefix INSTANCE = new ConsumedPrefix();
        }

        record Failed(@NotNull List<Statement> prefixStatements) implements StatementProgress {
            static final Failed EMPTY = new Failed(List.of());
        }
    }

    /// Port of `GDScriptParser::parse_statement` for the declaration-scoped subset: `pass`, local
    /// `var`/`const`, statement annotations, and expression statements built from the available
    /// expression subset. Distinguishes `Produced` (statement built), `ConsumedPrefix` (legal
    /// statement-level annotation consumed without counting as an error), and `Failed` (counts
    /// toward the suite circuit-breaker budget).
    private @NotNull StatementProgress parseStatement() {
        // A construct aborted on the nesting budget upstream poisons the current token region:
        // skip straight to synchronization (which also lifts the poison flag) instead of
        // letting partial constructs interpret the leftover tokens.
        if (nestingBudgetExceeded) {
            synchronize();
            return StatementProgress.Failed.EMPTY;
        }
        // Statement-level annotations are collected and attach to the next real statement;
        // consuming one is valid progress (`ConsumedPrefix`) and does not consume failure budget.
        if (current.type() == GdTokenType.ANNOTATION) {
            advance();
            var parsed = parseScopedAnnotation();
            var ann = parsed.statement();
            var kinds = knownAnnotationKinds(ann.name());
            if (kinds != -1 && (kinds & (ANN_STATEMENT | ANN_STANDALONE)) == 0) {
                emitInvalidAnnotationLevel(parsed, (kinds & ANN_SCRIPT) != 0);
                pendingAnnotations.add(new PendingAnnotation(
                        ann, parsed.targetFrame(), false, true, isolatedFallbackFrame != null));
                if (consumeCurrentFramePendingSync() || nestingBudgetExceeded) {
                    synchronize();
                }
                return StatementProgress.ConsumedPrefix.INSTANCE;
            }
            if (kinds != -1 && (kinds & ANN_STANDALONE) != 0) {
                if (!isWarningIgnoreRegionDirective(ann.name())) {
                    emitInvalidAnnotationLevel(parsed, false);
                    pendingAnnotations.add(new PendingAnnotation(
                            ann, parsed.targetFrame(), false, true, isolatedFallbackFrame != null));
                    if (consumeCurrentFramePendingSync() || nestingBudgetExceeded) {
                        synchronize();
                    }
                    return StatementProgress.ConsumedPrefix.INSTANCE;
                }
                var directiveValid = validateStandaloneDirective(parsed);
                var drained = new ArrayList<>(drainAnnotationsAndCommentsAsDangling(ann.range().startByte(), false));
                if (isolatedFallbackFrame != null) {
                    if (!directiveValid && activePropertyRecoveryAnnotations != null) {
                        activePropertyRecoveryAnnotations.add(ann);
                    }
                    if (consumeCurrentFramePendingSync() || nestingBudgetExceeded) {
                        synchronize();
                    }
                    return drained.isEmpty()
                            ? StatementProgress.ConsumedPrefix.INSTANCE
                            : new StatementProgress.Produced(List.copyOf(drained));
                }
                bindRecoveryOwner(parsed.targetFrame(), ann);
                drained.add(ann);
                drained.addAll(drainTrailingComments());
                if (consumeCurrentFramePendingSync() || nestingBudgetExceeded) {
                    synchronize();
                }
                return new StatementProgress.Produced(List.copyOf(drained));
            }
            pendingAnnotations.add(new PendingAnnotation(
                    ann, parsed.targetFrame(), false, false, false));
            if (consumeCurrentFramePendingSync() || nestingBudgetExceeded) {
                synchronize();
            }
            return StatementProgress.ConsumedPrefix.INSTANCE;
        }
        if (inLambda && (isVirtualLambdaBoundary() || check(GdTokenType.DEDENT) || isAtEnd())) {
            lambdaEnded = true;
            return StatementProgress.ConsumedPrefix.INSTANCE;
        }

        statementStartByte = computeStatementStart(current);
        var statementAnnotations = new ArrayList<>(pendingAnnotations);
        pendingAnnotations.clear();
        var produced = new ArrayList<>(drainCommentsWithPending(statementAnnotations, current.startByte()));
        Statement result;
        var terminatedLambda = false;
        switch (current.type()) {
            case PASS -> {
                advance();
                result = new PassStatement(range(statementStartByte, previous.endByte()));
                finishStatement(result, "\"pass\"");
            }
            case VAR -> {
                advance();
                result = parseVariable(false, false);
                if (result == null) {
                    markCurrentFramePendingSync();
                }
            }
            case CONST -> {
                advance();
                result = parseConstant();
                if (result == null) {
                    markCurrentFramePendingSync();
                }
            }
            case IF -> {
                advance();
                result = parseIf();
            }
            case WHILE -> {
                advance();
                result = parseWhile();
            }
            case FOR -> {
                advance();
                result = parseFor();
            }
            case MATCH -> {
                advance();
                result = parseMatch();
            }
            case RETURN -> {
                advance();
                Expression returnValue = null;
                RecoveryTransactionTracker.ProvisionalFrame returnControlFrame = null;
                RecoveryTransactionTracker.ProvisionalFrame valueFrame = null;
                if (!isGenuineReturnStatementEnd()) {
                    // Ported vendor check: a constructor (`_init`/`_static_init`) may not
                    // return a value. The error is reported before parsing the value, like Godot.
                    if ("_init".equals(currentFunctionName) || "_static_init".equals(currentFunctionName)) {
                        pushRecoveryOwner("statement.control");
                        recoveryTracker.emitError(
                                "Constructor cannot return a value.",
                                tokenRange(previous),
                                DiagnosticCodes.SYNTAX_UNEXPECTED_TOKEN,
                                RecoveryTransactionTracker.AttributionPolicy.CURRENT_FRAME,
                                null
                        );
                        returnControlFrame = popRecoveryOwner();
                    }
                    var valueStart = current.startByte();
                    pushRecoveryOwner("return.value");
                    var valueOutcome = parseExpression(false);
                    if (valueOutcome instanceof ParseOutcome.Missing<Expression>) {
                        returnValue = recoverMissingReturnValue(valueStart);
                    } else {
                        returnValue = returnSlotValue(valueOutcome, valueStart);
                    }
                    valueFrame = popRecoveryOwner();
                }
                var returnEnd = returnValue == null
                        ? extentEnd()
                        : Math.max(extentEnd(), returnValue.range().endByte());
                result = new ReturnStatement(returnValue, range(statementStartByte, returnEnd));
                if (returnControlFrame != null) {
                    bindRecoveryOwner(returnControlFrame, result);
                }
                if (valueFrame != null) {
                    bindRecoveryOwner(valueFrame, result);
                }
                finishStatement(result, "return statement");
            }
            case BREAK -> {
                advance();
                RecoveryTransactionTracker.ProvisionalFrame breakControlFrame = null;
                if (!canBreak) {
                    pushRecoveryOwner("statement.control");
                    recoveryTracker.emitError(
                            "Cannot use \"break\" outside of a loop.",
                            tokenRange(previous),
                            DiagnosticCodes.SYNTAX_UNEXPECTED_TOKEN,
                            RecoveryTransactionTracker.AttributionPolicy.CURRENT_FRAME,
                            null
                    );
                    breakControlFrame = popRecoveryOwner();
                }
                result = new BreakStatement(range(statementStartByte, previous.endByte()));
                if (breakControlFrame != null) {
                    bindRecoveryOwner(breakControlFrame, result);
                }
                finishStatement(result, "\"break\"");
            }
            case CONTINUE -> {
                advance();
                RecoveryTransactionTracker.ProvisionalFrame continueControlFrame = null;
                if (!canContinue) {
                    pushRecoveryOwner("statement.control");
                    recoveryTracker.emitError(
                            "Cannot use \"continue\" outside of a loop.",
                            tokenRange(previous),
                            DiagnosticCodes.SYNTAX_UNEXPECTED_TOKEN,
                            RecoveryTransactionTracker.AttributionPolicy.CURRENT_FRAME,
                            null
                    );
                    continueControlFrame = popRecoveryOwner();
                }
                result = new ContinueStatement(range(statementStartByte, previous.endByte()));
                if (continueControlFrame != null) {
                    bindRecoveryOwner(continueControlFrame, result);
                }
                finishStatement(result, "\"continue\"");
            }
            case BREAKPOINT -> {
                advance();
                result = new BreakpointStatement(range(statementStartByte, previous.endByte()));
                finishStatement(result, "\"breakpoint\"");
            }
            case ASSERT -> {
                advance();
                result = parseAssert();
            }
            case FUNC -> {
                advance();
                pushRecoveryOwner("expression");
                var lambda = parseLambdaExpression().valueOrThrow();
                recoveryTracker.emitError(
                        "Standalone lambdas cannot be accessed. Consider assigning it to a variable.",
                        lambda.range(),
                        DiagnosticCodes.LAMBDA_BARE_STATEMENT,
                        RecoveryTransactionTracker.AttributionPolicy.CURRENT_FRAME,
                        null
                );
                var expressionFrame = popRecoveryOwner();
                result = new ExpressionStatement(lambda, range(statementStartByte, lambda.range().endByte()));
                bindRecoveryOwner(expressionFrame, result);
                // Like the vendor's expression-statement path: consume the lambda-end state so
                // the surrounding suite keeps parsing following statements.
                finishStatement(result, "expression");
            }
            default -> {
                // Only statement-level expressions may contain assignments (Godot's
                // `parse_expression(true)` call site in `parse_expression_statement`).
                pushRecoveryOwner("expression");
                var expressionOutcome = parseExpression(true);
                var expressionFrame = popRecoveryOwner();
                switch (expressionOutcome) {
                    case ParseOutcome.Ok<Expression> _, ParseOutcome.EnclosedDamage<Expression> _,
                         ParseOutcome.Damaged<Expression> _ -> {
                        // Every carried node survives; finishStatement diagnoses any unparsed tail.
                        // The span includes consumed grouping delimiters omitted by the inner node.
                        result = new ExpressionStatement(expressionOutcome.valueOrThrow(),
                                range(statementStartByte, extentEnd()));
                        bindRecoveryOwner(expressionFrame, result);
                        finishStatement(result, "expression");
                    }
                    case ParseOutcome.Failed<Expression> _ -> {
                        // The root cause was already reported inside; no generic message here.
                        discardFailedStatement();
                        result = null;
                    }
                    case ParseOutcome.Missing<Expression> _ -> {
                        if (inLambda && (isVirtualLambdaBoundary() || check(GdTokenType.DEDENT) || isAtEnd())) {
                            // Inside a lambda, an ancestor-owned delimiter or suite end terminates
                            // the lambda without consuming the boundary token.
                            lambdaEnded = true;
                            terminatedLambda = true;
                        } else {
                            if (!isAtEnd()) {
                                advance();
                            }
                            if (isCloserToken(previous.type())
                                    && (!inLambda || !hasMatchingAncestorCloser(previous.type()))) {
                                emitUnexpectedCloser(previous);
                            } else {
                                emitInCurrentFrameOrLegacy(
                                        "Expected statement, found \"%s\" instead."
                                                .formatted(previous.type().displayName()),
                                        DiagnosticCodes.SYNTAX_UNEXPECTED_TOKEN
                                );
                            }
                            markCurrentFramePendingSync();
                        }
                        result = null;
                    }
                }
            }
        }

        if (result == null) {
            if (!terminatedLambda) {
                for (var pending : statementAnnotations) {
                    materializePendingAnnotation(pending, true, false);
                }
            }
            if (consumeCurrentFramePendingSync() || nestingBudgetExceeded) {
                synchronize();
            }
            return terminatedLambda
                    ? StatementProgress.ConsumedPrefix.INSTANCE
                    : new StatementProgress.Failed(List.copyOf(produced));
        }
        for (var pending : statementAnnotations) {
            materializePendingAnnotation(pending, false, false);
        }
        produced.add(result);
        // A comment trailing the statement's own line lands right after it.
        produced.addAll(drainTrailingComments());
        if (consumeCurrentFramePendingSync() || nestingBudgetExceeded) {
            synchronize();
        }
        return new StatementProgress.Produced(List.copyOf(produced));
    }

    // ---------------------------------------------------------------------------------------------
    // Control-flow statements — ports of parse_if / parse_while / parse_for / parse_assert.
    // ---------------------------------------------------------------------------------------------

    /// Port of `GDScriptParser::parse_if`. Godot nests `elif` as a synthetic suite holding another
    /// `IfNode` in `false_block`; this AST folds the chain into `elifClauses` plus an optional
    /// `elseBody` (shape pinned by the reference path). A failed condition keeps a marker in the
    /// statement, which survives with its suites (vendor keeps the node with a null condition).
    private @NotNull Statement parseIf() {
        var start = statementStartByte;
        var conditionStart = current.startByte();
        pushRecoveryOwner("if.condition");
        var conditionOutcome = parseExpression(false);
        var condition = absorbSlot(conditionOutcome, conditionStart, "if.condition",
                "Expected conditional expression after \"if\".");
        var matchedColon = match(GdTokenType.COLON);
        // Pop before the suite runs so closer diagnostics inside the body cannot mis-bind to the
        // header facet; the stashed frame binds once the statement is built.
        var conditionFrame = popRecoveryOwner();
        if (!matchedColon) {
            recoveryTracker.emitError(
                    "Expected \":\" after \"if\" condition, found \"%s\" instead."
                            .formatted(current.type().displayName()),
                    tokenRange(previous),
                    DiagnosticCodes.MISSING_SEPARATOR_SUITE_COLON,
                    RecoveryTransactionTracker.AttributionPolicy.TARGET_FRAME,
                    conditionFrame
            );
        }
        var trueSuite = parseSuiteWithLayout("\"if\" block");
        var trueBlock = trueSuite.block();

        var elifClauses = new ArrayList<ElifClause>();
        Block elseBody = null;
        while (match(GdTokenType.ELIF)) {
            var elifStart = previous.startByte();
            var elifConditionStart = current.startByte();
            pushRecoveryOwner("elif.condition");
            var elifConditionOutcome = parseExpression(false);
            var elifCondition = absorbSlot(elifConditionOutcome, elifConditionStart, "elif.condition",
                    "Expected conditional expression after \"elif\".");
            var matchedElifColon = match(GdTokenType.COLON);
            var elifConditionFrame = popRecoveryOwner();
            if (!matchedElifColon) {
                recoveryTracker.emitError(
                        "Expected \":\" after \"elif\" condition, found \"%s\" instead."
                                .formatted(current.type().displayName()),
                        tokenRange(previous),
                        DiagnosticCodes.MISSING_SEPARATOR_SUITE_COLON,
                        RecoveryTransactionTracker.AttributionPolicy.TARGET_FRAME,
                        elifConditionFrame
                );
            }
            var elifSuite = parseSuiteWithLayout("\"elif\" block");
            var elifBody = elifSuite.block();
            // A damaged elif clause survives with its marker condition and healthy body.
            var clause = new ElifClause(elifCondition, elifBody,
                    range(elifStart, Math.max(elifBody.range().endByte(), elifCondition.range().endByte())));
            bindRecoveryOwner(elifConditionFrame, clause);
            if (elifSuite.layoutFrame() != null) {
                bindRecoveryOwner(elifSuite.layoutFrame(), clause);
            }
            elifClauses.add(clause);
        }
        RecoveryTransactionTracker.ProvisionalFrame elseColonFrame = null;
        SuiteResult elseSuite = null;
        if (match(GdTokenType.ELSE)) {
            pushRecoveryOwner("suite.body");
            if (!match(GdTokenType.COLON)) {
                recoveryTracker.emitError(
                        "Expected \":\" after \"else\".",
                        tokenRange(previous),
                        DiagnosticCodes.MISSING_SEPARATOR_SUITE_COLON,
                        RecoveryTransactionTracker.AttributionPolicy.CURRENT_FRAME,
                        null
                );
            }
            elseColonFrame = popRecoveryOwner();
            elseSuite = parseSuiteWithLayout("\"else\" block");
            elseBody = elseSuite.block();
        }

        var end = elseBody != null ? elseBody.range().endByte()
                : !elifClauses.isEmpty() ? elifClauses.getLast().range().endByte()
                  : trueBlock.range().endByte();
        end = Math.max(end, condition.range().endByte());
        var statement = new IfStatement(condition, trueBlock, List.copyOf(elifClauses), elseBody,
                range(start, end));
        bindRecoveryOwner(conditionFrame, statement);
        if (trueSuite.layoutFrame() != null) {
            bindRecoveryOwner(trueSuite.layoutFrame(), statement);
        }
        if (elseColonFrame != null) {
            bindRecoveryOwner(elseColonFrame, statement);
        }
        if (elseSuite != null && elseSuite.layoutFrame() != null) {
            bindRecoveryOwner(elseSuite.layoutFrame(), statement);
        }
        return statement;
    }

    /// Port of `GDScriptParser::parse_while`, including the save/enable/restore of the
    /// break/continue loop flags around the loop suite. A failed condition keeps a marker in the
    /// surviving statement (vendor keeps the node with a null condition).
    private @NotNull Statement parseWhile() {
        var start = statementStartByte;
        var conditionStart = current.startByte();
        pushRecoveryOwner("while.condition");
        var conditionOutcome = parseExpression(false);
        var condition = absorbSlot(conditionOutcome, conditionStart, "while.condition",
                "Expected conditional expression after \"while\".");
        var matchedColon = match(GdTokenType.COLON);
        var conditionFrame = popRecoveryOwner();
        if (!matchedColon) {
            recoveryTracker.emitError(
                    "Expected \":\" after \"while\" condition, found \"%s\" instead."
                            .formatted(current.type().displayName()),
                    tokenRange(previous),
                    DiagnosticCodes.MISSING_SEPARATOR_SUITE_COLON,
                    RecoveryTransactionTracker.AttributionPolicy.TARGET_FRAME,
                    conditionFrame
            );
        }

        var couldBreak = canBreak;
        var couldContinue = canContinue;
        canBreak = true;
        canContinue = true;
        var bodySuite = parseSuiteWithLayout("\"while\" block");
        var body = bodySuite.block();
        canBreak = couldBreak;
        canContinue = couldContinue;

        var statement = new WhileStatement(condition, body,
                range(start, Math.max(body.range().endByte(), condition.range().endByte())));
        bindRecoveryOwner(conditionFrame, statement);
        if (bodySuite.layoutFrame() != null) {
            bindRecoveryOwner(bodySuite.layoutFrame(), statement);
        }
        return statement;
    }

    /// Port of `GDScriptParser::parse_for`: optional `: type` after the loop variable (the two
    /// distinct `in` error messages depend on whether a type was given), iterable expression, and
    /// a loop suite with break/continue enabled. Both a missing loop variable name (`iterator = ""`)
    /// and a failed iterable retain the `ForStatement` node with diagnostic attribution.
    /// A damaged type may synchronize to `in` on the header's line without claiming a suite
    /// colon or enclosing delimiter.
    private @NotNull Statement parseFor() {
        var start = statementStartByte;
        pushRecoveryOwner("for.iterator");
        var hasIterator = match(GdTokenType.IDENTIFIER);
        if (!hasIterator) {
            recoveryTracker.emitError(
                    "Expected loop variable name after \"for\".",
                    tokenRange(previous),
                    DiagnosticCodes.FOR_MISSING_ITERATOR,
                    RecoveryTransactionTracker.AttributionPolicy.CURRENT_FRAME,
                    null
            );
        }
        var iterator = hasIterator ? previous.source() : "";

        TypeRef iteratorType = null;
        var canMatchIn = true;
        var headerLogicalStartLine = currentLogicalStartLine;
        var sawColon = match(GdTokenType.COLON);
        if (sawColon) {
            var colonToken = previous;
            var typeOutcome = parseTypeInternal(false);
            iteratorType = absorbType(typeOutcome, colonToken,
                    "Expected type specifier after \":\".", DiagnosticCodes.MISSING_TYPE_FOR_ITERATOR);
            if (!(typeOutcome instanceof ParseOutcome.Ok<ParsedType>)) {
                // Bounded same-line sync to `in` when a broken type followed `:`, stopping before
                // a suite colon, layout boundary, or enclosing delimiter/comma so outer arguments
                // and statements are never swallowed.
                while (!isAtEnd()
                        && !check(GdTokenType.IN)
                        && !check(GdTokenType.COLON)
                        && !isStatementEndToken()
                        && !check(GdTokenType.INDENT)
                        && !check(GdTokenType.DEDENT)
                        && !lambdaEnded
                        && !isAtUnclosedMultilineStop()
                        && !isAtAncestorOwnedCloser()
                        && !(check(GdTokenType.COMMA) && hasCommaOwningAncestor())
                        && !(inLambda && isVirtualLambdaBoundary())
                        && current.startLine() == colonToken.startLine()) {
                    forIteratorTypeSyncSteps++;
                    advance();
                }
                // Do not scan garbage on a later physical line, but an already-visible `in`
                // can follow an explicit continuation in the same logical header.
                canMatchIn = currentLogicalStartLine == headerLogicalStartLine
                        && !lambdaEnded && !isAtUnclosedMultilineStop();
            }
        }
        var matchedIn = canMatchIn && match(GdTokenType.IN);
        var iteratorFrame = popRecoveryOwner();

        if (!matchedIn) {
            recoveryTracker.emitError(
                    sawColon
                            ? "Expected \"in\" after \"for\" variable type specifier."
                            : "Expected \"in\" or \":\" after \"for\" variable name.",
                    tokenRange(previous),
                    DiagnosticCodes.FOR_MISSING_IN,
                    RecoveryTransactionTracker.AttributionPolicy.TARGET_FRAME,
                    iteratorFrame
            );
        }

        var iterableStart = current.startByte();
        pushRecoveryOwner("for.iterable");
        var iterableOutcome = parseExpression(false);
        var iterable = absorbSlot(iterableOutcome, iterableStart, "for.iterable",
                "Expected iterable after \"in\".");
        var matchedColon = match(GdTokenType.COLON);
        var iterableFrame = popRecoveryOwner();
        if (!matchedColon) {
            recoveryTracker.emitError(
                    "Expected \":\" after \"for\" condition, found \"%s\" instead."
                            .formatted(current.type().displayName()),
                    tokenRange(previous),
                    DiagnosticCodes.MISSING_SEPARATOR_SUITE_COLON,
                    RecoveryTransactionTracker.AttributionPolicy.TARGET_FRAME,
                    iterableFrame
            );
        }

        var couldBreak = canBreak;
        var couldContinue = canContinue;
        canBreak = true;
        canContinue = true;
        var bodySuite = parseSuiteWithLayout("\"for\" block");
        var body = bodySuite.block();
        canBreak = couldBreak;
        canContinue = couldContinue;

        var forEnd = Math.max(body.range().endByte(), iterable.range().endByte());
        if (iteratorType != null) {
            forEnd = Math.max(forEnd, iteratorType.range().endByte());
        }
        var statement = new ForStatement(iterator, iteratorType, iterable, body,
                range(start, forEnd));
        bindRecoveryOwner(iteratorFrame, statement);
        bindRecoveryOwner(iterableFrame, statement);
        if (bodySuite.layoutFrame() != null) {
            bindRecoveryOwner(bodySuite.layoutFrame(), statement);
        }
        return statement;
    }

    /// Port of `GDScriptParser::parse_assert`: parenthesized condition plus optional message after
    /// a comma (one trailing comma allowed). A failed condition or message keeps a marker in its
    /// slot and the statement survives, consuming the success-path tail (a deliberate improvement
    /// over the vendor, which drops the node); the multiline pop position matches the success
    /// path on every branch.
    private @NotNull Statement parseAssert() {
        var start = statementStartByte;
        StructuralFrame assertFrame = null;
        // Covers the whole argument region (including a missing opening `(` diagnostic).
        pushRecoveryOwner("assert.arguments");
        pushMultiline();
        if (match(GdTokenType.PARENTHESIS_OPEN)) {
            assertFrame = pushBracketedFrame(
                    StructuralRole.ASSERT,
                    GdTokenType.PARENTHESIS_OPEN,
                    GdTokenType.PARENTHESIS_CLOSE,
                    previous.startByte(),
                    true,
                    true,
                    "\"assert\"",
                    "Expected \")\" after assert expression.",
                    DiagnosticCodes.MISSING_CLOSER_ASSERT,
                    DiagnosticCodes.MISMATCHED_CLOSER_ASSERT
            );
        } else {
            popMultiline(false);
            recoveryTracker.emitError(
                    "Expected \"(\" after \"assert\".",
                    tokenRange(previous),
                    DiagnosticCodes.SYNTAX_UNEXPECTED_TOKEN,
                    RecoveryTransactionTracker.AttributionPolicy.CURRENT_FRAME,
                    null
            );
        }

        var conditionStart = current.startByte();
        pushRecoveryOwner("assert.condition");
        var conditionOutcome = parseExpression(false);
        var condition = absorbSlot(conditionOutcome, conditionStart, "assert.condition",
                "Expected expression to assert.");
        var conditionFrame = popRecoveryOwner();

        Expression message = null;
        RecoveryTransactionTracker.ProvisionalFrame messageFrame = null;
        if ((assertFrame == null || !assertFrame.settled)
                && !nestingBudgetExceeded
                && !isAtUnclosedMultilineStop()
                && match(GdTokenType.COMMA)
                && (!check(GdTokenType.PARENTHESIS_CLOSE) || hasCurrentSlotLexicalErrors())
                && (!isAtUnclosedMultilineStop() || hasCurrentSlotLexicalErrors())) {
            var messageStart = current.startByte();
            pushRecoveryOwner("assert.message");
            var messageOutcome = parseExpression(false);
            message = absorbSlot(messageOutcome, messageStart, "assert.message",
                    "Expected error message for assert after \",\".");
            messageFrame = popRecoveryOwner();
            if ((assertFrame == null || !assertFrame.settled)
                    && !nestingBudgetExceeded
                    && !isAtUnclosedMultilineStop()) {
                match(GdTokenType.COMMA);
            }
        }

        if (assertFrame != null) {
            consumeCloser(assertFrame);
        }
        var argumentsFrame = popRecoveryOwner();
        var assertEnd = Math.max(extentEnd(), condition.range().endByte());
        if (message != null) {
            assertEnd = Math.max(assertEnd, message.range().endByte());
        }
        var statement = new AssertStatement(condition, message, range(start, assertEnd));
        bindRecoveryOwner(conditionFrame, statement);
        if (messageFrame != null) {
            bindRecoveryOwner(messageFrame, statement);
        }
        bindRecoveryOwner(argumentsFrame, statement);
        finishStatement(statement, "\"assert\"");
        return statement;
    }

    // ---------------------------------------------------------------------------------------------
    // Match statement and patterns — ports of parse_match / parse_match_branch /
    // parse_match_pattern. Patterns are represented with the existing expression AST (the
    // reference path's contract): literals/identifiers/general expressions as-is, `var x` as
    // `PatternBindingExpression`, `_` as a plain identifier, containers as array/dictionary
    // expressions with `openEnded` carrying the `..` marker.
    // ---------------------------------------------------------------------------------------------

    /// One parsed match pattern: an expression node, or the `..` rest token (only legal inside
    /// array/dictionary patterns, where it turns into `openEnded`). A `null` result occurs only
    /// when the nesting budget rejects the pattern.
    private record MatchPattern(@Nullable Expression expression, @Nullable GdToken restToken) {
        boolean rest() {
            return restToken != null;
        }
    }

    private RecoveryTransactionTracker.@NotNull ProvisionalFrame emitMatchLayoutError(
            RecoveryTransactionTracker.@Nullable ProvisionalFrame existing,
            @NotNull String message,
            @NotNull Range diagnosticRange,
            @NotNull String code
    ) {
        var frame = existing;
        if (frame == null) {
            pushRecoveryOwner("match.section");
            frame = popRecoveryOwner();
        }
        recoveryTracker.emitError(
                message,
                diagnosticRange,
                code,
                RecoveryTransactionTracker.AttributionPolicy.TARGET_FRAME,
                frame
        );
        return frame;
    }

    /// Port of `GDScriptParser::parse_match`. Branch-level `pass` is consumed without a node and
    /// branch-level annotations other than `@warning_ignore` are rejected (the reference path
    /// errors/drops differently — a registered parity divergence). The statement's range ends at
    /// the last branch, before the closing DEDENT. A failed value keeps a marker in the surviving
    /// statement (including the missing-INDENT early return, which keeps an empty section list).
    private @NotNull Statement parseMatch() {
        var start = statementStartByte;
        var valueStart = current.startByte();
        pushRecoveryOwner("match.value");
        var valueOutcome = parseExpression(false);
        var value = absorbSlot(valueOutcome, valueStart, "match.value",
                "Expected expression to test after \"match\".");
        var matchedColon = match(GdTokenType.COLON);
        var valueFrame = popRecoveryOwner();
        if (!matchedColon) {
            recoveryTracker.emitError(
                    "Expected \":\" after \"match\" expression.",
                    tokenRange(previous),
                    DiagnosticCodes.MISSING_SEPARATOR_SUITE_COLON,
                    RecoveryTransactionTracker.AttributionPolicy.TARGET_FRAME,
                    valueFrame
            );
        }
        RecoveryTransactionTracker.ProvisionalFrame matchLayoutFrame = null;
        if (hasSameLogicalLineHeaderLexicalErrors()) {
            pushRecoveryOwner("match.section");
            matchLayoutFrame = popRecoveryOwner();
            claimSameLogicalLineHeaderLexicalErrors(matchLayoutFrame);
        }
        var headerEnd = Math.max(extentEnd(), value.range().endByte());
        if (match(GdTokenType.NEWLINE)) {
            // A comment trailing the match-header line is dropped by both paths.
            discardTrailingCommentsBefore(previous.startByte());
        } else {
            matchLayoutFrame = emitMatchLayoutError(
                    matchLayoutFrame,
                    "Expected a newline after \"match\" statement.",
                    tokenRange(previous),
                    DiagnosticCodes.SYNTAX_UNEXPECTED_TOKEN
            );
        }

        var sections = new ArrayList<MatchSection>();
        // Accepted `@warning_ignore` annotations pending a branch to attach to; this AST drops
        // them (branches have no annotation field), but leftover ones are still diagnosed.
        var danglingAnnotations = new ArrayList<AnnotationStatement>();
        var matchIndent = -1;
        if (!match(GdTokenType.INDENT)) {
            matchLayoutFrame = emitMatchLayoutError(
                    matchLayoutFrame,
                    "Expected an indented block after \"match\" statement.",
                    tokenRange(previous),
                    DiagnosticCodes.SYNTAX_UNEXPECTED_TOKEN
            );
            // Vendor returns immediately on a missing indented block: no DEDENT consumption (an
            // outer suite's DEDENT must not be eaten) and no comment discard.
            var earlyEnd = Math.max(headerEnd, Math.max(extentEnd(), value.range().endByte()));
            var early = new MatchStatement(value, List.of(), range(start, earlyEnd));
            bindRecoveryOwner(valueFrame, early);
            bindRecoveryOwner(matchLayoutFrame, early);
            return early;
        }
        if (matchLayoutFrame == null) {
            pushRecoveryOwner("match.section");
            matchLayoutFrame = popRecoveryOwner();
        }
        var matchBodyStart = current.startByte();
        var matchLayoutBaseline = 0;
        if (!check(GdTokenType.DEDENT)) {
            matchIndent = indentOf(current.startByte());
            if (!isAtEnd()) {
                matchLayoutBaseline = lineIndentOf(current.startByte());
            }
        }
        var matchBodyFrame = pushStructuralFrame(StructuralFrame.nonBracketed(
                StructuralRole.MATCH_BODY, matchBodyStart, matchLayoutBaseline, true, false, "match body"));
        try {
            while (!check(GdTokenType.DEDENT) && !isAtEnd()) {
                var loopStart = current;
                // A depth trip inside a branch leaves a poisoned region that ends at the branch
                // indentation; consume it before interpreting the next line as a branch (the match
                // level has no parseStatement tail hook of its own). The `continue` re-checks the
                // loop guards: synchronization may have stopped at the match's own closing DEDENT.
                if (nestingBudgetExceeded) {
                    synchronize();
                    continue;
                }
                // The reference path drops comments at the match branch level (a col-0 comment
                // between branches even breaks its grammar — a registered parity divergence).
                discardPendingComments();
                if (check(GdTokenType.PASS)) {
                    if (hasUnsettledLexicalBatch(currentPrecedingErrors)) {
                        var count = currentPrecedingErrors.entryCount();
                        var mask = new boolean[count];
                        var any = false;
                        for (var i = 0; i < count; i++) {
                            if (currentPrecedingErrors.entryLogicalStartLine(i) == currentLogicalStartLine) {
                                mask[i] = true;
                                any = true;
                            }
                        }
                        if (any) {
                            var claimed = recoveryTracker.claimLexicalBatchSubset(
                                    currentPrecedingErrors, mask, matchLayoutFrame);
                            if (claimed != null) {
                                claimedLexicalEndByte = Math.max(claimedLexicalEndByte, claimed.endByte());
                                headerEnd = Math.max(headerEnd, claimed.endByte());
                            }
                        }
                    }
                    match(GdTokenType.PASS);
                    if (hasSameLogicalLineHeaderLexicalErrors()) {
                        var claimed = claimSameLogicalLineHeaderLexicalErrors(matchLayoutFrame);
                        if (claimed != null) {
                            headerEnd = Math.max(headerEnd, claimed.endByte());
                        }
                    }
                    if (!match(GdTokenType.NEWLINE)) {
                        matchLayoutFrame = emitMatchLayoutError(
                                matchLayoutFrame,
                                "Expected newline after \"pass\".",
                                tokenRange(previous),
                                DiagnosticCodes.SYNTAX_UNEXPECTED_TOKEN
                        );
                    }
                    continue;
                }
                if (match(GdTokenType.ANNOTATION)) {
                    var annotation = parseMatchAnnotation(matchLayoutFrame);
                    // Only `@warning_ignore` may annotate a branch (Godot attaches it to the
                    // branch node, which has no annotation field here); anything else is an error
                    // and is not tracked (vendor skips it the same way).
                    if (annotation.name().equals("warning_ignore")) {
                        danglingAnnotations.add(annotation);
                    } else {
                        matchLayoutFrame = emitMatchLayoutError(
                                matchLayoutFrame,
                                "Annotation \"@%s\" is not allowed in this level.".formatted(annotation.name()),
                                annotation.range(),
                                DiagnosticCodes.ANNOTATION_INVALID_LEVEL
                        );
                        if (isolatedFallbackFrame != null && activePropertyRecoveryAnnotations != null) {
                            activePropertyRecoveryAnnotations.add(annotation);
                        }
                    }
                    continue;
                }
                sections.add(parseMatchSection());
                danglingAnnotations.clear();
                if (current == loopStart && !check(GdTokenType.DEDENT) && !isAtEnd()) {
                    advance();
                }
            }
        } finally {
            popStructuralFrame(matchBodyFrame);
        }
        // The statement ends at the last branch (before the closing DEDENT), not at the DEDENT
        // the last branch's suite consumed — the reference path's extent ends there too.
        var end = sections.isEmpty()
                ? Math.max(headerEnd, Math.max(extentEnd(), value.range().endByte()))
                : Math.max(sections.getLast().range().endByte(), Math.max(headerEnd, value.range().endByte()));
        // Tail comments indented into the match body are branch-level and dropped with it, but
        // the match node still spans them (the reference path's extent covers them); less-
        // indented ones stay pending for the outer sequence (they are not match trivia).
        // A trailing comment always belongs to its (match-internal) line — a rejected branch's
        // suite never drains it — and must not block the cleanup of whole-line comments behind
        // it; whole-line comments still need the indent check so outer-scope ones stay pending.
        if (matchIndent >= 0) {
            while (commentCursor < comments.size()
                    && comments.get(commentCursor).startByte() < current.startByte()
                    && (!comments.get(commentCursor).wholeLine()
                    || indentOf(comments.get(commentCursor)) >= matchIndent)) {
                end = Math.max(end, comments.get(commentCursor).endByte());
                commentCursor++;
            }
        }
        for (var dangling : danglingAnnotations) {
            matchLayoutFrame = emitMatchLayoutError(
                    matchLayoutFrame,
                    "Annotation \"@%s\" does not precede a valid target, so it will have no effect."
                            .formatted(dangling.name()),
                    dangling.range(),
                    DiagnosticCodes.ANNOTATION_DANGLING
            );
            if (isolatedFallbackFrame != null && activePropertyRecoveryAnnotations != null) {
                activePropertyRecoveryAnnotations.add(dangling);
            }
        }
        if (!match(GdTokenType.DEDENT)) {
            matchLayoutFrame = emitMatchLayoutError(
                    matchLayoutFrame,
                    "Expected an indented block after \"match\" statement.",
                    tokenRange(previous),
                    DiagnosticCodes.SYNTAX_UNEXPECTED_TOKEN
            );
        }

        var statement = new MatchStatement(value, List.copyOf(sections), range(start, end));
        bindRecoveryOwner(valueFrame, statement);
        bindRecoveryOwner(matchLayoutFrame, statement);
        return statement;
    }

    /// Port of `GDScriptParser::parse_match_branch`: comma-separated patterns, optional `when`
    /// guard, colon, and the branch suite. The section range spans from the first pattern to the
    /// suite end. A failed expression pattern keeps a marker in the pattern list (so the
    /// "No pattern found" check treats it as seen); a `when` whose guard failed keeps a marker in
    /// the guard slot, which also selects the "after guard" colon message. The vendor's
    /// bind-conflict checks (multiple patterns with a bind, duplicate or shadowing binds) are
    /// symbol-table-dependent and deferred to the analysis layer.
    private @NotNull MatchSection parseMatchSection() {
        var sectionStartToken = current;
        var start = current.startByte();
        var patterns = new ArrayList<Expression>();
        var sawAnyPattern = false;
        pushRecoveryOwner("match.pattern");
        do {
            var pattern = parseMatchPattern();
            if (pattern == null) {
                if (nestingBudgetExceeded) {
                    break;
                }
                continue;
            }
            sawAnyPattern = true;
            if (pattern.rest()) {
                var restToken = Objects.requireNonNull(pattern.restToken());
                var restRange = tokenRange(restToken);
                recoveryTracker.emitError(
                        "Rest pattern can only be used inside array and dictionary patterns.",
                        restRange,
                        DiagnosticCodes.MATCH_REST_MISPLACED,
                        RecoveryTransactionTracker.AttributionPolicy.CURRENT_FRAME,
                        null
                );
                patterns.add(new ErrorExpression(CstIssueKind.ERROR, DIAGNOSTIC_NODE_TYPE,
                        restToken.source(), restRange));
            } else {
                patterns.add(pattern.expression());
            }
        } while (!nestingBudgetExceeded && match(GdTokenType.COMMA));
        if (!sawAnyPattern) {
            recoveryTracker.emitError(
                    "No pattern found for \"match\" branch.",
                    tokenRange(previous),
                    DiagnosticCodes.SYNTAX_UNEXPECTED_TOKEN,
                    RecoveryTransactionTracker.AttributionPolicy.CURRENT_FRAME,
                    null
            );
        }
        var patternFrame = popRecoveryOwner();

        Expression guard = null;
        RecoveryTransactionTracker.ProvisionalFrame guardFrame = null;
        var hasGuard = match(GdTokenType.WHEN);
        if (hasGuard) {
            var guardStart = current.startByte();
            pushRecoveryOwner("match.guard");
            var guardOutcome = parseExpression(false);
            guard = absorbSlot(guardOutcome, guardStart, "match.guard",
                    "Expected expression for pattern guard after \"when\".");
            guardFrame = popRecoveryOwner();
        }

        pushRecoveryOwner("match.section");
        var missingColon = !match(GdTokenType.COLON);
        if (missingColon) {
            recoveryTracker.emitError(
                    hasGuard
                            ? "Expected \":\" after \"match\" pattern guard."
                            : "Expected \":\" or \"when\" after \"match\" patterns.",
                    tokenRange(previous),
                    DiagnosticCodes.MISSING_SEPARATOR_MATCH_COLON,
                    RecoveryTransactionTracker.AttributionPolicy.CURRENT_FRAME,
                    null
            );
        }
        var sectionFrame = popRecoveryOwner();

        Block body;
        RecoveryTransactionTracker.ProvisionalFrame bodyLayoutFrame = null;
        if (missingColon) {
            var sectionConsumedAny = current != sectionStartToken;
            var alreadyAtLineBoundary = isAtEnd()
                    || (sectionConsumedAny
                            && (previous.type() == GdTokenType.NEWLINE
                                    || previous.type() == GdTokenType.DEDENT))
                    || lambdaEnded
                    || (hasGuard && current.type() != GdTokenType.NEWLINE
                            && currentLogicalStartLine > Math.max(
                                    guard.range().endPoint().row() + 1,
                                    requirePreviousToken().endLine()));
            if (lambdaEnded) {
                lambdaEnded = false;
            }
            if (alreadyAtLineBoundary) {
                if (previous.type() == GdTokenType.NEWLINE && check(GdTokenType.INDENT)) {
                    // An inline lambda guard already consumed the line break before an indented
                    // suite: parse it directly so the stray INDENT does not desync sibling branches.
                    var bodySuite = parseSuiteWithLayout("match pattern block", false, true);
                    body = bodySuite.block();
                    bodyLayoutFrame = bodySuite.layoutFrame();
                } else {
                    var emptyEnd = Math.max(start, extentEnd());
                    body = new Block(List.of(), range(emptyEnd, emptyEnd));
                }
            } else {
                // Drain only the remainder of the malformed branch header line, stopping before DEDENT
                // so an outer layout boundary is never stolen; take over a real indented suite only
                // when `NEWLINE + INDENT` immediately follows.
                while (current.type() != GdTokenType.NEWLINE
                        && current.type() != GdTokenType.DEDENT
                        && !isAtEnd()) {
                    if (hasSameLogicalLineHeaderLexicalErrors()) {
                        claimSameLogicalLineHeaderLexicalErrors(sectionFrame);
                    }
                    advance();
                }
                if (check(GdTokenType.NEWLINE) && peekAfterCurrent() == GdTokenType.INDENT) {
                    var bodySuite = parseSuiteWithLayout("match pattern block");
                    body = bodySuite.block();
                    bodyLayoutFrame = bodySuite.layoutFrame();
                } else {
                    if (hasSameLogicalLineHeaderLexicalErrors()) {
                        claimSameLogicalLineHeaderLexicalErrors(sectionFrame);
                    }
                    match(GdTokenType.NEWLINE);
                    var emptyEnd = Math.max(start, extentEnd());
                    body = new Block(List.of(), range(emptyEnd, emptyEnd));
                }
            }
        } else {
            var bodySuite = parseSuiteWithLayout("match pattern block");
            body = bodySuite.block();
            bodyLayoutFrame = bodySuite.layoutFrame();
        }
        // The section's left edge extends over its patterns: a zero-consumption pattern marker
        // anchors at `extentEnd()`, which can precede the section start on a follow-up branch
        // (`previous` is then the preceding branch's newline, not this line's content).
        var sectionStart = start;
        var sectionEnd = body.range().endByte();
        for (var pattern : patterns) {
            sectionStart = Math.min(sectionStart, pattern.range().startByte());
            sectionEnd = Math.max(sectionEnd, pattern.range().endByte());
        }
        if (guard != null) {
            sectionEnd = Math.max(sectionEnd, guard.range().endByte());
        }
        var section = new MatchSection(List.copyOf(patterns), guard, body,
                range(sectionStart, sectionEnd));
        bindRecoveryOwner(sectionFrame, section);
        bindRecoveryOwner(patternFrame, section);
        if (guardFrame != null) {
            bindRecoveryOwner(guardFrame, section);
        }
        if (bodyLayoutFrame != null) {
            bindRecoveryOwner(bodyLayoutFrame, section);
        }
        return section;
    }

    /// Port of `GDScriptParser::parse_match_pattern`: `var` binds, `_` wildcard, `..` rest, and
    /// nested array/dictionary patterns get dedicated handling; anything else parses as a general
    /// expression (`false` — no assignments in patterns). A failed expression pattern or a `var`
    /// bind without a name keeps an explicit `ErrorExpression` in the pattern position so branch,
    /// array, and dictionary positions are never silently dropped.
    private @Nullable MatchPattern parseMatchPattern() {
        // Nested array/dictionary patterns recurse through here.
        if (!enterNestingLevel()) {
            return null;
        }
        try {
            return parseMatchPatternImpl();
        } finally {
            exitNestingLevel();
        }
    }

    private @NotNull MatchPattern parseMatchPatternImpl() {
        switch (current.type()) {
            case VAR -> {
                var varToken = current;
                var start = varToken.startByte();
                advance();
                if (!match(GdTokenType.IDENTIFIER)) {
                    var varRange = tokenRange(varToken);
                    recoveryTracker.emitError(
                            "Expected bind name after \"var\".",
                            varRange,
                            DiagnosticCodes.MATCH_BIND_MISSING_NAME,
                            RecoveryTransactionTracker.AttributionPolicy.CURRENT_FRAME,
                            null
                    );
                    return new MatchPattern(
                            new ErrorExpression(CstIssueKind.ERROR, DIAGNOSTIC_NODE_TYPE,
                                    varToken.source(), varRange),
                            null
                    );
                }
                return new MatchPattern(new PatternBindingExpression(previous.source(),
                        range(start, previous.endByte())), null);
            }
            case UNDERSCORE -> {
                advance();
                return new MatchPattern(new IdentifierExpression("_",
                        range(previous.startByte(), previous.endByte())), null);
            }
            case PERIOD_PERIOD -> {
                advance();
                return new MatchPattern(null, previous);
            }
            case BRACKET_OPEN -> {
                return parseArrayMatchPattern();
            }
            case BRACE_OPEN -> {
                return parseDictionaryMatchPattern();
            }
            default -> {
                var patternStart = current.startByte();
                var expressionOutcome = parseExpression(false);
                var expression = absorbSlot(expressionOutcome, patternStart, "match.pattern",
                        "Expected expression for match pattern.");
                return new MatchPattern(expression, null);
            }
        }
    }

    /// Array pattern: sub-patterns recurse, `..` is legal once at the end (further elements or
    /// duplicate rest markers emit `MATCH_REST_NOT_LAST` while preserving element positions).
    private @NotNull MatchPattern parseArrayMatchPattern() {
        var start = current.startByte();
        pushMultiline();
        advance();
        var arrayPatternFrame = pushBracketedFrame(
                StructuralRole.MATCH_ARRAY_PATTERN,
                GdTokenType.BRACKET_OPEN,
                GdTokenType.BRACKET_CLOSE,
                start,
                true,
                true,
                "array pattern",
                "Expected \"]\" to close the array pattern.",
                DiagnosticCodes.MISSING_CLOSER_MATCH_ARRAY_PATTERN,
                DiagnosticCodes.MISMATCHED_CLOSER_MATCH_ARRAY_PATTERN
        );

        var elements = new ArrayList<Expression>();
        GdToken firstRestToken = null;
        var reportedRestNotLast = false;
        do {
            if (arrayPatternFrame.settled || nestingBudgetExceeded
                    || isAtEnd()
                    || (check(GdTokenType.BRACKET_CLOSE) && !hasCurrentSlotLexicalErrors())) {
                break;
            }
            if (isAtUnclosedMultilineStop()) {
                if (hasCurrentSlotLexicalErrors()) {
                    var errExpr = claimLexicalErrorSlotValue(previous.endByte(), false);
                    if (firstRestToken != null && !reportedRestNotLast) {
                        reportedRestNotLast = true;
                        recoveryTracker.emitError(
                                "The \"..\" pattern must be the last element in the pattern array.",
                                tokenRange(firstRestToken),
                                DiagnosticCodes.MATCH_REST_NOT_LAST,
                                RecoveryTransactionTracker.AttributionPolicy.CURRENT_FRAME,
                                null
                        );
                    }
                    elements.add(errExpr);
                }
                break;
            }
            var itemStart = current.startByte();
            var diagBefore = recoveryTracker.diagnosticCount();
            var sub = parseMatchPattern();
            if (sub != null) {
                if (firstRestToken != null) {
                    if (sub.rest()) {
                        recoveryTracker.emitError(
                                "The \"..\" pattern must be the last element in the pattern array.",
                                tokenRange(Objects.requireNonNull(sub.restToken())),
                                DiagnosticCodes.MATCH_REST_NOT_LAST,
                                RecoveryTransactionTracker.AttributionPolicy.CURRENT_FRAME,
                                null
                        );
                    } else {
                        if (!reportedRestNotLast) {
                            reportedRestNotLast = true;
                            recoveryTracker.emitError(
                                    "The \"..\" pattern must be the last element in the pattern array.",
                                    tokenRange(firstRestToken),
                                    DiagnosticCodes.MATCH_REST_NOT_LAST,
                                    RecoveryTransactionTracker.AttributionPolicy.CURRENT_FRAME,
                                    null
                            );
                        }
                        elements.add(sub.expression());
                    }
                } else if (sub.rest()) {
                    firstRestToken = sub.restToken();
                } else {
                    elements.add(sub.expression());
                }
            }
            if (!advanceSeparatedList(arrayPatternFrame, itemStart, previous,
                    recoveryTracker.diagnosticCount() > diagBefore, this::isArrayPatternStarter)) {
                break;
            }
        } while (true);

        consumeCloser(arrayPatternFrame);
        return new MatchPattern(new ArrayExpression(List.copyOf(elements), firstRestToken != null,
                range(start, Math.max(start, extentEnd()))), null);
    }

    /// Dictionary pattern: `key: pattern` entries, the `..` rest marker, and key-only or
    /// damaged-key entries (`DictEntry(key, null, range)`). Entries stay independent so a damaged
    /// key without a colon never discards surviving sibling entries, and non-starter garbage
    /// after a damaged entry is boundedly skipped at the same bracket level.
    private @NotNull MatchPattern parseDictionaryMatchPattern() {
        var start = current.startByte();
        pushMultiline();
        advance();
        var dictPatternFrame = pushBracketedFrame(
                StructuralRole.MATCH_DICTIONARY_PATTERN,
                GdTokenType.BRACE_OPEN,
                GdTokenType.BRACE_CLOSE,
                start,
                true,
                true,
                "dictionary pattern",
                "Expected \"}\" to close the dictionary pattern.",
                DiagnosticCodes.MISSING_CLOSER_MATCH_DICTIONARY_PATTERN,
                DiagnosticCodes.MISMATCHED_CLOSER_MATCH_DICTIONARY_PATTERN
        );

        var entries = new ArrayList<DictEntry>();
        GdToken firstRestToken = null;
        var reportedRestNotLast = false;
        do {
            if (dictPatternFrame.settled || nestingBudgetExceeded
                    || (check(GdTokenType.BRACE_CLOSE) && !hasCurrentSlotLexicalErrors())
                    || isAtEnd()) {
                break;
            }
            if (isAtUnclosedMultilineStop()) {
                if (hasCurrentSlotLexicalErrors()) {
                    var key = claimLexicalErrorSlotValue(previous.endByte(), false);
                    if (firstRestToken != null && !reportedRestNotLast) {
                        reportedRestNotLast = true;
                        recoveryTracker.emitError(
                                "The \"..\" pattern must be the last element in the pattern dictionary.",
                                tokenRange(firstRestToken),
                                DiagnosticCodes.MATCH_REST_NOT_LAST,
                                RecoveryTransactionTracker.AttributionPolicy.CURRENT_FRAME,
                                null
                        );
                    }
                    entries.add(new DictEntry(key, null,
                            range(key.range().startByte(), Math.max(key.range().endByte(), extentEnd()))));
                }
                break;
            }
            var itemStart = current.startByte();
            var diagBefore = recoveryTracker.diagnosticCount();
            if (match(GdTokenType.PERIOD_PERIOD)) {
                var restToken = previous;
                if (firstRestToken != null) {
                    recoveryTracker.emitError(
                            "The \"..\" pattern must be the last element in the pattern dictionary.",
                            tokenRange(restToken),
                            DiagnosticCodes.MATCH_REST_NOT_LAST,
                            RecoveryTransactionTracker.AttributionPolicy.CURRENT_FRAME,
                            null
                    );
                } else {
                    firstRestToken = restToken;
                }
            } else {
                var keyStart = current.startByte();
                var keyOutcome = parseExpression(false);
                if (dictPatternFrame.settled || nestingBudgetExceeded) {
                    break;
                }
                var key = absorbSlot(keyOutcome, keyStart, "dictionary_pattern.key",
                        "Expected expression as key for dictionary pattern.");
                if (firstRestToken != null && !reportedRestNotLast) {
                    reportedRestNotLast = true;
                    recoveryTracker.emitError(
                            "The \"..\" pattern must be the last element in the pattern dictionary.",
                            tokenRange(firstRestToken),
                            DiagnosticCodes.MATCH_REST_NOT_LAST,
                            RecoveryTransactionTracker.AttributionPolicy.CURRENT_FRAME,
                            null
                    );
                }
                if (match(GdTokenType.COLON)) {
                    var sub = parseMatchPattern();
                    if (dictPatternFrame.settled || nestingBudgetExceeded) {
                        break;
                    }
                    if (sub != null) {
                        Expression entryValue;
                        if (sub.rest()) {
                            var restToken = Objects.requireNonNull(sub.restToken());
                            var restRange = tokenRange(restToken);
                            recoveryTracker.emitError(
                                    "The \"..\" pattern cannot be used as a value.",
                                    restRange,
                                    DiagnosticCodes.MATCH_REST_MISPLACED,
                                    RecoveryTransactionTracker.AttributionPolicy.CURRENT_FRAME,
                                    null
                            );
                            entryValue = new ErrorExpression(CstIssueKind.ERROR, DIAGNOSTIC_NODE_TYPE,
                                    restToken.source(), restRange);
                        } else {
                            entryValue = Objects.requireNonNull(sub.expression());
                        }
                        entries.add(new DictEntry(key, entryValue,
                                range(Math.min(keyStart, key.range().startByte()),
                                        Math.max(extentEnd(), entryValue.range().endByte()))));
                    }
                } else {
                    entries.add(new DictEntry(key, null,
                            range(Math.min(keyStart, key.range().startByte()),
                                    Math.max(key.range().endByte(), extentEnd()))));
                }
            }
            if (!advanceSeparatedList(dictPatternFrame, itemStart, previous,
                    recoveryTracker.diagnosticCount() > diagBefore, this::isDictionaryPatternKeyStarter)) {
                break;
            }
        } while (true);

        consumeCloser(dictPatternFrame);
        return new MatchPattern(new DictionaryExpression(List.copyOf(entries), firstRestToken != null,
                range(start, Math.max(start, extentEnd()))), null);
    }

    // ---------------------------------------------------------------------------------------------
    // Annotations and types.
    // ---------------------------------------------------------------------------------------------

    /// Port of `GDScriptParser::parse_annotation`, syntax only: the registry lookups (name,
    /// target, argument count) are cut. Arguments may span lines and may use a trailing comma. A
    /// failed argument keeps a marker at its position in the list; the owner frame binds any
    /// missing-`)` diagnostic to the annotation itself (the annotated declaration does not exist
    /// yet at this point, so it cannot own the facet).
    @SuppressWarnings("PointlessBitwiseExpression")
    private static final int ANN_SCRIPT = 1 << 0;
    private static final int ANN_CLASS = 1 << 1;
    private static final int ANN_VARIABLE = 1 << 2;
    private static final int ANN_CONSTANT = 1 << 3;
    private static final int ANN_SIGNAL = 1 << 4;
    private static final int ANN_FUNCTION = 1 << 5;
    private static final int ANN_STATEMENT = 1 << 6;
    private static final int ANN_STANDALONE = 1 << 7;
    private static final int ANN_CLASS_LEVEL =
            ANN_CLASS | ANN_VARIABLE | ANN_CONSTANT | ANN_SIGNAL | ANN_FUNCTION;

    private record PendingAnnotation(
            @NotNull AnnotationStatement statement,
            @NotNull RecoveryTransactionTracker.ProvisionalFrame targetFrame,
            boolean scriptRootEligible,
            boolean alreadyDiagnosed,
            boolean recordPropertyRecovery
    ) {
    }

    private record ParsedAnnotation(
            @NotNull AnnotationStatement statement,
            @NotNull RecoveryTransactionTracker.ProvisionalFrame targetFrame,
            boolean hadNewlineAfter
    ) {
    }

    private @NotNull AnnotationStatement parseMatchAnnotation(
            RecoveryTransactionTracker.@NotNull ProvisionalFrame matchFallbackFrame
    ) {
        var parsed = parseScopedAnnotation(matchFallbackFrame, "match.section");
        recoveryTracker.abandon(parsed.targetFrame());
        return parsed.statement();
    }

    private @NotNull ParsedAnnotation parseScopedAnnotation() {
        return parseScopedAnnotation(null, null);
    }

    private @NotNull ParsedAnnotation parseScopedAnnotation(
            RecoveryTransactionTracker.@Nullable ProvisionalFrame fallbackFrame,
            @Nullable String fallbackFacet
    ) {
        var atToken = previous;
        var name = atToken.source().substring(1);

        if (fallbackFrame != null && fallbackFacet != null) {
            recoveryTracker.pushChildFrame("annotation.target", fallbackFrame, fallbackFacet);
        } else {
            pushRecoveryOwner("annotation.target");
        }
        var targetFramePeek = Objects.requireNonNull(recoveryTracker.activeFrame());
        var arguments = new ArrayList<Expression>();
        var end = atToken.endByte();
        RecoveryTransactionTracker.ProvisionalFrame argumentsFrame = null;
        if (check(GdTokenType.PARENTHESIS_OPEN)) {
            pushMultiline();
            advance();
            var annotationFrame = pushBracketedFrame(
                    StructuralRole.ANNOTATION,
                    GdTokenType.PARENTHESIS_OPEN,
                    GdTokenType.PARENTHESIS_CLOSE,
                    previous.startByte(),
                    true,
                    true,
                    "annotation arguments",
                    "Expected \")\" after annotation arguments.",
                    DiagnosticCodes.MISSING_CLOSER_ANNOTATION,
                    DiagnosticCodes.MISMATCHED_CLOSER_ANNOTATION
            );
            recoveryTracker.pushChildFrame("annotation.arguments", targetFramePeek, "annotation.target");
            do {
                if (annotationFrame.settled || nestingBudgetExceeded
                        || (check(GdTokenType.PARENTHESIS_CLOSE) && !hasCurrentSlotLexicalErrors())) {
                    // Allow for trailing comma at closer.
                    break;
                }
                if (isAtUnclosedMultilineStop()) {
                    if (hasCurrentSlotLexicalErrors()) {
                        arguments.add(claimLexicalErrorSlotValue(previous.endByte(), false));
                    }
                    break;
                }
                var argumentStart = current.startByte();
                var argumentOutcome = parseExpression(false);
                arguments.add(absorbSlot(argumentOutcome, argumentStart, "annotation.argument",
                        "Expected expression as the annotation argument."));
            } while (!annotationFrame.settled
                    && !nestingBudgetExceeded
                    && !isAtUnclosedMultilineStop()
                    && match(GdTokenType.COMMA));

            var closed = consumeCloser(annotationFrame);
            argumentsFrame = popRecoveryOwner();
            end = extentEnd();
            if (!arguments.isEmpty()) {
                end = Math.max(end, arguments.getLast().range().endByte());
            }
            if (!closed && !isAtEnd() && !check(GdTokenType.NEWLINE) && !check(GdTokenType.DEDENT)
                    && !isVirtualLambdaBoundary()) {
                markCurrentFramePendingSync();
            }
        }
        var targetFrame = popRecoveryOwner();
        var hadNewlineAfter = match(GdTokenType.NEWLINE); // Newline after annotation is optional.
        var annotation = new AnnotationStatement(name, List.copyOf(arguments), range(atToken.startByte(), end));
        if (argumentsFrame != null) {
            bindRecoveryOwner(argumentsFrame, annotation);
        }
        return new ParsedAnnotation(annotation, targetFrame, hadNewlineAfter);
    }

    /// Returns the Godot target-kind bitmask for well-known built-in annotations, or `-1` for
    /// custom/user-defined annotations so unknown annotations remain usable on declarations and
    /// statements without a closed whitelist.
    private static int knownAnnotationKinds(@NotNull String name) {
        return switch (name) {
            case "tool", "icon", "static_unload" -> ANN_SCRIPT;
            case "abstract" -> ANN_SCRIPT | ANN_CLASS | ANN_FUNCTION;
            case "onready",
                 "export",
                 "export_enum",
                 "export_file",
                 "export_file_path",
                 "export_dir",
                 "export_global_file",
                 "export_global_dir",
                 "export_multiline",
                 "export_placeholder",
                 "export_range",
                 "export_exp_easing",
                 "export_color_no_alpha",
                 "export_node_path",
                 "export_flags",
                 "export_flags_2d_render",
                 "export_flags_2d_physics",
                 "export_flags_2d_navigation",
                 "export_flags_3d_render",
                 "export_flags_3d_physics",
                 "export_flags_3d_navigation",
                 "export_flags_avoidance",
                 "export_storage",
                 "export_custom",
                 "export_tool_button" -> ANN_VARIABLE;
            case "rpc" -> ANN_FUNCTION;
            case "export_category", "export_group", "export_subgroup" -> ANN_STANDALONE;
            case "warning_ignore" -> ANN_CLASS_LEVEL | ANN_STATEMENT;
            case "warning_ignore_start", "warning_ignore_restore" ->
                    ANN_STANDALONE | ANN_CLASS_LEVEL | ANN_STATEMENT;
            default -> -1;
        };
    }

    private static boolean isWarningIgnoreRegionDirective(@NotNull String name) {
        return "warning_ignore_start".equals(name) || "warning_ignore_restore".equals(name);
    }

    private void emitInvalidAnnotationLevel(@NotNull ParsedAnnotation parsed, boolean scriptOnlyHint) {
        var message = scriptOnlyHint
                ? "Annotation \"@%s\" must be at the top of the script, before \"extends\" and \"class_name\"."
                .formatted(parsed.statement().name())
                : "Annotation \"@%s\" is not allowed in this level.".formatted(parsed.statement().name());
        recoveryTracker.emitError(
                message,
                parsed.statement().range(),
                DiagnosticCodes.ANNOTATION_INVALID_LEVEL,
                RecoveryTransactionTracker.AttributionPolicy.TARGET_FRAME,
                parsed.targetFrame()
        );
    }

    private void validateScriptOnlyAnnotation(@NotNull ParsedAnnotation parsed) {
        var ann = parsed.statement();
        var args = ann.arguments();
        var hasBrokenArg = args.stream().anyMatch(ErrorExpression.class::isInstance);
        switch (ann.name()) {
            case "tool" -> {
                if (!hasBrokenArg && !args.isEmpty()) {
                    recoveryTracker.emitError(
                            "Annotation \"@tool\" requires at most 0 arguments, but %d were given."
                                    .formatted(args.size()),
                            ann.range(),
                            DiagnosticCodes.SYNTAX_UNEXPECTED_TOKEN,
                            RecoveryTransactionTracker.AttributionPolicy.TARGET_FRAME,
                            parsed.targetFrame()
                    );
                }
                if (scriptToolSeen) {
                    recoveryTracker.emitError(
                            "\"@tool\" annotation can only be used once.",
                            ann.range(),
                            DiagnosticCodes.SYNTAX_UNEXPECTED_TOKEN,
                            RecoveryTransactionTracker.AttributionPolicy.TARGET_FRAME,
                            parsed.targetFrame()
                    );
                } else {
                    scriptToolSeen = true;
                }
            }
            case "static_unload" -> {
                if (!hasBrokenArg && !args.isEmpty()) {
                    recoveryTracker.emitError(
                            "Annotation \"@static_unload\" requires at most 0 arguments, but %d were given."
                                    .formatted(args.size()),
                            ann.range(),
                            DiagnosticCodes.SYNTAX_UNEXPECTED_TOKEN,
                            RecoveryTransactionTracker.AttributionPolicy.TARGET_FRAME,
                            parsed.targetFrame()
                    );
                }
                if (scriptStaticUnloadSeen) {
                    recoveryTracker.emitError(
                            "\"@static_unload\" annotation can only be used once per script.",
                            ann.range(),
                            DiagnosticCodes.SYNTAX_UNEXPECTED_TOKEN,
                            RecoveryTransactionTracker.AttributionPolicy.TARGET_FRAME,
                            parsed.targetFrame()
                    );
                } else {
                    scriptStaticUnloadSeen = true;
                }
            }
            case "icon" -> {
                if (!hasBrokenArg && args.size() != 1) {
                    var msg = args.isEmpty()
                            ? "Annotation \"@icon\" requires at least 1 arguments, but 0 were given."
                            : "Annotation \"@icon\" requires at most 1 arguments, but %d were given."
                            .formatted(args.size());
                    recoveryTracker.emitError(
                            msg,
                            ann.range(),
                            DiagnosticCodes.SYNTAX_UNEXPECTED_TOKEN,
                            RecoveryTransactionTracker.AttributionPolicy.TARGET_FRAME,
                            parsed.targetFrame()
                    );
                } else if (!hasBrokenArg) {
                    var iconPath = unquotedStringLiteral(args.getFirst());
                    if (iconPath == null) {
                        recoveryTracker.emitError(
                                "Argument 1 of annotation \"@icon\" must be a string literal.",
                                args.getFirst().range(),
                                DiagnosticCodes.SYNTAX_UNEXPECTED_TOKEN,
                                RecoveryTransactionTracker.AttributionPolicy.TARGET_FRAME,
                                parsed.targetFrame()
                        );
                    } else if (iconPath.isEmpty()) {
                        recoveryTracker.emitError(
                                "\"@icon\" annotation argument must contain the path to the icon.",
                                args.getFirst().range(),
                                DiagnosticCodes.SYNTAX_UNEXPECTED_TOKEN,
                                RecoveryTransactionTracker.AttributionPolicy.TARGET_FRAME,
                                parsed.targetFrame()
                        );
                    }
                }
                if (scriptIconSeen) {
                    recoveryTracker.emitError(
                            "\"@icon\" annotation can only be used once.",
                            ann.range(),
                            DiagnosticCodes.SYNTAX_UNEXPECTED_TOKEN,
                            RecoveryTransactionTracker.AttributionPolicy.TARGET_FRAME,
                            parsed.targetFrame()
                    );
                } else {
                    scriptIconSeen = true;
                }
            }
            default -> {
            }
        }
    }

    private boolean validateStandaloneDirective(@NotNull ParsedAnnotation parsed) {
        var ann = parsed.statement();
        var args = ann.arguments();
        var valid = true;
        if (!parsed.hadNewlineAfter() && !isAtEnd() && !check(GdTokenType.DEDENT) && !isVirtualLambdaBoundary()) {
            recoveryTracker.emitError(
                    "Expected newline after a standalone annotation.",
                    ann.range(),
                    DiagnosticCodes.SYNTAX_UNEXPECTED_TOKEN,
                    RecoveryTransactionTracker.AttributionPolicy.TARGET_FRAME,
                    parsed.targetFrame()
            );
            valid = false;
        }
        var hasBrokenArg = args.stream().anyMatch(ErrorExpression.class::isInstance);
        if (hasBrokenArg) {
            return false;
        }
        switch (ann.name()) {
            case "export_category" -> {
                if (args.size() != 1) {
                    var msg = args.isEmpty()
                            ? "Annotation \"@export_category\" requires at least 1 arguments, but 0 were given."
                            : "Annotation \"@export_category\" requires at most 1 arguments, but %d were given."
                            .formatted(args.size());
                    recoveryTracker.emitError(
                            msg,
                            ann.range(),
                            DiagnosticCodes.SYNTAX_UNEXPECTED_TOKEN,
                            RecoveryTransactionTracker.AttributionPolicy.TARGET_FRAME,
                            parsed.targetFrame()
                    );
                    valid = false;
                }
            }
            case "export_group", "export_subgroup" -> {
                if (args.isEmpty() || args.size() > 2) {
                    var msg = args.isEmpty()
                            ? "Annotation \"@%s\" requires at least 1 arguments, but 0 were given."
                            .formatted(ann.name())
                            : "Annotation \"@%s\" requires at most 2 arguments, but %d were given."
                            .formatted(ann.name(), args.size());
                    recoveryTracker.emitError(
                            msg,
                            ann.range(),
                            DiagnosticCodes.SYNTAX_UNEXPECTED_TOKEN,
                            RecoveryTransactionTracker.AttributionPolicy.TARGET_FRAME,
                            parsed.targetFrame()
                    );
                    valid = false;
                }
            }
            case "warning_ignore_start", "warning_ignore_restore" -> {
                if (args.isEmpty()) {
                    recoveryTracker.emitError(
                            "Annotation \"@%s\" requires at least 1 arguments, but 0 were given."
                                    .formatted(ann.name()),
                            ann.range(),
                            DiagnosticCodes.SYNTAX_UNEXPECTED_TOKEN,
                            RecoveryTransactionTracker.AttributionPolicy.TARGET_FRAME,
                            parsed.targetFrame()
                    );
                    return false;
                }
                var isStart = "warning_ignore_start".equals(ann.name());
                var line = ann.range().startPoint().row() + 1;
                for (var i = 0; i < args.size(); i++) {
                    var arg = args.get(i);
                    var warningName = unquotedStringLiteral(arg);
                    if (warningName == null) {
                        recoveryTracker.emitError(
                                "Argument %d of annotation \"@%s\" must be a string literal."
                                        .formatted(i + 1, ann.name()),
                                arg.range(),
                                DiagnosticCodes.SYNTAX_UNEXPECTED_TOKEN,
                                RecoveryTransactionTracker.AttributionPolicy.TARGET_FRAME,
                                parsed.targetFrame()
                        );
                        valid = false;
                        continue;
                    }
                    var key = warningName.toUpperCase(Locale.ROOT);
                    if (isStart) {
                        var existingLine = activeWarningIgnoreStarts.get(key);
                        if (existingLine != null) {
                            recoveryTracker.emitError(
                                    "Warning \"%s\" is already being ignored by \"@warning_ignore_start\" at line %d."
                                            .formatted(key, existingLine),
                                    ann.range(),
                                    DiagnosticCodes.SYNTAX_UNEXPECTED_TOKEN,
                                    RecoveryTransactionTracker.AttributionPolicy.TARGET_FRAME,
                                    parsed.targetFrame()
                            );
                            valid = false;
                        } else {
                            activeWarningIgnoreStarts.put(key, line);
                        }
                    } else {
                        if (activeWarningIgnoreStarts.remove(key) == null) {
                            recoveryTracker.emitError(
                                    "Warning \"%s\" is not being ignored by \"@warning_ignore_start\"."
                                            .formatted(key),
                                    ann.range(),
                                    DiagnosticCodes.SYNTAX_UNEXPECTED_TOKEN,
                                    RecoveryTransactionTracker.AttributionPolicy.TARGET_FRAME,
                                    parsed.targetFrame()
                            );
                            valid = false;
                        }
                    }
                }
            }
            default -> {
            }
        }
        return valid;
    }

    private static @Nullable String unquotedStringLiteral(@NotNull Expression expression) {
        if (!(expression instanceof LiteralExpression(var kind, var sourceText, _))
                || !"string".equals(kind)) {
            return null;
        }
        var isRaw = false;
        var start = 0;
        if (!sourceText.isEmpty() && (sourceText.charAt(0) == 'r' || sourceText.charAt(0) == 'R')) {
            isRaw = true;
            start = 1;
        }
        var len = sourceText.length();
        if (len - start >= 6) {
            var quote3 = sourceText.substring(start, start + 3);
            if (("\"\"\"".equals(quote3) || "'''".equals(quote3)) && sourceText.endsWith(quote3)) {
                var body = sourceText.substring(start + 3, len - 3);
                return isRaw ? body : decodeGdStringEscapes(body);
            }
        }
        if (len - start >= 2) {
            var quote = sourceText.charAt(start);
            if ((quote == '"' || quote == '\'') && sourceText.charAt(len - 1) == quote) {
                var body = sourceText.substring(start + 1, len - 1);
                return isRaw ? body : decodeGdStringEscapes(body);
            }
        }
        return null;
    }

    private static @NotNull String decodeGdStringEscapes(@NotNull String body) {
        if (body.indexOf('\\') < 0) {
            return body;
        }
        var sb = new StringBuilder(body.length());
        var prevSurrogate = 0;
        for (var i = 0; i < body.length(); ) {
            var ch = body.codePointAt(i);
            i += Character.charCount(ch);
            if (ch != '\\') {
                prevSurrogate = 0;
                sb.appendCodePoint(ch);
                continue;
            }
            if (i >= body.length()) {
                prevSurrogate = 0;
                break;
            }
            var code = body.codePointAt(i);
            i += Character.charCount(code);
            var escaped = 0;
            var validEscape = true;
            switch (code) {
                case 'a' -> escaped = 0x07;
                case 'b' -> escaped = '\b';
                case 'f' -> escaped = '\f';
                case 'n' -> escaped = '\n';
                case 'r' -> escaped = '\r';
                case 't' -> escaped = '\t';
                case 'v' -> escaped = 0x0B;
                case '\'' -> escaped = '\'';
                case '\"' -> escaped = '\"';
                case '\\' -> escaped = '\\';
                case 'u', 'U' -> {
                    var hexLen = (code == 'U') ? 6 : 4;
                    for (var j = 0; j < hexLen; j++) {
                        if (i >= body.length()) {
                            validEscape = false;
                            break;
                        }
                        var digit = body.codePointAt(i);
                        var hexVal = asciiHexValue(digit);
                        if (hexVal < 0) {
                            validEscape = false;
                            break;
                        }
                        escaped = (escaped << 4) | hexVal;
                        i += Character.charCount(digit);
                    }
                }
                case '\r' -> {
                    if (i < body.length() && body.charAt(i) == '\n') {
                        i++;
                        validEscape = false;
                    } else if (i < body.length()) {
                        var nextCp = body.codePointAt(i);
                        prevSurrogate = 0;
                        sb.append('\\').appendCodePoint(nextCp);
                        i += Character.charCount(nextCp);
                        validEscape = false;
                    } else {
                        validEscape = false;
                    }
                }
                case '\n' -> validEscape = false;
                default -> validEscape = false;
            }
            if (!validEscape) {
                prevSurrogate = 0;
                continue;
            }
            if ((escaped & 0xFFFFFC00) == 0xD800) {
                if (prevSurrogate == 0) {
                    prevSurrogate = escaped;
                    continue;
                }
                prevSurrogate = 0;
                continue;
            }
            if ((escaped & 0xFFFFFC00) == 0xDC00) {
                if (prevSurrogate == 0) {
                    continue;
                }
                escaped = ((prevSurrogate - 0xD800) << 10) + (escaped - 0xDC00) + 0x10000;
                prevSurrogate = 0;
            } else if (prevSurrogate != 0) {
                prevSurrogate = 0;
            }
            if (Character.isValidCodePoint(escaped)) {
                sb.appendCodePoint(escaped);
            }
        }
        return sb.toString();
    }

    private static int asciiHexValue(int codePoint) {
        if (codePoint >= '0' && codePoint <= '9') {
            return codePoint - '0';
        }
        if (codePoint >= 'a' && codePoint <= 'f') {
            return codePoint - 'a' + 10;
        }
        if (codePoint >= 'A' && codePoint <= 'F') {
            return codePoint - 'A' + 10;
        }
        return -1;
    }

    private void validatePendingAnnotationsForTarget(int targetKindMask, @NotNull String targetKindLabel) {
        var sawAbstract = false;
        for (var i = 0; i < pendingAnnotations.size(); i++) {
            var pending = pendingAnnotations.get(i);
            if (pending.alreadyDiagnosed()) {
                continue;
            }
            var ann = pending.statement();
            var kinds = knownAnnotationKinds(ann.name());
            if (kinds != -1 && (kinds & targetKindMask) == 0) {
                recoveryTracker.emitError(
                        "Annotation \"@%s\" cannot be applied to a %s.".formatted(ann.name(), targetKindLabel),
                        ann.range(),
                        DiagnosticCodes.ANNOTATION_INVALID_TARGET,
                        RecoveryTransactionTracker.AttributionPolicy.TARGET_FRAME,
                        pending.targetFrame()
                );
                pendingAnnotations.set(i, new PendingAnnotation(
                        ann, pending.targetFrame(), pending.scriptRootEligible(), true,
                        pending.recordPropertyRecovery()));
                continue;
            }
            if ("abstract".equals(ann.name())) {
                if (sawAbstract) {
                    recoveryTracker.emitError(
                            "\"@abstract\" annotation can only be used once per %s.".formatted(targetKindLabel),
                            ann.range(),
                            DiagnosticCodes.SYNTAX_UNEXPECTED_TOKEN,
                            RecoveryTransactionTracker.AttributionPolicy.TARGET_FRAME,
                            pending.targetFrame()
                    );
                    pendingAnnotations.set(i, new PendingAnnotation(
                            ann, pending.targetFrame(), pending.scriptRootEligible(), true,
                            pending.recordPropertyRecovery()));
                } else {
                    sawAbstract = true;
                }
            }
        }
    }

    private @NotNull List<Statement> drainAnnotationsAndCommentsAsDangling(
            int limitByte,
            boolean allowScriptRoot
    ) {
        var drained = new ArrayList<Statement>();
        while (true) {
            skipGroupingInternalComments();
            var hasAnnotation = !pendingAnnotations.isEmpty();
            var hasComment = commentCursor < comments.size()
                    && comments.get(commentCursor).startByte() < limitByte;
            if (!hasAnnotation && !hasComment) {
                break;
            }
            var annotationStart = hasAnnotation
                    ? pendingAnnotations.getFirst().statement().range().startByte()
                    : Integer.MAX_VALUE;
            if (hasComment && comments.get(commentCursor).startByte() < annotationStart) {
                drained.add(materializeComment(comments.get(commentCursor++)));
            } else {
                var pending = pendingAnnotations.removeFirst();
                drained.add(materializePendingAnnotation(pending, true, allowScriptRoot));
            }
        }
        return drained;
    }

    private @NotNull AnnotationStatement materializePendingAnnotation(
            @NotNull PendingAnnotation pending,
            boolean dangling,
            boolean allowScriptRoot
    ) {
        var ann = pending.statement();
        if (allowScriptRoot && pending.scriptRootEligible()) {
            if (!pending.alreadyDiagnosed() && "abstract".equals(ann.name())) {
                if (scriptAbstractSeen) {
                    recoveryTracker.emitError(
                            "\"@abstract\" annotation can only be used once per class.",
                            ann.range(),
                            DiagnosticCodes.SYNTAX_UNEXPECTED_TOKEN,
                            RecoveryTransactionTracker.AttributionPolicy.TARGET_FRAME,
                            pending.targetFrame()
                    );
                } else {
                    scriptAbstractSeen = true;
                }
            }
            bindRecoveryOwner(pending.targetFrame(), ann);
            return ann;
        }
        if (dangling && !pending.alreadyDiagnosed()) {
            recoveryTracker.emitError(
                    "Annotation \"@%s\" does not precede a valid target, so it will have no effect."
                            .formatted(ann.name()),
                    ann.range(),
                    DiagnosticCodes.ANNOTATION_DANGLING,
                    RecoveryTransactionTracker.AttributionPolicy.TARGET_FRAME,
                    pending.targetFrame()
            );
        }
        if (isolatedFallbackFrame != null) {
            // Keep targetFrame uncommitted inside isolated property accessor suites so dangling /
            // invalid-level diagnostics fall back onto the owning VariableDeclaration's
            // `variable.property` facet while retaining the recovery child on the variable.
            if ((dangling || pending.recordPropertyRecovery()) && activePropertyRecoveryAnnotations != null) {
                activePropertyRecoveryAnnotations.add(ann);
            }
            return ann;
        }
        bindRecoveryOwner(pending.targetFrame(), ann);
        return ann;
    }

    /// A type's public text and range, plus the internal shape needed to reject nested collections.
    private record ParsedType(@NotNull TypeRef ref, boolean isContainer) {
    }

    /// Parses dotted names, typed collections and (when allowed) `void`. Types inherit the
    /// caller's multiline mode; their brackets never open one. The outcome distinguishes an
    /// untouched slot from an invalid type and a type with a usable consumed prefix.
    private @NotNull ParseOutcome<ParsedType> parseTypeInternal(boolean allowVoid) {
        // Container types recurse (`Array[...]`); the rejection of *nested* containers happens
        // only after the recursive call returns, so the recursion itself needs the guard.
        if (nestingBudgetExceeded || !enterNestingLevel()) {
            return ParseOutcome.failed();
        }
        try {
            return parseTypeInternalImpl(allowVoid);
        } finally {
            exitNestingLevel();
        }
    }

    private boolean shouldContinueDictionaryTypeComma(@NotNull StructuralFrame typeBracketFrame) {
        if (!check(GdTokenType.COMMA)) {
            return false;
        }
        if (!hasEnclosingCommaOwner(typeBracketFrame)) {
            return true;
        }
        var fact = bracketOracle().factFor(typeBracketFrame.openerStartByte);
        return fact != null
                && fact.status() == BracketBalanceOracle.OpenerStatus.MATCHED
                && fact.pairedCloserByte() > current.startByte();
    }

    /// Parses a type at an already-entered nesting level, settling its bracket frame before
    /// propagating failure. A closed collection can keep damage from a recoverable element.
    private @NotNull ParseOutcome<ParsedType> parseTypeInternalImpl(boolean allowVoid) {
        var startToken = current;
        var typeStart = current.startByte();
        if (!check(GdTokenType.IDENTIFIER) && !check(GdTokenType.VOID)) {
            // The "expected type" message is left to the caller, which knows the context.
            return ParseOutcome.missing();
        }
        var complete = !hasLexicalDamageOnAdvance(true);
        advance();
        if (startToken.type() == GdTokenType.VOID) {
            if (allowVoid) {
                return ParseOutcome.structured(new ParsedType(new TypeRef("void", tokenRange(startToken)), false),
                        complete, false);
            }
            emitInCurrentFrameOrLegacy("\"void\" is only allowed for a function return type.",
                    DiagnosticCodes.SYNTAX_UNEXPECTED_TOKEN);
            return ParseOutcome.failed();
        }

        if (check(GdTokenType.BRACKET_OPEN)) {
            complete &= !hasLexicalDamageOnAdvance(true);
            advance();
            var typeBracketFrame = pushBracketedFrame(
                    StructuralRole.TYPE_BRACKET,
                    GdTokenType.BRACKET_OPEN,
                    GdTokenType.BRACKET_CLOSE,
                    previous.startByte(),
                    false,
                    false,
                    "collection type",
                    "Expected closing \"]\" after collection type.",
                    DiagnosticCodes.MISSING_CLOSER_TYPE_BRACKET,
                    DiagnosticCodes.MISMATCHED_CLOSER_TYPE_BRACKET
            );
            typeBracketFrame.allowsTypeComma = "Dictionary".equals(startToken.source());
            // Typed collection (like Array[int], Dictionary[String, int]).
            var failed = false;
            var hasChildDamage = false;
            var argCount = 0;
            do {
                typeBracketFrame.separatorPhase = SeparatorPhase.EXPECT_ELEMENT;
                var element = parseTypeInternal(false);
                switch (element) {
                    case ParseOutcome.Missing<ParsedType> _ -> {
                        if (!typeBracketFrame.settled) {
                            emitInCurrentFrameOrLegacy(
                                    "Expected type for collection after \"%s\".".formatted(argCount == 0 ? "[" : ","),
                                    DiagnosticCodes.MISSING_TYPE_GENERIC_ARGUMENT
                            );
                        }
                        failed = true;
                    }
                    case ParseOutcome.Failed<ParsedType> _ -> failed = true;
                    case ParseOutcome.Ok<ParsedType> _, ParseOutcome.EnclosedDamage<ParsedType> _,
                         ParseOutcome.Damaged<ParsedType> _ -> {
                        hasChildDamage |= !(element instanceof ParseOutcome.Ok<ParsedType>);
                        if (element.valueOrThrow().isContainer()) {
                            emitInCurrentFrameOrLegacy("Nested typed collections are not supported.",
                                    DiagnosticCodes.SYNTAX_UNEXPECTED_TOKEN);
                            hasChildDamage = true;
                        }
                    }
                }
                if (failed) {
                    break;
                }
                argCount++;
                if (typeBracketFrame.settled || nestingBudgetExceeded
                        || !typeBracketFrame.allowsTypeComma || argCount >= 2) {
                    typeBracketFrame.separatorPhase = SeparatorPhase.NONE;
                    break;
                }
                typeBracketFrame.separatorPhase = SeparatorPhase.EXPECT_SEPARATOR_OR_CLOSER;
                if (!shouldContinueDictionaryTypeComma(typeBracketFrame)) {
                    break;
                }
                hasChildDamage |= hasLexicalDamageOnAdvance(true);
                advance();
            } while (true);
            typeBracketFrame.separatorPhase = SeparatorPhase.NONE;
            hasChildDamage |= hasLexicalDamageOnExactCloser(typeBracketFrame);
            var closed = consumeCloser(typeBracketFrame);
            if (failed) {
                return ParseOutcome.failed();
            }
            var typeEnd = previous.endByte();
            return ParseOutcome.structured(
                    new ParsedType(new TypeRef(slice(typeStart, typeEnd), range(typeStart, typeEnd)), true),
                    complete && closed, hasChildDamage);
        }

        while (check(GdTokenType.PERIOD)) {
            complete &= !hasLexicalDamageOnAdvance(true);
            advance();
            if (check(GdTokenType.IDENTIFIER)) {
                complete &= !hasLexicalDamageOnAdvance(true);
            }
            complete &= consume(GdTokenType.IDENTIFIER, "Expected inner type name after \".\".",
                    DiagnosticCodes.SYNTAX_UNEXPECTED_TOKEN);
        }
        // The dot belongs to the type even when there is no inner name to consume.
        var typeEnd = previous.endByte();
        return ParseOutcome.structured(
                new ParsedType(new TypeRef(slice(typeStart, typeEnd), range(typeStart, typeEnd)), false),
                complete, false);
    }

    /// Keeps a declaration or iterator's parsed type fragment. Only a truly untouched type slot
    /// needs a context diagnostic; an invalid type with no usable fragment remains absent.
    private @Nullable TypeRef absorbType(@NotNull ParseOutcome<ParsedType> outcome, @NotNull GdToken introducer,
                                        @NotNull String message, @NotNull String code) {
        return switch (outcome) {
            case ParseOutcome.Ok<ParsedType> _, ParseOutcome.EnclosedDamage<ParsedType> _,
                 ParseOutcome.Damaged<ParsedType> _ -> outcome.valueOrThrow().ref();
            case ParseOutcome.Missing<ParsedType> _ -> {
                recoveryTracker.emitError(message, tokenRange(introducer), code,
                        RecoveryTransactionTracker.AttributionPolicy.CURRENT_FRAME, null);
                yield null;
            }
            case ParseOutcome.Failed<ParsedType> _ -> null;
        };
    }

    /// Builds the inferred-type marker (`:=`, or `: =` preserving inner whitespace) as a non-null
    /// `TypeRef`, matching the reference path's `inferred_type` handling. The trailing `=` token
    /// is not consumed; the initializer's `match(EQUAL)` picks it up.
    private @NotNull TypeRef inferredTypeRef(GdToken colonToken) {
        var start = colonToken.startByte();
        var end = current.endByte(); // The EQUAL token, not yet consumed.
        return new TypeRef(slice(start, end).strip(), range(start, end));
    }

    // ---------------------------------------------------------------------------------------------
    // Declarations — port of parse_variable / parse_constant / parse_signal / parse_enum /
    // parse_function (+ signature).
    // ---------------------------------------------------------------------------------------------

    /// Port of `GDScriptParser::parse_variable`. When `allowProperty` is set (class members), a
    /// get/set property tail is consumed but, per the AST contract, produces no nodes.
    private @Nullable VariableDeclaration parseVariable(boolean isStatic, boolean allowProperty) {
        var start = statementStartByte;
        String name;
        RecoveryTransactionTracker.ProvisionalFrame nameFrame = null;
        if (match(GdTokenType.IDENTIFIER)) {
            name = previous.source();
        } else if (check(GdTokenType.COLON) || check(GdTokenType.EQUAL)) {
            pushRecoveryOwner("declaration.name");
            recoveryTracker.emitError(
                    "Expected variable name after \"var\".",
                    tokenRange(previous),
                    DiagnosticCodes.DECLARATION_MISSING_NAME,
                    RecoveryTransactionTracker.AttributionPolicy.CURRENT_FRAME,
                    null
            );
            nameFrame = popRecoveryOwner();
            name = "";
        } else {
            emitInCurrentFrameOrLegacy("Expected variable name after \"var\".",
                    DiagnosticCodes.SYNTAX_UNEXPECTED_TOKEN);
            return null;
        }

        TypeRef type = null;
        RecoveryTransactionTracker.ProvisionalFrame typeFrame = null;
        Expression value = null;
        RecoveryTransactionTracker.ProvisionalFrame valueFrame = null;
        if (match(GdTokenType.COLON)) {
            var colonToken = previous;
            if (check(GdTokenType.NEWLINE)) {
                if (allowProperty) {
                    advance();
                    pushRecoveryOwner("variable.property");
                    var propResult = parseProperty(true);
                    var propertyFrame = popRecoveryOwner();
                    var variable = new VariableDeclaration(DeclarationKind.VAR, name, null, null, isStatic,
                            "variable_statement", range(start, propResult.endByte()),
                            propResult.recoveryAnnotations());
                    if (nameFrame != null) {
                        bindRecoveryOwner(nameFrame, variable);
                    }
                    bindRecoveryOwner(propertyFrame, variable);
                    if (propResult.trailingFrame() != null) {
                        bindRecoveryOwner(propResult.trailingFrame(), variable);
                    }
                    return variable;
                }
                pushRecoveryOwner("variable.type");
                recoveryTracker.emitError(
                        "Expected type after \":\"",
                        tokenRange(previous),
                        DiagnosticCodes.MISSING_TYPE_VARIABLE,
                        RecoveryTransactionTracker.AttributionPolicy.CURRENT_FRAME,
                        null
                );
                typeFrame = popRecoveryOwner();
            } else if (check(GdTokenType.EQUAL)) {
                // Infer-type marker; the `=` itself belongs to the initializer below.
                type = inferredTypeRef(colonToken);
            } else {
                if (allowProperty && check(GdTokenType.IDENTIFIER)
                        && (current.source().equals("get") || current.source().equals("set"))) {
                    pushRecoveryOwner("variable.property");
                    var propResult = parseProperty(false);
                    var propertyFrame = popRecoveryOwner();
                    var variable = new VariableDeclaration(DeclarationKind.VAR, name, null, null, isStatic,
                            "variable_statement", range(start, propResult.endByte()),
                            propResult.recoveryAnnotations());
                    if (nameFrame != null) {
                        bindRecoveryOwner(nameFrame, variable);
                    }
                    bindRecoveryOwner(propertyFrame, variable);
                    if (propResult.trailingFrame() != null) {
                        bindRecoveryOwner(propResult.trailingFrame(), variable);
                    }
                    return variable;
                }
                pushRecoveryOwner("variable.type");
                type = absorbType(parseTypeInternal(false), colonToken,
                        "Expected type after \":\"", DiagnosticCodes.MISSING_TYPE_VARIABLE);
                typeFrame = popRecoveryOwner();
            }
        }

        if (match(GdTokenType.EQUAL)) {
            var valueStart = current.startByte();
            pushRecoveryOwner("variable.initializer");
            var valueOutcome = parseExpression(false);
            value = absorbSlot(valueOutcome, valueStart, "variable.initializer",
                    "Expected expression for variable initial value after \"=\".");
            valueFrame = popRecoveryOwner();
        }

        if (allowProperty && match(GdTokenType.COLON)) {
            pushRecoveryOwner("variable.property");
            var propResult = match(GdTokenType.NEWLINE)
                    ? parseProperty(true)
                    : parseProperty(false);
            var propertyFrame = popRecoveryOwner();
            var propEnd = Math.max(propResult.endByte(),
                    value != null ? value.range().endByte() : (type != null ? type.range().endByte() : start));
            for (var recAnn : propResult.recoveryAnnotations()) {
                propEnd = Math.max(propEnd, recAnn.range().endByte());
            }
            var withProperty = new VariableDeclaration(DeclarationKind.VAR, name, type, value, isStatic,
                    "variable_statement", range(start, propEnd),
                    propResult.recoveryAnnotations());
            if (nameFrame != null) {
                bindRecoveryOwner(nameFrame, withProperty);
            }
            if (typeFrame != null) {
                bindRecoveryOwner(typeFrame, withProperty);
            }
            if (valueFrame != null) {
                bindRecoveryOwner(valueFrame, withProperty);
            }
            bindRecoveryOwner(propertyFrame, withProperty);
            if (propResult.trailingFrame() != null) {
                bindRecoveryOwner(propResult.trailingFrame(), withProperty);
            }
            return withProperty;
        }

        var varEnd = Math.max(extentEnd(),
                value != null ? value.range().endByte() : (type != null ? type.range().endByte() : start));
        var variable = new VariableDeclaration(DeclarationKind.VAR, name, type, value, isStatic,
                "variable_statement", range(start, varEnd));
        if (nameFrame != null) {
            bindRecoveryOwner(nameFrame, variable);
        }
        if (typeFrame != null) {
            bindRecoveryOwner(typeFrame, variable);
        }
        if (valueFrame != null) {
            bindRecoveryOwner(valueFrame, variable);
        }
        finishStatement(variable, "variable declaration");
        return variable;
    }

    private record PropertyParseResult(int endByte,
                                       @Nullable RecoveryTransactionTracker.ProvisionalFrame trailingFrame,
                                       @NotNull List<AnnotationStatement> recoveryAnnotations) {
    }

    /// Port of `GDScriptParser::parse_property` (+ setter/getter helpers) extended to drain
    /// variable-length property blocks and single-line lists: each entry independently parses
    /// `= fn`, `get:`, `set(v):`, duplicate accessors, or unknown accessors without borrowing an
    /// outer `DEDENT` or calling global `synchronize()`. Returns the end byte of the last drained
    /// property part, captured before the trailing statement-end / DEDENT consumption.
    private @NotNull PropertyParseResult parseProperty(boolean needIndent) {
        if (needIndent
                && !consume(GdTokenType.INDENT, "Expected indented block for property after \":\".",
                        DiagnosticCodes.SYNTAX_UNEXPECTED_TOKEN)) {
            return new PropertyParseResult(previous.endByte(), null, List.of());
        }

        var previousRecoveryAnnotations = activePropertyRecoveryAnnotations;
        var collectedRecoveryAnnotations = new ArrayList<AnnotationStatement>();
        activePropertyRecoveryAnnotations = collectedRecoveryAnnotations;
        try {
            var firstIdent = check(GdTokenType.IDENTIFIER);
            var firstKeyword = firstIdent && (current.source().equals("get") || current.source().equals("set"));
            var firstLooksReference = firstIdent && peekAfterCurrent() == GdTokenType.EQUAL;
            if (!needIndent && firstKeyword && !firstLooksReference) {
                emitInCurrentFrameOrLegacy("Property with inline code must go to an indented block.",
                        DiagnosticCodes.PROPERTY_INVALID_ACCESSOR);
            }

            // Lock reference-vs-block style only on the first valid `get`/`set` accessor so leading
            // unknown entries (such as `oops = g` or `oops:`) never misclassify later valid accessors.
            Boolean lockedReferenceForm = null;
            var singleLineReferenceList = !needIndent && firstLooksReference;
            var getterUsed = false;
            var setterUsed = false;
            var end = previous.endByte();
            var firstItem = true;
            var prevConsumedComma = false;
            while (true) {
                if (!firstItem) {
                    if (!needIndent) {
                        if (singleLineReferenceList) {
                            if (!match(GdTokenType.COMMA)) {
                                break;
                            }
                            if (check(GdTokenType.NEWLINE)) {
                                // Leave the hard newline unconsumed for `finishStatementProvisional` so single-line
                                // reference lists never swallow the next member declaration.
                                emitInCurrentFrameOrLegacy(
                                        "Inline setter/getter setting cannot span across multiple lines (use \"\\\\\"\" if needed).",
                                        DiagnosticCodes.SYNTAX_UNEXPECTED_TOKEN);
                                break;
                            }
                        } else {
                            break;
                        }
                    } else {
                        var matchedComma = match(GdTokenType.COMMA);
                        var commaToken = matchedComma ? previous : null;
                        var hadSeparator = matchedComma || prevConsumedComma;
                        prevConsumedComma = false;
                        match(GdTokenType.NEWLINE);
                        if (check(GdTokenType.DEDENT) || isAtEnd()) {
                            if (matchedComma) {
                                recoveryTracker.emitError(
                                        "Expected \"get\" or \"set\" for property declaration.",
                                        tokenRange(commaToken),
                                        DiagnosticCodes.PROPERTY_INVALID_ACCESSOR,
                                        RecoveryTransactionTracker.AttributionPolicy.CURRENT_FRAME,
                                        null
                                );
                            }
                            break;
                        }
                        if (Boolean.TRUE.equals(lockedReferenceForm)
                                && !hadSeparator
                                && check(GdTokenType.IDENTIFIER)
                                && (current.source().equals("get") || current.source().equals("set"))
                                && peekAfterCurrent() == GdTokenType.EQUAL) {
                            var point = extentEnd();
                            recoveryTracker.emitError(
                                    "Expected \",\" between property accessors.",
                                    range(point, point),
                                    DiagnosticCodes.MISSING_SEPARATOR_COMMA,
                                    RecoveryTransactionTracker.AttributionPolicy.CURRENT_FRAME,
                                    null
                            );
                        }
                    }
                }
                firstItem = false;

                if (needIndent && (check(GdTokenType.DEDENT) || isAtEnd())) {
                    break;
                }
                if (!needIndent && isAtEnd()) {
                    break;
                }
                var iterationStartToken = current;
                if (!match(GdTokenType.IDENTIFIER)) {
                    recoveryTracker.emitError(
                            "Expected \"get\" or \"set\" for property declaration.",
                            tokenRange(current),
                            DiagnosticCodes.PROPERTY_INVALID_ACCESSOR,
                            RecoveryTransactionTracker.AttributionPolicy.CURRENT_FRAME,
                            null
                    );
                    var stopAtComma = needIndent || singleLineReferenceList || !firstKeyword;
                    end = Math.max(end, drainUnexpectedPropertyAccessor(needIndent, stopAtComma));
                    if (!needIndent) {
                        if (check(GdTokenType.COMMA) && (singleLineReferenceList || !firstKeyword)) {
                            singleLineReferenceList = true;
                            continue;
                        }
                        break;
                    }
                    if (current == iterationStartToken && !isAtEnd() && !check(GdTokenType.DEDENT)) {
                        advance();
                        end = Math.max(end, previous.endByte());
                        if (previous.type() == GdTokenType.COMMA) {
                            prevConsumedComma = true;
                        }
                    }
                    continue;
                }

                var functionName = previous.source();
                var itemIsReferenceForm = check(GdTokenType.EQUAL);
                if (functionName.equals("set")) {
                    if (setterUsed) {
                        recoveryTracker.emitError(
                                "Properties can only have one setter.",
                                tokenRange(previous),
                                DiagnosticCodes.PROPERTY_DUPLICATE_SETTER,
                                RecoveryTransactionTracker.AttributionPolicy.CURRENT_FRAME,
                                null
                        );
                    } else {
                        setterUsed = true;
                    }
                    if (Boolean.TRUE.equals(lockedReferenceForm) && !itemIsReferenceForm) {
                        emitInCurrentFrameOrLegacy("Expected \"=\" after \"set\"",
                                DiagnosticCodes.PROPERTY_INVALID_ACCESSOR);
                    }
                    var parseAsReference = lockedReferenceForm != null
                            ? (lockedReferenceForm && itemIsReferenceForm)
                            : itemIsReferenceForm;
                    var outcome = parsePropertyAccessor(true, parseAsReference);
                    end = Math.max(end, outcome.endByte());
                    if (lockedReferenceForm == null && outcome.completeHeader()) {
                        lockedReferenceForm = itemIsReferenceForm;
                    }
                } else if (functionName.equals("get")) {
                    if (getterUsed) {
                        recoveryTracker.emitError(
                                "Properties can only have one getter.",
                                tokenRange(previous),
                                DiagnosticCodes.PROPERTY_DUPLICATE_GETTER,
                                RecoveryTransactionTracker.AttributionPolicy.CURRENT_FRAME,
                                null
                        );
                    } else {
                        getterUsed = true;
                    }
                    if (Boolean.TRUE.equals(lockedReferenceForm) && !itemIsReferenceForm) {
                        emitInCurrentFrameOrLegacy("Expected \"=\" after \"get\"",
                                DiagnosticCodes.PROPERTY_INVALID_ACCESSOR);
                    }
                    var parseAsReference = lockedReferenceForm != null
                            ? (lockedReferenceForm && itemIsReferenceForm)
                            : itemIsReferenceForm;
                    var outcome = parsePropertyAccessor(false, parseAsReference);
                    end = Math.max(end, outcome.endByte());
                    if (lockedReferenceForm == null && outcome.completeHeader()) {
                        lockedReferenceForm = itemIsReferenceForm;
                    }
                } else {
                    recoveryTracker.emitError(
                            "Expected \"get\" or \"set\" for property declaration.",
                            tokenRange(previous),
                            DiagnosticCodes.PROPERTY_INVALID_ACCESSOR,
                            RecoveryTransactionTracker.AttributionPolicy.CURRENT_FRAME,
                            null
                    );
                    var stopAtComma = needIndent || singleLineReferenceList || !firstKeyword;
                    end = Math.max(end, drainUnexpectedPropertyAccessor(needIndent, stopAtComma));
                    if (!needIndent && check(GdTokenType.COMMA) && (singleLineReferenceList || !firstKeyword)) {
                        singleLineReferenceList = true;
                    }
                }

                if (current == iterationStartToken && !isAtEnd() && !(needIndent && check(GdTokenType.DEDENT))) {
                    advance();
                    end = Math.max(end, previous.endByte());
                    if (!needIndent) {
                        break;
                    }
                }
            }

            RecoveryTransactionTracker.ProvisionalFrame trailingFrame = null;
            if (!needIndent && singleLineReferenceList) {
                trailingFrame = finishStatementProvisional("property declaration");
            }
            if (needIndent) {
                consume(GdTokenType.DEDENT, "Expected end of indented block for property.",
                        DiagnosticCodes.SYNTAX_UNEXPECTED_TOKEN);
            }
            // Skip comments strictly inside the drained property extent so they do not leak into the
            // enclosing class body and violate range containment, while preserving outer comments.
            while (commentCursor < comments.size() && comments.get(commentCursor).startByte() < end) {
                commentCursor++;
            }
            collectedRecoveryAnnotations.sort(Comparator.comparingInt(ann -> ann.range().startByte()));
            return new PropertyParseResult(end, trailingFrame, List.copyOf(collectedRecoveryAnnotations));
        } finally {
            activePropertyRecoveryAnnotations = previousRecoveryAnnotations;
        }
    }

    /// Drains one unexpected property accessor entry locally: stays on the current logical line
    /// (breaking at `NEWLINE`/`DEDENT` once `localIndents == 0` regardless of unclosed brackets),
    /// always consumes at least a stalling `;`, and balances an unexpected nested suite via
    /// `parseIsolatedSuite` only when `NEWLINE + INDENT` immediately follows in block mode.
    private int drainUnexpectedPropertyAccessor(boolean allowNestedSuite, boolean stopAtComma) {
        var end = previous.endByte();
        var consumedAny = false;
        var localIndents = 0;
        var localParens = 0;
        var localBrackets = 0;
        var localBraces = 0;
        while (!isAtEnd()) {
            if (localIndents == 0 && (check(GdTokenType.NEWLINE) || check(GdTokenType.DEDENT))) {
                break;
            }
            if (localIndents == 0 && localParens == 0 && localBrackets == 0 && localBraces == 0) {
                if (check(GdTokenType.SEMICOLON) || (stopAtComma && check(GdTokenType.COMMA))) {
                    break;
                }
            }
            if (current.type() == GdTokenType.DEDENT && localIndents > 0) {
                localIndents--;
                advance();
                consumedAny = true;
                end = Math.max(end, previous.endByte());
                if (localIndents == 0) {
                    break;
                }
                continue;
            }
            switch (current.type()) {
                case INDENT -> localIndents++;
                case PARENTHESIS_OPEN -> localParens++;
                case PARENTHESIS_CLOSE -> {
                    if (localParens > 0) {
                        localParens--;
                    }
                }
                case BRACKET_OPEN -> localBrackets++;
                case BRACKET_CLOSE -> {
                    if (localBrackets > 0) {
                        localBrackets--;
                    }
                }
                case BRACE_OPEN -> localBraces++;
                case BRACE_CLOSE -> {
                    if (localBraces > 0) {
                        localBraces--;
                    }
                }
                default -> {
                }
            }
            advance();
            consumedAny = true;
            end = Math.max(end, previous.endByte());
        }
        if (!consumedAny && check(GdTokenType.SEMICOLON)) {
            advance();
            end = Math.max(end, previous.endByte());
        }
        if (allowNestedSuite && check(GdTokenType.NEWLINE)) {
            if (peekAfterCurrent() == GdTokenType.INDENT) {
                end = Math.max(end, parseIsolatedSuite("invalid property accessor"));
            } else {
                advance();
            }
        }
        return end;
    }

    private record PropertyAccessorOutcome(int endByte, boolean completeHeader) {
    }

    /// Port of `GDScriptParser::parse_property_setter` / `parse_property_getter`. Returns the end
    /// byte of the accessor (for the inline form: its suite's content end) and whether the header
    /// syntax was complete enough to lock the property's reference-vs-block style.
    private @NotNull PropertyAccessorOutcome parsePropertyAccessor(boolean setter, boolean referenceForm) {
        if (referenceForm) {
            var hasEqual = consume(GdTokenType.EQUAL,
                    setter ? "Expected \"=\" after \"set\"" : "Expected \"=\" after \"get\"",
                    DiagnosticCodes.PROPERTY_INVALID_ACCESSOR);
            var hasIdent = consume(GdTokenType.IDENTIFIER,
                    setter ? "Expected setter function name after \"=\"." : "Expected getter function name after \"=\".",
                    DiagnosticCodes.PROPERTY_INVALID_ACCESSOR);
            return new PropertyAccessorOutcome(previous.endByte(), hasEqual && hasIdent);
        }
        if (setter) {
            if (check(GdTokenType.EQUAL)) {
                emitInCurrentFrameOrLegacy("Expected \"(\" after \"set\".",
                        DiagnosticCodes.PROPERTY_INVALID_ACCESSOR);
                return new PropertyAccessorOutcome(drainUnexpectedPropertyAccessor(true, false), false);
            }
            StructuralFrame setterParamFrame = null;
            var hasOpen = match(GdTokenType.PARENTHESIS_OPEN);
            if (hasOpen) {
                setterParamFrame = pushBracketedFrame(
                        StructuralRole.PARAMETER_LIST,
                        GdTokenType.PARENTHESIS_OPEN,
                        GdTokenType.PARENTHESIS_CLOSE,
                        previous.startByte(),
                        false,
                        false,
                        "setter parameter",
                        "Expected \")\" after parameter name.",
                        DiagnosticCodes.MISSING_CLOSER_PARAMETER_LIST,
                        DiagnosticCodes.MISMATCHED_CLOSER_PARAMETER_LIST
                );
            } else {
                emitInCurrentFrameOrLegacy("Expected \"(\" after \"set\".",
                        DiagnosticCodes.PROPERTY_INVALID_ACCESSOR);
            }
            var hasParamName = consume(GdTokenType.IDENTIFIER, "Expected parameter name after \"(\".",
                    DiagnosticCodes.DECLARATION_MISSING_NAME);
            var hasClose = setterParamFrame != null && consumeCloser(setterParamFrame);
            var hasColon = consume(GdTokenType.COLON, "Expected \":\" after \")\".",
                    DiagnosticCodes.PROPERTY_INVALID_ACCESSOR);
            var suiteEnd = parseIsolatedSuite("setter declaration");
            return new PropertyAccessorOutcome(suiteEnd, hasOpen && hasParamName && hasClose && hasColon);
        }
        if (check(GdTokenType.EQUAL)) {
            emitInCurrentFrameOrLegacy("Expected \":\" or \"(\" after \"get\".",
                    DiagnosticCodes.PROPERTY_INVALID_ACCESSOR);
            return new PropertyAccessorOutcome(drainUnexpectedPropertyAccessor(true, false), false);
        }
        boolean completeHeader;
        if (match(GdTokenType.PARENTHESIS_OPEN)) {
            var getterParamFrame = pushBracketedFrame(
                    StructuralRole.PARAMETER_LIST,
                    GdTokenType.PARENTHESIS_OPEN,
                    GdTokenType.PARENTHESIS_CLOSE,
                    previous.startByte(),
                    false,
                    false,
                    "getter parameter list",
                    "Expected \")\" after \"get(\".",
                    DiagnosticCodes.MISSING_CLOSER_PARAMETER_LIST,
                    DiagnosticCodes.MISMATCHED_CLOSER_PARAMETER_LIST
            );
            var hasClose = consumeCloser(getterParamFrame);
            var hasColon = consume(GdTokenType.COLON, "Expected \":\" after \"get()\".",
                    DiagnosticCodes.PROPERTY_INVALID_ACCESSOR);
            completeHeader = hasClose && hasColon;
        } else {
            completeHeader = consume(GdTokenType.COLON, "Expected \":\" or \"(\" after \"get\".",
                    DiagnosticCodes.PROPERTY_INVALID_ACCESSOR);
        }
        return new PropertyAccessorOutcome(parseIsolatedSuite("getter declaration"), completeHeader);
    }

    /// Parses an accessor suite with the outer pending annotations saved aside and inner recovery
    /// frames anchored onto the enclosing property frame: the suite's statements are discarded,
    /// while unattached or invalid-level annotations are collected into
    /// `activePropertyRecoveryAnnotations` so the owning `VariableDeclaration` retains them without
    /// leaking them onto the next outer member.
    private int parseIsolatedSuite(String context) {
        var saved = new ArrayList<>(pendingAnnotations);
        var previousIsolatedFallback = isolatedFallbackFrame;
        if (isolatedFallbackFrame == null) {
            isolatedFallbackFrame = recoveryTracker.activeFrame();
        }
        pendingAnnotations.clear();
        var startIndex = activePropertyRecoveryAnnotations == null ? 0 : activePropertyRecoveryAnnotations.size();
        Block suite;
        try {
            suite = parseSuite(context);
        } finally {
            // Replace, not append: annotations left dangling inside the discarded accessor suite
            // are retained in `activePropertyRecoveryAnnotations` rather than leaking outward.
            pendingAnnotations.clear();
            pendingAnnotations.addAll(saved);
            isolatedFallbackFrame = previousIsolatedFallback;
        }
        var end = suite.range().endByte();
        if (activePropertyRecoveryAnnotations != null) {
            for (var i = startIndex; i < activePropertyRecoveryAnnotations.size(); i++) {
                end = Math.max(end, activePropertyRecoveryAnnotations.get(i).range().endByte());
            }
        }
        return end;
    }

    private record NamedType(@NotNull String name, @Nullable TypeRef type,
                             @Nullable RecoveryTransactionTracker.ProvisionalFrame typeFrame) {
    }

    /// Shared name-plus-optional-type prefix of constants and parameters. The name token is
    /// already consumed; a `:` immediately followed by `=` is an inferred type.
    private @NotNull NamedType parseNameAndType(
            @NotNull String typeFacet,
            @NotNull String missingTypeCode
    ) {
        return parseNameAndType(typeFacet, null, null, missingTypeCode);
    }

    /// Parses a parameter or signal type without letting its annotation steal a suite colon.
    private @NotNull NamedType parseNameAndType(
            @NotNull String typeFacet,
            @Nullable StructuralFrame enclosingParamList,
            RecoveryTransactionTracker.@Nullable ProvisionalFrame signatureFrame,
            @NotNull String missingTypeCode
    ) {
        var name = previous.source();
        TypeRef type = null;
        RecoveryTransactionTracker.ProvisionalFrame typeFrame = null;
        if (enclosingParamList == null || !shouldYieldColonToEnclosingSuite(enclosingParamList)) {
            if (match(GdTokenType.COLON)) {
                var colonToken = previous;
                if (check(GdTokenType.EQUAL)) {
                    type = inferredTypeRef(colonToken);
                } else {
                    pushParameterSlotOwner(typeFacet, signatureFrame);
                    type = absorbType(parseTypeInternal(false), colonToken, "Expected type after \":\".", missingTypeCode);
                    typeFrame = popRecoveryOwner();
                }
            }
        }
        return new NamedType(name, type, typeFrame);
    }

    /// Checks directly on `sourceBytes` (without mutating tokenizer/queue multiline state) whether
    /// a `:` right after a parameter name in an unclosed parameter list sits at the end of the
    /// header line and is followed by a strictly deeper indented suite line. O(1) line-layout checks
    /// run before any cached oracle/signature-continuation query so normal inline `a: int` types
    /// incur zero extra scanning.
    private boolean shouldYieldColonToEnclosingSuite(@NotNull StructuralFrame paramListFrame) {
        if (current.type() != GdTokenType.COLON) {
            return false;
        }
        var pos = current.endByte();
        while (pos < sourceBytes.length && (sourceBytes[pos] == ' ' || sourceBytes[pos] == '\t' || sourceBytes[pos] == '\r')) {
            pos++;
        }
        if (pos < sourceBytes.length && sourceBytes[pos] == '#') {
            while (pos < sourceBytes.length && sourceBytes[pos] != '\n') {
                pos++;
            }
        }
        if (pos >= sourceBytes.length || sourceBytes[pos] != '\n') {
            return false;
        }
        pos++;
        while (pos < sourceBytes.length) {
            while (pos < sourceBytes.length && (sourceBytes[pos] == ' ' || sourceBytes[pos] == '\t' || sourceBytes[pos] == '\r')) {
                pos++;
            }
            if (pos < sourceBytes.length && sourceBytes[pos] == '#') {
                while (pos < sourceBytes.length && sourceBytes[pos] != '\n') {
                    pos++;
                }
            }
            if (pos < sourceBytes.length && sourceBytes[pos] == '\n') {
                pos++;
                continue;
            }
            break;
        }
        if (pos >= sourceBytes.length) {
            return false;
        }
        var first = sourceBytes[pos];
        if (first == ')' || first == ']' || first == '}') {
            return false;
        }
        if (lineIndentOf(pos) <= paramListFrame.indentBaseline) {
            return false;
        }
        return isParameterListUnclosed(paramListFrame);
    }

    private boolean isParameterListUnclosed(@NotNull StructuralFrame paramListFrame) {
        if (paramListFrame.unclosedParamListCache >= 0) {
            return paramListFrame.unclosedParamListCache == 1;
        }
        var fact = bracketOracle().factFor(paramListFrame.openerStartByte);
        boolean unclosed;
        if (fact == null || fact.status() != BracketBalanceOracle.OpenerStatus.MATCHED) {
            unclosed = true;
        } else {
            unclosed = fact.pairedCloserByte() >= 0
                    && hasUnmatchedOuterParenFrame(paramListFrame)
                    && !isFollowedBySignatureContinuation(fact.pairedCloserByte() + 1);
        }
        paramListFrame.unclosedParamListCache = unclosed ? (byte) 1 : (byte) 0;
        return unclosed;
    }

    private boolean hasUnmatchedOuterParenFrame(@NotNull StructuralFrame innerParamList) {
        for (var frame : structuralStack) {
            if (frame != innerParamList && frame.expectedCloser == GdTokenType.PARENTHESIS_CLOSE) {
                var outerFact = bracketOracle().factFor(frame.openerStartByte);
                if (outerFact == null || outerFact.status() != BracketBalanceOracle.OpenerStatus.MATCHED) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isFollowedBySignatureContinuation(int pos) {
        var inLineContinuation = false;
        while (pos < sourceBytes.length) {
            signatureContinuationScanSteps++;
            var b = sourceBytes[pos];
            if (b == ' ' || b == '\t' || b == '\r') {
                pos++;
                continue;
            }
            if (b == '\\') {
                if (pos + 1 < sourceBytes.length && sourceBytes[pos + 1] == '\n') {
                    inLineContinuation = true;
                    signatureContinuationScanSteps++;
                    pos += 2;
                    continue;
                }
                if (pos + 2 < sourceBytes.length && sourceBytes[pos + 1] == '\r' && sourceBytes[pos + 2] == '\n') {
                    inLineContinuation = true;
                    signatureContinuationScanSteps += 2;
                    pos += 3;
                    continue;
                }
                break;
            }
            if (inLineContinuation && b == '\n') {
                pos++;
                continue;
            }
            if (inLineContinuation && b == '#') {
                pos++;
                while (pos < sourceBytes.length && sourceBytes[pos] != '\n') {
                    signatureContinuationScanSteps++;
                    pos++;
                }
                if (pos < sourceBytes.length && sourceBytes[pos] == '\n') {
                    pos++;
                }
                continue;
            }
            break;
        }
        if (pos >= sourceBytes.length) {
            return false;
        }
        return sourceBytes[pos] == ':'
                || (sourceBytes[pos] == '-' && pos + 1 < sourceBytes.length && sourceBytes[pos + 1] == '>');
    }

    /// Port of `GDScriptParser::parse_constant`. A missing `=` retains the constant declaration
    /// with a zero-width `MISSING` value slot (`SLOT_CONSTANT_VALUE`); a failed value keeps a
    /// marker and the statement survives, consuming the success-path `endStatement` tail.
    private @Nullable VariableDeclaration parseConstant() {
        var start = statementStartByte;
        if (!consume(GdTokenType.IDENTIFIER, "Expected constant name after \"const\".",
                DiagnosticCodes.SYNTAX_UNEXPECTED_TOKEN)) {
            return null;
        }
        var named = parseNameAndType("constant.type", DiagnosticCodes.MISSING_TYPE_CONSTANT);

        Expression value;
        RecoveryTransactionTracker.ProvisionalFrame valueFrame;
        if (match(GdTokenType.EQUAL)) {
            var valueStart = current.startByte();
            pushRecoveryOwner("constant.value");
            var valueOutcome = parseExpression(false);
            value = absorbSlot(valueOutcome, valueStart, "constant.value",
                    "Expected initializer expression for constant.");
            valueFrame = popRecoveryOwner();
        } else {
            var missingPos = extentEnd();
            pushRecoveryOwner("constant.value");
            recoveryTracker.emitError(
                    "Expected initializer after constant name.",
                    tokenRange(previous),
                    DiagnosticCodes.SLOT_CONSTANT_VALUE,
                    RecoveryTransactionTracker.AttributionPolicy.CURRENT_FRAME,
                    null
            );
            valueFrame = popRecoveryOwner();
            value = new ErrorExpression(CstIssueKind.MISSING, "constant.value", "",
                    range(missingPos, missingPos));
        }

        var constEnd = Math.max(extentEnd(), value.range().endByte());
        if (named.type() != null) {
            constEnd = Math.max(constEnd, named.type().range().endByte());
        }
        var constant = new VariableDeclaration(DeclarationKind.CONST, named.name(), named.type(), value, false,
                "const_statement", range(start, constEnd));
        if (named.typeFrame() != null) {
            bindRecoveryOwner(named.typeFrame(), constant);
        }
        bindRecoveryOwner(valueFrame, constant);
        finishStatement(constant, "constant declaration");
        return constant;
    }

    private @Nullable ParsedParameter parseParameter(
            @Nullable StructuralFrame enclosingParamList,
            @NotNull String missingTypeCode
    ) {
        // Capture the start locally: a lambda default reuses the shared field for its own
        // signature's parameters before this parameter's range is computed.
        var start = parameterStartByte;
        var signatureFrame = recoveryTracker.activeFrame();
        if (!consume(GdTokenType.IDENTIFIER, "Expected parameter name.",
                DiagnosticCodes.DECLARATION_MISSING_NAME)) {
            return null;
        }
        var named = parseNameAndType("parameter.type", enclosingParamList, signatureFrame, missingTypeCode);

        Expression defaultValue = null;
        RecoveryTransactionTracker.ProvisionalFrame defaultFrame = null;
        if (match(GdTokenType.EQUAL)) {
            var defaultStart = current.startByte();
            pushParameterSlotOwner("parameter.default", signatureFrame);
            var defaultOutcome = parseExpression(false);
            defaultValue = absorbSlot(defaultOutcome, defaultStart, "parameter.default",
                    "Expected expression for parameter default value after \"=\".");
            defaultFrame = popRecoveryOwner();
        }
        var paramEnd = extentEnd();
        if (named.type() != null) {
            paramEnd = Math.max(paramEnd, named.type().range().endByte());
        }
        if (defaultValue != null) {
            paramEnd = Math.max(paramEnd, defaultValue.range().endByte());
        }
        var parameter = new Parameter(named.name(), named.type(), defaultValue, false,
                range(start, paramEnd));
        return new ParsedParameter(parameter, named.typeFrame(), defaultFrame);
    }

    /// A parsed parameter plus the owner frames of its type and default value; see
    /// {@link #parseParameter} for the delayed-binding contract.
    private record ParsedParameter(@NotNull Parameter parameter,
                                   @Nullable RecoveryTransactionTracker.ProvisionalFrame typeFrame,
                                   @Nullable RecoveryTransactionTracker.ProvisionalFrame defaultFrame) {
    }

    /// Port of `GDScriptParser::parse_signal`, including the purely syntactic duplicate-name and
    /// no-default-value checks.
    private @Nullable SignalStatement parseSignal() {
        var start = statementStartByte;
        if (!consume(GdTokenType.IDENTIFIER, "Expected signal name after \"signal\".",
                DiagnosticCodes.SYNTAX_UNEXPECTED_TOKEN)) {
            return null;
        }
        var name = previous.source();

        var parameters = new ArrayList<Parameter>();
        var boundTypeFrames = new ArrayList<RecoveryTransactionTracker.ProvisionalFrame>();
        var boundTypeOwners = new ArrayList<Parameter>();
        var boundDefaultFrames = new ArrayList<RecoveryTransactionTracker.ProvisionalFrame>();
        var boundDefaultOwners = new ArrayList<Parameter>();
        RecoveryTransactionTracker.ProvisionalFrame signalParamFrame = null;
        if (check(GdTokenType.PARENTHESIS_OPEN)) {
            pushRecoveryOwner("signal.parameter");
            pushMultiline();
            advance();
            var paramListFrame = pushBracketedFrame(
                    StructuralRole.PARAMETER_LIST,
                    GdTokenType.PARENTHESIS_OPEN,
                    GdTokenType.PARENTHESIS_CLOSE,
                    previous.startByte(),
                    true,
                    true,
                    "signal parameters",
                    "Expected closing \")\" after signal parameters.",
                    DiagnosticCodes.MISSING_CLOSER_PARAMETER_LIST,
                    DiagnosticCodes.MISMATCHED_CLOSER_PARAMETER_LIST
            );
            var declaredNames = new HashSet<String>();
            do {
                if (paramListFrame.settled || nestingBudgetExceeded || check(GdTokenType.PARENTHESIS_CLOSE)) {
                    break;
                }
                if (isAtUnclosedMultilineStop() || isAtEnd() || check(GdTokenType.DEDENT)) {
                    claimInScopeMultilineLexicalErrorsWithoutSlot();
                    break;
                }
                var itemStart = current.startByte();
                var diagBefore = recoveryTracker.diagnosticCount();
                parameterStartByte = itemStart;
                var parsed = parseParameter(null, DiagnosticCodes.MISSING_TYPE_SIGNAL_PARAMETER);
                if (parsed != null) {
                    var parameter = parsed.parameter();
                    if (parameter.defaultValue() != null) {
                        emitInCurrentFrameOrLegacy("Signal parameters cannot have a default value.",
                                DiagnosticCodes.SIGNATURE_SIGNAL_PARAMETER_DEFAULT);
                    }
                    if (declaredNames.contains(parameter.name())) {
                        emitInCurrentFrameOrLegacy(
                                "Parameter with name \"%s\" was already declared for this signal."
                                        .formatted(parameter.name()),
                                DiagnosticCodes.SIGNATURE_DUPLICATE_PARAMETER
                        );
                        if (parsed.typeFrame() != null) {
                            recoveryTracker.abandon(parsed.typeFrame());
                        }
                        if (parsed.defaultFrame() != null) {
                            recoveryTracker.abandon(parsed.defaultFrame());
                        }
                    } else {
                        declaredNames.add(parameter.name());
                        parameters.add(parameter);
                        if (parsed.typeFrame() != null) {
                            boundTypeFrames.add(parsed.typeFrame());
                            boundTypeOwners.add(parameter);
                        }
                        if (parsed.defaultFrame() != null) {
                            boundDefaultFrames.add(parsed.defaultFrame());
                            boundDefaultOwners.add(parameter);
                        }
                    }
                }
                if (!advanceSeparatedList(paramListFrame, itemStart, previous,
                        recoveryTracker.diagnosticCount() > diagBefore, this::isSignalParameterStarter)) {
                    if (isAtUnclosedMultilineStop() || isAtEnd() || check(GdTokenType.DEDENT)) {
                        claimInScopeMultilineLexicalErrorsWithoutSlot();
                    }
                    break;
                }
            } while (true);
            if (isAtUnclosedMultilineStop() || isAtEnd() || check(GdTokenType.DEDENT)) {
                claimInScopeMultilineLexicalErrorsWithoutSlot();
            }

            consumeCloser(paramListFrame);
            signalParamFrame = popRecoveryOwner();
        }

        var signal = new SignalStatement(name, List.copyOf(parameters),
                range(start, extentEnd()));
        for (var i = 0; i < boundTypeFrames.size(); i++) {
            bindRecoveryOwner(boundTypeFrames.get(i), boundTypeOwners.get(i));
        }
        for (var i = 0; i < boundDefaultFrames.size(); i++) {
            bindRecoveryOwner(boundDefaultFrames.get(i), boundDefaultOwners.get(i));
        }
        if (signalParamFrame != null) {
            bindRecoveryOwner(signalParamFrame, signal);
        }
        finishStatement(signal, "signal declaration");
        return signal;
    }

    /// Port of `GDScriptParser::parse_enum`. Duplicate-key and class-member name-clash checks are
    /// cut with the symbol-table machinery; values may span lines and use a trailing comma.
    private @NotNull EnumDeclaration parseEnum() {
        var start = statementStartByte;
        String name = null;
        if (match(GdTokenType.IDENTIFIER)) {
            name = previous.source();
        }

        var members = new ArrayList<EnumMember>();
        pushRecoveryOwner("enum.body");
        pushMultiline();
        if (match(GdTokenType.BRACE_OPEN)) {
            var enumFrame = pushBracketedFrame(
                    StructuralRole.ENUM,
                    GdTokenType.BRACE_OPEN,
                    GdTokenType.BRACE_CLOSE,
                    previous.startByte(),
                    true,
                    true,
                    "enum",
                    "Expected closing \"}\" for enum.",
                    DiagnosticCodes.MISSING_CLOSER_ENUM,
                    DiagnosticCodes.MISMATCHED_CLOSER_ENUM
            );
            do {
                if (enumFrame.settled || nestingBudgetExceeded || check(GdTokenType.BRACE_CLOSE)) {
                    break;
                }
                if (isAtUnclosedMultilineStop() || isAtEnd() || check(GdTokenType.DEDENT)) {
                    claimInScopeMultilineLexicalErrorsWithoutSlot();
                    break;
                }
                if (consume(GdTokenType.IDENTIFIER, "Expected identifier for enum key.",
                        DiagnosticCodes.DECLARATION_MISSING_NAME)) {
                    var memberName = previous.source();
                    var memberStart = previous.startByte();
                    var memberEnd = previous.endByte();

                    Expression value = null;
                    RecoveryTransactionTracker.ProvisionalFrame valueFrame = null;
                    if (match(GdTokenType.EQUAL)) {
                        var valueStart = current.startByte();
                        pushRecoveryOwner("enum.value");
                        var valueOutcome = parseExpression(false);
                        value = absorbSlot(valueOutcome, valueStart, "enum.value",
                                "Expected expression value after \"=\".");
                        valueFrame = popRecoveryOwner();
                        // The member range extends over the value slot, including a marker's end.
                        memberEnd = Math.max(extentEnd(), value.range().endByte());
                    }
                    var member = new EnumMember(memberName, value, range(memberStart, memberEnd));
                    if (valueFrame != null) {
                        bindRecoveryOwner(valueFrame, member);
                    }
                    members.add(member);
                }
            } while (!enumFrame.settled
                    && !nestingBudgetExceeded
                    && !isAtUnclosedMultilineStop()
                    && !isAtEnd()
                    && !check(GdTokenType.DEDENT)
                    && match(GdTokenType.COMMA));
            if (isAtUnclosedMultilineStop() || isAtEnd() || check(GdTokenType.DEDENT)) {
                claimInScopeMultilineLexicalErrorsWithoutSlot();
            }

            consumeCloser(enumFrame);
        } else {
            popMultiline(false);
            emitInCurrentFrameOrLegacy(
                    "Expected \"{\" after %s.".formatted(name != null ? "enum name" : "\"enum\""),
                    DiagnosticCodes.SYNTAX_UNEXPECTED_TOKEN
            );
        }
        var enumBodyFrame = popRecoveryOwner();
        var enumEnd = Math.max(start, extentEnd());
        if (!members.isEmpty()) {
            enumEnd = Math.max(enumEnd, members.getLast().range().endByte());
        }
        var enumDecl = new EnumDeclaration(name, List.copyOf(members),
                range(start, enumEnd));
        bindRecoveryOwner(enumBodyFrame, enumDecl);
        finishStatement(enumDecl, "enum");
        return enumDecl;
    }

    /// Port of `GDScriptParser::parse_function` / `parse_function_signature`. A `_init` function
    /// produces a `ConstructorDeclaration` (the reference grammar marks it with a dedicated node
    /// type); a non-lambda `_static_init` stays a `FunctionDeclaration` and must be declared
    /// `static` with zero parameters.
    private @Nullable Statement parseFunction(boolean isStatic) {
        var start = statementStartByte;
        String name;
        GdToken nameToken = null;
        RecoveryTransactionTracker.ProvisionalFrame nameFrame = null;
        if (match(GdTokenType.IDENTIFIER)) {
            nameToken = previous;
            name = previous.source();
        } else if (check(GdTokenType.PARENTHESIS_OPEN)
                || check(GdTokenType.FORWARD_ARROW)
                || check(GdTokenType.COLON)) {
            pushRecoveryOwner("function.name");
            recoveryTracker.emitError(
                    "Expected function name after \"func\".",
                    tokenRange(previous),
                    DiagnosticCodes.DECLARATION_MISSING_NAME,
                    RecoveryTransactionTracker.AttributionPolicy.CURRENT_FRAME,
                    null
            );
            nameFrame = popRecoveryOwner();
            name = "";
        } else {
            emitInCurrentFrameOrLegacy("Expected function name after \"func\".",
                    DiagnosticCodes.SYNTAX_UNEXPECTED_TOKEN);
            return null;
        }

        pushRecoveryOwner("function.signature");
        StructuralFrame paramListFrame = null;
        pushMultiline();
        if (match(GdTokenType.PARENTHESIS_OPEN)) {
            paramListFrame = pushBracketedFrame(
                    StructuralRole.PARAMETER_LIST,
                    GdTokenType.PARENTHESIS_OPEN,
                    GdTokenType.PARENTHESIS_CLOSE,
                    previous.startByte(),
                    true,
                    true,
                    "function parameters",
                    "Expected closing \")\" after function parameters.",
                    DiagnosticCodes.MISSING_CLOSER_PARAMETER_LIST,
                    DiagnosticCodes.MISMATCHED_CLOSER_PARAMETER_LIST
            );
        } else {
            popMultiline(false);
            emitInCurrentFrameOrLegacy("Expected opening \"(\" after function name.",
                    DiagnosticCodes.SYNTAX_UNEXPECTED_TOKEN);
        }
        var signature = parseFunctionSignature(false, paramListFrame);
        if ("_static_init".equals(name)) {
            var errorRange = nameToken != null ? tokenRange(nameToken) : tokenRange(previous);
            if (!isStatic) {
                recoveryTracker.emitError(
                        "Static constructor must be declared static.",
                        errorRange,
                        DiagnosticCodes.FUNCTION_INVALID_STATIC_INIT,
                        RecoveryTransactionTracker.AttributionPolicy.CURRENT_FRAME,
                        null
                );
            }
            if (!signature.parameters().isEmpty()) {
                recoveryTracker.emitError(
                        "Static constructor cannot have parameters.",
                        errorRange,
                        DiagnosticCodes.FUNCTION_INVALID_STATIC_INIT,
                        RecoveryTransactionTracker.AttributionPolicy.CURRENT_FRAME,
                        null
                );
            }
        }
        var signatureFrame = popRecoveryOwner();

        var headerEnd = extentEnd();
        var previousFunctionName = currentFunctionName;
        currentFunctionName = name;
        Block body;
        RecoveryTransactionTracker.ProvisionalFrame bodyLayoutFrame = null;
        RecoveryTransactionTracker.ProvisionalFrame trailingFrame = null;
        if (!signature.hasBody()) {
            // Bodyless (abstract-style) functions end the statement at the signature.
            trailingFrame = finishStatementProvisional("bodyless function declaration");
            // The reference path maps a missing body to an empty block spanning the whole
            // declaration node; mirror that range for parity.
            body = new Block(List.of(), range(start, headerEnd));
        } else {
            var bodySuite = parseSuiteWithLayout("function declaration");
            body = bodySuite.block();
            bodyLayoutFrame = bodySuite.layoutFrame();
        }
        currentFunctionName = previousFunctionName;

        var end = Math.max(body.range().endByte(), headerEnd);
        Statement functionNode = name.equals("_init")
                ? new ConstructorDeclaration(signature.parameters(), signature.returnType(),
                        body, range(start, end))
                : new FunctionDeclaration(name, signature.parameters(), signature.returnType(),
                        isStatic, body, range(start, end));
        if (nameFrame != null) {
            bindRecoveryOwner(nameFrame, functionNode);
        }
        bindRecoveryOwner(signatureFrame, functionNode);
        if (signature.returnTypeFrame() != null) {
            bindRecoveryOwner(signature.returnTypeFrame(), functionNode);
        }
        if (bodyLayoutFrame != null) {
            bindRecoveryOwner(bodyLayoutFrame, functionNode);
        }
        if (trailingFrame != null) {
            bindRecoveryOwner(trailingFrame, functionNode);
        }
        return functionNode;
    }

    private record FunctionSignature(@NotNull List<Parameter> parameters, @Nullable TypeRef returnType,
                                     @Nullable RecoveryTransactionTracker.ProvisionalFrame returnTypeFrame,
                                     boolean hasReturnTypeDamage, boolean hasBody) {
    }

    /// Port of `GDScriptParser::parse_function_signature`: parameter list with rest parameter and
    /// the purely syntactic ordering/duplicate checks, and optional `->` return type. Functions
    /// allow a missing colon (bodyless/abstract form); lambdas require it (`forLambda`).
    private @NotNull FunctionSignature parseFunctionSignature(boolean forLambda,
                                                              @Nullable StructuralFrame paramListFrame) {
        var parameters = new ArrayList<Parameter>();
        var declaredNames = new HashSet<String>();
        if (paramListFrame != null) {
            if (!check(GdTokenType.PARENTHESIS_CLOSE) && !isAtEnd()) {
                var defaultUsed = false;
                var restUsed = false;
                do {
                    if (paramListFrame.settled || nestingBudgetExceeded || check(GdTokenType.PARENTHESIS_CLOSE)) {
                        break;
                    }
                    if (isAtUnclosedMultilineStop() || isAtEnd() || check(GdTokenType.DEDENT)) {
                        claimInScopeMultilineLexicalErrorsWithoutSlot();
                        break;
                    }

                    var itemStart = current.startByte();
                    var diagBefore = recoveryTracker.diagnosticCount();
                    var isRest = match(GdTokenType.PERIOD_PERIOD_PERIOD);
                    parameterStartByte = isRest ? previous.startByte() : current.startByte();

                    var parsed = parseParameter(
                            paramListFrame,
                            forLambda
                                    ? DiagnosticCodes.MISSING_TYPE_LAMBDA_PARAMETER
                                    : DiagnosticCodes.MISSING_TYPE_PARAMETER
                    );
                    if (parsed != null) {
                        var parameter = parsed.parameter();

                        // Ordering violations are reported but the parameter keeps its position in the
                        // list (the vendor skips it; positions are part of this AST's recovery contract).
                        if (restUsed) {
                            emitInCurrentFrameOrLegacy("Cannot have parameters after the rest parameter.",
                                    DiagnosticCodes.SIGNATURE_PARAMETER_AFTER_REST);
                        }
                        if (parameter.defaultValue() != null) {
                            if (isRest) {
                                emitInCurrentFrameOrLegacy("The rest parameter cannot have a default value.",
                                        DiagnosticCodes.SIGNATURE_REST_WITH_DEFAULT);
                            } else {
                                defaultUsed = true;
                            }
                        } else if (defaultUsed && !isRest) {
                            emitInCurrentFrameOrLegacy("Cannot have mandatory parameters after optional parameters.",
                                    DiagnosticCodes.SIGNATURE_MANDATORY_AFTER_OPTIONAL);
                        }

                        if (declaredNames.contains(parameter.name())) {
                            emitInCurrentFrameOrLegacy(
                                    "Parameter with name \"%s\" was already declared for this function."
                                            .formatted(parameter.name()),
                                    DiagnosticCodes.SIGNATURE_DUPLICATE_PARAMETER
                            );
                            if (parsed.typeFrame() != null) {
                                recoveryTracker.abandon(parsed.typeFrame());
                            }
                            if (parsed.defaultFrame() != null) {
                                recoveryTracker.abandon(parsed.defaultFrame());
                            }
                            // A dropped duplicate never gets its frame bound; its slot/closer
                            // diagnostics fall back to `function.signature` via their parent chain.
                        } else if (isRest) {
                            restUsed = true;
                            // The variadic rebuild preserves the parsed default (a marker on failure) and
                            // the parameter range — which already spans from the `...` marker start
                            // (parseParameter captured it before the default parse, so a lambda default
                            // with its own parameters cannot clobber it through the shared field). The
                            // type and default owner frames migrate to this final node.
                            var variadic = new Parameter(parameter.name(), parameter.type(),
                                    parameter.defaultValue(), true, parameter.range());
                            parameters.add(variadic);
                            declaredNames.add(parameter.name());
                            if (parsed.typeFrame() != null) {
                                bindRecoveryOwner(parsed.typeFrame(), variadic);
                            }
                            if (parsed.defaultFrame() != null) {
                                bindRecoveryOwner(parsed.defaultFrame(), variadic);
                            }
                        } else {
                            declaredNames.add(parameter.name());
                            parameters.add(parameter);
                            if (parsed.typeFrame() != null) {
                                bindRecoveryOwner(parsed.typeFrame(), parameter);
                            }
                            if (parsed.defaultFrame() != null) {
                                bindRecoveryOwner(parsed.defaultFrame(), parameter);
                            }
                        }
                    }
                    if (!advanceSeparatedList(paramListFrame, itemStart, previous,
                            recoveryTracker.diagnosticCount() > diagBefore, this::isFunctionParameterStarter)) {
                        if (isAtUnclosedMultilineStop() || isAtEnd() || check(GdTokenType.DEDENT)) {
                            claimInScopeMultilineLexicalErrorsWithoutSlot();
                        }
                        break;
                    }
                } while (true);
            }
            if (isAtUnclosedMultilineStop() || isAtEnd() || check(GdTokenType.DEDENT)) {
                claimInScopeMultilineLexicalErrorsWithoutSlot();
            }

            consumeCloser(paramListFrame);
        }

        TypeRef returnType = null;
        RecoveryTransactionTracker.ProvisionalFrame returnTypeFrame = null;
        var hasReturnTypeDamage = false;
        if (match(GdTokenType.FORWARD_ARROW)) {
            var arrowToken = previous;
            pushRecoveryOwner("function.return_type");
            var returnTypeOutcome = parseTypeInternal(true);
            hasReturnTypeDamage = !(returnTypeOutcome instanceof ParseOutcome.Ok<ParsedType>);
            returnType = absorbType(returnTypeOutcome, arrowToken,
                    "Expected return type or \"void\" after \"->\".", DiagnosticCodes.MISSING_TYPE_RETURN_TYPE);
            returnTypeFrame = popRecoveryOwner();
        }

        // The colon may not be present in the case of bodyless functions; lambdas require it
        // (the vendor reports but still parses the body afterwards).
        var hasBody = forLambda
                ? consume(GdTokenType.COLON, "Expected \":\" after lambda declaration.",
                        DiagnosticCodes.MISSING_SEPARATOR_SUITE_COLON)
                : match(GdTokenType.COLON);
        return new FunctionSignature(List.copyOf(parameters), returnType, returnTypeFrame, hasReturnTypeDamage, hasBody);
    }

    // ---------------------------------------------------------------------------------------------
    // Lambda expression — port of parse_lambda (the `func` prefix rule).
    // ---------------------------------------------------------------------------------------------

    private boolean hasTrustedLambdaSuiteStart() {
        if (check(GdTokenType.INDENT)) {
            return true;
        }
        if (check(GdTokenType.NEWLINE)) {
            return peekAfterCurrent() == GdTokenType.INDENT;
        }
        return !isAtEnd() && !check(GdTokenType.DEDENT) && !isVirtualLambdaBoundary();
    }

    /// Port of `GDScriptParser::parse_lambda`, shared by expression and bare statement forms.
    /// A lambda isolates its suite from enclosing multiline mode and restores it after the body.
    private @NotNull ParseOutcome<Expression> parseLambdaExpression() {
        var start = previous.startByte(); // the consumed `func` keyword
        String name = null;
        var hasHeaderDamage = false;
        if (check(GdTokenType.IDENTIFIER)) {
            hasHeaderDamage = advanceExpressionToken();
            name = previous.source();
        }

        var lambdaFrame = pushStructuralFrame(StructuralFrame.nonBracketed(
                StructuralRole.LAMBDA, start, lineIndentOf(start), false, false, "lambda expression"));
        var multilineContext = !multilineStack.isEmpty() && multilineStack.peekLast();
        // Isolate the lambda body from any enclosing multiline mode (vendor push_multiline(false)).
        pushMultiline(false);
        if (multilineContext) {
            tokenizer.pushExpressionIndentedBlock();
        }
        pushRecoveryOwner("function.signature");
        StructuralFrame paramListFrame = null;
        pushMultiline(); // for the parameters
        if (match(GdTokenType.PARENTHESIS_OPEN)) {
            paramListFrame = pushBracketedFrame(
                    StructuralRole.PARAMETER_LIST,
                    GdTokenType.PARENTHESIS_OPEN,
                    GdTokenType.PARENTHESIS_CLOSE,
                    previous.startByte(),
                    true,
                    true,
                    "lambda parameters",
                    "Expected closing \")\" after function parameters.",
                    DiagnosticCodes.MISSING_CLOSER_PARAMETER_LIST,
                    DiagnosticCodes.MISMATCHED_CLOSER_PARAMETER_LIST
            );
        } else {
            popMultiline(false);
            emitInCurrentFrameOrLegacy(
                    name != null
                            ? "Expected opening \"(\" after lambda name."
                            : "Expected opening \"(\" after \"func\".",
                    DiagnosticCodes.SYNTAX_UNEXPECTED_TOKEN
            );
        }

        var previousFunctionName = currentFunctionName;
        currentFunctionName = null;
        var signature = parseFunctionSignature(true, paramListFrame);
        var signatureFrame = popRecoveryOwner();
        // The return type has a separate diagnostic owner but is part of the lambda header.
        hasHeaderDamage |= signature.hasReturnTypeDamage() || recoveryTracker.hasErrors(signatureFrame);
        var headerEnd = extentEnd();

        var previousInLambda = inLambda;
        inLambda = true;
        var couldBreak = canBreak;
        var couldContinue = canContinue;
        canBreak = false;
        canContinue = false;
        // The body suite's parseStatement rounds clobber the shared statement-start field;
        // the enclosing statement's range still needs its own start afterwards.
        var savedStatementStart = statementStartByte;

        // Comments before the `func` keyword are never lambda-body comments: they belong to the
        // enclosing sequence (drained there later) or are grouping-internal trivia (skipped
        // once the enclosing grouping's span is recorded at its close). Defer them for the
        // duration of the body instead of letting the body's drains consume them.
        deferCommentsBefore(start);
        // Same isolation for annotations pending on the enclosing sequence (the body's merged
        // drain would otherwise steal them, like the accessor-suite isolation does).
        var enclosingAnnotations = List.copyOf(pendingAnnotations);
        pendingAnnotations.clear();

        var previousLambdaFallback = lambdaBodyFallbackFrame;
        pushRecoveryOwner("suite.body");
        var lambdaSuiteFallback = popRecoveryOwner();
        lambdaBodyFallbackFrame = lambdaSuiteFallback;

        Block body;
        RecoveryTransactionTracker.ProvisionalFrame bodyLayoutFrame = null;
        try {
            if (nestingBudgetExceeded) {
                // Depth-aborted signature: do not open a body suite over the enclosing
                // statement region — it would absorb following sibling lines. Recovery happens
                // at the outer statement boundary.
                body = new Block(List.of(), range(current.startByte(), current.startByte()));
            } else if (!signature.hasBody() && !hasTrustedLambdaSuiteStart()) {
                // Missing `:` with no trusted body start (e.g. an enclosing call's `,` or `)`):
                // return a damaged bodyless lambda instead of entering `parseSuite` and consuming
                // the enclosing container's continuation.
                body = new Block(List.of(), range(headerEnd, headerEnd));
            } else {
                var bodySuite = parseSuiteWithLayout("lambda declaration", true);
                body = bodySuite.block();
                bodyLayoutFrame = bodySuite.layoutFrame();
            }
        } finally {
            lambdaBodyFallbackFrame = previousLambdaFallback;
            pendingAnnotations.clear();
            pendingAnnotations.addAll(enclosingAnnotations);
            statementStartByte = savedStatementStart;
            restoreDeferredComments();
            popStructuralFrame(lambdaFrame);
        }

        // The lambda-body isolation pop keeps pending comments: those after the body still
        // belong to the enclosing sequence.
        popMultiline(false);
        if (multilineContext) {
            // Skip spurious layout tokens left over from the enclosing expression without
            // touching `previous` (vendor raw `tokenizer->scan()`, not `advance()`).
            while (current.type() == GdTokenType.DEDENT || current.type() == GdTokenType.INDENT
                    || current.type() == GdTokenType.NEWLINE) {
                if (currentPrecedingErrors != null) {
                    recoveryTracker.flushLexicalBatchAsStandalone(currentPrecedingErrors);
                }
                if (currentPostNewlineErrors != null) {
                    recoveryTracker.flushLexicalBatchAsStandalone(currentPostNewlineErrors);
                }
                var next = nextScannedToken();
                current = next.token();
                currentFollowedContinuation = next.followedContinuation();
                currentExplicitContinuation = next.explicitContinuation();
                currentLogicalStartLine = next.logicalStartLine();
                currentPrecedingErrors = next.precedingErrors();
                currentPostNewlineErrors = next.postNewlineErrors();
            }
            tokenizer.popExpressionIndentedBlock();
            if (current.type() == GdTokenType.EOF) {
                var eofRestored = tokenizer.drainRestoredEofDedents();
                if (!eofRestored.isEmpty()) {
                    tokenQueue.addFirst(new QueuedToken(
                            current, currentFollowedContinuation, currentExplicitContinuation,
                            currentLogicalStartLine, null, currentPostNewlineErrors));
                    for (var i = eofRestored.size() - 1; i >= 1; i--) {
                        tokenQueue.addFirst(new QueuedToken(eofRestored.get(i), false));
                    }
                    current = eofRestored.getFirst();
                    currentFollowedContinuation = false;
                    currentExplicitContinuation = false;
                    currentLogicalStartLine = current.startLine();
                    currentPostNewlineErrors = null;
                }
            }
        }

        currentFunctionName = previousFunctionName;
        inLambda = previousInLambda;
        canBreak = couldBreak;
        canContinue = couldContinue;

        // The lambda body's trailing end_statement may have consumed the layout token that
        // follows the lambda (a plain NEWLINE here); enclosing extents end at the body instead.
        trailingLambdaEndByte = body.range().endByte();
        var lambdaNode = new LambdaExpression(name, signature.parameters(), signature.returnType(), body,
                range(start, body.range().endByte()));
        bindRecoveryOwner(signatureFrame, lambdaNode);
        if (signature.returnTypeFrame() != null) {
            bindRecoveryOwner(signature.returnTypeFrame(), lambdaNode);
        }
        bindRecoveryOwner(lambdaSuiteFallback, lambdaNode);
        if (bodyLayoutFrame != null) {
            bindRecoveryOwner(bodyLayoutFrame, lambdaNode);
        }
        return ParseOutcome.structured(lambdaNode, !hasHeaderDamage, false);
    }

    // ---------------------------------------------------------------------------------------------
    // Expression subset — Pratt loop ported from parse_precedence with the declaration-scoped
    // slice of the rule table.
    // ---------------------------------------------------------------------------------------------

    /// Godot's `Precedence` enum (higher ordinal binds tighter); the declaration order mirrors
    /// `gdscript_parser.h` so the table can be extended rule-by-rule to the full set.
    private enum Precedence {
        NONE,
        ASSIGNMENT,
        CAST,
        TERNARY,
        LOGIC_OR,
        LOGIC_AND,
        LOGIC_NOT,
        CONTENT_TEST,
        COMPARISON,
        BIT_OR,
        BIT_XOR,
        BIT_AND,
        BIT_SHIFT,
        ADDITION_SUBTRACTION,
        FACTOR,
        SIGN,
        BIT_NOT,
        POWER,
        TYPE_TEST,
        AWAIT,
        CALL,
        ATTRIBUTE,
        SUBSCRIPT,
        PRIMARY
    }

    private @FunctionalInterface interface PrefixRule {
        @NotNull ParseOutcome<Expression> parse();
    }

    private @FunctionalInterface interface InfixRule {
        /// `precedence` is the enclosing `parsePrecedence` cutoff (used by the suffix chain's
        /// continuation check); `canAssign` is Godot's `p_can_assign`, consulted only by the
        /// assignment rule (every other infix parses its operands with `canAssign == false`,
        /// matching the vendor call sites); `extentStart` is the byte offset of the first token
        /// of the left operand's consumed source span, which can precede the operand node's own
        /// range (e.g. a grouping's `(`) and is the correct left edge for the combined node's
        /// range (the tree-sitter path spans enclosing CST nodes over the whole consumed text).
        /// `left` comes from either an `Ok` or a `Damaged` left-hand outcome whose local
        /// boundaries are settled; the Pratt loop combines prior left damage with any new infix
        /// damage by logical OR.
        @NotNull ParseOutcome<Expression> parse(@NotNull Expression left, Precedence precedence,
                                                boolean canAssign, int extentStart);
    }

    private record ParseRule(@Nullable PrefixRule prefix, @Nullable InfixRule infix,
                             @NotNull Precedence precedence) {
        private static final ParseRule NONE = new ParseRule(null, null, Precedence.NONE);
    }

    /// Port of `GDScriptParser::get_rule()` for the ported expression slice: literals,
    /// identifiers, `self`, the full unary/binary operator families, grouping/array/dictionary
    /// prefixes, `await`/`preload`/`super`/get-node prefixes, ternary, `as`/`is`/`not in`, the
    /// assignment family, and the call/attribute/subscript suffixes. Identifier-like tokens
    /// (including the builtin constants `PI`/`TAU`/`INF`/`NAN`) are normalized to `IDENTIFIER`
    /// by `parsePrecedence` before this lookup. Rules outside the subset resolve to
    /// `ParseRule.NONE`.
    private @NotNull ParseRule ruleFor(GdTokenType type) {
        return switch (type) {
            case LITERAL -> new ParseRule(this::parseLiteralExpression, null, Precedence.NONE);
            case IDENTIFIER -> new ParseRule(this::parseIdentifierExpression, null, Precedence.NONE);
            case SELF -> new ParseRule(this::parseSelfExpression, null, Precedence.NONE);
            case PLUS, MINUS -> new ParseRule(this::parseUnaryOperator, this::parseBinaryOperator,
                    Precedence.ADDITION_SUBTRACTION);
            case TILDE, BANG -> new ParseRule(this::parseUnaryOperator, null, Precedence.NONE);
            case NOT -> new ParseRule(this::parseUnaryOperator, this::parseNotInOperator, Precedence.CONTENT_TEST);
            case STAR, SLASH -> new ParseRule(null, this::parseBinaryOperator, Precedence.FACTOR);
            case PERCENT -> new ParseRule(this::parseGetNodeExpression, this::parseBinaryOperator, Precedence.FACTOR);
            case STAR_STAR -> new ParseRule(null, this::parseBinaryOperator, Precedence.POWER);
            case LESS, LESS_EQUAL, GREATER, GREATER_EQUAL, EQUAL_EQUAL, BANG_EQUAL ->
                    new ParseRule(null, this::parseBinaryOperator, Precedence.COMPARISON);
            case AND, AMPERSAND_AMPERSAND -> new ParseRule(null, this::parseBinaryOperator, Precedence.LOGIC_AND);
            case OR, PIPE_PIPE -> new ParseRule(null, this::parseBinaryOperator, Precedence.LOGIC_OR);
            case AMPERSAND -> new ParseRule(null, this::parseBinaryOperator, Precedence.BIT_AND);
            case PIPE -> new ParseRule(null, this::parseBinaryOperator, Precedence.BIT_OR);
            case CARET -> new ParseRule(null, this::parseBinaryOperator, Precedence.BIT_XOR);
            case LESS_LESS, GREATER_GREATER -> new ParseRule(null, this::parseBinaryOperator, Precedence.BIT_SHIFT);
            case IN -> new ParseRule(null, this::parseBinaryOperator, Precedence.CONTENT_TEST);
            case IF -> new ParseRule(null, this::parseTernaryOperator, Precedence.TERNARY);
            case AS -> new ParseRule(null, this::parseCast, Precedence.CAST);
            case IS -> new ParseRule(null, this::parseTypeTest, Precedence.TYPE_TEST);
            case EQUAL, PLUS_EQUAL, MINUS_EQUAL, STAR_EQUAL, STAR_STAR_EQUAL, SLASH_EQUAL, PERCENT_EQUAL,
                 LESS_LESS_EQUAL, GREATER_GREATER_EQUAL, AMPERSAND_EQUAL, PIPE_EQUAL, CARET_EQUAL ->
                    new ParseRule(null, this::parseAssignment, Precedence.ASSIGNMENT);
            case QUESTION_MARK -> new ParseRule(null, this::parseInvalidToken, Precedence.CAST);
            case AWAIT -> new ParseRule(this::parseAwaitExpression, null, Precedence.NONE);
            case FUNC -> new ParseRule(this::parseLambdaExpression, null, Precedence.NONE);
            case DOLLAR -> new ParseRule(this::parseGetNodeExpression, null, Precedence.NONE);
            case PRELOAD -> new ParseRule(this::parsePreloadExpression, null, Precedence.NONE);
            case SUPER -> new ParseRule(this::parseSuperExpression, null, Precedence.NONE);
            case PARENTHESIS_OPEN -> new ParseRule(this::parseGroupingExpression, this::parseSuffixChain,
                    Precedence.CALL);
            case BRACKET_OPEN -> new ParseRule(this::parseArrayExpression, this::parseSuffixChain,
                    Precedence.SUBSCRIPT);
            case BRACE_OPEN -> new ParseRule(this::parseDictionaryExpression, null, Precedence.NONE);
            case PERIOD -> new ParseRule(null, this::parseSuffixChain, Precedence.ATTRIBUTE);
            default -> ParseRule.NONE;
        };
    }

    /// Port of `GDScriptParser::parse_expression`. `canAssign` gates the assignment infix family
    /// (only statement-level expressions pass `true`, like the vendor call sites);
    /// `stopOnAssign` makes the Pratt loop stop before an `=` token (used by dictionary keys to
    /// detect the Lua-style separator).
    private @NotNull ParseOutcome<Expression> parseExpression(boolean canAssign) {
        return parseExpression(canAssign, false);
    }

    private @NotNull ParseOutcome<Expression> parseExpression(boolean canAssign, boolean stopOnAssign) {
        return parsePrecedence(Precedence.ASSIGNMENT, canAssign, stopOnAssign);
    }

    private @NotNull ParseOutcome<Expression> parsePrecedence(Precedence precedence) {
        return parsePrecedence(precedence, false, false);
    }

    /// Records damage to an expression token before advancing settles its pending lexical errors.
    private boolean advanceExpressionToken() {
        var hasLexicalDamage = hasLexicalDamageOnAdvance(true);
        advance();
        return hasLexicalDamage;
    }

    private static @NotNull ParseOutcome<Expression> withExpressionLexicalDamage(
            @NotNull ParseOutcome<Expression> outcome, boolean hasLexicalDamage
    ) {
        return hasLexicalDamage && outcome.hasCompleteValue()
                ? ParseOutcome.damaged(outcome.valueOrThrow()) : outcome;
    }

    private @NotNull ParseOutcome<Expression> parsePrecedence(Precedence precedence, boolean canAssign, boolean stopOnAssign) {
        // Every expression recursion funnels through here (grouping, containers, calls, lambda
        // defaults, unary/ternary nesting), so this single guard bounds them all. The depth error
        // was already pushed by the guard, so the failure carries its root cause; nothing is
        // consumed on this path.
        if (!enterNestingLevel()) {
            return ParseOutcome.failed();
        }
        try {
            if (isAtUnclosedMultilineStop()) {
                return ParseOutcome.missing();
            }
            // Switch multiline mode on for grouping tokens, before consuming them (vendor order:
            // the flag must be active before the tokenizer scans the following token).
            var pushedGrouping = current.type() == GdTokenType.PARENTHESIS_OPEN
                    || current.type() == GdTokenType.BRACE_OPEN || current.type() == GdTokenType.BRACKET_OPEN;
            if (pushedGrouping) {
                pushMultiline();
            }
            // Godot normalizes identifier-like tokens (match/when/builtin constants) to plain
            // identifiers before consulting the rule table.
            var rule = ruleFor(isIdentifier(current) ? GdTokenType.IDENTIFIER : current.type());
            if (rule.prefix() == null) {
                // Expected expression; the caller reports the proper context-specific error. All
                // grouping tokens currently have prefix rules, so the pop below is unreachable —
                // it guards the multiline balance against future rule-table regressions.
                if (pushedGrouping) {
                    popMultiline();
                }
                return ParseOutcome.missing();
            }

            // The first token of this frame starts the consumed span of every expression built here
            // (infix folds keep that left edge, e.g. a grouping's `(` precedes the inner node's range).
            var entryFrame = structuralStack.peekLast();
            var extentStart = current.startByte();
            var damagedPrefix = advanceExpressionToken();
            var left = withExpressionLexicalDamage(rule.prefix().parse(), damagedPrefix);

            while (precedence.ordinal() <= ruleFor(current.type()).precedence().ordinal()) {
                if (nestingBudgetExceeded
                        || (entryFrame != null && entryFrame.settled)
                        || isAtUnclosedMultilineStop()) {
                    return left;
                }
                var infixRule = ruleFor(current.type());
                // Vendor's infix-loop head stops on a pending lambda end: an operator following
                // the lambda boundary belongs to the enclosing flow, not to this expression
                // (e.g. `return func(): return 1` keeps the bare lambda as the return value).
                // A `Damaged` left operand whose local boundary is settled keeps folding per Pratt
                // precedence; only node-less `Missing`/`Failed` outcomes stop the loop.
                if (infixRule.infix() == null || lambdaEnded
                        || (stopOnAssign && current.type() == GdTokenType.EQUAL)
                        || isMultilineLineHeadIfStatement(current)) {
                    return left;
                }
                var leftExpression = switch (left) {
                    case ParseOutcome.Ok<Expression>(var node) -> node;
                    case ParseOutcome.EnclosedDamage<Expression>(var node) -> node;
                    case ParseOutcome.Damaged<Expression>(var node) -> node;
                    case ParseOutcome.Missing<Expression> _, ParseOutcome.Failed<Expression> _ -> null;
                };
                if (leftExpression == null) {
                    return left;
                }
                // Switch multiline mode on for infix grouping operators, before consuming the token.
                if (current.type() == GdTokenType.PARENTHESIS_OPEN || current.type() == GdTokenType.BRACKET_OPEN) {
                    pushMultiline();
                }
                var damagedOperator = advanceExpressionToken();
                var nextOutcome = infixRule.infix().parse(leftExpression, precedence, canAssign, extentStart);
                // Folding must preserve left-operand damage. A lexical error before the
                // operator itself remains outside any delimiters the operator introduces.
                left = nextOutcome.hasCompleteValue() && (damagedOperator || !(left instanceof ParseOutcome.Ok<Expression>))
                        ? ParseOutcome.structured(nextOutcome.valueOrThrow(), !damagedOperator && left.hasCompleteValue(), true)
                        : nextOutcome;
            }
            return left;
        } finally {
            exitNestingLevel();
        }
    }

    private @NotNull ParseOutcome<Expression> parseLiteralExpression() {
        var kind = previous.literalKind().astKind();
        if (kind == null) {
            throw new IllegalStateException("GDScript parser bug: literal token without a literal kind.");
        }
        return ParseOutcome.ok(new LiteralExpression(kind, previous.source(), tokenRange(previous)));
    }

    private @NotNull ParseOutcome<Expression> parseIdentifierExpression() {
        return ParseOutcome.ok(new IdentifierExpression(previous.source(), tokenRange(previous)));
    }

    private @NotNull ParseOutcome<Expression> parseSelfExpression() {
        return ParseOutcome.ok(new SelfExpression(tokenRange(previous)));
    }

    /// Port of `GDScriptParser::parse_grouping`: produces the inner expression itself (no wrapper
    /// node). Multiline was pushed by `parsePrecedence` before the `(` was consumed; the pop
    /// happens here before the closing parenthesis (vendor order), even on failure. A closed
    /// grouping preserves the inner outcome because it introduces no AST node that could enclose
    /// damage. A missing or wrong `)` makes even a healthy inner value damaged.
    private @NotNull ParseOutcome<Expression> parseGroupingExpression() {
        var groupingFrame = pushBracketedFrame(
                StructuralRole.GROUPING,
                GdTokenType.PARENTHESIS_OPEN,
                GdTokenType.PARENTHESIS_CLOSE,
                previous.startByte(),
                true,
                true,
                "grouping expression",
                "Expected closing \")\" after grouping expression.",
                DiagnosticCodes.MISSING_CLOSER_GROUPING,
                DiagnosticCodes.MISMATCHED_CLOSER_GROUPING
        );
        var innerStart = current.startByte();
        var hasInnerLexicalDamage = hasCurrentSlotLexicalErrors();
        var grouped = parseExpression(false);
        switch (grouped) {
            case ParseOutcome.Ok<Expression> _, ParseOutcome.EnclosedDamage<Expression> _,
                 ParseOutcome.Damaged<Expression> _ -> {
                hasInnerLexicalDamage |= hasLexicalDamageOnExactCloser(groupingFrame);
                return consumeCloser(groupingFrame) && !hasInnerLexicalDamage
                        ? grouped : ParseOutcome.damaged(grouped.valueOrThrow());
            }
            case ParseOutcome.Missing<Expression> _, ParseOutcome.Failed<Expression> _ -> {
                Expression marker;
                if (hasCurrentSlotLexicalErrors()) {
                    marker = claimLexicalErrorSlotValue(innerStart, grouped instanceof ParseOutcome.Failed<Expression>);
                } else {
                    if (grouped instanceof ParseOutcome.Missing<Expression>) {
                        recoveryTracker.emitError(
                                "Expected grouping expression.",
                                tokenRange(previous),
                                DiagnosticCodes.SLOT_GROUPING,
                                RecoveryTransactionTracker.AttributionPolicy.CURRENT_FRAME,
                                null
                        );
                    }
                    marker = slotMarker(grouped, innerStart, "grouping");
                }
                consumeCloser(groupingFrame, true);
                return ParseOutcome.damaged(marker);
            }
        }
    }

    /// Port of `GDScriptParser::parse_array`: elements parse with assignment disallowed; a failed
    /// element is reported and its slot keeps a marker (preserving element positions); trailing
    /// comma allowed. A closed array encloses element damage but not a broken list boundary
    /// or separator of its own.
    private @NotNull ParseOutcome<Expression> parseArrayExpression() {
        var start = previous.startByte();
        var arrayFrame = pushBracketedFrame(
                StructuralRole.ARRAY,
                GdTokenType.BRACKET_OPEN,
                GdTokenType.BRACKET_CLOSE,
                start,
                true,
                true,
                "array elements",
                "Expected closing \"]\" after array elements.",
                DiagnosticCodes.MISSING_CLOSER_ARRAY,
                DiagnosticCodes.MISMATCHED_CLOSER_ARRAY
        );
        var elements = new ArrayList<Expression>();
        var hasChildDamage = false;
        if (!check(GdTokenType.BRACKET_CLOSE) || hasCurrentSlotLexicalErrors()) {
            do {
                if (arrayFrame.settled || nestingBudgetExceeded
                        || (check(GdTokenType.BRACKET_CLOSE) && !hasCurrentSlotLexicalErrors())) {
                    // Allow for trailing comma at closer.
                    break;
                }
                if (isAtUnclosedMultilineStop()) {
                    if (hasCurrentSlotLexicalErrors()) {
                        elements.add(claimLexicalErrorSlotValue(previous.endByte(), false));
                        hasChildDamage = true;
                    }
                    break;
                }
                var elementStart = current.startByte();
                var diagBefore = recoveryTracker.diagnosticCount();
                hasChildDamage |= hasCurrentSlotLexicalErrors();
                var element = parseExpression(false);
                hasChildDamage |= !(element instanceof ParseOutcome.Ok<Expression>);
                elements.add(absorbSlot(element, elementStart, "array.element",
                        "Expected expression as array element."));
                if (!advanceSeparatedList(arrayFrame, elementStart, previous,
                        recoveryTracker.diagnosticCount() > diagBefore, this::isExpressionStarter)) {
                    break;
                }
            } while (true);
        }
        hasChildDamage |= hasLexicalDamageOnExactCloser(arrayFrame) || arrayFrame.lexicalDamageBeforeListSeparator;
        var closed = consumeCloser(arrayFrame);
        var arrayEnd = Math.max(start, extentEnd());
        if (!elements.isEmpty()) {
            arrayEnd = Math.max(arrayEnd, elements.getLast().range().endByte());
        }
        return ParseOutcome.structured(new ArrayExpression(List.copyOf(elements), false, range(start, arrayEnd)),
                closed && !arrayFrame.damagedListSyntax, hasChildDamage);
    }

    /// Keeps recovered entries and distinguishes entry damage from an incomplete dictionary.
    /// Dictionary styles tracked by `parseDictionaryExpression` (vendor `DictionaryNode::Style`).
    private enum DictStyle {NONE, PYTHON_DICT, LUA_TABLE}

    /// Port of `GDScriptParser::parse_dictionary` with an explicit `Key -> Separator -> Value ->
    /// NextItem` state machine: style locks only on the first real `:` or `=`; mixed styles report
    /// without consuming past the mismatched separator; missing separators emit
    /// `MISSING_SEPARATOR_DICTIONARY` once and fill a zero-width `MISSING` value at delimiters,
    /// outer boundaries, or cross-line next-entry starts without double-reporting a missing value,
    /// while same-line clear value starts (e.g. `{1 2}`) still parse the value.
    private @NotNull ParseOutcome<Expression> parseDictionaryExpression() {
        var start = previous.startByte();
        var dictionaryFrame = pushBracketedFrame(
                StructuralRole.DICTIONARY,
                GdTokenType.BRACE_OPEN,
                GdTokenType.BRACE_CLOSE,
                start,
                true,
                true,
                "dictionary elements",
                "Expected closing \"}\" after dictionary elements.",
                DiagnosticCodes.MISSING_CLOSER_DICTIONARY,
                DiagnosticCodes.MISMATCHED_CLOSER_DICTIONARY
        );
        var entries = new ArrayList<DictEntry>();
        var style = DictStyle.NONE;
        var hasChildDamage = false;
        if (!check(GdTokenType.BRACE_CLOSE) || hasCurrentSlotLexicalErrors()) {
            do {
                if (dictionaryFrame.settled || nestingBudgetExceeded
                        || (check(GdTokenType.BRACE_CLOSE) && !hasCurrentSlotLexicalErrors())) {
                    // Allow for trailing comma at closer.
                    break;
                }
                if (isAtUnclosedMultilineStop()) {
                    if (hasCurrentSlotLexicalErrors()) {
                        var key = claimLexicalErrorSlotValue(previous.endByte(), false);
                        var missingPos = Math.max(extentEnd(), key.range().endByte());
                        var value = new ErrorExpression(CstIssueKind.MISSING, "dictionary.value", "",
                                range(missingPos, missingPos));
                        entries.add(new DictEntry(
                                key,
                                value,
                                range(key.range().startByte(), missingPos)
                        ));
                        hasChildDamage = true;
                    }
                    break;
                }
                var keyExtentStart = current.startByte();
                var keyStartLine = current.startLine();
                var diagBefore = recoveryTracker.diagnosticCount();
                hasChildDamage |= hasCurrentSlotLexicalErrors();
                dictionaryFrame.parsingDictionaryKey = true;
                ParseOutcome<Expression> keyOutcome;
                try {
                    keyOutcome = parseExpression(false, true);
                } finally {
                    dictionaryFrame.parsingDictionaryKey = false;
                }
                if (dictionaryFrame.settled || nestingBudgetExceeded) {
                    break;
                }
                var key = absorbSlot(keyOutcome, keyExtentStart, "dictionary.key",
                        "Expected expression as dictionary key.");
                hasChildDamage |= !(keyOutcome instanceof ParseOutcome.Ok<Expression>);

                // State 2: Separator. Lock style only when a real `:` or `=` arrives.
                var separatorStyle = switch (current.type()) {
                    case COLON -> DictStyle.PYTHON_DICT;
                    case EQUAL -> DictStyle.LUA_TABLE;
                    default -> DictStyle.NONE;
                };
                var hasSeparator = separatorStyle != DictStyle.NONE;
                if (hasSeparator) {
                    hasChildDamage |= hasLexicalDamageOnAdvance(true);
                    advance();
                    if (style == DictStyle.NONE) {
                        style = separatorStyle;
                    } else if (style != separatorStyle) {
                        dictionaryFrame.damagedListSyntax = true;
                        var expected = style == DictStyle.LUA_TABLE ? "=" : ":";
                        emitInCurrentFrameOrLegacy(
                                "Expected \"%s\" after dictionary key. Mixing dictionary styles is not allowed."
                                        .formatted(expected),
                                DiagnosticCodes.DICTIONARY_MIXED_STYLE
                        );
                    }
                } else if (!(keyOutcome instanceof ParseOutcome.Missing<Expression>)) {
                    dictionaryFrame.damagedListSyntax = true;
                    var sepMessage = switch (style) {
                        case NONE -> "Expected \":\" or \"=\" after dictionary key.";
                        case PYTHON_DICT -> "Expected \":\" after dictionary key.";
                        case LUA_TABLE -> "Expected \"=\" after dictionary key.";
                    };
                    recoveryTracker.emitError(
                            sepMessage,
                            tokenRange(previous),
                            DiagnosticCodes.MISSING_SEPARATOR_DICTIONARY,
                            RecoveryTransactionTracker.AttributionPolicy.CURRENT_FRAME,
                            null
                    );
                }

                var normalizedKey = key;
                if (style == DictStyle.LUA_TABLE && keyOutcome.hasCompleteValue()) {
                    // A closed container has a checkable key shape even when its children
                    // need recovery. A partial key does not, so avoid a secondary complaint.
                    if (key instanceof IdentifierExpression(var name, _)) {
                        normalizedKey = new LiteralExpression("string_name", "&\"" + name + "\"", key.range());
                    } else if (key instanceof LiteralExpression(var kind, var sourceText, _)
                            && "string".equals(kind)) {
                        normalizedKey = new LiteralExpression("string_name", "&" + sourceText, key.range());
                    } else {
                        hasChildDamage = true;
                        emitInCurrentFrameOrLegacy(
                                "Expected identifier or string as Lua-style dictionary key (e.g \"{ key = value }\").",
                                DiagnosticCodes.DICTIONARY_INVALID_LUA_KEY
                        );
                    }
                }

                // State 3: Value. A written `:` or `=` parses unrestricted (including cross-line
                // values); without a separator, stop at boundaries, cross-line breaks (relative to
                // the key's start line), or a clear next `key:`/`key=` start with a zero-width
                // MISSING value and parse only an unambiguous same-line value start.
                ParseOutcome<Expression> valueOutcome;
                Expression value;
                hasChildDamage |= hasCurrentSlotLexicalErrors();
                if (hasSeparator) {
                    var valueExtentStart = current.startByte();
                    valueOutcome = parseExpression(false);
                    value = absorbSlot(valueOutcome, valueExtentStart, "dictionary.value",
                            "Expected expression as dictionary value.");
                } else {
                    var sameLineCandidate = !isAtEnd()
                            && !check(GdTokenType.COMMA)
                            && !check(GdTokenType.BRACE_CLOSE)
                            && !(isCloserToken(current.type()) && hasMatchingAncestorCloser(current.type()))
                            && !isAtUnclosedMultilineStop()
                            && (current.startLine() == keyStartLine || currentFollowedContinuation)
                            && isExpressionStarter();
                    // Check for a trailing `key:`/`key=` only after boundary/escape checks pass so
                    // `peekAfterCurrent()` never clears `tokenizer.suppressedNewline` before replay.
                    var nextLooksLikeEntryKey = sameLineCandidate
                            && (current.type() == GdTokenType.LITERAL || isIdentifier(current))
                            && (peekAfterCurrent() == GdTokenType.COLON || peekAfterCurrent() == GdTokenType.EQUAL);
                    var sameLineClearValue = sameLineCandidate && !nextLooksLikeEntryKey;
                    if (sameLineClearValue) {
                        var valueExtentStart = current.startByte();
                        valueOutcome = parseExpression(false);
                        value = absorbSlot(valueOutcome, valueExtentStart, "dictionary.value",
                                "Expected expression as dictionary value.");
                    } else {
                        var missingPos = extentEnd();
                        valueOutcome = ParseOutcome.missing();
                        value = new ErrorExpression(CstIssueKind.MISSING, "dictionary.value", "",
                                range(missingPos, missingPos));
                    }
                }

                // An entry is witnessed by an explicit `:` / `=` separator, a claimed lexical-error
                // key, or a non-missing key/value outcome (`{}` and `,}` produce no entry).
                var claimedKeyLexical = key instanceof ErrorExpression(var keyIssue, _, _, _)
                        && keyIssue == CstIssueKind.ERROR;
                if (hasSeparator
                        || claimedKeyLexical
                        || !(keyOutcome instanceof ParseOutcome.Missing<Expression>
                                && valueOutcome instanceof ParseOutcome.Missing<Expression>)) {
                    hasChildDamage |= !(valueOutcome instanceof ParseOutcome.Ok<Expression>);
                    // Entry edges span the consumed text: a grouping `(`/`)` around a key belongs to
                    // the entry even though the unwrapped key node starts inside it, and a zero-width
                    // marker side anchors at its failure point (envelope-safe by construction).
                    var entryStart = Math.min(keyExtentStart, key.range().startByte());
                    var entryEnd = Math.max(Math.max(extentEnd(), value.range().endByte()),
                            key.range().endByte());
                    entries.add(new DictEntry(
                            normalizedKey,
                            Objects.requireNonNull(value, "dictionary literal entry value must not be null"),
                            range(entryStart, entryEnd)
                    ));
                }

                // State 4: NextItem.
                if (!advanceSeparatedList(dictionaryFrame, keyExtentStart, previous,
                        recoveryTracker.diagnosticCount() > diagBefore, this::isExpressionStarter)) {
                    break;
                }
            } while (true);
        }
        hasChildDamage |= hasLexicalDamageOnExactCloser(dictionaryFrame)
                || dictionaryFrame.lexicalDamageBeforeListSeparator;
        var closed = consumeCloser(dictionaryFrame);
        var dictEnd = Math.max(start, extentEnd());
        if (!entries.isEmpty()) {
            dictEnd = Math.max(dictEnd, entries.getLast().range().endByte());
        }
        return ParseOutcome.structured(new DictionaryExpression(List.copyOf(entries), false, range(start, dictEnd)),
                closed && !dictionaryFrame.damagedListSyntax, hasChildDamage);
    }

    /// Path states of `parseGetNodeExpression` (vendor `parse_get_node`'s `PathState`).
    private enum NodePathState {START, SLASH, PERCENT, NODE_NAME}

    /// Port of `GDScriptParser::parse_get_node` (`$...` / `%...` shorthands). Path segments are
    /// node names (Godot's `is_node_name()` set) or string literals; `/` is valid only at the
    /// start or after a name, `%` only at the start or right after `/`. The AST node stores the
    /// raw source slice (including the leading `$`/`%`), matching the tree-sitter path's
    /// `GetNodeExpression.sourceText` contract.
    private @NotNull ParseOutcome<Expression> parseGetNodeExpression() {
        var startToken = previous;
        if (!isNodeName(current) && current.type() != GdTokenType.LITERAL
                && current.type() != GdTokenType.SLASH && current.type() != GdTokenType.PERCENT) {
            emitInCurrentFrameOrLegacy("Expected node path as string or identifier after \"%s\"."
                    .formatted(startToken.type().displayName()),
                    DiagnosticCodes.NODE_PATH_INVALID);
            return ParseOutcome.failed();
        }
        if (current.type() == GdTokenType.LITERAL && current.literalKind() != GdLiteralKind.STRING) {
            emitInCurrentFrameOrLegacy("Expected node path as string or identifier after \"%s\"."
                    .formatted(startToken.type().displayName()),
                    DiagnosticCodes.NODE_PATH_INVALID);
            return ParseOutcome.failed();
        }

        var state = NodePathState.START;
        var hadLexicalDamage = false;
        if (startToken.type() == GdTokenType.DOLLAR && check(GdTokenType.SLASH)) {
            // An initial slash (absolute path) is handled by the loop's SLASH branch.
            hadLexicalDamage = advanceExpressionToken();
        }
        do {
            if (previous.type() == GdTokenType.PERCENT) {
                if (state != NodePathState.START && state != NodePathState.SLASH) {
                    emitInCurrentFrameOrLegacy(
                            "\"%\" is only valid in the beginning of a node name (either after \"$\" or after \"/\")",
                            DiagnosticCodes.NODE_PATH_INVALID);
                    return ParseOutcome.failed();
                }
                state = NodePathState.PERCENT;
            } else if (previous.type() == GdTokenType.SLASH) {
                if (state != NodePathState.START && state != NodePathState.NODE_NAME) {
                    emitInCurrentFrameOrLegacy(
                            "\"/\" is only valid at the beginning of the path or after a node name.",
                            DiagnosticCodes.NODE_PATH_INVALID);
                    return ParseOutcome.failed();
                }
                state = NodePathState.SLASH;
            }

            if (check(GdTokenType.LITERAL)) {
                hadLexicalDamage |= advanceExpressionToken();
                if (previous.literalKind() != GdLiteralKind.STRING) {
                    var after = switch (state) {
                        case PERCENT -> "%";
                        case SLASH -> "/";
                        default -> "$";
                    };
                    emitInCurrentFrameOrLegacy("Expected node path as string or identifier after \"%s\"."
                            .formatted(after),
                            DiagnosticCodes.NODE_PATH_INVALID);
                    return ParseOutcome.failed();
                }
                state = NodePathState.NODE_NAME;
            } else if (isNodeName(current)) {
                hadLexicalDamage |= advanceExpressionToken();
                state = NodePathState.NODE_NAME;
            } else if (!check(GdTokenType.SLASH) && !check(GdTokenType.PERCENT)) {
                emitInCurrentFrameOrLegacy("Unexpected \"%s\" in node path.".formatted(
                        current.type() == GdTokenType.IDENTIFIER ? current.source()
                                : current.type().displayName()),
                        DiagnosticCodes.NODE_PATH_INVALID);
                return ParseOutcome.failed();
            }
            if (!check(GdTokenType.SLASH) && !check(GdTokenType.PERCENT)) {
                break;
            }
            hadLexicalDamage |= advanceExpressionToken();
        } while (true);

        var node = new GetNodeExpression(slice(startToken.startByte(), previous.endByte()),
                range(startToken.startByte(), previous.endByte()));
        return ParseOutcome.structured(node, !hadLexicalDamage, false);
    }

    /// Port of `GDScriptParser::parse_preload`: the path is a general expression (constant
    /// folding and resource-existence checks belong to later Godot phases, cut here); one
    /// trailing comma is allowed. A closed preload encloses damage in the path but not damage
    /// before the opening parenthesis.
    private @NotNull ParseOutcome<Expression> parsePreloadExpression() {
        var startToken = previous;
        pushMultiline();
        if (!check(GdTokenType.PARENTHESIS_OPEN)) {
            popMultiline(false);
            emitInCurrentFrameOrLegacy("Expected \"(\" after \"preload\".",
                    DiagnosticCodes.SYNTAX_UNEXPECTED_TOKEN);
            var end = startToken.endByte();
            var marker = new ErrorExpression(CstIssueKind.MISSING, "preload.path", "", range(end, end));
            return ParseOutcome.damaged(new PreloadExpression(marker, range(startToken.startByte(), end)));
        }
        var openerHadLexicalDamage = advanceExpressionToken();
        var preloadFrame = pushBracketedFrame(
                StructuralRole.PRELOAD,
                GdTokenType.PARENTHESIS_OPEN,
                GdTokenType.PARENTHESIS_CLOSE,
                previous.startByte(),
                true,
                true,
                "preload arguments",
                "Expected \")\" after preload path.",
                DiagnosticCodes.MISSING_CLOSER_PRELOAD,
                DiagnosticCodes.MISMATCHED_CLOSER_PRELOAD
        );
        var pathStart = current.startByte();
        var hadLexicalErrors = hasCurrentSlotLexicalErrors();
        var pathOutcome = parseExpression(false);
        var path = absorbSlot(pathOutcome, pathStart, "preload.path", "Expected resource path after \"(\".");
        if (!preloadFrame.settled && !nestingBudgetExceeded && !isAtUnclosedMultilineStop()
                && check(GdTokenType.COMMA)) {
            hadLexicalErrors |= advanceExpressionToken(); // Allow trailing comma.
        }
        hadLexicalErrors |= hasLexicalDamageOnExactCloser(preloadFrame);
        var closed = consumeCloser(preloadFrame);
        var node = new PreloadExpression(path,
                range(startToken.startByte(), Math.max(extentEnd(), path.range().endByte())));
        return ParseOutcome.structured(node, closed && !openerHadLexicalDamage,
                hadLexicalErrors || !(pathOutcome instanceof ParseOutcome.Ok<Expression>));
    }

    /// Port of `GDScriptParser::parse_await`: the operand parses at `PREC_AWAIT`, so call/
    /// attribute/subscript suffixes bind into the awaited expression (`await timer.timeout`). A
    /// failed operand keeps a marker in the node (vendor keeps the node with a null operand).
    private @NotNull ParseOutcome<Expression> parseAwaitExpression() {
        var startToken = previous;
        var operandStart = current.startByte();
        var operandOutcome = parsePrecedence(Precedence.AWAIT);
        var operand = absorbSlot(operandOutcome, operandStart, "await.operand",
                "Expected signal or coroutine after \"await\".");
        var node = new AwaitExpression(operand,
                range(startToken.startByte(), Math.max(extentEnd(), operand.range().endByte())));
        return propagateOperandDamage(node, operandOutcome);
    }

    /// An undelimited operator can carry already-enclosed damage, but it cannot enclose a partial
    /// or missing operand by itself.
    private static @NotNull ParseOutcome<Expression> propagateOperandDamage(
            @NotNull Expression node, @NotNull ParseOutcome<Expression> operand
    ) {
        return ParseOutcome.structured(node, operand.hasCompleteValue(),
                operand instanceof ParseOutcome.EnclosedDamage<Expression>);
    }

    /// Port of the `SUPER` branch of `GDScriptParser::parse_call`, mapped to the AST contract's
    /// pinned shapes: `super(...)` is a `CallExpression` on an `IdentifierExpression("super")`
    /// callee, `super.method(...)` an `AttributeExpression` with a single call step. The method
    /// name follows vendor `consume(IDENTIFIER)`, which accepts the whole `is_identifier()` set
    /// (`match`/`when`/builtin constants, e.g. `super.match()`) but not the wider
    /// `is_node_name()` set. Godot's "implicit super() outside a function" check needs
    /// function-context tracking and is outside the ported parse-time check set, left to the
    /// analysis layer.
    private @NotNull ParseOutcome<Expression> parseSuperExpression() {
        var superToken = previous;
        var superIdentifier = new IdentifierExpression("super", tokenRange(superToken));
        if (check(GdTokenType.PARENTHESIS_OPEN)) {
            pushMultiline();
            var hadSyntaxLexicalDamage = advanceExpressionToken();
            var arguments = parseCallArguments();
            var call = new CallExpression(superIdentifier, arguments.valueOrThrow(),
                    range(superToken.startByte(), extentEnd()));
            return ParseOutcome.structured(call, arguments.hasCompleteValue() && !hadSyntaxLexicalDamage,
                    arguments instanceof ParseOutcome.EnclosedDamage<List<Expression>>);
        }
        if (!check(GdTokenType.PERIOD)) {
            emitInCurrentFrameOrLegacy("Expected \".\" or \"(\" after \"super\".", DiagnosticCodes.SUPER_MISSING_ACCESS);
            return ParseOutcome.damaged(superIdentifier);
        }
        var hadSyntaxLexicalDamage = advanceExpressionToken();
        if (!check(GdTokenType.IDENTIFIER)) {
            // The receiver stays visible (a chain with a missing-name step) so editor consumers
            // can still offer lexical-superclass member candidates after `super.`.
            emitInCurrentFrameOrLegacy("Expected function name after \".\".", DiagnosticCodes.SUPER_MISSING_NAME);
            var missing = new MissingAttributeStep(range(extentEnd(), extentEnd()));
            return ParseOutcome.damaged(new AttributeExpression(superIdentifier, List.of(missing),
                    range(superToken.startByte(), extentEnd())));
        }
        hadSyntaxLexicalDamage |= advanceExpressionToken();
        var nameToken = previous;
        if (!check(GdTokenType.PARENTHESIS_OPEN)) {
            emitInCurrentFrameOrLegacy("Expected \"(\" after function name.", DiagnosticCodes.SUPER_MISSING_CALL);
            var propertyStep = new AttributePropertyStep(nameToken.source(), tokenRange(nameToken));
            return ParseOutcome.damaged(new AttributeExpression(superIdentifier, List.of(propertyStep),
                    range(superToken.startByte(), extentEnd())));
        }
        pushMultiline();
        hadSyntaxLexicalDamage |= advanceExpressionToken();
        var arguments = parseCallArguments();
        var step = new AttributeCallStep(nameToken.source(), arguments.valueOrThrow(),
                range(nameToken.startByte(), extentEnd()));
        var chain = new AttributeExpression(superIdentifier, List.of(step),
                range(superToken.startByte(), extentEnd()));
        return ParseOutcome.structured(chain, arguments.hasCompleteValue() && !hadSyntaxLexicalDamage,
                arguments instanceof ParseOutcome.EnclosedDamage<List<Expression>>);
    }

    /// Port of `GDScriptParser::parse_unary_operator`: `+`/`-` immediately adjacent to an
    /// integer or non-dot-prefixed float literal bind at `PREC_PRIMARY` (matching Godot's
    /// tokenizer-absorbed signed number precedence without mutating unsigned lexemes), while
    /// spaced `+`/`-` and non-literal operands stay at `PREC_SIGN`, `~` at `PREC_BIT_NOT`, and
    /// `not`/`!` at `PREC_LOGIC_NOT`. A failed operand keeps a marker in the node and marks the
    /// outcome damaged.
    private @NotNull ParseOutcome<Expression> parseUnaryOperator() {
        var operator = previous;
        var isTightSignedNumber = (operator.type() == GdTokenType.PLUS || operator.type() == GdTokenType.MINUS)
                && operator.endByte() == current.startByte()
                && current.type() == GdTokenType.LITERAL
                && (current.literalKind() == GdLiteralKind.INTEGER
                || (current.literalKind() == GdLiteralKind.FLOAT && !current.source().startsWith(".")));
        var operandPrecedence = switch (operator.type()) {
            case PLUS, MINUS -> isTightSignedNumber ? Precedence.PRIMARY : Precedence.SIGN;
            case TILDE -> Precedence.BIT_NOT;
            case NOT, BANG -> Precedence.LOGIC_NOT;
            default -> throw new IllegalStateException(
                    "GDScript parser bug: unary operator rule entered on " + operator.type());
        };
        var operandStart = current.startByte();
        var operandOutcome = parsePrecedence(operandPrecedence);
        var operand = absorbSlot(operandOutcome, operandStart, "unary.operand",
                "Expected expression after \"%s\" operator.".formatted(operator.type().displayName()));
        // The right edge is the last consumed token, not the operand node's own end (a trailing
        // grouping `)` belongs to the consumed span, matching the tree-sitter path's CST ranges).
        var node = new UnaryExpression(operator.source(), operand,
                range(operator.startByte(), Math.max(extentEnd(), operand.range().endByte())));
        return propagateOperandDamage(node, operandOutcome);
    }

    /// Port of `GDScriptParser::parse_binary_operator` (left-associative via precedence + 1 on the
    /// right operand, which also makes `**` left-associative like Godot). A failed right operand
    /// keeps a marker in the node (vendor keeps the node with a null child) and marks this
    /// operator's own outcome damaged while keeping the node eligible for further Pratt folding.
    private @NotNull ParseOutcome<Expression> parseBinaryOperator(@NotNull Expression left, Precedence entryPrecedence,
                                                                  boolean canAssign, int extentStart) {
        var operator = previous;
        var precedence = ruleFor(operator.type()).precedence();
        var rightStart = current.startByte();
        var rightOutcome = parsePrecedence(Precedence.values()[precedence.ordinal() + 1]);
        var right = absorbSlot(rightOutcome, rightStart, "binary.right",
                "Expected expression after \"%s\" operator.".formatted(operator.type().displayName()));
        var node = new BinaryExpression(operator.source(), left, right,
                range(extentStart, Math.max(Math.max(extentEnd(), right.range().endByte()), left.range().endByte())));
        return propagateOperandDamage(node, rightOutcome);
    }

    /// Port of `GDScriptParser::parse_binary_not_in_operator`. Godot wraps the membership binary
    /// in a logic-not unary node; the AST contract models the pair as a single
    /// `BinaryExpression("not in")` (the tree-sitter path's pinned shape). When the `in` token is
    /// missing, the vendor still parses the right-hand side as if it were present (the recovery
    /// message then names "not"); mirrored here.
    private @NotNull ParseOutcome<Expression> parseNotInOperator(@NotNull Expression left, Precedence entryPrecedence,
                                                                 boolean canAssign, int extentStart) {
        var matchedIn = check(GdTokenType.IN);
        var damagedIn = matchedIn && advanceExpressionToken();
        if (!matchedIn) {
            emitInCurrentFrameOrLegacy("Expected \"in\" after \"not\" in content-test operator.",
                    DiagnosticCodes.SYNTAX_UNEXPECTED_TOKEN);
        }
        var rightStart = current.startByte();
        var rightOutcome = parsePrecedence(Precedence.COMPARISON); // PREC_CONTENT_TEST + 1
        var right = absorbSlot(rightOutcome, rightStart, "binary.right",
                "Expected expression after \"%s\" operator.".formatted(matchedIn ? "in" : "not"));
        var node = new BinaryExpression("not in", left, right,
                range(extentStart, Math.max(Math.max(extentEnd(), right.range().endByte()), left.range().endByte())));
        return matchedIn && !damagedIn ? propagateOperandDamage(node, rightOutcome) : ParseOutcome.damaged(node);
    }

    private boolean isTernaryMissingElseStop(int conditionEndLine) {
        return isStatementEndToken()
                || check(GdTokenType.DEDENT)
                || isAtUnclosedMultilineStop()
                || (current.startLine() > conditionEndLine && !currentFollowedContinuation)
                || check(GdTokenType.COMMA)
                || check(GdTokenType.PARENTHESIS_CLOSE)
                || check(GdTokenType.BRACKET_CLOSE)
                || check(GdTokenType.BRACE_CLOSE)
                || check(GdTokenType.COLON);
    }

    /// Port of `GDScriptParser::parse_ternary_operator` (`true_value if condition else false_value`).
    /// Both branches parse at `PREC_TERNARY` with assignment disallowed. Missing `else` keeps the
    /// conditional damaged and preserves statement boundaries across lines using a zero-width
    /// `MISSING` false branch rather than stealing the next line's expression.
    private @NotNull ParseOutcome<Expression> parseTernaryOperator(@NotNull Expression left, Precedence entryPrecedence,
                                                                   boolean canAssign, int extentStart) {
        var conditionStart = current.startByte();
        var conditionOutcome = parsePrecedence(Precedence.TERNARY);
        var condition = absorbSlot(conditionOutcome, conditionStart, "ternary.condition",
                "Expected expression as ternary condition after \"if\".");
        var conditionEndLine = previous.endLine();
        var hasElse = check(GdTokenType.ELSE);
        var damagedElse = hasElse && advanceExpressionToken();
        if (!hasElse) {
            emitInCurrentFrameOrLegacy("Expected \"else\" after ternary operator condition.",
                    DiagnosticCodes.SYNTAX_UNEXPECTED_TOKEN);
        }
        Expression falseBranch;
        ParseOutcome<Expression> falseOutcome;
        if (!hasElse && isTernaryMissingElseStop(conditionEndLine)) {
            var missingPos = extentEnd();
            falseBranch = new ErrorExpression(CstIssueKind.MISSING, "ternary.else", "", range(missingPos, missingPos));
            falseOutcome = ParseOutcome.missing();
        } else {
            var falseStart = current.startByte();
            falseOutcome = parsePrecedence(Precedence.TERNARY);
            falseBranch = absorbSlot(falseOutcome, falseStart, "ternary.else",
                    "Expected expression after \"else\".");
        }
        var ternaryEnd = Math.max(Math.max(extentEnd(), falseBranch.range().endByte()),
                Math.max(condition.range().endByte(), left.range().endByte()));
        var node = new ConditionalExpression(condition, left, falseBranch, range(extentStart, ternaryEnd));
        return ParseOutcome.structured(node,
                hasElse && !damagedElse && conditionOutcome.hasCompleteValue() && falseOutcome.hasCompleteValue(),
                conditionOutcome instanceof ParseOutcome.EnclosedDamage<Expression>
                        || falseOutcome instanceof ParseOutcome.EnclosedDamage<Expression>);
    }

    /// Parses `expr as Type`, retaining the left operand even when the type is incomplete.
    private @NotNull ParseOutcome<Expression> parseCast(@NotNull Expression left, Precedence entryPrecedence,
                                                        boolean canAssign, int extentStart) {
        return parseTypedInfix(left, true, false, extentStart);
    }

    /// Parses `expr is [not] Type` with the same type recovery as a cast.
    private @NotNull ParseOutcome<Expression> parseTypeTest(@NotNull Expression left, Precedence entryPrecedence,
                                                            boolean canAssign, int extentStart) {
        var negated = check(GdTokenType.NOT);
        var damagedNot = negated && advanceExpressionToken();
        return withExpressionLexicalDamage(parseTypedInfix(left, false, negated, extentStart), damagedNot);
    }

    /// Operators normally inherit the expression slot's owner. When parsed without one, use
    /// the resulting infix node itself so bracket and missing-type diagnostics still have an
    /// owner, even when a usable type could not be built.
    private @NotNull ParseOutcome<Expression> parseTypedInfix(
            @NotNull Expression left, boolean cast, boolean negated, int extentStart
    ) {
        if (recoveryTracker.activeFrame() != null) {
            return parseTypedInfixWithOwner(left, cast, negated, extentStart);
        }
        pushRecoveryOwner("type.argument");
        ParseOutcome<Expression> outcome;
        RecoveryTransactionTracker.ProvisionalFrame frame;
        try {
            outcome = parseTypedInfixWithOwner(left, cast, negated, extentStart);
        } finally {
            frame = popRecoveryOwner();
        }
        bindRecoveryOwner(frame, outcome.valueOrThrow());
        return outcome;
    }

    /// Preserves nonempty type fragments in ordinary infix nodes. A node-less type becomes a
    /// recovery marker; only a missing slot needs an operator-specific diagnostic.
    private @NotNull ParseOutcome<Expression> parseTypedInfixWithOwner(
            @NotNull Expression left, boolean cast, boolean negated, int extentStart
    ) {
        var operatorToken = previous;
        var typeStart = current.startByte();
        var type = parseTypeInternal(false);
        return switch (type) {
            case ParseOutcome.Ok<ParsedType> _, ParseOutcome.EnclosedDamage<ParsedType> _,
                 ParseOutcome.Damaged<ParsedType> _ -> {
                var ref = type.valueOrThrow().ref();
                var span = range(extentStart, ref.range().endByte());
                Expression node = cast ? new CastExpression(left, ref, span)
                        : new TypeTestExpression(left, ref, negated, span);
                // A cast/test requires a valid target type; an invalid child cannot be hidden
                // by the infix node's surface when a parent performs shape validation.
                yield type instanceof ParseOutcome.Ok<ParsedType> ? ParseOutcome.ok(node) : ParseOutcome.damaged(node);
            }
            case ParseOutcome.Missing<ParsedType> _, ParseOutcome.Failed<ParsedType> _ -> {
                var operator = cast ? "as" : negated ? "is not" : "is";
                if (type instanceof ParseOutcome.Missing<ParsedType>) {
                    var code = cast ? DiagnosticCodes.MISSING_TYPE_CAST : DiagnosticCodes.MISSING_TYPE_TYPE_TEST;
                    recoveryTracker.emitError("Expected type specifier after \"%s\".".formatted(operator),
                            tokenRange(operatorToken), code, RecoveryTransactionTracker.AttributionPolicy.CURRENT_FRAME, null);
                }
                var problem = slotMarker(type, typeStart, cast ? "cast.type" : "type_test.type");
                yield ParseOutcome.damaged(
                        new RecoveredInfixExpression(left, operator, problem, range(extentStart, extentEnd())));
            }
        };
    }

    /// Port of `GDScriptParser::parse_assignment` for the whole `=`/`+=`/.../`^=` family. Embedded
    /// assignments (`!canAssign`) emit `ASSIGNMENT_DISALLOWED_IN_EXPRESSION` at the operator;
    /// invalid surface targets (`1 = 2`, `f() = 2`, `obj.foo() = 2`) emit
    /// `ASSIGNMENT_INVALID_TARGET` unless `left` is already a missing-target marker; both still
    /// parse the right-hand side through `absorbSlot("assignment.value")` and return a damaged
    /// `AssignmentExpression` so neither side is discarded.
    private @NotNull ParseOutcome<Expression> parseAssignment(@NotNull Expression left, Precedence entryPrecedence,
                                                              boolean canAssign, int extentStart) {
        var operator = previous;
        var validTarget = isValidAssignmentTarget(left);
        if (!canAssign) {
            recoveryTracker.emitError(
                    "Assignment is not allowed inside an expression.",
                    tokenRange(operator),
                    DiagnosticCodes.ASSIGNMENT_DISALLOWED_IN_EXPRESSION,
                    RecoveryTransactionTracker.AttributionPolicy.CURRENT_FRAME,
                    null
            );
        } else if (!validTarget && !isMissingAssignmentTargetMarker(left)) {
            recoveryTracker.emitError(
                    "Only identifier, attribute access, and subscription access can be used as assignment target.",
                    left.range(),
                    DiagnosticCodes.ASSIGNMENT_INVALID_TARGET,
                    RecoveryTransactionTracker.AttributionPolicy.CURRENT_FRAME,
                    null
            );
        }
        var rightStart = current.startByte();
        var rightOutcome = parseExpression(false);
        var right = absorbSlot(rightOutcome, rightStart, "assignment.value", "Expected an expression after \"=\".");
        var node = new AssignmentExpression(operator.source(), left, right,
                range(extentStart, Math.max(Math.max(extentEnd(), right.range().endByte()), left.range().endByte())));
        return canAssign && validTarget ? propagateOperandDamage(node, rightOutcome) : ParseOutcome.damaged(node);
    }

    private static boolean isValidAssignmentTarget(@NotNull Expression target) {
        return switch (target) {
            case IdentifierExpression _, SubscriptExpression _ -> true;
            case AttributeExpression(_, var steps, _) -> !steps.isEmpty() && (
                    steps.getLast() instanceof AttributePropertyStep
                            || steps.getLast() instanceof AttributeSubscriptStep);
            default -> false;
        };
    }

    private static boolean isMissingAssignmentTargetMarker(@NotNull Expression target) {
        return switch (target) {
            case ErrorExpression _ -> true;
            case AttributeExpression(_, var steps, _) ->
                    !steps.isEmpty() && steps.getLast() instanceof MissingAttributeStep;
            default -> false;
        };
    }

    /// Port of `GDScriptParser::parse_invalid_token` for `?` (GDScript 2.x ternary syntax hint).
    /// Retains the left operand inside a damaged `RecoveredInfixExpression("?", ERROR "?")`.
    private @NotNull ParseOutcome<Expression> parseInvalidToken(@NotNull Expression left, Precedence entryPrecedence,
                                                                boolean canAssign, int extentStart) {
        var questionToken = previous;
        if (questionToken.type() == GdTokenType.QUESTION_MARK) {
            var questionRange = tokenRange(questionToken);
            recoveryTracker.emitError(
                    "Unexpected \"?\" in source. If you want a ternary operator, use \"truthy_value if true_condition else falsy_value\".",
                    questionRange,
                    DiagnosticCodes.SYNTAX_UNEXPECTED_TOKEN,
                    RecoveryTransactionTracker.AttributionPolicy.CURRENT_FRAME,
                    null
            );
            var marker = new ErrorExpression(CstIssueKind.ERROR, DIAGNOSTIC_NODE_TYPE,
                    questionToken.source(), questionRange);
            return ParseOutcome.damaged(
                    new RecoveredInfixExpression(left, "?", marker, range(extentStart, questionToken.endByte())));
        }
        return ParseOutcome.ok(left);
    }

    /// Consumes one contiguous attribute/call/subscript suffix run (`obj.a.b(1).c[2]`) and folds
    /// it into the established AST shapes: a `.`-started run becomes a single
    /// `AttributeExpression(base, steps)`; a call/subscript directly after `.name` folds into the
    /// chain's last step; any other call/subscript wraps the expression built so far in a
    /// `CallExpression`/`SubscriptExpression`.
    ///
    /// Semantically equivalent to Godot's per-infix dispatch (`parse_attribute` / `parse_call` /
    /// `parse_subscript` driven by the rule table) — the run is batched here only so the chain's
    /// steps accumulate in a single list and freeze once at the end, instead of re-copying an
    /// immutable step list per suffix. Equivalence notes:
    /// - Token-level order is unchanged: every grouping token gets its own multiline push before
    ///   consumption and pop before its closer.
    /// - Continuation honors the same precedence check as Godot's infix loop (today it always
    ///   passes, since every caller parses at precedence below `CALL` and the three suffix rules
    ///   are the only infixes at `CALL`/`ATTRIBUTE`/`SUBSCRIPT`; the check keeps the loop honest
    ///   if the rule table ever gains an operator in that range).
    ///
    /// Entered from the Pratt loop with `previous` holding the first suffix token (multiline
    /// already pushed when it was `(` or `[`); subsequent suffix tokens are consumed by this
    /// loop with that same order. `entryPrecedence` is the precedence of the enclosing
    /// `parsePrecedence` call, used by the continuation check above. `canAssign` is accepted for
    /// `InfixRule` uniformity but ignored: indices and arguments always parse with assignment
    /// disallowed, like the vendor call sites in `parse_call`/`parse_subscript`. `extentStart`
    /// is the consumed-span start of the base expression (a grouping `(` can precede the base
    /// node's own range) and stays the left edge of every node the chain produces.
    ///
    /// Damage handling: a missing member name appends a zero-width `MissingAttributeStep` at the
    /// dot's end and the chain continues (the marker acts as a fold barrier — a following
    /// `(`/`[` no longer folds onto the previous property step, so `self.b.(1)` builds
    /// `CallExpression(callee = chain(self, [b, MISSING]))`). Closed calls and subscripts enclose
    /// child damage; absent members, invalid callees and broken delimiters damage this chain.
    /// Both kinds of damage keep folding until a structural, nesting, or lambda guard stops it.
    private @NotNull ParseOutcome<Expression> parseSuffixChain(@NotNull Expression left, Precedence entryPrecedence,
                                                               boolean canAssign, int extentStart) {
        var entryFrame = structuralStack.peekLast();
        var result = left;
        Expression chainBase = null;
        List<AttributeStep> steps = null;
        var damaged = false;
        var hasChildDamage = false;
        while (true) {
            switch (previous.type()) {
                case PERIOD -> {
                    // Member names accept Godot's `is_node_name()` set (nearly all keywords). A
                    // missing name is reported and leaves the marker step behind; if the missing
                    // name crossed into a structural stop outside an unclosed container, yield
                    // without consuming the escaped token.
                    var escaped = isAtUnclosedMultilineStop();
                    if (escaped || !isNodeName(current)) {
                        emitInCurrentFrameOrLegacy("Expected identifier after \".\" for attribute access.",
                                DiagnosticCodes.ATTRIBUTE_MISSING_NAME);
                        if (steps == null) {
                            chainBase = result;
                            steps = new ArrayList<>();
                        }
                        steps.add(new MissingAttributeStep(range(extentEnd(), extentEnd())));
                        damaged = true;
                        if (escaped) {
                            return ParseOutcome.damaged(finishChain(result, chainBase, steps, extentStart));
                        }
                    } else {
                        damaged |= advanceExpressionToken();
                        if (steps == null) {
                            chainBase = result;
                            steps = new ArrayList<>();
                        }
                        steps.add(new AttributePropertyStep(previous.source(), tokenRange(previous)));
                    }
                }
                case PARENTHESIS_OPEN -> {
                    var argumentsOutcome = parseCallArguments();
                    var arguments = argumentsOutcome.valueOrThrow();
                    var end = arguments.isEmpty()
                            ? extentEnd()
                            : Math.max(extentEnd(), arguments.getLast().range().endByte());
                    if (steps != null && steps.getLast() instanceof AttributePropertyStep(var name, var stepRange)) {
                        steps.removeLast();
                        steps.add(new AttributeCallStep(name, arguments,
                                range(stepRange.startByte(), end)));
                    } else {
                        var callee = finishChain(result, chainBase, steps, extentStart);
                        var validDirectCallee = isValidDirectCallee(callee);
                        var missingCallee = isMissingDirectCalleeMarker(callee);
                        if (!validDirectCallee) {
                            if (!missingCallee) {
                                recoveryTracker.emitError(
                                        "Cannot call on an expression. Use \".call()\" if it's a Callable.",
                                        callee.range(),
                                        DiagnosticCodes.CALL_INVALID_CALLEE,
                                        RecoveryTransactionTracker.AttributionPolicy.CURRENT_FRAME,
                                        null
                                );
                            }
                            damaged = true;
                        }
                        result = new CallExpression(callee, arguments,
                                range(extentStart, Math.max(end, callee.range().endByte())));
                        chainBase = null;
                        steps = null;
                    }
                    damaged |= !argumentsOutcome.hasCompleteValue();
                    hasChildDamage |= argumentsOutcome instanceof ParseOutcome.EnclosedDamage<List<Expression>>;
                }
                case BRACKET_OPEN -> {
                    var subscriptFrame = pushBracketedFrame(
                            StructuralRole.SUBSCRIPT,
                            GdTokenType.BRACKET_OPEN,
                            GdTokenType.BRACKET_CLOSE,
                            previous.startByte(),
                            true,
                            true,
                            "subscription index",
                            "Expected \"]\" after subscription index.",
                            DiagnosticCodes.MISSING_CLOSER_SUBSCRIPT,
                            DiagnosticCodes.MISMATCHED_CLOSER_SUBSCRIPT
                    );
                    var indexStart = current.startByte();
                    hasChildDamage |= hasCurrentSlotLexicalErrors();
                    var indexOutcome = parseExpression(false);
                    var index = absorbSlot(indexOutcome, indexStart, "subscript.index",
                            "Expected expression after \"[\".");
                    hasChildDamage |= hasLexicalDamageOnExactCloser(subscriptFrame);
                    damaged |= !consumeCloser(subscriptFrame);
                    var end = Math.max(extentEnd(), index.range().endByte());
                    if (steps != null && steps.getLast() instanceof AttributePropertyStep(var name, var stepRange)) {
                        steps.removeLast();
                        steps.add(new AttributeSubscriptStep(name, List.of(index),
                                range(stepRange.startByte(), end)));
                    } else {
                        var baseExpression = finishChain(result, chainBase, steps, extentStart);
                        result = new SubscriptExpression(baseExpression, List.of(index),
                                range(extentStart, end));
                        chainBase = null;
                        steps = null;
                    }
                    hasChildDamage |= !(indexOutcome instanceof ParseOutcome.Ok<Expression>);
                }
                default -> throw new IllegalStateException(
                        "GDScript parser bug: suffix chain entered on " + previous.type());
            }

            // Continue the chain on the next suffix token, honoring the same precedence cutoff
            // and boundary/budget guards as `parsePrecedence`.
            if (nestingBudgetExceeded
                    || (entryFrame != null && entryFrame.settled)
                    || lambdaEnded
                    || isAtUnclosedMultilineStop()) {
                var node = finishChain(result, chainBase, steps, extentStart);
                return ParseOutcome.structured(node, !damaged, hasChildDamage);
            }
            var nextType = current.type();
            var isSuffix = nextType == GdTokenType.PARENTHESIS_OPEN || nextType == GdTokenType.BRACKET_OPEN
                    || nextType == GdTokenType.PERIOD;
            if (!isSuffix || entryPrecedence.ordinal() > ruleFor(nextType).precedence().ordinal()) {
                var node = finishChain(result, chainBase, steps, extentStart);
                return ParseOutcome.structured(node, !damaged, hasChildDamage);
            }
            if (nextType != GdTokenType.PERIOD) {
                pushMultiline();
            }
            damaged |= advanceExpressionToken();
        }
    }

    /// Freezes an open attribute chain (if any) into a single `AttributeExpression` whose left
    /// edge is the base's consumed-span start (`extentStart`, see `parseSuffixChain`).
    private @NotNull Expression finishChain(@NotNull Expression result, @Nullable Expression chainBase,
                                            @Nullable List<AttributeStep> steps, int extentStart) {
        if (steps == null) {
            return result;
        }
        if (chainBase == null) {
            throw new IllegalStateException("GDScript parser bug: attribute chain without a base.");
        }
        return new AttributeExpression(chainBase, List.copyOf(steps),
                range(extentStart, steps.getLast().range().endByte()));
    }

    private static boolean isValidDirectCallee(@NotNull Expression callee) {
        return switch (callee) {
            case IdentifierExpression _ -> true;
            case AttributeExpression(_, var attrSteps, _) ->
                    !attrSteps.isEmpty() && attrSteps.getLast() instanceof AttributePropertyStep;
            default -> false;
        };
    }

    private static boolean isMissingDirectCalleeMarker(@NotNull Expression callee) {
        return switch (callee) {
            case ErrorExpression _, RecoveredInfixExpression _ -> true;
            case AttributeExpression(_, var attrSteps, _) ->
                    !attrSteps.isEmpty() && attrSteps.getLast() instanceof MissingAttributeStep;
            default -> false;
        };
    }

    /// Port of the argument loop of `GDScriptParser::parse_call` (non-`super` branch): assumes the
    /// opening parenthesis was already consumed and multiline pushed; pops multiline before
    /// consuming the closer. A failed argument keeps a marker at its position (the AST analog of
    /// the vendor's dropped argument, but position-preserving). A real `)` encloses argument
    /// damage; a missing/wrong `)` or broken separator makes the list itself damaged.
    private @NotNull ParseOutcome<List<Expression>> parseCallArguments() {
        var callFrame = pushBracketedFrame(
                StructuralRole.CALL,
                GdTokenType.PARENTHESIS_OPEN,
                GdTokenType.PARENTHESIS_CLOSE,
                previous.startByte(),
                true,
                true,
                "call arguments",
                "Expected closing \")\" after call arguments.",
                DiagnosticCodes.MISSING_CLOSER_CALL,
                DiagnosticCodes.MISMATCHED_CLOSER_CALL
        );
        var arguments = new ArrayList<Expression>();
        var hasChildDamage = false;
        do {
            if (callFrame.settled || nestingBudgetExceeded
                    || (check(GdTokenType.PARENTHESIS_CLOSE) && !hasCurrentSlotLexicalErrors())) {
                // Allow for trailing comma at closer.
                break;
            }
            if (isAtUnclosedMultilineStop()) {
                if (hasCurrentSlotLexicalErrors()) {
                    hasChildDamage = true;
                    arguments.add(claimLexicalErrorSlotValue(previous.endByte(), false));
                }
                break;
            }
            var argumentStart = current.startByte();
            var diagBefore = recoveryTracker.diagnosticCount();
            hasChildDamage |= hasCurrentSlotLexicalErrors();
            var argumentOutcome = parseExpression(false);
            hasChildDamage |= !(argumentOutcome instanceof ParseOutcome.Ok<Expression>);
            arguments.add(absorbSlot(argumentOutcome, argumentStart, "call.argument",
                    "Expected expression as the function argument."));
            if (!advanceSeparatedList(callFrame, argumentStart, previous,
                    recoveryTracker.diagnosticCount() > diagBefore, this::isExpressionStarter)) {
                break;
            }
        } while (true);

        hasChildDamage |= hasLexicalDamageOnExactCloser(callFrame) || callFrame.lexicalDamageBeforeListSeparator;
        var closed = consumeCloser(callFrame);
        return ParseOutcome.structured(List.copyOf(arguments), closed && !callFrame.damagedListSyntax, hasChildDamage);
    }

    private boolean isExpressionStarter() {
        return ruleFor(isIdentifier(current) ? GdTokenType.IDENTIFIER : current.type()).prefix() != null;
    }

    private boolean isFunctionParameterStarter() {
        return check(GdTokenType.IDENTIFIER) || check(GdTokenType.PERIOD_PERIOD_PERIOD);
    }

    private boolean isSignalParameterStarter() {
        return check(GdTokenType.IDENTIFIER);
    }

    private boolean isArrayPatternStarter() {
        return current.type() == GdTokenType.VAR
                || current.type() == GdTokenType.UNDERSCORE
                || current.type() == GdTokenType.PERIOD_PERIOD
                || isExpressionStarter();
    }

    private boolean isDictionaryPatternKeyStarter() {
        return current.type() == GdTokenType.PERIOD_PERIOD || isExpressionStarter();
    }

    /// Bounded list-advancement step shared by comma-delimited containers and parameter lists:
    /// yields on closer/outer boundaries, consumes true commas, inserts at most one zero-width
    /// `MISSING_SEPARATOR_COMMA` when the previous item advanced and the next token is a trusted
    /// item starter on the same logical line or inside an oracle-matched container, and otherwise
    /// skips malformed item tails at balanced local bracket depth without crossing layout/ancestor
    /// boundaries or absorbing cross-line statements from unclosed openers.
    private boolean advanceSeparatedList(
            @NotNull StructuralFrame frame,
            int itemStartExtent,
            @NotNull GdToken itemAnchor,
            boolean itemEmittedDiagnostic,
            @NotNull BooleanSupplier isTrustedNextItemStarter
    ) {
        if (frame.settled || nestingBudgetExceeded || isAtEnd()) {
            return false;
        }
        if (current.type() == frame.expectedCloser
                || (isCloserToken(current.type()) && hasMatchingAncestorCloser(current.type()))
                || current.type() == GdTokenType.DEDENT
                || current.type() == GdTokenType.NEWLINE
                || isAtUnclosedMultilineStop()
                || isMultilineLineHeadIfStatement(current)) {
            return false;
        }
        if (check(GdTokenType.COMMA)) {
            frame.lexicalDamageBeforeListSeparator |= hasLexicalDamageOnAdvance(true);
            advance();
            return !frame.settled && !nestingBudgetExceeded;
        }
        // Let `consumeCloser` handle any remaining unowned closer (single-layer mismatch).
        if (isCloserToken(current.type())) {
            return false;
        }
        if (frame.role == StructuralRole.PARAMETER_LIST
                && (current.type() == GdTokenType.COLON || current.type() == GdTokenType.FORWARD_ARROW)
                && isParameterListUnclosed(frame)) {
            // Signature parameter lists yield immediately to an enclosing `->` return type or `:`
            // body introducer instead of eating them as list garbage.
            return false;
        }
        var sameLogicalLine = current.startLine() <= itemAnchor.endLine() || currentFollowedContinuation;
        var fact = bracketOracle().factFor(frame.openerStartByte);
        var oracleMatched = fact != null && fact.status() == BracketBalanceOracle.OpenerStatus.MATCHED;
        if (!sameLogicalLine && !oracleMatched) {
            // Never turn the next physical line of an unclosed container into a missing-comma item
            // or skip it as malformed tail garbage.
            return false;
        }
        if (extentEnd() > itemStartExtent && isTrustedNextItemStarter.getAsBoolean()) {
            frame.damagedListSyntax = true;
            var point = extentEnd();
            recoveryTracker.emitError(
                    "Expected \",\" between %s.".formatted(frame.constructDescription),
                    range(point, point),
                    DiagnosticCodes.MISSING_SEPARATOR_COMMA,
                    RecoveryTransactionTracker.AttributionPolicy.CURRENT_FRAME,
                    null
            );
            return true;
        }
        frame.damagedListSyntax = true;
        if (!itemEmittedDiagnostic) {
            recoveryTracker.emitError(
                    "Unexpected \"%s\" in %s.".formatted(current.type().displayName(), frame.constructDescription),
                    tokenRange(current),
                    DiagnosticCodes.SYNTAX_UNEXPECTED_TOKEN,
                    RecoveryTransactionTracker.AttributionPolicy.CURRENT_FRAME,
                    null
            );
        }
        var skipLine = current.startLine();
        var localParens = 0;
        var localBrackets = 0;
        var localBraces = 0;
        while (!isAtEnd() && !nestingBudgetExceeded && !frame.settled) {
            if (current.type() == GdTokenType.DEDENT
                    || current.type() == GdTokenType.NEWLINE
                    || isAtUnclosedMultilineStop()
                    || isMultilineLineHeadIfStatement(current)
                    || (frame.role == StructuralRole.PARAMETER_LIST
                            && (current.type() == GdTokenType.COLON || current.type() == GdTokenType.FORWARD_ARROW)
                            && isParameterListUnclosed(frame))
                    || (!oracleMatched && current.startLine() > skipLine && !currentFollowedContinuation)) {
                break;
            }
            if (isCloserToken(current.type())) {
                var matchingLocalOpeners = switch (current.type()) {
                    case PARENTHESIS_CLOSE -> localParens;
                    case BRACKET_CLOSE -> localBrackets;
                    case BRACE_CLOSE -> localBraces;
                    default -> 0;
                };
                if ((localParens == 0 && localBrackets == 0 && localBraces == 0)
                        || (matchingLocalOpeners == 0
                                && (current.type() == frame.expectedCloser || hasMatchingAncestorCloser(current.type())))) {
                    break;
                }
            }
            if (localParens == 0 && localBrackets == 0 && localBraces == 0 && match(GdTokenType.COMMA)) {
                return !frame.settled && !nestingBudgetExceeded;
            }
            switch (current.type()) {
                case PARENTHESIS_OPEN -> localParens++;
                case PARENTHESIS_CLOSE -> {
                    if (localParens > 0) {
                        localParens--;
                    }
                }
                case BRACKET_OPEN -> localBrackets++;
                case BRACKET_CLOSE -> {
                    if (localBrackets > 0) {
                        localBrackets--;
                    }
                }
                case BRACE_OPEN -> localBraces++;
                case BRACE_CLOSE -> {
                    if (localBraces > 0) {
                        localBraces--;
                    }
                }
                default -> {
                }
            }
            advance();
        }
        return false;
    }

    private void emitInCurrentFrameOrLegacy(@NotNull String message, @NotNull String code) {
        if (recoveryTracker.activeFrame() != null) {
            recoveryTracker.emitError(
                    message,
                    tokenRange(previous),
                    code,
                    RecoveryTransactionTracker.AttributionPolicy.CURRENT_FRAME,
                    null
            );
        } else if (isolatedFallbackFrame != null) {
            recoveryTracker.emitError(
                    message,
                    tokenRange(previous),
                    code,
                    RecoveryTransactionTracker.AttributionPolicy.TARGET_FRAME,
                    isolatedFallbackFrame
            );
        } else if (suiteBodyFallbackFrame != null && DiagnosticCodes.allowsNullRecovery(code)) {
            recoveryTracker.emitError(
                    message,
                    tokenRange(previous),
                    code,
                    RecoveryTransactionTracker.AttributionPolicy.TARGET_FRAME,
                    suiteBodyFallbackFrame
            );
        } else if (lambdaBodyFallbackFrame != null && DiagnosticCodes.allowsNullRecovery(code)) {
            recoveryTracker.emitError(
                    message,
                    tokenRange(previous),
                    code,
                    RecoveryTransactionTracker.AttributionPolicy.TARGET_FRAME,
                    lambdaBodyFallbackFrame
            );
        } else {
            pushError(message, code);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Small helpers.
    // ---------------------------------------------------------------------------------------------

    private @NotNull Range range(int startByte, int endByte) {
        return index.rangeOf(startByte, endByte);
    }

    private @NotNull Range tokenRange(GdToken token) {
        return index.rangeOf(token.startByte(), token.endByte());
    }

    private @NotNull String slice(int startByte, int endByte) {
        return new String(sourceBytes, startByte, endByte - startByte, StandardCharsets.UTF_8);
    }

    /// Port of `GDScriptTokenizer::Token::get_debug_name` for the class-body error.
    private static @NotNull String debugName(GdToken token) {
        if (token.type() == GdTokenType.IDENTIFIER) {
            return "identifier \"%s\"".formatted(token.source());
        }
        return "\"%s\"".formatted(token.type().displayName());
    }

    /// Variant type names used by the `extends` literal error, mapped from our literal kinds.
    private static @NotNull String variantTypeName(GdLiteralKind kind) {
        return switch (kind) {
            case INTEGER -> "int";
            case FLOAT -> "float";
            case STRING -> "String";
            case STRING_NAME -> "StringName";
            case NODE_PATH -> "NodePath";
            case TRUE, FALSE -> "bool";
            case NULL -> "Nil";
            case NONE -> "Variant";
        };
    }
}
