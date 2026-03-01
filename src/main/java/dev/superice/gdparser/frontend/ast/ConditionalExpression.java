package dev.superice.gdparser.frontend.ast;

/// ternary-like conditional expression.
public record ConditionalExpression(
        Expression condition,
        Expression left,
        Expression right,
        Range range
) implements Expression {
}
