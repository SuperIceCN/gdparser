package dev.superice.gdparser.frontend.gdscript;

import dev.superice.gdparser.frontend.ast.Range;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/// Syntactic completion context at a UTF-8 cursor byte offset, independent of AST identities.
/// At a name/punctuation boundary the name ending at the cursor wins. String and comment
/// interiors suppress completion; a cursor before their first byte remains in code context.
///
/// @param kind the cursor's syntactic context
/// @param replaceableRange the entire name under the cursor, including any suffix after it;
/// an empty slot uses a zero-width range
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

    /// Classifies the slot without prescribing how a client supplies candidates.
    public enum Kind {
        /// A member name after a receiver and dot.
        MEMBER_ACCESS,
        /// A bare identifier or an empty value expression.
        IDENTIFIER,
        /// A type name or an empty type slot.
        TYPE_POSITION,
        /// An argument position inside a call expression.
        CALL_ARGUMENT,
        /// A new parameter name in a function or signal signature.
        PARAMETER_NAME,
        /// The empty body on the same logical line as a function or lambda header.
        SUITE_START,
        /// The `in` keyword expected immediately after an infix `not`.
        EXPECTED_KEYWORD,
        /// No supported slot could be inferred at the cursor.
        UNKNOWN
    }
}
