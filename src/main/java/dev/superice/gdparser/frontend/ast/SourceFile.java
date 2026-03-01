package dev.superice.gdparser.frontend.ast;

import java.util.List;

/// Root AST node for one GDScript source file.
public record SourceFile(List<Statement> statements, Range range) {
}
