package dev.superice.gdparser.frontend.ast;

/// dictionary key/value pair.
public record DictEntry(Expression key, Expression value, Range range) implements Node {
}
