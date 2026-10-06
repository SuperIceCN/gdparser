package dev.superice.gdparser.frontend.gdscript;

/// Comment trivia collected by `GdTokenizer`. Comments never enter the token stream
/// (aligned with Godot); they are kept here so the parser can later materialize them as
/// `CommentStatement`/`RegionDirectiveStatement` nodes.
///
/// @param text comment text including the leading `#` (aligned with Godot's TOOLS-build
/// comment recording)
/// @param wholeLine true when the comment starts at the beginning of a line or directly
/// after indentation; false for trailing comments following code on the same line
/// (aligned with Godot's `CommentData.new_line` flag)
/// @param line 1-based line on which the comment starts (comments never span lines)
/// @param startByte inclusive UTF-8 byte offset of the leading `#`
/// @param endByte exclusive UTF-8 byte offset after the last comment character (before
/// the terminating newline or EOF)
public record GdComment(String text, boolean wholeLine, int line, int startByte, int endByte) {
}
