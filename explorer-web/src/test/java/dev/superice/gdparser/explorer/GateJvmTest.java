package dev.superice.gdparser.explorer;

import dev.superice.gdparser.frontend.ast.AstDiagnostic;
import dev.superice.gdparser.frontend.ast.AstDiagnosticSeverity;
import dev.superice.gdparser.frontend.ast.AstMappingResult;
import dev.superice.gdparser.frontend.ast.BinaryExpression;
import dev.superice.gdparser.frontend.ast.ErrorExpression;
import dev.superice.gdparser.frontend.ast.FunctionDeclaration;
import dev.superice.gdparser.frontend.ast.LiteralExpression;
import dev.superice.gdparser.frontend.ast.PassStatement;
import dev.superice.gdparser.frontend.ast.Point;
import dev.superice.gdparser.frontend.ast.Range;
import dev.superice.gdparser.frontend.ast.VariableDeclaration;
import dev.superice.gdparser.frontend.cst.CstIssueKind;
import dev.superice.gdparser.frontend.gdscript.DiagnosticCodes;
import dev.superice.gdparser.frontend.gdscript.GdScriptParserFacade;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GateJvmTest {
    private final GdScriptParserFacade parser = new GdScriptParserFacade();

    @Test
    void keepsTheValidFunctionAndTheTwoSiblingsOfAnIncompleteDeclaration() {
        var valid = parser.parse(GateCases.VALID);
        assertEquals(List.of(), valid.diagnostics());
        assertEquals(1, valid.ast().statements().size());
        var function = assertInstanceOf(FunctionDeclaration.class, valid.ast().statements().getFirst());
        assertEquals("f", function.name());
        assertInstanceOf(PassStatement.class, function.body().statements().getFirst());
        var labels = ExplorerProjection.parse(GateCases.VALID).treeText().lines()
                .map(line -> line.substring(0, line.lastIndexOf(" [")))
                .toList();
        assertEquals(List.of("SourceFile", "  FunctionDeclaration name=f", "    FunctionBody",
                "      PassStatement"), labels);

        var incomplete = parser.parse(GateCases.INCOMPLETE);
        assertEquals(List.of("a", "b"), variableNames(incomplete));
        var first = assertInstanceOf(VariableDeclaration.class, incomplete.ast().statements().getFirst());
        assertInstanceOf(ErrorExpression.class, first.value());
        assertTrue(incomplete.diagnostics().stream().anyMatch(diagnostic ->
                diagnostic.severity() == AstDiagnosticSeverity.ERROR && diagnostic.recovery() != null
                        && diagnostic.recovery().owner() == first));
    }

    @Test
    void preservesPhysicalByteRangesAndDistinguishesTheDiagnosticFromTheMissingMarker() {
        var crlf = parser.parse(GateCases.CRLF_EMOJI);
        var function = assertInstanceOf(FunctionDeclaration.class, crlf.ast().statements().getFirst());
        var variable = assertInstanceOf(VariableDeclaration.class, function.body().statements().getFirst());
        assertMissingBinaryRight(crlf, variable, 30, 31, 1, 19, 20);
        var crlfTree = ExplorerProjection.parse(GateCases.CRLF_EMOJI).treeText();
        assertTrue(crlfTree.lines().anyMatch(line -> line.stripLeading().startsWith("MISSING ")
                && line.endsWith("[31,31) (1:20..1:20)")), crlfTree);

        var surrogate = parser.parse(GateCases.LONE_SURROGATE);
        var topLevel = assertInstanceOf(VariableDeclaration.class, surrogate.ast().statements().getFirst());
        assertMissingBinaryRight(surrogate, topLevel, 12, 13, 0, 12, 13);
        var surrogateTree = ExplorerProjection.parse(GateCases.LONE_SURROGATE).treeText();
        assertTrue(surrogateTree.lines().anyMatch(line -> line.stripLeading().startsWith("MISSING ")
                && line.endsWith("[13,13) (0:13..0:13)")), surrogateTree);
    }

    @Test
    void followsJdkIdentifierRulesInBothPositionsAndKeepsTheBracketRecoverySibling() {
        var controlStart = parser.parse(GateCases.CONTROL_START);
        assertEquals(List.of("x"), variableNames(controlStart));
        assertEquals(List.of(DiagnosticCodes.LEXICAL_ERROR), codes(controlStart));
        assertIdentifier(GateCases.CONTROL_PART, "x\u0001y");
        assertIdentifier(GateCases.SCRIPT_P_START, "℘x");
        assertIdentifier(GateCases.SCRIPT_P_PART, "x℘y");

        var recovery = parser.parse(GateCases.BRACKET_RECOVERY);
        assertEquals(2, recovery.ast().statements().size());
        assertEquals(List.of("x"), variableNames(recovery));
        var following = assertInstanceOf(FunctionDeclaration.class, recovery.ast().statements().getLast());
        assertEquals("after", following.name());
        assertEquals(List.of(DiagnosticCodes.LEXICAL_ERROR, DiagnosticCodes.MISSING_CLOSER_ARRAY), codes(recovery));
        assertEquals(new Range(12, 13, new Point(1, 0), new Point(1, 1)),
                recovery.diagnostics().getFirst().range());
        assertEquals(new Range(11, 11, new Point(0, 11), new Point(0, 11)),
                recovery.diagnostics().getLast().range());
    }

    @Test
    void acceptsCommonAsciiAndMixedCjkIdentifiers() {
        assertIdentifier(GateCases.ASCII_IDENTIFIER, "_sample_total2");
        assertIdentifier(GateCases.CJK_START, "中x");
        var mixed = parser.parse(GateCases.CJK_MIXED);
        assertEquals(List.of("cost中文_2", "变量_total2"), variableNames(mixed));
        assertEquals(List.of(), mixed.diagnostics());
    }

    @Test
    void rendersATripleQuotedLiteralOnOnePhysicalLineWithoutChangingTheOriginalAst() {
        var result = parser.parse(GateCases.TRIPLE_QUOTED);
        assertEquals(List.of(), result.diagnostics());
        assertEquals(List.of("text", "other"), variableNames(result));
        var first = assertInstanceOf(VariableDeclaration.class, result.ast().statements().getFirst());
        var literal = assertInstanceOf(LiteralExpression.class, first.value());
        assertEquals("\"\"\"first\n\tfakeChild\nlast\"\"\"", literal.sourceText());
        assertEquals(new Range(11, 38, new Point(0, 11), new Point(2, 7)), literal.range());

        var lines = ExplorerProjection.parse(GateCases.TRIPLE_QUOTED).treeText().lines().toList();
        var literalLine = lines.stream().filter(line -> line.contains("fakeChild")).findFirst().orElseThrow();
        assertTrue(literalLine.startsWith("    LiteralExpression "), literalLine);
        assertTrue(literalLine.contains("first\\n\\tfakeChild\\nlast"), literalLine);
        var declarations = lines.stream().filter(line -> line.stripLeading().startsWith("VariableDeclaration "))
                .toList();
        assertEquals(2, declarations.size());
        for (var declaration : declarations) {
            assertTrue(declaration.startsWith("  VariableDeclaration "), declaration);
        }
        assertTrue(lines.indexOf(declarations.getFirst()) < lines.indexOf(literalLine));
        assertTrue(lines.indexOf(literalLine) < lines.indexOf(declarations.getLast()));
    }

    @Test
    void acceptsEmptyInputAndDiagnosesTheRealParserDepthBoundaryAndHugeInputs() {
        var empty = parser.parse("");
        assertTrue(empty.ast().statements().isEmpty());
        assertTrue(empty.diagnostics().isEmpty());

        for (var depth : List.of(255, 256, 257)) {
            var result = parser.parse(GateCases.lambdaDepth(depth));
            assertEquals(List.of("x", "after"), variableNames(result));
            assertEquals(depth >= 256, codes(result).contains(DiagnosticCodes.NESTING_DEPTH_EXCEEDED),
                    () -> "Depth " + depth + ": " + result.diagnostics());
            if (depth < 256) {
                assertEquals(List.of(), result.diagnostics());
            }
        }
        for (var source : List.of(GateCases.HUGE_GROUPING, GateCases.HUGE_SUITE)) {
            assertTrue(codes(parser.parse(source)).contains(DiagnosticCodes.NESTING_DEPTH_EXCEEDED));
        }
    }

    @Test
    void validatesLongSourcePathsAndTheFullDiagnosticStressReference() {
        for (var length : List.of(32 * 1024, 64 * 1024)) {
            var valid = GateCases.longScript(length, false);
            var incomplete = GateCases.longScript(length, true);
            assertEquals(length, valid.length());
            assertEquals(length, incomplete.length());
            var parsedValid = parser.parse(valid);
            var parsedIncomplete = parser.parse(incomplete);
            assertTrue(parsedValid.ast().statements().size() > 80);
            assertEquals(List.of(), parsedValid.diagnostics());
            assertEquals(List.of("complete"), variableNames(parsedValid));
            assertEquals(List.of("editing"), variableNames(parsedIncomplete));
            assertTrue(codes(parsedIncomplete).contains(DiagnosticCodes.MISSING_CLOSER_ARRAY));
        }

        var stress = parser.parse(GateCases.DIAGNOSTIC_STRESS);
        assertEquals(2, stress.ast().statements().size());
        var function = assertInstanceOf(FunctionDeclaration.class, stress.ast().statements().getLast());
        assertEquals("after", function.name());
        assertEquals(2002, stress.diagnostics().size());
        assertEquals(2001, codes(stress).stream().filter(DiagnosticCodes.LEXICAL_ERROR::equals).count());
        var projected = ExplorerProjection.parse(GateCases.DIAGNOSTIC_STRESS);
        assertFalse(projected.diagnostics().isEmpty());
        assertTrue(projected.omittedDiagnostics() > 0);
        assertEquals(ExplorerProjection.DEFAULT_LIMITS.maxDiagnostics(), projected.diagnostics().size());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("namedSamples")
    void projectedDiagnosticsAreAnExactPrefixOfTheUncappedFacadeResult(GateCases.Sample sample) {
        var expected = parser.parse(sample.source()).diagnostics();
        var projected = ExplorerProjection.parse(sample.source());
        var prefix = projected.diagnostics();
        var limits = ExplorerProjection.DEFAULT_LIMITS;
        assertTrue(prefix.size() <= limits.maxDiagnostics());
        assertTrue(projected.treeText().length() <= limits.maxTreeText());
        var textUnits = projected.treeText().length() + prefix.stream().mapToInt(diagnostic ->
                diagnostic.severity().length() + (diagnostic.code() == null ? 0 : diagnostic.code().length())
                        + diagnostic.message().length()).sum();
        assertTrue(textUnits <= limits.maxTotalText());
        if (!sample.source().equals(GateCases.DIAGNOSTIC_STRESS)) {
            assertEquals(0, projected.omittedDiagnostics());
        }
        assertEquals(expected.size(), prefix.size() + projected.omittedDiagnostics());
        for (var index = 0; index < prefix.size(); index++) {
            var fromParser = expected.get(index);
            var fromProjection = prefix.get(index);
            var range = fromParser.range();
            assertEquals(fromParser.severity().name(), fromProjection.severity());
            assertEquals(fromParser.code(), fromProjection.code());
            assertEquals(fromParser.message(), fromProjection.message());
            assertEquals(range.startByte(), fromProjection.startByte());
            assertEquals(range.endByte(), fromProjection.endByte());
            assertEquals(range.startPoint().row(), fromProjection.startRow());
            assertEquals(range.startPoint().column(), fromProjection.startColumn());
            assertEquals(range.endPoint().row(), fromProjection.endRow());
            assertEquals(range.endPoint().column(), fromProjection.endColumn());
        }
    }

    private static Stream<Named<GateCases.Sample>> namedSamples() {
        return GateCases.samples().stream().map(sample -> Named.of(sample.name(), sample));
    }

    private void assertIdentifier(String source, String identifier) {
        var result = parser.parse(source);
        assertEquals(List.of(identifier), variableNames(result));
        assertEquals(List.of(), result.diagnostics());
    }

    private static void assertMissingBinaryRight(
            AstMappingResult result, VariableDeclaration variable, int start, int end, int row,
            int startColumn, int endColumn
    ) {
        var binary = assertInstanceOf(BinaryExpression.class, variable.value());
        var missing = assertInstanceOf(ErrorExpression.class, binary.right());
        assertEquals(CstIssueKind.MISSING, missing.kind());
        assertEquals("binary.right", missing.nodeType());
        var endPoint = new Point(row, endColumn);
        assertEquals(new Range(end, end, endPoint, endPoint), missing.range());

        assertEquals(List.of(DiagnosticCodes.SLOT_BINARY_RIGHT), codes(result));
        var diagnostic = result.diagnostics().getFirst();
        assertEquals(AstDiagnosticSeverity.ERROR, diagnostic.severity());
        assertEquals(new Range(start, end, new Point(row, startColumn), endPoint), diagnostic.range());
    }

    private static List<String> variableNames(AstMappingResult result) {
        return result.ast().statements().stream()
                .filter(VariableDeclaration.class::isInstance)
                .map(VariableDeclaration.class::cast)
                .map(VariableDeclaration::name)
                .toList();
    }

    private static List<String> codes(AstMappingResult result) {
        return result.diagnostics().stream().map(AstDiagnostic::code).toList();
    }
}
