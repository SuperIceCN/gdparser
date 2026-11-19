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

    // Current multi-char token window (Godot's `_start`/`start_line`/`start_column`).
    private int tokenStart;
    private int startLine = 1;
    private int startColumn = 1;

    private final boolean emitRawClosers;
    private final @Nullable Boolean initialMultilineMode;

    private boolean lineContinuation;
    private boolean lineHasContent;
    private boolean eofSettled;
    private @Nullable Boolean explicitMultilineMode;
    private boolean pendingNewline;
    private GdToken lastNewline;
    private int pendingIndents;
    private final List<Integer> indentStack = new ArrayList<>();
    private final List<List<Integer>> indentStackStack = new ArrayList<>();
    private final List<Integer> parenStack = new ArrayList<>();
    private final List<Integer> openParenIndices = new ArrayList<>();
    private final List<Integer> openBracketIndices = new ArrayList<>();
    private final List<Integer> openBraceIndices = new ArrayList<>();
    private int indentChar;
    private final List<GdToken> errorStack = new ArrayList<>();
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
        line = 1;
        column = 1;
        tokenStart = 0;
        startLine = 1;
        startColumn = 1;
        lineContinuation = false;
        lineHasContent = false;
        eofSettled = false;
        explicitMultilineMode = initialMultilineMode;
        pendingNewline = false;
        lastNewline = null;
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

    /// Port of `scan()`: returns the next token, draining queued errors first (LIFO),
    /// then a pending newline, then pending indent/dedent tokens, then dispatches on the
    /// next character. At end of input returns an `EOF` token.
    ///
    /// Godot recurses `scan()` for line continuations; the JVM does not eliminate tail
    /// calls, so this port loops instead (identical state flow, bounded stack usage).
    public @NotNull GdToken scan() {
        while (true) {
            if (hasError()) {
                return popError();
            }

            skipWhitespace();

            if (pendingNewline) {
                pendingNewline = false;
                if (!effectiveMultilineMode()) {
                    // Don't return newline tokens in multiline mode.
                    return lastNewline;
                }
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
                    return makeToken(GdTokenType.INDENT);
                }
                pendingIndents++;
                if (isAtEnd()) {
                    return new GdToken(GdTokenType.DEDENT, "", GdLiteralKind.NONE, null,
                            byteOffsets[position], byteOffsets[position], line, column, line, column);
                }
                tokenStart -= startColumn - 1;
                startColumn = 1;
                return makeToken(GdTokenType.DEDENT);
            }

            if (isAtEnd()) {
                if (!eofSettled) {
                    settleEof();
                    if (pendingNewline || pendingIndents != 0) {
                        continue;
                    }
                }
                return new GdToken(GdTokenType.EOF, "", GdLiteralKind.NONE, null,
                        byteOffsets[position], byteOffsets[position], line, column, line, column);
            }

            var c = advance();

            if (c == '\\') {
                // Line continuation with backslash.
                if (peek() == '\r') {
                    if (peek(1) != '\n') {
                        return emitContent(makeError("Unexpected carriage return character."));
                    }
                    advance();
                }
                if (peek() != '\n') {
                    return emitContent(makeError("Expected new line after \"\\\"."));
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

            lineContinuation = false;

            if (isDigit(c)) {
                return emitContent(number());
            } else if (c == 'r' && (peek() == '"' || peek() == '\'')) {
                // Raw string literals.
                return emitContent(string());
            } else if (isUnicodeIdentifierStart(c)) {
                return emitContent(potentialIdentifier());
            }

            return emitContent(dispatch(c));
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
            if (!effectiveMultilineMode()) {
                var eofByte = byteOffsets[position];
                lastNewline = new GdToken(GdTokenType.NEWLINE, "", GdLiteralKind.NONE, null,
                        eofByte, eofByte, line, column, line, column);
                pendingNewline = true;
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
            while (!isAtEnd()) {
                var space = peek();
                if (space == '\t') {
                    indentCount += TAB_SIZE;
                } else if (space == ' ') {
                    indentCount += 1;
                } else if (space == '\r' && peek(1) != '\n') {
                    advance();
                    pushError(makeCursorError("Stray carriage return character in source code."));
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
                return;
            }

            if (peek() == '\r') {
                advance();
            }
            if (peek() == '\n') {
                // Empty line, keep going.
                advance();
                newline(false);
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
                newline(false);
                continue;
            }

            if (mixed && !lineContinuation && !effectiveMultilineMode()) {
                pushError(makeIndentError("Mixed use of tabs and spaces for indentation."));
            }

            if (lineContinuation || effectiveMultilineMode()) {
                // Whitespace at the beginning of the line was consumed; a continuation or
                // multiline mode must not produce indentation changes.
                return;
            }

            if (indentCount == 0) {
                // A line beginning with a stray CR and no spaces/tabs dedents to column 1
                // without recording a file-wide indentation character.
                pendingIndents -= indentStack.size();
                indentStack.clear();
                return;
            }

            // Check indentation character consistency across the file.
            if (indentChar == 0) {
                // First time indenting, choose character now.
                indentChar = lineIndentChar;
            } else if (lineIndentChar != indentChar) {
                pushError(makeIndentError(
                        "Used " + indentCharName(lineIndentChar) + " character for indentation instead of "
                                + indentCharName(indentChar) + " as used before in the file."));
            }

            // Check if indent or dedent.
            var previousIndent = indentStack.isEmpty() ? 0 : indentStack.getLast();
            if (indentCount == previousIndent) {
                // No change in indentation.
                return;
            }
            if (indentCount > previousIndent) {
                // Indentation increased.
                indentStack.add(indentCount);
                pendingIndents++;
            } else {
                // Indentation decreased (dedent).
                if (indentStack.isEmpty()) {
                    pushError(makeIndentError("Tokenizer bug: trying to dedent without previous indent."));
                    return;
                }
                while (!indentStack.isEmpty() && indentStack.getLast() > indentCount) {
                    indentStack.removeLast();
                    pendingIndents--;
                }
                if (indentStack.isEmpty() || indentStack.getLast() != indentCount) {
                    // Mismatched indentation alignment; stay lenient and keep this level.
                    pushError(makeIndentError("Unindent doesn't match the previous indentation level."));
                    indentStack.add(indentCount);
                }
            }
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
                    newline(true);
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
                    newline(true);
                    checkIndent();
                }
                default -> {
                    return;
                }
            }
        }
    }

    /// Consumes a physical newline and advances the physical line cursor. When `makeToken`
    /// is set and no newline is pending (and this is not a line continuation), schedules a
    /// `NEWLINE` token covering the consumed `\n` and resets `lineHasContent`.
    private void newline(boolean makeToken) {
        var prevLine = line;
        var prevColumn = column;
        line++;
        column = 1;
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
        var index = parenStack.size();
        parenStack.add(paren);
        var stack = openerIndexStack(paren);
        if (stack != null) {
            stack.add(index);
        }
    }

    private int popTopParen() {
        var top = parenStack.removeLast();
        var stack = openerIndexStack(top);
        if (stack != null) {
            stack.removeLast();
        }
        return top;
    }

    private boolean hasError() {
        return !errorStack.isEmpty();
    }

    /// Port of `pop_error()`: the error stack is LIFO, like Godot's `List` back-pop.
    private GdToken popError() {
        return errorStack.removeLast();
    }

    private void pushError(GdToken error) {
        errorStack.add(error);
    }

    // --- Character classification (port of core/string/char_utils.h) ---------------------

    private static boolean isDigit(int c) {
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

    /// Approximation of Godot's `is_unicode_identifier_start` (XID_Start table that
    /// explicitly includes `_`): JDK `Character.isUnicodeIdentifierStart` (ID_Start) plus
    /// `_`. The ID_Start/XID_Start difference only affects a handful of exotic code points.
    /// The out-of-bounds sentinel 0 is excluded explicitly: unlike Godot's table, the JDK
    /// treats NUL and other identifier-ignorable controls as identifier characters, which
    /// would otherwise loop forever at EOF.
    private static boolean isUnicodeIdentifierStart(int c) {
        return c != 0 && (c == '_' || Character.isUnicodeIdentifierStart(c));
    }

    /// Approximation of Godot's `is_unicode_identifier_continue` (XID_Continue table that
    /// explicitly includes `_`): JDK `Character.isUnicodeIdentifierPart` plus `_`.
    /// The out-of-bounds sentinel 0 is excluded for the same reason as above.
    private static boolean isUnicodeIdentifierContinue(int c) {
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
