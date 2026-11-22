package dev.superice.gdparser.frontend.gdscript;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/// Incremental GDScript lexer producing a token stream via `scan()`.
///
/// Behavioral highlights:
/// - Errors are queued and returned as `ERROR` tokens in LIFO order before regular
///   scanning resumes; a single error never aborts the scan.
/// - Indentation uses an indent stack plus a signed `pendingIndents` counter; `scan()`
///   delivers at most one `INDENT`/`DEDENT` per call. A misaligned dedent queues an error
///   and pushes the actual width back without incrementing the counter.
/// - Newlines follow a pending-newline model: empty lines and comment-only lines never
///   emit `NEWLINE`; multiline mode discards pending newlines and suppresses indentation
///   changes; a backslash line continuation consumes the newline.
/// - Comments are collected as `GdComment` trivia and never enter the token stream.
/// - Supports two operating modes:
///   - **Standalone (public default)**: layout suppression follows unclosed brackets on
///     `parenStack` unless explicitly overridden via `setMultilineMode(boolean)`, and
///     unmatched/mismatched closers emit lexical `ERROR` tokens with single-transition
///     stack unwinding.
///   - **Managed (parser / completion)**: explicit `setMultilineMode(boolean)` starts as
///     `false` and always takes precedence over `parenStack`, while closers always emit
///     their real closer token so syntactic recovery owns bracket pairing.
///
/// Deliberate deviations (documented project decisions):
/// - Sign characters are never absorbed into number literals: `+`/`-` always produce
///   `PLUS`/`MINUS` so the parser can model unary operations as `UnaryExpression`.
/// - Decoded literal values are not computed; `GdToken.literalKind` carries the literal
///   classification instead and the raw text stays available via `GdToken.source`.
///   `ERROR` tokens carry an empty `source` (byte range stays exact) to avoid quadratic
///   allocation on error-dense inputs.
/// - Positions: tokens carry physical UTF-8 byte offsets alongside 1-based physical line
///   and Unicode code-point column coordinates (tab counts as 1; CRLF advances `line`
///   once). EOF-synthesized `NEWLINE`, `DEDENT`, and `EOF` tokens anchor as zero-width
///   spans at the physical EOF cursor without advancing `line` or `column`.
public final class GdTokenizer {

    /// Tab width used for indentation level counting, aligned with Godot's default
    /// (`tab_size = 4`); the editor-settings override only exists in TOOLS builds.
    /// Package-visible so the parser's poisoned-region indent gate uses the same unit.
    static final int TAB_SIZE = 4;

    /// Diagnostic message emitted when a string literal reaches EOF without its closing quote.
    static final String UNTERMINATED_STRING_ERROR = "Unterminated string.";

    /// Godot keyword table (vendor/godot/gdscript_tokenizer.cpp `KEYWORDS` macro list).
    /// Keywords are ASCII-only with lengths between 2 and 10.
    private static final Map<String, GdTokenType> KEYWORDS = Map.ofEntries(
            Map.entry("as", GdTokenType.AS),
            Map.entry("and", GdTokenType.AND),
            Map.entry("assert", GdTokenType.ASSERT),
            Map.entry("await", GdTokenType.AWAIT),
            Map.entry("break", GdTokenType.BREAK),
            Map.entry("breakpoint", GdTokenType.BREAKPOINT),
            Map.entry("class", GdTokenType.CLASS),
            Map.entry("class_name", GdTokenType.CLASS_NAME),
            Map.entry("const", GdTokenType.CONST),
            Map.entry("continue", GdTokenType.CONTINUE),
            Map.entry("elif", GdTokenType.ELIF),
            Map.entry("else", GdTokenType.ELSE),
            Map.entry("enum", GdTokenType.ENUM),
            Map.entry("extends", GdTokenType.EXTENDS),
            Map.entry("for", GdTokenType.FOR),
            Map.entry("func", GdTokenType.FUNC),
            Map.entry("if", GdTokenType.IF),
            Map.entry("in", GdTokenType.IN),
            Map.entry("is", GdTokenType.IS),
            Map.entry("match", GdTokenType.MATCH),
            Map.entry("namespace", GdTokenType.NAMESPACE),
            Map.entry("not", GdTokenType.NOT),
            Map.entry("or", GdTokenType.OR),
            Map.entry("pass", GdTokenType.PASS),
            Map.entry("preload", GdTokenType.PRELOAD),
            Map.entry("return", GdTokenType.RETURN),
            Map.entry("self", GdTokenType.SELF),
            Map.entry("signal", GdTokenType.SIGNAL),
            Map.entry("static", GdTokenType.STATIC),
            Map.entry("super", GdTokenType.SUPER),
            Map.entry("trait", GdTokenType.TRAIT),
            Map.entry("var", GdTokenType.VAR),
            Map.entry("void", GdTokenType.VOID),
            Map.entry("while", GdTokenType.WHILE),
            Map.entry("when", GdTokenType.WHEN),
            Map.entry("yield", GdTokenType.YIELD),
            Map.entry("INF", GdTokenType.CONST_INF),
            Map.entry("NAN", GdTokenType.CONST_NAN),
            Map.entry("PI", GdTokenType.CONST_PI),
            Map.entry("TAU", GdTokenType.CONST_TAU)
    );

    // Scanning state. `chars`/`byteOffsets` play the role of Godot's `_source`/`_current`
    // char32_t cursor; `position` is the consumed code-point count.
    private String source = "";
    private int[] chars = new int[0];
    private int[] byteOffsets = new int[0];
    private int position;
    private int line = 1;
    private int column = 1;
    private long scanSteps;

    // Current multi-char token window (Godot's `_start`/`start_line`/`start_column`).
    private int tokenStart;
    private int startLine = 1;
    private int startColumn = 1;

    // Mode configuration preserved across `setSourceCode` resets (standalone vs. managed).
    private final boolean emitRawClosers;
    private final @Nullable Boolean initialMultilineMode;

    // Line progression, newline/EOF settlement, and active multiline/continuation flags.
    private boolean lineContinuation;
    private boolean lineHasContent;
    private boolean eofSettled;
    private @Nullable Boolean explicitMultilineMode;
    private boolean pendingNewline;
    private GdToken lastNewline;

    // Suppressed-layout replay state for managed multiline escapes: captures the suppressed
    // NEWLINE, its one-token expiration flag, the last non-ERROR token's code-point start (used
    // to inspect leading whitespace on an escaped line), and whether that token followed `\`.
    private @Nullable GdToken suppressedNewline;
    private boolean clearSuppressedOnNextScan;
    private int lastReturnedTokenStart;
    private boolean lastTokenFollowedContinuation;
    private boolean lastErrorFollowedContinuation;
    private int hardNewlineCount;
    private int logicalLineStartLine = 1;
    private int lastTokenLogicalStartLine = 1;
    private int lastErrorLogicalStartLine = 1;

    // Indentation levels and expression-block snapshots (used for inline/indented lambda bodies).
    private int pendingIndents;
    private int indentChar;
    private final List<Integer> indentStack = new ArrayList<>();
    private final List<List<Integer>> indentStackStack = new ArrayList<>();

    // Standalone lexical bracket-pairing stacks (unused in managed mode).
    private final List<Integer> parenStack = new ArrayList<>();
    private final List<Integer> openParenIndices = new ArrayList<>();
    private final List<Integer> openBracketIndices = new ArrayList<>();
    private final List<Integer> openBraceIndices = new ArrayList<>();

    private record QueuedError(GdToken token, boolean followedContinuation, int logicalStartLine) {
    }

    // Diagnostic queue and side-channel trivia/continuation records collected during scanning.
    private final List<QueuedError> errorStack = new ArrayList<>();
    private final List<GdComment> comments = new ArrayList<>();
    private final List<Integer> continuationLines = new ArrayList<>();

    public GdTokenizer() {
        this("", false, null);
    }

    public GdTokenizer(@NotNull String source) {
        this(source, false, null);
    }

    private GdTokenizer(@NotNull String source, boolean emitRawClosers, @Nullable Boolean initialMultilineMode) {
        this.emitRawClosers = emitRawClosers;
        this.initialMultilineMode = initialMultilineMode;
        setSourceCode(source);
    }

    /// Creates a managed tokenizer for syntactic consumers (parser and completion stream):
    /// explicit `setMultilineMode(boolean)` starts at `false` and always overrides
    /// `parenStack`, while closing brackets always emit their real closer token instead
    /// of lexical pairing `ERROR` tokens.
    static @NotNull GdTokenizer managed(@NotNull String source) {
        return new GdTokenizer(source, true, Boolean.FALSE);
    }

    /// Resets the entire tokenizer state for the new source while preserving whether this
    /// instance runs in standalone or managed mode.
    public void setSourceCode(@NotNull String source) {
        this.source = Objects.requireNonNull(source, "source must not be null");
        var length = source.length();
        var codePoints = new int[source.codePointCount(0, length)];
        var offsets = new int[codePoints.length + 1];
        var cpIndex = 0;
        var byteOffset = 0;
        for (var i = 0; i < length; ) {
            var codePoint = source.codePointAt(i);
            i += Character.charCount(codePoint);
            codePoints[cpIndex] = codePoint;
            offsets[cpIndex] = byteOffset;
            byteOffset += SourceTextIndex.utf8Length(codePoint);
            cpIndex++;
        }
        offsets[codePoints.length] = byteOffset;
        this.chars = codePoints;
        this.byteOffsets = offsets;
        position = 0;
        scanSteps = 0;
        line = 1;
        column = 1;
        tokenStart = 0;
        startLine = 1;
        startColumn = 1;
        lineContinuation = false;
        lastTokenFollowedContinuation = false;
        lastErrorFollowedContinuation = false;
        hardNewlineCount = 0;
        logicalLineStartLine = 1;
        lastTokenLogicalStartLine = 1;
        lastErrorLogicalStartLine = 1;
        lineHasContent = false;
        eofSettled = false;
        explicitMultilineMode = initialMultilineMode;
        pendingNewline = false;
        lastNewline = null;
        suppressedNewline = null;
        clearSuppressedOnNextScan = false;
        lastReturnedTokenStart = 0;
        pendingIndents = 0;
        indentStack.clear();
        indentStackStack.clear();
        parenStack.clear();
        openParenIndices.clear();
        openBracketIndices.clear();
        openBraceIndices.clear();
        indentChar = 0;
        errorStack.clear();
        comments.clear();
        continuationLines.clear();
    }

    /// Comment trivia collected so far, in source order. Never part of the token stream.
    public @NotNull List<GdComment> getComments() {
        return Collections.unmodifiableList(comments);
    }

    /// The live mutable trivia list for the parser (same package): it grows during scanning,
    /// and the parser both reads ahead and temporarily re-inserts deferred prefixes into it.
    @NotNull List<GdComment> modifiableComments() {
        return comments;
    }

    /// Port of `get_continuation_lines`: 1-based lines produced by backslash continuations.
    public @NotNull List<Integer> getContinuationLines() {
        return Collections.unmodifiableList(continuationLines);
    }

    /// Configures explicit multiline mode for this tokenizer. When called, this explicit
    /// setting overrides bracket-based auto-suppression (`parenStack`) until reset via
    /// `setSourceCode(String)`.
    public void setMultilineMode(boolean state) {
        explicitMultilineMode = state;
    }

    /// Whether multiline layout suppression is currently active (explicit override if set,
    /// otherwise whether any opening bracket remains on `parenStack`).
    public boolean isMultilineMode() {
        return effectiveMultilineMode();
    }

    private boolean effectiveMultilineMode() {
        return explicitMultilineMode != null ? explicitMultilineMode : !parenStack.isEmpty();
    }

    /// Port of `push_expression_indented_block`: snapshots the indent stack so a lambda
    /// (or a block inside an expression) can temporarily manipulate indentation.
    public void pushExpressionIndentedBlock() {
        indentStackStack.add(List.copyOf(indentStack));
    }

    /// Port of `pop_expression_indented_block`. Godot silently no-ops on an empty stack
    /// (`ERR_FAIL_COND`); misuse here is an internal bug, so this port throws instead.
    public void popExpressionIndentedBlock() {
        if (indentStackStack.isEmpty()) {
            throw new IllegalStateException("popExpressionIndentedBlock without a matching push");
        }
        var snapshot = indentStackStack.removeLast();
        indentStack.clear();
        indentStack.addAll(snapshot);
    }

    /// Emits synthetic zero-width layout closure tokens (`NEWLINE` followed by one `DEDENT` per
    /// restored outer indentation level) when a nested expression block exits after `EOF` has
    /// already been settled. Clears `indentStack` and any dangling `suppressedNewline` so the
    /// parser can close outer suites cleanly with balanced tokenizer state.
    @NotNull List<GdToken> drainRestoredEofDedents() {
        if (!eofSettled || indentStack.isEmpty()) {
            return List.of();
        }
        suppressedNewline = null;
        var eofByte = byteOffsets[position];
        var count = indentStack.size();
        indentStack.clear();
        var tokens = new ArrayList<GdToken>(count + 1);
        tokens.add(new GdToken(GdTokenType.NEWLINE, "", GdLiteralKind.NONE, null,
                eofByte, eofByte, line, column, line, column));
        for (var i = 0; i < count; i++) {
            tokens.add(new GdToken(GdTokenType.DEDENT, "", GdLiteralKind.NONE, null,
                    eofByte, eofByte, line, column, line, column));
        }
        return List.copyOf(tokens);
    }

    /// Current 1-based scanner line (Godot diagnostic coordinate).
    public int getLine() {
        return line;
    }

    /// Current 1-based scanner column in characters (Godot diagnostic coordinate).
    public int getColumn() {
        return column;
    }

    /// Byte-offset/line conversion index over the current source, for `Range` conversion.
    SourceTextIndex sourceTextIndex() {
        return SourceTextIndex.of(source);
    }

    int[] codePoints() {
        return chars;
    }

    int[] byteOffsets() {
        return byteOffsets;
    }

    boolean isLineContinuation() {
        return lastTokenFollowedContinuation;
    }

    boolean isErrorLineContinuation() {
        return lastErrorFollowedContinuation;
    }

    int hardNewlineCount() {
        return hardNewlineCount;
    }

    int tokenLogicalStartLine() {
        return lastTokenLogicalStartLine;
    }

    int errorLogicalStartLine() {
        return lastErrorLogicalStartLine;
    }

    boolean hasBalancedLayoutState() {
        return indentStack.isEmpty()
                && indentStackStack.isEmpty()
                && pendingIndents == 0
                && !pendingNewline
                && suppressedNewline == null
                && errorStack.isEmpty();
    }

    boolean hasBalancedPersistentState() {
        return indentStackStack.isEmpty() && suppressedNewline == null;
    }

    boolean hasBalancedDelimiterState() {
        return parenStack.isEmpty() && openParenIndices.isEmpty()
                && openBracketIndices.isEmpty() && openBraceIndices.isEmpty();
    }

    /// Reconciles suppressed multiline layout against `indentStack` once multiline suppression
    /// turns off at an escaped cross-line `lookahead` token. Returns layout tokens (`NEWLINE`
    /// and any needed `INDENT` or `DEDENT`) plus any indentation `ERROR` tokens to emit ahead of
    /// `lookahead`, preserving physical coordinates and deduplicating already-emitted layout.
    @NotNull List<GdToken> reconcileLayoutForReplay(@NotNull GdToken lookahead) {
        if (effectiveMultilineMode() || suppressedNewline == null) {
            return List.of();
        }
        var nl = suppressedNewline;
        suppressedNewline = null;
        if (lookahead.type() == GdTokenType.NEWLINE || lookahead.type() == GdTokenType.INDENT) {
            return List.of();
        }
        if (lookahead.type() == GdTokenType.DEDENT || lookahead.type() == GdTokenType.EOF) {
            return List.of(nl);
        }
        var lineStartIdx = Math.max(0, lastReturnedTokenStart - (lookahead.startColumn() - 1));
        var indentCount = 0;
        var lineIndentChar = 0;
        var mixed = false;
        for (var i = lineStartIdx; i < lastReturnedTokenStart && i < chars.length; i++) {
            var ch = chars[i];
            if (ch == '\t') {
                indentCount += TAB_SIZE;
            } else if (ch == ' ') {
                indentCount += 1;
            } else if (ch == '\r') {
                continue;
            } else {
                break;
            }
            if (lineIndentChar == 0) {
                lineIndentChar = ch;
            }
            mixed = mixed || ch != lineIndentChar;
        }
        var replayed = new ArrayList<GdToken>(4);
        replayed.add(nl);
        applyIndentationStep(
                indentCount,
                lineIndentChar,
                mixed,
                lineStartIdx,
                lastReturnedTokenStart,
                lookahead.startLine(),
                lookahead.startColumn(),
                replayed
        );
        return List.copyOf(replayed);
    }

    /// Validates leading-whitespace indentation (`mixed`, `indentChar` consistency, and dedent
    /// alignment) and applies the resulting state change to `indentStack`. When `replayOut` is
    /// null, behaves as live `checkIndent()` (updating `pendingIndents` and pushing errors onto
    /// `errorStack`); when non-null, appends replay `ERROR`/`INDENT`/`DEDENT` tokens directly.
    private void applyIndentationStep(
            int indentCount,
            int lineIndentChar,
            boolean mixed,
            int lineStartIdx,
            int wsEndIdx,
            int targetLine,
            int targetColumn,
            @Nullable List<GdToken> replayOut
    ) {
        if (mixed) {
            emitIndentationStepError(
                    "Mixed use of tabs and spaces for indentation.",
                    lineStartIdx,
                    wsEndIdx,
                    targetLine,
                    targetColumn,
                    replayOut
            );
        }

        if (indentCount == 0) {
            if (replayOut == null) {
                // A line beginning with a stray CR and no spaces/tabs dedents to column 1
                // without recording a file-wide indentation character.
                pendingIndents -= indentStack.size();
                indentStack.clear();
            } else {
                var wsText = new String(chars, lineStartIdx, Math.max(0, wsEndIdx - lineStartIdx));
                var wsStartByte = byteOffsets[lineStartIdx];
                var wsEndByte = byteOffsets[wsEndIdx];
                while (!indentStack.isEmpty()) {
                    indentStack.removeLast();
                    replayOut.add(new GdToken(GdTokenType.DEDENT, wsText, GdLiteralKind.NONE, null,
                            wsStartByte, wsEndByte, targetLine, 1, targetLine, targetColumn));
                }
            }
            return;
        }

        // Check indentation character consistency across the file.
        if (indentChar == 0) {
            // First time indenting, choose character now.
            indentChar = lineIndentChar;
        } else if (lineIndentChar != indentChar) {
            emitIndentationStepError(
                    "Used " + indentCharName(lineIndentChar) + " character for indentation instead of "
                            + indentCharName(indentChar) + " as used before in the file.",
                    lineStartIdx,
                    wsEndIdx,
                    targetLine,
                    targetColumn,
                    replayOut
            );
        }

        // Check if indent or dedent.
        var previousIndent = indentStack.isEmpty() ? 0 : indentStack.getLast();
        if (indentCount == previousIndent) {
            return;
        }
        if (indentCount > previousIndent) {
            indentStack.add(indentCount);
            if (replayOut == null) {
                pendingIndents++;
            } else {
                var wsText = new String(chars, lineStartIdx, Math.max(0, wsEndIdx - lineStartIdx));
                replayOut.add(new GdToken(GdTokenType.INDENT, wsText, GdLiteralKind.NONE, null,
                        byteOffsets[lineStartIdx], byteOffsets[wsEndIdx], targetLine, 1, targetLine, targetColumn));
            }
            return;
        }

        // Indentation decreased (dedent).
        if (replayOut == null && indentStack.isEmpty()) {
            pushError(makeIndentError("Tokenizer bug: trying to dedent without previous indent."));
            return;
        }
        var wsText = replayOut == null
                ? ""
                : new String(chars, lineStartIdx, Math.max(0, wsEndIdx - lineStartIdx));
        var wsStartByte = byteOffsets[lineStartIdx];
        var wsEndByte = byteOffsets[wsEndIdx];
        while (!indentStack.isEmpty() && indentStack.getLast() > indentCount) {
            indentStack.removeLast();
            if (replayOut == null) {
                pendingIndents--;
            } else {
                replayOut.add(new GdToken(GdTokenType.DEDENT, wsText, GdLiteralKind.NONE, null,
                        wsStartByte, wsEndByte, targetLine, 1, targetLine, targetColumn));
            }
        }
        if (indentStack.isEmpty() || indentStack.getLast() != indentCount) {
            // Mismatched indentation alignment; stay lenient and keep this level.
            emitIndentationStepError(
                    "Unindent doesn't match the previous indentation level.",
                    lineStartIdx,
                    wsEndIdx,
                    targetLine,
                    targetColumn,
                    replayOut
            );
            indentStack.add(indentCount);
        }
    }

    private void emitIndentationStepError(
            @NotNull String message,
            int lineStartIdx,
            int wsEndIdx,
            int targetLine,
            int targetColumn,
            @Nullable List<GdToken> replayOut
    ) {
        var error = makeSpanError(message, lineStartIdx, targetLine, 1, wsEndIdx, targetLine, targetColumn);
        if (replayOut == null) {
            pushError(error);
        } else {
            replayOut.add(error);
        }
    }

    private GdToken returnScannedToken(GdToken token, int tokenStartIndex) {
        return returnScannedToken(token, tokenStartIndex, false);
    }

    private GdToken returnScannedToken(GdToken token, int tokenStartIndex, boolean followedContinuation) {
        if (token.type() != GdTokenType.ERROR) {
            clearSuppressedOnNextScan = true;
            lastReturnedTokenStart = tokenStartIndex;
            lastTokenFollowedContinuation = followedContinuation;
            lastTokenLogicalStartLine = logicalLineStartLine;
            lastErrorFollowedContinuation = false;
            lastErrorLogicalStartLine = logicalLineStartLine;
        } else {
            lastErrorFollowedContinuation = followedContinuation;
            lastErrorLogicalStartLine = logicalLineStartLine;
        }
        return token;
    }

    /// Port of `scan()`: returns the next token, draining queued errors first (LIFO),
    /// then a pending newline, then pending indent/dedent tokens, then dispatches on the
    /// next character. At end of input returns an `EOF` token.
    ///
    /// Godot recurses `scan()` for line continuations; the JVM does not eliminate tail
    /// calls, so this port loops instead (identical state flow, bounded stack usage).
    public @NotNull GdToken scan() {
        if (clearSuppressedOnNextScan) {
            suppressedNewline = null;
            clearSuppressedOnNextScan = false;
        }
        while (true) {
            scanSteps++;
            if (hasError()) {
                return popError();
            }

            skipWhitespace();

            if (pendingNewline) {
                pendingNewline = false;
                if (!effectiveMultilineMode()) {
                    // Don't return newline tokens in multiline mode.
                    return returnScannedToken(lastNewline, position);
                }
                suppressedNewline = lastNewline;
            }

            // Errors queued while skipping whitespace come before any regular token.
            if (hasError()) {
                return popError();
            }

            tokenStart = position;
            startLine = line;
            startColumn = column;

            if (pendingIndents != 0) {
                if (pendingIndents > 0) {
                    // Indent tokens cover the line's leading whitespace.
                    tokenStart -= startColumn - 1;
                    startColumn = 1;
                    pendingIndents--;
                    return returnScannedToken(makeToken(GdTokenType.INDENT), tokenStart);
                }
                pendingIndents++;
                if (isAtEnd()) {
                    return returnScannedToken(new GdToken(GdTokenType.DEDENT, "", GdLiteralKind.NONE, null,
                            byteOffsets[position], byteOffsets[position], line, column, line, column), position);
                }
                tokenStart -= startColumn - 1;
                startColumn = 1;
                return returnScannedToken(makeToken(GdTokenType.DEDENT), tokenStart);
            }

            if (isAtEnd()) {
                if (!eofSettled) {
                    settleEof();
                    if (pendingNewline || pendingIndents != 0) {
                        continue;
                    }
                }
                return returnScannedToken(new GdToken(GdTokenType.EOF, "", GdLiteralKind.NONE, null,
                        byteOffsets[position], byteOffsets[position], line, column, line, column), position);
            }

            var c = advance();

            if (c == '\\') {
                // Line continuation with backslash.
                if (peek() == '\r') {
                    if (peek(1) != '\n') {
                        var followedContinuation = lineContinuation;
                        lineContinuation = false;
                        return returnScannedToken(
                                emitContent(makeError("Unexpected carriage return character.")),
                                tokenStart,
                                followedContinuation
                        );
                    }
                    advance();
                }
                if (peek() != '\n') {
                    var followedContinuation = lineContinuation;
                    lineContinuation = false;
                    return returnScannedToken(
                            emitContent(makeError("Expected new line after \"\\\".")),
                            tokenStart,
                            followedContinuation
                    );
                }
                advance();
                newline(false);
                lineContinuation = true;
                // Skip whitespace/comment lines after the backslash.
                skipWhitespace();
                continuationLines.add(line);
                // Loop instead of tail recursion to keep stack depth O(1).
                continue;
            }

            var followedContinuation = lineContinuation;
            lineContinuation = false;

            if (isDigit(c)) {
                return returnScannedToken(emitContent(number()), tokenStart, followedContinuation);
            } else if (c == 'r' && (peek() == '"' || peek() == '\'')) {
                // Raw string literals.
                return returnScannedToken(emitContent(string()), tokenStart, followedContinuation);
            } else if (isUnicodeIdentifierStart(c)) {
                return returnScannedToken(emitContent(potentialIdentifier()), tokenStart, followedContinuation);
            }

            return returnScannedToken(emitContent(dispatch(c)), tokenStart, followedContinuation);
        }
    }

    private GdToken emitContent(GdToken token) {
        lineHasContent = true;
        return token;
    }

    /// Settles EOF state once per stream without mutating physical `(line, column)`:
    /// schedules a zero-width synthetic `NEWLINE` only if the current logical line emitted
    /// content and is not suppressed by multiline mode, and flushes any remaining indent levels.
    private void settleEof() {
        eofSettled = true;
        if (lineHasContent) {
            lineHasContent = false;
            var eofByte = byteOffsets[position];
            var eofNewline = new GdToken(GdTokenType.NEWLINE, "", GdLiteralKind.NONE, null,
                    eofByte, eofByte, line, column, line, column);
            if (!effectiveMultilineMode()) {
                lastNewline = eofNewline;
                pendingNewline = true;
            } else if (suppressedNewline == null) {
                suppressedNewline = eofNewline;
            }
        }
        if (!indentStack.isEmpty()) {
            pendingIndents -= indentStack.size();
            indentStack.clear();
        }
    }

    /// Character dispatch of `scan()` after identifier/number/raw-string handling.
    private GdToken dispatch(int c) {
        return switch (c) {
            // String literals.
            case '"', '\'' -> string();
            // Annotation.
            case '@' -> annotation();
            // Single characters.
            case '~' -> makeToken(GdTokenType.TILDE);
            case ',' -> makeToken(GdTokenType.COMMA);
            case ':' -> makeToken(GdTokenType.COLON);
            case ';' -> makeToken(GdTokenType.SEMICOLON);
            case '$' -> makeToken(GdTokenType.DOLLAR);
            case '?' -> makeToken(GdTokenType.QUESTION_MARK);
            case '`' -> makeToken(GdTokenType.BACKTICK);
            // Parens.
            case '(' -> {
                pushParen('(');
                yield makeToken(GdTokenType.PARENTHESIS_OPEN);
            }
            case '[' -> {
                pushParen('[');
                yield makeToken(GdTokenType.BRACKET_OPEN);
            }
            case '{' -> {
                pushParen('{');
                yield makeToken(GdTokenType.BRACE_OPEN);
            }
            case ')' -> handleCloser(')', '(', GdTokenType.PARENTHESIS_CLOSE);
            case ']' -> handleCloser(']', '[', GdTokenType.BRACKET_CLOSE);
            case '}' -> handleCloser('}', '{', GdTokenType.BRACE_CLOSE);
            // Double characters.
            case '!' -> {
                if (peek() == '=') {
                    advance();
                    yield makeToken(GdTokenType.BANG_EQUAL);
                }
                yield makeToken(GdTokenType.BANG);
            }
            case '.' -> {
                if (peek() == '.') {
                    advance();
                    if (peek() == '.') {
                        advance();
                        yield makeToken(GdTokenType.PERIOD_PERIOD_PERIOD);
                    }
                    yield makeToken(GdTokenType.PERIOD_PERIOD);
                } else if (isDigit(peek())) {
                    // Number starting with '.'.
                    yield number();
                }
                yield makeToken(GdTokenType.PERIOD);
            }
            // '+'/'-' never absorb a following number; they always yield operator
            // tokens (Godot's `can_precede_bin_op` sign-absorption branch is removed).
            case '+' -> {
                if (peek() == '=') {
                    advance();
                    yield makeToken(GdTokenType.PLUS_EQUAL);
                }
                yield makeToken(GdTokenType.PLUS);
            }
            case '-' -> {
                if (peek() == '=') {
                    advance();
                    yield makeToken(GdTokenType.MINUS_EQUAL);
                } else if (peek() == '>') {
                    advance();
                    yield makeToken(GdTokenType.FORWARD_ARROW);
                }
                yield makeToken(GdTokenType.MINUS);
            }
            case '*' -> {
                if (peek() == '=') {
                    advance();
                    yield makeToken(GdTokenType.STAR_EQUAL);
                } else if (peek() == '*') {
                    if (peek(1) == '=') {
                        advance();
                        advance(); // Advance both '*' and '='.
                        yield makeToken(GdTokenType.STAR_STAR_EQUAL);
                    }
                    advance();
                    yield makeToken(GdTokenType.STAR_STAR);
                }
                yield makeToken(GdTokenType.STAR);
            }
            case '/' -> {
                if (peek() == '=') {
                    advance();
                    yield makeToken(GdTokenType.SLASH_EQUAL);
                }
                yield makeToken(GdTokenType.SLASH);
            }
            case '%' -> {
                if (peek() == '=') {
                    advance();
                    yield makeToken(GdTokenType.PERCENT_EQUAL);
                }
                yield makeToken(GdTokenType.PERCENT);
            }
            case '^' -> {
                if (peek() == '=') {
                    advance();
                    yield makeToken(GdTokenType.CARET_EQUAL);
                } else if (peek() == '"' || peek() == '\'') {
                    // Node path.
                    yield string();
                }
                yield makeToken(GdTokenType.CARET);
            }
            case '&' -> {
                if (peek() == '&') {
                    advance();
                    yield makeToken(GdTokenType.AMPERSAND_AMPERSAND);
                } else if (peek() == '=') {
                    advance();
                    yield makeToken(GdTokenType.AMPERSAND_EQUAL);
                } else if (peek() == '"' || peek() == '\'') {
                    // StringName.
                    yield string();
                }
                yield makeToken(GdTokenType.AMPERSAND);
            }
            case '|' -> {
                if (peek() == '|') {
                    advance();
                    yield makeToken(GdTokenType.PIPE_PIPE);
                } else if (peek() == '=') {
                    advance();
                    yield makeToken(GdTokenType.PIPE_EQUAL);
                }
                yield makeToken(GdTokenType.PIPE);
            }
            // Potential VCS conflict markers.
            case '=' -> {
                if (peek() == '=') {
                    yield checkVcsMarker('=', GdTokenType.EQUAL_EQUAL);
                }
                yield makeToken(GdTokenType.EQUAL);
            }
            case '<' -> {
                if (peek() == '=') {
                    advance();
                    yield makeToken(GdTokenType.LESS_EQUAL);
                } else if (peek() == '<') {
                    if (peek(1) == '=') {
                        advance();
                        advance(); // Advance both '<' and '='.
                        yield makeToken(GdTokenType.LESS_LESS_EQUAL);
                    }
                    yield checkVcsMarker('<', GdTokenType.LESS_LESS);
                }
                yield makeToken(GdTokenType.LESS);
            }
            case '>' -> {
                if (peek() == '=') {
                    advance();
                    yield makeToken(GdTokenType.GREATER_EQUAL);
                } else if (peek() == '>') {
                    if (peek(1) == '=') {
                        advance();
                        advance(); // Advance both '>' and '='.
                        yield makeToken(GdTokenType.GREATER_GREATER_EQUAL);
                    }
                    yield checkVcsMarker('>', GdTokenType.GREATER_GREATER);
                }
                yield makeToken(GdTokenType.GREATER);
            }
            default -> {
                if (isWhitespaceChar(c)) {
                    yield makeError("Invalid white space character " + formatCodePoint(c) + ".");
                }
                yield makeError("Invalid character \"" + codePointToString(c) + "\" (" + formatCodePoint(c) + ").");
            }
        };
    }

    /// Port of `check_indent()`. Must only be called at the beginning of a line
    /// (column == 1). Skips empty and comment-only lines (which never emit `NEWLINE` nor
    /// affect indentation) and updates the indent stack / pending indent counter.
    private void checkIndent() {
        // Leave EOF dedents to settleEof() so any synthetic EOF NEWLINE is emitted first.
        while (!isAtEnd()) {
            var firstChar = peek();
            if (firstChar != ' ' && firstChar != '\t' && firstChar != '\r'
                    && firstChar != '\n' && firstChar != '#') {
                // First character of the line is not whitespace: clear all indentation
                // levels, unless in a continuation or in multiline mode.
                if (lineContinuation || effectiveMultilineMode()) {
                    return;
                }
                pendingIndents -= indentStack.size();
                indentStack.clear();
                return;
            }

            // Consume leading spaces, tabs, and any stray CR before deciding whether the line
            // is empty, comment-only, at EOF, or starts a statement.
            var indentCount = 0;
            var lineIndentChar = 0;
            var mixed = false;
            var pushedStrayCr = false;
            while (!isAtEnd()) {
                var space = peek();
                if (space == '\t') {
                    indentCount += TAB_SIZE;
                } else if (space == ' ') {
                    indentCount += 1;
                } else if (space == '\r' && peek(1) != '\n') {
                    advance();
                    pushError(makeCursorError("Stray carriage return character in source code."));
                    pushedStrayCr = true;
                    continue;
                } else {
                    break;
                }
                if (lineIndentChar == 0) {
                    lineIndentChar = space;
                }
                mixed = mixed || space != lineIndentChar;
                advance();
            }

            if (isAtEnd()) {
                if (pushedStrayCr && lineContinuation) {
                    lineHasContent = true;
                    lineContinuation = false;
                }
                return;
            }

            if (pushedStrayCr
                    && lineContinuation
                    && (peek() == '\n' || peek() == '#' || (peek() == '\r' && peek(1) == '\n'))) {
                // A continued line containing a stray CR error is not blank/comment-only: stop so
                // the queued error drains and the trailing line break closes the continuation.
                lineHasContent = true;
                lineContinuation = false;
                return;
            }

            if (peek() == '\r') {
                advance();
            }
            if (peek() == '\n') {
                // Empty line, keep going.
                advance();
                newline(false, !lineContinuation);
                continue;
            }
            if (peek() == '#') {
                // Comment-only line: record trivia, then skip to the next line.
                var commentStart = position;
                var commentLine = line;
                var text = new StringBuilder();
                while (peek() != '\n' && !isAtEnd()) {
                    text.appendCodePoint(advance());
                }
                comments.add(new GdComment(text.toString(), true, commentLine,
                        byteOffsets[commentStart], byteOffsets[position]));
                if (isAtEnd()) {
                    return;
                }
                advance(); // Consume '\n'.
                newline(false, !lineContinuation);
                continue;
            }

            if (lineContinuation || effectiveMultilineMode()) {
                // Whitespace at the beginning of the line was consumed; a continuation or
                // multiline mode must not produce indentation changes.
                return;
            }

            applyIndentationStep(
                    indentCount,
                    lineIndentChar,
                    mixed,
                    position - (column - 1),
                    position,
                    line,
                    column,
                    null
            );
            break;
        }
    }

    /// Port of `_skip_whitespace()`. At the beginning of a line it delegates to
    /// `checkIndent()`; mid-line it consumes spaces, tabs, newlines (scheduling a pending
    /// `NEWLINE` for non-empty lines) and trailing comments.
    private void skipWhitespace() {
        if (pendingIndents != 0) {
            // Still have some indent/dedent tokens to give.
            return;
        }

        var isBol = column == 1; // Beginning of line.
        if (isBol) {
            checkIndent();
            return;
        }

        for (; ; ) {
            var c = peek();
            switch (c) {
                case ' ', '\t' -> advance();
                case '\r' -> {
                    advance(); // Consume either way.
                    if (peek() != '\n') {
                        pushError(makeCursorError("Stray carriage return character in source code."));
                        return;
                    }
                }
                case '\n' -> {
                    advance();
                    // Don't create newline tokens for empty lines (is_bol is always false
                    // here; the parameter is kept to mirror Godot's call shape).
                    newline(true, !lineContinuation);
                    checkIndent();
                }
                case '#' -> {
                    // Trailing comment after code on the same line.
                    var commentStart = position;
                    var commentLine = line;
                    var text = new StringBuilder();
                    while (peek() != '\n' && !isAtEnd()) {
                        text.appendCodePoint(advance());
                    }
                    comments.add(new GdComment(text.toString(), false, commentLine,
                            byteOffsets[commentStart], byteOffsets[position]));
                    if (isAtEnd()) {
                        return;
                    }
                    advance(); // Consume '\n'.
                    newline(true, !lineContinuation);
                    checkIndent();
                }
                default -> {
                    return;
                }
            }
        }
    }

    private void newline(boolean makeToken) {
        newline(makeToken, false);
    }

    /// Consumes a physical newline and advances the physical line cursor. When `makeToken`
    /// is set and no newline is pending (and this is not a line continuation), schedules a
    /// `NEWLINE` token covering the consumed `\n` and resets `lineHasContent`.
    private void newline(boolean makeToken, boolean hardBreak) {
        var prevLine = line;
        var prevColumn = column;
        line++;
        column = 1;
        if (hardBreak) {
            hardNewlineCount++;
            logicalLineStartLine = line;
        }
        if (makeToken && !pendingNewline && !lineContinuation) {
            int startByte;
            int endByte;
            int startCol;
            if (peek(-1) == '\n') {
                endByte = byteOffsets[position];
                startByte = endByte - 1;
                startCol = prevColumn - 1;
            } else {
                startByte = endByte = byteOffsets[position];
                startCol = prevColumn;
            }
            lastNewline = new GdToken(GdTokenType.NEWLINE, "", GdLiteralKind.NONE, null,
                    startByte, endByte, prevLine, startCol, line, column);
            pendingNewline = true;
            lineHasContent = false;
        }
    }

    /// Port of `number()`. Handles decimal/hex (`0x`)/binary (`0b`) integers, decimals,
    /// exponents and `_` separators, queueing Godot's exact numeric errors. The sign
    /// prefix branch is removed: signs never reach this method (see the class-level note).
    private GdToken number() {
        var base = 10;
        var hasDecimal = false;
        var hasExponent = false;
        var hasError = false;
        var needDigits = false;

        if (peek(-1) == '.') {
            hasDecimal = true;
        } else if (peek(-1) == '0') {
            if (peek() == 'x' || peek() == 'X') {
                // Hexadecimal.
                base = 16;
                needDigits = true;
                advance();
            } else if (peek() == 'b' || peek() == 'B') {
                // Binary.
                base = 2;
                needDigits = true;
                advance();
            }
        }

        if (base != 10 && isUnderscore(peek())) { // Disallow `0x_` and `0b_`.
            pushError(makeError("Unexpected underscore after \"0" + codePointToString(peek(-1)) + "\"."));
            hasError = true;
        }

        var previousWasUnderscore = false; // Allow `_` inside numbers for readability.
        while (digitCheck(base, peek()) || isUnderscore(peek())) {
            if (isUnderscore(peek())) {
                if (previousWasUnderscore) {
                    pushError(makeError("Multiple underscores cannot be adjacent in a numeric literal."));
                }
                previousWasUnderscore = true;
            } else {
                needDigits = false;
                previousWasUnderscore = false;
            }
            advance();
        }

        // It might be a ".." token (instead of decimal point) so we check if it's not.
        if (peek() == '.' && peek(1) != '.') {
            if (base == 10 && !hasDecimal) {
                hasDecimal = true;
            } else if (base == 10) {
                pushError(makeError("Cannot use a decimal point twice in a number."));
                hasError = true;
            } else if (base == 16) {
                pushError(makeError("Cannot use a decimal point in a hexadecimal number."));
                hasError = true;
            } else {
                pushError(makeError("Cannot use a decimal point in a binary number."));
                hasError = true;
            }
            if (!hasError) {
                advance();

                // Consume decimal digits. Disallow `10._`, but allow `10.`.
                if (isUnderscore(peek())) {
                    pushError(makeError("Unexpected underscore after decimal point."));
                    hasError = true;
                }
                previousWasUnderscore = false;
                while (isDigit(peek()) || isUnderscore(peek())) {
                    if (isUnderscore(peek())) {
                        if (previousWasUnderscore) {
                            pushError(makeError("Multiple underscores cannot be adjacent in a numeric literal."));
                        }
                        previousWasUnderscore = true;
                    } else {
                        previousWasUnderscore = false;
                    }
                    advance();
                }
            }
        }
        if (base == 10 && (peek() == 'e' || peek() == 'E')) {
            hasExponent = true;
            advance();
            if (peek() == '+' || peek() == '-') {
                // Exponent sign.
                advance();
            }
            // Consume exponent digits.
            if (!isDigit(peek())) {
                pushError(makeError("Expected exponent value after \"e\"."));
            }
            previousWasUnderscore = false;
            while (isDigit(peek()) || isUnderscore(peek())) {
                if (isUnderscore(peek())) {
                    if (previousWasUnderscore) {
                        pushError(makeError("Multiple underscores cannot be adjacent in a numeric literal."));
                    }
                    previousWasUnderscore = true;
                } else {
                    previousWasUnderscore = false;
                }
                advance();
            }
        }

        if (needDigits) {
            // No digits in hex or binary literal; returned immediately (not queued).
            return makeError("Expected " + (base == 16 ? "hexadecimal" : "binary")
                    + " digit after \"0" + (base == 16 ? "x" : "b") + "\".");
        }

        // Detect extra decimal point or a letter at the end of the number.
        if (!hasError && hasDecimal && peek() == '.' && peek(1) != '.') {
            pushError(makeError("Cannot use a decimal point twice in a number."));
        } else if (isUnicodeIdentifierStart(peek()) || isUnicodeIdentifierContinue(peek())) {
            pushError(makeError("Invalid numeric notation."));
        }

        return makeLiteral(base == 10 && (hasDecimal || hasExponent) ? GdLiteralKind.FLOAT : GdLiteralKind.INTEGER);
    }

    /// Port of `string()`. Handles single/double-quoted, triple-quoted (multiline), raw
    /// (`r"..."`), StringName (`&"..."`) and NodePath (`^"..."`) strings with Godot's
    /// escape validation and error reporting. The decoded value is not materialized (the
    /// AST contract keeps the source slice), but all consumption and error behavior —
    /// including UTF-16 surrogate pair validation of `\\uXXXX`/`\\UXXXXXX` escapes — is
    /// preserved.
    private GdToken string() {
        var isRaw = false;
        var isMultiline = false;
        var literalKind = GdLiteralKind.STRING;

        if (peek(-1) == 'r') {
            isRaw = true;
            advance();
        } else if (peek(-1) == '&') {
            literalKind = GdLiteralKind.STRING_NAME;
            advance();
        } else if (peek(-1) == '^') {
            literalKind = GdLiteralKind.NODE_PATH;
            advance();
        }

        var quoteChar = peek(-1);

        if (peek() == quoteChar && peek(1) == quoteChar) {
            isMultiline = true;
            // Consume all quotes.
            advance();
            advance();
        }

        // Pending lead surrogate of a `\\uXXXX`/`\\UXXXXXX` escape awaiting its trail
        // surrogate (Godot's `prev`/`prev_pos`/`prev_line` validation state).
        var prevSurrogate = 0;
        var prevSurrogateStart = 0;
        var prevSurrogateEnd = 0;
        var prevSurrogateStartLine = 0;
        var prevSurrogateStartColumn = 0;
        var prevSurrogateEndLine = 0;
        var prevSurrogateEndColumn = 0;

        for (; ; ) {
            if (isAtEnd()) {
                return makeError(UNTERMINATED_STRING_ERROR);
            }

            var ch = peek();

            // Invisible bidirectional control characters are rejected to avoid
            // Trojan-Source style confusion (aligned with Godot).
            if (ch == 0x200E || ch == 0x200F || (ch >= 0x202A && ch <= 0x202E) || (ch >= 0x2066 && ch <= 0x2069)) {
                String message;
                if (isRaw) {
                    message = "Invisible text direction control character present in the string, "
                            + "use regular string literal instead of r-string.";
                } else {
                    message = "Invisible text direction control character present in the string, escape it (\"\\u"
                            + Integer.toHexString(ch) + "\") to avoid confusion.";
                }
                pushError(makeSpanError(message, position, line, column, position + 1, line, column + 1));
            }

            if (ch == '\\') {
                // Escape pattern.
                var escapeStart = position;
                var escapeStartLine = line;
                var escapeStartColumn = column;
                advance();
                if (isAtEnd()) {
                    return makeError(UNTERMINATED_STRING_ERROR);
                }

                if (isRaw) {
                    // In raw strings only quote and backslash escapes are special;
                    // every other backslash stays verbatim.
                    if (peek() == quoteChar) {
                        advance();
                        if (isAtEnd()) {
                            return makeError(UNTERMINATED_STRING_ERROR);
                        }
                    } else if (peek() == '\\') { // For `\\\"`.
                        advance();
                        if (isAtEnd()) {
                            return makeError(UNTERMINATED_STRING_ERROR);
                        }
                    }
                } else {
                    var code = peek();
                    advance();
                    if (code == '\n') {
                        newline(false);
                    }
                    if (isAtEnd()) {
                        return makeError(UNTERMINATED_STRING_ERROR);
                    }

                    var escaped = 0;
                    var validEscape = true;

                    switch (code) {
                        case 'a', 'b', 'f', 'n', 'r', 't', 'v', '\'', '\"', '\\' -> {
                            // Single-character escapes; the decoded value is unused.
                        }
                        case 'U', 'u' -> {
                            // Hexadecimal unicode sequence: `\\uXXXX` or `\\UXXXXXX`.
                            var hexLen = (code == 'U') ? 6 : 4;
                            for (var j = 0; j < hexLen; j++) {
                                if (isAtEnd()) {
                                    return makeError(UNTERMINATED_STRING_ERROR);
                                }
                                var digit = peek();
                                int value;
                                if (isDigit(digit)) {
                                    value = digit - '0';
                                } else if (digit >= 'a' && digit <= 'f') {
                                    value = digit - 'a' + 10;
                                } else if (digit >= 'A' && digit <= 'F') {
                                    value = digit - 'A' + 10;
                                } else {
                                    // Make error, but keep parsing the string.
                                    var endLine = digit == '\n' ? line + 1 : line;
                                    var endCol = digit == '\n' ? 1 : column + 1;
                                    pushError(makeSpanError(
                                            "Invalid hexadecimal digit in unicode escape sequence.",
                                            position, line, column, position + 1, endLine, endCol));
                                    validEscape = false;
                                    break;
                                }
                                escaped <<= 4;
                                escaped |= value;
                                advance();
                            }
                        }
                        case '\r' -> {
                            if (peek() != '\n') {
                                // Carriage return without newline in string; leniently
                                // consumes the following character as well.
                                advance();
                            } else {
                                // Escaping CRLF newline: consume '\n' and advance line once.
                                advance();
                                newline(false);
                                validEscape = false;
                            }
                        }
                        // Line break was already counted immediately after consuming '\n'.
                        case '\n' -> validEscape = false;
                        default -> {
                            pushError(makeSpanError("Invalid escape in string.",
                                    escapeStart, escapeStartLine, escapeStartColumn, position, line, column));
                            validEscape = false;
                        }
                    }

                    // Validate UTF-16 surrogate pairing of unicode escapes.
                    if (validEscape) {
                        if ((escaped & 0xfffffc00) == 0xd800) {
                            if (prevSurrogate == 0) {
                                prevSurrogate = escaped;
                                prevSurrogateStart = escapeStart;
                                prevSurrogateEnd = position;
                                prevSurrogateStartLine = escapeStartLine;
                                prevSurrogateStartColumn = escapeStartColumn;
                                prevSurrogateEndLine = line;
                                prevSurrogateEndColumn = column;
                                continue;
                            } else {
                                pushError(makeSpanError(
                                        "Invalid UTF-16 sequence in string, unpaired lead surrogate.",
                                        escapeStart, escapeStartLine, escapeStartColumn, position, line, column));
                                prevSurrogate = 0;
                            }
                        } else if ((escaped & 0xfffffc00) == 0xdc00) {
                            if (prevSurrogate == 0) {
                                pushError(makeSpanError(
                                        "Invalid UTF-16 sequence in string, unpaired trail surrogate.",
                                        escapeStart, escapeStartLine, escapeStartColumn, position, line, column));
                            } else {
                                // Pair completed; the combined code point is unused.
                                prevSurrogate = 0;
                            }
                        }
                        if (prevSurrogate != 0) {
                            pushError(makeSpanError(
                                    "Invalid UTF-16 sequence in string, unpaired lead surrogate.",
                                    prevSurrogateStart, prevSurrogateStartLine, prevSurrogateStartColumn,
                                    prevSurrogateEnd, prevSurrogateEndLine, prevSurrogateEndColumn));
                            prevSurrogate = 0;
                        }
                    }
                }
            } else if (ch == quoteChar) {
                if (prevSurrogate != 0) {
                    pushError(makeSpanError(
                            "Invalid UTF-16 sequence in string, unpaired lead surrogate",
                            prevSurrogateStart, prevSurrogateStartLine, prevSurrogateStartColumn,
                            prevSurrogateEnd, prevSurrogateEndLine, prevSurrogateEndColumn));
                    prevSurrogate = 0;
                }
                advance();
                if (isMultiline) {
                    if (peek() == quoteChar && peek(1) == quoteChar) {
                        // Ended the multiline string. Consume all quotes.
                        advance();
                        advance();
                        break;
                    }
                    // Not a multiline termination; the quote stays part of the string.
                } else {
                    // Ended single-line string.
                    break;
                }
            } else {
                if (prevSurrogate != 0) {
                    pushError(makeSpanError(
                            "Invalid UTF-16 sequence in string, unpaired lead surrogate",
                            prevSurrogateStart, prevSurrogateStartLine, prevSurrogateStartColumn,
                            prevSurrogateEnd, prevSurrogateEndLine, prevSurrogateEndColumn));
                    prevSurrogate = 0;
                }
                advance();
                if (ch == '\n') {
                    newline(false);
                }
            }
        }

        return makeLiteral(literalKind);
    }

    /// Port of `potential_identifier()`. A lone `_` yields `UNDERSCORE`; ASCII candidates
    /// of keyword length are looked up in the keyword table; `true`/`false`/`null` become
    /// `LITERAL` tokens; everything else is an `IDENTIFIER`.
    private GdToken potentialIdentifier() {
        var onlyAscii = peek(-1) < 128;
        while (isUnicodeIdentifierContinue(peek())) {
            var c = advance();
            onlyAscii = onlyAscii && c < 128;
        }

        var length = position - tokenStart;

        if (length == 1 && peek(-1) == '_') {
            // Lone underscore.
            return makeToken(GdTokenType.UNDERSCORE);
        }

        var name = new String(chars, tokenStart, length);
        if (length < 2 || length > 10 || !onlyAscii) {
            // Cannot be a keyword: keywords are ASCII-only with lengths in [2, 10].
            return makeToken(GdTokenType.IDENTIFIER);
        }

        var keyword = KEYWORDS.get(name);
        if (keyword != null) {
            return makeToken(keyword);
        }

        // Special literals.
        return switch (name) {
            case "true" -> makeLiteral(GdLiteralKind.TRUE);
            case "false" -> makeLiteral(GdLiteralKind.FALSE);
            case "null" -> makeLiteral(GdLiteralKind.NULL);
            default -> makeToken(GdTokenType.IDENTIFIER);
        };
    }

    /// Port of `annotation()`: produces a single `ANNOTATION` token whose source includes
    /// the leading `@`. A missing identifier after `@` queues an error but still yields
    /// the annotation token (aligned with Godot).
    private GdToken annotation() {
        if (isUnicodeIdentifierStart(peek())) {
            advance();
        } else {
            pushError(makeError("Expected annotation identifier after \"@\"."));
        }
        while (isUnicodeIdentifierContinue(peek())) {
            advance();
        }
        return makeToken(GdTokenType.ANNOTATION);
    }

    /// Port of `check_vcs_marker()`: a run of 7+ identical `=`/`<`/`>` characters is a VCS
    /// conflict marker token; otherwise the second character is consumed for the regular
    /// double-character token.
    private GdToken checkVcsMarker(int test, GdTokenType doubleType) {
        var chars = 2; // Two already matched (first consumed, second peeked).
        var offset = 1;
        // Test before consuming characters, since we don't want to consume more than needed.
        while (peek(offset) == test) {
            chars++;
            offset++;
        }
        if (chars >= 7) {
            // It is a VCS conflict marker; consume all remaining characters of the run.
            while (chars > 1) {
                advance();
                chars--;
            }
            return makeToken(GdTokenType.VCS_CONFLICT_MARKER);
        }
        // Regular double character token: consume the second character.
        advance();
        return makeToken(doubleType);
    }

    // --- Token factories -------------------------------------------------------------

    /// Canonical token factory. Byte offsets derive from the code-point window
    /// `[tokenStartIndex, position)`, so they always describe physical source positions.
    ///
    /// ERROR tokens carry an empty `source` slice (their byte range stays exact): Godot
    /// shares its immutable String storage, so copying the whole token-so-far window is
    /// free there, whereas Java would copy per queued error — O(n^2) memory on inputs
    /// with many errors inside one long literal. The byte range remains the diagnostic
    /// position carrier.
    private GdToken newToken(GdTokenType type, GdLiteralKind literalKind, String errorMessage,
                             int tokenStartIndex, int startLine, int startColumn, int endLine, int endColumn) {
        var sourceSlice = type == GdTokenType.ERROR
                ? ""
                : new String(chars, tokenStartIndex, position - tokenStartIndex);
        return new GdToken(type, sourceSlice, literalKind, errorMessage,
                byteOffsets[tokenStartIndex], byteOffsets[position],
                startLine, startColumn, endLine, endColumn);
    }

    /// Port of `make_token()`: token over the current window with current coordinates.
    private GdToken makeToken(GdTokenType type) {
        return newToken(type, GdLiteralKind.NONE, null, tokenStart, startLine, startColumn, line, column);
    }

    /// Port of `make_literal()`.
    private GdToken makeLiteral(GdLiteralKind literalKind) {
        return newToken(GdTokenType.LITERAL, literalKind, null, tokenStart, startLine, startColumn, line, column);
    }

    /// Port of `make_error()`; valid within the current dispatch window.
    private GdToken makeError(String message) {
        return newToken(GdTokenType.ERROR, GdLiteralKind.NONE, message, tokenStart, startLine, startColumn, line, column);
    }

    /// Error token covering an explicit sub-span inside a multi-character literal.
    private GdToken makeSpanError(String message, int startIndex, int startLine, int startColumn,
                                  int endIndex, int endLine, int endColumn) {
        return new GdToken(GdTokenType.ERROR, "", GdLiteralKind.NONE, message,
                byteOffsets[startIndex], byteOffsets[endIndex],
                startLine, startColumn, endLine, endColumn);
    }

    /// Whitespace-phase error anchored at the just-consumed character (used for stray
    /// carriage returns). Godot reports these with the previous token's stale window;
    /// this port anchors them to the offending character instead.
    private GdToken makeCursorError(String message) {
        return new GdToken(GdTokenType.ERROR, "", GdLiteralKind.NONE, message,
                byteOffsets[position - 1], byteOffsets[position], line, column - 1, line, column);
    }

    /// Indentation-phase error covering the current line's leading whitespace.
    private GdToken makeIndentError(String message) {
        var lineStartIndex = position - (column - 1);
        return new GdToken(GdTokenType.ERROR, "", GdLiteralKind.NONE, message,
                byteOffsets[lineStartIndex], byteOffsets[position], line, 1, line, column);
    }

    /// Closer handler:
    /// - Managed mode: emits the real closer token directly in O(1) without touching `parenStack`.
    /// - Standalone mode:
    ///   1. Empty stack: emits orphan-closer ERROR.
    ///   2. Top matches: pops top once and emits the real closer token.
    ///   3. Ancestor exists: unwinds down to and including the nearest matching ancestor,
    ///      and emits an ERROR naming every unclosed opener skipped along the way.
    ///   4. No ancestor: pops only the mismatched top and emits an ERROR naming it.
    private GdToken handleCloser(int closerChar, int expectedOpener, GdTokenType tokenType) {
        scanSteps++;
        if (emitRawClosers) {
            return makeToken(tokenType);
        }
        if (parenStack.isEmpty()) {
            return makeError("Closing \"" + codePointToString(closerChar) + "\" doesn't have an opening counterpart.");
        }
        var top = parenStack.getLast();
        if (top == expectedOpener) {
            popTopParen();
            return makeToken(tokenType);
        }
        var expectedStack = openerIndexStack(expectedOpener);
        if (expectedStack != null && !expectedStack.isEmpty()) {
            var matchIndex = expectedStack.getLast();
            var skipped = new StringBuilder();
            for (var i = matchIndex + 1; i < parenStack.size(); i++) {
                scanSteps++;
                if (!skipped.isEmpty()) {
                    skipped.append(", ");
                }
                skipped.append('"').appendCodePoint(parenStack.get(i)).append('"');
            }
            while (parenStack.size() > matchIndex) {
                popTopParen();
            }
            return makeError("Closing \"" + codePointToString(closerChar) + "\" skips unclosed " + skipped + ".");
        }
        var displaced = popTopParen();
        return makeError("Closing \"" + codePointToString(closerChar) + "\" doesn't match the opening \""
                + codePointToString(displaced) + "\".");
    }

    // --- Cursor primitives -------------------------------------------------------------

    /// Port of `_is_at_end()`.
    private boolean isAtEnd() {
        return position >= chars.length;
    }

    /// Port of `_peek(offset)`: code point at `position + offset`, or 0 out of bounds.
    /// Negative offsets look backwards; `peek(-1)` is the most recently consumed char.
    private int peek(int offset) {
        scanSteps++;
        var index = position + offset;
        return index >= 0 && index < chars.length ? chars[index] : 0;
    }

    /// Port of `_peek()` (default offset 0).
    private int peek() {
        return peek(0);
    }

    /// Consumes one code point and advances physical `position` and `column` without
    /// mutating line state or synthesizing EOF layout.
    private int advance() {
        if (isAtEnd()) {
            return 0;
        }
        position++;
        column++;
        return peek(-1);
    }

    private @Nullable List<Integer> openerIndexStack(int opener) {
        return switch (opener) {
            case '(' -> openParenIndices;
            case '[' -> openBracketIndices;
            case '{' -> openBraceIndices;
            default -> null;
        };
    }

    private void pushParen(int paren) {
        if (emitRawClosers) {
            return;
        }
        scanSteps++;
        var index = parenStack.size();
        parenStack.add(paren);
        var stack = openerIndexStack(paren);
        if (stack != null) {
            stack.add(index);
        }
    }

    private int popTopParen() {
        scanSteps++;
        var top = parenStack.removeLast();
        var stack = openerIndexStack(top);
        if (stack != null) {
            stack.removeLast();
        }
        return top;
    }

    /// Scan-dispatch iterations, code-point lookups, and standalone delimiter operations.
    long scanSteps() {
        return scanSteps;
    }

    private boolean hasError() {
        return !errorStack.isEmpty();
    }

    /// Port of `pop_error()`: the error stack is LIFO, like Godot's `List` back-pop.
    private GdToken popError() {
        var queued = errorStack.removeLast();
        lastErrorFollowedContinuation = queued.followedContinuation();
        lastErrorLogicalStartLine = queued.logicalStartLine();
        return queued.token();
    }

    private void pushError(GdToken error) {
        errorStack.add(new QueuedError(error, lineContinuation, logicalLineStartLine));
    }

    // --- Character classification (port of core/string/char_utils.h) ---------------------

    static boolean isDigit(int c) {
        return c >= '0' && c <= '9';
    }

    private static boolean isHexDigit(int c) {
        return isDigit(c) || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    private static boolean isBinaryDigit(int c) {
        return c == '0' || c == '1';
    }

    private static boolean isUnderscore(int c) {
        return c == '_';
    }

    private static boolean digitCheck(int base, int c) {
        return switch (base) {
            case 16 -> isHexDigit(c);
            case 2 -> isBinaryDigit(c);
            default -> isDigit(c);
        };
    }

    /// Skips a number with the same token boundaries as `number()`, without reporting errors.
    static int numberLiteralEnd(int[] chars, int start) {
        var len = chars.length;
        var pos = start + 1; // The tokenizer has already consumed the first digit or period.
        var leadingPeriod = chars[start] == '.';
        if (chars[start] == '0' && pos < len) {
            var base = switch (chars[pos]) {
                case 'x', 'X' -> 16;
                case 'b', 'B' -> 2;
                default -> 10;
            };
            if (base != 10) {
                return skipNumberDigits(chars, pos + 1, base);
            }
        }
        pos = skipNumberDigits(chars, pos, 10);
        if (!leadingPeriod && pos < len && chars[pos] == '.' && (pos + 1 == len || chars[pos + 1] != '.')) {
            pos = skipNumberDigits(chars, pos + 1, 10);
        }
        if (pos < len && (chars[pos] == 'e' || chars[pos] == 'E')) {
            pos++;
            if (pos < len && (chars[pos] == '+' || chars[pos] == '-')) {
                pos++;
            }
            pos = skipNumberDigits(chars, pos, 10);
        }
        return pos;
    }

    private static int skipNumberDigits(int[] chars, int pos, int base) {
        while (pos < chars.length && (digitCheck(base, chars[pos]) || isUnderscore(chars[pos]))) {
            pos++;
        }
        return pos;
    }

    /// Returns the end byte offset of an error-only `0x`/`0X`/`0b`/`0B` prefix (including
    /// any trailing `_` separators) when `number()` emits only a lexical error (`needDigits`
    /// remains true), or `-1` when `pos` does not start an error-only radix prefix (including
    /// valid literals such as `0x1`, `0x_1`, `0b0`, `0b_1`, `0`, or `0.5`).
    static int errorOnlyRadixLiteralEnd(byte @NotNull [] bytes, int pos) {
        if (pos + 1 >= bytes.length || bytes[pos] != '0') {
            return -1;
        }
        var prefix = bytes[pos + 1] & 0xFF;
        int base;
        if (prefix == 'x' || prefix == 'X') {
            base = 16;
        } else if (prefix == 'b' || prefix == 'B') {
            base = 2;
        } else {
            return -1;
        }
        var scan = pos + 2;
        while (scan < bytes.length) {
            var c = bytes[scan] & 0xFF;
            if (digitCheck(base, c)) {
                return -1;
            }
            if (!isUnderscore(c)) {
                break;
            }
            scan++;
        }
        return scan;
    }

    /// Approximation of Godot's `is_unicode_identifier_start` (XID_Start table that
    /// explicitly includes `_`): JDK `Character.isUnicodeIdentifierStart` (ID_Start) plus
    /// `_`. The ID_Start/XID_Start difference only affects a handful of exotic code points.
    /// The out-of-bounds sentinel 0 is excluded explicitly: unlike Godot's table, the JDK
    /// treats NUL and other identifier-ignorable controls as identifier characters, which
    /// would otherwise loop forever at EOF.
    static boolean isUnicodeIdentifierStart(int c) {
        return c != 0 && (c == '_' || Character.isUnicodeIdentifierStart(c));
    }

    /// Approximation of Godot's `is_unicode_identifier_continue` (XID_Continue table that
    /// explicitly includes `_`): JDK `Character.isUnicodeIdentifierPart` plus `_`.
    /// The out-of-bounds sentinel 0 is excluded for the same reason as above.
    static boolean isUnicodeIdentifierContinue(int c) {
        return c != 0 && (c == '_' || Character.isUnicodeIdentifierPart(c));
    }

    /// Port of Godot's `is_whitespace` ranges. Only used to phrase the "invalid white
    /// space" error, since regular whitespace is consumed elsewhere.
    private static boolean isWhitespaceChar(int c) {
        return c == ' ' || c == 0x00a0 || c == 0x1680 || (c >= 0x2000 && c <= 0x200b)
                || c == 0x202f || c == 0x205f || c == 0x3000 || c == 0x2028 || c == 0x2029
                || (c >= 0x0009 && c <= 0x000d) || c == 0x0085;
    }

    private static String indentCharName(int c) {
        if (c != ' ' && c != '\t') {
            return codePointToString(c);
        }
        return c == ' ' ? "space" : "tab";
    }

    private static String codePointToString(int codePoint) {
        return new String(Character.toChars(codePoint));
    }

    /// Godot's `%04X` formatting used in invalid-character error messages.
    private static String formatCodePoint(int codePoint) {
        return "U+%04X".formatted(codePoint);
    }
}
