package dev.superice.gdparser.frontend.ast;

import org.jetbrains.annotations.Nullable;

import java.util.List;

/// if / elif / else statement.
public record IfStatement(
        Expression condition,
        Block body,
        List<ElifClause> elifClauses,
        @Nullable Block elseBody,
        Range range
) implements Statement {
}
