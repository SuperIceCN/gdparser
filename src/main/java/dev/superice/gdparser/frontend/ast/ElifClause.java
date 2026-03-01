package dev.superice.gdparser.frontend.ast;

/// elif branch of an if statement.
public record ElifClause(Expression condition, Block body, Range range) {
}
