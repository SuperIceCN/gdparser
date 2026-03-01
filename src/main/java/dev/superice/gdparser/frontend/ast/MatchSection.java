package dev.superice.gdparser.frontend.ast;

import org.jetbrains.annotations.Nullable;

import java.util.List;

/// one pattern section in match statement.
public record MatchSection(
        List<Expression> patterns,
        @Nullable Expression guard,
        Block body,
        Range range
) implements Node {
}
