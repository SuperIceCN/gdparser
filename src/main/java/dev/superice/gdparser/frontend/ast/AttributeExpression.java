package dev.superice.gdparser.frontend.ast;

import java.util.List;

/// attribute chain expression.
public record AttributeExpression(Expression base, List<AttributeStep> steps, Range range) implements Expression {
}
