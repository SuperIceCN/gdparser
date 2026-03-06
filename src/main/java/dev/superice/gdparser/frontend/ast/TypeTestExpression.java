package dev.superice.gdparser.frontend.ast;

/// `value is Type` / `value is not Type` expression.
public record TypeTestExpression(Expression value, TypeRef targetType, boolean negated,
                                 Range range) implements Expression {
}
