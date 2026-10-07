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

    /// Strict parse: throws on the first ERROR diagnostic (mirroring
    /// `CstToAstMapper.mapStrict`); warnings alone do not fail.
    ///
    /// @throws IllegalStateException with message `Parsing failed at <nodeType>: <message>`
    public @NotNull SourceFile parseStrict(@NotNull String source) {
        var result = parse(source);
        var firstError = result.diagnostics().stream()
                .filter(d -> d.severity() == AstDiagnosticSeverity.ERROR)
                .findFirst();
        if (firstError.isPresent()) {
            var error = firstError.get();
            throw new IllegalStateException("Parsing failed at " + error.nodeType() + ": " + error.message());
        }
        return result.ast();
    }

    /// Raw token stream from `GdTokenizer.scan()` up to and including the EOF token.
    ///
    /// Unlike `parse`, no parser drives the tokenizer here, so multiline mode is never pushed:
    /// NEWLINE/INDENT/DEDENT tokens appear inside groupings, and indentation lines that are
    /// legal for the parser may surface context-dependent ERROR tokens (e.g. an unindent
    /// mismatch inside brackets). Consequently a non-empty ERROR count in this stream is NOT a
    /// validity criterion for the source — judge legality via `parse` diagnostics instead.
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
