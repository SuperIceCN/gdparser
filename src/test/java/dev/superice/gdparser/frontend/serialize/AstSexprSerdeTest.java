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
import dev.superice.gdparser.frontend.ast.Expression;
import dev.superice.gdparser.frontend.ast.ExpressionStatement;
import dev.superice.gdparser.frontend.ast.FunctionDeclaration;
import dev.superice.gdparser.frontend.ast.IdentifierExpression;
import dev.superice.gdparser.frontend.ast.LambdaExpression;
import dev.superice.gdparser.frontend.ast.LiteralExpression;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

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
                """;

        var cstRoot = parserFacade.parseCstRoot(source);
        var mappingResult = mapper.map(source, cstRoot);

        assertFalse(
                mappingResult.diagnostics().stream().anyMatch(diagnostic -> diagnostic.severity() == AstDiagnosticSeverity.ERROR),
                () -> "Unexpected lowering errors: " + mappingResult.diagnostics()
        );

        var ast = mappingResult.ast();
        var text = serializer.serialize(ast);
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
                var restored = deserializer.deserialize(text);

                assertFalse(ast.statements().isEmpty(), () -> "AST is empty for fixture: " + script.getFileName());
                assertEquals(ast, restored, () -> "AST mismatch after round-trip for fixture: " + script.getFileName());
                assertEquals(
                        text,
                        serializer.serialize(restored),
                        () -> "Serialized text is not canonical for fixture: " + script.getFileName()
                );
                assertTrue(text.startsWith("(source-file "), () -> "Unexpected serialized root tag for " + script.getFileName());
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
        var base = span(0, 10);
        var tiny = span(1, 2);

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
                        new UnknownAttributeStep("custom_step", "?.", tiny)
                ),
                tiny
        );

        var function = new FunctionDeclaration(
                "run",
                List.of(new Parameter("x", new TypeRef("int", tiny), null, false, tiny)),
                null,
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
                new ClassNameStatement("Demo", null, "res://icons/\"demo\"\\n.png", tiny),
                function
        );

        return new SourceFile(statements, base);
    }

    private static Range span(int start, int end) {
        return new Range(start, end, new Point(0, start), new Point(0, end));
    }
}
