package dev.superice.gdparser.frontend.ast;

import java.util.List;

/// Statement block body.
public record Block(List<Statement> statements, Range range) implements Statement {
}
