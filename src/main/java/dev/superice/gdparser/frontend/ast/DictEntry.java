package dev.superice.gdparser.frontend.ast;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;

/// Dictionary key/value entry used by both dictionary literals and dictionary match patterns.
///
/// @param key key expression (always non-null; uses an error/missing marker when damaged)
/// @param value value or sub-pattern expression; `null` ONLY for key-only dictionary match
///              patterns without a `:` value slot (ordinary dictionary literals must always
///              provide a non-null value)
/// @param range source byte/point span of the entry
public record DictEntry(@NotNull Expression key, @Nullable Expression value, @NotNull Range range) implements Node {

    public DictEntry {
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(range, "range must not be null");
    }

    @Override
    public List<Node> getChildren() {
        return NodeChildren.builder().add(key).add(value).toList();
    }
}
