package dev.superice.gdparser.frontend.ast;

import java.util.List;

/// subscript expression.
public record SubscriptExpression(Expression base, List<Expression> arguments, Range range) implements Expression {
}
