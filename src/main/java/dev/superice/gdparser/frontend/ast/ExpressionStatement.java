package dev.superice.gdparser.frontend.ast;

/// expression statement.
public record ExpressionStatement(Expression expression, Range range) implements Statement {
}
