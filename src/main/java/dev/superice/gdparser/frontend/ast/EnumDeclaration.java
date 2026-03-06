package dev.superice.gdparser.frontend.ast;

import org.jetbrains.annotations.Nullable;

import java.util.List;

/// `enum` declaration.
public record EnumDeclaration(@Nullable String name, List<EnumMember> members, Range range) implements Statement {
}
