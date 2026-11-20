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

/// Behavior tests for `GdTokenizer` covering token dispatch, physical 1-based line/column
/// coordinates, UTF-8 byte offsets, standalone vs managed layout suppression, and bracket closer
/// error handling. Most non-empty lines without a trailing newline emit a zero-width EOF
/// `NEWLINE` anchored at physical EOF unless multiline mode suppresses it.
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

    private static List<GdToken> tokenizeManaged(String source) {
        var tokenizer = GdTokenizer.managed(source);
        var tokens = new ArrayList<GdToken>();
        for (var i = 0; i < 100_000; i++) {
            var token = tokenizer.scan();
            tokens.add(token);
            if (token.type() == GdTokenType.EOF) {
                return List.copyOf(tokens);
            }
        }
        return fail("managed tokenizer did not reach EOF within 100k scans");
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
        var literal = tokens.getFirst();
        assertEquals("\"\"\"a\nb\"\"\"", literal.source());
        assertEquals(1, literal.startLine());
        assertEquals(1, literal.startColumn());
        assertEquals(2, literal.endLine());
        assertEquals(5, literal.endColumn());

        var newline = tokens.get(1);
        assertEquals(2, newline.startLine());
        assertEquals(5, newline.startColumn());
        assertEquals(2, newline.endLine());
        assertEquals(5, newline.endColumn());

        var eof = tokens.getLast();
        assertEquals(2, eof.startLine());
        assertEquals(5, eof.startColumn());
        assertEquals(2, eof.endLine());
        assertEquals(5, eof.endColumn());

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
                Map.entry("?", GdTokenType.QUESTION_MARK), Map.entry("`", GdTokenType.BACKTICK));
        for (var entry : operators.entrySet()) {
            assertTypes(entry.getKey(), entry.getValue(), GdTokenType.NEWLINE, GdTokenType.EOF);
        }
        // Standalone openers keep parenStack non-empty, which suppresses the trailing EOF NEWLINE.
        assertTypes("[", GdTokenType.BRACKET_OPEN, GdTokenType.EOF);
        assertTypes("{", GdTokenType.BRACE_OPEN, GdTokenType.EOF);
        assertTypes("(", GdTokenType.PARENTHESIS_OPEN, GdTokenType.EOF);
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
        assertSingleError("]", "Closing \"]\" doesn't have an opening counterpart.");
        assertSingleError("}", "Closing \"}\" doesn't have an opening counterpart.");

        // Non-ancestor mismatch `[)`: pops `[` and reports it; layout resumes on following lines.
        var nonAncestor = tokenize("[)");
        assertEquals(List.of(GdTokenType.BRACKET_OPEN, GdTokenType.ERROR,
                GdTokenType.NEWLINE, GdTokenType.EOF), typesOf(nonAncestor));
        assertEquals("Closing \")\" doesn't match the opening \"[\".", nonAncestor.get(1).errorMessage());

        var resumedLayout = tokenize("var x = [1)\nvar y = 1\n");
        assertEquals(List.of("Closing \")\" doesn't match the opening \"[\"."),
                errorsOf(resumedLayout).stream().map(GdToken::errorMessage).toList());
        assertEquals(List.of(
                GdTokenType.VAR, GdTokenType.IDENTIFIER, GdTokenType.EQUAL,
                GdTokenType.BRACKET_OPEN, GdTokenType.LITERAL, GdTokenType.ERROR, GdTokenType.NEWLINE,
                GdTokenType.VAR, GdTokenType.IDENTIFIER, GdTokenType.EQUAL, GdTokenType.LITERAL, GdTokenType.NEWLINE,
                GdTokenType.EOF), typesOf(resumedLayout));

        // Non-ancestor mismatch `((]`: pops only the inner `(`, keeping outer `(` multiline active.
        var nestedNonAncestor = tokenize("((]");
        assertEquals(List.of(GdTokenType.PARENTHESIS_OPEN, GdTokenType.PARENTHESIS_OPEN, GdTokenType.ERROR,
                GdTokenType.EOF), typesOf(nestedNonAncestor));
        assertEquals("Closing \"]\" doesn't match the opening \"(\".", nestedNonAncestor.get(2).errorMessage());

        var keepsOuterMultiline = tokenize("((]\n1)");
        assertEquals(List.of(GdTokenType.PARENTHESIS_OPEN, GdTokenType.PARENTHESIS_OPEN, GdTokenType.ERROR,
                GdTokenType.LITERAL, GdTokenType.PARENTHESIS_CLOSE, GdTokenType.NEWLINE, GdTokenType.EOF),
                typesOf(keepsOuterMultiline));
        assertEquals("Closing \"]\" doesn't match the opening \"(\".", keepsOuterMultiline.get(2).errorMessage());

        // Ancestor mismatch `([)]`: `)` names the skipped `[` and unwinds back through matching `(`.
        var ancestor = tokenize("([)]");
        assertEquals(List.of(GdTokenType.PARENTHESIS_OPEN, GdTokenType.BRACKET_OPEN,
                GdTokenType.ERROR, GdTokenType.ERROR, GdTokenType.NEWLINE, GdTokenType.EOF), typesOf(ancestor));
        assertEquals("Closing \")\" skips unclosed \"[\".", ancestor.get(2).errorMessage());
        assertEquals("Closing \"]\" doesn't have an opening counterpart.", ancestor.get(3).errorMessage());

        // Multi-opener ancestor mismatch `([{)`: `)` names every skipped opener in opening order.
        var multiSkipped = tokenize("([{)");
        assertEquals(List.of(GdTokenType.PARENTHESIS_OPEN, GdTokenType.BRACKET_OPEN,
                GdTokenType.BRACE_OPEN, GdTokenType.ERROR, GdTokenType.NEWLINE, GdTokenType.EOF),
                typesOf(multiSkipped));
        assertEquals("Closing \")\" skips unclosed \"[\", \"{\".", multiSkipped.get(3).errorMessage());

        // Deep non-ancestor mismatch `([}])`: `}` pops `[`, leaving `(` for `]`, leaving empty for `)`.
        var deep = tokenize("([}])");
        assertEquals(List.of(GdTokenType.PARENTHESIS_OPEN, GdTokenType.BRACKET_OPEN, GdTokenType.ERROR,
                GdTokenType.ERROR, GdTokenType.ERROR, GdTokenType.NEWLINE, GdTokenType.EOF), typesOf(deep));
        assertEquals("Closing \"}\" doesn't match the opening \"[\".", deep.get(2).errorMessage());
        assertEquals("Closing \"]\" doesn't match the opening \"(\".", deep.get(3).errorMessage());
        assertEquals("Closing \")\" doesn't have an opening counterpart.", deep.get(4).errorMessage());
    }

    @Test
    void managedTokenizerAlwaysEmitsRealCloserTokensWithoutLexicalCloserErrors() {
        var orphan = tokenizeManaged(")");
        assertEquals(List.of(GdTokenType.PARENTHESIS_CLOSE, GdTokenType.NEWLINE, GdTokenType.EOF),
                typesOf(orphan));
        assertTrue(errorsOf(orphan).isEmpty());

        var nonAncestor = tokenizeManaged("[)");
        assertEquals(List.of(GdTokenType.BRACKET_OPEN, GdTokenType.PARENTHESIS_CLOSE,
                GdTokenType.NEWLINE, GdTokenType.EOF), typesOf(nonAncestor));
        assertTrue(errorsOf(nonAncestor).isEmpty());

        var ancestor = tokenizeManaged("([)]");
        assertEquals(List.of(GdTokenType.PARENTHESIS_OPEN, GdTokenType.BRACKET_OPEN,
                GdTokenType.PARENTHESIS_CLOSE, GdTokenType.BRACKET_CLOSE, GdTokenType.NEWLINE, GdTokenType.EOF),
                typesOf(ancestor));
        assertTrue(errorsOf(ancestor).isEmpty());

        var deep = tokenizeManaged("([}])");
        assertEquals(List.of(GdTokenType.PARENTHESIS_OPEN, GdTokenType.BRACKET_OPEN, GdTokenType.BRACE_CLOSE,
                GdTokenType.BRACKET_CLOSE, GdTokenType.PARENTHESIS_CLOSE, GdTokenType.NEWLINE, GdTokenType.EOF),
                typesOf(deep));
        assertTrue(errorsOf(deep).isEmpty());

        var multiSkipped = tokenizeManaged("([{)");
        assertEquals(List.of(GdTokenType.PARENTHESIS_OPEN, GdTokenType.BRACKET_OPEN, GdTokenType.BRACE_OPEN,
                GdTokenType.PARENTHESIS_CLOSE, GdTokenType.NEWLINE, GdTokenType.EOF), typesOf(multiSkipped));
        assertTrue(errorsOf(multiSkipped).isEmpty());
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
    void newlineAndWhitespaceOnlyFilesProduceOnlyEof() {
        assertTypes("", GdTokenType.EOF);
        assertTypes("\n", GdTokenType.EOF);
        assertTypes("\n\n", GdTokenType.EOF);
        assertTypes("\r\n", GdTokenType.EOF);
        assertTypes("   \t  ", GdTokenType.EOF);

        var eof = tokenize("\n\n").getFirst();
        assertEquals(2, eof.startByte());
        assertEquals(2, eof.endByte());
        assertEquals(3, eof.startLine());
        assertEquals(1, eof.startColumn());
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
        var eof = tokenizer.scan();
        assertEquals(GdTokenType.EOF, eof.type());
        assertEquals(3, eof.startLine());
        assertEquals(11, eof.startColumn());
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
                GdTokenType.NEWLINE, GdTokenType.EOF), types);
        assertEquals(2, secondLiteralLine);
        assertEquals(List.of(2), tokenizer.getContinuationLines());
        assertTypes("\\\n", GdTokenType.EOF);
    }

    @Test
    void continuationSkipsCommentLines() {
        // Whitespace and comment lines after `\` are skipped without emitting NEWLINE.
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
                GdTokenType.PLUS, GdTokenType.LITERAL, GdTokenType.NEWLINE, GdTokenType.EOF), types);
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

    private static void assertSpan(GdToken token, int startByte, int endByte,
                                   int startLine, int startColumn, int endLine, int endColumn) {
        assertEquals(startByte, token.startByte(), () -> "startByte of " + token);
        assertEquals(endByte, token.endByte(), () -> "endByte of " + token);
        assertEquals(startLine, token.startLine(), () -> "startLine of " + token);
        assertEquals(startColumn, token.startColumn(), () -> "startColumn of " + token);
        assertEquals(endLine, token.endLine(), () -> "endLine of " + token);
        assertEquals(endColumn, token.endColumn(), () -> "endColumn of " + token);
    }

    // --- Multiline mode -----------------------------------------------------------------------

    @Test
    void multilineModeSuppressesNewlinesAndIndentation() {
        // Standalone mode suppresses NEWLINE/INDENT/DEDENT while parenStack is non-empty and
        // resumes normal layout tracking once brackets close.
        assertTypes("(1 +\n 2)",
                GdTokenType.PARENTHESIS_OPEN, GdTokenType.LITERAL, GdTokenType.PLUS,
                GdTokenType.LITERAL, GdTokenType.PARENTHESIS_CLOSE, GdTokenType.NEWLINE,
                GdTokenType.EOF);

        var bracketed = tokenize("var xs = [\n\t\t1,\n\t2\n]\nvar ys = {\n  \"a\":\n\t1\n}\n");
        assertTrue(errorsOf(bracketed).isEmpty());
        assertFalse(typesOf(bracketed).contains(GdTokenType.INDENT));
        assertFalse(typesOf(bracketed).contains(GdTokenType.DEDENT));

        var resumed = tokenize("if a:\n\tvar xs = [\n\t\t1\n\t]\n\treturn xs\n");
        assertTrue(errorsOf(resumed).isEmpty());
        assertEquals(1, resumed.stream().filter(t -> t.type() == GdTokenType.INDENT).count());
        assertEquals(1, resumed.stream().filter(t -> t.type() == GdTokenType.DEDENT).count());

        // Misaligned dedent outside brackets still reports an indentation error.
        var badAfterBracket = tokenize("var xs = [\n\t1\n]\nif a:\n    pass\n  pass\n");
        assertEquals(List.of("Unindent doesn't match the previous indentation level."),
                errorsOf(badAfterBracket).stream().map(GdToken::errorMessage).toList());
    }

    @Test
    void managedExplicitMultilineModeOverridesParenStackForLambdaSuites() {
        // Simulates parser-driven multiline control around `foo(func():\n\tvar a = 1\n\treturn a\n)`.
        var source = "foo(func():\n\tvar a = 1\n\treturn a\n)";
        var tokenizer = GdTokenizer.managed(source);
        var types = new ArrayList<GdTokenType>();
        var inCallArgs = false;
        var inLambdaSignature = false;
        var inLambdaBody = false;
        for (var i = 0; i < 10_000; i++) {
            var token = tokenizer.scan();
            types.add(token.type());
            if (token.type() == GdTokenType.PARENTHESIS_OPEN) {
                if (!inCallArgs) {
                    inCallArgs = true;
                    tokenizer.setMultilineMode(true);
                } else {
                    inLambdaSignature = true;
                    tokenizer.setMultilineMode(true);
                }
            } else if (token.type() == GdTokenType.FUNC) {
                inLambdaBody = true;
                tokenizer.setMultilineMode(false);
            } else if (token.type() == GdTokenType.PARENTHESIS_CLOSE) {
                if (inLambdaSignature) {
                    inLambdaSignature = false;
                    tokenizer.setMultilineMode(false);
                } else if (inLambdaBody) {
                    inLambdaBody = false;
                    tokenizer.setMultilineMode(false);
                }
            }
            if (token.type() == GdTokenType.EOF) {
                break;
            }
        }
        assertTrue(types.contains(GdTokenType.INDENT), () -> "expected INDENT inside lambda body: " + types);
        assertTrue(types.contains(GdTokenType.DEDENT), () -> "expected DEDENT after lambda body: " + types);
        assertEquals(List.of(
                GdTokenType.IDENTIFIER, GdTokenType.PARENTHESIS_OPEN,
                GdTokenType.FUNC, GdTokenType.PARENTHESIS_OPEN, GdTokenType.PARENTHESIS_CLOSE,
                GdTokenType.COLON, GdTokenType.NEWLINE,
                GdTokenType.INDENT,
                GdTokenType.VAR, GdTokenType.IDENTIFIER, GdTokenType.EQUAL, GdTokenType.LITERAL, GdTokenType.NEWLINE,
                GdTokenType.RETURN, GdTokenType.IDENTIFIER, GdTokenType.NEWLINE,
                GdTokenType.DEDENT,
                GdTokenType.PARENTHESIS_CLOSE, GdTokenType.NEWLINE, GdTokenType.EOF), types);
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
        assertSpan(tokens.get(4), 10, 11, 1, 11, 2, 1);
        assertSpan(tokens.get(5), 11, 14, 2, 1, 2, 4);
        assertSpan(tokens.get(9), 21, 22, 2, 11, 3, 1);
        assertSpan(tokens.getLast(), 22, 22, 3, 1, 3, 1);
    }

    @Test
    void escapedCrlfInsideStringAdvancesLineOnce() {
        var tokens = tokenize("\"a\\\r\nb\"");
        assertEquals(List.of(GdTokenType.LITERAL, GdTokenType.NEWLINE, GdTokenType.EOF), typesOf(tokens));
        assertTrue(errorsOf(tokens).isEmpty());
        assertSpan(tokens.get(0), 0, 7, 1, 1, 2, 3);
        assertSpan(tokens.get(1), 7, 7, 2, 3, 2, 3);
        assertSpan(tokens.get(2), 7, 7, 2, 3, 2, 3);
    }

    @Test
    void strayCarriageReturnReportsErrorAndScanningContinues() {
        var tokens = tokenize("a\rb");
        assertEquals(List.of(GdTokenType.IDENTIFIER, GdTokenType.ERROR, GdTokenType.IDENTIFIER,
                GdTokenType.NEWLINE, GdTokenType.EOF), typesOf(tokens));
        var error = errorsOf(tokens).getFirst();
        assertEquals("Stray carriage return character in source code.", error.errorMessage());
        assertSpan(error, 1, 2, 1, 2, 1, 3);
        assertSpan(tokens.get(2), 2, 3, 1, 3, 1, 4);

        var bolStray = tokenize("\ra");
        assertEquals(List.of(GdTokenType.ERROR, GdTokenType.IDENTIFIER, GdTokenType.NEWLINE, GdTokenType.EOF),
                typesOf(bolStray));
        assertSpan(bolStray.get(0), 0, 1, 1, 1, 1, 2);
        assertSpan(bolStray.get(1), 1, 2, 1, 2, 1, 3);

        var indentedStray = tokenize("  \ra");
        assertEquals(List.of(GdTokenType.ERROR, GdTokenType.INDENT, GdTokenType.IDENTIFIER,
                GdTokenType.NEWLINE, GdTokenType.DEDENT, GdTokenType.EOF), typesOf(indentedStray));
        assertSpan(indentedStray.get(0), 2, 3, 1, 3, 1, 4);
    }

    // --- EOF coordinate anchoring -----------------------------------------------------------------

    @Test
    void eofSynthesizedNewlineAnchorsToPhysicalEofWithoutTrailingNewline() {
        var tokens = tokenize("pass");
        assertEquals(List.of(GdTokenType.PASS, GdTokenType.NEWLINE, GdTokenType.EOF), typesOf(tokens));
        assertSpan(tokens.get(0), 0, 4, 1, 1, 1, 5);
        assertSpan(tokens.get(1), 4, 4, 1, 5, 1, 5);
        assertSpan(tokens.get(2), 4, 4, 1, 5, 1, 5);
    }

    @Test
    void trailingNewlineAdvancesLineOnceAtEof() {
        var tokens = tokenize("pass\n");
        assertEquals(List.of(GdTokenType.PASS, GdTokenType.NEWLINE, GdTokenType.EOF), typesOf(tokens));
        assertSpan(tokens.get(0), 0, 4, 1, 1, 1, 5);
        assertSpan(tokens.get(1), 4, 5, 1, 5, 2, 1);
        assertSpan(tokens.get(2), 5, 5, 2, 1, 2, 1);
    }

    @Test
    void pendingDedentsFlushBeforeEofAndAnchorToPhysicalEof() {
        var noNewline = tokenize("if a:\n\tpass");
        assertEquals(List.of(GdTokenType.IF, GdTokenType.IDENTIFIER, GdTokenType.COLON,
                GdTokenType.NEWLINE, GdTokenType.INDENT, GdTokenType.PASS, GdTokenType.NEWLINE,
                GdTokenType.DEDENT, GdTokenType.EOF), typesOf(noNewline));
        assertSpan(noNewline.get(6), 11, 11, 2, 6, 2, 6);
        assertSpan(noNewline.get(7), 11, 11, 2, 6, 2, 6);
        assertSpan(noNewline.get(8), 11, 11, 2, 6, 2, 6);

        var withNewline = tokenize("if a:\n\tpass\n");
        assertEquals(List.of(GdTokenType.IF, GdTokenType.IDENTIFIER, GdTokenType.COLON,
                GdTokenType.NEWLINE, GdTokenType.INDENT, GdTokenType.PASS, GdTokenType.NEWLINE,
                GdTokenType.DEDENT, GdTokenType.EOF), typesOf(withNewline));
        assertSpan(withNewline.get(6), 11, 12, 2, 6, 3, 1);
        assertSpan(withNewline.get(7), 12, 12, 3, 1, 3, 1);
        assertSpan(withNewline.get(8), 12, 12, 3, 1, 3, 1);

        var trailingComment = tokenize("if a:\n\tpass\n\t# c");
        assertEquals(List.of(GdTokenType.IF, GdTokenType.IDENTIFIER, GdTokenType.COLON,
                GdTokenType.NEWLINE, GdTokenType.INDENT, GdTokenType.PASS, GdTokenType.NEWLINE,
                GdTokenType.DEDENT, GdTokenType.EOF), typesOf(trailingComment));
        assertSpan(trailingComment.get(6), 11, 12, 2, 6, 3, 1);
        assertSpan(trailingComment.get(7), 16, 16, 3, 5, 3, 5);
        assertSpan(trailingComment.get(8), 16, 16, 3, 5, 3, 5);
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
        var tokens = tokenize("\\\n".repeat(10_000) + "x");
        var types = typesOf(tokens);
        assertEquals(List.of(GdTokenType.IDENTIFIER, GdTokenType.NEWLINE, GdTokenType.EOF),
                types.subList(types.size() - 3, types.size()));
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

        assertSpan(tokens.get(3), 8, utf8Length, 1, 9, 2, 7);
        assertEquals(GdLiteralKind.STRING, tokens.get(3).literalKind());
        assertSpan(tokens.get(4), utf8Length, utf8Length, 2, 7, 2, 7);
        assertSpan(tokens.get(5), utf8Length, utf8Length, 2, 7, 2, 7);
    }

    @Test
    void unpairedUtf16SurrogatesCountAsOneReplacementByte() {
        var tokens = tokenize("a\uD800b");
        assertEquals(List.of(GdTokenType.IDENTIFIER, GdTokenType.ERROR, GdTokenType.IDENTIFIER,
                GdTokenType.NEWLINE, GdTokenType.EOF), typesOf(tokens));
        assertTrue(errorsOf(tokens).getFirst().errorMessage().contains("(U+D800)"));
        assertSpan(tokens.get(1), 1, 2, 1, 2, 1, 3);
        assertSpan(tokens.get(2), 2, 3, 1, 3, 1, 4);
    }

    // --- Token position conventions -----------------------------------------------------------------

    @Test
    void tokenByteOffsetsAndCodePointColumnsStayConsistent() {
        var tokens = tokenize("var x = 1");
        assertSpan(tokens.get(0), 0, 3, 1, 1, 1, 4);
        assertSpan(tokens.get(1), 4, 5, 1, 5, 1, 6);
        assertSpan(tokens.get(2), 6, 7, 1, 7, 1, 8);
        assertSpan(tokens.get(3), 8, 9, 1, 9, 1, 10);
        assertSpan(tokens.get(4), 9, 9, 1, 10, 1, 10);
        assertSpan(tokens.get(5), 9, 9, 1, 10, 1, 10);
    }

    @Test
    void codePointColumnsDistinguishCjkEmojiAndTabsFromUtf8Bytes() {
        // CJK (`变量` = 2 code points, 6 UTF-8 bytes) and emoji (`😀` = 1 code point, 4 UTF-8 bytes).
        var source = "\tvar 变量 = \"😀\"";
        var tokens = tokenize(source);
        assertEquals(List.of(GdTokenType.INDENT, GdTokenType.VAR, GdTokenType.IDENTIFIER,
                GdTokenType.EQUAL, GdTokenType.LITERAL, GdTokenType.NEWLINE,
                GdTokenType.DEDENT, GdTokenType.EOF), typesOf(tokens));
        assertSpan(tokens.get(0), 0, 1, 1, 1, 1, 2);
        assertSpan(tokens.get(1), 1, 4, 1, 2, 1, 5);
        assertSpan(tokens.get(2), 5, 11, 1, 6, 1, 8);
        assertSpan(tokens.get(3), 12, 13, 1, 9, 1, 10);
        assertSpan(tokens.get(4), 14, 20, 1, 11, 1, 14);
        assertSpan(tokens.get(5), 20, 20, 1, 14, 1, 14);
        assertSpan(tokens.get(6), 20, 20, 1, 14, 1, 14);
        assertSpan(tokens.get(7), 20, 20, 1, 14, 1, 14);
    }

    @Test
    void longSingleLineKeepsExactPhysicalEndColumnAndEof() {
        var ident = "a".repeat(4100);
        var source = "var " + ident;
        var tokens = tokenize(source);
        assertEquals(List.of(GdTokenType.VAR, GdTokenType.IDENTIFIER, GdTokenType.NEWLINE, GdTokenType.EOF),
                typesOf(tokens));
        assertSpan(tokens.get(1), 4, 4104, 1, 5, 1, 4105);
        assertSpan(tokens.get(2), 4104, 4104, 1, 4105, 1, 4105);
        assertSpan(tokens.get(3), 4104, 4104, 1, 4105, 1, 4105);
    }

    @Test
    void lexicalErrorsProduceValidPhysicalCoordinatesAndByteRanges() {
        var errorInputs = List.of(
                "\u0001",
                "\u2003",
                "0x",
                "1__2",
                "1.2.3",
                "12abc",
                "\"\\q\"",
                "\"\\uD800\"",
                "\"abc",
                "if a:\n    pass\n  pass\n",
                "[)",
                ")"
        );
        for (var source : errorInputs) {
            var utf8Length = source.getBytes(StandardCharsets.UTF_8).length;
            var errors = errorsOf(tokenize(source));
            assertFalse(errors.isEmpty(), () -> "expected error for: " + source);
            for (var error : errors) {
                assertTrue(error.startByte() >= 0 && error.startByte() <= error.endByte()
                        && error.endByte() <= utf8Length, () -> "invalid bytes for " + error);
                assertTrue(error.startLine() >= 1 && error.startLine() <= error.endLine(),
                        () -> "invalid lines for " + error);
                assertTrue(error.startColumn() >= 1 && error.endColumn() >= 1,
                        () -> "invalid columns for " + error);
                assertTrue(error.startLine() < error.endLine() || error.startColumn() <= error.endColumn(),
                        () -> "inverted coordinates for " + error);
            }
        }
    }

    @Test
    void physicalCoordinatesAlwaysMatchTokenByteRangesAcrossValidAndErrorInputs() {
        var inputs = List.of(
                "pass\n",
                "pass\r\n",
                "pass",
                "var x = 1",
                "\"\"\"a\nb\"\"\"",
                "0x",
                "0x_FF",
                "1__2",
                "1.2.3",
                "1e",
                "1e\n",
                "\"a\\qb\"",
                "\"\\u123g\"",
                "\"\\u123\n\"",
                "\"\\uD800x\"",
                "\"\\uD800\\uD800\"",
                "\"\\uDC00\"",
                "\"a\u200Eb\"",
                "var x = \"\\\n",
                "var x = \"\\\r\n",
                "a\rb",
                "pass\n\r \n",
                "if a:\n\tpass",
                "if a:\n\tpass\n",
                "if a:\n\t\tpass\n\tpass",
                "func f():\n\t\tpass\n\tpass\n",
                "if a:\n    pass\n  pass\n",
                "if a:\n\t  pass\n",
                "if a:\n\tpass\nif b:\n  pass\n",
                "\tvar 变量 = \"😀\"",
                "var " + "a".repeat(4100),
                "([)]",
                "((]",
                "[)",
                ")"
        );
        for (var source : inputs) {
            assertPhysicalCoordinatesMatchBytes(source, tokenize(source));
            assertPhysicalCoordinatesMatchBytes(source, tokenizeManaged(source));
        }

        var lfNewline = tokenize("pass\n").get(1);
        assertEquals(GdTokenType.NEWLINE, lfNewline.type());
        assertSpan(lfNewline, 4, 5, 1, 5, 2, 1);

        var crlfNewline = tokenize("pass\r\n").get(1);
        assertEquals(GdTokenType.NEWLINE, crlfNewline.type());
        assertSpan(crlfNewline, 5, 6, 1, 6, 2, 1);
    }

    @Test
    void eofContinuationAndIndentedBlocksEmitNewlineBeforeDedent() {
        for (var source : List.of("func f():\n\tpass\\\n", "func f():\n\tpass\\\r\n")) {
            var tokens = tokenize(source);
            assertEquals(List.of(
                    GdTokenType.FUNC, GdTokenType.IDENTIFIER, GdTokenType.PARENTHESIS_OPEN,
                    GdTokenType.PARENTHESIS_CLOSE, GdTokenType.COLON, GdTokenType.NEWLINE,
                    GdTokenType.INDENT, GdTokenType.PASS, GdTokenType.NEWLINE,
                    GdTokenType.DEDENT, GdTokenType.EOF), typesOf(tokens), () -> "source: " + source);
            assertTrue(errorsOf(tokens).isEmpty());
        }

        for (var source : List.of("if a:\n\tpass\n", "if a:\n\tpass", "if a:\n\tpass\n\t# comment")) {
            var types = typesOf(tokenize(source));
            assertEquals(List.of(
                    GdTokenType.IF, GdTokenType.IDENTIFIER, GdTokenType.COLON, GdTokenType.NEWLINE,
                    GdTokenType.INDENT, GdTokenType.PASS, GdTokenType.NEWLINE,
                    GdTokenType.DEDENT, GdTokenType.EOF), types, () -> "source: " + source);
        }
    }

    @Test
    void unterminatedStringAfterEscapedNewlineEndsAtPhysicalLineTwoColumnOne() {
        for (var source : List.of("var x = \"\\\n", "var x = \"\\\r\n")) {
            var tokens = tokenize(source);
            var utf8Length = source.getBytes(StandardCharsets.UTF_8).length;
            assertEquals(List.of(GdTokenType.VAR, GdTokenType.IDENTIFIER, GdTokenType.EQUAL,
                    GdTokenType.ERROR, GdTokenType.NEWLINE, GdTokenType.EOF), typesOf(tokens));
            var error = tokens.get(3);
            assertEquals("Unterminated string.", error.errorMessage());
            assertSpan(error, 8, utf8Length, 1, 9, 2, 1);
            assertSpan(tokens.getLast(), utf8Length, utf8Length, 2, 1, 2, 1);
        }
    }

    @Test
    void strayCarriageReturnInTrailingWhitespaceLineDoesNotCreateSpuriousEofNewline() {
        var trailingCrLine = tokenize("pass\n\r \n");
        assertEquals(List.of(GdTokenType.PASS, GdTokenType.NEWLINE, GdTokenType.ERROR, GdTokenType.EOF),
                typesOf(trailingCrLine));
        assertEquals("Stray carriage return character in source code.", trailingCrLine.get(2).errorMessage());

        var inlineCrAtEof = tokenize("a\r");
        assertEquals(List.of(GdTokenType.IDENTIFIER, GdTokenType.ERROR, GdTokenType.NEWLINE, GdTokenType.EOF),
                typesOf(inlineCrAtEof));
        assertEquals("Stray carriage return character in source code.", inlineCrAtEof.get(1).errorMessage());

        for (var source : List.of(
                "func f():\n\tpass\n\t\t\r",
                "func f():\n\tpass\n\t\t\r ",
                "func f():\n\tpass\n\t\t\r \n")) {
            var tokens = tokenize(source);
            assertEquals(List.of(
                    GdTokenType.FUNC, GdTokenType.IDENTIFIER, GdTokenType.PARENTHESIS_OPEN,
                    GdTokenType.PARENTHESIS_CLOSE, GdTokenType.COLON, GdTokenType.NEWLINE,
                    GdTokenType.INDENT, GdTokenType.PASS, GdTokenType.NEWLINE,
                    GdTokenType.ERROR, GdTokenType.DEDENT, GdTokenType.EOF), typesOf(tokens), () -> "source: " + source);
            assertEquals(List.of("Stray carriage return character in source code."),
                    errorsOf(tokens).stream().map(GdToken::errorMessage).toList());
            assertPhysicalCoordinatesMatchBytes(source, tokens);
        }

        var leadingCrBeforeStatement = "if a:\n\tpass\n\rpass";
        var leadingCrTokens = tokenize(leadingCrBeforeStatement);
        assertEquals(List.of(
                GdTokenType.IF, GdTokenType.IDENTIFIER, GdTokenType.COLON, GdTokenType.NEWLINE,
                GdTokenType.INDENT, GdTokenType.PASS, GdTokenType.NEWLINE,
                GdTokenType.ERROR, GdTokenType.DEDENT, GdTokenType.PASS, GdTokenType.NEWLINE,
                GdTokenType.EOF), typesOf(leadingCrTokens));
        assertEquals(List.of("Stray carriage return character in source code."),
                errorsOf(leadingCrTokens).stream().map(GdToken::errorMessage).toList());
        assertPhysicalCoordinatesMatchBytes(leadingCrBeforeStatement, leadingCrTokens);
    }

    @Test
    void unmatchedCloserStormScalesLinearlyInStandaloneAndManagedModes() {
        var count = 20_000;
        var source = "[".repeat(count) + ")".repeat(count);

        var standalone = tokenize(source);
        assertEquals(count * 2 + 2, standalone.size());
        assertEquals(GdTokenType.BRACKET_OPEN, standalone.getFirst().type());
        assertEquals(GdTokenType.ERROR, standalone.get(count).type());
        assertEquals("Closing \")\" doesn't match the opening \"[\".", standalone.get(count).errorMessage());
        assertEquals(GdTokenType.NEWLINE, standalone.get(count * 2).type());
        assertEquals(GdTokenType.EOF, standalone.getLast().type());

        var managed = tokenizeManaged(source);
        assertEquals(count * 2 + 2, managed.size());
        assertTrue(errorsOf(managed).isEmpty());
        assertEquals(GdTokenType.BRACKET_OPEN, managed.getFirst().type());
        assertEquals(GdTokenType.PARENTHESIS_CLOSE, managed.get(count).type());
        assertEquals(GdTokenType.NEWLINE, managed.get(count * 2).type());
        assertEquals(GdTokenType.EOF, managed.getLast().type());
    }

    @Test
    void reconcileLayoutForReplayEmitsNewlineAndIndentOrDedentAcrossMultilineEscape() {
        // Deeper line after suppressed multiline newline replays NEWLINE + INDENT (`func f(a, b:\n\tpass`).
        var indentTokenizer = GdTokenizer.managed("func f(a, b:\n\tpass\n");
        assertEquals(GdTokenType.FUNC, indentTokenizer.scan().type());
        assertEquals(GdTokenType.IDENTIFIER, indentTokenizer.scan().type());
        indentTokenizer.setMultilineMode(true);
        assertEquals(GdTokenType.PARENTHESIS_OPEN, indentTokenizer.scan().type());
        assertEquals(GdTokenType.IDENTIFIER, indentTokenizer.scan().type()); // a
        assertEquals(GdTokenType.COMMA, indentTokenizer.scan().type());
        assertEquals(GdTokenType.IDENTIFIER, indentTokenizer.scan().type()); // b
        assertEquals(GdTokenType.COLON, indentTokenizer.scan().type());
        var passLookahead = indentTokenizer.scan(); // crosses suppressed `\n\t` -> PASS
        assertEquals(GdTokenType.PASS, passLookahead.type());
        indentTokenizer.setMultilineMode(false); // exit multiline without consuming `)`
        var replayedIndent = indentTokenizer.reconcileLayoutForReplay(passLookahead);
        assertEquals(List.of(GdTokenType.NEWLINE, GdTokenType.INDENT), typesOf(replayedIndent));
        // Draining the rest of the stream balances indentStack cleanly to EOF.
        assertEquals(GdTokenType.NEWLINE, indentTokenizer.scan().type());
        assertEquals(GdTokenType.DEDENT, indentTokenizer.scan().type());
        assertEquals(GdTokenType.EOF, indentTokenizer.scan().type());
        assertTrue(indentTokenizer.hasBalancedLayoutState());

        // Shallower line across CRLF with comment-only lines replays NEWLINE + DEDENT.
        var dedentTokenizer = GdTokenizer.managed("func f():\r\n\tprint(1\r\n# trivia\r\nvar after = 2\r\n");
        while (dedentTokenizer.scan().type() != GdTokenType.IDENTIFIER) {
            // advance until `print`
        }
        while (dedentTokenizer.scan().type() != GdTokenType.IDENTIFIER) {
            // advance until `print`
        }
        dedentTokenizer.setMultilineMode(true);
        assertEquals(GdTokenType.PARENTHESIS_OPEN, dedentTokenizer.scan().type());
        assertEquals(GdTokenType.LITERAL, dedentTokenizer.scan().type()); // 1
        var varLookahead = dedentTokenizer.scan(); // crosses `\r\n# trivia\r\n` -> VAR
        assertEquals(GdTokenType.VAR, varLookahead.type());
        dedentTokenizer.setMultilineMode(false);
        var replayedDedent = dedentTokenizer.reconcileLayoutForReplay(varLookahead);
        assertEquals(List.of(GdTokenType.NEWLINE, GdTokenType.DEDENT), typesOf(replayedDedent));
        assertTrue(replayedDedent.getFirst().startByte() < varLookahead.startByte());
        while (dedentTokenizer.scan().type() != GdTokenType.EOF) {
            // drain to EOF
        }
        assertTrue(dedentTokenizer.hasBalancedLayoutState());
    }

    @Test
    void reconcileLayoutForReplaySkipsNormalClosureAndSoftNewlines() {
        var tokenizer = GdTokenizer.managed("var x = (1\n\t+ 2)\n");
        while (tokenizer.scan().type() != GdTokenType.EQUAL) {
            // advance to `=`
        }
        tokenizer.setMultilineMode(true);
        assertEquals(GdTokenType.PARENTHESIS_OPEN, tokenizer.scan().type());
        assertEquals(GdTokenType.LITERAL, tokenizer.scan().type()); // 1
        assertEquals(GdTokenType.PLUS, tokenizer.scan().type()); // + (cleared suppressed newline on next scan)
        assertEquals(GdTokenType.LITERAL, tokenizer.scan().type()); // 2
        tokenizer.setMultilineMode(false);
        var closer = tokenizer.scan(); // `)`
        assertEquals(GdTokenType.PARENTHESIS_CLOSE, closer.type());
        assertEquals(List.of(), tokenizer.reconcileLayoutForReplay(closer));
        assertEquals(GdTokenType.NEWLINE, tokenizer.scan().type());
        assertEquals(GdTokenType.EOF, tokenizer.scan().type());
        assertTrue(tokenizer.hasBalancedLayoutState());
    }

    @Test
    void drainRestoredEofDedentsBalancesRestoredIndentStackAtEof() {
        var tokenizer = GdTokenizer.managed("func f():\n\tvar x = g(func(): pass");
        // Scan until `g(` (second `(`) so indentStack holds `[4]`, then isolate the lambda body.
        var openParens = 0;
        while (openParens < 2) {
            if (tokenizer.scan().type() == GdTokenType.PARENTHESIS_OPEN) {
                openParens++;
            }
        }
        tokenizer.pushExpressionIndentedBlock();
        while (tokenizer.scan().type() != GdTokenType.EOF) {
            // drain truncated lambda to EOF
        }
        tokenizer.popExpressionIndentedBlock();
        var closure = tokenizer.drainRestoredEofDedents();
        assertEquals(List.of(GdTokenType.NEWLINE, GdTokenType.DEDENT), typesOf(closure));
        assertTrue(tokenizer.hasBalancedLayoutState());
    }

    @Test
    void reconcileLayoutForReplayValidatesEscapedLineIndentationAndExemptsMultiline() {
        // Negative 1: wrong indentation character on an escaped line emits ERROR + NEWLINE.
        var wrongCharTok = GdTokenizer.managed("func f():\n\tvar x = [1,\n    var y = 1\n");
        while (wrongCharTok.scan().type() != GdTokenType.BRACKET_OPEN) {
            // advance to `[`
        }
        wrongCharTok.setMultilineMode(true);
        assertEquals(GdTokenType.LITERAL, wrongCharTok.scan().type());
        assertEquals(GdTokenType.COMMA, wrongCharTok.scan().type());
        var wrongCharLookahead = wrongCharTok.scan();
        assertEquals(GdTokenType.VAR, wrongCharLookahead.type());
        wrongCharTok.setMultilineMode(false);
        var wrongCharReplay = wrongCharTok.reconcileLayoutForReplay(wrongCharLookahead);
        assertEquals(List.of(GdTokenType.NEWLINE, GdTokenType.ERROR), typesOf(wrongCharReplay));
        assertTrue(wrongCharReplay.get(1).errorMessage().contains("space character for indentation"));

        // Negative 2: mixed tabs and spaces on an escaped line emits ERROR + NEWLINE (+ INDENT for width 5 > 4).
        var mixedTok = GdTokenizer.managed("func f():\n\tvar x = [1,\n\t var y = 1\n");
        while (mixedTok.scan().type() != GdTokenType.BRACKET_OPEN) {
            // advance to `[`
        }
        mixedTok.setMultilineMode(true);
        assertEquals(GdTokenType.LITERAL, mixedTok.scan().type());
        assertEquals(GdTokenType.COMMA, mixedTok.scan().type());
        var mixedLookahead = mixedTok.scan();
        assertEquals(GdTokenType.VAR, mixedLookahead.type());
        mixedTok.setMultilineMode(false);
        var mixedReplay = mixedTok.reconcileLayoutForReplay(mixedLookahead);
        assertEquals(List.of(GdTokenType.NEWLINE, GdTokenType.ERROR, GdTokenType.INDENT), typesOf(mixedReplay));
        assertTrue(mixedReplay.get(1).errorMessage().contains("Mixed use of tabs and spaces"));

        // Negative 3: mismatched dedent on an escaped line emits ERROR + NEWLINE + DEDENT.
        var mismatchTok = GdTokenizer.managed("func f():\n    if true:\n        var x = [1,\n      var y = 1\n");
        while (mismatchTok.scan().type() != GdTokenType.BRACKET_OPEN) {
            // advance to `[`
        }
        mismatchTok.setMultilineMode(true);
        assertEquals(GdTokenType.LITERAL, mismatchTok.scan().type());
        assertEquals(GdTokenType.COMMA, mismatchTok.scan().type());
        var mismatchLookahead = mismatchTok.scan();
        assertEquals(GdTokenType.VAR, mismatchLookahead.type());
        mismatchTok.setMultilineMode(false);
        var mismatchReplay = mismatchTok.reconcileLayoutForReplay(mismatchLookahead);
        assertEquals(List.of(GdTokenType.NEWLINE, GdTokenType.DEDENT, GdTokenType.ERROR), typesOf(mismatchReplay));
        assertTrue(mismatchReplay.get(2).errorMessage().contains("Unindent doesn't match"));

        // Positive control: closed multiline line using spaces inside a tab file stays error-free.
        var closedMultiline = GdTokenizer.managed("func f():\n\tvar x = [\n    1\n\t]\n");
        while (closedMultiline.scan().type() != GdTokenType.BRACKET_OPEN) {
            // advance to `[`
        }
        closedMultiline.setMultilineMode(true);
        var lit = closedMultiline.scan();
        assertEquals(GdTokenType.LITERAL, lit.type());
        var closer = closedMultiline.scan();
        assertEquals(GdTokenType.BRACKET_CLOSE, closer.type());
        closedMultiline.setMultilineMode(false);
        assertEquals(GdTokenType.NEWLINE, closedMultiline.scan().type());
        assertEquals(GdTokenType.DEDENT, closedMultiline.scan().type());
        assertEquals(GdTokenType.EOF, closedMultiline.scan().type());
        assertTrue(closedMultiline.hasBalancedLayoutState());
    }

    private static void assertPhysicalCoordinatesMatchBytes(String source, List<GdToken> tokens) {
        var utf8Length = source.getBytes(StandardCharsets.UTF_8).length;
        var lines = new int[utf8Length + 1];
        var columns = new int[utf8Length + 1];
        var line = 1;
        var column = 1;
        var byteOffset = 0;
        lines[0] = line;
        columns[0] = column;
        for (var i = 0; i < source.length(); ) {
            var codePoint = source.codePointAt(i);
            i += Character.charCount(codePoint);
            byteOffset += SourceTextIndex.utf8Length(codePoint);
            if (codePoint == '\n') {
                line++;
                column = 1;
            } else {
                column++;
            }
            lines[byteOffset] = line;
            columns[byteOffset] = column;
        }
        for (var token : tokens) {
            assertTrue(token.startByte() >= 0 && token.startByte() <= token.endByte()
                    && token.endByte() <= utf8Length, () -> "out-of-bounds byte span: " + token);
            assertTrue(lines[token.startByte()] > 0, () -> "startByte not on code-point boundary: " + token);
            assertTrue(lines[token.endByte()] > 0, () -> "endByte not on code-point boundary: " + token);
            assertEquals(lines[token.startByte()], token.startLine(), () -> "startLine mismatch: " + token);
            assertEquals(columns[token.startByte()], token.startColumn(), () -> "startColumn mismatch: " + token);
            assertEquals(lines[token.endByte()], token.endLine(), () -> "endLine mismatch: " + token);
            assertEquals(columns[token.endByte()], token.endColumn(), () -> "endColumn mismatch: " + token);
        }
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
