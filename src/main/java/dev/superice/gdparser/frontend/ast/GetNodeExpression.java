package dev.superice.gdparser.frontend.ast;

/// `$...` / `%...` shorthand get-node expression.
public record GetNodeExpression(String sourceText, Range range) implements Expression {
}
