package dev.superice.gdparser.frontend.ast;

import org.jetbrains.annotations.Nullable;

import java.util.List;

/// lambda expression.
public record LambdaExpression(
        @Nullable String name,
        List<Parameter> parameters,
        @Nullable TypeRef returnType,
        Block body,
        Range range
) implements Expression {
}
