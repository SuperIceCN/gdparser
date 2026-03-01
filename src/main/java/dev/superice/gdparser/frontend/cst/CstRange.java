package dev.superice.gdparser.frontend.cst;

/// Byte and point span for a CST node.
public record CstRange(int startByte, int endByte, CstPoint startPoint, CstPoint endPoint) {
}
