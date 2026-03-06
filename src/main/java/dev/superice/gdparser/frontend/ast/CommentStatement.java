package dev.superice.gdparser.frontend.ast;

/// Standalone source comment.
public record CommentStatement(String text, Range range) implements Statement {
}
