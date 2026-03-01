package dev.superice.gdparser.frontend.ast;

/// fallback statement for unsupported CST node.
public record UnknownStatement(String nodeType, String sourceText, Range range) implements Statement {
}
