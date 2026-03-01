package dev.superice.gdparser.frontend.ast;

import java.util.List;

/// array expression.
public record ArrayExpression(List<Expression> elements, boolean openEnded, Range range) implements Expression {
}
