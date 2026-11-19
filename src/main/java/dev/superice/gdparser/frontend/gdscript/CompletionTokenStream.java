package dev.superice.gdparser.frontend.gdscript;

import dev.superice.gdparser.frontend.ast.Range;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/// A recovery-independent token view. Layout remains a boundary unless the enclosing
/// expression enables multiline mode; function bodies temporarily disable that mode.
final class CompletionTokenStream {

    enum Role {
        OTHER, GROUP, CALL, SUBSCRIPT, CONTAINER, TYPE, SIGNATURE
    }

    record Entry(GdToken token, boolean typePosition, Role role) {
    }

    private enum Body {
        PREFIX, ANONYMOUS_OPEN, NAMED_OPEN, SIGNATURE, RETURN_TYPE, START, INLINE, INDENTED
    }

    private enum TypeState {
        NONE, START_AFTER_IS, START, EXTENDS_START, EXTENDS_PATH,
        BASE_NAME, DOTTED_DOT, DOTTED_NAME, COMPLETE
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
        Body body;
        boolean enclosingMultiline;
        boolean bodyNewline;
        int bodyIndent;
        final List<MatchFrame> matches = new ArrayList<>();

        Frame(int opener, Role role, boolean multiline, int indent) {
            this.opener = opener;
            this.role = role;
            this.multiline = multiline;
            this.indent = indent;
            body = opener < 0 ? Body.PREFIX : null;
        }

        boolean isFunction() {
            return opener < 0;
        }
    }

    private final List<Entry> entries = new ArrayList<>();
    private final List<Integer> partners = new ArrayList<>();
    private final List<Frame> frames = new ArrayList<>();
    private final List<Integer> openParenFrames = new ArrayList<>();
    private final List<Integer> openBracketFrames = new ArrayList<>();
    private final List<Integer> openBraceFrames = new ArrayList<>();
    private final List<GdComment> comments;
    private final List<Range> incompleteStrings = new ArrayList<>();
    private final SourceTextIndex index;
    private final int[] lineIndents;
    private TypeState typeState = TypeState.NONE;
    private int effectiveIndent;
    private boolean logicalLineStart = true;
    private final List<Integer> continuationLines;

    CompletionTokenStream(String source) {
        var tokenizer = GdTokenizer.managed(source);
        continuationLines = tokenizer.getContinuationLines();
        index = tokenizer.sourceTextIndex();
        lineIndents = new int[index.lineCount()];
        var row = 0;
        var leading = true;
        for (var i = 0; i < source.length(); i++) {
            var character = source.charAt(i);
            if (character == '\n') {
                row++;
                leading = true;
            } else if (leading && (character == ' ' || character == '\t')) {
                lineIndents[row] += character == '\t' ? GdTokenizer.TAB_SIZE : 1;
            } else {
                leading = false;
            }
        }
        GdToken token;
        do {
            token = tokenizer.scan();
            if (token.type() != GdTokenType.ERROR) {
                accept(token);
            } else if (GdTokenizer.UNTERMINATED_STRING_ERROR.equals(token.errorMessage())) {
                incompleteStrings.add(index.rangeOf(token.startByte(), token.endByte()));
            }
        } while (token.type() != GdTokenType.EOF);
        comments = tokenizer.getComments();
    }

    List<Entry> entries() {
        return entries;
    }

    List<GdComment> comments() {
        return comments;
    }

    List<Range> incompleteStrings() {
        return incompleteStrings;
    }

    SourceTextIndex index() {
        return index;
    }

    int partner(int position) {
        return partners.get(position);
    }

    private void accept(GdToken token) {
        var type = token.type();
        if (isLayout(type)) {
            // EOF's synthetic layout must not separate a prefix from the cursor at EOF.
            if (token.startByte() != token.endByte() && !multiline()) {
                if (type == GdTokenType.NEWLINE) {
                    discardIncompleteTypes();
                }
                var function = topFunction();
                if (type == GdTokenType.NEWLINE && function != null
                        && (function.body == Body.NAMED_OPEN
                        || function.body == Body.PREFIX && function.enclosingMultiline)) {
                    return;
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
            }
            return;
        }
        if (typeState != TypeState.NONE && nextTypeState(token) == TypeState.NONE) {
            typeState = TypeState.NONE;
            discardIncompleteTypes();
        }
        finishBodiesBefore(token);
        if (logicalLineStart && !multiline() && type != GdTokenType.EOF
                && Collections.binarySearch(continuationLines, index.pointAt(token.startByte()).row() + 1) < 0) {
            effectiveIndent = indent(token);
        }
        logicalLineStart = false;
        var previous = previousToken(0);
        var beforePrevious = previousToken(1);
        var function = topFunction();
        var signature = type == GdTokenType.PARENTHESIS_OPEN
                && ((function != null && isPendingSignature(function))
                || (previous != null && isIdentifier(previous)
                && beforePrevious != null && beforePrevious.type() == GdTokenType.SIGNAL));

        typeState = nextTypeState(token);
        if (type == GdTokenType.COLON && isTypeColon()) {
            typeState = TypeState.START;
        } else if ((type == GdTokenType.AS || type == GdTokenType.IS
                || type == GdTokenType.EXTENDS) && !isPathName(previous)) {
            typeState = switch (type) {
                case IS -> TypeState.START_AFTER_IS;
                case EXTENDS -> TypeState.EXTENDS_START;
                default -> TypeState.START;
            };
        } else if (type == GdTokenType.FORWARD_ARROW && closesSignature(entries.size() - 1)) {
            typeState = TypeState.START;
        }

        var inType = typeState != TypeState.NONE && typeState != TypeState.EXTENDS_PATH;
        var role = switch (type) {
            case PARENTHESIS_OPEN -> signature ? Role.SIGNATURE
                    : isCalleeEnd(entries.size() - 1) ? Role.CALL : Role.GROUP;
            case BRACKET_OPEN -> inType ? Role.TYPE
                    : isPrimaryEnd(entries.size() - 1) ? Role.SUBSCRIPT : Role.CONTAINER;
            case BRACE_OPEN -> Role.CONTAINER;
            default -> Role.OTHER;
        };
        var position = entries.size();
        add(token, inType, role);
        if (function != null && isPendingSignature(function)) {
            if (signature) {
                function.body = Body.SIGNATURE;
            } else if (isIdentifier(token)) {
                function.body = Body.NAMED_OPEN;
            }
        }

        if (type == GdTokenType.FUNC && !isPathName(previous)) {
            var frame = new Frame(-1, Role.OTHER, false, effectiveIndent);
            frame.enclosingMultiline = multiline();
            frames.add(frame);
            typeState = TypeState.NONE;
        } else if (isOpen(type)) {
            pushDelimiterFrame(position, type, role);
            if (role != Role.TYPE) {
                typeState = TypeState.NONE;
            }
        } else if (isClose(type)) {
            close(position);
        } else if (type == GdTokenType.COLON) {
            function = topFunction();
            if (function != null && function.body == Body.RETURN_TYPE) {
                function.body = Body.START;
                function.bodyNewline = false;
                typeState = TypeState.NONE;
            }
        }
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
                    : type == GdTokenType.PERIOD ? TypeState.DOTTED_DOT : typeSeparatorState(type);
            case DOTTED_DOT -> isIdentifier(token) ? TypeState.DOTTED_NAME : TypeState.NONE;
            case DOTTED_NAME -> type == GdTokenType.PERIOD ? TypeState.DOTTED_DOT : typeSeparatorState(type);
            case COMPLETE -> typeSeparatorState(type);
            case NONE -> TypeState.NONE;
        };
    }

    private static TypeState typeAtomState(GdToken token) {
        return token.type() == GdTokenType.VOID ? TypeState.COMPLETE
                : isIdentifier(token) ? TypeState.BASE_NAME : TypeState.NONE;
    }

    private TypeState typeSeparatorState(GdTokenType type) {
        if (!frames.isEmpty() && frames.getLast().role == Role.TYPE) {
            return switch (type) {
                case COMMA -> TypeState.START;
                case BRACKET_CLOSE -> TypeState.COMPLETE;
                default -> TypeState.NONE;
            };
        }
        return TypeState.NONE;
    }

    /// Non-multiline type containers cannot outlive a failed type atom or a hard newline.
    private void discardIncompleteTypes() {
        while (!frames.isEmpty() && frames.getLast().role == Role.TYPE && !frames.getLast().multiline) {
            removeFramesFrom(frames.size() - 1);
        }
    }

    private boolean isTypeColon() {
        var name = previousToken(0);
        if (name == null || !isIdentifier(name)) {
            return false;
        }
        var before = previousToken(1);
        if (before != null && switch (before.type()) {
            case VAR, CONST, FOR -> true;
            default -> false;
        }) {
            return true;
        }
        if (frames.isEmpty() || frames.getLast().role != Role.SIGNATURE) {
            return false;
        }
        return entries.size() - 2 == frames.getLast().opener
                || (before != null && (before.type() == GdTokenType.COMMA
                || before.type() == GdTokenType.PERIOD_PERIOD_PERIOD));
    }

    private void finishBodiesBefore(GdToken token) {
        var type = token.type();
        for (var frame = topFunction(); frame != null; frame = topFunction()) {
            if (isPendingSignature(frame)) {
                if (type == GdTokenType.PARENTHESIS_OPEN
                        || frame.body == Body.PREFIX && isIdentifier(token)) {
                    break;
                }
                removeFramesFrom(frames.size() - 1);
                continue;
            }
            if (frame.body == Body.SIGNATURE) {
                break;
            }
            if (frame.body == Body.RETURN_TYPE) {
                if (type == GdTokenType.COLON
                        || type == GdTokenType.FORWARD_ARROW && closesSignature(entries.size() - 1)
                        || typeState != TypeState.NONE && nextTypeState(token) != TypeState.NONE) {
                    break;
                }
                removeFramesFrom(frames.size() - 1);
                continue;
            }
            if (frame.body == Body.START) {
                if (!frame.bodyNewline) {
                    frame.body = Body.INLINE;
                } else if (indent(token) > frame.indent) {
                    frame.body = Body.INDENTED;
                    frame.bodyIndent = indent(token);
                } else {
                    removeFramesFrom(frames.size() - 1);
                    continue;
                }
            } else if (logicalLineStart && frame.body == Body.INDENTED
                    && indent(token) < frame.bodyIndent) {
                removeFramesFrom(frames.size() - 1);
                continue;
            }
            updateMatchHeader(frame, token);
            // Only enclosing expression delimiters terminate a lambda. A match pattern's
            // comma belongs to its branch header, not to the surrounding call or container.
            if (type == GdTokenType.EOF || hasEnclosingDelimiter()
                    && (isClose(type) || type == GdTokenType.COMMA && !isMatchHeader(frame))) {
                removeFramesFrom(frames.size() - 1);
                continue;
            }
            break;
        }
    }

    private boolean hasEnclosingDelimiter() {
        for (var i = frames.size() - 2; i >= 0; i--) {
            if (!frames.get(i).isFunction()) {
                return true;
            }
        }
        return false;
    }

    private void updateMatchHeader(Frame function, GdToken token) {
        if (function.body != Body.INDENTED) {
            return;
        }
        var matches = function.matches;
        if (logicalLineStart) {
            var indent = indent(token);
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
        var type = entries.get(position).token().type();
        var stack = openFramesFor(type);
        if (stack == null || stack.isEmpty()) {
            return;
        }
        var frameIndex = stack.getLast();
        var frame = frames.get(frameIndex);
        partners.set(frame.opener, position);
        partners.set(position, frame.opener);
        removeFramesFrom(frameIndex);
        if (frame.role == Role.SIGNATURE) {
            var enclosingFunction = topFunction();
            if (enclosingFunction != null) {
                enclosingFunction.body = Body.RETURN_TYPE;
            }
        }
    }

    private static boolean isPendingSignature(Frame frame) {
        return frame.body == Body.PREFIX || frame.body == Body.ANONYMOUS_OPEN
                || frame.body == Body.NAMED_OPEN;
    }

    private void pushDelimiterFrame(int position, GdTokenType type, Role role) {
        var frameIndex = frames.size();
        frames.add(new Frame(position, role, role != Role.TYPE || multiline(), 0));
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
        entries.add(new Entry(token, typePosition, role));
        partners.add(-1);
    }

    private @Nullable Frame topFunction() {
        return !frames.isEmpty() && frames.getLast().isFunction() ? frames.getLast() : null;
    }

    private boolean multiline() {
        return !frames.isEmpty() && frames.getLast().multiline;
    }

    private int indent(GdToken token) {
        return lineIndents[index.pointAt(token.startByte()).row()];
    }

    private @Nullable GdToken previousToken(int distance) {
        var position = entries.size() - 1 - distance;
        return position < 0 ? null : entries.get(position).token();
    }

    boolean closesSignature(int position) {
        return position >= 0 && partners.get(position) >= 0
                && entries.get(partners.get(position)).role() == Role.SIGNATURE;
    }

    boolean isCalleeEnd(int position) {
        if (position < 0) {
            return false;
        }
        var token = entries.get(position).token();
        if (position > 0 && entries.get(position - 1).token().type() == GdTokenType.PERIOD) {
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

    private static boolean matches(GdTokenType open, GdTokenType close) {
        return switch (open) {
            case PARENTHESIS_OPEN -> close == GdTokenType.PARENTHESIS_CLOSE;
            case BRACKET_OPEN -> close == GdTokenType.BRACKET_CLOSE;
            case BRACE_OPEN -> close == GdTokenType.BRACE_CLOSE;
            default -> false;
        };
    }
}
