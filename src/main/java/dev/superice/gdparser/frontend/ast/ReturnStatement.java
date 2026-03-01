package dev.superice.gdparser.frontend.ast;

import org.jetbrains.annotations.Nullable;

/// return statement.
public record ReturnStatement(@Nullable Expression value, Range range) implements Statement {
}
