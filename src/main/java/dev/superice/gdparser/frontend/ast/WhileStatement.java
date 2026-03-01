package dev.superice.gdparser.frontend.ast;

/// while statement.
public record WhileStatement(Expression condition, Block body, Range range) implements Statement {
}
