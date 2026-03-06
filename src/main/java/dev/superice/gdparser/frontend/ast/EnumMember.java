package dev.superice.gdparser.frontend.ast;

import org.jetbrains.annotations.Nullable;

/// One enum entry.
public record EnumMember(String name, @Nullable Expression value, Range range) implements Node {
}
