package dev.superice.gdparser.frontend.gdscript;

import dev.superice.gdparser.frontend.ast.Point;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Unit tests for `SourceTextIndex`, the UTF-8 byte offset <-> (0-based row, byte column)
/// converter that anchors `Range` computation to the physical byte cursor.
class SourceTextIndexTest {

    @Test
    void asciiLineStartsAndColumns() {
        var index = SourceTextIndex.of("ab\ncd\n");

        assertEquals(6, index.utf8Length());
        assertEquals(3, index.lineCount());
        assertEquals(0, index.lineStartByte(0));
        assertEquals(3, index.lineStartByte(1));
        // A trailing newline introduces the (empty) last row's start at EOF, matching
        // tree-sitter's end-point convention for newline-terminated files.
        assertEquals(6, index.lineStartByte(2));

        // The '\n' byte belongs to the row it terminates.
        assertEquals(new Point(0, 2), index.pointAt(2));
        assertEquals(new Point(1, 0), index.pointAt(3));
        assertEquals(new Point(1, 2), index.pointAt(5));
        // EOF offset maps to the start of the empty last row.
        assertEquals(new Point(2, 0), index.pointAt(6));
    }

    @Test
    void multiByteCharactersCountBytesNotChars() {
        // 3 + 3 + 1 bytes on row 0, then 'x' on row 1.
        var index = SourceTextIndex.of("变量\nx");

        assertEquals(8, index.utf8Length());
        assertEquals(2, index.lineCount());
        assertEquals(7, index.lineStartByte(1));
        // Byte columns: the second Chinese character starts at byte 3.
        assertEquals(new Point(0, 3), index.pointAt(3));
        assertEquals(new Point(1, 0), index.pointAt(7));
        assertEquals(new Point(1, 1), index.pointAt(8));
    }

    @Test
    void emojiUsesFourUtf8Bytes() {
        var source = "\uD83D\uDE00\nb"; // U+1F600, one code point, two UTF-16 units, four UTF-8 bytes.
        assertEquals(6, source.getBytes(StandardCharsets.UTF_8).length, "sanity: emoji + newline + 'b' is 6 UTF-8 bytes");

        var index = SourceTextIndex.of(source);
        assertEquals(6, index.utf8Length());
        assertEquals(5, index.lineStartByte(1));
        assertEquals(new Point(0, 4), index.pointAt(4));
        assertEquals(new Point(1, 0), index.pointAt(5));
        assertEquals(new Point(1, 1), index.pointAt(6));
    }

    @Test
    void crlfKeepsCarriageReturnOnPreviousRow() {
        var index = SourceTextIndex.of("a\r\nb");

        assertEquals(4, index.utf8Length());
        assertEquals(3, index.lineStartByte(1));
        assertEquals(new Point(0, 1), index.pointAt(1)); // '\r'
        assertEquals(new Point(0, 2), index.pointAt(2)); // '\n'
        assertEquals(new Point(1, 0), index.pointAt(3)); // 'b'
    }

    @Test
    void tabCountsAsOneByteColumn() {
        var index = SourceTextIndex.of("a\tb");

        assertEquals(1, index.lineCount());
        assertEquals(new Point(0, 2), index.pointAt(2));
    }

    @Test
    void eofAnchorsToLastRowWithoutTrailingNewline() {
        var index = SourceTextIndex.of("ab\ncd");

        assertEquals(5, index.utf8Length());
        assertEquals(2, index.lineCount());
        assertEquals(new Point(1, 2), index.pointAt(5));
    }

    @Test
    void eofAfterTrailingComment() {
        var index = SourceTextIndex.of("var a = 1 # tail");

        assertEquals(16, index.utf8Length());
        assertEquals(1, index.lineCount());
        assertEquals(new Point(0, 16), index.pointAt(16));
    }

    @Test
    void eofAfterMultilineString() {
        var source = "var s = '''\nabc'''";
        var index = SourceTextIndex.of(source);

        assertEquals(source.getBytes(StandardCharsets.UTF_8).length, index.utf8Length());
        assertEquals(2, index.lineCount());
        assertEquals(new Point(1, 6), index.pointAt(index.utf8Length()));
    }

    @Test
    void longCrlfSourceWithStrayCrKeepsPhysicalUtf8Ranges() {
        var source = "func f():\r\n\tvar 名 = \"\"\"😀\r\n界\"\"\"\r\n\tvar " + "界".repeat(4096) + " = 7\r§";
        var index = SourceTextIndex.of(source);
        var literal = source.indexOf('7');
        var error = source.indexOf('§');
        assertEquals(4, index.lineCount());
        assertEquals(GdScriptParserErrorRecoveryTest.utf8Range(source, literal, error + 1),
                index.rangeOf(source.substring(0, literal).getBytes(StandardCharsets.UTF_8).length,
                        source.getBytes(StandardCharsets.UTF_8).length));
        assertEquals(new Point(3, 12_296), index.pointAt(index.utf8Length() - 4));
        assertEquals(new Point(3, 12_300), index.pointAt(index.utf8Length()));
    }

    @Test
    void rangeOfCombinesBothEndpoints() {
        var index = SourceTextIndex.of("ab\ncd");

        var range = index.rangeOf(1, 4);
        assertEquals(1, range.startByte());
        assertEquals(4, range.endByte());
        assertEquals(new Point(0, 1), range.startPoint());
        assertEquals(new Point(1, 1), range.endPoint());
    }

    @Test
    void outOfRangeOffsetsAreRejected() {
        var index = SourceTextIndex.of("ab");

        assertThrows(IllegalArgumentException.class, () -> index.pointAt(-1));
        assertThrows(IllegalArgumentException.class, () -> index.pointAt(3));
        assertThrows(IllegalArgumentException.class, () -> index.rangeOf(2, 1));
        assertThrows(IllegalArgumentException.class, () -> index.lineStartByte(1));
        assertThrows(IllegalArgumentException.class, () -> index.lineStartByte(-1));
    }

    @Test
    void emptySource() {
        var index = SourceTextIndex.of("");

        assertEquals(0, index.utf8Length());
        assertEquals(1, index.lineCount());
        assertEquals(new Point(0, 0), index.pointAt(0));
    }

    @Test
    void unpairedSurrogatesMatchJdkUtf8ReplacementEncoding() {
        // The tree-sitter path parses `source.getBytes(UTF_8)`, where an unpaired
        // surrogate is replaced with a single '?' byte; the index must follow.
        var source = "a\uD800b\n";
        assertEquals(4, source.getBytes(StandardCharsets.UTF_8).length, "sanity: JDK replacement encoding");

        var index = SourceTextIndex.of(source);
        assertEquals(4, index.utf8Length());
        assertEquals(new Point(0, 2), index.pointAt(2));
        assertEquals(new Point(0, 3), index.pointAt(3));
        assertEquals(new Point(1, 0), index.pointAt(4));
    }
}
