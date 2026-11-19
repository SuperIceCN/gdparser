package dev.superice.gdparser.frontend.gdscript;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/// A single token produced by `GdTokenizer`, mirroring Godot's `GDScriptTokenizer::Token`.
///
/// Byte offsets are the authoritative position carrier: they always refer to physical
/// UTF-8 positions and are later converted to `Range` via `SourceTextIndex`. The 1-based
/// line/column fields track physical lines and consumed Unicode code points (tab counts
/// one; CRLF advances line once); EOF-synthesized layout tokens (`NEWLINE`, `DEDENT`,
/// `EOF`) anchor as zero-width spans at the physical EOF coordinate `(eofLine, eofColumn)`.
///
/// @param type token type
/// @param source raw source slice of the token; empty for `ERROR`, `NEWLINE` and `EOF`
/// tokens (an empty `source` does NOT imply a zero-width byte range). For `ANNOTATION`
/// it includes the leading `@`, aligned with Godot's token source/literal
/// @param literalKind literal classification, meaningful only for `LITERAL` tokens
/// (`NONE` otherwise); decoded literal values are intentionally not computed because the
/// AST contract keeps the source text
/// @param errorMessage message for `ERROR` tokens (aligned with Godot's ERROR literal
/// carrier), null for all other types
/// @param startByte inclusive UTF-8 byte offset of the token start
/// @param endByte exclusive UTF-8 byte offset of the token end
/// @param startLine 1-based start line (Godot diagnostic coordinate)
/// @param startColumn 1-based start column in characters
/// @param endLine 1-based end line (Godot diagnostic coordinate)
/// @param endColumn 1-based end column in characters
public record GdToken(
        @NotNull GdTokenType type,
        @NotNull String source,
        @NotNull GdLiteralKind literalKind,
        @Nullable String errorMessage,
        int startByte,
        int endByte,
        int startLine,
        int startColumn,
        int endLine,
        int endColumn
) {
    public GdToken {
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(source, "source must not be null");
        Objects.requireNonNull(literalKind, "literalKind must not be null");
        if (type == GdTokenType.ERROR && errorMessage == null) {
            throw new IllegalArgumentException("ERROR tokens must carry an errorMessage");
        }
    }

    /// Whether this token is an `ERROR` token. Scanning continues after errors; the
    /// message is available via `errorMessage()`.
    public boolean isError() {
        return type == GdTokenType.ERROR;
    }

    @Override
    public @NotNull String toString() {
        var builder = new StringBuilder(type.name());
        if (type == GdTokenType.ERROR) {
            builder.append("(\"").append(errorMessage).append("\")");
        } else if (!source.isEmpty()) {
            builder.append("(\"").append(source).append("\")");
        }
        builder.append('[').append(startByte).append(',').append(endByte).append(')');
        builder.append('@').append(startLine).append(':').append(startColumn)
                .append('-').append(endLine).append(':').append(endColumn);
        return builder.toString();
    }
}
