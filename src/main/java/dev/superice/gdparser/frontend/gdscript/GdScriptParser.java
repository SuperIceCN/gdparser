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

    private GdTokenizer tokenizer = GdTokenizer.managed("");
    private byte[] sourceBytes = new byte[0];
    private SourceTextIndex index;
    private GdToken current;
    private GdToken previous;
    private final Deque<Boolean> multilineStack = new ArrayDeque<>();
    /// Start byte of the token that opened each multiline frame, parallel to `multilineStack`.
    private final Deque<Integer> multilineStartStack = new ArrayDeque<>();
    /// Explicit structural frames for open delimiters, lambda boundaries, suites, and class bodies.
    private final Deque<StructuralFrame> structuralStack = new ArrayDeque<>();
    /// Number of active ancestor/current frames expecting `)`, `]`, or `}` respectively.
    private final int[] openCloserCounts = new int[3];
    /// Single-step lookahead buffer used when peeking past a `NEWLINE` without consuming it.
    private final Deque<GdToken> tokenQueue = new ArrayDeque<>();
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

    /// Annotations parsed but not yet flushed into a statement sequence. They flush (in source
    /// order) right before the next dispatched statement, and the flushed run that sits on the
    /// same physical line as the statement keyword extends that statement's range, matching the
    /// reference grammar's `annotations` span behavior.
    private final List<AnnotationStatement> pendingAnnotations = new ArrayList<>();
    /// Start byte of the statement currently being dispatched (see {@link #computeStatementStart}).
    private int statementStartByte;
    /// Start byte of the parameter currently being parsed; the signature loops set this because a
    /// variadic `...` marker extends the parameter range to the marker start.
    private int parameterStartByte;
    /// Whether the last `parseStatement` core dispatch produced no statement (Godot's `nullptr`
    /// return). The suite circuit breaker counts this — not the flushed annotation output — so a
    /// failed declaration preceded by annotations still counts, exactly like Godot.
    private boolean lastStatementWasNull;
    /// Whether the script-level `extends` was already used (set by both the standalone and the
    /// same-line `class_name … extends` forms; Godot tracks it on the root class node).
    private boolean scriptExtendsUsed;
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
    private int lineIndentScanSteps;

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
        multilineStack.clear();
        multilineStartStack.clear();
        structuralStack.clear();
        tokenQueue.clear();
        Arrays.fill(openCloserCounts, 0);
        recoveryTracker.reset();
        pendingAnnotations.clear();
        lastStatementWasNull = false;
        scriptExtendsUsed = false;
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
    private GdToken scanSkippingErrors() {
        var token = tokenizer.scan();
        while (token.isError()) {
            recoveryTracker.stageLexicalError(
                    token.errorMessage(),
                    tokenRange(token),
                    DiagnosticCodes.LEXICAL_ERROR
            );
            token = tokenizer.scan();
        }
        return token;
    }

    private @NotNull GdToken nextScannedToken() {
        if (!tokenQueue.isEmpty()) {
            return tokenQueue.removeFirst();
        }
        return scanSkippingErrors();
    }

    private @NotNull GdTokenType peekAfterCurrent() {
        if (tokenQueue.isEmpty()) {
            tokenQueue.addLast(scanSkippingErrors());
        }
        return tokenQueue.getFirst().type();
    }

    /// Port of `GDScriptParser::advance`. Godot soft-fails on EOF (`ERR_FAIL_COND_V_MSG`,
    /// no movement); this port throws instead, so every call site that can legally sit at EOF
    /// must guard with `isAtEnd()` first (all loops ported from Godot already do).
    private void advance() {
        if (current.type() == GdTokenType.EOF) {
            throw new IllegalStateException("GDScript parser bug: trying to advance past the end of stream.");
        }
        // Vendor clears the lambda-end marker on every real token advance (a stale marker would
        // otherwise end a following statement early, e.g. `return` after a lambda argument).
        lambdaEnded = false;
        trailingLambdaEndByte = -1;
        previous = current;
        current = nextScannedToken();
    }

    /// End byte of the consumed span of the expression most recently parsed at this level:
    /// normally the last consumed token's end, but a trailing lambda's body end when the
    /// lambda's internal trailing machinery already consumed the following layout token.
    private int extentEnd() {
        return trailingLambdaEndByte >= 0 ? trailingLambdaEndByte : previous.endByte();
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

    private boolean consume(GdTokenType type, String errorMessage) {
        if (match(type)) {
            return true;
        }
        pushError(errorMessage);
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

    private boolean isVirtualLambdaBoundary() {
        if (current.type() == GdTokenType.COMMA) {
            return hasCommaOwningAncestor();
        }
        if (isCloserToken(current.type())) {
            return hasMatchingAncestorCloser(current.type())
                    || canDeferReplacementCloserToEnclosingDelimiter();
        }
        return current.type() == GdTokenType.DEDENT;
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
        var potentialDedentEscape = suiteBaseline > 0 && !tokenizer.isLineContinuation()
                && current.startByte() < sourceBytes.length
                && current.startLine() > previous.endLine() && isLineStartToken(current)
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
                        token.errorMessage(),
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
        tokenQueue.addFirst(current);
        for (var i = layoutTokens.size() - 1; i >= 1; i--) {
            tokenQueue.addFirst(layoutTokens.get(i));
        }
        current = layoutTokens.getFirst();
    }

    private static GdTokenType matchingCloserFor(GdTokenType opener) {
        return switch (opener) {
            case PARENTHESIS_OPEN -> GdTokenType.PARENTHESIS_CLOSE;
            case BRACKET_OPEN -> GdTokenType.BRACKET_CLOSE;
            case BRACE_OPEN -> GdTokenType.BRACE_CLOSE;
            default -> throw new IllegalArgumentException("Not a bracket opener: " + opener);
        };
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
            if (current.startLine() > rejectionLine && !tokenizer.isLineContinuation()
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
        popStructuralFrame(frame);
        if (current.type() == frame.expectedCloser) {
            if (frame.ownsMultilineFrame) {
                popMultiline(frame.recordCommentSpan, current.startByte());
            }
            advance();
            return true;
        }
        if (isCloserToken(current.type()) && hasMatchingAncestorCloser(current.type())) {
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

    private boolean consumeCloser(@NotNull StructuralFrame frame) {
        return consumeCloser(frame, false);
    }

    /// Port of `GDScriptParser::push_error`: always appends and sets the panic flag; the no-origin
    /// overload reports at `previous`, like Godot.
    private void pushError(String message) {
        pushError(message, previous);
    }

    private void pushError(String message, GdToken origin) {
        pushError(message, origin, null);
    }

    private void pushError(String message, String code) {
        pushError(message, previous, code);
    }

    private void pushError(String message, GdToken origin, @Nullable String code) {
        recoveryTracker.emitLegacyError(message, tokenRange(origin), code);
    }

    /// Marker for an opened expression slot whose parse produced no node: a zero-width MISSING
    /// node at the failure point for `Missing`, otherwise an ERROR fragment over the consumed span
    /// (`Failed` may be zero-consumption when the depth budget rejected the slot before any token
    /// moved, in which case the fragment degenerates to zero-width at `extentEnd()`). Never moves
    /// the cursor; the right edge uses `extentEnd()` like every enclosing node.
    private @NotNull ErrorExpression slotMarker(ParseOutcome<Expression> outcome, int slotStartByte, String slotName) {
        var end = extentEnd();
        return switch (outcome) {
            case ParseOutcome.Missing<Expression> _ ->
                    new ErrorExpression(CstIssueKind.MISSING, slotName, "", range(end, end));
            case ParseOutcome.Failed<Expression> _ -> {
                var start = Math.min(slotStartByte, end);
                yield new ErrorExpression(CstIssueKind.ERROR, DIAGNOSTIC_NODE_TYPE,
                        start < end ? slice(start, end) : "", range(start, end));
            }
            default -> throw new IllegalStateException("slotMarker requires a node-less outcome, got: " + outcome);
        };
    }

    /// Fills an opened expression slot from its outcome: a healthy or damaged node is kept as-is,
    /// a node-less outcome becomes a slot marker. The context message is pushed only for `Missing`
    /// (nothing was reported inside); `Failed`/`Damaged` already carry their root-cause
    /// diagnostics, so a generic message would double-report the same failure.
    private @NotNull Expression absorbSlot(ParseOutcome<Expression> outcome, int slotStartByte, String slotName, String contextMessage) {
        return switch (outcome) {
            case ParseOutcome.Ok<Expression>(var node) -> node;
            case ParseOutcome.Damaged<Expression>(var node) -> node;
            case ParseOutcome.Missing<Expression> _ -> {
                recoveryTracker.emitError(
                        contextMessage,
                        tokenRange(previous),
                        DiagnosticCodes.slotCode(slotName),
                        RecoveryTransactionTracker.AttributionPolicy.CURRENT_FRAME,
                        null
                );
                yield slotMarker(outcome, slotStartByte, slotName);
            }
            case ParseOutcome.Failed<Expression> _ -> slotMarker(outcome, slotStartByte, slotName);
        };
    }

    /// Opens an owner context for a facet that may hold a damaged expression.
    private void pushRecoveryOwner(String facet) {
        recoveryTracker.pushFrame(facet);
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
    private @NotNull List<Statement> drainAnnotationsAndComments(int limitByte) {
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
                    ? pendingAnnotations.getFirst().range().startByte()
                    : Integer.MAX_VALUE;
            if (hasComment && comments.get(commentCursor).startByte() < annotationStart) {
                drained.add(materializeComment(comments.get(commentCursor++)));
            } else {
                drained.add(pendingAnnotations.removeFirst());
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
    private @NotNull List<Statement> drainTailWithAnnotations(int bodyIndent) {
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
                    ? pendingAnnotations.getFirst().range().startByte()
                    : Integer.MAX_VALUE;
            if (hasComment && comments.get(commentCursor).startByte() < annotationStart) {
                drained.add(materializeComment(comments.get(commentCursor++)));
            } else {
                drained.add(pendingAnnotations.removeFirst());
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
                if (isCloserToken(current.type()) && hasMatchingAncestorCloser(current.type())) {
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
                        // Keep scanning.
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

    private void ensureLineMetadataCached(int row) {
        if (cachedLineIndent[row] >= 0) {
            return;
        }
        var offset = index.lineStartByte(row);
        var width = 0;
        while (offset < sourceBytes.length
                && (sourceBytes[offset] == ' ' || sourceBytes[offset] == '\t')) {
            width += sourceBytes[offset] == '\t' ? GdTokenizer.TAB_SIZE : 1;
            offset++;
            lineIndentScanSteps++;
        }
        var firstContent = offset;
        while (firstContent < sourceBytes.length && sourceBytes[firstContent] == '\r') {
            firstContent++;
            lineIndentScanSteps++;
        }
        cachedLineIndent[row] = width;
        cachedLineFirstContentByte[row] = firstContent;
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
            current = nextScannedToken();
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

    /// Port of `GDScriptParser::is_statement_end`: the lambda states count as statement end even
    /// when no end token follows (a lambda body can terminate the enclosing statement).
    private boolean isStatementEnd() {
        return lambdaEnded || inLambda || isStatementEndToken();
    }

    /// Port of `GDScriptParser::end_statement`: consumes a run of NEWLINE/SEMICOLON, stops at EOF
    /// without consuming it, and reports when anything else follows. The lambda states participate
    /// like the vendor's: a pending `lambda_ended` is consumed as a virtual end token, and inside
    /// a lambda any other token marks the lambda as ended (without advancing).
    private void endStatement(String context) {
        var found = false;
        while ((isStatementEnd() || (isCloserToken(current.type()) && !hasMatchingAncestorCloser(current.type())))
                && !isAtEnd()) {
            if (isStatementEndToken()) {
                advance();
                found = true;
            } else if (isCloserToken(current.type()) && !hasMatchingAncestorCloser(current.type())) {
                if (inLambda && canDeferReplacementCloserToEnclosingDelimiter()) {
                    // Defer an enclosing container's mismatched closer to the outer frame rather
                    // than consuming it as a standalone orphan inside the inline lambda body.
                    if (!found) {
                        lambdaEnded = true;
                        found = true;
                    }
                    break;
                }
                advance();
                emitUnexpectedCloser(previous);
            } else if (lambdaEnded) {
                // Consume the virtual token, like the vendor.
                lambdaEnded = false;
                found = true;
                break;
            } else {
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
        if (!found && !isAtEnd()) {
            pushError("Expected end of statement after %s, found \"%s\" instead."
                    .formatted(context, current.type().displayName()));
            markCurrentFramePendingSync();
        }
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
            while (!check(GdTokenType.EOF)) {
                if (match(GdTokenType.ANNOTATION)) {
                    pendingAnnotations.add(parseAnnotation());
                } else if (notStandaloneDocstring()) {
                    break;
                }
            }

            // Second prefix phase: class_name / extends. Their relative order is free; anything else
            // (except further docstrings, kept for compatibility) ends the prefix.
            var canHaveClassOrExtends = true;
            while (canHaveClassOrExtends) {
                switch (current.type()) {
                    case CLASS_NAME -> {
                        advance();
                        if (classNameUsed) {
                            pushError("\"class_name\" can only be used once.");
                        } else {
                            classNameUsed = true;
                            statementStartByte = computeStatementStart(previous);
                            statements.addAll(drainAnnotationsAndComments(current.startByte()));
                            statements.add(parseClassName());
                            statements.addAll(drainTrailingComments());
                        }
                    }
                    case EXTENDS -> {
                        advance();
                        if (scriptExtendsUsed) {
                            pushError("\"extends\" can only be used once.");
                        } else {
                            scriptExtendsUsed = true;
                            statementStartByte = computeStatementStart(previous);
                            statements.addAll(drainAnnotationsAndComments(current.startByte()));
                            statements.add(parseExtendsStatement());
                            endStatement("superclass");
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

            parseClassBody(true, statements, 0);
            // Safety net for anything left over at EOF (e.g. a trailing comment after the last
            // member on a newline-less last line): everything remaining belongs to the top level.
            statements.addAll(drainAnnotationsAndComments(Integer.MAX_VALUE));

            if (!check(GdTokenType.EOF)) {
                pushError("Expected end of file.");
            }
            statements.addAll(drainAnnotations());
            return List.copyOf(statements);
        } finally {
            popStructuralFrame(rootFrame);
        }
    }

    /// Port of `GDScriptParser::parse_class_name` (including the same-line `extends` tail).
    private @NotNull Statement parseClassName() {
        var start = statementStartByte;
        String name = null;
        if (consume(GdTokenType.IDENTIFIER, "Expected identifier for the global class name after \"class_name\".")) {
            name = previous.source();
        }

        String extendsTarget = null;
        var end = previous.endByte();
        if (match(GdTokenType.EXTENDS)) {
            // The same-line extends marks the script-level extends as used (like Godot's root
            // class flag), so a later standalone `extends` line is reported as a duplicate.
            scriptExtendsUsed = true;
            extendsTarget = parseExtendsTarget();
            end = previous.endByte();
            endStatement("superclass");
        } else {
            endStatement("class_name statement");
        }
        return new ClassNameStatement(name, extendsTarget, range(start, end));
    }

    /// Wraps {@link #parseExtendsTarget} into an `ExtendsStatement` spanning from the `extends`
    /// keyword (already consumed) to the target end.
    private @NotNull Statement parseExtendsStatement() {
        var start = statementStartByte;
        var target = parseExtendsTarget();
        var end = previous.endByte();
        return new ExtendsStatement(target, range(start, end));
    }

    /// Port of `GDScriptParser::parse_extends`: a string literal path or an identifier chain
    /// (a literal may also lead a chain after a `.`). Returns the raw source slice of the target,
    /// matching the reference path's trimmed-text extraction.
    private @Nullable String parseExtendsTarget() {
        GdToken firstToken = null;
        if (match(GdTokenType.LITERAL)) {
            firstToken = previous;
            if (previous.literalKind() != GdLiteralKind.STRING) {
                pushError("Only strings or identifiers can be used after \"extends\", found \"%s\" instead."
                        .formatted(variantTypeName(previous.literalKind())));
            }
            if (!match(GdTokenType.PERIOD)) {
                return slice(firstToken.startByte(), previous.endByte());
            }
        }

        if (!consume(GdTokenType.IDENTIFIER, "Expected superclass name after \"extends\".")) {
            return firstToken == null ? null : slice(firstToken.startByte(), previous.endByte());
        }
        if (firstToken == null) {
            firstToken = previous;
        }
        while (match(GdTokenType.PERIOD)) {
            if (!consume(GdTokenType.IDENTIFIER, "Expected superclass name after \".\".")) {
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
                pushError("Expected newline after comment string.");
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
    private void parseClassBody(boolean multiline, List<Statement> statements, int bodyIndent) {
        var classEnd = false;
        var nextIsStatic = false;
        while (!classEnd && !isAtEnd()) {
            var token = current;
            switch (token.type()) {
                case VAR -> {
                    beginMember(statements, nextIsStatic);
                    advance();
                    var variable = parseVariable(nextIsStatic, true);
                    if (variable != null) {
                        statements.add(variable);
                    } else {
                        markCurrentFramePendingSync();
                    }
                }
                case CONST -> {
                    beginMember(statements, false);
                    advance();
                    var constant = parseConstant();
                    if (constant != null) {
                        statements.add(constant);
                    } else {
                        markCurrentFramePendingSync();
                    }
                }
                case SIGNAL -> {
                    beginMember(statements, false);
                    advance();
                    var signal = parseSignal();
                    if (signal != null) {
                        statements.add(signal);
                    } else {
                        markCurrentFramePendingSync();
                    }
                }
                case FUNC -> {
                    beginMember(statements, nextIsStatic);
                    advance();
                    var function = parseFunction(nextIsStatic);
                    if (function != null) {
                        statements.add(function);
                    } else {
                        markCurrentFramePendingSync();
                    }
                }
                case CLASS -> {
                    beginMember(statements, false);
                    advance();
                    statements.add(parseNestedClass());
                }
                case ENUM -> {
                    beginMember(statements, false);
                    advance();
                    statements.add(parseEnum());
                }
                case STATIC -> {
                    beginMember(statements, false);
                    advance();
                    // Only a directly following `func`/`var` keeps the static flag; on any other
                    // token the flag is dropped so the error does not leak into the sibling
                    // declaration that synchronization recovers to (Godot keeps the flag there,
                    // which mislabels the recovered sibling — a recovery-only refinement).
                    nextIsStatic = check(GdTokenType.FUNC) || check(GdTokenType.VAR);
                    if (!nextIsStatic) {
                        pushError("Expected \"func\" or \"var\" after \"static\".");
                        markCurrentFramePendingSync();
                    }
                }
                case ANNOTATION -> {
                    advance();
                    pendingAnnotations.add(parseAnnotation());
                }
                case PASS -> {
                    beginMember(statements, false);
                    advance();
                    statements.add(new PassStatement(range(statementStartByte, previous.endByte())));
                    endStatement("\"pass\"");
                }
                case DEDENT -> {
                    if (bodyIndent > 0) {
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
            statements.addAll(drainTailWithAnnotations(bodyIndent));
        } else {
            statements.addAll(drainAnnotations());
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
        pushError(message);
        markCurrentFramePendingSync();
    }

    /// Computes the statement start (unless a `static` prefix already did so for this member)
    /// and flushes pending annotations/comments into the sequence in byte order. The flush runs
    /// even after `static` so a comment between `static` and the member keeps its position.
    private void beginMember(List<Statement> statements, boolean alreadyStarted) {
        if (!alreadyStarted) {
            statementStartByte = computeStatementStart(current);
        }
        statements.addAll(drainAnnotationsAndComments(current.startByte()));
    }

    /// The statement starts at the keyword, unless a contiguous run of pending annotations sits
    /// on the same physical line as the keyword — then it starts at the earliest such annotation
    /// (the reference grammar spans same-line annotations into the statement node).
    private int computeStatementStart(GdToken keyword) {
        var start = keyword.startByte();
        var keywordRow = keyword.startLine() - 1;
        for (var i = pendingAnnotations.size() - 1; i >= 0; i--) {
            var annotation = pendingAnnotations.get(i);
            if (annotation.range().startPoint().row() != keywordRow) {
                break;
            }
            start = annotation.range().startByte();
        }
        return start;
    }

    private List<AnnotationStatement> drainAnnotations() {
        if (pendingAnnotations.isEmpty()) {
            return List.of();
        }
        var drained = List.copyOf(pendingAnnotations);
        pendingAnnotations.clear();
        return drained;
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
        if (consume(GdTokenType.IDENTIFIER, "Expected identifier for the class name after \"class\".")) {
            name = previous.source();
        }

        String extendsTarget = null;
        if (match(GdTokenType.EXTENDS)) {
            extendsTarget = parseExtendsTarget();
        }

        consume(GdTokenType.COLON, "Expected \":\" after class declaration.");
        var headerEnd = previous.endByte();

        var multiline = match(GdTokenType.NEWLINE);
        int blockStart;
        if (multiline) {
            // A comment trailing the class-header line (`class Inner: # c`) is dropped.
            var commentEnd = discardTrailingCommentsBefore(previous.startByte());
            blockStart = layoutBlockStart(previous, Math.max(headerEnd, commentEnd));
            if (!consume(GdTokenType.INDENT, "Expected indented block after class declaration.")) {
                return new ClassDeclaration(name, extendsTarget,
                        new Block(List.of(), range(blockStart, blockStart)),
                        range(start, Math.max(blockStart, headerEnd)));
            }
        } else {
            blockStart = current.startByte();
        }

        if (match(GdTokenType.EXTENDS)) {
            if (extendsTarget != null) {
                pushError("Cannot use \"extends\" more than once in the same class.");
            }
            extendsTarget = parseExtendsTarget();
            endStatement("superclass");
        }

        var bodyStatements = new ArrayList<Statement>();
        var bodyIndent = multiline ? indentOf(current.startByte()) : 0;
        var bodyLayoutBaseline = multiline && !check(GdTokenType.DEDENT) && !isAtEnd()
                ? lineIndentOf(current.startByte())
                : 0;
        var classFrame = pushStructuralFrame(StructuralFrame.nonBracketed(
                StructuralRole.CLASS_BODY, start, bodyLayoutBaseline, multiline, false, "class body"));
        try {
            parseClassBody(multiline, bodyStatements, bodyIndent);
        } finally {
            popStructuralFrame(classFrame);
        }
        var blockEnd = bodyStatements.isEmpty()
                ? blockStart
                : bodyStatements.getLast().range().endByte();
        var body = new Block(List.copyOf(bodyStatements), range(blockStart, blockEnd));

        var end = Math.max(blockEnd, headerEnd);
        if (multiline) {
            consume(GdTokenType.DEDENT, "Missing unindent at the end of the class body.");
        }
        return new ClassDeclaration(name, extendsTarget, body, range(start, end));
    }

    // ---------------------------------------------------------------------------------------------
    // Suites and statements — port of parse_suite / parse_statement (declaration-scoped subset).
    // ---------------------------------------------------------------------------------------------

    /// Port of `GDScriptParser::parse_suite`: multi-line (NEWLINE + INDENT … DEDENT) or single-line
    /// (semicolon-separated) statement block. Failed statements are skipped; only those count
    /// toward the circuit breaker (`error_count++ > 100`, i.e. the 102nd failure aborts the
    /// suite) — `push_error` alone does not count.
    private @NotNull Block parseSuite(String context) {
        return parseSuite(context, false);
    }

    /// `forLambda` mirrors the vendor's `p_for_lambda`: the loop also stops when a lambda-end is
    /// pending, the closing DEDENT becomes optional in that case, and `lambdaEnded` is raised when
    /// the suite finishes so the enclosing statement ends without a newline token.
    private @NotNull Block parseSuite(String context, boolean forLambda) {
        // Depth guard: nested control-flow suites recurse via parseStatement. The rejected
        // suite's tokens are not consumed here; the poisoned synchronize() pass that follows
        // (triggered by the enclosing statement's panic-mode tail or the match loop head)
        // consumes the whole region layout-aware.
        if (!enterNestingLevel()) {
            return new Block(List.of(), range(current.startByte(), current.startByte()));
        }
        try {
            return parseSuiteImpl(context, forLambda);
        } finally {
            exitNestingLevel();
        }
    }

    private @NotNull Block parseSuiteImpl(String context, boolean forLambda) {
        var headerEnd = previous != null ? previous.endByte() : 0;
        var multiline = match(GdTokenType.NEWLINE);
        int blockStart;
        var suiteIndent = -1;
        if (multiline) {
            // A comment trailing the suite-header line (`if x: # c`) is dropped by both paths.
            var commentEnd = discardTrailingCommentsBefore(previous.startByte());
            // The reference path spans the body from right after the header content: trailing
            // whitespace before the newline is included, a trailing comment is not.
            blockStart = layoutBlockStart(previous, Math.max(headerEnd, commentEnd));
            if (!consume(GdTokenType.INDENT, "Expected indented block after %s.".formatted(context))) {
                return new Block(List.of(), range(blockStart, blockStart));
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
        var suiteFrame = pushStructuralFrame(StructuralFrame.nonBracketed(
                StructuralRole.SUITE, blockStart, suiteLayoutBaseline, multiline, false, context));
        var statements = new ArrayList<Statement>();
        var errorCount = 0;
        try {
            do {
                if (isAtEnd() || (!multiline && previous.type() == GdTokenType.SEMICOLON && check(GdTokenType.NEWLINE))) {
                    break;
                }
                var produced = parseStatement();
                if (lastStatementWasNull) {
                    if (errorCount++ > 100) {
                        pushError("Too many statement errors.");
                        break;
                    }
                    continue;
                }
                statements.addAll(produced);
            } while ((multiline || previous.type() == GdTokenType.SEMICOLON)
                    && !check(GdTokenType.DEDENT)
                    && !lambdaEnded
                    && !isAtEnd());
        } finally {
            popStructuralFrame(suiteFrame);
        }

        if (multiline) {
            if (!lambdaEnded) {
                consume(GdTokenType.DEDENT, "Missing unindent at the end of %s.".formatted(context));
            } else {
                match(GdTokenType.DEDENT);
            }
        } else if (previous.type() == GdTokenType.SEMICOLON) {
            consume(GdTokenType.NEWLINE, "Expected newline after \";\" at the end of %s.".formatted(context));
        }
        if (forLambda) {
            lambdaEnded = true;
        }

        // Comments in the tail window before the closing dedent belong to this suite only when
        // they are indented into it; dangling annotations flush into the suite's own sequence
        // instead of escaping to an outer member (merged in byte order).
        if (multiline && suiteIndent >= 0) {
            statements.addAll(drainTailWithAnnotations(suiteIndent));
        } else {
            statements.addAll(drainAnnotations());
        }
        var blockEnd = statements.isEmpty() ? blockStart : statements.getLast().range().endByte();
        return new Block(List.copyOf(statements), range(blockStart, blockEnd));
    }

    /// Discard path of the expression-statement dispatch for outcomes that already carry their
    /// root-cause diagnostic (`Failed`, or a `Damaged` expression not sitting at a statement
    /// boundary): inside a lambda the failure ends the lambda without consuming the token;
    /// outside, exactly one token is consumed (EOF-guarded, mirroring the vendor's soft-failing
    /// `advance()`) so the following `synchronize()` keeps its usual boundary. No
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

    /// Three-way handling of the return-value slot outcome (S1): `Missing` keeps the slot absent
    /// without adding a diagnostic (a following garbage token is still reported by
    /// `endStatement`/`synchronize`); `Failed` fills an ERROR fragment over the consumed span;
    /// `Ok`/`Damaged` keep the node.
    private @Nullable Expression returnSlotValue(ParseOutcome<Expression> outcome, int slotStartByte) {
        return switch (outcome) {
            case ParseOutcome.Ok<Expression>(var node) -> node;
            case ParseOutcome.Damaged<Expression>(var node) -> node;
            case ParseOutcome.Missing<Expression> _ -> null;
            case ParseOutcome.Failed<Expression> _ -> slotMarker(outcome, slotStartByte, "return.value");
        };
    }

    /// Port of `GDScriptParser::parse_statement` for the declaration-scoped subset: `pass`, local
    /// `var`/`const`, statement annotations, and expression statements built from the available
    /// expression subset. Returns the flushed annotations plus the parsed statement; an empty list
    /// mirrors Godot's `nullptr` return (counted by the suite's circuit breaker).
    private @NotNull List<Statement> parseStatement() {
        // A construct aborted on the nesting budget upstream poisons the current token region:
        // skip straight to synchronization (which also lifts the poison flag) instead of
        // letting partial constructs interpret the leftover tokens.
        if (nestingBudgetExceeded) {
            lastStatementWasNull = true;
            synchronize();
            return List.of();
        }
        // Statement-level annotations are collected and attach to the next real statement; the
        // annotation-only result is empty like Godot's null return.
        if (current.type() == GdTokenType.ANNOTATION) {
            advance();
            pendingAnnotations.add(parseAnnotation());
            // An annotation-only round produces no statement (Godot returns `nullptr` here too).
            lastStatementWasNull = true;
            if (consumeCurrentFramePendingSync() || nestingBudgetExceeded) {
                synchronize();
            }
            return List.of();
        }

        lastStatementWasNull = false;
        statementStartByte = computeStatementStart(current);
        var produced = new ArrayList<>(drainAnnotationsAndComments(current.startByte()));
        Statement result;
        switch (current.type()) {
            case PASS -> {
                advance();
                result = new PassStatement(range(statementStartByte, previous.endByte()));
                endStatement("\"pass\"");
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
                RecoveryTransactionTracker.ProvisionalFrame valueFrame = null;
                if (!isStatementEnd()) {
                    // Ported vendor check: a constructor (`_init`/`_static_init`) may not
                    // return a value. The error is reported before parsing the value, like Godot.
                    if ("_init".equals(currentFunctionName) || "_static_init".equals(currentFunctionName)) {
                        pushError("Constructor cannot return a value.");
                    }
                    var valueStart = current.startByte();
                    pushRecoveryOwner("return.value");
                    var valueOutcome = parseExpression(false);
                    valueFrame = popRecoveryOwner();
                    returnValue = returnSlotValue(valueOutcome, valueStart);
                } else if (inLambda && !isStatementEndToken()) {
                    // Inside a lambda `isStatementEnd()` is always true, so a value is attempted
                    // whenever a real token follows (vendor `parse_statement` RETURN branch).
                    var valueStart = current.startByte();
                    pushRecoveryOwner("return.value");
                    var valueOutcome = parseExpression(false);
                    valueFrame = popRecoveryOwner();
                    returnValue = returnSlotValue(valueOutcome, valueStart);
                }
                result = new ReturnStatement(returnValue, range(statementStartByte, extentEnd()));
                if (valueFrame != null) {
                    bindRecoveryOwner(valueFrame, result);
                }
                endStatement("return statement");
            }
            case BREAK -> {
                advance();
                if (!canBreak) {
                    pushError("Cannot use \"break\" outside of a loop.");
                }
                result = new BreakStatement(range(statementStartByte, previous.endByte()));
                endStatement("\"break\"");
            }
            case CONTINUE -> {
                advance();
                if (!canContinue) {
                    pushError("Cannot use \"continue\" outside of a loop.");
                }
                result = new ContinueStatement(range(statementStartByte, previous.endByte()));
                endStatement("\"continue\"");
            }
            case BREAKPOINT -> {
                advance();
                result = new BreakpointStatement(range(statementStartByte, previous.endByte()));
                endStatement("\"breakpoint\"");
            }
            case ASSERT -> {
                advance();
                result = parseAssert();
            }
            case FUNC -> {
                advance();
                result = (Statement) parseLambdaLike(true);
                // Like the vendor's expression-statement path: consume the lambda-end state so
                // the surrounding suite keeps parsing following statements.
                endStatement("expression");
            }
            default -> {
                // Only statement-level expressions may contain assignments (Godot's
                // `parse_expression(true)` call site in `parse_expression_statement`).
                pushRecoveryOwner("expression");
                var expressionOutcome = parseExpression(true);
                var expressionFrame = popRecoveryOwner();
                switch (expressionOutcome) {
                    case ParseOutcome.Ok<Expression>(var expression) -> {
                        // The statement ends at the last consumed token, not the expression node's
                        // own end (a trailing grouping `)` belongs to the consumed span).
                        result = new ExpressionStatement(expression, range(statementStartByte, extentEnd()));
                        bindRecoveryOwner(expressionFrame, result);
                        endStatement("expression");
                    }
                    case ParseOutcome.Damaged<Expression>(var expression) -> {
                        if (isStatementEndToken() || lambdaEnded || (inLambda && isVirtualLambdaBoundary())) {
                            // A damaged expression survives only at a real statement boundary (or
                            // an ancestor-owned inline lambda boundary); `isStatementEnd()` cannot
                            // gate this because it is always true inside a lambda.
                            result = new ExpressionStatement(expression, range(statementStartByte, extentEnd()));
                            bindRecoveryOwner(expressionFrame, result);
                            endStatement("expression");
                        } else {
                            discardFailedStatement();
                            result = null;
                        }
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
                        } else {
                            if (!isAtEnd()) {
                                advance();
                            }
                            if (isCloserToken(previous.type())
                                    && (!inLambda || !hasMatchingAncestorCloser(previous.type()))) {
                                emitUnexpectedCloser(previous);
                            } else {
                                pushError("Expected statement, found \"%s\" instead.".formatted(previous.type().displayName()));
                            }
                            markCurrentFramePendingSync();
                        }
                        result = null;
                    }
                }
            }
        }

        lastStatementWasNull = result == null;
        if (result != null) {
            produced.add(result);
        }
        // A comment trailing the statement's own line lands right after it.
        produced.addAll(drainTrailingComments());
        if (consumeCurrentFramePendingSync() || nestingBudgetExceeded) {
            synchronize();
        }
        return List.copyOf(produced);
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
        // Pop before the suite runs so closer diagnostics inside the body cannot mis-bind to the
        // header facet; the stashed frame binds once the statement is built.
        var conditionFrame = popRecoveryOwner();
        if (!match(GdTokenType.COLON)) {
            pushError("Expected \":\" after \"if\" condition, found \"%s\" instead."
                    .formatted(current.type().displayName()));
        }
        var trueBlock = parseSuite("\"if\" block");

        var elifClauses = new ArrayList<ElifClause>();
        Block elseBody = null;
        while (match(GdTokenType.ELIF)) {
            var elifStart = previous.startByte();
            var elifConditionStart = current.startByte();
            pushRecoveryOwner("elif.condition");
            var elifConditionOutcome = parseExpression(false);
            var elifCondition = absorbSlot(elifConditionOutcome, elifConditionStart, "elif.condition",
                    "Expected conditional expression after \"elif\".");
            var elifConditionFrame = popRecoveryOwner();
            if (!match(GdTokenType.COLON)) {
                pushError("Expected \":\" after \"elif\" condition, found \"%s\" instead."
                        .formatted(current.type().displayName()));
            }
            var elifBody = parseSuite("\"elif\" block");
            // A damaged elif clause survives with its marker condition and healthy body.
            var clause = new ElifClause(elifCondition, elifBody,
                    range(elifStart, elifBody.range().endByte()));
            bindRecoveryOwner(elifConditionFrame, clause);
            elifClauses.add(clause);
        }
        if (match(GdTokenType.ELSE)) {
            consume(GdTokenType.COLON, "Expected \":\" after \"else\".");
            elseBody = parseSuite("\"else\" block");
        }

        var end = elseBody != null ? elseBody.range().endByte()
                : !elifClauses.isEmpty() ? elifClauses.getLast().body().range().endByte()
                  : trueBlock.range().endByte();
        var statement = new IfStatement(condition, trueBlock, List.copyOf(elifClauses), elseBody,
                range(start, end));
        bindRecoveryOwner(conditionFrame, statement);
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
        var conditionFrame = popRecoveryOwner();
        if (!match(GdTokenType.COLON)) {
            pushError("Expected \":\" after \"while\" condition, found \"%s\" instead."
                    .formatted(current.type().displayName()));
        }

        var couldBreak = canBreak;
        var couldContinue = canContinue;
        canBreak = true;
        canContinue = true;
        var body = parseSuite("\"while\" block");
        canBreak = couldBreak;
        canContinue = couldContinue;

        var statement = new WhileStatement(condition, body, range(start, body.range().endByte()));
        bindRecoveryOwner(conditionFrame, statement);
        return statement;
    }

    /// Port of `GDScriptParser::parse_for`: optional `: type` after the loop variable (the two
    /// distinct `in` error messages depend on whether a type was given), iterable expression, and
    /// a loop suite with break/continue enabled. A failed iterable keeps a marker in the
    /// surviving statement (vendor keeps the node with a null iterable); a missing loop variable
    /// name still drops the statement (no node can carry the failure).
    private @Nullable Statement parseFor() {
        var start = statementStartByte;
        var hasIterator = consume(GdTokenType.IDENTIFIER, "Expected loop variable name after \"for\".");
        var iterator = hasIterator ? previous.source() : "";

        TypeRef iteratorType = null;
        RecoveryTransactionTracker.ProvisionalFrame iteratorTypeFrame = null;
        if (match(GdTokenType.COLON)) {
            pushRecoveryOwner("for.iterator");
            iteratorType = parseType(false);
            iteratorTypeFrame = popRecoveryOwner();
            if (iteratorType == null) {
                pushError("Expected type specifier after \":\".");
            }
        }

        if (iteratorType == null) {
            consume(GdTokenType.IN, "Expected \"in\" or \":\" after \"for\" variable name.");
        } else {
            consume(GdTokenType.IN, "Expected \"in\" after \"for\" variable type specifier.");
        }

        var iterableStart = current.startByte();
        pushRecoveryOwner("for.iterable");
        var iterableOutcome = parseExpression(false);
        var iterable = absorbSlot(iterableOutcome, iterableStart, "for.iterable",
                "Expected iterable after \"in\".");
        var iterableFrame = popRecoveryOwner();
        if (!match(GdTokenType.COLON)) {
            pushError("Expected \":\" after \"for\" condition, found \"%s\" instead."
                    .formatted(current.type().displayName()));
        }

        var couldBreak = canBreak;
        var couldContinue = canContinue;
        canBreak = true;
        canContinue = true;
        var body = parseSuite("\"for\" block");
        canBreak = couldBreak;
        canContinue = couldContinue;

        if (!hasIterator) {
            if (iteratorTypeFrame != null) {
                recoveryTracker.abandon(iteratorTypeFrame);
            }
            recoveryTracker.abandon(iterableFrame);
            return null;
        }
        var statement = new ForStatement(iterator, iteratorType, iterable, body,
                range(start, body.range().endByte()));
        if (iteratorTypeFrame != null) {
            bindRecoveryOwner(iteratorTypeFrame, statement);
        }
        bindRecoveryOwner(iterableFrame, statement);
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
            pushError("Expected \"(\" after \"assert\".");
        }
        // Covers the whole argument region: the assert's own missing-`)` diagnostic lands here
        // once the condition/message frames have been popped.
        pushRecoveryOwner("assert.arguments");

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
                && match(GdTokenType.COMMA)
                && !check(GdTokenType.PARENTHESIS_CLOSE)
                && !isAtUnclosedMultilineStop()) {
            var messageStart = current.startByte();
            pushRecoveryOwner("assert.message");
            var messageOutcome = parseExpression(false);
            message = absorbSlot(messageOutcome, messageStart, "assert.message",
                    "Expected error message for assert after \",\".");
            messageFrame = popRecoveryOwner();
            if ((assertFrame == null || !assertFrame.settled) && !nestingBudgetExceeded) {
                match(GdTokenType.COMMA);
            }
        }

        if (assertFrame != null) {
            consumeCloser(assertFrame);
        }
        var argumentsFrame = popRecoveryOwner();
        var statement = new AssertStatement(condition, message, range(start, extentEnd()));
        bindRecoveryOwner(conditionFrame, statement);
        if (messageFrame != null) {
            bindRecoveryOwner(messageFrame, statement);
        }
        bindRecoveryOwner(argumentsFrame, statement);
        endStatement("\"assert\"");
        return statement;
    }

    // ---------------------------------------------------------------------------------------------
    // Match statement and patterns — ports of parse_match / parse_match_branch /
    // parse_match_pattern. Patterns are represented with the existing expression AST (the
    // reference path's contract): literals/identifiers/general expressions as-is, `var x` as
    // `PatternBindingExpression`, `_` as a plain identifier, containers as array/dictionary
    // expressions with `openEnded` carrying the `..` marker.
    // ---------------------------------------------------------------------------------------------

    /// One parsed match pattern: an expression node, or the `..` rest marker (only legal inside
    /// array/dictionary patterns, where it turns into `openEnded`). A `null` result means the
    /// pattern failed (vendor's `nullptr`, skipped by the branch's comma loop).
    private record MatchPattern(@Nullable Expression expression, boolean rest) {
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
        var valueFrame = popRecoveryOwner();
        consume(GdTokenType.COLON, "Expected \":\" after \"match\" expression.");
        if (match(GdTokenType.NEWLINE)) {
            // A comment trailing the match-header line is dropped by both paths.
            discardTrailingCommentsBefore(previous.startByte());
        } else {
            pushError("Expected a newline after \"match\" statement.");
        }

        var sections = new ArrayList<MatchSection>();
        // Accepted `@warning_ignore` annotations pending a branch to attach to; this AST drops
        // them (branches have no annotation field), but leftover ones are still diagnosed.
        var danglingAnnotations = new ArrayList<AnnotationStatement>();
        var matchIndent = -1;
        if (!consume(GdTokenType.INDENT, "Expected an indented block after \"match\" statement.")) {
            // Vendor returns immediately on a missing indented block: no DEDENT consumption (an
            // outer suite's DEDENT must not be eaten) and no comment discard.
            var early = new MatchStatement(value, List.of(), range(start, previous.endByte()));
            bindRecoveryOwner(valueFrame, early);
            return early;
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
                if (match(GdTokenType.PASS)) {
                    consume(GdTokenType.NEWLINE, "Expected newline after \"pass\".");
                    continue;
                }
                if (match(GdTokenType.ANNOTATION)) {
                    var annotation = parseAnnotation();
                    // Only `@warning_ignore` may annotate a branch (Godot attaches it to the
                    // branch node, which has no annotation field here); anything else is an error
                    // and is not tracked (vendor skips it the same way).
                    if (annotation.name().equals("warning_ignore")) {
                        danglingAnnotations.add(annotation);
                    } else {
                        pushError("Annotation \"@%s\" is not allowed in this level."
                                .formatted(annotation.name()));
                    }
                    continue;
                }
                sections.add(parseMatchSection());
                danglingAnnotations.clear();
            }
        } finally {
            popStructuralFrame(matchBodyFrame);
        }
        // The statement ends at the last branch (before the closing DEDENT), not at the DEDENT
        // the last branch's suite consumed — the reference path's extent ends there too.
        var end = sections.isEmpty() ? previous.endByte() : sections.getLast().range().endByte();
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
            pushError("Annotation \"@%s\" does not precede a valid target, so it will have no effect."
                    .formatted(dangling.name()));
        }
        consume(GdTokenType.DEDENT, "Expected an indented block after \"match\" statement.");

        var statement = new MatchStatement(value, List.copyOf(sections), range(start, end));
        bindRecoveryOwner(valueFrame, statement);
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
                pushError("Rest pattern can only be used inside array and dictionary patterns.");
            } else {
                patterns.add(pattern.expression());
            }
        } while (!nestingBudgetExceeded && match(GdTokenType.COMMA));
        var patternFrame = popRecoveryOwner();

        if (!sawAnyPattern) {
            pushError("No pattern found for \"match\" branch.");
        }

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

        Block body;
        if (!consume(GdTokenType.COLON, hasGuard
                ? "Expected \":\" after \"match\" pattern guard."
                : "Expected \":\" or \"when\" after \"match\" patterns.")) {
            // Vendor recovery: consume the rest of the line; the next line starts a new branch.
            while (current.type() != GdTokenType.NEWLINE && !isAtEnd()) {
                advance();
            }
            if (!isAtEnd()) {
                advance();
            }
            body = new Block(List.of(), range(start, previous.endByte()));
        } else {
            body = parseSuite("match pattern block");
        }
        // The section's left edge extends over its patterns: a zero-consumption pattern marker
        // anchors at `extentEnd()`, which can precede the section start on a follow-up branch
        // (`previous` is then the preceding branch's newline, not this line's content).
        var sectionStart = start;
        for (var pattern : patterns) {
            sectionStart = Math.min(sectionStart, pattern.range().startByte());
        }
        var section = new MatchSection(List.copyOf(patterns), guard, body,
                range(sectionStart, body.range().endByte()));
        bindRecoveryOwner(patternFrame, section);
        if (guardFrame != null) {
            bindRecoveryOwner(guardFrame, section);
        }
        return section;
    }

    /// Port of `GDScriptParser::parse_match_pattern`: `var` binds, `_` wildcard, `..` rest, and
    /// nested array/dictionary patterns get dedicated handling; anything else parses as a general
    /// expression (`false` — no assignments in patterns). A failed expression pattern keeps a
    /// marker in the pattern (the vendor's `nullptr` skip would lose the position); a `var` bind
    /// without a name still drops the pattern (no node can carry the nameless failure).
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

    private @Nullable MatchPattern parseMatchPatternImpl() {
        switch (current.type()) {
            case VAR -> {
                var start = current.startByte();
                advance();
                if (!consume(GdTokenType.IDENTIFIER, "Expected bind name after \"var\".")) {
                    return null;
                }
                return new MatchPattern(new PatternBindingExpression(previous.source(),
                        range(start, previous.endByte())), false);
            }
            case UNDERSCORE -> {
                advance();
                return new MatchPattern(new IdentifierExpression("_",
                        range(previous.startByte(), previous.endByte())), false);
            }
            case PERIOD_PERIOD -> {
                advance();
                return new MatchPattern(null, true);
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
                return new MatchPattern(expression, false);
            }
        }
    }

    /// Array pattern: sub-patterns recurse, `..` is legal once at the end (further elements are
    /// errors but still collected, like the vendor).
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
        var openEnded = false;
        do {
            if (arrayPatternFrame.settled || nestingBudgetExceeded
                    || isAtEnd() || check(GdTokenType.BRACKET_CLOSE)
                    || isAtUnclosedMultilineStop()) {
                break;
            }
            var sub = parseMatchPattern();
            if (sub == null) {
                if (arrayPatternFrame.settled || nestingBudgetExceeded) {
                    break;
                }
                continue;
            }
            if (openEnded) {
                pushError("The \"..\" pattern must be the last element in the pattern array.");
                if (!sub.rest()) {
                    elements.add(sub.expression());
                }
            } else if (sub.rest()) {
                openEnded = true;
            } else {
                elements.add(sub.expression());
            }
        } while (!arrayPatternFrame.settled && !nestingBudgetExceeded && match(GdTokenType.COMMA));

        consumeCloser(arrayPatternFrame);
        return new MatchPattern(new ArrayExpression(List.copyOf(elements), openEnded,
                range(start, Math.max(start, extentEnd()))), false);
    }

    /// Dictionary pattern: `key: pattern` entries, the `..` rest marker, and healthy key-only
    /// entries (`DictEntry(key, null, range)` with zero diagnostics). A failed key keeps a marker
    /// in its entry when a `: sub-pattern` carrier survives; when no carrier exists (no colon,
    /// a dropped sub-pattern, or a `..`-misuse branch that drops the entry), the damaged key would
    /// be homeless, so the whole dictionary pattern degrades to an ERROR fragment covering the
    /// consumed prefix.
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
        var openEnded = false;
        var homelessDamagedKey = false;
        do {
            if (dictPatternFrame.settled || nestingBudgetExceeded
                    || check(GdTokenType.BRACE_CLOSE) || isAtEnd()
                    || isAtUnclosedMultilineStop()) {
                break;
            }
            if (match(GdTokenType.PERIOD_PERIOD)) {
                if (openEnded) {
                    pushError("The \"..\" pattern must be the last element in the pattern dictionary.");
                } else {
                    openEnded = true;
                }
                continue;
            }
            var keyStart = current.startByte();
            var keyOutcome = parseExpression(false);
            if (dictPatternFrame.settled || nestingBudgetExceeded) {
                break;
            }
            var key = absorbSlot(keyOutcome, keyStart, "dictionary_pattern.key",
                    "Expected expression as key for dictionary pattern.");
            var keyDamaged = !(keyOutcome instanceof ParseOutcome.Ok<Expression>);
            if (match(GdTokenType.COLON)) {
                var sub = parseMatchPattern();
                if (sub == null) {
                    homelessDamagedKey |= keyDamaged;
                    if (dictPatternFrame.settled || nestingBudgetExceeded) {
                        break;
                    }
                    continue;
                }
                if (openEnded) {
                    pushError("The \"..\" pattern must be the last element in the pattern dictionary.");
                    homelessDamagedKey |= keyDamaged;
                } else if (sub.rest()) {
                    pushError("The \"..\" pattern cannot be used as a value.");
                    homelessDamagedKey |= keyDamaged;
                } else {
                    entries.add(new DictEntry(key, sub.expression(),
                            range(Math.min(keyStart, key.range().startByte()),
                                    Objects.requireNonNull(sub.expression()).range().endByte())));
                }
            } else if (keyDamaged) {
                // A damaged key without a `: value` carrier has no representable home.
                homelessDamagedKey = true;
            } else {
                entries.add(new DictEntry(key, null,
                        range(Math.min(keyStart, key.range().startByte()),
                                Math.max(key.range().endByte(), extentEnd()))));
            }
        } while (!dictPatternFrame.settled && !nestingBudgetExceeded && match(GdTokenType.COMMA));

        consumeCloser(dictPatternFrame);
        if (homelessDamagedKey) {
            var end = extentEnd();
            return new MatchPattern(new ErrorExpression(CstIssueKind.ERROR, DIAGNOSTIC_NODE_TYPE,
                    slice(start, end), range(start, end)), false);
        }
        return new MatchPattern(new DictionaryExpression(List.copyOf(entries), openEnded,
                range(start, Math.max(start, extentEnd()))), false);
    }

    // ---------------------------------------------------------------------------------------------
    // Annotations and types.
    // ---------------------------------------------------------------------------------------------

    /// Port of `GDScriptParser::parse_annotation`, syntax only: the registry lookups (name,
    /// target, argument count) are cut. Arguments may span lines and may use a trailing comma. A
    /// failed argument keeps a marker at its position in the list; the owner frame binds any
    /// missing-`)` diagnostic to the annotation itself (the annotated declaration does not exist
    /// yet at this point, so it cannot own the facet).
    private @NotNull AnnotationStatement parseAnnotation() {
        var atToken = previous;
        var name = atToken.source().substring(1);

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
            pushRecoveryOwner("annotation.arguments");
            do {
                if (annotationFrame.settled || nestingBudgetExceeded
                        || check(GdTokenType.PARENTHESIS_CLOSE) || isAtUnclosedMultilineStop()) {
                    // Allow for trailing comma or escape at unclosed cross-line stop.
                    break;
                }
                var argumentStart = current.startByte();
                var argumentOutcome = parseExpression(false);
                arguments.add(absorbSlot(argumentOutcome, argumentStart, "annotation.argument",
                        "Expected expression as the annotation argument."));
            } while (!annotationFrame.settled && !nestingBudgetExceeded && match(GdTokenType.COMMA));

            var closed = consumeCloser(annotationFrame);
            argumentsFrame = popRecoveryOwner();
            end = extentEnd();
            if (!closed && !isAtEnd() && !check(GdTokenType.NEWLINE) && !check(GdTokenType.DEDENT)
                    && !isVirtualLambdaBoundary()) {
                markCurrentFramePendingSync();
            }
        }
        match(GdTokenType.NEWLINE); // Newline after annotation is optional.
        var annotation = new AnnotationStatement(name, List.copyOf(arguments), range(atToken.startByte(), end));
        if (argumentsFrame != null) {
            bindRecoveryOwner(argumentsFrame, annotation);
        }
        return annotation;
    }

    /// Port of `GDScriptParser::parse_type`: identifier chains, `Array[E]` / `Dictionary[K, V]`
    /// container types (nested typed collections rejected), and `void` restricted to function
    /// return types. Multiline mode is inherited from the calling context (no push/pop here), so
    /// member variable types cannot wrap lines while signature parameter types can.
    private @Nullable TypeRef parseType(boolean allowVoid) {
        var parsed = parseTypeInternal(allowVoid);
        return parsed == null ? null : parsed.ref();
    }

    /// Internal shape of {@link #parseType}: also reports whether the parsed type is itself a
    /// container type, which the opaque-text `TypeRef` cannot carry (Godot tracks this on the
    /// type node's `container_types` list).
    private record ParsedType(@NotNull TypeRef ref, boolean isContainer) {
    }

    private @Nullable ParsedType parseTypeInternal(boolean allowVoid) {
        // Container types recurse (`Array[...]`); the rejection of *nested* containers happens
        // only after the recursive call returns, so the recursion itself needs the guard.
        if (!enterNestingLevel()) {
            return null;
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

    private @Nullable ParsedType parseTypeInternalImpl(boolean allowVoid) {
        var typeStart = current.startByte();
        if (!match(GdTokenType.IDENTIFIER)) {
            if (match(GdTokenType.VOID)) {
                if (allowVoid) {
                    return new ParsedType(new TypeRef("void", range(typeStart, previous.endByte())), false);
                }
                pushError("\"void\" is only allowed for a function return type.");
            }
            // The "expected type" message is left to the caller, which knows the context.
            return null;
        }
        var containerName = previous.source();
        var typeEnd = previous.endByte();

        if (match(GdTokenType.BRACKET_OPEN)) {
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
            typeBracketFrame.allowsTypeComma = "Dictionary".equals(containerName);
            // Typed collection (like Array[int], Dictionary[String, int]).
            var firstPass = true;
            var failed = false;
            var argCount = 0;
            do {
                typeBracketFrame.separatorPhase = SeparatorPhase.EXPECT_ELEMENT;
                var element = parseTypeInternal(false);
                if (element == null) {
                    if (!nestingBudgetExceeded && !typeBracketFrame.settled) {
                        pushError("Expected type for collection after \"%s\".".formatted(firstPass ? "[" : ","));
                    }
                    failed = true;
                    break;
                } else if (element.isContainer()) {
                    pushError("Nested typed collections are not supported.");
                }
                firstPass = false;
                argCount++;
                if (typeBracketFrame.settled || nestingBudgetExceeded
                        || !typeBracketFrame.allowsTypeComma || argCount >= 2) {
                    typeBracketFrame.separatorPhase = SeparatorPhase.NONE;
                    break;
                }
                typeBracketFrame.separatorPhase = SeparatorPhase.EXPECT_SEPARATOR_OR_CLOSER;
            } while (!typeBracketFrame.settled && !nestingBudgetExceeded
                    && shouldContinueDictionaryTypeComma(typeBracketFrame)
                    && match(GdTokenType.COMMA));
            typeBracketFrame.separatorPhase = SeparatorPhase.NONE;
            consumeCloser(typeBracketFrame);
            if (failed) {
                return null;
            }
            typeEnd = previous.endByte();
            return new ParsedType(new TypeRef(slice(typeStart, typeEnd), range(typeStart, typeEnd)), true);
        }

        while (match(GdTokenType.PERIOD)) {
            if (consume(GdTokenType.IDENTIFIER, "Expected inner type name after \".\".")) {
                typeEnd = previous.endByte();
            }
        }
        return new ParsedType(new TypeRef(slice(typeStart, typeEnd), range(typeStart, typeEnd)), false);
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
        if (!consume(GdTokenType.IDENTIFIER, "Expected variable name after \"var\".")) {
            return null;
        }
        var name = previous.source();

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
                    var end = parseProperty(true);
                    var propertyFrame = popRecoveryOwner();
                    var variable = new VariableDeclaration(DeclarationKind.VAR, name, null, null, isStatic,
                            "variable_statement", range(start, end));
                    bindRecoveryOwner(propertyFrame, variable);
                    return variable;
                }
                pushError("Expected type after \":\"");
                return null;
            } else if (check(GdTokenType.EQUAL)) {
                // Infer-type marker; the `=` itself belongs to the initializer below.
                type = inferredTypeRef(colonToken);
            } else {
                if (allowProperty && check(GdTokenType.IDENTIFIER)
                        && (current.source().equals("get") || current.source().equals("set"))) {
                    pushRecoveryOwner("variable.property");
                    var end = parseProperty(false);
                    var propertyFrame = popRecoveryOwner();
                    var variable = new VariableDeclaration(DeclarationKind.VAR, name, null, null, isStatic,
                            "variable_statement", range(start, end));
                    bindRecoveryOwner(propertyFrame, variable);
                    return variable;
                }
                pushRecoveryOwner("variable.type");
                type = parseType(false);
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
            int end;
            if (match(GdTokenType.NEWLINE)) {
                end = parseProperty(true);
            } else {
                end = parseProperty(false);
            }
            var propertyFrame = popRecoveryOwner();
            var withProperty = new VariableDeclaration(DeclarationKind.VAR, name, type, value, isStatic,
                    "variable_statement", range(start, end));
            if (typeFrame != null) {
                bindRecoveryOwner(typeFrame, withProperty);
            }
            if (valueFrame != null) {
                bindRecoveryOwner(valueFrame, withProperty);
            }
            bindRecoveryOwner(propertyFrame, withProperty);
            return withProperty;
        }

        var variable = new VariableDeclaration(DeclarationKind.VAR, name, type, value, isStatic,
                "variable_statement", range(start, extentEnd()));
        if (typeFrame != null) {
            bindRecoveryOwner(typeFrame, variable);
        }
        if (valueFrame != null) {
            bindRecoveryOwner(valueFrame, variable);
        }
        endStatement("variable declaration");
        return variable;
    }

    /// Port of `GDScriptParser::parse_property` (+ setter/getter helpers): consumes both the
    /// reference form (`get = name, set = name`) and the inline-suite form (`get:` /
    /// `set(value):`). Produces no AST and returns the end byte of the whole property tail,
    /// captured before the trailing statement-end / DEDENT consumption.
    private int parseProperty(boolean needIndent) {
        if (needIndent
                && !consume(GdTokenType.INDENT, "Expected indented block for property after \":\".")) {
            return previous.endByte();
        }

        if (!consume(GdTokenType.IDENTIFIER, "Expected \"get\" or \"set\" for property declaration.")) {
            return previous.endByte();
        }
        var functionName = previous.source();

        // Reference form when `=` follows the first accessor, inline form otherwise.
        var referenceForm = check(GdTokenType.EQUAL);
        if (!referenceForm && !needIndent) {
            pushError("Property with inline code must go to an indented block.");
        }

        var getterUsed = false;
        var setterUsed = false;
        var accessorEnd = previous.endByte();
        // Run in a loop because accessor order doesn't matter.
        for (var i = 0; i < 2; i++) {
            if (functionName.equals("set")) {
                if (setterUsed) {
                    pushError("Properties can only have one setter.");
                } else {
                    accessorEnd = parsePropertyAccessor(true, referenceForm);
                    setterUsed = true;
                }
            } else if (functionName.equals("get")) {
                if (getterUsed) {
                    pushError("Properties can only have one getter.");
                } else {
                    accessorEnd = parsePropertyAccessor(false, referenceForm);
                    getterUsed = true;
                }
            } else {
                pushError("Expected \"get\" or \"set\" for property declaration.");
            }

            if (i == 0 && referenceForm) {
                if (match(GdTokenType.COMMA)) {
                    // Consume potential newline. (The message text is byte-exact with Godot's
                    // source, including its quirky quoting.)
                    if (match(GdTokenType.NEWLINE) && !needIndent) {
                        pushError("Inline setter/getter setting cannot span across multiple lines (use \"\\\\\"\" if needed).");
                    }
                } else {
                    break;
                }
            }

            if (!match(GdTokenType.IDENTIFIER)) {
                break;
            }
            functionName = previous.source();
        }

        // Inline form: the property tail ends at the last accessor suite's content end (the
        // suite's own trailing DEDENT is not part of it, matching the reference path's extent).
        var end = referenceForm ? previous.endByte() : accessorEnd;
        if (referenceForm) {
            endStatement("property declaration");
        }
        if (needIndent) {
            consume(GdTokenType.DEDENT, "Expected end of indented block for property.");
        }
        return end;
    }

    /// Port of `GDScriptParser::parse_property_setter` / `parse_property_getter`. Returns the end
    /// byte of the accessor (for the inline form: its suite's content end).
    private int parsePropertyAccessor(boolean setter, boolean referenceForm) {
        if (referenceForm) {
            consume(GdTokenType.EQUAL, setter ? "Expected \"=\" after \"set\"" : "Expected \"=\" after \"get\"");
            consume(GdTokenType.IDENTIFIER,
                    setter ? "Expected setter function name after \"=\"." : "Expected getter function name after \"=\".");
            return previous.endByte();
        }
        if (setter) {
            StructuralFrame setterParamFrame = null;
            if (match(GdTokenType.PARENTHESIS_OPEN)) {
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
                pushError("Expected \"(\" after \"set\".");
            }
            consume(GdTokenType.IDENTIFIER, "Expected parameter name after \"(\".");
            if (setterParamFrame != null) {
                consumeCloser(setterParamFrame);
            }
            consume(GdTokenType.COLON, "Expected \":\" after \")\".");
            return parseIsolatedSuite("setter declaration");
        }
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
            consumeCloser(getterParamFrame);
            consume(GdTokenType.COLON, "Expected \":\" after \"get()\".");
        } else {
            consume(GdTokenType.COLON, "Expected \":\" or \"(\" after \"get\".");
        }
        return parseIsolatedSuite("getter declaration");
    }

    /// Parses an accessor suite with the outer pending annotations saved aside: the suite's
    /// content is discarded entirely, so annotations inside it must not leak into the outer
    /// sequence (and the outer sequence's pending annotations must not be stolen by it).
    /// Returns the suite block's end byte for the property tail's extent.
    private int parseIsolatedSuite(String context) {
        var saved = new ArrayList<>(pendingAnnotations);
        pendingAnnotations.clear();
        Block suite;
        try {
            suite = parseSuite(context);
        } finally {
            // Replace, not append: annotations left dangling inside the discarded accessor suite
            // are discarded with it instead of leaking onto the next outer member.
            pendingAnnotations.clear();
            pendingAnnotations.addAll(saved);
        }
        return suite.range().endByte();
    }

    private record NamedType(@NotNull String name, @Nullable TypeRef type,
                             @Nullable RecoveryTransactionTracker.ProvisionalFrame typeFrame) {
    }

    /// Shared name-plus-optional-type prefix of constants and parameters. The name token is
    /// already consumed; a `:` immediately followed by `=` is an inferred type.
    private @NotNull NamedType parseNameAndType(@NotNull String typeFacet) {
        var name = previous.source();
        TypeRef type = null;
        RecoveryTransactionTracker.ProvisionalFrame typeFrame = null;
        if (match(GdTokenType.COLON)) {
            var colonToken = previous;
            if (check(GdTokenType.EQUAL)) {
                type = inferredTypeRef(colonToken);
            } else {
                pushRecoveryOwner(typeFacet);
                type = parseType(false);
                typeFrame = popRecoveryOwner();
            }
        }
        return new NamedType(name, type, typeFrame);
    }

    /// Port of `GDScriptParser::parse_constant`. A missing `=` still drops the declaration (no
    /// expression slot was opened); a failed value keeps a marker and the statement survives,
    /// consuming the success-path `endStatement` tail (a deliberate improvement over the vendor,
    /// which drops the node).
    private @Nullable VariableDeclaration parseConstant() {
        var start = statementStartByte;
        if (!consume(GdTokenType.IDENTIFIER, "Expected constant name after \"const\".")) {
            return null;
        }
        var named = parseNameAndType("constant.type");

        if (!consume(GdTokenType.EQUAL, "Expected initializer after constant name.")) {
            if (named.typeFrame() != null) {
                recoveryTracker.abandon(named.typeFrame());
            }
            return null;
        }
        var valueStart = current.startByte();
        pushRecoveryOwner("constant.value");
        var valueOutcome = parseExpression(false);
        var value = absorbSlot(valueOutcome, valueStart, "constant.value",
                "Expected initializer expression for constant.");
        var valueFrame = popRecoveryOwner();

        var constant = new VariableDeclaration(DeclarationKind.CONST, named.name(), named.type(), value, false,
                "const_statement", range(start, extentEnd()));
        if (named.typeFrame() != null) {
            bindRecoveryOwner(named.typeFrame(), constant);
        }
        bindRecoveryOwner(valueFrame, constant);
        endStatement("constant declaration");
        return constant;
    }

    /// Port of `GDScriptParser::parse_parameter` (the variadic `...` marker itself is consumed by
    /// the signature loops, which also set {@link #parameterStartByte}). A written `=` whose
    /// default failed keeps a marker, staying distinguishable from a never-written `=`. The
    /// default's owner frame is returned unbound: the signature loops bind it to the final
    /// parameter node (a rest parameter's variadic rebuild replaces the node, and a dropped
    /// duplicate's frame is dropped with it).
    private @Nullable ParsedParameter parseParameter() {
        // Capture the start locally: a lambda default reuses the shared field for its own
        // signature's parameters before this parameter's range is computed.
        var start = parameterStartByte;
        if (!consume(GdTokenType.IDENTIFIER, "Expected parameter name.")) {
            return null;
        }
        var named = parseNameAndType("parameter.type");

        Expression defaultValue = null;
        RecoveryTransactionTracker.ProvisionalFrame defaultFrame = null;
        if (match(GdTokenType.EQUAL)) {
            var defaultStart = current.startByte();
            pushRecoveryOwner("parameter.default");
            var defaultOutcome = parseExpression(false);
            defaultValue = absorbSlot(defaultOutcome, defaultStart, "parameter.default",
                    "Expected expression for parameter default value after \"=\".");
            defaultFrame = popRecoveryOwner();
        }
        var parameter = new Parameter(named.name(), named.type(), defaultValue, false,
                range(start, extentEnd()));
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
        if (!consume(GdTokenType.IDENTIFIER, "Expected signal name after \"signal\".")) {
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
            var declaredNames = new ArrayList<String>();
            do {
                if (check(GdTokenType.PARENTHESIS_CLOSE) || isAtUnclosedMultilineStop()) {
                    // Allow for trailing comma or escape at unclosed cross-line stop.
                    break;
                }
                parameterStartByte = current.startByte();
                var parsed = parseParameter();
                if (parsed == null) {
                    pushError("Expected signal parameter name.");
                    break;
                }
                var parameter = parsed.parameter();
                if (parameter.defaultValue() != null) {
                    pushError("Signal parameters cannot have a default value.");
                }
                if (declaredNames.contains(parameter.name())) {
                    pushError("Parameter with name \"%s\" was already declared for this signal."
                            .formatted(parameter.name()));
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
            } while (!paramListFrame.settled && !nestingBudgetExceeded && match(GdTokenType.COMMA) && !isAtEnd());

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
        endStatement("signal declaration");
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
                if (enumFrame.settled || nestingBudgetExceeded
                        || check(GdTokenType.BRACE_CLOSE) || isAtUnclosedMultilineStop()) {
                    // Allow trailing comma or escape at unclosed cross-line stop.
                    break;
                }
                if (consume(GdTokenType.IDENTIFIER, "Expected identifier for enum key.")) {
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
                        memberEnd = extentEnd();
                    }
                    var member = new EnumMember(memberName, value, range(memberStart, memberEnd));
                    if (valueFrame != null) {
                        bindRecoveryOwner(valueFrame, member);
                    }
                    members.add(member);
                }
            } while (!enumFrame.settled && !nestingBudgetExceeded && match(GdTokenType.COMMA));

            consumeCloser(enumFrame);
        } else {
            popMultiline(false);
            pushError("Expected \"{\" after %s.".formatted(name != null ? "enum name" : "\"enum\""));
        }
        var enumBodyFrame = popRecoveryOwner();
        var enumDecl = new EnumDeclaration(name, List.copyOf(members),
                range(start, Math.max(start, extentEnd())));
        bindRecoveryOwner(enumBodyFrame, enumDecl);
        endStatement("enum");
        return enumDecl;
    }

    /// Port of `GDScriptParser::parse_function` / `parse_function_signature`. A `_init` function
    /// produces a `ConstructorDeclaration` (the reference grammar marks it with a dedicated node
    /// type); `_static_init` special-casing is cut with the other semantic checks.
    private @Nullable Statement parseFunction(boolean isStatic) {
        var start = statementStartByte;
        if (!consume(GdTokenType.IDENTIFIER, "Expected function name after \"func\".")) {
            return null;
        }
        var name = previous.source();

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
            pushError("Expected opening \"(\" after function name.");
        }
        var signature = parseFunctionSignature(false, paramListFrame);
        var signatureFrame = popRecoveryOwner();

        var headerEnd = extentEnd();
        var previousFunctionName = currentFunctionName;
        currentFunctionName = name;
        Block body;
        if (!signature.hasBody()) {
            // Bodyless (abstract-style) functions end the statement at the signature.
            endStatement("bodyless function declaration");
            // The reference path maps a missing body to an empty block spanning the whole
            // declaration node; mirror that range for parity.
            body = new Block(List.of(), range(start, headerEnd));
        } else {
            body = parseSuite("function declaration");
        }
        currentFunctionName = previousFunctionName;

        var end = Math.max(body.range().endByte(), headerEnd);
        Statement functionNode = name.equals("_init")
                ? new ConstructorDeclaration(signature.parameters(), signature.returnType(),
                        body, range(start, end))
                : new FunctionDeclaration(name, signature.parameters(), signature.returnType(),
                        isStatic, body, range(start, end));
        bindRecoveryOwner(signatureFrame, functionNode);
        if (signature.returnTypeFrame() != null) {
            bindRecoveryOwner(signature.returnTypeFrame(), functionNode);
        }
        return functionNode;
    }

    private record FunctionSignature(@NotNull List<Parameter> parameters, @Nullable TypeRef returnType,
                                     @Nullable RecoveryTransactionTracker.ProvisionalFrame returnTypeFrame,
                                     boolean hasBody) {
    }

    /// Port of `GDScriptParser::parse_function_signature`: parameter list with rest parameter and
    /// the purely syntactic ordering/duplicate checks, and optional `->` return type. Functions
    /// allow a missing colon (bodyless/abstract form); lambdas require it (`forLambda`).
    private @NotNull FunctionSignature parseFunctionSignature(boolean forLambda,
                                                              @Nullable StructuralFrame paramListFrame) {
        var parameters = new ArrayList<Parameter>();
        var declaredNames = new ArrayList<String>();
        if (paramListFrame != null) {
            if (!check(GdTokenType.PARENTHESIS_CLOSE) && !isAtEnd()) {
                var defaultUsed = false;
                var restUsed = false;
                do {
                    if (check(GdTokenType.PARENTHESIS_CLOSE) || isAtUnclosedMultilineStop()) {
                        // Allow for trailing comma or escape at unclosed cross-line stop.
                        break;
                    }

                    var isRest = match(GdTokenType.PERIOD_PERIOD_PERIOD);
                    parameterStartByte = isRest ? previous.startByte() : current.startByte();

                    var parsed = parseParameter();
                    if (parsed == null) {
                        break;
                    }
                    var parameter = parsed.parameter();

                    // Ordering violations are reported but the parameter keeps its position in the
                    // list (the vendor skips it; positions are part of this AST's recovery contract).
                    if (restUsed) {
                        pushError("Cannot have parameters after the rest parameter.",
                                DiagnosticCodes.SIGNATURE_PARAMETER_AFTER_REST);
                    }
                    if (parameter.defaultValue() != null) {
                        if (isRest) {
                            pushError("The rest parameter cannot have a default value.",
                                    DiagnosticCodes.SIGNATURE_REST_WITH_DEFAULT);
                        } else {
                            defaultUsed = true;
                        }
                    } else if (defaultUsed && !isRest) {
                        pushError("Cannot have mandatory parameters after optional parameters.",
                                DiagnosticCodes.SIGNATURE_MANDATORY_AFTER_OPTIONAL);
                    }

                    if (declaredNames.contains(parameter.name())) {
                        pushError("Parameter with name \"%s\" was already declared for this function."
                                .formatted(parameter.name()));
                        if (parsed.typeFrame() != null) {
                            recoveryTracker.abandon(parsed.typeFrame());
                        }
                        if (parsed.defaultFrame() != null) {
                            recoveryTracker.abandon(parsed.defaultFrame());
                        }
                        // A dropped duplicate never gets its frame bound; its closer diagnostics keep
                        // code and range and stay consumable via range correlation.
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
                } while (!paramListFrame.settled && !nestingBudgetExceeded && match(GdTokenType.COMMA));
            }

            consumeCloser(paramListFrame);
        }

        TypeRef returnType = null;
        RecoveryTransactionTracker.ProvisionalFrame returnTypeFrame = null;
        if (match(GdTokenType.FORWARD_ARROW)) {
            pushRecoveryOwner("function.return_type");
            returnType = parseType(true);
            returnTypeFrame = popRecoveryOwner();
            if (returnType == null) {
                pushError("Expected return type or \"void\" after \"->\".");
            }
        }

        // The colon may not be present in the case of bodyless functions; lambdas require it
        // (the vendor reports but still parses the body afterwards).
        var hasBody = forLambda
                ? consume(GdTokenType.COLON, "Expected \":\" after lambda declaration.")
                : match(GdTokenType.COLON);
        return new FunctionSignature(List.copyOf(parameters), returnType, returnTypeFrame, hasBody);
    }

    // ---------------------------------------------------------------------------------------------
    // Lambda expression — port of parse_lambda (the `func` prefix rule).
    // ---------------------------------------------------------------------------------------------

    /// Port of `GDScriptParser::parse_lambda`: optional name, signature (parameters and `->` type
    /// shared with functions), and a suite parsed with lambda-end semantics. When the lambda sits
    /// in a multiline context (e.g. inside call parentheses), the enclosing indentation state is
    /// snapshotted so the body can track indentation, and spurious layout tokens after the body
    /// are raw-skipped without touching `previous`. Break/continue are disallowed inside, with
    /// save/restore like the vendor.
    private @NotNull ParseOutcome<Expression> parseLambdaExpression() {
        // The expression-level prefix rule always produces a LambdaExpression. Internal damage
        // (parameter defaults, body statements) is absorbed by the lambda's own slots, so the
        // prefix outcome is always Ok.
        return ParseOutcome.ok((Expression) parseLambdaLike(false));
    }

    private boolean hasTrustedLambdaSuiteStart() {
        if (check(GdTokenType.INDENT)) {
            return true;
        }
        if (check(GdTokenType.NEWLINE)) {
            return peekAfterCurrent() == GdTokenType.INDENT;
        }
        return !isAtEnd() && !check(GdTokenType.DEDENT) && !isVirtualLambdaBoundary();
    }

    /// Shared lambda-body machinery. `statementLevel` selects the node type for the
    /// statement-position `func` form: the reference path maps it to a (possibly named)
    /// `FunctionDeclaration` rather than a lambda in an expression statement, so parity requires
    /// that shape here (Godot itself parses both as a lambda expression statement).
    private @NotNull Node parseLambdaLike(boolean statementLevel) {
        var start = previous.startByte(); // the consumed `func` keyword
        String name = null;
        if (match(GdTokenType.IDENTIFIER)) {
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
            pushError(name != null
                    ? "Expected opening \"(\" after lambda name."
                    : "Expected opening \"(\" after \"func\".");
        }

        var previousFunctionName = currentFunctionName;
        currentFunctionName = name;
        var signature = parseFunctionSignature(true, paramListFrame);
        var signatureFrame = popRecoveryOwner();
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

        Block body;
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
                body = parseSuite("lambda declaration", true);
            }
        } finally {
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
                current = nextScannedToken();
            }
            tokenizer.popExpressionIndentedBlock();
            if (current.type() == GdTokenType.EOF) {
                var eofRestored = tokenizer.drainRestoredEofDedents();
                if (!eofRestored.isEmpty()) {
                    tokenQueue.addFirst(current);
                    for (var i = eofRestored.size() - 1; i >= 1; i--) {
                        tokenQueue.addFirst(eofRestored.get(i));
                    }
                    current = eofRestored.getFirst();
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
        Node lambdaNode = statementLevel
                ? new FunctionDeclaration(name != null ? name : "<anonymous>", signature.parameters(),
                        signature.returnType(), false, body, range(start, body.range().endByte()))
                : new LambdaExpression(name, signature.parameters(), signature.returnType(), body,
                        range(start, body.range().endByte()));
        bindRecoveryOwner(signatureFrame, lambdaNode);
        if (signature.returnTypeFrame() != null) {
            bindRecoveryOwner(signature.returnTypeFrame(), lambdaNode);
        }
        return lambdaNode;
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
        /// `left` is always healthy: the Pratt loop stops folding a non-`Ok` left operand before
        /// reaching the infix rule.
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
            advance(); // Only consume the token if there's a valid rule.
            var left = rule.prefix().parse();

            while (precedence.ordinal() <= ruleFor(current.type()).precedence().ordinal()) {
                if (nestingBudgetExceeded
                        || (entryFrame != null && entryFrame.settled)
                        || isAtUnclosedMultilineStop()) {
                    return left;
                }
                var infixRule = ruleFor(current.type());
                // Vendor's infix-loop head also stops on a pending lambda end: an operator following
                // the lambda boundary belongs to the enclosing flow, not to this expression
                // (e.g. `return func(): return 1` keeps the bare lambda as the return value).
                // A non-Ok left operand stops the fold where this parser's former nullable
                // contract stopped on null (Godot keeps folding a non-null partial node — a
                // deliberate difference): the partial node returns to the host instead of
                // folding further operators into it.
                if (infixRule.infix() == null || lambdaEnded
                        || (stopOnAssign && current.type() == GdTokenType.EQUAL)
                        || !(left instanceof ParseOutcome.Ok<Expression>(var healthyLeft))) {
                    return left;
                }
                // Switch multiline mode on for infix grouping operators, before consuming the token.
                if (current.type() == GdTokenType.PARENTHESIS_OPEN || current.type() == GdTokenType.BRACKET_OPEN) {
                    pushMultiline();
                }
                advance();
                left = infixRule.infix().parse(healthyLeft, precedence, canAssign, extentStart);
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
    /// happens here before the closing parenthesis (vendor order), even on failure. A healthy
    /// inner expression consumes the closer (missing closer only records a delimiter diagnostic);
    /// a non-Ok inner outcome leaves the closer unconsumed for outer recovery — deliberate
    /// difference from Godot, which consumes the `)` for a non-null partial node (this parser's
    /// damaged outcome stops folding instead, matching its former null-stop control flow).
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
        var grouped = parseExpression(false);
        switch (grouped) {
            case ParseOutcome.Ok<Expression>(var node) -> {
                consumeCloser(groupingFrame);
                return ParseOutcome.ok(node);
            }
            case ParseOutcome.Damaged<Expression>(var node) -> {
                consumeCloser(groupingFrame);
                return ParseOutcome.damaged(node);
            }
            default -> {
                if (grouped instanceof ParseOutcome.Missing<Expression>) {
                    recoveryTracker.emitError(
                            "Expected grouping expression.",
                            tokenRange(previous),
                            DiagnosticCodes.SLOT_GROUPING,
                            RecoveryTransactionTracker.AttributionPolicy.CURRENT_FRAME,
                            null
                    );
                }
                var marker = slotMarker(grouped, innerStart, "grouping");
                consumeCloser(groupingFrame, true);
                return ParseOutcome.damaged(marker);
            }
        }
    }

    /// Port of `GDScriptParser::parse_array`: elements parse with assignment disallowed; a failed
    /// element is reported and its slot keeps a marker (preserving element positions); trailing
    /// comma allowed. The array node itself always survives: element damage is enclosed in the
    /// list and never propagates to the enclosing fold.
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
        if (!check(GdTokenType.BRACKET_CLOSE)) {
            do {
                if (arrayFrame.settled || nestingBudgetExceeded
                        || check(GdTokenType.BRACKET_CLOSE) || isAtUnclosedMultilineStop()) {
                    // Allow for trailing comma or escape at unclosed cross-line stop.
                    break;
                }
                var elementStart = current.startByte();
                var element = parseExpression(false);
                elements.add(absorbSlot(element, elementStart, "array.element",
                        "Expected expression as array element."));
            } while (!arrayFrame.settled && !nestingBudgetExceeded && match(GdTokenType.COMMA) && !isAtEnd());
        }
        consumeCloser(arrayFrame);
        return ParseOutcome.ok(new ArrayExpression(List.copyOf(elements), false,
                range(start, Math.max(start, extentEnd()))));
    }

    /// Dictionary styles tracked by `parseDictionaryExpression` (vendor `DictionaryNode::Style`).
    private enum DictStyle {PYTHON_DICT, LUA_TABLE}

    /// Port of `GDScriptParser::parse_dictionary`: Python (`{key: value}`) and Lua
    /// (`{key = value}`) styles are decided by the first entry's separator and cannot be mixed.
    /// Lua-style identifier/string keys normalize to `string_name` literals (the AST contract's
    /// pinned shape, mirroring the tree-sitter mapper). Keys parse with `stopOnAssign` so the
    /// Pratt loop leaves a Lua `=` separator unconsumed. A missing key or value keeps a marker in
    /// its slot (the AST analog of Godot's dummy recovery `LiteralNode`, but with an explicit
    /// recovery identity and an in-envelope range); the entry is dropped only when both sides
    /// failed without consuming anything.
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
        DictStyle style = null;
        if (!check(GdTokenType.BRACE_CLOSE)) {
            do {
                if (dictionaryFrame.settled || nestingBudgetExceeded
                        || check(GdTokenType.BRACE_CLOSE) || isAtUnclosedMultilineStop()) {
                    // Allow for trailing comma or escape at unclosed cross-line stop.
                    break;
                }
                var keyExtentStart = current.startByte();
                var keyOutcome = parseExpression(false, true);
                if (dictionaryFrame.settled || nestingBudgetExceeded) {
                    break;
                }
                var key = absorbSlot(keyOutcome, keyExtentStart, "dictionary.key",
                        "Expected expression as dictionary key.");
                if (style == null) {
                    switch (current.type()) {
                        case COLON -> style = DictStyle.PYTHON_DICT;
                        case EQUAL -> style = DictStyle.LUA_TABLE;
                        default -> pushError("Expected \":\" or \"=\" after dictionary key.");
                    }
                    // Godot's style field defaults to PYTHON_DICT when nothing valid decided it.
                    if (style == null) {
                        style = DictStyle.PYTHON_DICT;
                    }
                }
                var normalizedKey = key;
                if (style == DictStyle.LUA_TABLE) {
                    // Only a healthy key normalizes; a marker/damaged key already carries its own
                    // root-cause diagnostic, so the type complaint would double-report it.
                    if (keyOutcome instanceof ParseOutcome.Ok<Expression>) {
                        if (key instanceof IdentifierExpression(var name, _)) {
                            normalizedKey = new LiteralExpression("string_name", "&\"" + name + "\"", key.range());
                        } else if (key instanceof LiteralExpression(var kind, var sourceText, _)
                                && "string".equals(kind)) {
                            normalizedKey = new LiteralExpression("string_name", "&" + sourceText, key.range());
                        } else {
                            pushError("Expected identifier or string as Lua-style dictionary key (e.g \"{ key = value }\").");
                        }
                    }
                    if (!match(GdTokenType.EQUAL)) {
                        if (match(GdTokenType.COLON)) {
                            pushError("Expected \"=\" after dictionary key. Mixing dictionary styles is not allowed.");
                            // Vendor consumes one extra token here ("Consume wrong separator
                            // anyway", though `match` already consumed it); mirrored verbatim.
                            if (!isAtEnd()) {
                                advance();
                            }
                        } else {
                            pushError("Expected \"=\" after dictionary key.");
                        }
                    }
                } else {
                    if (!match(GdTokenType.COLON)) {
                        if (match(GdTokenType.EQUAL)) {
                            pushError("Expected \":\" after dictionary key. Mixing dictionary styles is not allowed.");
                            // Same vendor extra-consumption quirk as the Lua branch above.
                            if (!isAtEnd()) {
                                advance();
                            }
                        } else {
                            pushError("Expected \":\" after dictionary key.");
                        }
                    }
                }
                var valueExtentStart = current.startByte();
                var valueOutcome = parseExpression(false);
                var value = absorbSlot(valueOutcome, valueExtentStart, "dictionary.value",
                        "Expected expression as dictionary value.");
                // Both sides missing means the iteration consumed nothing meaningful (e.g. `{:}`):
                // drop the entry like today instead of materializing a marker-only pair.
                if (keyOutcome instanceof ParseOutcome.Missing<Expression>
                        && valueOutcome instanceof ParseOutcome.Missing<Expression>) {
                    continue;
                }
                // Entry edges span the consumed text: a grouping `(`/`)` around a key belongs to
                // the entry even though the unwrapped key node starts inside it, and a zero-width
                // marker side anchors at its failure point (envelope-safe by construction).
                var entryStart = Math.min(keyExtentStart, key.range().startByte());
                entries.add(new DictEntry(
                        normalizedKey,
                        Objects.requireNonNull(value, "dictionary literal entry value must not be null"),
                        range(entryStart, extentEnd())
                ));
            } while (!dictionaryFrame.settled && !nestingBudgetExceeded && match(GdTokenType.COMMA) && !isAtEnd());
        }
        consumeCloser(dictionaryFrame);
        return ParseOutcome.ok(new DictionaryExpression(List.copyOf(entries), false,
                range(start, Math.max(start, extentEnd()))));
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
            pushError("Expected node path as string or identifier after \"%s\"."
                    .formatted(startToken.type().displayName()));
            return ParseOutcome.failed();
        }
        if (current.type() == GdTokenType.LITERAL && current.literalKind() != GdLiteralKind.STRING) {
            pushError("Expected node path as string or identifier after \"%s\"."
                    .formatted(startToken.type().displayName()));
            return ParseOutcome.failed();
        }

        var state = NodePathState.START;
        if (startToken.type() == GdTokenType.DOLLAR) {
            // An initial slash (absolute path) is handled by the loop's SLASH branch.
            match(GdTokenType.SLASH);
        }
        do {
            if (previous.type() == GdTokenType.PERCENT) {
                if (state != NodePathState.START && state != NodePathState.SLASH) {
                    pushError("\"%\" is only valid in the beginning of a node name (either after \"$\" or after \"/\")");
                    return ParseOutcome.failed();
                }
                state = NodePathState.PERCENT;
            } else if (previous.type() == GdTokenType.SLASH) {
                if (state != NodePathState.START && state != NodePathState.NODE_NAME) {
                    pushError("\"/\" is only valid at the beginning of the path or after a node name.");
                    return ParseOutcome.failed();
                }
                state = NodePathState.SLASH;
            }

            if (match(GdTokenType.LITERAL)) {
                if (previous.literalKind() != GdLiteralKind.STRING) {
                    var after = switch (state) {
                        case PERCENT -> "%";
                        case SLASH -> "/";
                        default -> "$";
                    };
                    pushError("Expected node path as string or identifier after \"%s\".".formatted(after));
                    return ParseOutcome.failed();
                }
                state = NodePathState.NODE_NAME;
            } else if (isNodeName(current)) {
                advance();
                state = NodePathState.NODE_NAME;
            } else if (!check(GdTokenType.SLASH) && !check(GdTokenType.PERCENT)) {
                pushError("Unexpected \"%s\" in node path.".formatted(
                        current.type() == GdTokenType.IDENTIFIER ? current.source()
                                : current.type().displayName()));
                return ParseOutcome.failed();
            }
        } while (match(GdTokenType.SLASH) || match(GdTokenType.PERCENT));

        return ParseOutcome.ok(new GetNodeExpression(slice(startToken.startByte(), previous.endByte()),
                range(startToken.startByte(), previous.endByte())));
    }

    /// Port of `GDScriptParser::parse_preload`: the path is a general expression (constant
    /// folding and resource-existence checks belong to later Godot phases, cut here); one
    /// trailing comma is allowed. A failed path keeps a marker in the node and marks the outcome
    /// damaged so the enclosing fold stops (vendor keeps the node with a null path).
    private @NotNull ParseOutcome<Expression> parsePreloadExpression() {
        var startToken = previous;
        pushMultiline();
        if (!match(GdTokenType.PARENTHESIS_OPEN)) {
            popMultiline(false);
            pushError("Expected \"(\" after \"preload\".");
            var end = startToken.endByte();
            var marker = new ErrorExpression(CstIssueKind.MISSING, "preload.path", "", range(end, end));
            return ParseOutcome.damaged(new PreloadExpression(marker, range(startToken.startByte(), end)));
        }
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
        var pathOutcome = parseExpression(false);
        var path = absorbSlot(pathOutcome, pathStart, "preload.path", "Expected resource path after \"(\".");
        if (!preloadFrame.settled && !nestingBudgetExceeded) {
            match(GdTokenType.COMMA); // Allow trailing comma.
        }
        consumeCloser(preloadFrame);
        var node = new PreloadExpression(path, range(startToken.startByte(), extentEnd()));
        return pathOutcome instanceof ParseOutcome.Ok<Expression> ? ParseOutcome.ok(node) : ParseOutcome.damaged(node);
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
        var node = new AwaitExpression(operand, range(startToken.startByte(), extentEnd()));
        return operandOutcome instanceof ParseOutcome.Ok<Expression> ? ParseOutcome.ok(node) : ParseOutcome.damaged(node);
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
            advance();
            var arguments = parseCallArguments();
            var call = new CallExpression(superIdentifier, arguments.valueOrThrow(),
                    range(superToken.startByte(), extentEnd()));
            return arguments instanceof ParseOutcome.Ok<List<Expression>> ? ParseOutcome.ok(call) : ParseOutcome.damaged(call);
        }
        if (!match(GdTokenType.PERIOD)) {
            pushError("Expected \".\" or \"(\" after \"super\".", DiagnosticCodes.SUPER_MISSING_ACCESS);
            return ParseOutcome.damaged(superIdentifier);
        }
        if (!check(GdTokenType.IDENTIFIER)) {
            // The receiver stays visible (a chain with a missing-name step) so editor consumers
            // can still offer lexical-superclass member candidates after `super.`.
            pushError("Expected function name after \".\".", DiagnosticCodes.SUPER_MISSING_NAME);
            var missing = new MissingAttributeStep(range(extentEnd(), extentEnd()));
            return ParseOutcome.damaged(new AttributeExpression(superIdentifier, List.of(missing),
                    range(superToken.startByte(), extentEnd())));
        }
        advance();
        var nameToken = previous;
        if (!check(GdTokenType.PARENTHESIS_OPEN)) {
            pushError("Expected \"(\" after function name.", DiagnosticCodes.SUPER_MISSING_CALL);
            var propertyStep = new AttributePropertyStep(nameToken.source(), tokenRange(nameToken));
            return ParseOutcome.damaged(new AttributeExpression(superIdentifier, List.of(propertyStep),
                    range(superToken.startByte(), extentEnd())));
        }
        pushMultiline();
        advance();
        var arguments = parseCallArguments();
        var step = new AttributeCallStep(nameToken.source(), arguments.valueOrThrow(),
                range(nameToken.startByte(), extentEnd()));
        var chain = new AttributeExpression(superIdentifier, List.of(step),
                range(superToken.startByte(), extentEnd()));
        return arguments instanceof ParseOutcome.Ok<List<Expression>> ? ParseOutcome.ok(chain) : ParseOutcome.damaged(chain);
    }

    /// Port of `GDScriptParser::parse_unary_operator`: `+`/`-` at `PREC_SIGN`, `~` at
    /// `PREC_BIT_NOT`, `not`/`!` at `PREC_LOGIC_NOT`. A failed operand keeps a marker in the node
    /// (vendor keeps the node with a null operand) and marks the outcome damaged.
    private @NotNull ParseOutcome<Expression> parseUnaryOperator() {
        var operator = previous;
        var operandPrecedence = switch (operator.type()) {
            case PLUS, MINUS -> Precedence.SIGN;
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
        var node = new UnaryExpression(operator.source(), operand, range(operator.startByte(), extentEnd()));
        return operandOutcome instanceof ParseOutcome.Ok<Expression> ? ParseOutcome.ok(node) : ParseOutcome.damaged(node);
    }

    /// Port of `GDScriptParser::parse_binary_operator` (left-associative via precedence + 1 on the
    /// right operand, which also makes `**` left-associative like Godot). A failed right operand
    /// keeps a marker in the node (vendor keeps the node with a null child); the damaged outcome
    /// stops the enclosing fold, so `1 +` never folds a following operator into the partial node.
    private @NotNull ParseOutcome<Expression> parseBinaryOperator(@NotNull Expression left, Precedence entryPrecedence,
                                                                  boolean canAssign, int extentStart) {
        var operator = previous;
        var precedence = ruleFor(operator.type()).precedence();
        var rightStart = current.startByte();
        var rightOutcome = parsePrecedence(Precedence.values()[precedence.ordinal() + 1]);
        var right = absorbSlot(rightOutcome, rightStart, "binary.right",
                "Expected expression after \"%s\" operator.".formatted(operator.type().displayName()));
        var node = new BinaryExpression(operator.source(), left, right, range(extentStart, extentEnd()));
        return rightOutcome instanceof ParseOutcome.Ok<Expression> ? ParseOutcome.ok(node) : ParseOutcome.damaged(node);
    }

    /// Port of `GDScriptParser::parse_binary_not_in_operator`. Godot wraps the membership binary
    /// in a logic-not unary node; the AST contract models the pair as a single
    /// `BinaryExpression("not in")` (the tree-sitter path's pinned shape). When the `in` token is
    /// missing, the vendor still parses the right-hand side as if it were present (the recovery
    /// message then names "not"); mirrored here.
    private @NotNull ParseOutcome<Expression> parseNotInOperator(@NotNull Expression left, Precedence entryPrecedence,
                                                                 boolean canAssign, int extentStart) {
        var matchedIn = match(GdTokenType.IN);
        if (!matchedIn) {
            pushError("Expected \"in\" after \"not\" in content-test operator.");
        }
        var rightStart = current.startByte();
        var rightOutcome = parsePrecedence(Precedence.COMPARISON); // PREC_CONTENT_TEST + 1
        var right = absorbSlot(rightOutcome, rightStart, "binary.right",
                "Expected expression after \"%s\" operator.".formatted(matchedIn ? "in" : "not"));
        var node = new BinaryExpression("not in", left, right, range(extentStart, extentEnd()));
        return rightOutcome instanceof ParseOutcome.Ok<Expression> ? ParseOutcome.ok(node) : ParseOutcome.damaged(node);
    }

    /// Port of `GDScriptParser::parse_ternary_operator` (`true_value if condition else false_value`).
    /// Both branches parse at `PREC_TERNARY` with assignment disallowed. Failed branches keep
    /// markers in the node (vendor keeps the node with null children), and token consumption still
    /// follows the vendor order: condition, `else`, false branch.
    private @NotNull ParseOutcome<Expression> parseTernaryOperator(@NotNull Expression left, Precedence entryPrecedence,
                                                                   boolean canAssign, int extentStart) {
        var conditionStart = current.startByte();
        var conditionOutcome = parsePrecedence(Precedence.TERNARY);
        var condition = absorbSlot(conditionOutcome, conditionStart, "ternary.condition",
                "Expected expression as ternary condition after \"if\".");
        consume(GdTokenType.ELSE, "Expected \"else\" after ternary operator condition.");
        var falseStart = current.startByte();
        var falseOutcome = parsePrecedence(Precedence.TERNARY);
        var falseBranch = absorbSlot(falseOutcome, falseStart, "ternary.else",
                "Expected expression after \"else\".");
        var node = new ConditionalExpression(condition, left, falseBranch, range(extentStart, extentEnd()));
        return conditionOutcome instanceof ParseOutcome.Ok<Expression>
                && falseOutcome instanceof ParseOutcome.Ok<Expression>
                ? ParseOutcome.ok(node) : ParseOutcome.damaged(node);
    }

    /// Port of `GDScriptParser::parse_cast`: the right side is a type specifier (parsed by
    /// `parse_type`, not a general expression). On a missing type Godot drops the cast and
    /// returns the operand, mirrored here.
    private @NotNull ParseOutcome<Expression> parseCast(@NotNull Expression left, Precedence entryPrecedence,
                                                        boolean canAssign, int extentStart) {
        var type = parseType(false);
        if (type == null) {
            pushError("Expected type specifier after \"as\".");
            return ParseOutcome.ok(left);
        }
        return ParseOutcome.ok(new CastExpression(left, type, range(extentStart, type.range().endByte())));
    }

    /// Port of `GDScriptParser::parse_type_test`. Godot represents `is not` as a logic-not unary
    /// wrapping the test node; the AST contract carries the `negated` flag on
    /// `TypeTestExpression` instead (the tree-sitter path's pinned shape). On a missing type the
    /// expression degrades to the left operand (Godot keeps a typeless test node, which the AST
    /// cannot represent).
    private @NotNull ParseOutcome<Expression> parseTypeTest(@NotNull Expression left, Precedence entryPrecedence,
                                                            boolean canAssign, int extentStart) {
        var negated = match(GdTokenType.NOT);
        var type = parseType(false);
        if (type == null) {
            pushError("Expected type specifier after \"is%s\".".formatted(negated ? " not" : ""));
            return ParseOutcome.ok(left);
        }
        return ParseOutcome.ok(new TypeTestExpression(left, type, negated,
                range(extentStart, type.range().endByte())));
    }

    /// Port of `GDScriptParser::parse_assignment` for the whole `=`/`+=`/.../`^=` family. The
    /// operator text comes from the token source (`"="` for plain assignment). The right-hand
    /// side parses with assignment disallowed, so `a = b = 1` reports the nested-assignment
    /// error exactly like Godot. The vendor's assignee node-type check (`SUBSCRIPT`/`IDENTIFIER`)
    /// is a parse-time semantic check outside the ported set and is not enforced, left to the
    /// analysis layer; invalid targets produce an `AssignmentExpression` for the analyzer to reject.
    private @NotNull ParseOutcome<Expression> parseAssignment(@NotNull Expression left, Precedence entryPrecedence,
                                                              boolean canAssign, int extentStart) {
        var operator = previous;
        if (!canAssign) {
            // Degraded rescue (vendor): report and return the right operand's own outcome — no
            // AssignmentExpression is built. A zero-consumption failure here must become `Failed`
            // rather than `Missing`: `a =` was consumed and reported, and a transparent `Missing`
            // would make the outer host push a second generic message for the same slot.
            pushError("Assignment is not allowed inside an expression.");
            var right = parseExpression(false);
            if (right instanceof ParseOutcome.Missing<Expression>) {
                return ParseOutcome.failed();
            }
            return right;
        }
        var rightStart = current.startByte();
        var rightOutcome = parseExpression(false);
        var right = absorbSlot(rightOutcome, rightStart, "assignment.value", "Expected an expression after \"=\".");
        var node = new AssignmentExpression(operator.source(), left, right, range(extentStart, extentEnd()));
        return rightOutcome instanceof ParseOutcome.Ok<Expression> ? ParseOutcome.ok(node) : ParseOutcome.damaged(node);
    }

    /// Port of `GDScriptParser::parse_invalid_token` for `?` (GDScript 2.x ternary syntax hint).
    private @NotNull ParseOutcome<Expression> parseInvalidToken(@NotNull Expression left, Precedence entryPrecedence,
                                                                boolean canAssign, int extentStart) {
        if (previous.type() == GdTokenType.QUESTION_MARK) {
            pushError("Unexpected \"?\" in source. If you want a ternary operator, use \"truthy_value if true_condition else falsy_value\".");
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
    /// `CallExpression(callee = chain(self, [b, MISSING]))`); damaged call arguments or a
    /// damaged subscript index build the node and return `Damaged` immediately, so no later
    /// suffix folds into the partial node (equivalent to the former null bubbling).
    private @NotNull ParseOutcome<Expression> parseSuffixChain(@NotNull Expression left, Precedence entryPrecedence,
                                                               boolean canAssign, int extentStart) {
        var result = left;
        Expression chainBase = null;
        List<AttributeStep> steps = null;
        var damaged = false;
        while (true) {
            switch (previous.type()) {
                case PERIOD -> {
                    // Member names accept Godot's `is_node_name()` set (nearly all keywords). A
                    // missing name is reported and leaves the marker step behind; if the missing
                    // name crossed into a structural stop outside an unclosed container, yield
                    // without consuming the escaped token.
                    var escaped = isAtUnclosedMultilineStop();
                    if (escaped || !isNodeName(current)) {
                        pushError("Expected identifier after \".\" for attribute access.",
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
                        advance();
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
                    var end = extentEnd();
                    if (steps != null && steps.getLast() instanceof AttributePropertyStep(var name, var stepRange)) {
                        steps.removeLast();
                        steps.add(new AttributeCallStep(name, arguments,
                                range(stepRange.startByte(), end)));
                    } else {
                        var callee = finishChain(result, chainBase, steps, extentStart);
                        result = new CallExpression(callee, arguments,
                                range(extentStart, end));
                        chainBase = null;
                        steps = null;
                    }
                    if (argumentsOutcome instanceof ParseOutcome.Damaged<List<Expression>>) {
                        return ParseOutcome.damaged(finishChain(result, chainBase, steps, extentStart));
                    }
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
                    var indexOutcome = parseExpression(false);
                    var index = absorbSlot(indexOutcome, indexStart, "subscript.index",
                            "Expected expression after \"[\".");
                    consumeCloser(subscriptFrame);
                    var end = extentEnd();
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
                    if (!(indexOutcome instanceof ParseOutcome.Ok<Expression>)) {
                        return ParseOutcome.damaged(finishChain(result, chainBase, steps, extentStart));
                    }
                }
                default -> throw new IllegalStateException(
                        "GDScript parser bug: suffix chain entered on " + previous.type());
            }

            // Continue the chain on the next suffix token, honoring the same precedence cutoff
            // as Godot's infix loop and consuming with the Pratt loop's own order (multiline
            // push before a grouping token, then advance).
            if (isAtUnclosedMultilineStop()) {
                var node = finishChain(result, chainBase, steps, extentStart);
                return damaged ? ParseOutcome.damaged(node) : ParseOutcome.ok(node);
            }
            var nextType = current.type();
            var isSuffix = nextType == GdTokenType.PARENTHESIS_OPEN || nextType == GdTokenType.BRACKET_OPEN
                    || nextType == GdTokenType.PERIOD;
            if (!isSuffix || entryPrecedence.ordinal() > ruleFor(nextType).precedence().ordinal()) {
                var node = finishChain(result, chainBase, steps, extentStart);
                return damaged ? ParseOutcome.damaged(node) : ParseOutcome.ok(node);
            }
            if (nextType != GdTokenType.PERIOD) {
                pushMultiline();
            }
            advance();
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

    /// Port of the argument loop of `GDScriptParser::parse_call` (non-`super` branch): assumes the
    /// opening parenthesis was already consumed and multiline pushed; pops multiline before
    /// consuming the closer. A failed argument keeps a marker at its position (the AST analog of
    /// the vendor's dropped argument, but position-preserving) and makes the list `Damaged`, so
    /// the call node is built but folds no further suffixes. A missing closer only records a
    /// delimiter diagnostic — it does not damage the list.
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
        var damaged = false;
        do {
            if (callFrame.settled || nestingBudgetExceeded
                    || check(GdTokenType.PARENTHESIS_CLOSE) || isAtUnclosedMultilineStop()) {
                // Allow for trailing comma or escape at unclosed cross-line stop.
                break;
            }
            var argumentStart = current.startByte();
            var argumentOutcome = parseExpression(false);
            if (!(argumentOutcome instanceof ParseOutcome.Ok<Expression>)) {
                damaged = true;
            }
            arguments.add(absorbSlot(argumentOutcome, argumentStart, "call.argument",
                    "Expected expression as the function argument."));
        } while (!callFrame.settled && !nestingBudgetExceeded && match(GdTokenType.COMMA));

        consumeCloser(callFrame);
        var result = List.copyOf(arguments);
        return damaged ? ParseOutcome.damaged(result) : ParseOutcome.ok(result);
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
