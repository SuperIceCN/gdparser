package dev.superice.gdparser.infra.treesitter;

import dev.superice.gdparser.frontend.cst.CstAdapter;
import dev.superice.gdparser.frontend.cst.CstNodeView;
import dev.superice.gdparser.frontend.gdscript.GdScriptParserFacade;
import org.jetbrains.annotations.NotNull;
import org.treesitter.TSLanguage;
import org.treesitter.TSParser;
import org.treesitter.TSNode;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/// Legacy native parser facade, retained for CST consumers and parser parity tests.
///
/// Thread-safety contract: a facade instance holds only the shared, immutable `TSLanguage` and
/// keeps no parse state; every parse entry point creates a fresh `TSParser` under the hood.
/// Concurrent parse calls on a shared instance are therefore safe, and `CstToAstMapper` is
/// likewise stateless (per-call mapping context), so downstream tooling such as GDCC may share
/// one facade and one mapper across analyses without locking or pooling.
///
/// @deprecated Use [GdScriptParserFacade] for pure-Java AST parsing and completion.
/// The CST-specific methods remain available for existing consumers during migration.
@Deprecated
public final class GdParserFacade {

    private final TSLanguage language;

    public GdParserFacade(TSLanguage language) {
        this.language = language;
    }

    public static GdParserFacade withDefaultLanguage() {
        return new GdParserFacade(GdLanguageLoader.load());
    }

    /// @deprecated Use [GdScriptParserFacade#parse(String)] for typed syntax and diagnostics.
    @Deprecated
    public GdParseSnapshot parseSnapshot(String source) {
        var root = parseRootNode(source);
        return new GdParseSnapshot(root.getType(), root.toString(), root.hasError());
    }

    /// @deprecated Use [GdScriptParserFacade#parse(String)] unless a legacy CST is required.
    @Deprecated
    public CstNodeView parseCstRoot(String source) {
        return CstAdapter.fromNode(parseRootNode(source));
    }

    /// Resolves the completion context at `byteOffset` (a UTF-8 byte offset into `source`,
    /// clamped into range) from the natural CST structure of the - possibly incomplete - input.
    ///
    /// @deprecated Use [GdScriptParserFacade#parseCompletionContext(String, long)].
    @Deprecated
    public @NotNull CompletionContext parseCompletionContext(String source, long byteOffset) {
        Objects.requireNonNull(source, "source must not be null");
        var sourceBytes = source.getBytes(StandardCharsets.UTF_8);
        var offset = Math.clamp(byteOffset, 0, sourceBytes.length);
        var root = parseCstRoot(source);
        return CompletionContextAnalyzer.analyze(sourceBytes, root, offset);
    }

    private TSNode parseRootNode(String source) {
        GdTreeSitterRuntimeBootstrap.initialize();
        var parser = new TSParser();
        if (!parser.setLanguage(language)) {
            throw new IllegalStateException("Failed to set parser language: " + GdLanguageLoader.SYMBOL_NAME);
        }
        var tree = parser.parseString(null, source);
        if (tree == null) {
            throw new IllegalStateException("Parser returned null tree for source input");
        }
        return tree.getRootNode();
    }
}
