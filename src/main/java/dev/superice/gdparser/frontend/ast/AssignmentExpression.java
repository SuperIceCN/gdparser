package dev.superice.gdparser.frontend.ast;

/// assignment expression.
public record AssignmentExpression(
        String operator,
        Expression left,
        Expression right,
        Range range
) implements Expression {
}
