package dev.superice.gdparser.frontend.gdscript;

import dev.superice.gdparser.frontend.ast.Point;
import dev.superice.gdparser.frontend.ast.Range;

import java.util.ArrayList;

/// UTF-8 byte offset <-> (0-based row, byte column) conversion over a source text.
///
/// The physical byte cursor is the single source of truth for `frontend.ast.Range`:
/// Godot's 1-based token line/column coordinates (including EOF-synthesized positions
/// that may point past the physical end) never participate in the conversion. A line
/// starts right after a `\n`; `\r` in CRLF belongs to the previous line, and a lone `\r`
/// is treated as an ordinary character (the tokenizer reports it as an error separately).
final class SourceTextIndex {

    /// Byte offset of the first byte of each row; index = 0-based row.
    private final int[] lineStartBytes;
    /// Total UTF-8 byte length of the source; also the physical EOF byte offset.
    private final int utf8Length;

    private SourceTextIndex(int[] lineStartBytes, int utf8Length) {
        this.lineStartBytes = lineStartBytes;
        this.utf8Length = utf8Length;
    }

    static SourceTextIndex of(String source) {
        var lineStarts = new ArrayList<Integer>();
        lineStarts.add(0);
        var byteOffset = 0;
        var i = 0;
        while (i < source.length()) {
            var codePoint = source.codePointAt(i);
            i += Character.charCount(codePoint);
            byteOffset += utf8Length(codePoint);
            if (codePoint == '\n') {
                lineStarts.add(byteOffset);
            }
        }
        return new SourceTextIndex(lineStarts.stream().mapToInt(Integer::intValue).toArray(), byteOffset);
    }

    /// Total UTF-8 byte length of the source, i.e. the physical EOF byte offset.
    int utf8Length() {
        return utf8Length;
    }

    /// Number of rows. A trailing newline records the (empty) final row's start at the
    /// EOF offset, matching tree-sitter's end-point convention for newline-terminated
    /// files; that row only has content if bytes follow it.
    int lineCount() {
        return lineStartBytes.length;
    }

    /// Byte offset of the first byte of the given 0-based row.
    int lineStartByte(int row) {
        if (row < 0 || row >= lineStartBytes.length) {
            throw new IllegalArgumentException("row out of range: " + row);
        }
        return lineStartBytes[row];
    }

    /// Converts a UTF-8 byte offset into a 0-based `Point(row, byteColumn)`. The EOF
    /// offset (`utf8Length()`) is valid and maps to the end of the last row.
    Point pointAt(int byteOffset) {
        if (byteOffset < 0 || byteOffset > utf8Length) {
            throw new IllegalArgumentException(
                    "byteOffset out of range [0, " + utf8Length + "]: " + byteOffset);
        }
        // Greatest row whose start is <= byteOffset.
        var low = 0;
        var high = lineStartBytes.length - 1;
        while (low < high) {
            var middle = (low + high + 1) / 2;
            if (lineStartBytes[middle] <= byteOffset) {
                low = middle;
            } else {
                high = middle - 1;
            }
        }
        return new Point(low, byteOffset - lineStartBytes[low]);
    }

    /// Builds a `Range` from two physical byte offsets.
    Range rangeOf(int startByte, int endByte) {
        if (startByte > endByte) {
            throw new IllegalArgumentException(
                    "startByte must not be greater than endByte: " + startByte + " > " + endByte);
        }
        return new Range(startByte, endByte, pointAt(startByte), pointAt(endByte));
    }

    /// UTF-8 byte length of one code point, following the JDK UTF-8 encoder that also
    /// feeds the tree-sitter path: an unpaired surrogate (which `String.codePointAt`
    /// reports as-is) is replaced by the encoder with a single `?` byte, so it counts 1.
    static int utf8Length(int codePoint) {
        if (codePoint >= 0xD800 && codePoint <= 0xDFFF) {
            return 1;
        }
        if (codePoint < 0x80) {
            return 1;
        } else if (codePoint < 0x800) {
            return 2;
        } else if (codePoint < 0x10000) {
            return 3;
        } else {
            return 4;
        }
    }
}
