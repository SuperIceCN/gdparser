package dev.superice.gdparser.frontend.ast;

/// extends declaration.
public record ExtendsStatement(String target, Range range) implements Statement {
}
