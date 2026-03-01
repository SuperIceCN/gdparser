package dev.superice.gdparser.frontend.ast;

import java.util.List;

/// dictionary expression.
public record DictionaryExpression(List<DictEntry> entries, boolean openEnded, Range range) implements Expression {
}
