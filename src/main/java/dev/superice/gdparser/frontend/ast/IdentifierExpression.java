package dev.superice.gdparser.frontend.ast;

/// identifier reference expression.
public record IdentifierExpression(String name, Range range) implements Expression {
}
