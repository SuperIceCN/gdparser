package dev.superice.gdparser.infra.treesitter;

import dev.superice.gdparser.frontend.ast.Range;
import dev.superice.gdparser.frontend.gdscript.GdScriptParserFacade;
import org.jetbrains.annotations.Nullable;

/// Completion context at a cursor byte offset, derived from the immutable CST snapshot.
///
/// `replaceableRange` is the full range of the identifier under the cursor, spanning both the
/// part before and the part after the cursor, so accepting a candidate replaces the whole token.
/// Callers derive the typed prefix for filtering by slicing from `replaceableRange.startByte()`
/// up to the cursor offset themselves; no separate prefix range is exposed. When nothing typed
/// is under the cursor, the range is zero-width at the cursor.
///
/// `receiverRange` is only present for `MEMBER_ACCESS` and covers the fragment on the left side
/// of the dot as a byte range; no AST node is exposed for it.
///
/// @deprecated Use the context returned by [GdScriptParserFacade#parseCompletionContext(String, long)].
@Deprecated
public record CompletionContext(Kind kind, Range replaceableRange, @Nullable Range receiverRange) {

    public CompletionContext {
        if (kind == Kind.MEMBER_ACCESS && receiverRange == null) {
            throw new IllegalArgumentException("receiverRange must be present for MEMBER_ACCESS");
        }
        if (kind != Kind.MEMBER_ACCESS && receiverRange != null) {
            throw new IllegalArgumentException("receiverRange must be absent unless MEMBER_ACCESS");
        }
    }

    public enum Kind {
        /// Cursor in a member name position after `.`, e.g. `obj.pa|` or the incomplete `obj.|`.
        MEMBER_ACCESS,
        /// Cursor on a plain identifier, e.g. a statement-level or argument expression prefix.
        IDENTIFIER,
        /// Cursor in a type position, e.g. `var x: Fla|` or `x as Fla|`.
        TYPE_POSITION,
        /// Cursor where a call argument is expected, e.g. `node.f(|` or after a comma.
        CALL_ARGUMENT,
        /// No completable context could be determined.
        UNKNOWN
    }
}
