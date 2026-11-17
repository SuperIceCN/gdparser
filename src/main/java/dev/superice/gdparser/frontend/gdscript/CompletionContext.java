package dev.superice.gdparser.frontend.gdscript;

import dev.superice.gdparser.frontend.ast.Range;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/// Completion context at a UTF-8 cursor byte offset, without parser-specific node identities.
/// At a name/punctuation boundary the name ending at the cursor wins. String and comment
/// interiors suppress completion; a cursor before their first byte remains in code context.
///
/// @param kind the cursor's syntactic context
/// @param replaceableRange the entire name under the cursor, including any suffix after it;
/// a missing name or argument uses a zero-width range
/// @param receiverRange the source fragment to the left of the member-access dot, present
/// exactly when kind is `MEMBER_ACCESS`
public record CompletionContext(Kind kind, Range replaceableRange, @Nullable Range receiverRange) {

    public CompletionContext {
        Objects.requireNonNull(kind, "kind must not be null");
        Objects.requireNonNull(replaceableRange, "replaceableRange must not be null");
        if (kind == Kind.MEMBER_ACCESS && receiverRange == null) {
            throw new IllegalArgumentException("receiverRange must be present for MEMBER_ACCESS");
        }
        if (kind != Kind.MEMBER_ACCESS && receiverRange != null) {
            throw new IllegalArgumentException("receiverRange must be absent unless MEMBER_ACCESS");
        }
    }

    public enum Kind {
        MEMBER_ACCESS,
        IDENTIFIER,
        TYPE_POSITION,
        CALL_ARGUMENT,
        UNKNOWN
    }
}
