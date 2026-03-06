package dev.superice.gdparser.frontend.ast;

import org.jetbrains.annotations.Nullable;

import java.util.List;

/// `_init` constructor declaration lowered from `constructor_definition`.
public record ConstructorDeclaration(
        List<Parameter> parameters,
        List<Expression> baseArguments,
        @Nullable TypeRef returnType,
        Block body,
        Range range
) implements Statement {
}
