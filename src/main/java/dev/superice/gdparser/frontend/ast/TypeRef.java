package dev.superice.gdparser.frontend.ast;

/// textual type reference.
public record TypeRef(String sourceText, Range range) implements Node {
}
