package dev.superice.gdparser.infra.treesitter;

import dev.superice.gdparser.frontend.ast.Range;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/// Completion context classification at a cursor offset. In the sources below, `|` marks the
/// cursor position and is stripped before parsing.
@SuppressWarnings("deprecation") // The legacy backend remains a regression reference.
class CompletionContextTest {

    private static GdParserFacade parserFacade;

    @BeforeAll
    static void setUpFacade() {
        parserFacade = GdParserFacade.withDefaultLanguage();
    }

    @Test
    void completeMemberAccessPrefixShouldResolveReceiverAndReplaceableRanges() {
        var context = contextAt("func f():\n\tnode.pa|");

        assertEquals(CompletionContext.Kind.MEMBER_ACCESS, context.kind());
        assertRangeText("pa", context.replaceableRange(), "func f():\n\tnode.pa");
        assertNotNull(context.receiverRange());
        assertRangeText("node", context.receiverRange(), "func f():\n\tnode.pa");
    }

    @Test
    void completeMemberAccessShouldResolveThroughNormalAttributeCst() {
        // Syntactically complete member access with the cursor inside the member name prefix
        // must be classified along the normal attribute CST, not only via error structures.
        var context = contextAt("func f():\n\tvar x = node.pa|r\n\tpass");

        assertEquals(CompletionContext.Kind.MEMBER_ACCESS, context.kind());
        assertRangeText("par", context.replaceableRange(), "func f():\n\tvar x = node.par\n\tpass");
        assertRangeText("node", context.receiverRange(), "func f():\n\tvar x = node.par\n\tpass");
    }

    @Test
    void bareDotAtEndOfFileShouldResolveViaErrorStructure() {
        var context = contextAt("func f():\n\tnode.|");

        assertEquals(CompletionContext.Kind.MEMBER_ACCESS, context.kind());
        assertZeroWidthAtCursor(context.replaceableRange(), 16);
        assertRangeText("node", context.receiverRange(), "func f():\n\tnode.");
    }

    @Test
    void missingMemberPlaceholderShouldResolveViaMissingStructure() {
        var context = contextAt("func f():\n\tvar x = node.| + 1");

        assertEquals(CompletionContext.Kind.MEMBER_ACCESS, context.kind());
        assertZeroWidthAtCursor(context.replaceableRange(), 24);
        assertRangeText("node", context.receiverRange(), "func f():\n\tvar x = node. + 1");
    }

    @Test
    void cursorOnDotBeforeMemberNameShouldCompleteThatMember() {
        var context = contextAt("func f():\n\tnode.|pa");

        assertEquals(CompletionContext.Kind.MEMBER_ACCESS, context.kind());
        assertRangeText("pa", context.replaceableRange(), "func f():\n\tnode.pa");
        assertRangeText("node", context.receiverRange(), "func f():\n\tnode.pa");
    }

    @Test
    void multiStepChainShouldUseWholeLeftFragmentAsReceiver() {
        var context = contextAt("func f():\n\ta.b.c|");

        assertEquals(CompletionContext.Kind.MEMBER_ACCESS, context.kind());
        assertRangeText("c", context.replaceableRange(), "func f():\n\ta.b.c");
        assertRangeText("a.b", context.receiverRange(), "func f():\n\ta.b.c");
    }

    @Test
    void chainBaseIdentifierShouldBePlainIdentifier() {
        var context = contextAt("func f():\n\tno|de.pa");

        assertEquals(CompletionContext.Kind.IDENTIFIER, context.kind());
        assertRangeText("node", context.replaceableRange(), "func f():\n\tnode.pa");
        assertNull(context.receiverRange());
    }

    @Test
    void methodNameInsideCallStepShouldBeMemberAccess() {
        var context = contextAt("func f():\n\tnode.met|hod(1)");

        assertEquals(CompletionContext.Kind.MEMBER_ACCESS, context.kind());
        assertRangeText("method", context.replaceableRange(), "func f():\n\tnode.method(1)");
        assertRangeText("node", context.receiverRange(), "func f():\n\tnode.method(1)");
    }

    @Test
    void cursorOnDotBeforeCallStepShouldCompleteMethodName() {
        var context = contextAt("func f():\n\tnode.|method(1)");

        assertEquals(CompletionContext.Kind.MEMBER_ACCESS, context.kind());
        assertRangeText("method", context.replaceableRange(), "func f():\n\tnode.method(1)");
        assertRangeText("node", context.receiverRange(), "func f():\n\tnode.method(1)");
    }

    @Test
    void chainBeforeCallStepShouldUseWholeLeftFragmentAsReceiver() {
        var context = contextAt("func f():\n\ta.b.met|hod(1)");

        assertEquals(CompletionContext.Kind.MEMBER_ACCESS, context.kind());
        assertRangeText("method", context.replaceableRange(), "func f():\n\ta.b.method(1)");
        assertRangeText("a.b", context.receiverRange(), "func f():\n\ta.b.method(1)");
    }

    @Test
    void statementPrefixShouldBeIdentifier() {
        var context = contextAt("func f():\n\tpri|");

        assertEquals(CompletionContext.Kind.IDENTIFIER, context.kind());
        assertRangeText("pri", context.replaceableRange(), "func f():\n\tpri");
    }

    @Test
    void openParenAtEndOfFileShouldBeCallArgument() {
        var context = contextAt("func f():\n\tnode.f(|");

        assertEquals(CompletionContext.Kind.CALL_ARGUMENT, context.kind());
        assertZeroWidthAtCursor(context.replaceableRange(), 18);
        assertNull(context.receiverRange());
    }

    @Test
    void emptyParensShouldBeCallArgument() {
        var context = contextAt("func f():\n\tnode.f(|)");

        assertEquals(CompletionContext.Kind.CALL_ARGUMENT, context.kind());
        assertZeroWidthAtCursor(context.replaceableRange(), 18);
    }

    @Test
    void afterCommaInArgumentsShouldBeCallArgument() {
        var context = contextAt("func f():\n\tnode.f(1, |)");

        assertEquals(CompletionContext.Kind.CALL_ARGUMENT, context.kind());
        assertZeroWidthAtCursor(context.replaceableRange(), 21);
    }

    @Test
    void variableTypePrefixShouldBeTypePosition() {
        var context = contextAt("func f():\n\tvar x: Fla|");

        assertEquals(CompletionContext.Kind.TYPE_POSITION, context.kind());
        assertRangeText("Fla", context.replaceableRange(), "func f():\n\tvar x: Fla");
        assertNull(context.receiverRange());
    }

    @Test
    void extendsTypePrefixShouldBeTypePosition() {
        var context = contextAt("extends Fla|");

        assertEquals(CompletionContext.Kind.TYPE_POSITION, context.kind());
        assertRangeText("Fla", context.replaceableRange(), "extends Fla");
    }

    @Test
    void castTargetPrefixShouldBeTypePosition() {
        var context = contextAt("func f():\n\tx as Fla|");

        assertEquals(CompletionContext.Kind.TYPE_POSITION, context.kind());
        assertRangeText("Fla", context.replaceableRange(), "func f():\n\tx as Fla");
    }

    @Test
    void stringLiteralContentShouldBeUnknown() {
        var context = contextAt("func f():\n\tvar s = \"hel|lo\"");

        assertEquals(CompletionContext.Kind.UNKNOWN, context.kind());
    }

    @Test
    void whitespaceOnlyPositionShouldBeUnknown() {
        var context = contextAt("func f():\n\t|");

        assertEquals(CompletionContext.Kind.UNKNOWN, context.kind());
    }

    @Test
    void emptySourceShouldBeUnknown() {
        var context = contextAt("|");

        assertEquals(CompletionContext.Kind.UNKNOWN, context.kind());
        assertZeroWidthAtCursor(context.replaceableRange(), 0);
    }

    @Test
    void byteOffsetShouldBeClampedIntoSourceRange() {
        var source = "func f():\n\tnode.pa";
        var beforeStart = parserFacade.parseCompletionContext(source, -5);
        var pastEnd = parserFacade.parseCompletionContext(source, 10_000);

        assertZeroWidthAtCursor(beforeStart.replaceableRange(), 0);
        assertEquals(CompletionContext.Kind.MEMBER_ACCESS, pastEnd.kind(),
                "Clamped to end of source, the cursor lands on the trailing member prefix");
        assertRangeText("pa", pastEnd.replaceableRange(), source);
    }

    private static CompletionContext contextAt(String markedSource) {
        var cursor = markedSource.indexOf('|');
        if (cursor < 0) {
            throw new IllegalArgumentException("Source must contain a '|' cursor marker");
        }
        var source = markedSource.substring(0, cursor) + markedSource.substring(cursor + 1);
        return parserFacade.parseCompletionContext(source, cursor);
    }

    private static void assertRangeText(String expected, Range range, String source) {
        assertEquals(expected, source.substring(range.startByte(), range.endByte()),
                () -> "Range [" + range.startByte() + "," + range.endByte() + "] should cover <" + expected + ">");
    }

    private static void assertZeroWidthAtCursor(Range range, int cursor) {
        assertEquals(cursor, range.startByte());
        assertEquals(cursor, range.endByte());
    }
}
