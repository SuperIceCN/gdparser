package dev.superice.gdparser.frontend.gdscript;

import dev.superice.gdparser.frontend.gdscript.CompletionContext.Kind;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/// A recovery-independent token view. Layout remains a boundary unless the enclosing
/// expression enables multiline mode; function bodies temporarily disable that mode.
final class CompletionTokenStream {

    enum Role {
        OTHER, GROUP, CALL, SUBSCRIPT, CONTAINER, DICTIONARY, ENUM, TYPE, SIGNATURE
    }

    record Entry(GdToken token, boolean typePosition, TypeOrigin typeOrigin, Role role,
                 boolean operandName, boolean nodePathName) {
    }

    /// The owning construct and trigger token are entry indices; the exit is a UTF-8 byte.
    record CursorGapSlot(int owner, int entry, Kind kind, int exitEnd) {
    }

    private enum Body {
        PREFIX, ANONYMOUS_OPEN, NAMED_OPEN, SIGNATURE, RETURN_TYPE, START, INLINE, INDENTED
    }

    private enum TypeState {
        NONE, START_AFTER_IS, START, EXTENDS_START, EXTENDS_PATH,
        BASE_NAME, DOTTED_DOT, DOTTED_NAME, COMPLETE
    }

    private enum TypeOrigin {
        NONE, EXPRESSION, DECLARATION
    }

    private record TypeContinuationAnchor(Frame typeFrame, int newlinePos, int ownerIndent, TypeState stateAtNewline,
                                          @Nullable Frame enclosingDelimiter, boolean enclosingCommaOwner) {
    }

    private record TypeFragmentFrame(int openerByte, boolean commaAvailable, boolean enclosingCommaOwner) {
    }

    private enum MatchPhase {
        VALUE, WAIT_BRANCH, HEADER, BODY
    }

    private static final class MatchFrame {
        final int indent;
        int branchIndent = -1;
        MatchPhase phase = MatchPhase.VALUE;

        MatchFrame(int indent) {
            this.indent = indent;
        }
    }

    private static final class Frame {
        final int opener;
        final Role role;
        final boolean multiline;
        final int indent;
        final @Nullable Frame enclosingDelimiter;
        Body body;
        boolean enclosingMultiline;
        boolean lineBrokenBeforeParams;
        boolean validSignature = true;
        boolean signalSignature;
        int dictionaryKeyStart;
        int signatureCloser = -1;
        int suiteBaseline;
        boolean typeCommaAvailable;
        boolean enclosingCommaOwner;
        TypeOrigin typeOrigin = TypeOrigin.NONE;
        boolean bodyNewline;
        int bodyIndent;
        final List<MatchFrame> matches = new ArrayList<>();

        Frame(int opener, Role role, boolean multiline, int indent, @Nullable Frame enclosingDelimiter) {
            this.opener = opener;
            this.role = role;
            this.multiline = multiline;
            this.indent = indent;
            this.enclosingDelimiter = enclosingDelimiter;
            body = role == Role.OTHER ? Body.PREFIX : null;
            dictionaryKeyStart = role == Role.DICTIONARY ? opener + 1 : -1;
        }

        boolean isFunction() {
            return body != null;
        }
    }

    private final List<Entry> entries = new ArrayList<>();
    private final List<Integer> partners = new ArrayList<>();
    private final List<Integer> effectiveRecoveryEnds = new ArrayList<>();
    private final BitSet exclusiveRecoveryEnds = new BitSet();
    private final List<Integer> hardStops = new ArrayList<>();
    private final List<@Nullable CursorGapSlot> cursorGaps = new ArrayList<>();
    private final List<Frame> frames = new ArrayList<>();
    private final List<Integer> openParenFrames = new ArrayList<>();
    private final List<Integer> openBracketFrames = new ArrayList<>();
    private final List<Integer> openBraceFrames = new ArrayList<>();
    private final List<GdComment> comments;
    private final List<GdToken> incompleteStrings = new ArrayList<>();
    private final SourceTextIndex index;
    private final BracketBalanceOracle bracketOracle;
    private final String source;
    private final int[] lineIndents;
    private final int[] lineStartChars;
    private final int[] leadingWhitespaceLengths;
    private final int[] firstContentPositions;
    private final boolean[] wholeLineComments;
    private final boolean[] lexicalErrorLines;
    private final TypeContinuationAnchor[] typeContinuations;
    private TypeState typeState = TypeState.NONE;
    private TypeOrigin typeOrigin = TypeOrigin.NONE;
    private int effectiveIndent;
    private boolean logicalLineStart = true;
    private int lastLogicalContentLine;
    private @Nullable GdToken suppressedNewline;
    private int inferredColonOwner = -1;

    CompletionTokenStream(String source) {
        this.source = source;
        var tokenizer = GdTokenizer.managed(source);
        index = tokenizer.sourceTextIndex();
        bracketOracle = new BracketBalanceOracle(tokenizer.codePoints(), tokenizer.byteOffsets());
        var lines = index.lineCount();
        lineIndents = new int[lines];
        lineStartChars = new int[lines];
        leadingWhitespaceLengths = new int[lines];
        firstContentPositions = new int[lines];
        Arrays.fill(firstContentPositions, -1);
        wholeLineComments = new boolean[lines];
        lexicalErrorLines = new boolean[lines];
        typeContinuations = new TypeContinuationAnchor[lines];
        var row = 0;
        var leading = true;
        for (var i = 0; i < source.length(); i++) {
            var character = source.charAt(i);
            if (character == '\n') {
                lineStartChars[++row] = i + 1;
                leading = true;
            } else if (leading && (character == ' ' || character == '\t'
                    || character == '\r' && (i + 1 == source.length() || source.charAt(i + 1) != '\n'))) {
                lineIndents[row] += indentWidth(character);
                leadingWhitespaceLengths[row]++;
            } else {
                leading = false;
            }
        }
        GdToken token;
        do {
            token = tokenizer.scan();
            if (token.type() != GdTokenType.ERROR) {
                accept(token, tokenizer.tokenLogicalStartLine());
            } else {
                endPreviousGapAt(token.startByte());
                lexicalErrorLines[token.startLine() - 1] = true;
                if (GdTokenizer.UNTERMINATED_STRING_ERROR.equals(token.errorMessage())) {
                    incompleteStrings.add(token);
                }
            }
        } while (token.type() != GdTokenType.EOF);
        comments = tokenizer.getComments();
        for (var comment : comments) {
            if (comment.wholeLine()) {
                wholeLineComments[comment.line() - 1] = true;
            }
        }
    }

    List<Entry> entries() {
        return entries;
    }

    List<GdComment> comments() {
        return comments;
    }

    List<GdToken> incompleteStrings() {
        return incompleteStrings;
    }

    SourceTextIndex index() {
        return index;
    }

    int partner(int position) {
        return partners.get(position);
    }

    /// The end of the opener's effective scope, or `-1` while it remains open to EOF.
    int effectiveEnd(int position) {
        var partner = partners.get(position);
        return partner >= 0 ? entries.get(partner).token().startByte() : effectiveRecoveryEnds.get(position);
    }

    boolean isEffectiveEndInclusive(int position) {
        return !exclusiveRecoveryEnds.get(position);
    }

    boolean isWithinEffectiveEnd(int position, int offset) {
        var end = effectiveEnd(position);
        return end < 0 || offset < end || offset == end && isEffectiveEndInclusive(position);
    }

    /// Tests an exclusive child boundary without breaking the enclosing delimiter's multiline layout.
    boolean hasHardStopBetween(int startByte, int endByte) {
        var position = lastHardStopAtOrBefore(endByte);
        return position >= 0 && hardStopByte(position) >= startByte;
    }

    int lastHardStopAtOrBefore(int byteOffset) {
        var position = Collections.binarySearch(hardStops, byteOffset);
        return position >= 0 ? position : -position - 2;
    }

    int hardStopByte(int position) {
        return hardStops.get(position);
    }

    int oracleScanCount() {
        return bracketOracle.scanCount();
    }

    @Nullable CursorGapSlot cursorGap(int position, int offset) {
        var gap = position < 0 ? null : cursorGaps.get(position);
        if (gap == null || offset > gap.exitEnd()
                || hasHardStopBetween(entries.get(position).token().endByte(), offset)
                || isOpen(entries.get(gap.owner()).token().type())
                && !isWithinEffectiveEnd(gap.owner(), offset)) {
            return null;
        }
        return gap;
    }

    private void accept(GdToken token, int logicalStartLine) {
        var type = token.type();
        if (isLayout(type)) {
            // EOF's synthetic layout must not separate a prefix from the cursor at EOF.
            if (token.startByte() != token.endByte() && !multiline()) {
                if (type == GdTokenType.NEWLINE) {
                    discardIncompleteTypes(token);
                }
                var function = topFunction();
                if (type == GdTokenType.NEWLINE && function != null) {
                    if (function.body == Body.PREFIX || function.body == Body.NAMED_OPEN) {
                        function.lineBrokenBeforeParams = true;
                    }
                    if (function.body == Body.NAMED_OPEN
                            || function.body == Body.PREFIX && function.enclosingMultiline) {
                        // Preserve signature ownership even when it no longer offers parameter names.
                        return;
                    }
                }
                add(token, false, Role.OTHER);
                typeState = TypeState.NONE;
                if (type == GdTokenType.NEWLINE) {
                    logicalLineStart = true;
                    if (function != null) {
                        switch (function.body) {
                            case PREFIX -> function.body = Body.ANONYMOUS_OPEN;
                            case START -> function.bodyNewline = true;
                            // Retain this boundary before restoring the enclosing multiline mode.
                            case INLINE, RETURN_TYPE -> removeFramesFrom(frames.size() - 1);
                            case INDENTED -> {
                                if (!function.matches.isEmpty()
                                        && function.matches.getLast().phase == MatchPhase.HEADER) {
                                    function.matches.getLast().phase = MatchPhase.BODY;
                                }
                            }
                            default -> {
                            }
                        }
                    }
                }
            } else if (type == GdTokenType.NEWLINE && token.startByte() != token.endByte()) {
                suppressedNewline = token;
            }
            return;
        }
        var atEof = type == GdTokenType.EOF;
        var continuation = !atEof && token.startLine() > logicalStartLine;
        // A line containing only a backslash can start a logical line before its first token.
        var atHardLineHead = !atEof && logicalStartLine > lastLogicalContentLine;
        var lineIndent = lineIndents[atEof ? lineIndents.length - 1 : logicalStartLine - 1];
        settleFramesBefore(token, atHardLineHead ? logicalStartLine : 0, lineIndent);
        suppressedNewline = null;
        if (logicalLineStart && !multiline() && atHardLineHead) {
            effectiveIndent = lineIndent;
        }
        if (!atEof) {
            lastLogicalContentLine = logicalStartLine;
        }
        var atLineStart = logicalLineStart && !multiline();
        logicalLineStart = false;
        var position = entries.size();
        var previousPosition = isAdjacentToCurrent(position - 1) ? position - 1 : -1;
        var previous = previousPosition >= 0 ? entries.get(previousPosition).token() : null;
        var beforePrevious = previousToken(1);
        var function = topFunction();
        var enclosing = frames.isEmpty() ? null : frames.getLast();
        var functionSignature = type == GdTokenType.PARENTHESIS_OPEN
                && function != null && isPendingSignature(function);
        var signalSignature = type == GdTokenType.PARENTHESIS_OPEN && !functionSignature
                && previous != null && isIdentifier(previous)
                && beforePrevious != null && beforePrevious.type() == GdTokenType.SIGNAL
                && !isPathName(previousToken(2));
        var signature = functionSignature || signalSignature;
        var parameterStart = enclosing != null && enclosing.role == Role.SIGNATURE
                && isParameterStart(enclosing, previousPosition);
        var typeColonOwner = type == GdTokenType.COLON ? typeColonOwner(enclosing) : -1;

        typeState = nextTypeState(token);
        if (typeColonOwner >= 0) {
            typeState = TypeState.START;
            typeOrigin = TypeOrigin.DECLARATION;
        } else if ((type == GdTokenType.AS || type == GdTokenType.IS
                || type == GdTokenType.EXTENDS) && !isPathName(previous)) {
            typeState = switch (type) {
                case IS -> TypeState.START_AFTER_IS;
                case EXTENDS -> TypeState.EXTENDS_START;
                default -> TypeState.START;
            };
            typeOrigin = type == GdTokenType.EXTENDS ? TypeOrigin.DECLARATION : TypeOrigin.EXPRESSION;
        } else if (isReturnTypeArrow(type, function)) {
            typeState = TypeState.START;
            typeOrigin = TypeOrigin.DECLARATION;
        }

        var inType = typeState != TypeState.NONE && typeState != TypeState.EXTENDS_PATH;
        var role = switch (type) {
            case PARENTHESIS_OPEN -> signature ? Role.SIGNATURE
                    : isCalleeEnd(previousPosition) ? Role.CALL : Role.GROUP;
            case BRACKET_OPEN -> inType ? Role.TYPE
                    : isPrimaryEnd(previousPosition) ? Role.SUBSCRIPT : Role.CONTAINER;
            case BRACE_OPEN -> isEnumBody(previous, beforePrevious) ? Role.ENUM : Role.DICTIONARY;
            default -> Role.OTHER;
        };
        var pathName = (isNodeName(token) || token.literalKind() == GdLiteralKind.STRING)
                && isNodePathName(previousPosition);
        var operandName = isNodeName(token) && (pathName
                || previous != null && previous.type() == GdTokenType.PERIOD && isExpressionOperand(position - 2)
                || isIdentifier(token) && !isBindingName(previous, parameterStart)
                && !(type == GdTokenType.MATCH && isStatementEntry(atLineStart, previousPosition, enclosing))
                && !(type == GdTokenType.WHEN && !atLineStart && followsMatchPattern(previousPosition)));
        add(token, inType, typeState == TypeState.NONE ? TypeOrigin.NONE : typeOrigin, role, operandName, pathName);
        updateDictionaryEntry(enclosing, position, previousPosition, type);
        if (type == GdTokenType.COMMA && inType && enclosing != null && enclosing.role == Role.TYPE) {
            enclosing.typeCommaAvailable = false;
        }
        if (type == GdTokenType.EQUAL && previous != null && previous.type() == GdTokenType.COLON
                && inferredColonOwner >= 0) {
            var colon = entries.get(previousPosition);
            entries.set(previousPosition, new Entry(previous, false, TypeOrigin.NONE, colon.role(),
                    colon.operandName(), colon.nodePathName()));
            cursorGaps.set(previousPosition, null);
            openGap(inferredColonOwner, position, Kind.IDENTIFIER);
        }
        inferredColonOwner = typeColonOwner >= 0 && isInferredColonOwner(typeColonOwner, enclosing)
                ? typeColonOwner : -1;
        if (enclosing != null && enclosing.role == Role.SIGNATURE && enclosing.validSignature
                && (type == GdTokenType.COMMA || type == GdTokenType.PERIOD_PERIOD_PERIOD
                && parameterStart && !enclosing.signalSignature)) {
            openGap(enclosing.opener, position, Kind.PARAMETER_NAME);
        } else if (type == GdTokenType.NOT && !inType && !isPathName(previous)
                && isExpressionOperand(previousPosition)) {
            openGap(previousPosition, position, Kind.EXPECTED_KEYWORD);
        }
        if (function != null && isPendingSignature(function)) {
            if (functionSignature) {
                function.body = Body.SIGNATURE;
                function.validSignature = !function.lineBrokenBeforeParams;
            } else if (isIdentifier(token)) {
                function.body = Body.NAMED_OPEN;
            }
        }

        if (type == GdTokenType.FUNC && !isPathName(previous)) {
            var frame = new Frame(position, Role.OTHER, false, effectiveIndent, enclosingDelimiter());
            frame.enclosingMultiline = multiline();
            frames.add(frame);
            typeState = TypeState.NONE;
        } else if (isOpen(type)) {
            var validSignature = !signature || (functionSignature ? Objects.requireNonNull(function).validSignature
                    : previous != null && (previous.endLine() == token.startLine() || continuation));
            var typeCommaAvailable = role == Role.TYPE && previous != null && "Dictionary".equals(previous.source());
            pushDelimiterFrame(position, type, role, validSignature, signalSignature, typeCommaAvailable);
            if (signature && validSignature) {
                openGap(position, position, Kind.PARAMETER_NAME);
            }
            if (role != Role.TYPE) {
                typeState = TypeState.NONE;
            }
        } else if (isClose(type)) {
            close(position);
        } else if (type == GdTokenType.COLON) {
            function = topFunction();
            if (function != null && function.body == Body.RETURN_TYPE) {
                if (function.validSignature) {
                    openGap(function.opener, position, Kind.SUITE_START);
                }
                function.body = Body.START;
                function.bodyNewline = false;
                typeState = TypeState.NONE;
            }
        }
    }

    private boolean isEnumBody(@Nullable GdToken previous, @Nullable GdToken beforePrevious) {
        return previous != null && (previous.type() == GdTokenType.ENUM && !isPathName(beforePrevious)
                || isIdentifier(previous) && beforePrevious != null && beforePrevious.type() == GdTokenType.ENUM
                && !isPathName(previousToken(2)));
    }

    private boolean isStatementEntry(boolean atLineStart, int previous, @Nullable Frame enclosing) {
        if (atLineStart || previous < 0) {
            return atLineStart;
        }
        var entry = entries.get(previous);
        return switch (entry.token().type()) {
            case NEWLINE, INDENT, DEDENT, SEMICOLON -> true;
            case COLON -> !entry.typePosition() && (enclosing == null || enclosing.isFunction());
            default -> false;
        };
    }

    /// Only the first value separator at this dictionary's own depth can open a value slot.
    private void updateDictionaryEntry(@Nullable Frame dictionary, int position, int previous, GdTokenType type) {
        if (dictionary == null || dictionary.role != Role.DICTIONARY) {
            return;
        }
        if (type == GdTokenType.COMMA) {
            dictionary.dictionaryKeyStart = position + 1;
        } else if (type == GdTokenType.COLON || type == GdTokenType.EQUAL) {
            var keyStart = dictionary.dictionaryKeyStart;
            if (keyStart >= 0 && (type == GdTokenType.COLON
                    ? isPrimaryEnd(previous) && isExpressionOperand(previous)
                    : isLuaDictionaryKey(keyStart, previous))) {
                openGap(dictionary.opener, position, Kind.IDENTIFIER);
            }
            dictionary.dictionaryKeyStart = -1;
        }
    }

    /// A Lua-style key is one identifier or string, possibly wrapped in grouping parentheses.
    private boolean isLuaDictionaryKey(int first, int last) {
        if (first > last || !isAdjacentToCurrent(first)) {
            return false;
        }
        while (first < last && entries.get(first).role() == Role.GROUP && partner(first) == last) {
            first++;
            last--;
        }
        if (first != last) {
            return false;
        }
        var token = entries.get(first).token();
        return isIdentifier(token) || token.literalKind() == GdLiteralKind.STRING;
    }

    /// Mirrors type atoms rather than accepting an unbounded run of identifier-like tokens.
    private TypeState nextTypeState(GdToken token) {
        var type = token.type();
        return switch (typeState) {
            case START_AFTER_IS -> type == GdTokenType.NOT ? TypeState.START : typeAtomState(token);
            case START -> typeAtomState(token);
            case EXTENDS_START -> type == GdTokenType.LITERAL && token.literalKind() == GdLiteralKind.STRING
                    ? TypeState.EXTENDS_PATH : isIdentifier(token) ? TypeState.DOTTED_NAME : TypeState.NONE;
            case EXTENDS_PATH -> type == GdTokenType.PERIOD ? TypeState.DOTTED_DOT : TypeState.NONE;
            case BASE_NAME -> type == GdTokenType.BRACKET_OPEN ? TypeState.START
                    : type == GdTokenType.PERIOD ? TypeState.DOTTED_DOT : typeSeparatorState(token);
            case DOTTED_DOT -> isIdentifier(token) ? TypeState.DOTTED_NAME : TypeState.NONE;
            case DOTTED_NAME -> type == GdTokenType.PERIOD ? TypeState.DOTTED_DOT : typeSeparatorState(token);
            case COMPLETE -> typeSeparatorState(token);
            case NONE -> TypeState.NONE;
        };
    }

    private static TypeState typeAtomState(GdToken token) {
        return token.type() == GdTokenType.VOID ? TypeState.COMPLETE
                : isIdentifier(token) ? TypeState.BASE_NAME : TypeState.NONE;
    }

    private TypeState typeSeparatorState(GdToken token) {
        if (!frames.isEmpty() && frames.getLast().role == Role.TYPE) {
            var frame = frames.getLast();
            return switch (token.type()) {
                case COMMA -> acceptsTypeComma(entries.get(frame.opener).token().startByte(),
                        frame.typeCommaAvailable, frame.enclosingCommaOwner, token.startByte())
                        ? TypeState.START : TypeState.NONE;
                case BRACKET_CLOSE -> TypeState.COMPLETE;
                default -> TypeState.NONE;
            };
        }
        return TypeState.NONE;
    }

    /// Non-multiline type containers cannot outlive a failed type atom or a hard newline.
    private void discardIncompleteTypes(GdToken boundary) {
        Frame innermost = null;
        while (!frames.isEmpty() && frames.getLast().role == Role.TYPE && !frames.getLast().multiline) {
            var frame = frames.getLast();
            if (innermost == null) {
                innermost = frame;
            }
            effectiveRecoveryEnds.set(frame.opener, boundary.startByte());
            removeFramesFrom(frames.size() - 1);
        }
        if (innermost != null && boundary.type() == GdTokenType.NEWLINE
                && (typeState == TypeState.START || typeState == TypeState.DOTTED_DOT)) {
            var ancestor = enclosingDelimiter();
            typeContinuations[boundary.endLine() - 1] = new TypeContinuationAnchor(
                    innermost, entries.size(), effectiveIndent, typeState, ancestor, hasCommaOwner(ancestor));
        }
    }

    /// An unfinished collection type can supply a completion slot on one deeper line.
    /// The line must stand on its own as a type fragment; do not resurrect its parser frames.
    boolean isTrustedTypeContinuation(int offset, int name) {
        var cursor = index.pointAt(offset);
        var row = cursor.row();
        var anchor = typeAnchorFor(row);
        if (anchor == null || lexicalErrorLines[row]
                || indentationAt(row, cursor.column()) <= anchor.ownerIndent()
                || name >= 0 && (entries.get(name).token().startLine() != row + 1
                || !isIdentifier(entries.get(name).token()))) {
            return false;
        }
        var start = firstContentPositions[row];
        if (start < 0) {
            return name < 0;
        }
        var state = anchor.stateAtNewline();
        var stateAtCursor = state;
        var nameInFragment = false;
        var typeFrames = typeFragmentFrames(anchor);
        var ancestor = anchor.enclosingDelimiter();
        var trailingCloser = false;
        for (var i = start; i < entries.size(); i++) {
            var token = entries.get(i).token();
            var type = token.type();
            if (type == GdTokenType.EOF || type == GdTokenType.NEWLINE || token.startLine() != row + 1) {
                break;
            }
            if (isLayout(type)) {
                continue;
            }
            var hasAtom = state == TypeState.BASE_NAME || state == TypeState.DOTTED_NAME
                    || state == TypeState.COMPLETE;
            var closesEmptyType = type == GdTokenType.BRACKET_CLOSE && state == TypeState.START
                    && !typeFrames.isEmpty() && offset <= token.startByte()
                    && exactTypeCloser(typeFrames.getLast().openerByte()) == token.startByte();
            if (isClose(type) && (trailingCloser || type != GdTokenType.BRACKET_CLOSE
                    || !(hasAtom || closesEmptyType) || typeFrames.isEmpty())) {
                var matched = exactEnclosingCloser(ancestor, token.startByte());
                if (matched == null || offset > token.startByte()) {
                    return false;
                }
                ancestor = matched.enclosingDelimiter;
                trailingCloser = true;
                continue;
            }
            if (trailingCloser) {
                return false;
            }
            if (isIdentifier(token)) {
                state = switch (state) {
                    case START -> TypeState.BASE_NAME;
                    case DOTTED_DOT -> TypeState.DOTTED_NAME;
                    default -> TypeState.NONE;
                };
                nameInFragment |= i == name && !typeFrames.isEmpty();
            } else {
                switch (type) {
                    case PERIOD -> state = state == TypeState.BASE_NAME || state == TypeState.DOTTED_NAME
                            ? TypeState.DOTTED_DOT : TypeState.NONE;
                    case BRACKET_OPEN -> {
                        if (state != TypeState.BASE_NAME || typeFrames.isEmpty()) {
                            return false;
                        }
                        var parent = typeFrames.getLast();
                        typeFrames.add(new TypeFragmentFrame(token.startByte(),
                                "Dictionary".equals(entries.get(i - 1).token().source()),
                                parent.enclosingCommaOwner() || parent.commaAvailable()));
                        state = TypeState.START;
                    }
                    case COMMA -> {
                        if (!hasAtom || typeFrames.isEmpty()) {
                            return false;
                        }
                        var frame = typeFrames.getLast();
                        if (!acceptsTypeComma(frame.openerByte(), frame.commaAvailable(), frame.enclosingCommaOwner(),
                                token.startByte())) {
                            return false;
                        }
                        typeFrames.set(typeFrames.size() - 1,
                                new TypeFragmentFrame(frame.openerByte(), false, frame.enclosingCommaOwner()));
                        state = TypeState.START;
                    }
                    case BRACKET_CLOSE -> {
                        typeFrames.removeLast();
                        state = TypeState.COMPLETE;
                    }
                    default -> state = TypeState.NONE;
                }
            }
            if (state == TypeState.NONE) {
                return false;
            }
            if (token.endByte() <= offset) {
                stateAtCursor = state;
            }
        }
        return name >= 0 ? nameInFragment : stateAtCursor == TypeState.START || stateAtCursor == TypeState.DOTTED_DOT;
    }

    private List<TypeFragmentFrame> typeFragmentFrames(TypeContinuationAnchor anchor) {
        var inherited = new ArrayList<Frame>();
        for (var frame = anchor.typeFrame(); frame != anchor.enclosingDelimiter(); frame = frame.enclosingDelimiter) {
            frame = Objects.requireNonNull(frame);
            inherited.add(frame);
        }
        var enclosingCommaOwner = anchor.enclosingCommaOwner();
        var result = new ArrayList<TypeFragmentFrame>(inherited.size());
        for (var frame : inherited.reversed()) {
            result.add(new TypeFragmentFrame(entries.get(frame.opener).token().startByte(),
                    frame.typeCommaAvailable, enclosingCommaOwner));
            enclosingCommaOwner |= frame.typeCommaAvailable;
        }
        return result;
    }

    private static boolean hasCommaOwner(@Nullable Frame frame) {
        return frame != null && (frame.enclosingCommaOwner || frame.typeCommaAvailable || switch (frame.role) {
            case CALL, CONTAINER, DICTIONARY, ENUM, SIGNATURE -> true;
            default -> false;
        });
    }

    private boolean acceptsTypeComma(int opener, boolean commaAvailable, boolean enclosingCommaOwner, int comma) {
        return commaAvailable && (!enclosingCommaOwner || exactTypeCloser(opener) > comma);
    }

    private int exactTypeCloser(int opener) {
        var fact = bracketOracle.factFor(opener);
        return fact != null && fact.status() == BracketBalanceOracle.OpenerStatus.MATCHED
                ? fact.pairedCloserByte() : -1;
    }

    /// A type's `]` may have been dropped or used for recovery by the logical token stream.
    /// Consult exact lexical facts when validating enclosing closers on the speculative line.
    private @Nullable Frame exactEnclosingCloser(@Nullable Frame ancestor, int closerByte) {
        for (var frame = ancestor; frame != null; frame = frame.enclosingDelimiter) {
            var fact = bracketOracle.factFor(entries.get(frame.opener).token().startByte());
            if (fact != null && fact.status() == BracketBalanceOracle.OpenerStatus.MATCHED) {
                if (fact.pairedCloserByte() == closerByte) {
                    return frame;
                }
                if (fact.pairedCloserByte() > closerByte) {
                    break;
                }
            }
        }
        return null;
    }

    private @Nullable TypeContinuationAnchor typeAnchorFor(int row) {
        for (var candidate = row; candidate > 0; candidate--) {
            var anchor = typeContinuations[candidate];
            if (anchor != null && entries.get(anchor.newlinePos()).token().type() == GdTokenType.NEWLINE) {
                return anchor;
            }
            if (!wholeLineComments[candidate - 1] || lexicalErrorLines[candidate - 1]) {
                break;
            }
        }
        return null;
    }

    private int indentationAt(int row, int byteColumn) {
        var leading = leadingWhitespaceLengths[row];
        if (byteColumn >= leading) {
            return lineIndents[row];
        }
        var indent = 0;
        for (var i = 0; i < byteColumn; i++) {
            indent += indentWidth(source.charAt(lineStartChars[row] + i));
        }
        return indent;
    }

    private static int indentWidth(char character) {
        return switch (character) {
            case ' ' -> 1;
            case '\t' -> GdTokenizer.TAB_SIZE;
            default -> 0;
        };
    }

    /// Delimiters and function bodies can expose one another as they unwind. Each repeated pass
    /// removes at least one frame and replays any newly visible layout before examining the body.
    private void settleFramesBefore(GdToken token, int hardLineStart, int lineIndent) {
        var firstPass = true;
        int frameCount;
        do {
            frameCount = frames.size();
            if (hardLineStart > 0) {
                discardDelimitersBefore(token, hardLineStart, lineIndent);
            }
            replaySuppressedNewline();
            if (firstPass) {
                firstPass = false;
                if (token.type() == GdTokenType.COMMA) {
                    releaseTypesBeforeComma(token);
                }
                if (typeState != TypeState.NONE && nextTypeState(token) == TypeState.NONE) {
                    typeState = TypeState.NONE;
                    if (!isClose(token.type())) {
                        discardIncompleteTypes(token);
                        replaySuppressedNewline();
                    }
                }
            }
            var function = topFunction();
            if (function != null) {
                finishBodyBefore(function, token, lineIndent);
                if (topFunction() == function) {
                    // A surviving body owns this token. Do not update its match state twice.
                    return;
                }
                replaySuppressedNewline();
            }
        } while (frames.size() < frameCount);
    }

    private void replaySuppressedNewline() {
        var newline = suppressedNewline;
        if (newline != null && !multiline()) {
            suppressedNewline = null;
            accept(newline, 0);
        }
    }

    /// An unowned type comma can end malformed type frames and belong to the enclosing list.
    private void releaseTypesBeforeComma(GdToken comma) {
        while (!frames.isEmpty()) {
            var frame = frames.getLast();
            if (frame.role != Role.TYPE || !frame.enclosingCommaOwner || nextTypeState(comma) != TypeState.NONE) {
                break;
            }
            effectiveRecoveryEnds.set(frame.opener, comma.startByte());
            exclusiveRecoveryEnds.set(frame.opener);
            removeFramesFrom(frames.size() - 1);
            if (frames.isEmpty() || frames.getLast().role != Role.TYPE) {
                break;
            }
            // A parent generic can accept the comma after the child's damaged atom.
            typeState = TypeState.COMPLETE;
            typeOrigin = frames.getLast().typeOrigin;
        }
    }

    /// A live delimiter can leave at a structural statement or a suite dedent without claiming it.
    private void discardDelimitersBefore(GdToken token, int hardLineStart, int lineIndent) {
        while (!frames.isEmpty() && !frames.getLast().isFunction()) {
            var frame = frames.getLast();
            var opener = entries.get(frame.opener).token();
            if (opener.startLine() >= hardLineStart) {
                break;
            }
            var fact = bracketOracle.factFor(opener.startByte());
            // An indented body can keep a stray closer that the lexical scan used as a replacement.
            var canEscape = fact != null && (fact.status() == BracketBalanceOracle.OpenerStatus.MISSING
                    || fact.status() == BracketBalanceOracle.OpenerStatus.REPLACED
                    && fact.pairedCloserByte() < token.startByte() && !isClose(token.type()));
            if (!canEscape || !(lineIndent < frame.suiteBaseline
                    || bracketOracle.isStructuralStopToken(token.startByte()))) {
                break;
            }
            var stop = index.lineStartByte(hardLineStart - 1);
            if (hardStops.isEmpty() || hardStops.getLast() != stop) {
                hardStops.add(stop);
            }
            effectiveRecoveryEnds.set(frame.opener, stop);
            exclusiveRecoveryEnds.set(frame.opener);
            removeFramesFrom(frames.size() - 1);
            // Even a cast inside an expression delimiter cannot carry its type past this stop.
            typeState = TypeState.NONE;
            inferredColonOwner = -1;
            if (frame.role == Role.SIGNATURE && !frame.signalSignature
                    && signatureOwner() != null) {
                // The signature was abandoned, not closed. Its owner cannot accept a return type.
                removeFramesFrom(frames.size() - 1);
            }
        }
    }

    private int typeColonOwner(@Nullable Frame enclosing) {
        var name = previousToken(0);
        if (name == null || !isIdentifier(name)) {
            return -1;
        }
        var before = previousToken(1);
        if (before != null && switch (before.type()) {
            case VAR, CONST, FOR -> true;
            default -> false;
        } && !isPathName(previousToken(2))) {
            return entries.size() - 2;
        }
        return enclosing != null && enclosing.role == Role.SIGNATURE
                && isParameterStart(enclosing, entries.size() - 2) ? enclosing.opener : -1;
    }

    private boolean isInferredColonOwner(int owner, @Nullable Frame enclosing) {
        return switch (entries.get(owner).token().type()) {
            case VAR, CONST -> true;
            case PARENTHESIS_OPEN -> enclosing != null && enclosing.validSignature && !enclosing.signalSignature;
            default -> false;
        };
    }

    private boolean isParameterStart(Frame signature, int previous) {
        if (previous < signature.opener || !isAdjacentToCurrent(previous)) {
            return false;
        }
        var type = entries.get(previous).token().type();
        return previous == signature.opener || type == GdTokenType.COMMA
                || type == GdTokenType.PERIOD_PERIOD_PERIOD;
    }

    private static boolean isBindingName(@Nullable GdToken previous, boolean parameterStart) {
        return parameterStart || previous != null && switch (previous.type()) {
            case VAR, CONST, FOR, FUNC, SIGNAL, CLASS, CLASS_NAME, ENUM, NAMESPACE, TRAIT -> true;
            default -> false;
        };
    }

    private boolean followsMatchPattern(int position) {
        if (position < 0) {
            return false;
        }
        var token = entries.get(position).token();
        return isExpressionOperand(position) || token.type() == GdTokenType.UNDERSCORE
                || isAdjacentToCurrent(position - 1) && isIdentifier(token)
                && entries.get(position - 1).token().type() == GdTokenType.VAR;
    }

    private boolean isNodePathName(int prefix) {
        if (prefix < 0) {
            return false;
        }
        return switch (entries.get(prefix).token().type()) {
            case DOLLAR -> true;
            case PERCENT -> !isExpressionOperand(prefix - 1);
            case SLASH -> isAdjacentToCurrent(prefix - 1) && (entries.get(prefix - 1).nodePathName()
                    || entries.get(prefix - 1).token().type() == GdTokenType.DOLLAR);
            default -> false;
        };
    }

    private boolean isExpressionOperand(int position) {
        if (!isAdjacentToCurrent(position)) {
            return false;
        }
        var entry = entries.get(position);
        if (entry.typeOrigin() == TypeOrigin.DECLARATION) {
            return false;
        }
        return entry.operandName() || switch (entry.token().type()) {
            case LITERAL, SELF, SUPER -> true;
            case PARENTHESIS_CLOSE, BRACKET_CLOSE, BRACE_CLOSE -> isExpressionCloser(partner(position));
            default -> false;
        };
    }

    boolean isExpressionCloser(int opener) {
        if (opener < 0) {
            return false;
        }
        var entry = entries.get(opener);
        return switch (entry.role()) {
            case SIGNATURE, ENUM -> false;
            case TYPE -> entry.typeOrigin() == TypeOrigin.EXPRESSION;
            default -> true;
        };
    }

    private void finishBodyBefore(Frame frame, GdToken token, int lineIndent) {
        var type = token.type();
        if (isPendingSignature(frame)) {
            if (type != GdTokenType.PARENTHESIS_OPEN
                    && !(frame.body == Body.PREFIX && isIdentifier(token))) {
                removeFramesFrom(frames.size() - 1);
            }
            return;
        }
        if (frame.body == Body.SIGNATURE) {
            return;
        }
        if (frame.body == Body.RETURN_TYPE) {
            if (type != GdTokenType.COLON && !isReturnTypeArrow(type, frame)
                    && !(typeState != TypeState.NONE && nextTypeState(token) != TypeState.NONE)) {
                removeFramesFrom(frames.size() - 1);
            }
            return;
        }
        if (frame.body == Body.START) {
            if (!frame.bodyNewline) {
                frame.body = Body.INLINE;
            } else if (lineIndent > frame.indent) {
                frame.body = Body.INDENTED;
                frame.bodyIndent = lineIndent;
            } else {
                removeFramesFrom(frames.size() - 1);
                return;
            }
        } else if (logicalLineStart && frame.body == Body.INDENTED
                && lineIndent < frame.bodyIndent) {
            removeFramesFrom(frames.size() - 1);
            return;
        }
        updateMatchHeader(frame, token, lineIndent);
        var sameKindClosers = isClose(type) ? openFramesFor(type) : null;
        var closesOuter = isClose(type) && (frame.body == Body.INLINE
                || sameKindClosers != null && !sameKindClosers.isEmpty());
        // An indented body owns stray closers. A match pattern owns its own commas.
        if (type == GdTokenType.EOF || hasEnclosingDelimiter()
                && (closesOuter || type == GdTokenType.COMMA && !isMatchHeader(frame))) {
            removeFramesFrom(frames.size() - 1);
        }
    }

    private boolean hasEnclosingDelimiter() {
        return enclosingDelimiter() != null;
    }

    private @Nullable Frame enclosingDelimiter() {
        if (frames.isEmpty()) {
            return null;
        }
        var frame = frames.getLast();
        return frame.isFunction() ? frame.enclosingDelimiter : frame;
    }

    private void updateMatchHeader(Frame function, GdToken token, int indent) {
        if (function.body != Body.INDENTED) {
            return;
        }
        var matches = function.matches;
        if (logicalLineStart) {
            while (!matches.isEmpty() && indent <= matches.getLast().indent) {
                matches.removeLast();
            }
            if (!matches.isEmpty()) {
                var match = matches.getLast();
                if (match.phase == MatchPhase.WAIT_BRANCH) {
                    match.branchIndent = indent;
                }
                if (indent == match.branchIndent) {
                    match.phase = MatchPhase.HEADER;
                }
            }
            if (token.type() == GdTokenType.MATCH && !isMatchHeader(function)) {
                matches.add(new MatchFrame(indent));
            }
        }
        if (token.type() == GdTokenType.COLON && !matches.isEmpty()) {
            var match = matches.getLast();
            match.phase = switch (match.phase) {
                case VALUE -> MatchPhase.WAIT_BRANCH;
                case HEADER -> MatchPhase.BODY;
                default -> match.phase;
            };
        }
    }

    private static boolean isMatchHeader(Frame function) {
        return !function.matches.isEmpty() && function.matches.getLast().phase == MatchPhase.HEADER;
    }

    private void close(int position) {
        var closer = entries.get(position).token();
        var stack = openFramesFor(closer.type());
        var exact = stack != null && !stack.isEmpty();
        if (!exact && (frames.isEmpty() || frames.getLast().isFunction())) {
            return;
        }
        var frameIndex = exact ? stack.getLast() : frames.size() - 1;
        var frame = frames.get(frameIndex);
        // Skipped and replaced frames end here, but only a same-kind closer wraps a primary.
        for (var i = frameIndex; i < frames.size(); i++) {
            var ended = frames.get(i);
            if (!ended.isFunction()) {
                effectiveRecoveryEnds.set(ended.opener, closer.startByte());
            }
        }
        if (exact) {
            partners.set(frame.opener, position);
            partners.set(position, frame.opener);
        }
        removeFramesFrom(frameIndex);
        if (frame.role == Role.SIGNATURE && !frame.signalSignature) {
            var enclosingFunction = signatureOwner();
            if (enclosingFunction != null) {
                enclosingFunction.body = Body.RETURN_TYPE;
                enclosingFunction.signatureCloser = position;
            }
        } else if (!exact && frame.role == Role.TYPE) {
            typeState = TypeState.NONE;
        }
    }

    private static boolean isPendingSignature(Frame frame) {
        return frame.body == Body.PREFIX || frame.body == Body.ANONYMOUS_OPEN
                || frame.body == Body.NAMED_OPEN;
    }

    private @Nullable Frame signatureOwner() {
        var function = topFunction();
        return function != null && function.body == Body.SIGNATURE ? function : null;
    }

    private boolean isReturnTypeArrow(GdTokenType type, @Nullable Frame function) {
        return type == GdTokenType.FORWARD_ARROW && function != null && function.body == Body.RETURN_TYPE
                && function.signatureCloser == entries.size() - 1 && isAdjacentToCurrent(function.signatureCloser);
    }

    private void pushDelimiterFrame(int position, GdTokenType type, Role role, boolean validSignature,
                                    boolean signalSignature, boolean typeCommaAvailable) {
        var frameIndex = frames.size();
        var frame = new Frame(position, role, role != Role.TYPE || multiline(), 0, enclosingDelimiter());
        frame.validSignature = validSignature;
        frame.signalSignature = signalSignature;
        frame.suiteBaseline = effectiveIndent;
        frame.typeCommaAvailable = typeCommaAvailable;
        frame.enclosingCommaOwner = hasCommaOwner(frame.enclosingDelimiter);
        frame.typeOrigin = role == Role.TYPE ? entries.get(position).typeOrigin() : TypeOrigin.NONE;
        frames.add(frame);
        var stack = openFramesFor(type);
        if (stack != null) {
            stack.add(frameIndex);
        }
    }

    private @Nullable List<Integer> openFramesFor(GdTokenType type) {
        return switch (type) {
            case PARENTHESIS_OPEN, PARENTHESIS_CLOSE -> openParenFrames;
            case BRACKET_OPEN, BRACKET_CLOSE -> openBracketFrames;
            case BRACE_OPEN, BRACE_CLOSE -> openBraceFrames;
            default -> null;
        };
    }

    /// Reverse unwinding also restores the outermost saved baseline and keeps per-kind opener
    /// frame indices synchronized with `frames`.
    private void removeFramesFrom(int first) {
        for (var i = frames.size() - 1; i >= first; i--) {
            var frame = frames.removeLast();
            if (frame.isFunction()) {
                effectiveIndent = frame.indent;
                typeState = TypeState.NONE;
            } else {
                var stack = openFramesFor(entries.get(frame.opener).token().type());
                if (stack != null) {
                    stack.removeLast();
                }
            }
        }
    }

    private void add(GdToken token, boolean typePosition, Role role) {
        add(token, typePosition, TypeOrigin.NONE, role, false, false);
    }

    private void add(GdToken token, boolean typePosition, TypeOrigin typeOrigin, Role role,
                     boolean operandName, boolean nodePathName) {
        endPreviousGapAt(token.startByte());
        if (!isLayout(token.type()) && token.type() != GdTokenType.EOF) {
            var row = token.startLine() - 1;
            if (firstContentPositions[row] < 0) {
                firstContentPositions[row] = entries.size();
            }
        }
        entries.add(new Entry(token, typePosition, typeOrigin, role, operandName, nodePathName));
        partners.add(-1);
        effectiveRecoveryEnds.add(-1);
        cursorGaps.add(null);
    }

    private void openGap(int owner, int entry, Kind kind) {
        cursorGaps.set(entry, new CursorGapSlot(owner, entry, kind, index.utf8Length()));
    }

    private void endPreviousGapAt(int offset) {
        if (!cursorGaps.isEmpty()) {
            var gap = cursorGaps.getLast();
            if (gap != null && offset < gap.exitEnd()) {
                cursorGaps.set(gap.entry(), new CursorGapSlot(gap.owner(), gap.entry(), gap.kind(), offset));
            }
        }
    }

    private @Nullable Frame topFunction() {
        return !frames.isEmpty() && frames.getLast().isFunction() ? frames.getLast() : null;
    }

    private boolean multiline() {
        return !frames.isEmpty() && frames.getLast().multiline;
    }

    private @Nullable GdToken previousToken(int distance) {
        var position = entries.size() - 1 - distance;
        return isAdjacentToCurrent(position) ? entries.get(position).token() : null;
    }

    /// A child can end at a hard line boundary even while its enclosing delimiter suppresses layout.
    private boolean isAdjacentToCurrent(int position) {
        return position >= 0 && (hardStops.isEmpty() || hardStops.getLast() < entries.get(position).token().endByte());
    }

    private boolean isCalleeEnd(int position) {
        if (position < 0) {
            return false;
        }
        var token = entries.get(position).token();
        if (isAdjacentToCurrent(position - 1) && entries.get(position - 1).token().type() == GdTokenType.PERIOD) {
            return isNodeName(token);
        }
        return isIdentifier(token) || switch (token.type()) {
            case PARENTHESIS_CLOSE, BRACKET_CLOSE -> partners.get(position) >= 0;
            case PERIOD, SUPER, PRELOAD -> true;
            default -> false;
        };
    }

    static boolean isIdentifier(GdToken token) {
        return switch (token.type()) {
            case IDENTIFIER, MATCH, WHEN, CONST_PI, CONST_TAU, CONST_INF, CONST_NAN -> true;
            default -> false;
        };
    }

    static boolean isNodeName(GdToken token) {
        return switch (token.type()) {
            case IDENTIFIER, AND, AS, ASSERT, AWAIT, BREAK, BREAKPOINT, CLASS_NAME, CLASS, CONST,
                 CONST_PI, CONST_INF, CONST_NAN, CONST_TAU, CONTINUE, ELIF, ELSE, ENUM, EXTENDS,
                 FOR, FUNC, IF, IN, IS, MATCH, NAMESPACE, NOT, OR, PASS, PRELOAD, RETURN, SELF,
                 SIGNAL, STATIC, SUPER, TRAIT, UNDERSCORE, VAR, VOID, WHILE, WHEN, YIELD -> true;
            default -> false;
        };
    }

    private static boolean isPathName(@Nullable GdToken previous) {
        return previous != null && switch (previous.type()) {
            case PERIOD, SLASH, DOLLAR, PERCENT -> true;
            default -> false;
        };
    }

    boolean isPrimaryEnd(int position) {
        if (position < 0 || position >= entries.size()) {
            return false;
        }
        var token = entries.get(position).token();
        return isNodeName(token) || switch (token.type()) {
            case LITERAL -> true;
            case PARENTHESIS_CLOSE, BRACKET_CLOSE, BRACE_CLOSE -> partners.get(position) >= 0;
            default -> false;
        };
    }

    static boolean isLayout(GdTokenType type) {
        return type == GdTokenType.NEWLINE || type == GdTokenType.INDENT || type == GdTokenType.DEDENT;
    }

    private static boolean isOpen(GdTokenType type) {
        return type == GdTokenType.PARENTHESIS_OPEN || type == GdTokenType.BRACKET_OPEN
                || type == GdTokenType.BRACE_OPEN;
    }

    private static boolean isClose(GdTokenType type) {
        return type == GdTokenType.PARENTHESIS_CLOSE || type == GdTokenType.BRACKET_CLOSE
                || type == GdTokenType.BRACE_CLOSE;
    }
}
