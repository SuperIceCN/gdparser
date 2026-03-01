package dev.superice.gdparser.frontend.ast;

/// unary operation expression.
public record UnaryExpression(String operator, Expression operand, Range range) implements Expression {
}
