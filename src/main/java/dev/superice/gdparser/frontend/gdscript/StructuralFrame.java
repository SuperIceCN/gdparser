package dev.superice.gdparser.frontend.gdscript;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/// Structural roles tracked on the parser's explicit delimiter and block stack.
enum StructuralRole {
    CLASS_BODY,
    SUITE,
    MATCH_BODY,
    LAMBDA,
    GROUPING,
    ARRAY,
    DICTIONARY,
    CALL,
    SUBSCRIPT,
    ANNOTATION,
    PRELOAD,
    ASSERT,
    PARAMETER_LIST,
    TYPE_BRACKET,
    MATCH_ARRAY_PATTERN,
    MATCH_DICTIONARY_PATTERN,
    ENUM
}

/// Element/separator expectations inside delimiter-enclosed constructs.
enum SeparatorPhase {
    NONE,
    EXPECT_ELEMENT,
    EXPECT_SEPARATOR_OR_CLOSER,
    EXPECT_VALUE
}

/// Explicit structural frame representing an open delimiter construct, lambda boundary, suite, or
/// class body on {@link GdScriptParser}'s structural stack. Carries closer expectations, layout
/// ownership, indentation baseline, separator state, and local synchronization ownership.
final class StructuralFrame {

    final @NotNull StructuralRole role;
    final @Nullable GdTokenType openerType;
    final @Nullable GdTokenType expectedCloser;
    final int openerStartByte;
    final int indentBaseline;
    final boolean effectiveMultiline;
    final boolean ownsMultilineFrame;
    final boolean recordCommentSpan;
    final @NotNull String constructDescription;
    final @Nullable String missingCloserMessage;
    final @Nullable String missingCloserCode;
    final @Nullable String mismatchedCloserCode;
    @NotNull SeparatorPhase separatorPhase = SeparatorPhase.NONE;
    boolean allowsTypeComma;
    boolean parsingDictionaryKey;
    /// A separator was missing or inconsistent, or the list skipped an invalid item tail.
    boolean damagedListSyntax;
    /// A real separator closed an item with trailing lexical damage.
    boolean lexicalDamageBeforeListSeparator;
    boolean pendingSync;
    boolean settled;
    byte unclosedParamListCache = -1;

    private StructuralFrame(
            @NotNull StructuralRole role,
            @Nullable GdTokenType openerType,
            @Nullable GdTokenType expectedCloser,
            int openerStartByte,
            int indentBaseline,
            boolean effectiveMultiline,
            boolean ownsMultilineFrame,
            boolean recordCommentSpan,
            @NotNull String constructDescription,
            @Nullable String missingCloserMessage,
            @Nullable String missingCloserCode,
            @Nullable String mismatchedCloserCode
    ) {
        this.role = role;
        this.openerType = openerType;
        this.expectedCloser = expectedCloser;
        this.openerStartByte = openerStartByte;
        this.indentBaseline = indentBaseline;
        this.effectiveMultiline = effectiveMultiline;
        this.ownsMultilineFrame = ownsMultilineFrame;
        this.recordCommentSpan = recordCommentSpan;
        this.constructDescription = constructDescription;
        this.missingCloserMessage = missingCloserMessage;
        this.missingCloserCode = missingCloserCode;
        this.mismatchedCloserCode = mismatchedCloserCode;
    }

    static @NotNull StructuralFrame nonBracketed(
            @NotNull StructuralRole role,
            int startByte,
            int indentBaseline,
            boolean effectiveMultiline,
            boolean ownsMultilineFrame,
            @NotNull String constructDescription
    ) {
        return new StructuralFrame(
                role,
                null,
                null,
                startByte,
                indentBaseline,
                effectiveMultiline,
                ownsMultilineFrame,
                false,
                constructDescription,
                null,
                null,
                null
        );
    }

    static @NotNull StructuralFrame bracketed(
            @NotNull StructuralRole role,
            @NotNull GdTokenType openerType,
            @NotNull GdTokenType expectedCloser,
            int openerStartByte,
            int indentBaseline,
            boolean effectiveMultiline,
            boolean ownsMultilineFrame,
            boolean recordCommentSpan,
            @NotNull String constructDescription,
            @NotNull String missingCloserMessage,
            @NotNull String missingCloserCode,
            @NotNull String mismatchedCloserCode
    ) {
        return new StructuralFrame(
                role,
                openerType,
                expectedCloser,
                openerStartByte,
                indentBaseline,
                effectiveMultiline,
                ownsMultilineFrame,
                recordCommentSpan,
                constructDescription,
                missingCloserMessage,
                missingCloserCode,
                mismatchedCloserCode
        );
    }

    boolean isBracketed() {
        return expectedCloser != null;
    }
}
