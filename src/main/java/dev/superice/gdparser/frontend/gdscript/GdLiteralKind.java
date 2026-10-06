package dev.superice.gdparser.frontend.gdscript;

import org.jetbrains.annotations.Nullable;

/// Literal classification carried by `GdTokenType.LITERAL` tokens.
///
/// `astKind` values are exactly the `LiteralExpression.kind` contract vocabulary
/// ("integer"/"float"/"string"/"string_name"/"node_path"/"true"/"false"/"null"), so the
/// parser can forward them without a mapping layer. `NONE` marks non-literal tokens and
/// has no AST kind.
public enum GdLiteralKind {
    NONE(null),
    INTEGER("integer"),
    FLOAT("float"),
    STRING("string"),
    STRING_NAME("string_name"),
    NODE_PATH("node_path"),
    TRUE("true"),
    FALSE("false"),
    NULL("null");

    private final @Nullable String astKind;

    GdLiteralKind(@Nullable String astKind) {
        this.astKind = astKind;
    }

    /// `LiteralExpression.kind` vocabulary value, or null for `NONE`.
    public @Nullable String astKind() {
        return astKind;
    }
}
