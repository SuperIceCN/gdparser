package dev.superice.gdparser.frontend.gdscript;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

/// Behavior tests for `GdTokenizer`, anchored to Godot's tokenizer semantics.
///
/// Most single-token sources end without a trailing newline, so Godot's EOF hook
/// synthesizes a final `NEWLINE`; expected sequences therefore read
/// `[<tokens>, NEWLINE, EOF]`. Multibyte/newline/indent edge cases assert byte offsets
/// (the `Range` truth source) and Godot's 1-based diagnostic coordinates separately.
class GdTokenizerTest {

    private static final Path FIXTURE_DIR = Path.of("src", "test", "resources", "gdscript");

    // --- Helpers -----------------------------------------------------------------------

    /// Scans the whole stream, EOF token included. Fails instead of hanging if the
    /// tokenizer stops making progress.
    private static List<GdToken> tokenize(String source) {
        var tokenizer = new GdTokenizer(source);
        var tokens = new ArrayList<GdToken>();
        for (var i = 0; i < 100_000; i++) {
            var token = tokenizer.scan();
            tokens.add(token);
            if (token.type() == GdTokenType.EOF) {
                return List.copyOf(tokens);
            }
        }
        return fail("tokenizer did not reach EOF within 100k scans");
    }

    private static List<GdTokenType> typesOf(List<GdToken> tokens) {
        return tokens.stream().map(GdToken::type).toList();
    }

    private static void assertTypes(String source, GdTokenType... expected) {
        assertEquals(List.of(expected), typesOf(tokenize(source)), () -> "source: " + source);
    }

    private static List<GdToken> errorsOf(List<GdToken> tokens) {
        return tokens.stream().filter(GdToken::isError).toList();
    }

    private static void assertSingleError(String source, String expectedMessage) {
        var errors = errorsOf(tokenize(source));
        assertEquals(1, errors.size(), () -> "expected exactly one error in: " + source);
        assertEquals(expectedMessage, errors.getFirst().errorMessage());
    }

    // --- Basic dispatch ------------------------------------------------------------------

    @Test
    void emptySourceProducesOnlyEof() {
        var tokens = tokenize("");
        assertEquals(List.of(GdTokenType.EOF), typesOf(tokens));
        var eof = tokens.getFirst();
        assertEquals(0, eof.startByte());
        assertEquals(0, eof.endByte());
        assertEquals(1, eof.startLine());
        assertEquals(1, eof.startColumn());
    }

    @Test
    void keywordsAndBuiltinConstantsHaveDedicatedTypes() {
        var keywords = Map.ofEntries(
                Map.entry("as", GdTokenType.AS), Map.entry("and", GdTokenType.AND),
                Map.entry("assert", GdTokenType.ASSERT), Map.entry("await", GdTokenType.AWAIT),
                Map.entry("break", GdTokenType.BREAK), Map.entry("breakpoint", GdTokenType.BREAKPOINT),
                Map.entry("class", GdTokenType.CLASS), Map.entry("class_name", GdTokenType.CLASS_NAME),
                Map.entry("const", GdTokenType.CONST), Map.entry("continue", GdTokenType.CONTINUE),
                Map.entry("elif", GdTokenType.ELIF), Map.entry("else", GdTokenType.ELSE),
                Map.entry("enum", GdTokenType.ENUM), Map.entry("extends", GdTokenType.EXTENDS),
                Map.entry("for", GdTokenType.FOR), Map.entry("func", GdTokenType.FUNC),
                Map.entry("if", GdTokenType.IF), Map.entry("in", GdTokenType.IN),
                Map.entry("is", GdTokenType.IS), Map.entry("match", GdTokenType.MATCH),
                Map.entry("namespace", GdTokenType.NAMESPACE), Map.entry("not", GdTokenType.NOT),
                Map.entry("or", GdTokenType.OR), Map.entry("pass", GdTokenType.PASS),
                Map.entry("preload", GdTokenType.PRELOAD), Map.entry("return", GdTokenType.RETURN),
                Map.entry("self", GdTokenType.SELF), Map.entry("signal", GdTokenType.SIGNAL),
                Map.entry("static", GdTokenType.STATIC), Map.entry("super", GdTokenType.SUPER),
                Map.entry("trait", GdTokenType.TRAIT), Map.entry("var", GdTokenType.VAR),
                Map.entry("void", GdTokenType.VOID), Map.entry("while", GdTokenType.WHILE),
                Map.entry("when", GdTokenType.WHEN), Map.entry("yield", GdTokenType.YIELD),
                Map.entry("INF", GdTokenType.CONST_INF), Map.entry("NAN", GdTokenType.CONST_NAN),
                Map.entry("PI", GdTokenType.CONST_PI), Map.entry("TAU", GdTokenType.CONST_TAU));
        for (var entry : keywords.entrySet()) {
            assertTypes(entry.getKey(), entry.getValue(), GdTokenType.NEWLINE, GdTokenType.EOF);
        }
        // Keyword lookalikes stay identifiers.
        assertTypes("iffy", GdTokenType.IDENTIFIER, GdTokenType.NEWLINE, GdTokenType.EOF);
        assertTypes("variable", GdTokenType.IDENTIFIER, GdTokenType.NEWLINE, GdTokenType.EOF);
    }

    @Test
    void trueFalseNullAreLiteralsWithContractKinds() {
        var tokens = tokenize("true false null");
        assertEquals(GdLiteralKind.TRUE, tokens.get(0).literalKind());
        assertEquals(GdLiteralKind.FALSE, tokens.get(1).literalKind());
        assertEquals(GdLiteralKind.NULL, tokens.get(2).literalKind());
        assertTypes("true", GdTokenType.LITERAL, GdTokenType.NEWLINE, GdTokenType.EOF);
    }

    @Test
    void identifiersAndLoneUnderscore() {
        assertTypes("_foo", GdTokenType.IDENTIFIER, GdTokenType.NEWLINE, GdTokenType.EOF);
        assertTypes("__", GdTokenType.IDENTIFIER, GdTokenType.NEWLINE, GdTokenType.EOF);
        assertTypes("变量", GdTokenType.IDENTIFIER, GdTokenType.NEWLINE, GdTokenType.EOF);
        assertTypes("r", GdTokenType.IDENTIFIER, GdTokenType.NEWLINE, GdTokenType.EOF);
        assertTypes("rab", GdTokenType.IDENTIFIER, GdTokenType.NEWLINE, GdTokenType.EOF);

        var underscore = tokenize("_").getFirst();
        assertEquals(GdTokenType.UNDERSCORE, underscore.type());
        assertEquals("_", underscore.source());
    }

    @Test
    void annotationsKeepAtSignInSourceAndQueueErrorWithoutIdentifier() {
        assertTypes("@export var x",
                GdTokenType.ANNOTATION, GdTokenType.VAR, GdTokenType.IDENTIFIER,
                GdTokenType.NEWLINE, GdTokenType.EOF);
        assertEquals("@export", tokenize("@export var x").getFirst().source());

        // Aligned with Godot: the ANNOTATION token is still produced, the error follows.
        var tokens = tokenize("@ 1");
        assertEquals(List.of(GdTokenType.ANNOTATION, GdTokenType.ERROR, GdTokenType.LITERAL,
                GdTokenType.NEWLINE, GdTokenType.EOF), typesOf(tokens));
        assertEquals("@", tokens.getFirst().source());
        assertEquals("Expected annotation identifier after \"@\".", tokens.get(1).errorMessage());
    }

    // --- Numbers --------------------------------------------------------------------------

    @Test
    void integerAndFloatForms() {
        for (var source : List.of("0", "42", "0x1F", "0X2a", "0b101", "1_000_000")) {
            var tokens = tokenize(source);
            assertEquals(GdTokenType.LITERAL, tokens.getFirst().type(), () -> "source: " + source);
            assertEquals(GdLiteralKind.INTEGER, tokens.getFirst().literalKind(), () -> "source: " + source);
            assertEquals(source, tokens.getFirst().source());
        }
        for (var source : List.of("1.5", "1.", ".5", "1e3", "1E-3", "2.5e+10")) {
            var tokens = tokenize(source);
            assertEquals(GdTokenType.LITERAL, tokens.getFirst().type(), () -> "source: " + source);
            assertEquals(GdLiteralKind.FLOAT, tokens.getFirst().literalKind(), () -> "source: " + source);
            assertEquals(source, tokens.getFirst().source());
        }
    }

    @Test
    void hexOrBinaryWithoutDigitsReturnsErrorTokenImmediately() {
        assertSingleError("0x", "Expected hexadecimal digit after \"0x\".");
        var tokens = tokenize("0b2");
        assertEquals(List.of(GdTokenType.ERROR, GdTokenType.LITERAL, GdTokenType.NEWLINE, GdTokenType.EOF),
                typesOf(tokens));
        assertEquals("Expected binary digit after \"0b\".", tokens.getFirst().errorMessage());
    }

    @Test
    void malformedNumbersQueueErrorsButKeepScanning() {
        // Queued errors surface after the literal they were discovered in.
        assertSingleError("1__2", "Multiple underscores cannot be adjacent in a numeric literal.");
        assertSingleError("0x_FF", "Unexpected underscore after \"0x\".");
        assertSingleError("10._5", "Unexpected underscore after decimal point.");
        assertSingleError("1e", "Expected exponent value after \"e\".");
        assertSingleError("12abc", "Invalid numeric notation.");

        var tokens = tokenize("1.2.3");
        assertEquals(List.of(GdTokenType.LITERAL, GdTokenType.ERROR, GdTokenType.LITERAL,
                GdTokenType.NEWLINE, GdTokenType.EOF), typesOf(tokens));
        assertEquals("1.2", tokens.get(0).source());
        assertEquals("Cannot use a decimal point twice in a number.", tokens.get(1).errorMessage());
        assertEquals(".3", tokens.get(2).source());
        assertEquals(GdLiteralKind.FLOAT, tokens.get(2).literalKind());
    }

    @Test
    void queuedErrorsPopInLifoOrder() {
        var tokens = tokenize("0x_1__2");
        assertEquals(List.of(GdTokenType.LITERAL, GdTokenType.ERROR, GdTokenType.ERROR,
                GdTokenType.NEWLINE, GdTokenType.EOF), typesOf(tokens));
        assertEquals("Multiple underscores cannot be adjacent in a numeric literal.",
                tokens.get(1).errorMessage());
        assertEquals("Unexpected underscore after \"0x\".", tokens.get(2).errorMessage());
    }

    @Test
    void signsAreNeverAbsorbedIntoNumbers() {
        // Unary signs stay separate tokens regardless of the preceding token, so the
        // parser can model them as UnaryExpression.
        assertTypes("-2", GdTokenType.MINUS, GdTokenType.LITERAL, GdTokenType.NEWLINE, GdTokenType.EOF);
        assertTypes("+3", GdTokenType.PLUS, GdTokenType.LITERAL, GdTokenType.NEWLINE, GdTokenType.EOF);
        assertTypes("a - 2", GdTokenType.IDENTIFIER, GdTokenType.MINUS, GdTokenType.LITERAL,
                GdTokenType.NEWLINE, GdTokenType.EOF);
        assertTypes("(-2)", GdTokenType.PARENTHESIS_OPEN, GdTokenType.MINUS, GdTokenType.LITERAL,
                GdTokenType.PARENTHESIS_CLOSE, GdTokenType.NEWLINE, GdTokenType.EOF);
        assertTypes("-0x1F", GdTokenType.MINUS, GdTokenType.LITERAL, GdTokenType.NEWLINE, GdTokenType.EOF);
    }

    // --- Strings --------------------------------------------------------------------------

    @Test
    void stringFormsCarryRawSourceAndKinds() {
        var tokens = tokenize("\"hello\" 'x' &\"name\" ^\"path\" r\"\\n\"");
        var kinds = tokens.stream().filter(t -> t.type() == GdTokenType.LITERAL).toList();
        assertEquals(5, kinds.size());
        assertEquals(GdLiteralKind.STRING, kinds.get(0).literalKind());
        assertEquals("\"hello\"", kinds.get(0).source());
        assertEquals(GdLiteralKind.STRING, kinds.get(1).literalKind());
        assertEquals("'x'", kinds.get(1).source());
        assertEquals(GdLiteralKind.STRING_NAME, kinds.get(2).literalKind());
        assertEquals("&\"name\"", kinds.get(2).source());
        assertEquals(GdLiteralKind.NODE_PATH, kinds.get(3).literalKind());
        assertEquals("^\"path\"", kinds.get(3).source());
        assertEquals(GdLiteralKind.STRING, kinds.get(4).literalKind());
        assertEquals("r\"\\n\"", kinds.get(4).source());
    }

    @Test
    void tripleQuotedStringsSpanLinesWithoutNewlineTokens() {
        var tokens = tokenize("\"\"\"a\nb\"\"\"");
        assertEquals(List.of(GdTokenType.LITERAL, GdTokenType.NEWLINE, GdTokenType.EOF), typesOf(tokens));
        assertEquals("\"\"\"a\nb\"\"\"", tokens.getFirst().source());
        // The internal newline bumps to line 2, and Godot's EOF hook (fired when the
        // closing quote is consumed) bumps once more, so EOF lands on line 3.
        assertEquals(3, tokens.getLast().startLine());

        assertTypes("\"\"\"\"\"\"", GdTokenType.LITERAL, GdTokenType.NEWLINE, GdTokenType.EOF);
        assertTypes("\"\"", GdTokenType.LITERAL, GdTokenType.NEWLINE, GdTokenType.EOF);
    }

    @Test
    void validEscapesProduceNoErrors() {
        assertTrue(errorsOf(tokenize("\"a\\n\\t\\\\\\\"b\"")).isEmpty());
        assertTrue(errorsOf(tokenize("\"\\u0041\\U0001F600\"")).isEmpty());
        // Valid UTF-16 surrogate pair.
        assertTrue(errorsOf(tokenize("\"\\uD800\\uDC00\"")).isEmpty());
        // Raw strings keep backslashes verbatim without escape errors.
        assertTrue(errorsOf(tokenize("r\"\\q\"")).isEmpty());
    }

    @Test
    void invalidEscapesReportErrorsButStillProduceStringToken() {
        var tokens = tokenize("\"\\q\"");
        assertEquals(GdTokenType.LITERAL, tokens.getFirst().type());
        assertEquals("Invalid escape in string.", errorsOf(tokens).getFirst().errorMessage());

        assertSingleError("\"\\u00ZZ\"", "Invalid hexadecimal digit in unicode escape sequence.");
    }

    @Test
    void unpairedSurrogateEscapesReportErrors() {
        assertSingleError("\"\\uD800\"", "Invalid UTF-16 sequence in string, unpaired lead surrogate");
        assertSingleError("\"\\uDC00\"", "Invalid UTF-16 sequence in string, unpaired trail surrogate.");
    }

    @Test
    void unterminatedStringsReturnErrorToken() {
        assertSingleError("\"abc", "Unterminated string.");
        assertSingleError("'''abc", "Unterminated string.");
        assertSingleError("r\"abc", "Unterminated string.");
    }

    // --- Operators and punctuation ---------------------------------------------------------

    @Test
    void operatorAndPunctuationTable() {
        var operators = Map.ofEntries(
                Map.entry("<", GdTokenType.LESS), Map.entry("<=", GdTokenType.LESS_EQUAL),
                Map.entry(">", GdTokenType.GREATER), Map.entry(">=", GdTokenType.GREATER_EQUAL),
                Map.entry("==", GdTokenType.EQUAL_EQUAL), Map.entry("!=", GdTokenType.BANG_EQUAL),
                Map.entry("&&", GdTokenType.AMPERSAND_AMPERSAND), Map.entry("||", GdTokenType.PIPE_PIPE),
                Map.entry("!", GdTokenType.BANG), Map.entry("&", GdTokenType.AMPERSAND),
                Map.entry("|", GdTokenType.PIPE), Map.entry("~", GdTokenType.TILDE),
                Map.entry("^", GdTokenType.CARET), Map.entry("<<", GdTokenType.LESS_LESS),
                Map.entry(">>", GdTokenType.GREATER_GREATER), Map.entry("+", GdTokenType.PLUS),
                Map.entry("-", GdTokenType.MINUS), Map.entry("*", GdTokenType.STAR),
                Map.entry("**", GdTokenType.STAR_STAR), Map.entry("/", GdTokenType.SLASH),
                Map.entry("%", GdTokenType.PERCENT), Map.entry("=", GdTokenType.EQUAL),
                Map.entry("+=", GdTokenType.PLUS_EQUAL), Map.entry("-=", GdTokenType.MINUS_EQUAL),
                Map.entry("*=", GdTokenType.STAR_EQUAL), Map.entry("**=", GdTokenType.STAR_STAR_EQUAL),
                Map.entry("/=", GdTokenType.SLASH_EQUAL), Map.entry("%=", GdTokenType.PERCENT_EQUAL),
                Map.entry("<<=", GdTokenType.LESS_LESS_EQUAL), Map.entry(">>=", GdTokenType.GREATER_GREATER_EQUAL),
                Map.entry("&=", GdTokenType.AMPERSAND_EQUAL), Map.entry("|=", GdTokenType.PIPE_EQUAL),
                Map.entry("^=", GdTokenType.CARET_EQUAL), Map.entry(",", GdTokenType.COMMA),
                Map.entry(";", GdTokenType.SEMICOLON), Map.entry(".", GdTokenType.PERIOD),
                Map.entry("..", GdTokenType.PERIOD_PERIOD), Map.entry("...", GdTokenType.PERIOD_PERIOD_PERIOD),
                Map.entry(":", GdTokenType.COLON), Map.entry("$", GdTokenType.DOLLAR),
                Map.entry("->", GdTokenType.FORWARD_ARROW), Map.entry("_", GdTokenType.UNDERSCORE),
                Map.entry("?", GdTokenType.QUESTION_MARK), Map.entry("`", GdTokenType.BACKTICK),
                // Unmatched closers are ERROR tokens (covered by the bracket tests below);
                // matched brackets are covered by matchedBracketsProduceTokens.
                Map.entry("[", GdTokenType.BRACKET_OPEN), Map.entry("{", GdTokenType.BRACE_OPEN),
                Map.entry("(", GdTokenType.PARENTHESIS_OPEN));
        for (var entry : operators.entrySet()) {
            assertTypes(entry.getKey(), entry.getValue(), GdTokenType.NEWLINE, GdTokenType.EOF);
        }
    }

    @Test
    void colonAndEqualStaySeparateTokens() {
        // There is no `:=` token; the parser recognizes the inferred-type marker.
        assertTypes(":=", GdTokenType.COLON, GdTokenType.EQUAL, GdTokenType.NEWLINE, GdTokenType.EOF);
        assertTypes("var x := 1", GdTokenType.VAR, GdTokenType.IDENTIFIER, GdTokenType.COLON,
                GdTokenType.EQUAL, GdTokenType.LITERAL, GdTokenType.NEWLINE, GdTokenType.EOF);
    }

    @Test
    void vcsConflictMarkersNeedSevenCharacters() {
        assertTypes("=======", GdTokenType.VCS_CONFLICT_MARKER, GdTokenType.NEWLINE, GdTokenType.EOF);
        assertTypes("<<<<<<<", GdTokenType.VCS_CONFLICT_MARKER, GdTokenType.NEWLINE, GdTokenType.EOF);
        assertTypes(">>>>>>>", GdTokenType.VCS_CONFLICT_MARKER, GdTokenType.NEWLINE, GdTokenType.EOF);
        assertEquals("========", tokenize("========").getFirst().source());
        // Six characters are three regular double-character tokens.
        assertTypes("======", GdTokenType.EQUAL_EQUAL, GdTokenType.EQUAL_EQUAL, GdTokenType.EQUAL_EQUAL,
                GdTokenType.NEWLINE, GdTokenType.EOF);
    }

    @Test
    void closingBracketsMustMatchOpeners() {
        assertSingleError(")", "Closing \")\" doesn't have an opening counterpart.");

        var tokens = tokenize("([)]");
        assertEquals(List.of(GdTokenType.PARENTHESIS_OPEN, GdTokenType.BRACKET_OPEN,
                GdTokenType.ERROR, GdTokenType.ERROR, GdTokenType.NEWLINE, GdTokenType.EOF), typesOf(tokens));
        // Godot's pop_paren removes the mismatched opener before make_paren_error reports,
        // so the message names the next opener on the stack ("(" here).
        assertEquals("Closing \")\" doesn't match the opening \"(\".", tokens.get(2).errorMessage());
        assertEquals("Closing \"]\" doesn't have an opening counterpart.", tokens.get(3).errorMessage());
    }

    @Test
    void matchedBracketsProduceTokens() {
        assertTypes("([{}])", GdTokenType.PARENTHESIS_OPEN, GdTokenType.BRACKET_OPEN,
                GdTokenType.BRACE_OPEN, GdTokenType.BRACE_CLOSE, GdTokenType.BRACKET_CLOSE,
                GdTokenType.PARENTHESIS_CLOSE, GdTokenType.NEWLINE, GdTokenType.EOF);
    }

    @Test
    void invalidCharactersBecomeErrorsAndScanningContinues() {
        var tokens = tokenize("a \u0001b");
        assertEquals(List.of(GdTokenType.IDENTIFIER, GdTokenType.ERROR, GdTokenType.IDENTIFIER,
                GdTokenType.NEWLINE, GdTokenType.EOF), typesOf(tokens));
        assertTrue(tokens.get(1).errorMessage().contains("(U+0001)"),
                () -> tokens.get(1).errorMessage());

        assertSingleError("a\u2003b", "Invalid white space character U+2003.");
    }

    // --- Indentation and newlines -----------------------------------------------------------

    @Test
    void indentAndDedentSequence() {
        assertTypes("if a:\n\tpass\nvar b = 1",
                GdTokenType.IF, GdTokenType.IDENTIFIER, GdTokenType.COLON, GdTokenType.NEWLINE,
                GdTokenType.INDENT, GdTokenType.PASS, GdTokenType.NEWLINE, GdTokenType.DEDENT,
                GdTokenType.VAR, GdTokenType.IDENTIFIER, GdTokenType.EQUAL, GdTokenType.LITERAL,
                GdTokenType.NEWLINE, GdTokenType.EOF);

        // INDENT covers the line's leading whitespace (byte range and 1-based columns).
        var indent = tokenize("if a:\n\tpass").stream()
                .filter(t -> t.type() == GdTokenType.INDENT).findFirst().orElseThrow();
        assertEquals(6, indent.startByte());
        assertEquals(7, indent.endByte());
        assertEquals("\t", indent.source());
        assertEquals(1, indent.startColumn());
        assertEquals(2, indent.endColumn());
    }

    @Test
    void nestedIndentDedentsOneLevelAtATime() {
        assertTypes("if a:\n\tif b:\n\t\tpass\n\tpass2",
                GdTokenType.IF, GdTokenType.IDENTIFIER, GdTokenType.COLON, GdTokenType.NEWLINE,
                GdTokenType.INDENT, GdTokenType.IF, GdTokenType.IDENTIFIER, GdTokenType.COLON,
                GdTokenType.NEWLINE, GdTokenType.INDENT, GdTokenType.PASS, GdTokenType.NEWLINE,
                GdTokenType.DEDENT, GdTokenType.IDENTIFIER, GdTokenType.NEWLINE,
                GdTokenType.DEDENT, GdTokenType.EOF);
    }

    @Test
    void misalignedDedentReportsErrorAndKeepsLevel() {
        var tokens = tokenize("if a:\n    pass\n  pass\n");
        assertEquals(List.of(GdTokenType.IF, GdTokenType.IDENTIFIER, GdTokenType.COLON,
                GdTokenType.NEWLINE, GdTokenType.INDENT, GdTokenType.PASS, GdTokenType.NEWLINE,
                GdTokenType.ERROR, GdTokenType.DEDENT, GdTokenType.PASS, GdTokenType.NEWLINE,
                GdTokenType.DEDENT, GdTokenType.EOF), typesOf(tokens));
        assertEquals("Unindent doesn't match the previous indentation level.",
                errorsOf(tokens).getFirst().errorMessage());
        // The misaligned width is pushed back without emitting INDENT: one INDENT total,
        // balanced by two DEDENTs (one from the misaligned line, one at EOF).
        assertEquals(1, tokens.stream().filter(t -> t.type() == GdTokenType.INDENT).count());
        assertEquals(2, tokens.stream().filter(t -> t.type() == GdTokenType.DEDENT).count());
    }

    @Test
    void mixedTabsAndSpacesOnSameLineReportError() {
        var tokens = tokenize("if a:\n\t  pass\n");
        assertEquals("Mixed use of tabs and spaces for indentation.",
                errorsOf(tokens).getFirst().errorMessage());
        // Scanning continues and the indent token is still delivered.
        assertTrue(typesOf(tokens).contains(GdTokenType.INDENT));
    }

    @Test
    void inconsistentIndentCharacterAcrossFileReportsError() {
        assertSingleError("if a:\n\tpass\nif b:\n  pass\n",
                "Used space character for indentation instead of tab as used before in the file.");
    }

    @Test
    void blankAndCommentOnlyLinesProduceNoNewlineOrIndent() {
        var tokens = tokenize("var a = 1\n\n# comment\n  \n# c2\nvar b = 2");
        assertEquals(List.of(GdTokenType.VAR, GdTokenType.IDENTIFIER, GdTokenType.EQUAL,
                        GdTokenType.LITERAL, GdTokenType.NEWLINE, GdTokenType.VAR, GdTokenType.IDENTIFIER,
                        GdTokenType.EQUAL, GdTokenType.LITERAL, GdTokenType.NEWLINE, GdTokenType.EOF),
                typesOf(tokens));
    }

    @Test
    void newlineOnlyFileStillGetsEofSynthesizedNewline() {
        // Mid-file empty lines never emit NEWLINE, but consuming the final '\n' triggers
        // Godot's EOF hook, which synthesizes one pending NEWLINE regardless.
        assertTypes("\n\n", GdTokenType.NEWLINE, GdTokenType.EOF);
    }

    // --- Comments (trivia) ------------------------------------------------------------------

    @Test
    void commentsAreCollectedAsTriviaAndNeverEnterTokenStream() {
        var tokenizer = new GdTokenizer("var a = 1 # tail\n# whole\nvar b = 2");
        var tokens = new ArrayList<GdToken>();
        for (var i = 0; i < 10_000; i++) {
            var token = tokenizer.scan();
            tokens.add(token);
            if (token.type() == GdTokenType.EOF) {
                break;
            }
        }
        assertFalse(typesOf(tokens).contains(GdTokenType.ERROR));

        var comments = tokenizer.getComments();
        assertEquals(2, comments.size());
        var tail = comments.getFirst();
        assertEquals("# tail", tail.text());
        assertFalse(tail.wholeLine());
        assertEquals(1, tail.line());
        assertEquals("# tail", "var a = 1 # tail\n# whole\nvar b = 2".substring(10, 16));
        assertEquals(10, tail.startByte());
        assertEquals(16, tail.endByte());
        var whole = comments.get(1);
        assertEquals("# whole", whole.text());
        assertTrue(whole.wholeLine());
        assertEquals(2, whole.line());
    }

    @Test
    void commentInsideIndentedBlockDoesNotAffectIndentation() {
        var tokenizer = new GdTokenizer("if a:\n\t# c\n\tpass\n");
        var types = new ArrayList<GdTokenType>();
        for (var i = 0; i < 10_000; i++) {
            var token = tokenizer.scan();
            types.add(token.type());
            if (token.type() == GdTokenType.EOF) {
                break;
            }
        }
        assertEquals(List.of(GdTokenType.IF, GdTokenType.IDENTIFIER, GdTokenType.COLON,
                GdTokenType.NEWLINE, GdTokenType.INDENT, GdTokenType.PASS, GdTokenType.NEWLINE,
                GdTokenType.DEDENT, GdTokenType.EOF), types);
        var comments = tokenizer.getComments();
        assertEquals(1, comments.size());
        assertTrue(comments.getFirst().wholeLine());
        assertEquals(2, comments.getFirst().line());
    }

    @Test
    void commentAtEofWithoutNewlineIsCollected() {
        var tokenizer = new GdTokenizer("var a = 1 # tail");
        var types = new ArrayList<GdTokenType>();
        for (var i = 0; i < 10_000; i++) {
            var token = tokenizer.scan();
            types.add(token.type());
            if (token.type() == GdTokenType.EOF) {
                break;
            }
        }
        assertEquals(List.of(GdTokenType.VAR, GdTokenType.IDENTIFIER, GdTokenType.EQUAL,
                GdTokenType.LITERAL, GdTokenType.NEWLINE, GdTokenType.EOF), types);
        assertEquals("# tail", tokenizer.getComments().getFirst().text());
    }

    @Test
    void regionStyleCommentsArePlainTriviaAtTokenizerLevel() {
        var tokenizer = new GdTokenizer("#region foo\n# x\n#endregion");
        // Consuming the final comment character triggers the EOF hook, hence the NEWLINE.
        assertEquals(GdTokenType.NEWLINE, tokenizer.scan().type());
        assertEquals(GdTokenType.EOF, tokenizer.scan().type());
        var texts = tokenizer.getComments().stream().map(GdComment::text).toList();
        assertEquals(List.of("#region foo", "# x", "#endregion"), texts);
    }

    // --- Line continuation -------------------------------------------------------------------

    @Test
    void backslashContinuationSuppressesNewline() {
        var tokenizer = new GdTokenizer("var a = 1 +\\\n  2");
        var types = new ArrayList<GdTokenType>();
        var secondLiteralLine = -1;
        for (var i = 0; i < 10_000; i++) {
            var token = tokenizer.scan();
            types.add(token.type());
            if (token.type() == GdTokenType.LITERAL && token.source().equals("2")) {
                secondLiteralLine = token.startLine();
            }
            if (token.type() == GdTokenType.EOF) {
                break;
            }
        }
        assertEquals(List.of(GdTokenType.VAR, GdTokenType.IDENTIFIER, GdTokenType.EQUAL,
                GdTokenType.LITERAL, GdTokenType.PLUS, GdTokenType.LITERAL,
                GdTokenType.EOF), types);
        // Godot quirk (kept): `line_continuation` is still set when consuming the last
        // character, so the EOF hook does not synthesize a final NEWLINE here.
        assertEquals(2, secondLiteralLine);
        assertEquals(List.of(2), tokenizer.getContinuationLines());
    }

    @Test
    void continuationSkipsCommentLines() {
        // Godot GH-89403: whitespace and comment lines after `\` are skipped.
        var tokenizer = new GdTokenizer("a = 1 +\\\n# c\n 2");
        var types = new ArrayList<GdTokenType>();
        for (var i = 0; i < 10_000; i++) {
            var token = tokenizer.scan();
            types.add(token.type());
            if (token.type() == GdTokenType.EOF) {
                break;
            }
        }
        assertEquals(List.of(GdTokenType.IDENTIFIER, GdTokenType.EQUAL, GdTokenType.LITERAL,
                GdTokenType.PLUS, GdTokenType.LITERAL, GdTokenType.EOF), types);
        assertEquals(List.of(3), tokenizer.getContinuationLines());
        assertEquals("# c", tokenizer.getComments().getFirst().text());
        assertTrue(tokenizer.getComments().getFirst().wholeLine());
    }

    @Test
    void backslashNotFollowedByNewlineIsAnError() {
        var tokens = tokenize("a \\ x");
        assertEquals(List.of(GdTokenType.IDENTIFIER, GdTokenType.ERROR, GdTokenType.IDENTIFIER,
                GdTokenType.NEWLINE, GdTokenType.EOF), typesOf(tokens));
        assertEquals("Expected new line after \"\\\".", errorsOf(tokens).getFirst().errorMessage());
    }

    @Test
    void backslashMustBeImmediatelyFollowedByNewline() {
        // Aligned with Godot: a comment (or any other character) between the backslash and
        // the newline is an error, not a continuation.
        var tokens = tokenize("a = 1 + \\ # tail\n2");
        assertEquals(List.of(GdTokenType.IDENTIFIER, GdTokenType.EQUAL, GdTokenType.LITERAL,
                GdTokenType.PLUS, GdTokenType.ERROR, GdTokenType.NEWLINE, GdTokenType.LITERAL,
                GdTokenType.NEWLINE, GdTokenType.EOF), typesOf(tokens));
        assertEquals("Expected new line after \"\\\".", errorsOf(tokens).getFirst().errorMessage());
    }

    // --- Multiline mode -----------------------------------------------------------------------

    @Test
    void multilineModeSuppressesNewlinesAndIndentation() {
        // Raw stream (no parser multiline intervention) treats the line break inside
        // brackets as a real newline plus indent — this is why `tokenize` output must not
        // be used as a legality criterion for bracketed constructs.
        assertTypes("(1 +\n 2)",
                GdTokenType.PARENTHESIS_OPEN, GdTokenType.LITERAL, GdTokenType.PLUS,
                GdTokenType.NEWLINE, GdTokenType.INDENT, GdTokenType.LITERAL,
                GdTokenType.PARENTHESIS_CLOSE, GdTokenType.NEWLINE, GdTokenType.DEDENT,
                GdTokenType.EOF);

        // With multiline mode enabled after the open paren (as the parser would), the
        // newline and indentation disappear from the stream.
        var tokenizer = new GdTokenizer("(1 +\n 2)");
        var types = new ArrayList<GdTokenType>();
        for (var i = 0; i < 10_000; i++) {
            var token = tokenizer.scan();
            types.add(token.type());
            if (token.type() == GdTokenType.PARENTHESIS_OPEN) {
                tokenizer.setMultilineMode(true);
            }
            if (token.type() == GdTokenType.PARENTHESIS_CLOSE) {
                tokenizer.setMultilineMode(false);
            }
            if (token.type() == GdTokenType.EOF) {
                break;
            }
        }
        assertEquals(List.of(GdTokenType.PARENTHESIS_OPEN, GdTokenType.LITERAL, GdTokenType.PLUS,
                GdTokenType.LITERAL, GdTokenType.PARENTHESIS_CLOSE, GdTokenType.NEWLINE,
                GdTokenType.EOF), types);
    }

    // --- CRLF and stray carriage returns --------------------------------------------------------

    @Test
    void crlfNewlinesAreNormalized() {
        var tokens = tokenize("var a = 1\r\nvar b = 2\r\n");
        assertEquals(List.of(GdTokenType.VAR, GdTokenType.IDENTIFIER, GdTokenType.EQUAL,
                        GdTokenType.LITERAL, GdTokenType.NEWLINE, GdTokenType.VAR, GdTokenType.IDENTIFIER,
                        GdTokenType.EQUAL, GdTokenType.LITERAL, GdTokenType.NEWLINE, GdTokenType.EOF),
                typesOf(tokens));
        assertTrue(errorsOf(tokens).isEmpty());
        // The NEWLINE token covers only the '\n' byte; '\r' stays on the previous row.
        var newline = tokens.stream().filter(t -> t.type() == GdTokenType.NEWLINE).findFirst().orElseThrow();
        assertEquals(10, newline.startByte());
        assertEquals(11, newline.endByte());
    }

    @Test
    void strayCarriageReturnReportsErrorAndScanningContinues() {
        var tokens = tokenize("a\rb");
        assertEquals(List.of(GdTokenType.IDENTIFIER, GdTokenType.ERROR, GdTokenType.IDENTIFIER,
                GdTokenType.NEWLINE, GdTokenType.EOF), typesOf(tokens));
        var error = errorsOf(tokens).getFirst();
        assertEquals("Stray carriage return character in source code.", error.errorMessage());
        // Whitespace-phase errors are anchored to the offending character.
        assertEquals(1, error.startByte());
        assertEquals(2, error.endByte());
    }

    // --- EOF coordinate anchoring -----------------------------------------------------------------

    @Test
    void eofSynthesizedNewlineAnchorsToPhysicalEofWithoutTrailingNewline() {
        var tokens = tokenize("pass");
        assertEquals(List.of(GdTokenType.PASS, GdTokenType.NEWLINE, GdTokenType.EOF), typesOf(tokens));

        // The synthesized NEWLINE is zero-width at the physical EOF byte offset, while its
        // Godot coordinates (kept for diagnostic parity) cover the last consumed column.
        var newline = tokens.get(1);
        assertEquals(4, newline.startByte());
        assertEquals(4, newline.endByte());
        assertEquals(1, newline.startLine());
        assertEquals(4, newline.startColumn());
        assertEquals(5, newline.endColumn());

        var eof = tokens.get(2);
        assertEquals(4, eof.startByte());
        assertEquals(4, eof.endByte());
        assertEquals(2, eof.startLine());
    }

    @Test
    void trailingNewlineBumpsLineCounterTwiceAtEof() {
        // Godot quirk, kept for coordinate fidelity: the EOF hook inside advance() and the
        // explicit newline() call both increment the line counter for a trailing newline,
        // so EOF lands on a non-physical row. Byte offsets stay anchored to physical EOF.
        var tokens = tokenize("pass\n");
        var newline = tokens.get(1);
        assertEquals(4, newline.startByte());
        assertEquals(5, newline.endByte());
        assertEquals(1, newline.startLine());

        var eof = tokens.get(2);
        assertEquals(5, eof.startByte());
        assertEquals(5, eof.endByte());
        assertEquals(3, eof.startLine());
        assertEquals(1, eof.startColumn());
    }

    @Test
    void pendingDedentsFlushBeforeEofAndAnchorToPhysicalEof() {
        var tokens = tokenize("if a:\n\tpass");
        assertEquals(List.of(GdTokenType.IF, GdTokenType.IDENTIFIER, GdTokenType.COLON,
                GdTokenType.NEWLINE, GdTokenType.INDENT, GdTokenType.PASS, GdTokenType.NEWLINE,
                GdTokenType.DEDENT, GdTokenType.EOF), typesOf(tokens));

        var dedent = tokens.get(7);
        assertEquals(11, dedent.startByte());
        assertEquals(11, dedent.endByte());
        var eof = tokens.get(8);
        assertEquals(11, eof.startByte());
        assertEquals(11, eof.endByte());
    }

    // --- Indentation state snapshots -------------------------------------------------------------

    @Test
    void expressionIndentedBlockSnapshotsRestoreIndentStack() {
        var source = "func f():\n\tpass\nvar g = 1";
        var baseline = typesOf(tokenize(source));

        // Drive the scanner manually: snapshot after INDENT, let the dedent happen, then
        // restore. The restored level is dedented again at EOF, producing one extra DEDENT.
        var tokenizer = new GdTokenizer(source);
        var types = new ArrayList<GdTokenType>();
        var popped = false;
        for (var i = 0; i < 10_000; i++) {
            var token = tokenizer.scan();
            types.add(token.type());
            if (token.type() == GdTokenType.INDENT) {
                tokenizer.pushExpressionIndentedBlock();
            }
            if (token.type() == GdTokenType.DEDENT && !popped) {
                popped = true;
                tokenizer.popExpressionIndentedBlock();
            }
            if (token.type() == GdTokenType.EOF) {
                break;
            }
        }

        var baselineDedentRun = baseline.stream().filter(t -> t == GdTokenType.DEDENT).count();
        var snapshotDedentRun = types.stream().filter(t -> t == GdTokenType.DEDENT).count();
        assertEquals(1, baselineDedentRun);
        assertEquals(2, snapshotDedentRun);
        assertEquals(GdTokenType.EOF, types.getLast());
    }

    @Test
    void poppingEmptyIndentSnapshotThrows() {
        var tokenizer = new GdTokenizer("pass");
        // Godot silently no-ops here (ERR_FAIL_COND); this port treats it as an internal bug.
        assertThrows(IllegalStateException.class, tokenizer::popExpressionIndentedBlock);
    }

    @Test
    void defaultConstructorProducesUsableTokenizer() {
        var tokenizer = new GdTokenizer();
        assertEquals(GdTokenType.EOF, tokenizer.scan().type());
        // Scanning past EOF keeps producing EOF tokens.
        assertEquals(GdTokenType.EOF, tokenizer.scan().type());
    }

    @Test
    void manyConsecutiveContinuationsDoNotOverflowTheStack() {
        // Godot recurses scan() per continuation; this port loops (bounded stack usage).
        var tokens = tokenize("\\\n".repeat(10_000) + "x");
        var types = typesOf(tokens);
        assertEquals(List.of(GdTokenType.IDENTIFIER, GdTokenType.EOF),
                types.subList(types.size() - 2, types.size()));
    }

    @Test
    void manyErrorsInsideOneLiteralStayBounded() {
        // Every invalid escape queues an error; ERROR tokens carry empty source slices so
        // memory stays linear in the input size.
        var escapeCount = 20_000;
        var tokens = tokenize("\"" + "\\q".repeat(escapeCount) + "\"");
        assertEquals(GdTokenType.LITERAL, tokens.getFirst().type());
        var errors = errorsOf(tokens);
        assertEquals(escapeCount, errors.size());
        assertTrue(errors.stream().allMatch(t -> t.source().isEmpty()));
        assertEquals("Invalid escape in string.", errors.getFirst().errorMessage());
        assertEquals(GdTokenType.EOF, tokens.getLast().type());
    }

    @Test
    void multilineStringAtEofAnchorsBytesToPhysicalEof() {
        var source = "var s = '''\nabc'''";
        var utf8Length = source.getBytes(StandardCharsets.UTF_8).length;
        var tokens = tokenize(source);
        assertEquals(List.of(GdTokenType.VAR, GdTokenType.IDENTIFIER, GdTokenType.EQUAL,
                GdTokenType.LITERAL, GdTokenType.NEWLINE, GdTokenType.EOF), typesOf(tokens));

        var literal = tokens.get(3);
        assertEquals(8, literal.startByte());
        assertEquals(utf8Length, literal.endByte());
        assertEquals(GdLiteralKind.STRING, literal.literalKind());

        // Synthesized tokens are zero-width at the physical EOF offset.
        var newline = tokens.get(4);
        assertEquals(utf8Length, newline.startByte());
        assertEquals(utf8Length, newline.endByte());
        var eof = tokens.get(5);
        assertEquals(utf8Length, eof.startByte());
        assertEquals(utf8Length, eof.endByte());
    }

    @Test
    void unpairedUtf16SurrogatesCountAsOneReplacementByte() {
        // The tree-sitter path parses the JDK UTF-8 encoding, where an unpaired surrogate
        // becomes a single '?' byte; token byte offsets follow the same encoding.
        var tokens = tokenize("a\uD800b");
        assertEquals(List.of(GdTokenType.IDENTIFIER, GdTokenType.ERROR, GdTokenType.IDENTIFIER,
                GdTokenType.NEWLINE, GdTokenType.EOF), typesOf(tokens));
        assertTrue(errorsOf(tokens).getFirst().errorMessage().contains("(U+D800)"));
        var after = tokens.get(2);
        assertEquals(2, after.startByte());
        assertEquals(3, after.endByte());
    }

    // --- Token position conventions -----------------------------------------------------------------

    @Test
    void tokenByteOffsetsAndGodotColumnsStayConsistent() {
        var tokens = tokenize("var x = 1");
        var expectedBytes = new int[][]{{0, 3}, {4, 5}, {6, 7}, {8, 9}};
        var expectedColumns = new int[][]{{1, 4}, {5, 6}, {7, 8}, {9, 10}};
        for (var i = 0; i < 4; i++) {
            var token = tokens.get(i);
            assertEquals(expectedBytes[i][0], token.startByte(), () -> "token " + token);
            assertEquals(expectedBytes[i][1], token.endByte(), () -> "token " + token);
            assertEquals(expectedColumns[i][0], token.startColumn(), () -> "token " + token);
            assertEquals(1, token.startLine());
            if (i < 3) {
                assertEquals(expectedColumns[i][1], token.endColumn(), () -> "token " + token);
                assertEquals(1, token.endLine(), () -> "token " + token);
            }
        }
        // Godot quirk (kept for diagnostic parity): consuming the last character fires the
        // EOF hook, which bumps the line counter before the token is created, so the final
        // real token's end coordinates land on the non-physical next line. Byte offsets
        // are unaffected and remain the Range truth source.
        var lastLiteral = tokens.get(3);
        assertEquals(2, lastLiteral.endLine());
        assertEquals(1, lastLiteral.endColumn());
    }

    // --- Fixture corpus ---------------------------------------------------------------------------------

    @TestFactory
    List<DynamicTest> fixturesTokenizeWithoutErrorsOrExceptions() throws IOException {
        List<Path> scripts;
        try (var stream = Files.list(FIXTURE_DIR)) {
            scripts = stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".gd"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
        }
        assertEquals(13, scripts.size(), "fixture corpus size changed; review parity prerequisites");

        var tests = new ArrayList<DynamicTest>();
        for (var script : scripts) {
            tests.add(dynamicTest("tokenize-fixture: " + script.getFileName(), () -> {
                var source = Files.readString(script, StandardCharsets.UTF_8);
                var tokens = tokenize(source);
                var errors = errorsOf(tokens);
                assertTrue(errors.isEmpty(),
                        () -> "Unexpected ERROR tokens in " + script.getFileName() + ": " + errors);
                assertEquals(GdTokenType.EOF, tokens.getLast().type());
            }));
        }
        return List.copyOf(tests);
    }
}
