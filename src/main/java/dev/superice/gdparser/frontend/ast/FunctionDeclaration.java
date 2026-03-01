package dev.superice.gdparser.frontend.ast;

import org.jetbrains.annotations.Nullable;

import java.util.List;

/// func declaration.
public record FunctionDeclaration(
        String name,
        List<Parameter> parameters,
        @Nullable TypeRef returnType,
        Block body,
        Range range
) implements Statement {
}
