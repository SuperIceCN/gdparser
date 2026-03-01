package dev.superice.gdparser.frontend.ast;

import java.util.List;

/// call expression.
public record CallExpression(Expression callee, List<Expression> arguments, Range range) implements Expression {
}
