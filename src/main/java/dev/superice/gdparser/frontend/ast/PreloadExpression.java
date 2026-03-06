package dev.superice.gdparser.frontend.ast;

/// `preload(...)` lowered as a dedicated expression.
public record PreloadExpression(Expression path, Range range) implements Expression {
}
