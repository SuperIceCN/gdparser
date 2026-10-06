package dev.superice.gdparser.frontend.gdscript;

import dev.superice.gdparser.frontend.ast.AnnotationStatement;
import dev.superice.gdparser.frontend.ast.ArrayExpression;
import dev.superice.gdparser.frontend.ast.AssignmentExpression;
import dev.superice.gdparser.frontend.ast.AstDiagnostic;
import dev.superice.gdparser.frontend.ast.AstDiagnosticSeverity;
import dev.superice.gdparser.frontend.ast.AstMappingResult;
import dev.superice.gdparser.frontend.ast.AttributeCallStep;
import dev.superice.gdparser.frontend.ast.AttributeExpression;
import dev.superice.gdparser.frontend.ast.AttributePropertyStep;
import dev.superice.gdparser.frontend.ast.AttributeStep;
import dev.superice.gdparser.frontend.ast.AttributeSubscriptStep;
import dev.superice.gdparser.frontend.ast.AwaitExpression;
import dev.superice.gdparser.frontend.ast.BinaryExpression;
import dev.superice.gdparser.frontend.ast.Block;
import dev.superice.gdparser.frontend.ast.CallExpression;
import dev.superice.gdparser.frontend.ast.CastExpression;
import dev.superice.gdparser.frontend.ast.ClassDeclaration;
import dev.superice.gdparser.frontend.ast.ClassNameStatement;
import dev.superice.gdparser.frontend.ast.ConditionalExpression;
import dev.superice.gdparser.frontend.ast.ConstructorDeclaration;
import dev.superice.gdparser.frontend.ast.DeclarationKind;
import dev.superice.gdparser.frontend.ast.DictEntry;
import dev.superice.gdparser.frontend.ast.DictionaryExpression;
import dev.superice.gdparser.frontend.ast.EnumDeclaration;
import dev.superice.gdparser.frontend.ast.EnumMember;
import dev.superice.gdparser.frontend.ast.Expression;
import dev.superice.gdparser.frontend.ast.ExpressionStatement;
import dev.superice.gdparser.frontend.ast.ExtendsStatement;
import dev.superice.gdparser.frontend.ast.FunctionDeclaration;
import dev.superice.gdparser.frontend.ast.GetNodeExpression;
import dev.superice.gdparser.frontend.ast.IdentifierExpression;
import dev.superice.gdparser.frontend.ast.LiteralExpression;
import dev.superice.gdparser.frontend.ast.Parameter;
import dev.superice.gdparser.frontend.ast.PassStatement;
import dev.superice.gdparser.frontend.ast.PreloadExpression;
import dev.superice.gdparser.frontend.ast.Range;
import dev.superice.gdparser.frontend.ast.SelfExpression;
import dev.superice.gdparser.frontend.ast.SignalStatement;
import dev.superice.gdparser.frontend.ast.SourceFile;
import dev.superice.gdparser.frontend.ast.Statement;
import dev.superice.gdparser.frontend.ast.SubscriptExpression;
import dev.superice.gdparser.frontend.ast.TypeRef;
import dev.superice.gdparser.frontend.ast.TypeTestExpression;
import dev.superice.gdparser.frontend.ast.UnaryExpression;
import dev.superice.gdparser.frontend.ast.VariableDeclaration;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/// Recursive-descent + Pratt parser producing `frontend.ast` directly from a GDScript source.
/// Port of `GDScriptParser` (`vendor/godot/gdscript_parser.h` / `gdscript_parser.cpp`), scoped to
/// the syntax layer: program prefix, class-body declarations, suites, annotations, types and the
/// expression subset declarations depend on (literals, identifiers, `self`, builtin constants,
/// unary `+`/`-`, binary operators, call/attribute/subscript chains). Semantic analysis, the
/// annotation registry (name/target/arity validation), completion hooks and symbol tables are cut;
/// the full Pratt rule table and control-flow statements are ported separately.
///
/// Behavior deviations from Godot, by design:
/// - `PI`/`TAU`/`INF`/`NAN` produce `IdentifierExpression` (numeric folding is cut with the
///   constant-reduction pass) to match the established AST contract.
/// - Class-body `pass` produces a `PassStatement` (Godot emits no node) because this AST keeps it.
/// - Standalone strings at program prefix / class body are consumed as docstrings and produce no
///   node, exactly like Godot; the reference tree-sitter path keeps them as expression statements,
///   which is a registered parity divergence.
/// - An `extends` line inside a nested class body sets the class's superclass (Godot behavior);
///   the tree-sitter path instead emits an `ExtendsStatement` inside the body (registered
///   divergence).
/// - Member/enum duplicate-name checks are cut with the symbol-table machinery; the unambiguous
///   purely syntactic checks (duplicate parameter names, mandatory-after-optional, rest-parameter
///   rules) are ported.
/// - Annotation name/target/arity validation is cut; only the syntax is parsed.
/// - A failed unary/binary operand fails the whole expression instead of producing a node with a
///   null child, since the target AST has no nullable expression components.
public final class GdScriptParser {

    /// `AstDiagnostic.nodeType` for parser/tokenizer syntax diagnostics. There is no CST node to
    /// reference, so the uniform error-node tag is used (mirroring structural-issue diagnostics
    /// of the reference path).
    private static final String DIAGNOSTIC_NODE_TYPE = "ERROR";

    private GdTokenizer tokenizer = new GdTokenizer();
    private byte[] sourceBytes = new byte[0];
    private SourceTextIndex index;
    private GdToken current;
    private GdToken previous;
    private final Deque<Boolean> multilineStack = new ArrayDeque<>();
    private final List<AstDiagnostic> diagnostics = new ArrayList<>();
    private boolean panicMode;

    /// Annotations parsed but not yet flushed into a statement sequence. They flush (in source
    /// order) right before the next dispatched statement, and the flushed run that sits on the
    /// same physical line as the statement keyword extends that statement's range, matching the
    /// reference grammar's `annotations` span behavior.
    private final List<AnnotationStatement> pendingAnnotations = new ArrayList<>();
    /// Start byte of the statement currently being dispatched (see {@link #computeStatementStart}).
    private int statementStartByte;
    /// Start byte of the parameter currently being parsed; the signature loops set this because a
    /// variadic `...` marker extends the parameter range to the marker start.
    private int parameterStartByte;
    /// Whether the last `parseStatement` core dispatch produced no statement (Godot's `nullptr`
    /// return). The suite circuit breaker counts this — not the flushed annotation output — so a
    /// failed declaration preceded by annotations still counts, exactly like Godot.
    private boolean lastStatementWasNull;
    /// Whether the script-level `extends` was already used (set by both the standalone and the
    /// same-line `class_name … extends` forms; Godot tracks it on the root class node).
    private boolean scriptExtendsUsed;

    public GdScriptParser() {
    }

    /// Parses the given source. Every call fully reinitializes the parser state, so an instance
    /// may be reused sequentially; instances are not thread-safe.
    ///
    /// @return the mapped AST plus all accumulated diagnostics (tokenizer and parser errors).
    public @NotNull AstMappingResult parse(@NotNull String source) {
        tokenizer = new GdTokenizer(source);
        sourceBytes = source.getBytes(StandardCharsets.UTF_8);
        index = tokenizer.sourceTextIndex();
        multilineStack.clear();
        diagnostics.clear();
        pendingAnnotations.clear();
        panicMode = false;
        lastStatementWasNull = false;
        scriptExtendsUsed = false;
        previous = new GdToken(GdTokenType.EMPTY, "", GdLiteralKind.NONE, null, 0, 0, 1, 1, 1, 1);
        current = scanSkippingErrors();

        var statements = parseProgram();
        var fileRange = statements.isEmpty()
                ? range(index.utf8Length(), index.utf8Length())
                : range(0, index.utf8Length());
        return new AstMappingResult(new SourceFile(statements, fileRange), List.copyOf(diagnostics));
    }

    // ---------------------------------------------------------------------------------------------
    // Cursor, diagnostics and recovery — port of the cursor block in gdscript_parser.cpp.
    // ---------------------------------------------------------------------------------------------

    /// Scans until a non-ERROR token, converting each ERROR token into a diagnostic (the parser
    /// never observes ERROR tokens, mirroring the error-skip loop of `GDScriptParser::advance`).
    private GdToken scanSkippingErrors() {
        var token = tokenizer.scan();
        while (token.isError()) {
            pushError(token.errorMessage(), token);
            token = tokenizer.scan();
        }
        return token;
    }

    /// Port of `GDScriptParser::advance`. Godot soft-fails on EOF (`ERR_FAIL_COND_V_MSG`,
    /// no movement); this port throws instead, so every call site that can legally sit at EOF
    /// must guard with `isAtEnd()` first (all loops ported from Godot already do).
    private void advance() {
        if (current.type() == GdTokenType.EOF) {
            throw new IllegalStateException("GDScript parser bug: trying to advance past the end of stream.");
        }
        previous = current;
        current = scanSkippingErrors();
    }

    private boolean match(GdTokenType type) {
        if (!check(type)) {
            return false;
        }
        advance();
        return true;
    }

    /// Port of `GDScriptParser::check`: an `IDENTIFIER` check accepts every token Godot's
    /// `Token::is_identifier()` accepts (`match`/`when` and the builtin constants double as
    /// names, e.g. `String.match()` or `Vector2.INF`).
    private boolean check(GdTokenType type) {
        if (type == GdTokenType.IDENTIFIER) {
            return isIdentifier(current);
        }
        return current.type() == type;
    }

    /// Port of `GDScriptTokenizer::Token::is_identifier`.
    private static boolean isIdentifier(GdToken token) {
        return switch (token.type()) {
            case IDENTIFIER, MATCH, WHEN, CONST_PI, CONST_TAU, CONST_INF, CONST_NAN -> true;
            default -> false;
        };
    }

    /// Port of `GDScriptTokenizer::Token::is_node_name`: nearly every keyword may name an
    /// attribute member (used by `parse_attribute` and the `$`/`%` node paths).
    private static boolean isNodeName(GdToken token) {
        return switch (token.type()) {
            case IDENTIFIER, AND, AS, ASSERT, AWAIT, BREAK, BREAKPOINT, CLASS_NAME, CLASS, CONST,
                 CONST_PI, CONST_INF, CONST_NAN, CONST_TAU, CONTINUE, ELIF, ELSE, ENUM, EXTENDS,
                 FOR, FUNC, IF, IN, IS, MATCH, NAMESPACE, NOT, OR, PASS, PRELOAD, RETURN, SELF,
                 SIGNAL, STATIC, SUPER, TRAIT, UNDERSCORE, VAR, VOID, WHILE, WHEN, YIELD -> true;
            default -> false;
        };
    }

    private boolean consume(GdTokenType type, String errorMessage) {
        if (match(type)) {
            return true;
        }
        pushError(errorMessage);
        return false;
    }

    private boolean isAtEnd() {
        return check(GdTokenType.EOF);
    }

    /// Port of `GDScriptParser::push_error`: always appends and sets the panic flag; the no-origin
    /// overload reports at `previous`, like Godot.
    private void pushError(String message) {
        pushError(message, previous);
    }

    private void pushError(String message, GdToken origin) {
        panicMode = true;
        diagnostics.add(new AstDiagnostic(
                AstDiagnosticSeverity.ERROR,
                message,
                DIAGNOSTIC_NODE_TYPE,
                tokenRange(origin)
        ));
    }

    /// Port of `GDScriptParser::synchronize`: same sync token set (`if` deliberately excluded
    /// because it can appear inside expressions); stops before the sync token without consuming.
    private void synchronize() {
        panicMode = false;
        while (!isAtEnd()) {
            if (previous.type() == GdTokenType.NEWLINE || previous.type() == GdTokenType.SEMICOLON) {
                return;
            }
            switch (current.type()) {
                case CLASS, FUNC, STATIC, VAR, CONST, SIGNAL, FOR, WHILE, MATCH, RETURN, ANNOTATION -> {
                    return;
                }
                default -> {
                    // Keep scanning.
                }
            }
            advance();
        }
    }

    /// Port of `GDScriptParser::push_multiline(true)`, including the raw consumption of already
    /// pending NEWLINE/INDENT/DEDENT tokens (which must not touch `previous`). The disabled state
    /// is unused, so the parameter is omitted.
    private void pushMultiline() {
        multilineStack.addLast(true);
        tokenizer.setMultilineMode(true);
        // Discard pending layout tokens without touching `previous`. Unlike Godot (which
        // raw-scans here and can silently drop an ERROR token sitting in `current`), errors
        // are reported immediately — our diagnostics contract requires every tokenizer
        // error to surface.
        while (current.type() == GdTokenType.NEWLINE
                || current.type() == GdTokenType.INDENT
                || current.type() == GdTokenType.DEDENT) {
            current = scanSkippingErrors();
        }
    }

    /// Port of `GDScriptParser::pop_multiline`.
    private void popMultiline() {
        if (multilineStack.isEmpty()) {
            throw new IllegalStateException("GDScript parser bug: popping the multiline stack without a matching push.");
        }
        multilineStack.removeLast();
        tokenizer.setMultilineMode(multilineStack.peekLast() != null && multilineStack.peekLast());
    }

    private boolean isStatementEndToken() {
        return check(GdTokenType.NEWLINE) || check(GdTokenType.SEMICOLON) || check(GdTokenType.EOF);
    }

    /// Port of `GDScriptParser::end_statement` minus the lambda-end conditions (those only exist
    /// together with lambda parsing): consumes a run of NEWLINE/SEMICOLON, stops at EOF without
    /// consuming it, and reports when anything else follows.
    private void endStatement(String context) {
        var found = false;
        while (isStatementEndToken() && !isAtEnd()) {
            advance();
            found = true;
        }
        if (!found && !isAtEnd()) {
            pushError("Expected end of statement after %s, found \"%s\" instead."
                    .formatted(context, current.type().displayName()));
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Program structure — port of parse_program / parse_class_body and their helpers.
    // ---------------------------------------------------------------------------------------------

    /// Port of `GDScriptParser::parse_program`: prefix annotations/docstrings, then `class_name` /
    /// `extends` in any order with at most one each, then the class body. Pending annotations are
    /// flushed before the statement that follows them, preserving source order.
    private @NotNull List<Statement> parseProgram() {
        var statements = new ArrayList<Statement>();
        var classNameUsed = false;

        // First prefix phase: script-level annotations and standalone docstrings, in any order.
        while (!check(GdTokenType.EOF)) {
            if (match(GdTokenType.ANNOTATION)) {
                pendingAnnotations.add(parseAnnotation());
            } else if (notStandaloneDocstring()) {
                break;
            }
        }

        // Second prefix phase: class_name / extends. Their relative order is free; anything else
        // (except further docstrings, kept for compatibility) ends the prefix.
        var canHaveClassOrExtends = true;
        while (canHaveClassOrExtends) {
            switch (current.type()) {
                case CLASS_NAME -> {
                    advance();
                    if (classNameUsed) {
                        pushError("\"class_name\" can only be used once.");
                    } else {
                        classNameUsed = true;
                        statementStartByte = computeStatementStart(previous);
                        statements.addAll(drainAnnotations());
                        statements.add(parseClassName());
                    }
                }
                case EXTENDS -> {
                    advance();
                    if (scriptExtendsUsed) {
                        pushError("\"extends\" can only be used once.");
                    } else {
                        scriptExtendsUsed = true;
                        statementStartByte = computeStatementStart(previous);
                        statements.addAll(drainAnnotations());
                        statements.add(parseExtendsStatement());
                        endStatement("superclass");
                    }
                }
                case LITERAL -> {
                    if (notStandaloneDocstring()) {
                        canHaveClassOrExtends = false;
                    }
                }
                default -> canHaveClassOrExtends = false;
            }
            if (panicMode) {
                synchronize();
            }
        }

        parseClassBody(true, statements);

        if (!check(GdTokenType.EOF)) {
            pushError("Expected end of file.");
        }
        statements.addAll(drainAnnotations());
        return List.copyOf(statements);
    }

    /// Port of `GDScriptParser::parse_class_name` (including the same-line `extends` tail).
    private @NotNull Statement parseClassName() {
        var start = statementStartByte;
        String name = null;
        if (consume(GdTokenType.IDENTIFIER, "Expected identifier for the global class name after \"class_name\".")) {
            name = previous.source();
        }

        String extendsTarget = null;
        var end = previous.endByte();
        if (match(GdTokenType.EXTENDS)) {
            // The same-line extends marks the script-level extends as used (like Godot's root
            // class flag), so a later standalone `extends` line is reported as a duplicate.
            scriptExtendsUsed = true;
            extendsTarget = parseExtendsTarget();
            end = previous.endByte();
            endStatement("superclass");
        } else {
            endStatement("class_name statement");
        }
        return new ClassNameStatement(name, extendsTarget, range(start, end));
    }

    /// Wraps {@link #parseExtendsTarget} into an `ExtendsStatement` spanning from the `extends`
    /// keyword (already consumed) to the target end.
    private @NotNull Statement parseExtendsStatement() {
        var start = statementStartByte;
        var target = parseExtendsTarget();
        var end = previous.endByte();
        return new ExtendsStatement(target, range(start, end));
    }

    /// Port of `GDScriptParser::parse_extends`: a string literal path or an identifier chain
    /// (a literal may also lead a chain after a `.`). Returns the raw source slice of the target,
    /// matching the reference path's trimmed-text extraction.
    private @Nullable String parseExtendsTarget() {
        GdToken firstToken = null;
        if (match(GdTokenType.LITERAL)) {
            firstToken = previous;
            if (previous.literalKind() != GdLiteralKind.STRING) {
                pushError("Only strings or identifiers can be used after \"extends\", found \"%s\" instead."
                        .formatted(variantTypeName(previous.literalKind())));
            }
            if (!match(GdTokenType.PERIOD)) {
                return slice(firstToken.startByte(), previous.endByte());
            }
        }

        if (!consume(GdTokenType.IDENTIFIER, "Expected superclass name after \"extends\".")) {
            return firstToken == null ? null : slice(firstToken.startByte(), previous.endByte());
        }
        if (firstToken == null) {
            firstToken = previous;
        }
        while (match(GdTokenType.PERIOD)) {
            if (!consume(GdTokenType.IDENTIFIER, "Expected superclass name after \".\".")) {
                break;
            }
        }
        return slice(firstToken.startByte(), previous.endByte());
    }

    /// Port of `GDScriptParser::parse_standalone_string`, with the result flipped to match every
    /// caller: a plain string literal standing alone is consumed as a docstring (produces no node)
    /// and this returns false; other literal kinds are left alone and this returns true.
    private boolean notStandaloneDocstring() {
        if (check(GdTokenType.LITERAL) && current.literalKind() == GdLiteralKind.STRING) {
            advance();
            if (!match(GdTokenType.NEWLINE)) {
                pushError("Expected newline after comment string.");
            }
            return false;
        }
        return true;
    }

    /// Port of `GDScriptParser::parse_class_body`, including the Godot 3.x removed-keyword
    /// migration diagnostics and the `static` prefix handling. Unlike Godot, a class-body `pass`
    /// produces a `PassStatement` (this AST keeps it).
    private void parseClassBody(boolean multiline, List<Statement> statements) {
        var classEnd = false;
        var nextIsStatic = false;
        while (!classEnd && !isAtEnd()) {
            var token = current;
            switch (token.type()) {
                case VAR -> {
                    beginMember(statements, nextIsStatic);
                    advance();
                    var variable = parseVariable(nextIsStatic, true);
                    if (variable != null) {
                        statements.add(variable);
                    }
                }
                case CONST -> {
                    beginMember(statements, false);
                    advance();
                    var constant = parseConstant();
                    if (constant != null) {
                        statements.add(constant);
                    }
                }
                case SIGNAL -> {
                    beginMember(statements, false);
                    advance();
                    var signal = parseSignal();
                    if (signal != null) {
                        statements.add(signal);
                    }
                }
                case FUNC -> {
                    beginMember(statements, nextIsStatic);
                    advance();
                    var function = parseFunction(nextIsStatic);
                    if (function != null) {
                        statements.add(function);
                    }
                }
                case CLASS -> {
                    beginMember(statements, false);
                    advance();
                    statements.add(parseNestedClass());
                }
                case ENUM -> {
                    beginMember(statements, false);
                    advance();
                    statements.add(parseEnum());
                }
                case STATIC -> {
                    beginMember(statements, false);
                    advance();
                    // Only a directly following `func`/`var` keeps the static flag; on any other
                    // token the flag is dropped so the error does not leak into the sibling
                    // declaration that synchronization recovers to (Godot keeps the flag there,
                    // which mislabels the recovered sibling — a recovery-only refinement).
                    nextIsStatic = check(GdTokenType.FUNC) || check(GdTokenType.VAR);
                    if (!nextIsStatic) {
                        pushError("Expected \"func\" or \"var\" after \"static\".");
                    }
                }
                case ANNOTATION -> {
                    advance();
                    pendingAnnotations.add(parseAnnotation());
                }
                case PASS -> {
                    beginMember(statements, false);
                    advance();
                    statements.add(new PassStatement(range(statementStartByte, previous.endByte())));
                    endStatement("\"pass\"");
                }
                case DEDENT -> classEnd = true;
                case LITERAL -> {
                    if (notStandaloneDocstring()) {
                        reportUnexpectedClassBodyToken();
                    }
                }
                default -> reportUnexpectedClassBodyToken();
            }
            if (token.type() != GdTokenType.STATIC) {
                nextIsStatic = false;
            }
            if (panicMode) {
                synchronize();
            }
            if (!multiline) {
                classEnd = true;
            }
        }
        // Annotations left dangling at the end of this body belong to this scope (see the
        // suite flush); they must not escape to a following outer member.
        statements.addAll(drainAnnotations());
    }

    /// Default branch of the class-body dispatch: consumes one token and reports either a Godot
    /// 3.x removed-keyword migration diagnostic or the generic unexpected-token error.
    private void reportUnexpectedClassBodyToken() {
        advance();
        var message = switch (previous.source()) {
            case "export" ->
                    "The \"export\" keyword was removed in Godot 4. Use an export annotation (\"@export\", \"@export_range\", etc.) instead.";
            case "tool" -> "The \"tool\" keyword was removed in Godot 4. Use the \"@tool\" annotation instead.";
            case "onready" ->
                    "The \"onready\" keyword was removed in Godot 4. Use the \"@onready\" annotation instead.";
            case "remote" ->
                    "The \"remote\" keyword was removed in Godot 4. Use the \"@rpc\" annotation with \"any_peer\" instead.";
            case "remotesync" ->
                    "The \"remotesync\" keyword was removed in Godot 4. Use the \"@rpc\" annotation with \"any_peer\" and \"call_local\" instead.";
            case "puppet" ->
                    "The \"puppet\" keyword was removed in Godot 4. Use the \"@rpc\" annotation with \"authority\" instead.";
            case "puppetsync" ->
                    "The \"puppetsync\" keyword was removed in Godot 4. Use the \"@rpc\" annotation with \"authority\" and \"call_local\" instead.";
            case "master" ->
                    "The \"master\" keyword was removed in Godot 4. Use the \"@rpc\" annotation with \"any_peer\" and perform a check inside the function instead.";
            case "mastersync" ->
                    "The \"mastersync\" keyword was removed in Godot 4. Use the \"@rpc\" annotation with \"any_peer\" and \"call_local\", and perform a check inside the function instead.";
            default -> "Unexpected %s in class body.".formatted(debugName(previous));
        };
        pushError(message);
    }

    /// Computes the statement start and flushes pending annotations into the sequence, unless a
    /// `static` prefix already did so for this member.
    private void beginMember(List<Statement> statements, boolean alreadyStarted) {
        if (!alreadyStarted) {
            statementStartByte = computeStatementStart(current);
            statements.addAll(drainAnnotations());
        }
    }

    /// The statement starts at the keyword, unless a contiguous run of pending annotations sits
    /// on the same physical line as the keyword — then it starts at the earliest such annotation
    /// (the reference grammar spans same-line annotations into the statement node).
    private int computeStatementStart(GdToken keyword) {
        var start = keyword.startByte();
        var keywordRow = keyword.startLine() - 1;
        for (var i = pendingAnnotations.size() - 1; i >= 0; i--) {
            var annotation = pendingAnnotations.get(i);
            if (annotation.range().startPoint().row() != keywordRow) {
                break;
            }
            start = annotation.range().startByte();
        }
        return start;
    }

    private List<AnnotationStatement> drainAnnotations() {
        if (pendingAnnotations.isEmpty()) {
            return List.of();
        }
        var drained = List.copyOf(pendingAnnotations);
        pendingAnnotations.clear();
        return drained;
    }

    /// Port of `GDScriptParser::parse_class` (nested classes), including the optional superclass
    /// both after the name and as the first statement of an indented body.
    private @NotNull Statement parseNestedClass() {
        var start = statementStartByte;
        String name = null;
        if (consume(GdTokenType.IDENTIFIER, "Expected identifier for the class name after \"class\".")) {
            name = previous.source();
        }

        String extendsTarget = null;
        if (match(GdTokenType.EXTENDS)) {
            extendsTarget = parseExtendsTarget();
        }

        consume(GdTokenType.COLON, "Expected \":\" after class declaration.");
        var headerEnd = previous.endByte();

        var multiline = match(GdTokenType.NEWLINE);
        var blockStart = multiline ? previous.startByte() : current.startByte();
        if (multiline && !consume(GdTokenType.INDENT, "Expected indented block after class declaration.")) {
            return new ClassDeclaration(name, extendsTarget,
                    new Block(List.of(), range(blockStart, blockStart)),
                    range(start, headerEnd));
        }

        if (match(GdTokenType.EXTENDS)) {
            if (extendsTarget != null) {
                pushError("Cannot use \"extends\" more than once in the same class.");
            }
            extendsTarget = parseExtendsTarget();
            endStatement("superclass");
        }

        var bodyStatements = new ArrayList<Statement>();
        parseClassBody(multiline, bodyStatements);
        var blockEnd = bodyStatements.isEmpty()
                ? blockStart
                : bodyStatements.getLast().range().endByte();
        var body = new Block(List.copyOf(bodyStatements), range(blockStart, blockEnd));

        var end = Math.max(blockEnd, headerEnd);
        if (multiline) {
            consume(GdTokenType.DEDENT, "Missing unindent at the end of the class body.");
        }
        return new ClassDeclaration(name, extendsTarget, body, range(start, end));
    }

    // ---------------------------------------------------------------------------------------------
    // Suites and statements — port of parse_suite / parse_statement (declaration-scoped subset).
    // ---------------------------------------------------------------------------------------------

    /// Port of `GDScriptParser::parse_suite`: multi-line (NEWLINE + INDENT … DEDENT) or single-line
    /// (semicolon-separated) statement block. Failed statements are skipped; only those count
    /// toward the circuit breaker (`error_count++ > 100`, i.e. the 102nd failure aborts the
    /// suite) — `push_error` alone does not count.
    private @NotNull Block parseSuite(String context) {
        var multiline = match(GdTokenType.NEWLINE);
        int blockStart;
        if (multiline) {
            blockStart = previous.startByte();
            if (!consume(GdTokenType.INDENT, "Expected indented block after %s.".formatted(context))) {
                return new Block(List.of(), range(blockStart, blockStart));
            }
        } else {
            blockStart = current.startByte();
        }

        var statements = new ArrayList<Statement>();
        var errorCount = 0;
        do {
            if (isAtEnd() || (!multiline && previous.type() == GdTokenType.SEMICOLON && check(GdTokenType.NEWLINE))) {
                break;
            }
            var produced = parseStatement();
            if (lastStatementWasNull) {
                if (errorCount++ > 100) {
                    pushError("Too many statement errors.");
                    break;
                }
                continue;
            }
            statements.addAll(produced);
        } while ((multiline || previous.type() == GdTokenType.SEMICOLON)
                && !check(GdTokenType.DEDENT)
                && !isAtEnd());

        if (multiline) {
            consume(GdTokenType.DEDENT, "Missing unindent at the end of %s.".formatted(context));
        } else if (previous.type() == GdTokenType.SEMICOLON) {
            consume(GdTokenType.NEWLINE, "Expected newline after \";\" at the end of %s.".formatted(context));
        }

        // Annotations left dangling at the end of this suite belong to this scope: flush them
        // into the suite's own sequence instead of letting them escape to an outer member.
        statements.addAll(drainAnnotations());
        var blockEnd = statements.isEmpty() ? blockStart : statements.getLast().range().endByte();
        return new Block(List.copyOf(statements), range(blockStart, blockEnd));
    }

    /// Port of `GDScriptParser::parse_statement` for the declaration-scoped subset: `pass`, local
    /// `var`/`const`, statement annotations, and expression statements built from the available
    /// expression subset. Returns the flushed annotations plus the parsed statement; an empty list
    /// mirrors Godot's `nullptr` return (counted by the suite's circuit breaker).
    private @NotNull List<Statement> parseStatement() {
        // Statement-level annotations are collected and attach to the next real statement; the
        // annotation-only result is empty like Godot's null return.
        if (current.type() == GdTokenType.ANNOTATION) {
            advance();
            pendingAnnotations.add(parseAnnotation());
            // An annotation-only round produces no statement (Godot returns `nullptr` here too).
            lastStatementWasNull = true;
            if (panicMode) {
                synchronize();
            }
            return List.of();
        }

        lastStatementWasNull = false;
        statementStartByte = computeStatementStart(current);
        var produced = new ArrayList<Statement>(drainAnnotations());
        Statement result;
        switch (current.type()) {
            case PASS -> {
                advance();
                result = new PassStatement(range(statementStartByte, previous.endByte()));
                endStatement("\"pass\"");
            }
            case VAR -> {
                advance();
                result = parseVariable(false, false);
            }
            case CONST -> {
                advance();
                result = parseConstant();
            }
            default -> {
                // Only statement-level expressions may contain assignments (Godot's
                // `parse_expression(true)` call site in `parse_expression_statement`).
                var expression = parseExpression(true);
                if (expression == null) {
                    // Godot's advance() soft-fails at EOF; mirror that by guarding the advance
                    // so `previous` (and thus the error location/name) matches Godot exactly.
                    if (!isAtEnd()) {
                        advance();
                    }
                    pushError("Expected statement, found \"%s\" instead.".formatted(previous.type().displayName()));
                    result = null;
                } else {
                    // The statement ends at the last consumed token, not the expression node's
                    // own end (a trailing grouping `)` belongs to the consumed span).
                    result = new ExpressionStatement(expression, range(statementStartByte, previous.endByte()));
                    endStatement("expression");
                }
            }
        }

        lastStatementWasNull = result == null;
        if (result != null) {
            produced.add(result);
        }
        if (panicMode) {
            synchronize();
        }
        return List.copyOf(produced);
    }

    // ---------------------------------------------------------------------------------------------
    // Annotations and types.
    // ---------------------------------------------------------------------------------------------

    /// Port of `GDScriptParser::parse_annotation`, syntax only: the registry lookups (name,
    /// target, argument count) are cut. Arguments may span lines and may use a trailing comma.
    private @NotNull AnnotationStatement parseAnnotation() {
        var atToken = previous;
        var name = atToken.source().substring(1);

        var arguments = new ArrayList<Expression>();
        var end = atToken.endByte();
        if (check(GdTokenType.PARENTHESIS_OPEN)) {
            pushMultiline();
            advance();
            do {
                if (check(GdTokenType.PARENTHESIS_CLOSE)) {
                    // Allow for trailing comma.
                    break;
                }
                var argument = parseExpression(false);
                if (argument == null) {
                    pushError("Expected expression as the annotation argument.");
                } else {
                    arguments.add(argument);
                }
            } while (match(GdTokenType.COMMA));

            popMultiline();
            consume(GdTokenType.PARENTHESIS_CLOSE, "Expected \")\" after annotation arguments.");
            end = previous.endByte();
        }
        match(GdTokenType.NEWLINE); // Newline after annotation is optional.
        return new AnnotationStatement(name, List.copyOf(arguments), range(atToken.startByte(), end));
    }

    /// Port of `GDScriptParser::parse_type`: identifier chains, `Array[E]` / `Dictionary[K, V]`
    /// container types (nested typed collections rejected), and `void` restricted to function
    /// return types. Multiline mode is inherited from the calling context (no push/pop here), so
    /// member variable types cannot wrap lines while signature parameter types can.
    private @Nullable TypeRef parseType(boolean allowVoid) {
        var parsed = parseTypeInternal(allowVoid);
        return parsed == null ? null : parsed.ref();
    }

    /// Internal shape of {@link #parseType}: also reports whether the parsed type is itself a
    /// container type, which the opaque-text `TypeRef` cannot carry (Godot tracks this on the
    /// type node's `container_types` list).
    private record ParsedType(@NotNull TypeRef ref, boolean isContainer) {
    }

    private @Nullable ParsedType parseTypeInternal(boolean allowVoid) {
        var typeStart = current.startByte();
        if (!match(GdTokenType.IDENTIFIER)) {
            if (match(GdTokenType.VOID)) {
                if (allowVoid) {
                    return new ParsedType(new TypeRef("void", range(typeStart, previous.endByte())), false);
                }
                pushError("\"void\" is only allowed for a function return type.");
            }
            // The "expected type" message is left to the caller, which knows the context.
            return null;
        }
        var typeEnd = previous.endByte();

        if (match(GdTokenType.BRACKET_OPEN)) {
            // Typed collection (like Array[int], Dictionary[String, int]).
            var firstPass = true;
            var failed = false;
            do {
                var element = parseTypeInternal(false);
                if (element == null) {
                    pushError("Expected type for collection after \"%s\".".formatted(firstPass ? "[" : ","));
                    failed = true;
                    break;
                } else if (element.isContainer()) {
                    pushError("Nested typed collections are not supported.");
                }
                firstPass = false;
            } while (match(GdTokenType.COMMA));
            consume(GdTokenType.BRACKET_CLOSE, "Expected closing \"]\" after collection type.");
            if (failed) {
                return null;
            }
            typeEnd = previous.endByte();
            return new ParsedType(new TypeRef(slice(typeStart, typeEnd), range(typeStart, typeEnd)), true);
        }

        while (match(GdTokenType.PERIOD)) {
            if (consume(GdTokenType.IDENTIFIER, "Expected inner type name after \".\".")) {
                typeEnd = previous.endByte();
            }
        }
        return new ParsedType(new TypeRef(slice(typeStart, typeEnd), range(typeStart, typeEnd)), false);
    }

    /// Builds the inferred-type marker (`:=`, or `: =` preserving inner whitespace) as a non-null
    /// `TypeRef`, matching the reference path's `inferred_type` handling. The trailing `=` token
    /// is not consumed; the initializer's `match(EQUAL)` picks it up.
    private @NotNull TypeRef inferredTypeRef(GdToken colonToken) {
        var start = colonToken.startByte();
        var end = current.endByte(); // The EQUAL token, not yet consumed.
        return new TypeRef(slice(start, end).strip(), range(start, end));
    }

    // ---------------------------------------------------------------------------------------------
    // Declarations — port of parse_variable / parse_constant / parse_signal / parse_enum /
    // parse_function (+ signature).
    // ---------------------------------------------------------------------------------------------

    /// Port of `GDScriptParser::parse_variable`. When `allowProperty` is set (class members), a
    /// get/set property tail is consumed but, per the AST contract, produces no nodes.
    private @Nullable VariableDeclaration parseVariable(boolean isStatic, boolean allowProperty) {
        var start = statementStartByte;
        if (!consume(GdTokenType.IDENTIFIER, "Expected variable name after \"var\".")) {
            return null;
        }
        var name = previous.source();

        TypeRef type = null;
        Expression value = null;
        if (match(GdTokenType.COLON)) {
            var colonToken = previous;
            if (check(GdTokenType.NEWLINE)) {
                if (allowProperty) {
                    advance();
                    var end = parseProperty(true);
                    return new VariableDeclaration(DeclarationKind.VAR, name, null, null, isStatic,
                            "variable_statement", range(start, end));
                }
                pushError("Expected type after \":\"");
                return null;
            } else if (check(GdTokenType.EQUAL)) {
                // Infer-type marker; the `=` itself belongs to the initializer below.
                type = inferredTypeRef(colonToken);
            } else {
                if (allowProperty && check(GdTokenType.IDENTIFIER)
                        && (current.source().equals("get") || current.source().equals("set"))) {
                    var end = parseProperty(false);
                    return new VariableDeclaration(DeclarationKind.VAR, name, null, null, isStatic,
                            "variable_statement", range(start, end));
                }
                type = parseType(false);
            }
        }

        if (match(GdTokenType.EQUAL)) {
            value = parseExpression(false);
            if (value == null) {
                pushError("Expected expression for variable initial value after \"=\".");
            }
        }

        if (allowProperty && match(GdTokenType.COLON)) {
            int end;
            if (match(GdTokenType.NEWLINE)) {
                end = parseProperty(true);
            } else {
                end = parseProperty(false);
            }
            return new VariableDeclaration(DeclarationKind.VAR, name, type, value, isStatic,
                    "variable_statement", range(start, end));
        }

        var variable = new VariableDeclaration(DeclarationKind.VAR, name, type, value, isStatic,
                "variable_statement", range(start, previous.endByte()));
        endStatement("variable declaration");
        return variable;
    }

    /// Port of `GDScriptParser::parse_property` (+ setter/getter helpers): consumes both the
    /// reference form (`get = name, set = name`) and the inline-suite form (`get:` /
    /// `set(value):`). Produces no AST and returns the end byte of the whole property tail,
    /// captured before the trailing statement-end / DEDENT consumption.
    private int parseProperty(boolean needIndent) {
        if (needIndent
                && !consume(GdTokenType.INDENT, "Expected indented block for property after \":\".")) {
            return previous.endByte();
        }

        if (!consume(GdTokenType.IDENTIFIER, "Expected \"get\" or \"set\" for property declaration.")) {
            return previous.endByte();
        }
        var functionName = previous.source();

        // Reference form when `=` follows the first accessor, inline form otherwise.
        var referenceForm = check(GdTokenType.EQUAL);
        if (!referenceForm && !needIndent) {
            pushError("Property with inline code must go to an indented block.");
        }

        var getterUsed = false;
        var setterUsed = false;
        // Run in a loop because accessor order doesn't matter.
        for (var i = 0; i < 2; i++) {
            if (functionName.equals("set")) {
                if (setterUsed) {
                    pushError("Properties can only have one setter.");
                } else {
                    parsePropertyAccessor(true, referenceForm);
                    setterUsed = true;
                }
            } else if (functionName.equals("get")) {
                if (getterUsed) {
                    pushError("Properties can only have one getter.");
                } else {
                    parsePropertyAccessor(false, referenceForm);
                    getterUsed = true;
                }
            } else {
                pushError("Expected \"get\" or \"set\" for property declaration.");
            }

            if (i == 0 && referenceForm) {
                if (match(GdTokenType.COMMA)) {
                    // Consume potential newline. (The message text is byte-exact with Godot's
                    // source, including its quirky quoting.)
                    if (match(GdTokenType.NEWLINE) && !needIndent) {
                        pushError("Inline setter/getter setting cannot span across multiple lines (use \"\\\\\"\" if needed).");
                    }
                } else {
                    break;
                }
            }

            if (!match(GdTokenType.IDENTIFIER)) {
                break;
            }
            functionName = previous.source();
        }

        var end = previous.endByte();
        if (referenceForm) {
            endStatement("property declaration");
        }
        if (needIndent) {
            consume(GdTokenType.DEDENT, "Expected end of indented block for property.");
        }
        return end;
    }

    /// Port of `GDScriptParser::parse_property_setter` / `parse_property_getter`.
    private void parsePropertyAccessor(boolean setter, boolean referenceForm) {
        if (referenceForm) {
            consume(GdTokenType.EQUAL, setter ? "Expected \"=\" after \"set\"" : "Expected \"=\" after \"get\"");
            consume(GdTokenType.IDENTIFIER,
                    setter ? "Expected setter function name after \"=\"." : "Expected getter function name after \"=\".");
            return;
        }
        if (setter) {
            consume(GdTokenType.PARENTHESIS_OPEN, "Expected \"(\" after \"set\".");
            consume(GdTokenType.IDENTIFIER, "Expected parameter name after \"(\".");
            consume(GdTokenType.PARENTHESIS_CLOSE, "Expected \")\" after parameter name.");
            consume(GdTokenType.COLON, "Expected \":\" after \")\".");
            parseIsolatedSuite("setter declaration");
        } else {
            if (match(GdTokenType.PARENTHESIS_OPEN)) {
                consume(GdTokenType.PARENTHESIS_CLOSE, "Expected \")\" after \"get(\".");
                consume(GdTokenType.COLON, "Expected \":\" after \"get()\".");
            } else {
                consume(GdTokenType.COLON, "Expected \":\" or \"(\" after \"get\".");
            }
            parseIsolatedSuite("getter declaration");
        }
    }

    /// Parses an accessor suite with the outer pending annotations saved aside: the suite's
    /// content is discarded entirely, so annotations inside it must not leak into the outer
    /// sequence (and the outer sequence's pending annotations must not be stolen by it).
    private void parseIsolatedSuite(String context) {
        var saved = new ArrayList<>(pendingAnnotations);
        pendingAnnotations.clear();
        try {
            parseSuite(context);
        } finally {
            // Replace, not append: annotations left dangling inside the discarded accessor suite
            // are discarded with it instead of leaking onto the next outer member.
            pendingAnnotations.clear();
            pendingAnnotations.addAll(saved);
        }
    }

    private record NamedType(@NotNull String name, @Nullable TypeRef type) {
    }

    /// Shared name-plus-optional-type prefix of constants and parameters. The name token is
    /// already consumed; a `:` immediately followed by `=` is an inferred type.
    private @NotNull NamedType parseNameAndType() {
        var name = previous.source();
        TypeRef type = null;
        if (match(GdTokenType.COLON)) {
            var colonToken = previous;
            if (check(GdTokenType.EQUAL)) {
                type = inferredTypeRef(colonToken);
            } else {
                type = parseType(false);
            }
        }
        return new NamedType(name, type);
    }

    /// Port of `GDScriptParser::parse_constant`.
    private @Nullable VariableDeclaration parseConstant() {
        var start = statementStartByte;
        if (!consume(GdTokenType.IDENTIFIER, "Expected constant name after \"const\".")) {
            return null;
        }
        var named = parseNameAndType();

        Expression value;
        if (consume(GdTokenType.EQUAL, "Expected initializer after constant name.")) {
            value = parseExpression(false);
            if (value == null) {
                pushError("Expected initializer expression for constant.");
                return null;
            }
        } else {
            return null;
        }

        var constant = new VariableDeclaration(DeclarationKind.CONST, named.name(), named.type(), value, false,
                "const_statement", range(start, previous.endByte()));
        endStatement("constant declaration");
        return constant;
    }

    /// Port of `GDScriptParser::parse_parameter` (the variadic `...` marker itself is consumed by
    /// the signature loops, which also set {@link #parameterStartByte}).
    private @Nullable Parameter parseParameter() {
        if (!consume(GdTokenType.IDENTIFIER, "Expected parameter name.")) {
            return null;
        }
        var named = parseNameAndType();

        Expression defaultValue = null;
        if (match(GdTokenType.EQUAL)) {
            defaultValue = parseExpression(false);
        }
        return new Parameter(named.name(), named.type(), defaultValue, false,
                range(parameterStartByte, previous.endByte()));
    }

    /// Port of `GDScriptParser::parse_signal`, including the purely syntactic duplicate-name and
    /// no-default-value checks.
    private @Nullable SignalStatement parseSignal() {
        var start = statementStartByte;
        if (!consume(GdTokenType.IDENTIFIER, "Expected signal name after \"signal\".")) {
            return null;
        }
        var name = previous.source();

        var parameters = new ArrayList<Parameter>();
        if (check(GdTokenType.PARENTHESIS_OPEN)) {
            pushMultiline();
            advance();
            var declaredNames = new ArrayList<String>();
            do {
                if (check(GdTokenType.PARENTHESIS_CLOSE)) {
                    // Allow for trailing comma.
                    break;
                }
                parameterStartByte = current.startByte();
                var parameter = parseParameter();
                if (parameter == null) {
                    pushError("Expected signal parameter name.");
                    break;
                }
                if (parameter.defaultValue() != null) {
                    pushError("Signal parameters cannot have a default value.");
                }
                if (declaredNames.contains(parameter.name())) {
                    pushError("Parameter with name \"%s\" was already declared for this signal."
                            .formatted(parameter.name()));
                } else {
                    declaredNames.add(parameter.name());
                    parameters.add(parameter);
                }
            } while (match(GdTokenType.COMMA) && !isAtEnd());

            popMultiline();
            consume(GdTokenType.PARENTHESIS_CLOSE, "Expected closing \")\" after signal parameters.");
        }

        var signal = new SignalStatement(name, List.copyOf(parameters),
                range(start, previous.endByte()));
        endStatement("signal declaration");
        return signal;
    }

    /// Port of `GDScriptParser::parse_enum`. Duplicate-key and class-member name-clash checks are
    /// cut with the symbol-table machinery; values may span lines and use a trailing comma.
    private @NotNull EnumDeclaration parseEnum() {
        var start = statementStartByte;
        String name = null;
        if (match(GdTokenType.IDENTIFIER)) {
            name = previous.source();
        }

        var members = new ArrayList<EnumMember>();
        pushMultiline();
        consume(GdTokenType.BRACE_OPEN, "Expected \"{\" after %s.".formatted(name != null ? "enum name" : "\"enum\""));
        do {
            if (check(GdTokenType.BRACE_CLOSE)) {
                // Allow trailing comma.
                break;
            }
            if (consume(GdTokenType.IDENTIFIER, "Expected identifier for enum key.")) {
                var memberName = previous.source();
                var memberStart = previous.startByte();
                var memberEnd = previous.endByte();

                Expression value = null;
                if (match(GdTokenType.EQUAL)) {
                    value = parseExpression(false);
                    if (value == null) {
                        pushError("Expected expression value after \"=\".");
                    } else {
                        memberEnd = value.range().endByte();
                    }
                }
                members.add(new EnumMember(memberName, value, range(memberStart, memberEnd)));
            }
        } while (match(GdTokenType.COMMA));

        popMultiline();
        consume(GdTokenType.BRACE_CLOSE, "Expected closing \"}\" for enum.");
        var enumDecl = new EnumDeclaration(name, List.copyOf(members),
                range(start, previous.endByte()));
        endStatement("enum");
        return enumDecl;
    }

    /// Port of `GDScriptParser::parse_function` / `parse_function_signature`. A `_init` function
    /// produces a `ConstructorDeclaration` (the reference grammar marks it with a dedicated node
    /// type); `_static_init` special-casing is cut with the other semantic checks.
    private @Nullable Statement parseFunction(boolean isStatic) {
        var start = statementStartByte;
        if (!consume(GdTokenType.IDENTIFIER, "Expected function name after \"func\".")) {
            return null;
        }
        var name = previous.source();

        pushMultiline();
        consume(GdTokenType.PARENTHESIS_OPEN, "Expected opening \"(\" after function name.");
        var signature = parseFunctionSignature();

        var headerEnd = previous.endByte();
        Block body;
        if (!signature.hasBody()) {
            // Bodyless (abstract-style) functions end the statement at the signature.
            endStatement("bodyless function declaration");
            // The reference path maps a missing body to an empty block spanning the whole
            // declaration node; mirror that range for parity.
            body = new Block(List.of(), range(start, headerEnd));
        } else {
            body = parseSuite("function declaration");
        }

        var end = Math.max(body.range().endByte(), headerEnd);
        if (name.equals("_init")) {
            return new ConstructorDeclaration(signature.parameters(), signature.returnType(),
                    body, range(start, end));
        }
        return new FunctionDeclaration(name, signature.parameters(), signature.returnType(),
                isStatic, body, range(start, end));
    }

    private record FunctionSignature(@NotNull List<Parameter> parameters, @Nullable TypeRef returnType,
                                     boolean hasBody) {
    }

    /// Port of `GDScriptParser::parse_function_signature` for functions: parameter list with rest
    /// parameter and the purely syntactic ordering/duplicate checks, optional `->` return type, and
    /// the optional trailing colon (its absence marks a bodyless function).
    private @NotNull FunctionSignature parseFunctionSignature() {
        var parameters = new ArrayList<Parameter>();
        var declaredNames = new ArrayList<String>();
        if (!check(GdTokenType.PARENTHESIS_CLOSE) && !isAtEnd()) {
            var defaultUsed = false;
            var restUsed = false;
            do {
                if (check(GdTokenType.PARENTHESIS_CLOSE)) {
                    // Allow for trailing comma.
                    break;
                }

                var isRest = match(GdTokenType.PERIOD_PERIOD_PERIOD);
                parameterStartByte = isRest ? previous.startByte() : current.startByte();

                var parameter = parseParameter();
                if (parameter == null) {
                    break;
                }

                if (restUsed) {
                    pushError("Cannot have parameters after the rest parameter.");
                    continue;
                }
                if (parameter.defaultValue() != null) {
                    if (isRest) {
                        pushError("The rest parameter cannot have a default value.");
                        continue;
                    }
                    defaultUsed = true;
                } else if (defaultUsed && !isRest) {
                    pushError("Cannot have mandatory parameters after optional parameters.");
                    continue;
                }

                if (declaredNames.contains(parameter.name())) {
                    pushError("Parameter with name \"%s\" was already declared for this function."
                            .formatted(parameter.name()));
                } else if (isRest) {
                    restUsed = true;
                    parameters.add(new Parameter(parameter.name(), parameter.type(),
                            null, true,
                            range(parameterStartByte, parameter.range().endByte())));
                    declaredNames.add(parameter.name());
                } else {
                    declaredNames.add(parameter.name());
                    parameters.add(parameter);
                }
            } while (match(GdTokenType.COMMA));
        }

        popMultiline();
        consume(GdTokenType.PARENTHESIS_CLOSE, "Expected closing \")\" after function parameters.");

        TypeRef returnType = null;
        if (match(GdTokenType.FORWARD_ARROW)) {
            returnType = parseType(true);
            if (returnType == null) {
                pushError("Expected return type or \"void\" after \"->\".");
            }
        }

        // The colon may not be present in the case of bodyless functions.
        return new FunctionSignature(List.copyOf(parameters), returnType, match(GdTokenType.COLON));
    }

    // ---------------------------------------------------------------------------------------------
    // Expression subset — Pratt loop ported from parse_precedence with the declaration-scoped
    // slice of the rule table.
    // ---------------------------------------------------------------------------------------------

    /// Godot's `Precedence` enum (higher ordinal binds tighter); the declaration order mirrors
    /// `gdscript_parser.h` so the table can be extended rule-by-rule to the full set.
    private enum Precedence {
        NONE,
        ASSIGNMENT,
        CAST,
        TERNARY,
        LOGIC_OR,
        LOGIC_AND,
        LOGIC_NOT,
        CONTENT_TEST,
        COMPARISON,
        BIT_OR,
        BIT_XOR,
        BIT_AND,
        BIT_SHIFT,
        ADDITION_SUBTRACTION,
        FACTOR,
        SIGN,
        BIT_NOT,
        POWER,
        TYPE_TEST,
        AWAIT,
        CALL,
        ATTRIBUTE,
        SUBSCRIPT,
        PRIMARY
    }

    private @FunctionalInterface interface PrefixRule {
        @Nullable Expression parse();
    }

    private @FunctionalInterface interface InfixRule {
        /// `precedence` is the enclosing `parsePrecedence` cutoff (used by the suffix chain's
        /// continuation check); `canAssign` is Godot's `p_can_assign`, consulted only by the
        /// assignment rule (every other infix parses its operands with `canAssign == false`,
        /// matching the vendor call sites); `extentStart` is the byte offset of the first token
        /// of the left operand's consumed source span, which can precede the operand node's own
        /// range (e.g. a grouping's `(`) and is the correct left edge for the combined node's
        /// range (the tree-sitter path spans enclosing CST nodes over the whole consumed text).
        @Nullable Expression parse(@Nullable Expression left, Precedence precedence, boolean canAssign,
                                   int extentStart);
    }

    private record ParseRule(@Nullable PrefixRule prefix, @Nullable InfixRule infix,
                             @NotNull Precedence precedence) {
        private static final ParseRule NONE = new ParseRule(null, null, Precedence.NONE);
    }

    /// Port of `GDScriptParser::get_rule()` for the Phase 2/3 expression slice: literals,
    /// identifiers, `self`, the full unary/binary operator families, grouping/array/dictionary
    /// prefixes, `await`/`preload`/`super`/get-node prefixes, ternary, `as`/`is`/`not in`, the
    /// assignment family, and the call/attribute/subscript suffixes. Identifier-like tokens
    /// (including the builtin constants `PI`/`TAU`/`INF`/`NAN`) are normalized to `IDENTIFIER`
    /// by `parsePrecedence` before this lookup. Rules outside the subset resolve to
    /// `ParseRule.NONE`.
    private @NotNull ParseRule ruleFor(GdTokenType type) {
        return switch (type) {
            case LITERAL -> new ParseRule(this::parseLiteralExpression, null, Precedence.NONE);
            case IDENTIFIER -> new ParseRule(this::parseIdentifierExpression, null, Precedence.NONE);
            case SELF -> new ParseRule(this::parseSelfExpression, null, Precedence.NONE);
            case PLUS, MINUS -> new ParseRule(this::parseUnaryOperator, this::parseBinaryOperator,
                    Precedence.ADDITION_SUBTRACTION);
            case TILDE, BANG -> new ParseRule(this::parseUnaryOperator, null, Precedence.NONE);
            case NOT -> new ParseRule(this::parseUnaryOperator, this::parseNotInOperator, Precedence.CONTENT_TEST);
            case STAR, SLASH -> new ParseRule(null, this::parseBinaryOperator, Precedence.FACTOR);
            case PERCENT -> new ParseRule(this::parseGetNodeExpression, this::parseBinaryOperator, Precedence.FACTOR);
            case STAR_STAR -> new ParseRule(null, this::parseBinaryOperator, Precedence.POWER);
            case LESS, LESS_EQUAL, GREATER, GREATER_EQUAL, EQUAL_EQUAL, BANG_EQUAL ->
                    new ParseRule(null, this::parseBinaryOperator, Precedence.COMPARISON);
            case AND, AMPERSAND_AMPERSAND -> new ParseRule(null, this::parseBinaryOperator, Precedence.LOGIC_AND);
            case OR, PIPE_PIPE -> new ParseRule(null, this::parseBinaryOperator, Precedence.LOGIC_OR);
            case AMPERSAND -> new ParseRule(null, this::parseBinaryOperator, Precedence.BIT_AND);
            case PIPE -> new ParseRule(null, this::parseBinaryOperator, Precedence.BIT_OR);
            case CARET -> new ParseRule(null, this::parseBinaryOperator, Precedence.BIT_XOR);
            case LESS_LESS, GREATER_GREATER -> new ParseRule(null, this::parseBinaryOperator, Precedence.BIT_SHIFT);
            case IN -> new ParseRule(null, this::parseBinaryOperator, Precedence.CONTENT_TEST);
            case IF -> new ParseRule(null, this::parseTernaryOperator, Precedence.TERNARY);
            case AS -> new ParseRule(null, this::parseCast, Precedence.CAST);
            case IS -> new ParseRule(null, this::parseTypeTest, Precedence.TYPE_TEST);
            case EQUAL, PLUS_EQUAL, MINUS_EQUAL, STAR_EQUAL, STAR_STAR_EQUAL, SLASH_EQUAL, PERCENT_EQUAL,
                 LESS_LESS_EQUAL, GREATER_GREATER_EQUAL, AMPERSAND_EQUAL, PIPE_EQUAL, CARET_EQUAL ->
                    new ParseRule(null, this::parseAssignment, Precedence.ASSIGNMENT);
            case QUESTION_MARK -> new ParseRule(null, this::parseInvalidToken, Precedence.CAST);
            case AWAIT -> new ParseRule(this::parseAwaitExpression, null, Precedence.NONE);
            case DOLLAR -> new ParseRule(this::parseGetNodeExpression, null, Precedence.NONE);
            case PRELOAD -> new ParseRule(this::parsePreloadExpression, null, Precedence.NONE);
            case SUPER -> new ParseRule(this::parseSuperExpression, null, Precedence.NONE);
            case PARENTHESIS_OPEN -> new ParseRule(this::parseGroupingExpression, this::parseSuffixChain,
                    Precedence.CALL);
            case BRACKET_OPEN -> new ParseRule(this::parseArrayExpression, this::parseSuffixChain,
                    Precedence.SUBSCRIPT);
            case BRACE_OPEN -> new ParseRule(this::parseDictionaryExpression, null, Precedence.NONE);
            case PERIOD -> new ParseRule(null, this::parseSuffixChain, Precedence.ATTRIBUTE);
            default -> ParseRule.NONE;
        };
    }

    /// Port of `GDScriptParser::parse_expression`. `canAssign` gates the assignment infix family
    /// (only statement-level expressions pass `true`, like the vendor call sites);
    /// `stopOnAssign` makes the Pratt loop stop before an `=` token (used by dictionary keys to
    /// detect the Lua-style separator).
    private @Nullable Expression parseExpression(boolean canAssign) {
        return parseExpression(canAssign, false);
    }

    private @Nullable Expression parseExpression(boolean canAssign, boolean stopOnAssign) {
        return parsePrecedence(Precedence.ASSIGNMENT, canAssign, stopOnAssign);
    }

    private @Nullable Expression parsePrecedence(Precedence precedence) {
        return parsePrecedence(precedence, false, false);
    }

    private @Nullable Expression parsePrecedence(Precedence precedence, boolean canAssign, boolean stopOnAssign) {
        // Switch multiline mode on for grouping tokens, before consuming them (vendor order:
        // the flag must be active before the tokenizer scans the following token).
        var pushedGrouping = current.type() == GdTokenType.PARENTHESIS_OPEN
                || current.type() == GdTokenType.BRACE_OPEN || current.type() == GdTokenType.BRACKET_OPEN;
        if (pushedGrouping) {
            pushMultiline();
        }
        // Godot normalizes identifier-like tokens (match/when/builtin constants) to plain
        // identifiers before consulting the rule table.
        var rule = ruleFor(isIdentifier(current) ? GdTokenType.IDENTIFIER : current.type());
        if (rule.prefix() == null) {
            // Expected expression; the caller reports the proper context-specific error. All
            // grouping tokens currently have prefix rules, so the pop below is unreachable —
            // it guards the multiline balance against future rule-table regressions.
            if (pushedGrouping) {
                popMultiline();
            }
            return null;
        }

        // The first token of this frame starts the consumed span of every expression built here
        // (infix folds keep that left edge, e.g. a grouping's `(` precedes the inner node's range).
        var extentStart = current.startByte();
        advance(); // Only consume the token if there's a valid rule.
        var left = rule.prefix().parse();

        while (precedence.ordinal() <= ruleFor(current.type()).precedence().ordinal()) {
            var infixRule = ruleFor(current.type());
            if (left == null || infixRule.infix() == null
                    || (stopOnAssign && current.type() == GdTokenType.EQUAL)) {
                return left;
            }
            // Switch multiline mode on for infix grouping operators, before consuming the token.
            if (current.type() == GdTokenType.PARENTHESIS_OPEN || current.type() == GdTokenType.BRACKET_OPEN) {
                pushMultiline();
            }
            advance();
            left = infixRule.infix().parse(left, precedence, canAssign, extentStart);
        }
        return left;
    }

    private @NotNull Expression parseLiteralExpression() {
        var kind = previous.literalKind().astKind();
        if (kind == null) {
            throw new IllegalStateException("GDScript parser bug: literal token without a literal kind.");
        }
        return new LiteralExpression(kind, previous.source(), tokenRange(previous));
    }

    private @NotNull Expression parseIdentifierExpression() {
        return new IdentifierExpression(previous.source(), tokenRange(previous));
    }

    private @NotNull Expression parseSelfExpression() {
        return new SelfExpression(tokenRange(previous));
    }

    /// Port of `GDScriptParser::parse_grouping`: produces the inner expression itself (no wrapper
    /// node). Multiline was pushed by `parsePrecedence` before the `(` was consumed; the pop
    /// happens here before the closing parenthesis (vendor order), even on failure.
    private @Nullable Expression parseGroupingExpression() {
        var grouped = parseExpression(false);
        popMultiline();
        if (grouped == null) {
            pushError("Expected grouping expression.");
            return null;
        }
        consume(GdTokenType.PARENTHESIS_CLOSE, "Expected closing \")\" after grouping expression.");
        return grouped;
    }

    /// Port of `GDScriptParser::parse_array`: elements parse with assignment disallowed; a failed
    /// element is reported and skipped (Godot inserts no placeholder); trailing comma allowed.
    private @NotNull Expression parseArrayExpression() {
        var start = previous.startByte();
        var elements = new ArrayList<Expression>();
        if (!check(GdTokenType.BRACKET_CLOSE)) {
            do {
                if (check(GdTokenType.BRACKET_CLOSE)) {
                    // Allow for trailing comma.
                    break;
                }
                var element = parseExpression(false);
                if (element == null) {
                    pushError("Expected expression as array element.");
                } else {
                    elements.add(element);
                }
            } while (match(GdTokenType.COMMA) && !isAtEnd());
        }
        popMultiline();
        consume(GdTokenType.BRACKET_CLOSE, "Expected closing \"]\" after array elements.");
        return new ArrayExpression(List.copyOf(elements), false, range(start, previous.endByte()));
    }

    /// Dictionary styles tracked by `parseDictionaryExpression` (vendor `DictionaryNode::Style`).
    private enum DictStyle {PYTHON_DICT, LUA_TABLE}

    /// Port of `GDScriptParser::parse_dictionary`: Python (`{key: value}`) and Lua
    /// (`{key = value}`) styles are decided by the first entry's separator and cannot be mixed.
    /// Lua-style identifier/string keys normalize to `string_name` literals (the AST contract's
    /// pinned shape, mirroring the tree-sitter mapper). Keys parse with `stopOnAssign` so the
    /// Pratt loop leaves a Lua `=` separator unconsumed. A missing key or value is reported; the
    /// entry is kept with a zero-width `null` literal placeholder for the missing side (the AST
    /// analog of Godot's dummy recovery `LiteralNode`) and dropped only when both sides failed.
    private @NotNull Expression parseDictionaryExpression() {
        var start = previous.startByte();
        var entries = new ArrayList<DictEntry>();
        DictStyle style = null;
        if (!check(GdTokenType.BRACE_CLOSE)) {
            do {
                if (check(GdTokenType.BRACE_CLOSE)) {
                    // Allow for trailing comma.
                    break;
                }
                var keyExtentStart = current.startByte();
                var key = parseExpression(false, true);
                var keyExtentEnd = previous.endByte();
                if (key == null) {
                    pushError("Expected expression as dictionary key.");
                }
                if (style == null) {
                    switch (current.type()) {
                        case COLON -> style = DictStyle.PYTHON_DICT;
                        case EQUAL -> style = DictStyle.LUA_TABLE;
                        default -> pushError("Expected \":\" or \"=\" after dictionary key.");
                    }
                    // Godot's style field defaults to PYTHON_DICT when nothing valid decided it.
                    if (style == null) {
                        style = DictStyle.PYTHON_DICT;
                    }
                }
                var normalizedKey = key;
                if (style == DictStyle.LUA_TABLE) {
                    if (key instanceof IdentifierExpression(var name, _)) {
                        normalizedKey = new LiteralExpression("string_name", "&\"" + name + "\"", key.range());
                    } else if (key instanceof LiteralExpression(var kind, var sourceText, _)
                            && "string".equals(kind)) {
                        normalizedKey = new LiteralExpression("string_name", "&" + sourceText, key.range());
                    } else if (key != null) {
                        pushError("Expected identifier or string as Lua-style dictionary key (e.g \"{ key = value }\").");
                    }
                    if (!match(GdTokenType.EQUAL)) {
                        if (match(GdTokenType.COLON)) {
                            pushError("Expected \"=\" after dictionary key. Mixing dictionary styles is not allowed.");
                            // Vendor consumes one extra token here ("Consume wrong separator
                            // anyway", though `match` already consumed it); mirrored verbatim.
                            if (!isAtEnd()) {
                                advance();
                            }
                        } else {
                            pushError("Expected \"=\" after dictionary key.");
                        }
                    }
                } else {
                    if (!match(GdTokenType.COLON)) {
                        if (match(GdTokenType.EQUAL)) {
                            pushError("Expected \":\" after dictionary key. Mixing dictionary styles is not allowed.");
                            // Same vendor extra-consumption quirk as the Lua branch above.
                            if (!isAtEnd()) {
                                advance();
                            }
                        } else {
                            pushError("Expected \":\" after dictionary key.");
                        }
                    }
                }
                var valueExtentStart = current.startByte();
                var value = parseExpression(false);
                if (value == null) {
                    pushError("Expected expression as dictionary value.");
                }
                // Entry edges use the consumed spans (a grouping `(`/`)` around a key or value
                // belongs to the entry, like the tree-sitter pair node's range).
                if (normalizedKey != null && value != null) {
                    entries.add(new DictEntry(normalizedKey, value,
                            range(keyExtentStart, previous.endByte())));
                } else if (normalizedKey != null) {
                    entries.add(new DictEntry(normalizedKey, nullPlaceholder(),
                            range(keyExtentStart, keyExtentEnd)));
                } else if (value != null) {
                    entries.add(new DictEntry(nullPlaceholder(), value,
                            range(valueExtentStart, previous.endByte())));
                }
            } while (match(GdTokenType.COMMA) && !isAtEnd());
        }
        popMultiline();
        consume(GdTokenType.BRACE_CLOSE, "Expected closing \"}\" after dictionary elements.");
        return new DictionaryExpression(List.copyOf(entries), false, range(start, previous.endByte()));
    }

    /// Zero-width `null` literal standing in for a missing dictionary key/value, the AST analog
    /// of Godot's dummy recovery `LiteralNode` (empty Variant).
    private @NotNull LiteralExpression nullPlaceholder() {
        return new LiteralExpression("null", "", range(current.startByte(), current.startByte()));
    }

    /// Path states of `parseGetNodeExpression` (vendor `parse_get_node`'s `PathState`).
    private enum NodePathState {START, SLASH, PERCENT, NODE_NAME}

    /// Port of `GDScriptParser::parse_get_node` (`$...` / `%...` shorthands). Path segments are
    /// node names (Godot's `is_node_name()` set) or string literals; `/` is valid only at the
    /// start or after a name, `%` only at the start or right after `/`. The AST node stores the
    /// raw source slice (including the leading `$`/`%`), matching the tree-sitter path's
    /// `GetNodeExpression.sourceText` contract.
    private @Nullable Expression parseGetNodeExpression() {
        var startToken = previous;
        if (!isNodeName(current) && current.type() != GdTokenType.LITERAL
                && current.type() != GdTokenType.SLASH && current.type() != GdTokenType.PERCENT) {
            pushError("Expected node path as string or identifier after \"%s\"."
                    .formatted(startToken.type().displayName()));
            return null;
        }
        if (current.type() == GdTokenType.LITERAL && current.literalKind() != GdLiteralKind.STRING) {
            pushError("Expected node path as string or identifier after \"%s\"."
                    .formatted(startToken.type().displayName()));
            return null;
        }

        var state = NodePathState.START;
        if (startToken.type() == GdTokenType.DOLLAR) {
            // An initial slash (absolute path) is handled by the loop's SLASH branch.
            match(GdTokenType.SLASH);
        }
        do {
            if (previous.type() == GdTokenType.PERCENT) {
                if (state != NodePathState.START && state != NodePathState.SLASH) {
                    pushError("\"%\" is only valid in the beginning of a node name (either after \"$\" or after \"/\")");
                    return null;
                }
                state = NodePathState.PERCENT;
            } else if (previous.type() == GdTokenType.SLASH) {
                if (state != NodePathState.START && state != NodePathState.NODE_NAME) {
                    pushError("\"/\" is only valid at the beginning of the path or after a node name.");
                    return null;
                }
                state = NodePathState.SLASH;
            }

            if (match(GdTokenType.LITERAL)) {
                if (previous.literalKind() != GdLiteralKind.STRING) {
                    var after = switch (state) {
                        case PERCENT -> "%";
                        case SLASH -> "/";
                        default -> "$";
                    };
                    pushError("Expected node path as string or identifier after \"%s\".".formatted(after));
                    return null;
                }
                state = NodePathState.NODE_NAME;
            } else if (isNodeName(current)) {
                advance();
                state = NodePathState.NODE_NAME;
            } else if (!check(GdTokenType.SLASH) && !check(GdTokenType.PERCENT)) {
                pushError("Unexpected \"%s\" in node path.".formatted(
                        current.type() == GdTokenType.IDENTIFIER ? current.source()
                                : current.type().displayName()));
                return null;
            }
        } while (match(GdTokenType.SLASH) || match(GdTokenType.PERCENT));

        return new GetNodeExpression(slice(startToken.startByte(), previous.endByte()),
                range(startToken.startByte(), previous.endByte()));
    }

    /// Port of `GDScriptParser::parse_preload`: the path is a general expression (constant
    /// folding and resource-existence checks belong to later Godot phases, cut here); one
    /// trailing comma is allowed.
    private @Nullable Expression parsePreloadExpression() {
        var startToken = previous;
        pushMultiline();
        consume(GdTokenType.PARENTHESIS_OPEN, "Expected \"(\" after \"preload\".");
        var path = parseExpression(false);
        if (path == null) {
            pushError("Expected resource path after \"(\".");
        }
        match(GdTokenType.COMMA); // Allow trailing comma.
        popMultiline();
        consume(GdTokenType.PARENTHESIS_CLOSE, "Expected \")\" after preload path.");
        if (path == null) {
            return null;
        }
        return new PreloadExpression(path, range(startToken.startByte(), previous.endByte()));
    }

    /// Port of `GDScriptParser::parse_await`: the operand parses at `PREC_AWAIT`, so call/
    /// attribute/subscript suffixes bind into the awaited expression (`await timer.timeout`).
    private @Nullable Expression parseAwaitExpression() {
        var startToken = previous;
        var operand = parsePrecedence(Precedence.AWAIT);
        if (operand == null) {
            pushError("Expected signal or coroutine after \"await\".");
            return null;
        }
        return new AwaitExpression(operand, range(startToken.startByte(), previous.endByte()));
    }

    /// Port of the `SUPER` branch of `GDScriptParser::parse_call`, mapped to the AST contract's
    /// pinned shapes: `super(...)` is a `CallExpression` on an `IdentifierExpression("super")`
    /// callee, `super.method(...)` an `AttributeExpression` with a single call step. The method
    /// name follows vendor `consume(IDENTIFIER)`, which accepts the whole `is_identifier()` set
    /// (`match`/`when`/builtin constants, e.g. `super.match()`) but not the wider
    /// `is_node_name()` set. Godot's "implicit super() outside a function" check needs
    /// function-context tracking and is outside the ported parse-time check set (plan §5.6).
    private @Nullable Expression parseSuperExpression() {
        var superToken = previous;
        var superIdentifier = new IdentifierExpression("super", tokenRange(superToken));
        if (check(GdTokenType.PARENTHESIS_OPEN)) {
            pushMultiline();
            advance();
            var arguments = parseCallArguments();
            if (arguments == null) {
                return null;
            }
            return new CallExpression(superIdentifier, arguments,
                    range(superToken.startByte(), previous.endByte()));
        }
        consume(GdTokenType.PERIOD, "Expected \".\" or \"(\" after \"super\".");
        if (!consume(GdTokenType.IDENTIFIER, "Expected function name after \".\".")) {
            return null;
        }
        var nameToken = previous;
        if (!check(GdTokenType.PARENTHESIS_OPEN)) {
            pushError("Expected \"(\" after function name.");
            return null;
        }
        pushMultiline();
        advance();
        var arguments = parseCallArguments();
        if (arguments == null) {
            return null;
        }
        var step = new AttributeCallStep(nameToken.source(), arguments,
                range(nameToken.startByte(), previous.endByte()));
        return new AttributeExpression(superIdentifier, List.of(step),
                range(superToken.startByte(), previous.endByte()));
    }

    /// Port of `GDScriptParser::parse_unary_operator`: `+`/`-` at `PREC_SIGN`, `~` at
    /// `PREC_BIT_NOT`, `not`/`!` at `PREC_LOGIC_NOT`.
    private @Nullable Expression parseUnaryOperator() {
        var operator = previous;
        var operandPrecedence = switch (operator.type()) {
            case PLUS, MINUS -> Precedence.SIGN;
            case TILDE -> Precedence.BIT_NOT;
            case NOT, BANG -> Precedence.LOGIC_NOT;
            default -> throw new IllegalStateException(
                    "GDScript parser bug: unary operator rule entered on " + operator.type());
        };
        var operand = parsePrecedence(operandPrecedence);
        if (operand == null) {
            pushError("Expected expression after \"%s\" operator.".formatted(operator.type().displayName()));
            return null;
        }
        // The right edge is the last consumed token, not the operand node's own end (a trailing
        // grouping `)` belongs to the consumed span, matching the tree-sitter path's CST ranges).
        return new UnaryExpression(operator.source(), operand,
                range(operator.startByte(), previous.endByte()));
    }

    /// Port of `GDScriptParser::parse_binary_operator` (left-associative via precedence + 1 on the
    /// right operand, which also makes `**` left-associative like Godot).
    private @Nullable Expression parseBinaryOperator(@Nullable Expression left, Precedence entryPrecedence,
                                                     boolean canAssign, int extentStart) {
        var operator = previous;
        var precedence = ruleFor(operator.type()).precedence();
        var right = parsePrecedence(Precedence.values()[precedence.ordinal() + 1]);
        if (right == null) {
            pushError("Expected expression after \"%s\" operator.".formatted(operator.type().displayName()));
            return null;
        }
        if (left == null) {
            return null;
        }
        return new BinaryExpression(operator.source(), left, right,
                range(extentStart, previous.endByte()));
    }

    /// Port of `GDScriptParser::parse_binary_not_in_operator`. Godot wraps the membership binary
    /// in a logic-not unary node; the AST contract models the pair as a single
    /// `BinaryExpression("not in")` (the tree-sitter path's pinned shape). When the `in` token is
    /// missing, the vendor still parses the right-hand side as if it were present (the recovery
    /// message then names "not"); mirrored here.
    private @Nullable Expression parseNotInOperator(@Nullable Expression left, Precedence entryPrecedence,
                                                    boolean canAssign, int extentStart) {
        var matchedIn = match(GdTokenType.IN);
        if (!matchedIn) {
            pushError("Expected \"in\" after \"not\" in content-test operator.");
        }
        var right = parsePrecedence(Precedence.COMPARISON); // PREC_CONTENT_TEST + 1
        if (right == null) {
            pushError("Expected expression after \"%s\" operator.".formatted(matchedIn ? "in" : "not"));
            return null;
        }
        if (left == null) {
            return null;
        }
        return new BinaryExpression("not in", left, right,
                range(extentStart, previous.endByte()));
    }

    /// Port of `GDScriptParser::parse_ternary_operator` (`true_value if condition else false_value`).
    /// Both branches parse at `PREC_TERNARY` with assignment disallowed. A failed branch fails the
    /// whole expression (the AST has no nullable expression components, unlike Godot's nodes),
    /// but token consumption still follows the vendor order: condition, `else`, false branch.
    private @Nullable Expression parseTernaryOperator(@Nullable Expression left, Precedence entryPrecedence,
                                                      boolean canAssign, int extentStart) {
        var condition = parsePrecedence(Precedence.TERNARY);
        if (condition == null) {
            pushError("Expected expression as ternary condition after \"if\".");
        }
        consume(GdTokenType.ELSE, "Expected \"else\" after ternary operator condition.");
        var falseBranch = parsePrecedence(Precedence.TERNARY);
        if (falseBranch == null) {
            pushError("Expected expression after \"else\".");
        }
        if (left == null || condition == null || falseBranch == null) {
            return null;
        }
        return new ConditionalExpression(condition, left, falseBranch,
                range(extentStart, previous.endByte()));
    }

    /// Port of `GDScriptParser::parse_cast`: the right side is a type specifier (parsed by
    /// `parse_type`, not a general expression). On a missing type Godot drops the cast and
    /// returns the operand, mirrored here.
    private @Nullable Expression parseCast(@Nullable Expression left, Precedence entryPrecedence,
                                           boolean canAssign, int extentStart) {
        var type = parseType(false);
        if (type == null) {
            pushError("Expected type specifier after \"as\".");
            return left;
        }
        if (left == null) {
            return null;
        }
        return new CastExpression(left, type, range(extentStart, type.range().endByte()));
    }

    /// Port of `GDScriptParser::parse_type_test`. Godot represents `is not` as a logic-not unary
    /// wrapping the test node; the AST contract carries the `negated` flag on
    /// `TypeTestExpression` instead (the tree-sitter path's pinned shape). On a missing type the
    /// expression degrades to the left operand (Godot keeps a typeless test node, which the AST
    /// cannot represent).
    private @Nullable Expression parseTypeTest(@Nullable Expression left, Precedence entryPrecedence,
                                               boolean canAssign, int extentStart) {
        var negated = match(GdTokenType.NOT);
        var type = parseType(false);
        if (type == null) {
            pushError("Expected type specifier after \"is%s\".".formatted(negated ? " not" : ""));
            return left;
        }
        if (left == null) {
            return null;
        }
        return new TypeTestExpression(left, type, negated,
                range(extentStart, type.range().endByte()));
    }

    /// Port of `GDScriptParser::parse_assignment` for the whole `=`/`+=`/.../`^=` family. The
    /// operator text comes from the token source (`"="` for plain assignment). The right-hand
    /// side parses with assignment disallowed, so `a = b = 1` reports the nested-assignment
    /// error exactly like Godot. The vendor's assignee node-type check (`SUBSCRIPT`/`IDENTIFIER`)
    /// is a parse-time semantic check outside the ported set (plan §5.6) and is not enforced;
    /// invalid targets produce an `AssignmentExpression` for the analyzer to reject.
    private @Nullable Expression parseAssignment(@Nullable Expression left, Precedence entryPrecedence,
                                                 boolean canAssign, int extentStart) {
        var operator = previous;
        if (!canAssign) {
            pushError("Assignment is not allowed inside an expression.");
            return parseExpression(false);
        }
        if (left == null) {
            return parseExpression(false);
        }
        var right = parseExpression(false);
        if (right == null) {
            pushError("Expected an expression after \"=\".");
            return null;
        }
        return new AssignmentExpression(operator.source(), left, right,
                range(extentStart, previous.endByte()));
    }

    /// Port of `GDScriptParser::parse_invalid_token` for `?` (GDScript 2.x ternary syntax hint).
    private @Nullable Expression parseInvalidToken(@Nullable Expression left, Precedence entryPrecedence,
                                                   boolean canAssign, int extentStart) {
        if (previous.type() == GdTokenType.QUESTION_MARK) {
            pushError("Unexpected \"?\" in source. If you want a ternary operator, use \"truthy_value if true_condition else falsy_value\".");
        }
        return left;
    }

    /// Consumes one contiguous attribute/call/subscript suffix run (`obj.a.b(1).c[2]`) and folds
    /// it into the established AST shapes: a `.`-started run becomes a single
    /// `AttributeExpression(base, steps)`; a call/subscript directly after `.name` folds into the
    /// chain's last step; any other call/subscript wraps the expression built so far in a
    /// `CallExpression`/`SubscriptExpression`.
    ///
    /// Semantically equivalent to Godot's per-infix dispatch (`parse_attribute` / `parse_call` /
    /// `parse_subscript` driven by the rule table) — the run is batched here only so the chain's
    /// steps accumulate in a single list and freeze once at the end, instead of re-copying an
    /// immutable step list per suffix. Equivalence notes:
    /// - Token-level order is unchanged: every grouping token gets its own multiline push before
    ///   consumption and pop before its closer.
    /// - Continuation honors the same precedence check as Godot's infix loop (today it always
    ///   passes, since every caller parses at precedence below `CALL` and the three suffix rules
    ///   are the only infixes at `CALL`/`ATTRIBUTE`/`SUBSCRIPT`; the check keeps the loop honest
    ///   if the rule table ever gains an operator in that range).
    ///
    /// Entered from the Pratt loop with `previous` holding the first suffix token (multiline
    /// already pushed when it was `(` or `[`); subsequent suffix tokens are consumed by this
    /// loop with that same order. `entryPrecedence` is the precedence of the enclosing
    /// `parsePrecedence` call, used by the continuation check above. `canAssign` is accepted for
    /// `InfixRule` uniformity but ignored: indices and arguments always parse with assignment
    /// disallowed, like the vendor call sites in `parse_call`/`parse_subscript`. `extentStart`
    /// is the consumed-span start of the base expression (a grouping `(` can precede the base
    /// node's own range) and stays the left edge of every node the chain produces.
    private @Nullable Expression parseSuffixChain(@Nullable Expression left, Precedence entryPrecedence,
                                                  boolean canAssign, int extentStart) {
        if (left == null) {
            return null;
        }
        var result = left;
        Expression chainBase = null;
        List<AttributeStep> steps = null;
        while (true) {
            switch (previous.type()) {
                case PERIOD -> {
                    // Member names accept Godot's `is_node_name()` set (nearly all keywords). A
                    // missing name is reported but does not end the chain, so a following
                    // call/subscript still folds onto the previous property step (the recovery
                    // shape of the former per-infix implementation on this code path).
                    if (!isNodeName(current)) {
                        pushError("Expected identifier after \".\" for attribute access.");
                    } else {
                        advance();
                        if (steps == null) {
                            chainBase = result;
                            steps = new ArrayList<>();
                        }
                        steps.add(new AttributePropertyStep(previous.source(), tokenRange(previous)));
                    }
                }
                case PARENTHESIS_OPEN -> {
                    var arguments = parseCallArguments();
                    if (arguments == null) {
                        return null;
                    }
                    var end = previous.endByte();
                    if (steps != null && steps.getLast() instanceof AttributePropertyStep(var name, var stepRange)) {
                        steps.removeLast();
                        steps.add(new AttributeCallStep(name, arguments,
                                range(stepRange.startByte(), end)));
                    } else {
                        var callee = finishChain(result, chainBase, steps, extentStart);
                        result = new CallExpression(callee, arguments,
                                range(extentStart, end));
                        chainBase = null;
                        steps = null;
                    }
                }
                case BRACKET_OPEN -> {
                    var index = parseExpression(false);
                    if (index == null) {
                        pushError("Expected expression after \"[\".");
                    }
                    // The pop and the closing bracket are handled even when the index fails
                    // (like Godot), so a broken subscript cannot leak multiline mode.
                    popMultiline();
                    consume(GdTokenType.BRACKET_CLOSE, "Expected \"]\" after subscription index.");
                    if (index == null) {
                        return null;
                    }
                    var end = previous.endByte();
                    if (steps != null && steps.getLast() instanceof AttributePropertyStep(var name, var stepRange)) {
                        steps.removeLast();
                        steps.add(new AttributeSubscriptStep(name, List.of(index),
                                range(stepRange.startByte(), end)));
                    } else {
                        var baseExpression = finishChain(result, chainBase, steps, extentStart);
                        result = new SubscriptExpression(baseExpression, List.of(index),
                                range(extentStart, end));
                        chainBase = null;
                        steps = null;
                    }
                }
                default -> throw new IllegalStateException(
                        "GDScript parser bug: suffix chain entered on " + previous.type());
            }

            // Continue the chain on the next suffix token, honoring the same precedence cutoff
            // as Godot's infix loop and consuming with the Pratt loop's own order (multiline
            // push before a grouping token, then advance).
            var nextType = current.type();
            var isSuffix = nextType == GdTokenType.PARENTHESIS_OPEN || nextType == GdTokenType.BRACKET_OPEN
                    || nextType == GdTokenType.PERIOD;
            if (!isSuffix || entryPrecedence.ordinal() > ruleFor(nextType).precedence().ordinal()) {
                return finishChain(result, chainBase, steps, extentStart);
            }
            if (nextType != GdTokenType.PERIOD) {
                pushMultiline();
            }
            advance();
        }
    }

    /// Freezes an open attribute chain (if any) into a single `AttributeExpression` whose left
    /// edge is the base's consumed-span start (`extentStart`, see `parseSuffixChain`).
    private @NotNull Expression finishChain(@NotNull Expression result, @Nullable Expression chainBase,
                                            @Nullable List<AttributeStep> steps, int extentStart) {
        if (steps == null) {
            return result;
        }
        if (chainBase == null) {
            throw new IllegalStateException("GDScript parser bug: attribute chain without a base.");
        }
        return new AttributeExpression(chainBase, List.copyOf(steps),
                range(extentStart, steps.getLast().range().endByte()));
    }

    /// Port of the argument loop of `GDScriptParser::parse_call` (non-`super` branch): assumes the
    /// opening parenthesis was already consumed and multiline pushed; pops multiline before
    /// consuming the closer. Returns null when any argument failed.
    private @Nullable List<Expression> parseCallArguments() {
        var arguments = new ArrayList<Expression>();
        var failed = false;
        do {
            if (check(GdTokenType.PARENTHESIS_CLOSE)) {
                // Allow for trailing comma.
                break;
            }
            var argument = parseExpression(false);
            if (argument == null) {
                pushError("Expected expression as the function argument.");
                failed = true;
            } else {
                arguments.add(argument);
            }
        } while (match(GdTokenType.COMMA));

        popMultiline();
        consume(GdTokenType.PARENTHESIS_CLOSE, "Expected closing \")\" after call arguments.");
        return failed ? null : List.copyOf(arguments);
    }

    // ---------------------------------------------------------------------------------------------
    // Small helpers.
    // ---------------------------------------------------------------------------------------------

    private @NotNull Range range(int startByte, int endByte) {
        return index.rangeOf(startByte, endByte);
    }

    private @NotNull Range tokenRange(GdToken token) {
        return index.rangeOf(token.startByte(), token.endByte());
    }

    private @NotNull String slice(int startByte, int endByte) {
        return new String(sourceBytes, startByte, endByte - startByte, StandardCharsets.UTF_8);
    }

    /// Port of `GDScriptTokenizer::Token::get_debug_name` for the class-body error.
    private static @NotNull String debugName(GdToken token) {
        if (token.type() == GdTokenType.IDENTIFIER) {
            return "identifier \"%s\"".formatted(token.source());
        }
        return "\"%s\"".formatted(token.type().displayName());
    }

    /// Variant type names used by the `extends` literal error, mapped from our literal kinds.
    private static @NotNull String variantTypeName(GdLiteralKind kind) {
        return switch (kind) {
            case INTEGER -> "int";
            case FLOAT -> "float";
            case STRING -> "String";
            case STRING_NAME -> "StringName";
            case NODE_PATH -> "NodePath";
            case TRUE, FALSE -> "bool";
            case NULL -> "Nil";
            case NONE -> "Variant";
        };
    }
}
