package dev.superice.gdparser.frontend.ast;

import org.jetbrains.annotations.Nullable;

/// var/const declaration.
public record VariableDeclaration(
        DeclarationKind kind,
        String name,
        @Nullable TypeRef type,
        @Nullable Expression value,
        boolean isStatic,
        String sourceNodeType,
        Range range
) implements Statement {
}
