package dev.superice.gdparser.frontend.ast;

/// literal expression preserving source text and literal kind.
public record LiteralExpression(String kind, String sourceText, Range range) implements Expression {
}
