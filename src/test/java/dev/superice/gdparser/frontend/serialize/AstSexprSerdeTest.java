package dev.superice.gdparser.frontend.serialize;

import dev.superice.gdparser.frontend.ast.ArrayExpression;
import dev.superice.gdparser.frontend.ast.AssignmentExpression;
import dev.superice.gdparser.frontend.ast.AttributeCallStep;
import dev.superice.gdparser.frontend.ast.AttributeExpression;
import dev.superice.gdparser.frontend.ast.AttributePropertyStep;
import dev.superice.gdparser.frontend.ast.AttributeSubscriptStep;
import dev.superice.gdparser.frontend.ast.AstDiagnosticSeverity;
import dev.superice.gdparser.frontend.ast.BinaryExpression;
import dev.superice.gdparser.frontend.ast.Block;
import dev.superice.gdparser.frontend.ast.CallExpression;
import dev.superice.gdparser.frontend.ast.ClassNameStatement;
import dev.superice.gdparser.frontend.ast.ConditionalExpression;
import dev.superice.gdparser.frontend.ast.DeclarationKind;
import dev.superice.gdparser.frontend.ast.DictEntry;
import dev.superice.gdparser.frontend.ast.DictionaryExpression;
import dev.superice.gdparser.frontend.ast.ErrorExpression;
import dev.superice.gdparser.frontend.ast.ErrorStatement;
import dev.superice.gdparser.frontend.ast.Expression;
import dev.superice.gdparser.frontend.ast.ExpressionStatement;
import dev.superice.gdparser.frontend.ast.FunctionDeclaration;
import dev.superice.gdparser.frontend.ast.IdentifierExpression;
import dev.superice.gdparser.frontend.ast.LambdaExpression;
import dev.superice.gdparser.frontend.ast.LiteralExpression;
import dev.superice.gdparser.frontend.ast.MissingAttributeStep;
import dev.superice.gdparser.frontend.ast.Parameter;
import dev.superice.gdparser.frontend.ast.PassStatement;
import dev.superice.gdparser.frontend.ast.Point;
import dev.superice.gdparser.frontend.ast.Range;
import dev.superice.gdparser.frontend.ast.ReturnStatement;
import dev.superice.gdparser.frontend.ast.SourceFile;
import dev.superice.gdparser.frontend.ast.Statement;
import dev.superice.gdparser.frontend.ast.TypeRef;
import dev.superice.gdparser.frontend.ast.UnaryExpression;
import dev.superice.gdparser.frontend.ast.UnknownAttributeStep;
import dev.superice.gdparser.frontend.ast.UnknownExpression;
import dev.superice.gdparser.frontend.ast.VariableDeclaration;
import dev.superice.gdparser.frontend.cst.CstIssueKind;
import dev.superice.gdparser.frontend.lowering.CstToAstMapper;
import dev.superice.gdparser.infra.treesitter.GdParserFacade;
import org.junit.jupiter.api.BeforeAll;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

@SuppressWarnings("deprecation") // The legacy backend remains a regression reference.
class AstSexprSerdeTest {

    private static final Path FIXTURE_DIR = Path.of("src", "test", "resources", "gdscript");

    private static GdParserFacade parserFacade;
    private static CstToAstMapper mapper;

    private final AstSexprSerializer serializer = new AstSexprSerializer();
    private final AstSexprDeserializer deserializer = new AstSexprDeserializer();

    @BeforeAll
    static void setUp() {
        parserFacade = GdParserFacade.withDefaultLanguage();
        mapper = new CstToAstMapper();
    }

    @Test
    void shouldRoundTripMappedAstAndKeepCanonicalTextStable() {
        var source = """
                class_name Player
                extends Node
                signal damaged(amount)
                
                func _ready(delta: float) -> void:
                    var values = [1, 2, 3]
                    var data = {"x": delta, ..}
                    if delta > 0:
                        print(values[0])
                    else:
                        pass
                    return data["x"]
                
                static func test(a):
                    pass
                """;

        var cstRoot = parserFacade.parseCstRoot(source);
        var mappingResult = mapper.map(source, cstRoot);

        assertFalse(
                mappingResult.diagnostics().stream().anyMatch(diagnostic -> diagnostic.severity() == AstDiagnosticSeverity.ERROR),
                () -> "Unexpected lowering errors: " + mappingResult.diagnostics()
        );

        var ast = mappingResult.ast();
        var text = serializer.serialize(ast);
        IO.println(serializer.serializePretty(ast, AstSexprSerializer.PrettyMode.OMIT_RANGE));
        var restored = deserializer.deserialize(text);

        assertEquals(ast, restored);
        assertEquals(text, serializer.serialize(restored));
        assertTrue(text.contains("(source-file"));
        assertTrue(text.contains("(function-declaration"));
        assertTrue(text.contains("(dictionary-expression"));
    }

    @Test
    void shouldEscapeStringsAndPreserveNullsUnknownsAndNestedVariants() {
        var ast = manualAstFixture();

        var text = serializer.serialize(ast);
        var restored = deserializer.deserialize(text);

        assertEquals(ast, restored);
        assertTrue(text.contains("\\n"));
        assertTrue(text.contains("\\\\"));
        assertTrue(text.contains("\\\""));
        assertTrue(text.contains(" nil"));
    }

    @Test
    void shouldSerializePrettyTextThatDeserializerCanLoad() {
        var ast = manualAstFixture();

        var canonicalText = serializer.serialize(ast);
        var prettyText = serializer.serializePretty(ast);
        var restored = deserializer.deserialize(prettyText);

        assertEquals(ast, restored);
        assertEquals(canonicalText, serializer.serialize(restored));
        assertNotEquals(canonicalText, prettyText);
        assertTrue(prettyText.startsWith("(source-file\n"));
        assertTrue(prettyText.contains("\n  (statements\n"));
        assertTrue(prettyText.contains("\n    (list\n"));
        assertTrue(prettyText.contains("\n      (class-name-statement\n"));
    }

    @Test
    void shouldAllowPrettySerializationWithoutRange() {
        var ast = manualAstFixture();
        var expected = manualAstFixture(span(0, 0), span(0, 0));

        var prettyText = serializer.serializePretty(ast);
        var prettyWithoutRangeText = serializer.serializePretty(ast, AstSexprSerializer.PrettyMode.OMIT_RANGE);
        var restored = deserializer.deserialize(prettyWithoutRangeText);

        assertNotEquals(prettyText, prettyWithoutRangeText);
        assertTrue(prettyWithoutRangeText.startsWith("(source-file\n"));
        assertTrue(prettyWithoutRangeText.contains("\n  (statements\n"));
        assertFalse(prettyWithoutRangeText.contains("(range"));
        assertEquals(expected, restored);
        assertEquals(serializer.serialize(expected), serializer.serialize(restored));
    }

    @Test
    void shouldRoundTripParserProducedMarkersAndDamagedNodes() {
        // Recovery markers produced by the pure Java parser (ErrorExpression MISSING/ERROR and
        // MissingAttributeStep) round-trip like any other node.
        var parser = new dev.superice.gdparser.frontend.gdscript.GdScriptParser();
        var corpus = List.of(
                // MISSING slot markers (initializer, binary right operand, array element).
                "var hp =\nvar x = 1 +\nvar a = [1, , 3]\n",
                // ERROR fragment (consumed-span) plus a damaged binary.
                "var hp = $/!!\nvar r = 1 +\n",
                // Missing-member chain step.
                "var x = self.b.(1)\n",
                // Dictionary-pattern whole-pattern ERROR fallback.
                "func f():\n\tmatch x:\n\t\t{1+}: pass\n"
        );
        for (var source : corpus) {
            var ast = parser.parse(source).ast();
            var restored = deserializer.deserialize(serializer.serialize(ast));
            assertEquals(ast, restored, () -> "round-trip mismatch for: " + source);
            // Sanity: the corpus entry really does carry recovery nodes.
            assertTrue(containsRecoveryNode(ast), () -> "expected recovery nodes in: " + source);
        }
    }

    private static boolean containsRecoveryNode(dev.superice.gdparser.frontend.ast.Node node) {
        if (node instanceof ErrorExpression || node instanceof ErrorStatement
                || node instanceof MissingAttributeStep) {
            return true;
        }
        return node.getChildren().stream().anyMatch(AstSexprSerdeTest::containsRecoveryNode);
    }

    @Test
    void shouldRejectMalformedSexpr() {
        var malformedInputs = List.of(
                "(",
                "(source-file (statements (list))",
                "(unknown-tag (x 1))",
                "(source-file (statements (list)) (range (range (startByte 0) (endByte 0) (startPoint (point (row 0) (column 0))) (endPoint (point (row 0) (column 0))))) extra)"
        );

        for (var input : malformedInputs) {
            assertThrows(IllegalArgumentException.class, () -> deserializer.deserialize(input));
        }
    }

    @Test
    void shouldRejectTypeMismatchAndInvalidListMarker() {
        var valid = serializer.serialize(simpleAstFixture());

        var invalidInt = valid.replace("(startByte 0)", "(startByte \"oops\")");
        var invalidList = valid.replace("(list", "(items");

        assertThrows(IllegalArgumentException.class, () -> deserializer.deserialize(invalidInt));
        assertThrows(IllegalArgumentException.class, () -> deserializer.deserialize(invalidList));
    }

    @TestFactory
    List<DynamicTest> fixtureScriptsShouldRoundTripAstSerialization() throws IOException {
        var scripts = fixtureScripts();
        var tests = new ArrayList<DynamicTest>();
        for (var script : scripts) {
            tests.add(dynamicTest("fixture-ast-serde-roundtrip: " + script.getFileName(), () -> {
                var source = Files.readString(script, StandardCharsets.UTF_8);
                var cstRoot = parserFacade.parseCstRoot(source);
                var mappingResult = mapper.map(source, cstRoot);

                assertFalse(
                        mappingResult.diagnostics().stream()
                                .anyMatch(diagnostic -> diagnostic.severity() == AstDiagnosticSeverity.ERROR),
                        () -> "Unexpected lowering errors in " + script.getFileName() + ": " + mappingResult.diagnostics()
                );

                var ast = mappingResult.ast();
                var text = serializer.serialize(ast);
                var prettyText = serializer.serializePretty(ast);
                var restored = deserializer.deserialize(text);
                var prettyRestored = deserializer.deserialize(prettyText);

                assertFalse(ast.statements().isEmpty(), () -> "AST is empty for fixture: " + script.getFileName());
                assertEquals(ast, restored, () -> "AST mismatch after round-trip for fixture: " + script.getFileName());
                assertEquals(ast, prettyRestored, () -> "Pretty AST mismatch after round-trip for fixture: " + script.getFileName());
                assertEquals(
                        text,
                        serializer.serialize(restored),
                        () -> "Serialized text is not canonical for fixture: " + script.getFileName()
                );
                assertEquals(
                        text,
                        serializer.serialize(prettyRestored),
                        () -> "Pretty serialized text does not deserialize canonically for fixture: " + script.getFileName()
                );
                assertTrue(text.startsWith("(source-file "), () -> "Unexpected serialized root tag for " + script.getFileName());
                assertTrue(prettyText.startsWith("(source-file\n"), () -> "Unexpected pretty serialized root tag for " + script.getFileName());
            }));
        }
        return List.copyOf(tests);
    }

    private static List<Path> fixtureScripts() throws IOException {
        try (var stream = Files.list(FIXTURE_DIR)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".gd"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
        }
    }

    private static SourceFile simpleAstFixture() {
        var span = span(0, 0);
        return new SourceFile(List.of(new PassStatement(span)), span);
    }

    private static SourceFile manualAstFixture() {
        return manualAstFixture(span(0, 10), span(1, 2));
    }

    private static SourceFile manualAstFixture(Range base, Range tiny) {

        var escapedText = "line1\\nline2 \\\"quoted\\\" \\\\ slash";
        var unknownExpr = new UnknownExpression("mystery_expression", escapedText, tiny);

        Expression complexExpression = new ConditionalExpression(
                new BinaryExpression(">", new IdentifierExpression("x", tiny), new LiteralExpression("number", "0", tiny), tiny),
                new AssignmentExpression(
                        "=",
                        new IdentifierExpression("x", tiny),
                        new UnaryExpression("-", new LiteralExpression("number", "1", tiny), tiny),
                        tiny
                ),
                new CallExpression(
                        new IdentifierExpression("fallback", tiny),
                        List.of(
                                new ArrayExpression(List.of(new LiteralExpression("number", "1", tiny)), false, tiny),
                                new DictionaryExpression(
                                        List.of(new DictEntry(new LiteralExpression("string", "k", tiny), new IdentifierExpression("v", tiny), tiny)),
                                        true,
                                        tiny
                                )
                        ),
                        tiny
                ),
                tiny
        );

        var attributeExpr = new AttributeExpression(
                new IdentifierExpression("node", tiny),
                List.of(
                        new AttributePropertyStep("transform", tiny),
                        new AttributeCallStep("translated", List.of(new LiteralExpression("number", "1", tiny)), tiny),
                        new AttributeSubscriptStep("basis", List.of(new LiteralExpression("number", "0", tiny)), tiny),
                        new MissingAttributeStep(tiny),
                        new UnknownAttributeStep("custom_step", "?.", tiny)
                ),
                tiny
        );

        var function = new FunctionDeclaration(
                "run",
                List.of(new Parameter("x", new TypeRef("int", tiny), null, false, tiny)),
                null,
                false,
                new Block(
                        List.of(
                                new VariableDeclaration(
                                        DeclarationKind.VAR,
                                        "value",
                                        null,
                                        complexExpression,
                                        false,
                                        "variable_statement",
                                        tiny
                                ),
                                new ExpressionStatement(attributeExpr, tiny),
                                new ReturnStatement(
                                        new LambdaExpression(
                                                null,
                                                List.of(new Parameter("p", null, unknownExpr, false, tiny)),
                                                null,
                                                new Block(List.of(new ReturnStatement(unknownExpr, tiny)), tiny),
                                                tiny
                                        ),
                                        tiny
                                )
                        ),
                        tiny
                ),
                tiny
        );

        var statements = List.<Statement>of(
                new ClassNameStatement("Demo", null, tiny),
                function,
                new ErrorStatement(CstIssueKind.ERROR, "ERROR", "func _ready(:", tiny),
                new ExpressionStatement(
                        new ErrorExpression(CstIssueKind.MISSING, "identifier", "", tiny),
                        tiny
                )
        );

        return new SourceFile(statements, base);
    }

    private static Range span(int start, int end) {
        return new Range(start, end, new Point(0, start), new Point(0, end));
    }
}
