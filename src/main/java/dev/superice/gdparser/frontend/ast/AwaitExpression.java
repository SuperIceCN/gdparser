package dev.superice.gdparser.frontend.ast;

/// `await <expr>` expression.
public record AwaitExpression(Expression value, Range range) implements Expression {
}
