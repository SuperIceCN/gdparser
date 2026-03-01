package dev.superice.gdparser.frontend.ast;

/// binary operation expression.
public record BinaryExpression(String operator, Expression left, Expression right, Range range) implements Expression {
}
