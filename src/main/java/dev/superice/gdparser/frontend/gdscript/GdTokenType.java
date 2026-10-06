package dev.superice.gdparser.frontend.gdscript;

/// Token types aligned one-to-one with Godot `GDScriptTokenizer::Token::Type`
/// (vendor/godot/gdscript_tokenizer.h), keeping the same declaration order so the two
/// implementations can be cross-referenced mechanically.
///
/// Naming deviations: Godot's `TK_CONST`/`TK_IN`/`TK_VOID`/`TK_EOF` dodge C macro
/// conflicts and are named `CONST`/`IN`/`VOID`/`EOF` here; Godot's `TK_MAX` sentinel is
/// not a real token type and is omitted.
///
/// `displayName` mirrors Godot's `token_names` table (vendor/godot/gdscript_tokenizer.cpp)
/// and is intended for diagnostic messages.
public enum GdTokenType {
    EMPTY("Empty"),
    // Basic
    ANNOTATION("Annotation"),
    IDENTIFIER("Identifier"),
    LITERAL("Literal"),
    // Comparison
    LESS("<"),
    LESS_EQUAL("<="),
    GREATER(">"),
    GREATER_EQUAL(">="),
    EQUAL_EQUAL("=="),
    BANG_EQUAL("!="),
    // Logical
    AND("and"),
    OR("or"),
    NOT("not"),
    AMPERSAND_AMPERSAND("&&"),
    PIPE_PIPE("||"),
    BANG("!"),
    // Bitwise
    AMPERSAND("&"),
    PIPE("|"),
    TILDE("~"),
    CARET("^"),
    LESS_LESS("<<"),
    GREATER_GREATER(">>"),
    // Math
    PLUS("+"),
    MINUS("-"),
    STAR("*"),
    STAR_STAR("**"),
    SLASH("/"),
    PERCENT("%"),
    // Assignment
    EQUAL("="),
    PLUS_EQUAL("+="),
    MINUS_EQUAL("-="),
    STAR_EQUAL("*="),
    STAR_STAR_EQUAL("**="),
    SLASH_EQUAL("/="),
    PERCENT_EQUAL("%="),
    LESS_LESS_EQUAL("<<="),
    GREATER_GREATER_EQUAL(">>="),
    AMPERSAND_EQUAL("&="),
    PIPE_EQUAL("|="),
    CARET_EQUAL("^="),
    // Control flow
    IF("if"),
    ELIF("elif"),
    ELSE("else"),
    FOR("for"),
    WHILE("while"),
    BREAK("break"),
    CONTINUE("continue"),
    PASS("pass"),
    RETURN("return"),
    MATCH("match"),
    WHEN("when"),
    // Keywords
    AS("as"),
    ASSERT("assert"),
    AWAIT("await"),
    BREAKPOINT("breakpoint"),
    CLASS("class"),
    CLASS_NAME("class_name"),
    CONST("const"),
    ENUM("enum"),
    EXTENDS("extends"),
    FUNC("func"),
    IN("in"),
    IS("is"),
    NAMESPACE("namespace"),
    PRELOAD("preload"),
    SELF("self"),
    SIGNAL("signal"),
    STATIC("static"),
    SUPER("super"),
    TRAIT("trait"),
    VAR("var"),
    VOID("void"),
    YIELD("yield"),
    // Punctuation
    BRACKET_OPEN("["),
    BRACKET_CLOSE("]"),
    BRACE_OPEN("{"),
    BRACE_CLOSE("}"),
    PARENTHESIS_OPEN("("),
    PARENTHESIS_CLOSE(")"),
    COMMA(","),
    SEMICOLON(";"),
    PERIOD("."),
    PERIOD_PERIOD(".."),
    PERIOD_PERIOD_PERIOD("..."),
    COLON(":"),
    DOLLAR("$"),
    FORWARD_ARROW("->"),
    UNDERSCORE("_"),
    // Whitespace
    NEWLINE("Newline"),
    INDENT("Indent"),
    DEDENT("Dedent"),
    // Constants
    CONST_PI("PI"),
    CONST_TAU("TAU"),
    CONST_INF("INF"),
    CONST_NAN("NaN"),
    // Error message improvement
    VCS_CONFLICT_MARKER("VCS conflict marker"),
    BACKTICK("`"),
    QUESTION_MARK("?"),
    // Special
    ERROR("Error"),
    EOF("End of file");

    private final String displayName;

    GdTokenType(String displayName) {
        this.displayName = displayName;
    }

    /// Display name aligned with Godot's `token_names` table entry for this type.
    public String displayName() {
        return displayName;
    }
}
