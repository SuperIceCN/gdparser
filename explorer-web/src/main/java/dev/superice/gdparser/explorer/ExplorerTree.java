package dev.superice.gdparser.explorer;

import dev.superice.gdparser.frontend.ast.ArrayExpression;
import dev.superice.gdparser.frontend.ast.AttributeExpression;
import dev.superice.gdparser.frontend.ast.BinaryExpression;
import dev.superice.gdparser.frontend.ast.Block;
import dev.superice.gdparser.frontend.ast.CallExpression;
import dev.superice.gdparser.frontend.ast.ClassDeclaration;
import dev.superice.gdparser.frontend.ast.ClassNameStatement;
import dev.superice.gdparser.frontend.ast.ConstructorDeclaration;
import dev.superice.gdparser.frontend.ast.DictionaryExpression;
import dev.superice.gdparser.frontend.ast.EnumDeclaration;
import dev.superice.gdparser.frontend.ast.EnumMember;
import dev.superice.gdparser.frontend.ast.ErrorExpression;
import dev.superice.gdparser.frontend.ast.ErrorStatement;
import dev.superice.gdparser.frontend.ast.ExpressionStatement;
import dev.superice.gdparser.frontend.ast.ExtendsStatement;
import dev.superice.gdparser.frontend.ast.ForStatement;
import dev.superice.gdparser.frontend.ast.FunctionDeclaration;
import dev.superice.gdparser.frontend.ast.IdentifierExpression;
import dev.superice.gdparser.frontend.ast.IfStatement;
import dev.superice.gdparser.frontend.ast.LambdaExpression;
import dev.superice.gdparser.frontend.ast.LiteralExpression;
import dev.superice.gdparser.frontend.ast.MissingAttributeStep;
import dev.superice.gdparser.frontend.ast.Node;
import dev.superice.gdparser.frontend.ast.Parameter;
import dev.superice.gdparser.frontend.ast.PassStatement;
import dev.superice.gdparser.frontend.ast.ReturnStatement;
import dev.superice.gdparser.frontend.ast.SignalStatement;
import dev.superice.gdparser.frontend.ast.SourceFile;
import dev.superice.gdparser.frontend.ast.TypeRef;
import dev.superice.gdparser.frontend.ast.VariableDeclaration;
import dev.superice.gdparser.frontend.ast.WhileStatement;

import java.util.ArrayDeque;
import java.util.Iterator;

final class ExplorerTree {
    private static final String HEX = "0123456789abcdef";

    private ExplorerTree() {
    }

    static View render(Node root, int maxDepth, int maxNodes, int maxText) {
        var text = new StringBuilder();
        // A frame advances one child at a time in source order without pushing every sibling.
        var ancestors = new ArrayDeque<Frame>();
        var node = root;
        var functionChild = false;
        var truncated = false;
        for (var nodes = 0; ; nodes++) {
            var depth = ancestors.size();
            if (nodes == maxNodes || !appendLine(text, node, functionChild, depth, maxText)) {
                truncated = true;
                break;
            }

            var children = node.getChildren();
            if (!children.isEmpty()) {
                if (depth == maxDepth) {
                    truncated = true;
                } else {
                    var function = node instanceof FunctionDeclaration || node instanceof ConstructorDeclaration
                            || node instanceof LambdaExpression;
                    ancestors.push(new Frame(children.iterator(), function));
                }
            }
            while (!ancestors.isEmpty() && !ancestors.peek().children().hasNext()) {
                ancestors.pop();
            }
            if (ancestors.isEmpty()) {
                break;
            }
            var frame = ancestors.peek();
            node = frame.children().next();
            functionChild = frame.function();
        }
        return new View(text.toString(), truncated);
    }

    private static boolean appendLine(StringBuilder text, Node node, boolean functionChild, int depth, int limit) {
        var start = text.length();
        if (start == limit) {
            return false;
        }
        if (start != 0) {
            text.append('\n');
        }
        for (var i = 0; i < depth; i++) {
            text.append("  ");
        }
        if (appendLabel(text, node, functionChild, limit)) {
            var range = node.range();
            var from = range.startPoint();
            var to = range.endPoint();
            text.append(" [").append(range.startByte()).append(',').append(range.endByte()).append(") (")
                    .append(from.row()).append(':').append(from.column()).append("..")
                    .append(to.row()).append(':').append(to.column()).append(')');
            if (text.length() <= limit) {
                return true;
            }
        }
        // A line that does not fit is omitted in full, including its separator.
        text.setLength(start);
        return false;
    }

    private static boolean appendLabel(StringBuilder text, Node node, boolean functionChild, int limit) {
        switch (node) {
            case SourceFile _ -> text.append("SourceFile");
            case FunctionDeclaration n -> {
                return appendField(text.append("FunctionDeclaration"), " name=", n.name(), limit);
            }
            case ConstructorDeclaration _ -> text.append("ConstructorDeclaration name=_init");
            case ClassDeclaration n -> {
                return appendField(text.append("ClassDeclaration"), " name=", n.name(), limit);
            }
            case ClassNameStatement n -> {
                return appendField(text.append("ClassNameStatement"), " name=", n.name(), limit);
            }
            case SignalStatement n -> {
                return appendField(text.append("SignalStatement"), " name=", n.name(), limit);
            }
            case EnumDeclaration n -> {
                return appendField(text.append("EnumDeclaration"), " name=", n.name(), limit);
            }
            case EnumMember n -> {
                return appendField(text.append("EnumMember"), " name=", n.name(), limit);
            }
            case VariableDeclaration n -> {
                return appendField(text.append("VariableDeclaration"), " name=", n.name(), limit);
            }
            case Parameter n -> {
                return appendField(text.append("Parameter"), " name=", n.name(), limit);
            }
            case ExtendsStatement n -> {
                return appendField(text.append("ExtendsStatement"), " target=", n.target(), limit);
            }
            case TypeRef n -> {
                return appendField(text.append("TypeRef"), " raw=", n.sourceText(), limit);
            }
            case Block _ -> text.append(functionChild ? "FunctionBody" : "Block");
            case PassStatement _ -> text.append("PassStatement");
            case IfStatement _ -> text.append("IfStatement");
            case WhileStatement _ -> text.append("WhileStatement");
            case ForStatement _ -> text.append("ForStatement");
            case ReturnStatement _ -> text.append("ReturnStatement");
            case ExpressionStatement _ -> text.append("ExpressionStatement");
            case LambdaExpression n -> {
                return appendField(text.append("LambdaExpression"), " name=", n.name(), limit);
            }
            case LiteralExpression n -> {
                return appendField(text.append("LiteralExpression"), " kind=", n.kind(), limit)
                        && appendField(text, " raw=", n.sourceText(), limit);
            }
            case IdentifierExpression n -> {
                return appendField(text.append("IdentifierExpression"), " name=", n.name(), limit);
            }
            case BinaryExpression n -> {
                return appendField(text.append("BinaryExpression"), " operator=", n.operator(), limit);
            }
            case CallExpression _ -> text.append("CallExpression");
            case AttributeExpression _ -> text.append("AttributeExpression");
            case ArrayExpression _ -> text.append("ArrayExpression");
            case DictionaryExpression _ -> text.append("DictionaryExpression");
            case ErrorExpression n -> {
                return appendField(text.append(n.kind().name()).append(" kind=expression"), " slot=", n.nodeType(), limit);
            }
            case ErrorStatement n -> {
                return appendField(text.append(n.kind().name()).append(" kind=statement"), " slot=", n.nodeType(), limit);
            }
            case MissingAttributeStep _ -> text.append("MISSING kind=attribute slot=attribute.member");
            default -> text.append("Node");
        }
        return true;
    }

    private static boolean appendField(StringBuilder text, String prefix, String value, int limit) {
        if (value == null) {
            return true;
        }
        text.append(prefix);
        if (value.length() > limit - text.length()) {
            return false;
        }
        // Escape untrusted scalars before counting their length so none can create a new tree line.
        for (var i = 0; i < value.length(); i++) {
            var ch = value.charAt(i);
            var escape = switch (ch) {
                case '\\' -> "\\\\";
                case '\r' -> "\\r";
                case '\n' -> "\\n";
                case '\t' -> "\\t";
                default -> null;
            };
            var remaining = limit - text.length();
            if (escape != null) {
                if (remaining < escape.length()) {
                    return false;
                }
                text.append(escape);
            } else if (needsUnicodeEscape(value, i, ch)) {
                if (remaining < 6) {
                    return false;
                }
                text.append("\\u").append(HEX.charAt(ch >> 12)).append(HEX.charAt((ch >> 8) & 15))
                        .append(HEX.charAt((ch >> 4) & 15)).append(HEX.charAt(ch & 15));
            } else {
                if (remaining == 0) {
                    return false;
                }
                text.append(ch);
            }
        }
        return true;
    }

    private static boolean needsUnicodeEscape(String value, int index, char ch) {
        if (Character.isHighSurrogate(ch)) {
            return index + 1 == value.length() || !Character.isLowSurrogate(value.charAt(index + 1));
        }
        if (Character.isLowSurrogate(ch)) {
            return index == 0 || !Character.isHighSurrogate(value.charAt(index - 1));
        }
        return ch < 0x20 || ch >= 0x7f && ch <= 0x9f || ch == 0x2028 || ch == 0x2029;
    }

    record View(String text, boolean truncated) {
    }

    private record Frame(Iterator<Node> children, boolean function) {
    }
}
