package dev.superice.gdparser.frontend.ast;

import org.jetbrains.annotations.Nullable;

/// `#region` / `#endregion` directive.
public record RegionDirectiveStatement(String directive, @Nullable String label, Range range) implements Statement {
}
