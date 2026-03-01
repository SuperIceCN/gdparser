package dev.superice.gdparser.frontend.ast;

/// fallback expression for unsupported CST node.
public record UnknownExpression(String nodeType, String sourceText, Range range) implements Expression {
}
