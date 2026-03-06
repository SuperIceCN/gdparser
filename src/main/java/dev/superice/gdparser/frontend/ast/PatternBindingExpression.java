package dev.superice.gdparser.frontend.ast;

/// Match-pattern binding such as `var value`.
public record PatternBindingExpression(String name, Range range) implements Expression {
}
