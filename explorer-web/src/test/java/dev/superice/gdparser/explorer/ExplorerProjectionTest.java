package dev.superice.gdparser.explorer;

import dev.superice.gdparser.frontend.ast.AstDiagnostic;
import dev.superice.gdparser.frontend.ast.AstDiagnosticSeverity;
import dev.superice.gdparser.frontend.ast.AstMappingResult;
import dev.superice.gdparser.frontend.ast.ExpressionStatement;
import dev.superice.gdparser.frontend.ast.LiteralExpression;
import dev.superice.gdparser.frontend.ast.Point;
import dev.superice.gdparser.frontend.ast.Range;
import dev.superice.gdparser.frontend.ast.SourceFile;
import dev.superice.gdparser.frontend.ast.VariableDeclaration;
import dev.superice.gdparser.frontend.gdscript.GdScriptParserFacade;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExplorerProjectionTest {
    private static final Point ORIGIN = new Point(0, 0);
    private static final Range ZERO = new Range(0, 0, ORIGIN, ORIGIN);
    private static final String EMPTY_TREE = "SourceFile [0,0) (0:0..0:0)";

    @Test
    void projectsEmptySource() {
        assertEquals(new ExplorerProjection.Result(EMPTY_TREE, List.of(), 0, false), ExplorerProjection.parse(""));
    }

    @Test
    void traversesDirectChildrenInOrderThroughNamedAndFallbackNodes() {
        var result = ExplorerProjection.parse("func f(a: int = 1) -> void:\n\tassert(a)\n\tpass\nvar b = 2\n");

        assertEquals(List.of(), result.diagnostics());
        assertFalse(result.treeTruncated());
        assertEquals(List.of(
                "SourceFile",
                "  FunctionDeclaration name=f",
                "    Parameter name=a",
                "      TypeRef raw=int",
                "      LiteralExpression kind=integer raw=1",
                "    TypeRef raw=void",
                "    FunctionBody",
                "      Node",
                "        IdentifierExpression name=a",
                "      PassStatement",
                "  VariableDeclaration name=b",
                "    LiteralExpression kind=integer raw=2"
        ), labels(result));
    }

    @Test
    void preservesDiagnosticUtf8RangeAndDistinctZeroWidthMissingMarker() {
        var result = ExplorerProjection.parse("func f():\r\n\tvar x = \"😀中\" +");

        assertEquals(List.of(new ExplorerProjection.DiagnosticView("ERROR", "parse.missing_expression.binary_right",
                "Expected expression after \"+\" operator.", 30, 31, 1, 19, 1, 20)), result.diagnostics());
        assertEquals(0, result.omittedDiagnostics());
        assertEquals(List.of(
                "          LiteralExpression kind=string raw=\"😀中\" [20,29) (1:9..1:18)",
                "          MISSING kind=expression slot=binary.right [31,31) (1:20..1:20)"
        ), result.treeText().lines().skip(5).toList());
    }

    @Test
    void keepsErrorFragmentsAndMissingAttributeMarkersVisible() {
        var result = ExplorerProjection.parse("func f():\n\treturn =3\n");
        assertEquals("        ERROR kind=expression slot=ERROR [18,20) (1:8..1:10)",
                result.treeText().lines().toList().getLast());

        var attribute = ExplorerProjection.parse("func f():\n\tnode.\n");
        assertEquals("          MISSING kind=attribute slot=attribute.member [16,16) (1:6..1:6)",
                attribute.treeText().lines().toList().getLast());
    }

    @Test
    void escapesMultilineRawLexemeWithoutMovingNextSiblingOrChangingTheAst() {
        var raw = "\"\"\"first\\\\middle\r\n\tfakeChild" + (char) 1 + "中\nlast\"\"\"";
        var parsed = parseRaw("var text = " + raw + "\nvar other = 1\n");
        var result = ExplorerProjection.project(parsed, ExplorerProjection.DEFAULT_LIMITS);

        assertEquals(List.of(), result.diagnostics());
        assertEquals(List.of(
                "SourceFile [0,66) (0:0..4:0)",
                "  VariableDeclaration name=text [0,51) (0:0..2:7)",
                "    LiteralExpression kind=string raw=\"\"\"first\\\\\\\\middle\\r\\n\\tfakeChild\\u0001中\\nlast\"\"\""
                        + " [11,51) (0:11..2:7)",
                "  VariableDeclaration name=other [52,65) (3:0..3:13)",
                "    LiteralExpression kind=integer raw=1 [64,65) (3:12..3:13)"
        ), result.treeText().lines().toList());
        var declaration = assertInstanceOf(VariableDeclaration.class, parsed.ast().statements().getFirst());
        assertEquals(raw, assertInstanceOf(LiteralExpression.class, declaration.value()).sourceText());
    }

    @Test
    void skipsOnlyTheDescendantsBeyondTheDepthLimit() {
        var parsed = parseRaw("func f():\n\tpass\nvar tail = 1\n");
        var exact = ExplorerProjection.project(parsed, limits(3, 20, 1_000));
        var oneLess = ExplorerProjection.project(parsed, limits(2, 20, 1_000));

        assertFalse(exact.treeTruncated());
        assertEquals(List.of("SourceFile", "  FunctionDeclaration name=f", "    FunctionBody", "      PassStatement",
                "  VariableDeclaration name=tail", "    LiteralExpression kind=integer raw=1"), labels(exact));
        assertTrue(oneLess.treeTruncated());
        assertEquals(List.of("SourceFile", "  FunctionDeclaration name=f", "    FunctionBody",
                "  VariableDeclaration name=tail", "    LiteralExpression kind=integer raw=1"), labels(oneLess));
        assertFalse(ExplorerProjection.project(parseRaw(""), limits(0, 1, 1_000)).treeTruncated());
    }

    @Test
    void enforcesTheNodeLimitOnlyWhenMoreNodesRemain() {
        var parsed = parseRaw("var a = 1\nvar b = 2\n");
        var exact = ExplorerProjection.project(parsed, limits(20, 5, 1_000));
        var oneLess = ExplorerProjection.project(parsed, limits(20, 4, 1_000));
        var none = ExplorerProjection.project(parsed, limits(20, 0, 1_000));

        assertFalse(exact.treeTruncated());
        assertEquals(List.of("SourceFile", "  VariableDeclaration name=a", "    LiteralExpression kind=integer raw=1",
                "  VariableDeclaration name=b", "    LiteralExpression kind=integer raw=2"), labels(exact));
        assertTrue(oneLess.treeTruncated());
        assertEquals(labels(exact).subList(0, 4), labels(oneLess));
        assertEquals("", none.treeText());
        assertTrue(none.treeTruncated());
    }

    @Test
    void chargesEscapedTreeLengthAndKeepsWholeLinesAtTheBoundary() {
        var raw = "\\\t" + (char) 1 + (char) 0x2028 + (char) 0x2029 + (char) 0xd800;
        var literal = new LiteralExpression("string", raw, ZERO);
        var parsed = new AstMappingResult(new SourceFile(List.of(new ExpressionStatement(literal, ZERO)), ZERO),
                List.of());
        var priorLines = EMPTY_TREE + "\n  ExpressionStatement [0,0) (0:0..0:0)";
        var literalPrefix = priorLines + "\n    LiteralExpression kind=string raw=";
        var throughEscapedValue = literalPrefix + "\\\\\\t\\u0001\\u2028\\u2029\\ud800";
        var wholeTree = throughEscapedValue + " [0,0) (0:0..0:0)";

        var exact = ExplorerProjection.project(parsed, limits(20, 20, wholeTree.length()));
        assertEquals(wholeTree, exact.treeText());
        assertFalse(exact.treeTruncated());

        var oneLess = ExplorerProjection.project(parsed, limits(20, 20, wholeTree.length() - 1));
        assertEquals(priorLines, oneLess.treeText());
        assertTrue(oneLess.treeTruncated());

        var withinEscape = throughEscapedValue.length() - 1;
        assertTrue(literalPrefix.length() + raw.length() <= withinEscape);
        var partialEscape = ExplorerProjection.project(parsed, limits(20, 20, withinEscape));
        assertEquals(priorLines, partialEscape.treeText());
        assertTrue(partialEscape.treeTruncated());
        assertTrue(ExplorerProjection.project(parsed, limits(20, 20, 0)).treeText().isEmpty());
    }

    @Test
    void retainsTheCompleteDiagnosticPrefixWhenTheCountLimitIsReached() {
        var parsed = parseRaw("var x = [1,\n\u0001func after(): pass\n");
        var exact = ExplorerProjection.project(parsed, new ExplorerProjection.Limits(20, 20, 1_000, 2, 2_000));
        var oneLess = ExplorerProjection.project(parsed, new ExplorerProjection.Limits(20, 20, 1_000, 1, 2_000));
        var none = ExplorerProjection.project(parsed, new ExplorerProjection.Limits(20, 20, 1_000, 0, 2_000));

        assertEquals(List.of("parse.lexical.error", "parse.missing_closer.array"),
                exact.diagnostics().stream().map(ExplorerProjection.DiagnosticView::code).toList());
        assertEquals(List.of(12, 11), exact.diagnostics().stream().map(ExplorerProjection.DiagnosticView::startByte).toList());
        assertEquals(0, exact.omittedDiagnostics());
        assertEquals(exact.diagnostics().subList(0, 1), oneLess.diagnostics());
        assertEquals(1, oneLess.omittedDiagnostics());
        assertEquals(List.of(), none.diagnostics());
        assertEquals(2, none.omittedDiagnostics());
        assertEquals(exact.treeText(), oneLess.treeText());
        assertEquals(2, parsed.diagnostics().size());
    }

    @Test
    void sharesTextBudgetWithoutChangingRawDiagnosticValuesOrSkippingALongEntry() {
        var raw = "A\r\n\t" + (char) 1 + "😀中";
        var range = new Range(6, 6, new Point(1, 3), new Point(1, 3));
        var diagnostics = List.of(
                new AstDiagnostic(AstDiagnosticSeverity.WARNING, raw, "warning", range),
                new AstDiagnostic(AstDiagnosticSeverity.ERROR, "a longer message", "error", ZERO, "code", null),
                new AstDiagnostic(AstDiagnosticSeverity.ERROR, "", "error", ZERO));
        var parsed = new AstMappingResult(new SourceFile(List.of(), ZERO), diagnostics);
        var first = new ExplorerProjection.DiagnosticView("WARNING", null, raw, 6, 6, 1, 3, 1, 3);
        var second = new ExplorerProjection.DiagnosticView("ERROR", "code", "a longer message", 0, 0, 0, 0, 0, 0);
        var twoFit = EMPTY_TREE.length() + "WARNING".length() + raw.length() + "ERRORcodea longer message".length();
        var exact = ExplorerProjection.project(parsed, new ExplorerProjection.Limits(20, 20, EMPTY_TREE.length(), 20,
                twoFit));
        var oneLess = ExplorerProjection.project(parsed, new ExplorerProjection.Limits(20, 20, EMPTY_TREE.length(), 20,
                twoFit - 1));

        assertEquals(EMPTY_TREE, exact.treeText());
        assertFalse(exact.treeTruncated());
        assertEquals(List.of(first, second), exact.diagnostics());
        assertEquals(1, exact.omittedDiagnostics());
        assertEquals(List.of(first), oneLess.diagnostics());
        assertEquals(2, oneLess.omittedDiagnostics());
        assertEquals(diagnostics, parsed.diagnostics());
    }

    private static AstMappingResult parseRaw(String source) {
        return new GdScriptParserFacade().parse(source);
    }

    private static ExplorerProjection.Limits limits(int depth, int nodes, int treeText) {
        return new ExplorerProjection.Limits(depth, nodes, treeText, 20, 2_000);
    }

    private static List<String> labels(ExplorerProjection.Result result) {
        return result.treeText().lines().map(line -> line.substring(0, line.lastIndexOf(" ["))).toList();
    }
}
