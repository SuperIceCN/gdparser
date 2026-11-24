package dev.superice.gdparser.frontend.gdscript;

import dev.superice.gdparser.frontend.ast.AstDiagnosticSeverity;
import dev.superice.gdparser.frontend.ast.AstMappingResult;
import dev.superice.gdparser.frontend.ast.SourceFile;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/// Public entry point of the pure-Java GDScript parser pipeline (Godot port).
///
/// Thread-safety contract: the facade holds no state — every entry point creates a fresh
/// `GdScriptParser`/`GdTokenizer` per call (those instances are stateful and not thread-safe),
/// so a shared facade instance is safe to use concurrently without locking or pooling.
public final class GdScriptParserFacade {

    public GdScriptParserFacade() {
    }

    /// Tolerant parse: never throws on illegal input, returning the (possibly partial) AST plus
    /// all accumulated diagnostics — tokenizer errors first (in scan order) interleaved with
    /// parser errors as encountered.
    public @NotNull AstMappingResult parse(@NotNull String source) {
        Objects.requireNonNull(source, "source must not be null");
        return new GdScriptParser().parse(source);
    }

    /// Strict parse: throws on the first `ERROR` diagnostic; warnings alone do not fail.
    ///
    /// @throws IllegalStateException with message `Parsing failed at <nodeType>: <message>`
    public @NotNull SourceFile parseStrict(@NotNull String source) {
        return requireStrict(parse(source));
    }

    static @NotNull SourceFile requireStrict(@NotNull AstMappingResult result) {
        Objects.requireNonNull(result, "result must not be null");
        var firstError = result.diagnostics().stream()
                .filter(d -> d.severity() == AstDiagnosticSeverity.ERROR)
                .findFirst();
        if (firstError.isPresent()) {
            var error = firstError.get();
            throw new IllegalStateException("Parsing failed at " + error.nodeType() + ": " + error.message());
        }
        return result.ast();
    }

    /// Resolves completion from tolerant syntax and tokens, without native dependencies.
    /// `byteOffset` is a UTF-8 byte offset, clamped to the physical source bounds.
    public @NotNull CompletionContext parseCompletionContext(@NotNull String source, long byteOffset) {
        Objects.requireNonNull(source, "source must not be null");
        return CompletionContextAnalyzer.analyze(source, parse(source).ast(), byteOffset);
    }

    /// Standalone token stream from `GdTokenizer.scan()` up to and including the EOF token.
    ///
    /// Standalone tokenization suppresses `NEWLINE`, `INDENT`, and `DEDENT` layout tokens
    /// as well as indentation checks inside unclosed `(`, `[`, and `{` brackets via the
    /// tokenizer's bracket stack, and emits lexical bracket-mismatch `ERROR` tokens on
    /// invalid closers. Because standalone tokenization does not perform syntactic parsing,
    /// it does not recognize parenthesized multiline lambda suites — judge syntactic legality
    /// via `parse` instead.
    public @NotNull List<GdToken> tokenize(@NotNull String source) {
        Objects.requireNonNull(source, "source must not be null");
        var tokenizer = new GdTokenizer(source);
        var tokens = new ArrayList<GdToken>();
        GdToken token;
        do {
            token = tokenizer.scan();
            tokens.add(token);
        } while (token.type() != GdTokenType.EOF);
        return List.copyOf(tokens);
    }
}
