package dev.superice.gdparser.frontend.ast;

/// Byte and point span of an AST node.
public record Range(int startByte, int endByte, Point startPoint, Point endPoint) {
}
