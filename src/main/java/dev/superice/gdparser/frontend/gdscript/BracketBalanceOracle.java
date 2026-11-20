package dev.superice.gdparser.frontend.gdscript;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/// Lazy single-pass lexical bracket-balance oracle used when multiline bracketed constructs
/// encounter cross-line ambiguity.
///
/// Scans the source code points at most once per parse using the same string, raw-string,
/// triple-string, comment, and backslash-continuation rules as {@link GdTokenizer}, recording for
/// every opener `(`, `[`, `{` whether it is genuinely matched, closed via single-layer replacement,
/// or unclosed, together with the earliest structural stop byte seen while unclosed.
final class BracketBalanceOracle {

    enum OpenerStatus {
        MATCHED,
        REPLACED,
        MISSING
    }

    /// @param openerByte UTF-8 start byte offset of the opener token
    /// @param openerType opener token kind
    /// @param status pairing outcome determined by a single balanced scan
    /// @param pairedCloserByte UTF-8 start byte offset of the paired closer, or `-1` when missing
    /// @param effectiveRecoveryEnd UTF-8 byte offset where an unclosed construct should stop
    record OpenerFact(
            int openerByte,
            GdTokenType openerType,
            OpenerStatus status,
            int pairedCloserByte,
            int effectiveRecoveryEnd
    ) {
    }

    private static final class OpenEntry {
        final int openerByte;
        final GdTokenType openerType;
        final char expectedCloser;
        int firstStopByte = -1;

        OpenEntry(int openerByte, GdTokenType openerType, char expectedCloser) {
            this.openerByte = openerByte;
            this.openerType = openerType;
            this.expectedCloser = expectedCloser;
        }
    }

    private final int[] chars;
    private final int[] byteOffsets;
    private final Map<Integer, OpenerFact> factsByOpenerByte = new HashMap<>();
    private int scanCount;

    BracketBalanceOracle(int[] chars, int[] byteOffsets) {
        this.chars = chars;
        this.byteOffsets = byteOffsets;
    }

    int scanCount() {
        return scanCount;
    }

    @Nullable OpenerFact factFor(int openerByte) {
        ensureScanned();
        return factsByOpenerByte.get(openerByte);
    }

    private void ensureScanned() {
        if (scanCount > 0) {
            return;
        }
        scanCount++;

        var stack = new ArrayList<OpenEntry>();
        var parenIndices = new ArrayList<Integer>();
        var bracketIndices = new ArrayList<Integer>();
        var braceIndices = new ArrayList<Integer>();

        var firstTokenOnLine = true;
        var lineContinuation = false;
        var len = chars.length;
        var pos = 0;

        while (pos < len) {
            var ch = chars[pos];

            if (ch == ' ' || ch == '\t' || ch == '\r') {
                pos++;
                continue;
            }
            if (ch == '\n') {
                pos++;
                if (!lineContinuation) {
                    firstTokenOnLine = true;
                }
                continue;
            }
            if (ch == '#') {
                while (pos < len && chars[pos] != '\n') {
                    pos++;
                }
                continue;
            }
            if (ch == '\\') {
                pos++;
                if (pos < len && chars[pos] == '\r') {
                    pos++;
                }
                if (pos < len && chars[pos] == '\n') {
                    pos++;
                    lineContinuation = true;
                }
                continue;
            }

            lineContinuation = false;
            var tokenStart = pos;
            var atLineHead = firstTokenOnLine;
            firstTokenOnLine = false;

            // String literals (regular, triple-quoted, raw, StringName, NodePath).
            if (ch == '"' || ch == '\''
                    || ((ch == 'r' || ch == '&' || ch == '^') && pos + 1 < len
                    && (chars[pos + 1] == '"' || chars[pos + 1] == '\''))) {
                pos = skipString(pos);
                continue;
            }

            // Statement-level @annotation at line start.
            if (ch == '@') {
                if (atLineHead && !stack.isEmpty()) {
                    recordStop(stack, byteOffsets[tokenStart]);
                }
                pos++;
                while (pos < len && isIdentifierContinue(chars[pos])) {
                    pos++;
                }
                continue;
            }

            if (ch == '(' || ch == '[' || ch == '{') {
                var openerType = switch (ch) {
                    case '(' -> GdTokenType.PARENTHESIS_OPEN;
                    case '[' -> GdTokenType.BRACKET_OPEN;
                    default -> GdTokenType.BRACE_OPEN;
                };
                var expectedCloser = switch (ch) {
                    case '(' -> ')';
                    case '[' -> ']';
                    default -> '}';
                };
                var idx = stack.size();
                stack.add(new OpenEntry(byteOffsets[tokenStart], openerType, expectedCloser));
                indexStack(openerType, parenIndices, bracketIndices, braceIndices).add(idx);
                pos++;
                continue;
            }

            if (ch == ')' || ch == ']' || ch == '}') {
                settleCloser((char) ch, byteOffsets[tokenStart], byteOffsets[pos + 1],
                        stack, parenIndices, bracketIndices, braceIndices);
                pos++;
                continue;
            }

            if (isIdentifierStart(ch)) {
                pos++;
                while (pos < len && isIdentifierContinue(chars[pos])) {
                    pos++;
                }
                if (atLineHead && !stack.isEmpty()) {
                    var word = new String(chars, tokenStart, pos - tokenStart);
                    if (isStructuralStopKeyword(word, pos)) {
                        recordStop(stack, byteOffsets[tokenStart]);
                    }
                }
                continue;
            }

            // Numbers and all remaining punctuation advance one code point at a time; none can
            // introduce brackets, comments, strings, or line-head keywords.
            pos++;
        }

        var eofByte = byteOffsets[len];
        while (!stack.isEmpty()) {
            var entry = popTop(stack, parenIndices, bracketIndices, braceIndices);
            var end = entry.firstStopByte >= 0 ? entry.firstStopByte : eofByte;
            factsByOpenerByte.put(entry.openerByte,
                    new OpenerFact(entry.openerByte, entry.openerType, OpenerStatus.MISSING, -1, end));
        }
    }

    private void recordStop(List<OpenEntry> stack, int stopByte) {
        var top = stack.getLast();
        if (top.firstStopByte < 0) {
            top.firstStopByte = stopByte;
        }
    }

    private static void propagateStopDown(List<OpenEntry> stack, int childStopByte) {
        if (childStopByte < 0 || stack.isEmpty()) {
            return;
        }
        var parent = stack.getLast();
        if (parent.firstStopByte < 0 || childStopByte < parent.firstStopByte) {
            parent.firstStopByte = childStopByte;
        }
    }

    private void settleCloser(char closerChar, int closerStartByte, int closerEndByte,
                              List<OpenEntry> stack,
                              List<Integer> parenIndices,
                              List<Integer> bracketIndices,
                              List<Integer> braceIndices) {
        if (stack.isEmpty()) {
            return;
        }
        var top = stack.getLast();
        if (top.expectedCloser == closerChar) {
            var popped = popTop(stack, parenIndices, bracketIndices, braceIndices);
            factsByOpenerByte.put(popped.openerByte,
                    new OpenerFact(popped.openerByte, popped.openerType, OpenerStatus.MATCHED,
                            closerStartByte, closerEndByte));
            propagateStopDown(stack, popped.firstStopByte);
            return;
        }
        var expectedType = openerTypeForCloser(closerChar);
        var expectedStack = indexStack(expectedType, parenIndices, bracketIndices, braceIndices);
        if (!expectedStack.isEmpty()) {
            var matchIdx = expectedStack.getLast();
            while (stack.size() - 1 > matchIdx) {
                var skipped = popTop(stack, parenIndices, bracketIndices, braceIndices);
                var end = skipped.firstStopByte >= 0 && skipped.firstStopByte < closerStartByte
                        ? skipped.firstStopByte
                        : closerStartByte;
                factsByOpenerByte.put(skipped.openerByte,
                        new OpenerFact(skipped.openerByte, skipped.openerType, OpenerStatus.MISSING, -1, end));
                propagateStopDown(stack, skipped.firstStopByte);
            }
            var matched = popTop(stack, parenIndices, bracketIndices, braceIndices);
            factsByOpenerByte.put(matched.openerByte,
                    new OpenerFact(matched.openerByte, matched.openerType, OpenerStatus.MATCHED,
                            closerStartByte, closerEndByte));
            propagateStopDown(stack, matched.firstStopByte);
            return;
        }
        var replaced = popTop(stack, parenIndices, bracketIndices, braceIndices);
        factsByOpenerByte.put(replaced.openerByte,
                new OpenerFact(replaced.openerByte, replaced.openerType, OpenerStatus.REPLACED,
                        closerStartByte, closerEndByte));
        propagateStopDown(stack, replaced.firstStopByte);
    }

    private static OpenEntry popTop(List<OpenEntry> stack,
                                    List<Integer> parenIndices,
                                    List<Integer> bracketIndices,
                                    List<Integer> braceIndices) {
        var entry = stack.removeLast();
        indexStack(entry.openerType, parenIndices, bracketIndices, braceIndices).removeLast();
        return entry;
    }

    private static List<Integer> indexStack(GdTokenType openerType,
                                            List<Integer> parenIndices,
                                            List<Integer> bracketIndices,
                                            List<Integer> braceIndices) {
        return switch (openerType) {
            case PARENTHESIS_OPEN -> parenIndices;
            case BRACKET_OPEN -> bracketIndices;
            case BRACE_OPEN -> braceIndices;
            default -> throw new IllegalStateException("Unexpected opener type: " + openerType);
        };
    }

    private static GdTokenType openerTypeForCloser(char closerChar) {
        return switch (closerChar) {
            case ')' -> GdTokenType.PARENTHESIS_OPEN;
            case ']' -> GdTokenType.BRACKET_OPEN;
            case '}' -> GdTokenType.BRACE_OPEN;
            default -> throw new IllegalStateException("Unexpected closer char: " + closerChar);
        };
    }

    private boolean isStructuralStopKeyword(String word, int afterWordPos) {
        return switch (word) {
            case "class", "class_name", "extends", "enum", "var", "const", "signal",
                 "return", "pass", "break", "continue", "breakpoint",
                 "while", "for", "match", "assert" -> true;
            case "static" -> {
                var nextWord = peekSameLineWord(afterWordPos);
                yield "func".equals(nextWord) || "var".equals(nextWord);
            }
            case "func" -> peekSameLineWord(afterWordPos) != null;
            case "if" -> isStatementHeadIfLine(afterWordPos);
            default -> false;
        };
    }

    private @Nullable String peekSameLineWord(int pos) {
        var len = chars.length;
        while (pos < len && (chars[pos] == ' ' || chars[pos] == '\t' || chars[pos] == '\r')) {
            pos++;
        }
        if (pos >= len || !isIdentifierStart(chars[pos])) {
            return null;
        }
        var start = pos;
        pos++;
        while (pos < len && isIdentifierContinue(chars[pos])) {
            pos++;
        }
        return new String(chars, start, pos - start);
    }

    /// Distinguishes a statement-head `if <cond>:` on the current physical line from a ternary
    /// `if <cond> else <rhs>` at the same bracket depth.
    private boolean isStatementHeadIfLine(int pos) {
        var len = chars.length;
        var depth = 0;
        while (pos < len) {
            var ch = chars[pos];
            if (ch == '\n' || ch == '#') {
                return false;
            }
            if (ch == '\\') {
                pos++;
                if (pos < len && chars[pos] == '\r') {
                    pos++;
                }
                if (pos < len && chars[pos] == '\n') {
                    pos++;
                    continue;
                }
                continue;
            }
            if (ch == '"' || ch == '\''
                    || ((ch == 'r' || ch == '&' || ch == '^') && pos + 1 < len
                    && (chars[pos + 1] == '"' || chars[pos + 1] == '\''))) {
                pos = skipString(pos);
                continue;
            }
            if (ch == '(' || ch == '[' || ch == '{') {
                depth++;
                pos++;
                continue;
            }
            if (ch == ')' || ch == ']' || ch == '}') {
                if (depth == 0) {
                    return false;
                }
                depth--;
                pos++;
                continue;
            }
            if (ch == ':' && depth == 0) {
                return true;
            }
            if (isIdentifierStart(ch)) {
                var start = pos;
                pos++;
                while (pos < len && isIdentifierContinue(chars[pos])) {
                    pos++;
                }
                if (depth == 0 && pos - start == 4
                        && chars[start] == 'e' && chars[start + 1] == 'l'
                        && chars[start + 2] == 's' && chars[start + 3] == 'e') {
                    return false;
                }
                continue;
            }
            pos++;
        }
        return false;
    }

    private int skipString(int pos) {
        var len = chars.length;
        var isRaw = false;
        var ch = chars[pos];
        if (ch == 'r') {
            isRaw = true;
            pos++;
        } else if (ch == '&' || ch == '^') {
            pos++;
        }
        var quote = chars[pos++];
        var isTriple = pos + 1 < len && chars[pos] == quote && chars[pos + 1] == quote;
        if (isTriple) {
            pos += 2;
        }
        while (pos < len) {
            var c = chars[pos];
            if (c == '\\') {
                pos++;
                if (pos >= len) {
                    return pos;
                }
                if (isRaw) {
                    if (chars[pos] == quote || chars[pos] == '\\') {
                        pos++;
                    }
                } else {
                    pos++;
                }
                continue;
            }
            if (c == quote) {
                pos++;
                if (isTriple) {
                    if (pos + 1 < len && chars[pos] == quote && chars[pos + 1] == quote) {
                        pos += 2;
                        return pos;
                    }
                } else {
                    return pos;
                }
                continue;
            }
            pos++;
        }
        return pos;
    }

    private static boolean isIdentifierStart(int c) {
        return c != 0 && (c == '_' || Character.isUnicodeIdentifierStart(c));
    }

    private static boolean isIdentifierContinue(int c) {
        return c != 0 && (c == '_' || Character.isUnicodeIdentifierPart(c));
    }
}
