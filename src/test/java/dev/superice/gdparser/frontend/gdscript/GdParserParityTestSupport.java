package dev.superice.gdparser.frontend.gdscript;

import dev.superice.gdparser.frontend.ast.AstDiagnosticSeverity;
import dev.superice.gdparser.frontend.ast.AstMappingResult;
import dev.superice.gdparser.frontend.lowering.CstToAstMapper;
import dev.superice.gdparser.frontend.serialize.AstSexprSerializer;
import dev.superice.gdparser.infra.treesitter.GdParserFacade;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/// Shared support for dual-path parity tests between the tree-sitter pipeline (reference
/// side) and the Godot-port parser (new side, `GdScriptParser`).
///
/// Parity definition: both paths parse the same source, and their ASTs serialize via
/// `AstSexprSerializer` to character-identical text (all ranges included). Registered
/// divergence sets (operator-precedence divergences, Godot-only constructs) are NOT
/// asserted through `assertParity` — they get dedicated Godot-structure assertions.
@SuppressWarnings("deprecation") // The legacy backend remains a regression reference.
abstract class GdParserParityTestSupport {

    /// GDScript fixture corpus shared with the tree-sitter test suites.
    protected static final Path FIXTURE_DIR = Path.of("src", "test", "resources", "gdscript");

    private static final GdParserFacade TREE_SITTER_FACADE = GdParserFacade.withDefaultLanguage();
    private static final CstToAstMapper TREE_SITTER_MAPPER = new CstToAstMapper();
    private static final AstSexprSerializer SERIALIZER = new AstSexprSerializer();

    /// Parses with the reference tree-sitter path (CST adapter + lowering mapper).
    protected static AstMappingResult parseViaTreeSitter(String source) {
        return TREE_SITTER_MAPPER.map(source, TREE_SITTER_FACADE.parseCstRoot(source));
    }

    /// Reference-side serialized AST.
    protected static String treeSitterSexp(String source) {
        return SERIALIZER.serialize(parseViaTreeSitter(source).ast());
    }

    /// Parses with the new Godot-port parser (driven directly, not through a facade, per the
    /// migration plan).
    protected static AstMappingResult parseViaGdScript(String source) {
        return new GdScriptParser().parse(source);
    }

    /// New-parser-side serialized AST.
    protected static String gdScriptSexp(String source) {
        return SERIALIZER.serialize(parseViaGdScript(source).ast());
    }

    /// Asserts character-identical serialized ASTs between the two paths.
    protected static void assertParity(String source) {
        assertEquals(treeSitterSexp(source), gdScriptSexp(source));
    }

    /// Parity plus the legal-corpus invariant: neither side may produce ERROR diagnostics.
    /// (Snippets exercising pinned Godot quirks — e.g. the newline-only EOF synthesis — use
    /// plain `assertParity` instead.)
    protected static void assertCleanParity(String source) {
        var reference = parseViaTreeSitter(source);
        var actual = parseViaGdScript(source);
        assertEquals(SERIALIZER.serialize(reference.ast()), SERIALIZER.serialize(actual.ast()));
        for (var result : List.of(reference, actual)) {
            var errors = result.diagnostics().stream()
                    .filter(d -> d.severity() == AstDiagnosticSeverity.ERROR)
                    .toList();
            assertEquals(List.of(), errors, () -> "unexpected ERROR diagnostics for legal corpus: " + errors);
        }
    }

    /// Discovers the shared `.gd` fixtures (same discovery rule as the lowering tests).
    protected static List<Path> fixtureScripts() throws IOException {
        try (var stream = Files.list(FIXTURE_DIR)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".gd"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
        }
    }
}
