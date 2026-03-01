package dev.superice.gdparser.frontend.ast;

import org.jetbrains.annotations.Nullable;

/// class_name declaration.
public record ClassNameStatement(
        String name,
        @Nullable String extendsTarget,
        @Nullable String iconPath,
        Range range
) implements Statement {
}
