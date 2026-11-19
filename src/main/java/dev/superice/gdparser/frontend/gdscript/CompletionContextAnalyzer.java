package dev.superice.gdparser.frontend.gdscript;

import dev.superice.gdparser.frontend.ast.AttributeExpression;
import dev.superice.gdparser.frontend.ast.IdentifierExpression;
import dev.superice.gdparser.frontend.ast.Node;
import dev.superice.gdparser.frontend.ast.Range;
import dev.superice.gdparser.frontend.ast.SourceFile;
import dev.superice.gdparser.frontend.ast.TypeRef;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/// Combines surviving AST structure with token-level context for discarded expressions.
final class CompletionContextAnalyzer {

    private final CompletionTokenStream stream;
    private final List<CompletionTokenStream.Entry> entries;
    private final SourceTextIndex index;
    private final int offset;
    private final List<Node> path;

    private CompletionContextAnalyzer(CompletionTokenStream stream, SourceFile ast, long byteOffset) {
        this.stream = stream;
        entries = stream.entries();
        index = stream.index();
        offset = Math.clamp(byteOffset, 0, index.utf8Length());
        path = pathAt(ast);
    }

    static CompletionContext analyze(String source, SourceFile ast, long byteOffset) {
        return new CompletionContextAnalyzer(new CompletionTokenStream(source), ast, byteOffset).analyze();
    }

    private CompletionContext analyze() {
        if (isInTriviaOrString()) {
            return context(CompletionContext.Kind.UNKNOWN, zeroRange());
        }
        var name = nameAtCursor();
        if (isTypePosition(name)) {
            return context(CompletionContext.Kind.TYPE_POSITION,
                    name >= 0 && entries.get(name).typePosition() ? range(token(name)) : zeroRange());
        }
        var dot = memberDot(name);
        if (dot >= 0) {
            var receiver = receiverBefore(dot);
            if (receiver != null) {
                var next = dot + 1;
                var replacement = next < entries.size() && CompletionTokenStream.isNodeName(token(next))
                        ? range(token(next)) : index.rangeOf(token(dot).endByte(), token(dot).endByte());
                return new CompletionContext(CompletionContext.Kind.MEMBER_ACCESS, replacement, receiver);
            }
        }
        if (name >= 0 && CompletionTokenStream.isIdentifier(token(name))) {
            return context(CompletionContext.Kind.IDENTIFIER, range(token(name)));
        }
        for (var node : path.reversed()) {
            if (node instanceof IdentifierExpression) {
                return context(CompletionContext.Kind.IDENTIFIER, node.range());
            }
        }
        var paren = enclosingParen();
        if (paren >= 0 && entries.get(paren).role() == CompletionTokenStream.Role.CALL) {
            return context(CompletionContext.Kind.CALL_ARGUMENT, zeroRange());
        }
        return context(CompletionContext.Kind.UNKNOWN, zeroRange());
    }

    private boolean isInTriviaOrString() {
        for (var range : stream.incompleteStrings()) {
            if (range.startByte() < offset && offset <= range.endByte()) {
                return true;
            }
        }
        for (var comment : stream.comments()) {
            if (comment.startByte() < offset && offset <= comment.endByte()) {
                return true;
            }
        }
        for (var entry : entries) {
            var token = entry.token();
            if (token.startByte() < offset && offset < token.endByte()
                    && switch (token.literalKind()) {
                case STRING, STRING_NAME, NODE_PATH -> true;
                default -> false;
            }) {
                return true;
            }
        }
        return false;
    }

    private int nameAtCursor() {
        var ending = -1;
        for (var i = 0; i < entries.size(); i++) {
            var token = token(i);
            if (token.startByte() > offset) {
                break;
            }
            if (CompletionTokenStream.isNodeName(token)) {
                if (offset < token.endByte()) {
                    return i;
                }
                if (token.endByte() == offset) {
                    ending = i;
                }
            }
        }
        return ending;
    }

    private boolean isTypePosition(int name) {
        if (name >= 0 && entries.get(name).typePosition()) {
            return true;
        }
        // A keyword inside an expression is not an unfinished name in the preceding type slot.
        if (name >= 0 && token(name).startByte() < offset) {
            return false;
        }
        var previous = previousAtCursor();
        if (previous >= 0 && entries.get(previous).typePosition()
                && switch (token(previous).type()) {
            case COLON, AS, IS, NOT, EXTENDS, FORWARD_ARROW, PERIOD, BRACKET_OPEN, COMMA -> true;
            default -> false;
        }) {
            return true;
        }
        for (var node : path) {
            if (node instanceof TypeRef type && !type.sourceText().stripLeading().startsWith(":")) {
                return true;
            }
        }
        return false;
    }

    private int memberDot(int name) {
        if (name > 0 && token(name - 1).type() == GdTokenType.PERIOD) {
            return name - 1;
        }
        var previous = previousAtCursor();
        if (previous >= 0 && token(previous).type() == GdTokenType.PERIOD) {
            return previous;
        }
        if (previous > 0 && token(previous).type() == GdTokenType.PARENTHESIS_OPEN
                && token(previous - 1).type() == GdTokenType.PERIOD) {
            return previous - 1;
        }
        return -1;
    }

    private @Nullable Range receiverBefore(int dot) {
        if (dot == 0 || !stream.isPrimaryEnd(dot - 1)) {
            return null;
        }
        var end = token(dot - 1).endByte();
        var start = primaryStart(dot - 1);
        for (var node : path.reversed()) {
            if (node instanceof AttributeExpression chain) {
                for (var step : chain.steps()) {
                    if (step.range().startByte() == token(dot).endByte()
                            || (dot + 1 < entries.size()
                            && step.range().startByte() == token(dot + 1).startByte())) {
                        start = chain.range().startByte();
                        return index.rangeOf(start, end);
                    }
                }
            }
        }
        return index.rangeOf(start, end);
    }

    private int primaryStart(int position) {
        var start = token(position).startByte();
        while (position >= 0) {
            var token = token(position);
            start = token.startByte();
            if (token.type() == GdTokenType.PARENTHESIS_CLOSE
                    || token.type() == GdTokenType.BRACKET_CLOSE
                    || token.type() == GdTokenType.BRACE_CLOSE) {
                var open = stream.partner(position);
                if (open < 0) {
                    break;
                }
                start = token(open).startByte();
                var role = entries.get(open).role();
                position = open;
                if ((role == CompletionTokenStream.Role.CALL || role == CompletionTokenStream.Role.SUBSCRIPT)
                        && position > 0 && stream.isPrimaryEnd(position - 1)) {
                    position--;
                    continue;
                }
            } else if (!stream.isPrimaryEnd(position)) {
                break;
            }
            if (isPathSegment(token)) {
                position = nodePathStart(position);
                start = token(position).startByte();
            }
            if (position >= 2 && token(position - 1).type() == GdTokenType.PERIOD
                    && stream.isPrimaryEnd(position - 2)) {
                position -= 2;
                continue;
            }
            break;
        }
        return start;
    }

    private int nodePathStart(int position) {
        var left = position;
        var standalone = position;
        while (left > 0) {
            if (token(left - 1).type() == GdTokenType.PERCENT) {
                left--;
                var prefix = isExpressionPrefix(left);
                if (prefix) {
                    standalone = left;
                }
                // Within a dollar path '%' may start each slash-delimited segment.
                if (left == 0 || token(left - 1).type() != GdTokenType.SLASH
                        && token(left - 1).type() != GdTokenType.DOLLAR) {
                    return prefix ? left : standalone;
                }
            }
            if (token(left - 1).type() == GdTokenType.DOLLAR) {
                return left - 1;
            }
            if (token(left - 1).type() != GdTokenType.SLASH) {
                break;
            }
            left--;
            if (left > 0 && token(left - 1).type() == GdTokenType.DOLLAR) {
                return left - 1;
            }
            if (left == 0 || !isPathSegment(token(left - 1))) {
                break;
            }
            left--;
        }
        // A slash before '%' can be division rather than a dollar-path separator.
        return standalone;
    }

    private static boolean isPathSegment(GdToken token) {
        return CompletionTokenStream.isNodeName(token)
                || token.type() == GdTokenType.LITERAL && token.literalKind() == GdLiteralKind.STRING;
    }

    private boolean isExpressionPrefix(int position) {
        // A keyword after '/' or '%' is a path operand only if a real path prefix owns it.
        // Each unresolved '%' reverses that question; walking left avoids recursive scans.
        var invert = false;
        outer:
        while (position > 0) {
            var previous = token(position - 1);
            if (CompletionTokenStream.isIdentifier(previous) || previous.type() == GdTokenType.LITERAL
                    || previous.type() == GdTokenType.SELF || previous.type() == GdTokenType.SUPER) {
                return invert;
            }
            if (previous.type() == GdTokenType.PARENTHESIS_CLOSE
                    || previous.type() == GdTokenType.BRACKET_CLOSE
                    || previous.type() == GdTokenType.BRACE_CLOSE) {
                var open = stream.partner(position - 1);
                var nonOperandClose = open >= 0
                        && (entries.get(open).role() == CompletionTokenStream.Role.SIGNATURE
                        || entries.get(open).role() == CompletionTokenStream.Role.TYPE);
                return invert != nonOperandClose;
            }
            if (!CompletionTokenStream.isNodeName(previous) || position < 2) {
                return !invert;
            }
            var before = token(position - 2).type();
            if (before == GdTokenType.PERIOD || before == GdTokenType.DOLLAR) {
                return invert;
            }
            if (before != GdTokenType.PERCENT && before != GdTokenType.SLASH) {
                return !invert;
            }
            var left = position - 1;
            while (left > 0) {
                if (token(left - 1).type() == GdTokenType.PERCENT) {
                    left--;
                    if (left == 0) {
                        return invert;
                    }
                    var kind = token(left - 1).type();
                    if (kind == GdTokenType.SLASH || kind == GdTokenType.DOLLAR) {
                        return invert;
                    }
                    invert = !invert;
                    position = left;
                    continue outer;
                }
                if (token(left - 1).type() == GdTokenType.DOLLAR) {
                    return invert;
                }
                if (token(left - 1).type() != GdTokenType.SLASH) {
                    return !invert;
                }
                left--;
                if (left > 0 && token(left - 1).type() == GdTokenType.DOLLAR) {
                    return invert;
                }
                if (left == 0 || !isPathSegment(token(left - 1))) {
                    return !invert;
                }
                left--;
            }
            return !invert;
        }
        return !invert;
    }

    private int enclosingParen() {
        var result = -1;
        for (var i = 0; i < entries.size(); i++) {
            var token = token(i);
            if (token.startByte() >= offset) {
                break;
            }
            if (token.type() == GdTokenType.PARENTHESIS_OPEN) {
                var close = stream.partner(i);
                if (close < 0 || offset <= token(close).startByte()) {
                    result = i;
                }
            }
        }
        return result;
    }

    private int previousAtCursor() {
        var previous = -1;
        for (var i = 0; i < entries.size(); i++) {
            var token = token(i);
            if (token.startByte() > offset) {
                break;
            }
            if (token.endByte() <= offset && token.type() != GdTokenType.EOF) {
                previous = i;
            }
        }
        return previous;
    }

    private List<Node> pathAt(SourceFile root) {
        var result = new ArrayList<Node>();
        Node current = root;
        while (current != null) {
            result.add(current);
            Node next = null;
            for (var child : current.getChildren()) {
                var range = child.range();
                if (range.startByte() <= offset && offset <= range.endByte()
                        && (next == null || range.endByte() - range.startByte()
                        < next.range().endByte() - next.range().startByte())) {
                    next = child;
                }
            }
            current = next;
        }
        return result;
    }

    private GdToken token(int position) {
        return entries.get(position).token();
    }

    private Range range(GdToken token) {
        return index.rangeOf(token.startByte(), token.endByte());
    }

    private Range zeroRange() {
        return index.rangeOf(offset, offset);
    }

    private CompletionContext context(CompletionContext.Kind kind, Range replacement) {
        return new CompletionContext(kind, replacement, null);
    }
}
