package dev.superice.gdparser.infra.treesitter;

import dev.superice.gdparser.frontend.ast.Point;
import dev.superice.gdparser.frontend.ast.Range;
import dev.superice.gdparser.frontend.cst.CstNodeView;
import dev.superice.gdparser.frontend.gdscript.GdScriptParserFacade;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/// Classifies the completion context at a cursor byte offset by walking the immutable CST
/// snapshot. Both syntactically complete member access (cursor inside a member name prefix,
/// e.g. `obj.pa|r`) and incomplete input surfaced as natural tree-sitter `ERROR`/missing
/// placeholder structures (e.g. `obj.|` at end of file) are handled.
///
/// @deprecated Retained for the legacy CST facade; new consumers use [GdScriptParserFacade].
@Deprecated
final class CompletionContextAnalyzer {

    private static final String LINE_CONTINUATION_TYPE = "line_continuation";

    private final byte[] sourceBytes;
    private final int offset;

    private CompletionContextAnalyzer(byte[] sourceBytes, int offset) {
        this.sourceBytes = sourceBytes;
        this.offset = offset;
    }

    static CompletionContext analyze(byte[] sourceBytes, CstNodeView root, int offset) {
        return new CompletionContextAnalyzer(sourceBytes, offset).classify(root);
    }

    private CompletionContext classify(CstNodeView root) {
        var path = pathAt(root);
        for (var index = path.size() - 1; index >= 0; index--) {
            var node = path.get(index);
            var parent = index > 0 ? path.get(index - 1) : null;

            if (isIdentifierLike(node) && parent != null && parent.type().equals("attribute")) {
                var steps = namedChainChildren(parent);
                var stepIndex = identityIndexOf(steps, node);
                if (stepIndex > 0) {
                    return memberAccess(rangeOf(node), chainRange(parent, steps.get(stepIndex - 1)));
                }
                return identifier(rangeOf(node));
            }
            if (isIdentifierLike(node) && parent != null
                    && (parent.type().equals("attribute_call") || parent.type().equals("attribute_subscript"))
                    && index > 1 && path.get(index - 2).type().equals("attribute")) {
                // The name token of a call/subscript step, e.g. `obj.met|hod(1)`: the step node
                // sits inside the flat attribute chain, so resolve the receiver against the
                // chain child preceding the step and keep the bare name as the replaceable range.
                var attribute = path.get(index - 2);
                var steps = namedChainChildren(attribute);
                var stepIndex = identityIndexOf(steps, parent);
                if (stepIndex > 0) {
                    return memberAccess(rangeOf(node), chainRange(attribute, steps.get(stepIndex - 1)));
                }
                return identifier(rangeOf(node));
            }
            if (node.type().equals(".") && parent != null && parent.type().equals("attribute")) {
                var context = memberAccessAtDot(parent, node);
                if (context != null) {
                    return context;
                }
            }
            if (isIdentifierLike(node) && parent != null && parent.type().equals("type")) {
                return new CompletionContext(CompletionContext.Kind.TYPE_POSITION, rangeOf(node), null);
            }
            if (isIdentifierLike(node) && parent != null && parent.type().equals("binary_operator")) {
                var previousToken = previousAnonymousType(parent, node);
                if (previousToken != null && switch (previousToken) {
                    case "as", "is", "not" -> true;
                    default -> false;
                }) {
                    return new CompletionContext(CompletionContext.Kind.TYPE_POSITION, rangeOf(node), null);
                }
            }
            if (node.type().equals("type")) {
                return new CompletionContext(CompletionContext.Kind.TYPE_POSITION, zeroRange(), null);
            }
            if (node.type().equals("arguments")) {
                return new CompletionContext(CompletionContext.Kind.CALL_ARGUMENT, zeroRange(), null);
            }
            if (node.isError()) {
                var context = analyzeErrorTail(node);
                if (context != null) {
                    return context;
                }
            }
            if (isIdentifierLike(node)) {
                return identifier(rangeOf(node));
            }
        }
        return unknown();
    }

    /// Analyzes the trailing tokens of an `ERROR` node: the grammar wraps unfinished input such
    /// as `receiver.` or `callee(` at end of file into an error node, and the token sequence
    /// right before the cursor still identifies the completion context.
    private @Nullable CompletionContext analyzeErrorTail(CstNodeView errorNode) {
        CstNodeView lastBefore = null;
        for (var child : errorNode.children()) {
            if (child.range().startByte() > offset) {
                break;
            }
            if (child.range().endByte() <= offset) {
                lastBefore = child;
            }
        }
        if (lastBefore == null) {
            return null;
        }
        return switch (lastBefore.type()) {
            case "." -> {
                var receiver = receiverRangeBeforeDot(errorNode, lastBefore);
                yield receiver == null ? null : memberAccess(zeroRange(), receiver);
            }
            case "(", "," -> new CompletionContext(CompletionContext.Kind.CALL_ARGUMENT, zeroRange(), null);
            case ":" -> looksLikeVariableTypeTruncation(errorNode, lastBefore)
                    ? new CompletionContext(CompletionContext.Kind.TYPE_POSITION, zeroRange(), null)
                    : null;
            case "extends" -> new CompletionContext(CompletionContext.Kind.TYPE_POSITION, zeroRange(), null);
            case "=", "return" -> identifier(zeroRange());
            default -> null;
        };
    }

    /// Range of the receiver fragment on the left side of the final dot inside an `ERROR` node,
    /// following the `named (. named)*` chain backwards from the dot.
    private @Nullable Range receiverRangeBeforeDot(CstNodeView errorNode, CstNodeView dot) {
        var children = errorNode.children();
        var dotIndex = identityIndexOf(children, dot);
        var endIndex = dotIndex - 1;
        if (endIndex < 0 || !isChainLinkable(children.get(endIndex))) {
            return null;
        }
        var startIndex = endIndex;
        while (startIndex - 2 >= 0
                && children.get(startIndex - 1).type().equals(".")
                && isChainLinkable(children.get(startIndex - 2))) {
            startIndex -= 2;
        }
        return chainRange(children.get(startIndex), children.get(endIndex));
    }

    /// Detects a `var name:` / `const NAME:` prefix truncated at the cursor, where the type
    /// annotation is expected next. Dictionary colons do not match this shape.
    private boolean looksLikeVariableTypeTruncation(CstNodeView errorNode, CstNodeView colon) {
        var children = errorNode.children();
        var colonIndex = identityIndexOf(children, colon);
        if (colonIndex < 2) {
            return false;
        }
        var nameNode = children.get(colonIndex - 1);
        var keywordNode = children.get(colonIndex - 2);
        var isDeclarationKeyword = switch (keywordNode.type()) {
            case "var", "const" -> true;
            default -> false;
        };
        return isDeclarationKeyword && (nameNode.type().equals("name") || nameNode.type().equals("identifier"));
    }

    /// Member access context for a cursor sitting directly on the dot of a complete `attribute`
    /// node, e.g. `obj.|pa`: the following member token is the one being completed.
    private @Nullable CompletionContext memberAccessAtDot(CstNodeView attribute, CstNodeView dot) {
        var children = attribute.children();
        var dotIndex = identityIndexOf(children, dot);
        CstNodeView beforeNamed = null;
        for (var index = dotIndex - 1; index >= 0; index--) {
            var candidate = children.get(index);
            if (isChainLinkable(candidate)) {
                beforeNamed = candidate;
                break;
            }
            if (!candidate.type().equals(".")) {
                break;
            }
        }
        if (beforeNamed == null) {
            return null;
        }
        CstNodeView afterNamed = null;
        for (var index = dotIndex + 1; index < children.size(); index++) {
            var candidate = children.get(index);
            if (isChainLinkable(candidate)) {
                afterNamed = candidate;
                break;
            }
        }
        var replaceable = afterNamed != null ? replaceableRangeOfStep(afterNamed) : zeroRange();
        return memberAccess(replaceable, chainRange(children.getFirst(), beforeNamed));
    }

    /// Replaceable range for the chain child after the dot: the bare name token of a call or
    /// subscript step rather than its whole argument-carrying span.
    private Range replaceableRangeOfStep(CstNodeView step) {
        if (step.type().equals("attribute_call") || step.type().equals("attribute_subscript")) {
            for (var child : step.namedChildren()) {
                if (isIdentifierLike(child)) {
                    return rangeOf(child);
                }
            }
        }
        return rangeOf(step);
    }

    /// Path from the root down to the deepest node whose span contains the cursor offset.
    /// Boundary ties prefer named nodes, then the leftmost sibling, so a cursor at the end of a
    /// token still resolves to that token while zero-width missing placeholders win over
    /// surrounding anonymous punctuation.
    private List<CstNodeView> pathAt(CstNodeView root) {
        var path = new ArrayList<CstNodeView>();
        var current = root;
        path.add(current);
        while (true) {
            var next = deepestChildContaining(current);
            if (next == null) {
                break;
            }
            path.add(next);
            current = next;
        }
        return path;
    }

    private @Nullable CstNodeView deepestChildContaining(CstNodeView node) {
        CstNodeView best = null;
        for (var child : node.children()) {
            var range = child.range();
            if (range.startByte() <= offset && offset <= range.endByte()) {
                if (best == null || !best.isNamed() && child.isNamed()) {
                    best = child;
                }
                if (best.isNamed()) {
                    break;
                }
            }
        }
        return best;
    }

    private static boolean isIdentifierLike(CstNodeView node) {
        return node.type().equals("identifier") || node.type().equals("name");
    }

    private static boolean isChainLinkable(CstNodeView node) {
        return node.isNamed() && !node.type().equals(LINE_CONTINUATION_TYPE);
    }

    private static List<CstNodeView> namedChainChildren(CstNodeView node) {
        var result = new ArrayList<CstNodeView>();
        for (var child : node.namedChildren()) {
            if (!child.type().equals(LINE_CONTINUATION_TYPE)) {
                result.add(child);
            }
        }
        return result;
    }

    private static @Nullable String previousAnonymousType(CstNodeView parent, CstNodeView node) {
        var children = parent.children();
        var index = identityIndexOf(children, node);
        for (var i = index - 1; i >= 0; i--) {
            var sibling = children.get(i);
            if (sibling.isNamed()) {
                continue;
            }
            return sibling.type();
        }
        return null;
    }

    private static int identityIndexOf(List<CstNodeView> nodes, CstNodeView target) {
        for (var index = 0; index < nodes.size(); index++) {
            if (nodes.get(index) == target) {
                return index;
            }
        }
        return -1;
    }

    private static Range rangeOf(CstNodeView node) {
        var range = node.range();
        return new Range(
                range.startByte(),
                range.endByte(),
                new Point(range.startPoint().row(), range.startPoint().column()),
                new Point(range.endPoint().row(), range.endPoint().column())
        );
    }

    private static Range chainRange(CstNodeView first, CstNodeView last) {
        var start = first.range();
        var end = last.range();
        return new Range(
                start.startByte(),
                end.endByte(),
                new Point(start.startPoint().row(), start.startPoint().column()),
                new Point(end.endPoint().row(), end.endPoint().column())
        );
    }

    private CompletionContext memberAccess(Range replaceable, Range receiver) {
        return new CompletionContext(CompletionContext.Kind.MEMBER_ACCESS, replaceable, receiver);
    }

    private CompletionContext identifier(Range replaceable) {
        return new CompletionContext(CompletionContext.Kind.IDENTIFIER, replaceable, null);
    }

    private CompletionContext unknown() {
        return new CompletionContext(CompletionContext.Kind.UNKNOWN, zeroRange(), null);
    }

    /// Zero-width range at the cursor; points are computed from raw bytes because tree-sitter
    /// columns count bytes, not chars.
    private Range zeroRange() {
        var row = 0;
        var column = 0;
        for (var index = 0; index < offset; index++) {
            if (sourceBytes[index] == '\n') {
                row++;
                column = 0;
            } else {
                column++;
            }
        }
        var point = new Point(row, column);
        return new Range(offset, offset, point, point);
    }
}
