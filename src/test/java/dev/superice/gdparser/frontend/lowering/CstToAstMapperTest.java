package dev.superice.gdparser.frontend.lowering;

import dev.superice.gdparser.frontend.ast.*;
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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

class CstToAstMapperTest {

    private static final Path FIXTURE_DIR = Path.of("src", "test", "resources", "gdscript");

    private static GdParserFacade parserFacade;
    private static CstToAstMapper mapper;

    @BeforeAll
    static void setUp() {
        parserFacade = GdParserFacade.withDefaultLanguage();
        mapper = new CstToAstMapper();
    }

    @Test
    void topLevelDeclarationsShouldMapToTypedAstStatements() {
        var source = """
                class_name Player
                extends Node
                signal damaged(amount)
                const SPEED = 10
                var hp := 100
                
                func _ready(delta: float) -> void:
                    return
                """;

        var result = map(source);

        assertFalse(hasErrors(result));
        assertEquals(6, result.ast().statements().size());

        var className = assertInstanceOf(ClassNameStatement.class, result.ast().statements().get(0));
        assertEquals("Player", className.name());

        var extendsStatement = assertInstanceOf(ExtendsStatement.class, result.ast().statements().get(1));
        assertEquals("Node", extendsStatement.target());

        var signal = assertInstanceOf(SignalStatement.class, result.ast().statements().get(2));
        assertEquals("damaged", signal.name());
        assertEquals(1, signal.parameters().size());

        var constant = assertInstanceOf(VariableDeclaration.class, result.ast().statements().get(3));
        assertEquals(DeclarationKind.CONST, constant.kind());
        assertEquals("SPEED", constant.name());

        var variable = assertInstanceOf(VariableDeclaration.class, result.ast().statements().get(4));
        assertEquals(DeclarationKind.VAR, variable.kind());
        assertEquals("hp", variable.name());

        var function = assertInstanceOf(FunctionDeclaration.class, result.ast().statements().get(5));
        assertEquals("_ready", function.name());
        assertEquals(1, function.parameters().size());
        assertNotNull(function.returnType());
        assertFalse(function.isStatic());
    }

    @Test
    void controlFlowStatementsShouldMapInsideFunctionBody() {
        var source = """
                func _process(delta):
                    if delta > 0:
                        return
                    elif delta < 0:
                        pass
                    else:
                        pass
                
                    for i in [1, 2, 3]:
                        pass
                
                    while delta > 0:
                        delta -= 1
                
                    match delta:
                        0:
                            return
                        _:
                            pass
                """;

        var result = map(source);
        assertFalse(hasErrors(result));

        var function = assertInstanceOf(FunctionDeclaration.class, result.ast().statements().getFirst());
        var bodyStatements = function.body().statements();

        assertTrue(bodyStatements.stream().anyMatch(IfStatement.class::isInstance));
        assertTrue(bodyStatements.stream().anyMatch(ForStatement.class::isInstance));
        assertTrue(bodyStatements.stream().anyMatch(WhileStatement.class::isInstance));
        assertTrue(bodyStatements.stream().anyMatch(MatchStatement.class::isInstance));

        var ifStatement = bodyStatements.stream()
                .filter(IfStatement.class::isInstance)
                .map(IfStatement.class::cast)
                .findFirst()
                .orElseThrow();
        assertEquals(1, ifStatement.elifClauses().size());
        assertNotNull(ifStatement.elseBody());
    }

    @Test
    void coreExpressionNodesShouldMapWithTypedShapes() {
        var source = """
                func _expr(a, b):
                    var arr = [1, 2, 3]
                    var dict = {"x": a, "y": b, ..}
                    var sum = a + b
                    var neg = -sum
                    var call_result = foo(bar(1), arr[0])
                    var chained = node.transform.origin
                    var lam = func(x): return x * 2
                    return dict["x"]
                """;

        var result = map(source);
        assertFalse(hasErrors(result));

        var function = assertInstanceOf(FunctionDeclaration.class, result.ast().statements().getFirst());
        var declarations = function.body().statements().stream()
                .filter(VariableDeclaration.class::isInstance)
                .map(VariableDeclaration.class::cast)
                .toList();

        assertEquals(7, declarations.size());

        var arrayDecl = declarations.getFirst();
        var arrayExpr = assertInstanceOf(ArrayExpression.class, arrayDecl.value());
        assertEquals(3, arrayExpr.elements().size());

        var dictDecl = declarations.get(1);
        var dictExpr = assertInstanceOf(DictionaryExpression.class, dictDecl.value());
        assertTrue(dictExpr.openEnded());
        assertEquals(2, dictExpr.entries().size());

        var sumDecl = declarations.get(2);
        assertInstanceOf(BinaryExpression.class, sumDecl.value());

        var negDecl = declarations.get(3);
        assertInstanceOf(UnaryExpression.class, negDecl.value());

        var callDecl = declarations.get(4);
        assertInstanceOf(CallExpression.class, callDecl.value());

        var chainedDecl = declarations.get(5);
        assertInstanceOf(AttributeExpression.class, chainedDecl.value());

        var lambdaDecl = declarations.get(6);
        assertInstanceOf(LambdaExpression.class, lambdaDecl.value());

        var returnStatement = assertInstanceOf(
                ReturnStatement.class,
                function.body().statements().getLast()
        );
        assertInstanceOf(SubscriptExpression.class, returnStatement.value());
    }

    @Test
    void luaStyleIdentifierKeyShouldLowerToStringNameLiteral() {
        var result = map("""
                func f():
                    return {x = 1}
                """);
        assertFalse(hasErrors(result));

        var function = assertInstanceOf(FunctionDeclaration.class, result.ast().statements().getFirst());
        var returnStatement = assertInstanceOf(ReturnStatement.class, function.body().statements().getFirst());
        var dictionary = assertInstanceOf(DictionaryExpression.class, returnStatement.value());
        assertEquals(1, dictionary.entries().size());

        var key = assertInstanceOf(LiteralExpression.class, dictionary.entries().getFirst().key());
        assertEquals("string_name", key.kind());
        assertEquals("&\"x\"", key.sourceText());
        assertFalse(dictionary.entries().getFirst().key() instanceof IdentifierExpression);
    }

    @Test
    void luaStyleStringKeyShouldLowerToStringNameLiteral() {
        var result = map("""
                func f():
                    return {"name" = 1}
                """);
        assertFalse(hasErrors(result));

        var function = assertInstanceOf(FunctionDeclaration.class, result.ast().statements().getFirst());
        var returnStatement = assertInstanceOf(ReturnStatement.class, function.body().statements().getFirst());
        var dictionary = assertInstanceOf(DictionaryExpression.class, returnStatement.value());
        assertEquals(1, dictionary.entries().size());

        var key = assertInstanceOf(LiteralExpression.class, dictionary.entries().getFirst().key());
        assertEquals("string_name", key.kind());
        assertEquals("&\"name\"", key.sourceText());
    }

    @Test
    void pythonStyleIdentifierKeyShouldRemainIdentifierExpression() {
        var result = map("""
                func f():
                    return {x: 1}
                """);
        assertFalse(hasErrors(result));

        var function = assertInstanceOf(FunctionDeclaration.class, result.ast().statements().getFirst());
        var returnStatement = assertInstanceOf(ReturnStatement.class, function.body().statements().getFirst());
        var dictionary = assertInstanceOf(DictionaryExpression.class, returnStatement.value());
        var key = assertInstanceOf(IdentifierExpression.class, dictionary.entries().getFirst().key());
        assertEquals("x", key.name());
    }

    @Test
    void mixedDictionaryStylesShouldEmitError() {
        var result = map("""
                func f():
                    return {x: 1, y = 2}
                """);
        assertTrue(hasErrors(result));
        assertTrue(result.diagnostics().stream().anyMatch(diagnostic ->
                diagnostic.severity() == AstDiagnosticSeverity.ERROR
                        && diagnostic.message().contains("Mixing dictionary styles is not allowed.")));
    }

    @Test
    void dedicatedSemanticNodesShouldLowerToSpecificAstRecords() {
        var source = """
                #region Demo
                @tool
                class_name Demo
                extends Node
                
                enum Kind { A, B = 2 }
                
                @export var value: int = 1
                
                @static_unload
                class Inner extends RefCounted:
                    pass
                
                func foo():
                    return 1
                
                func _init():
                    super.foo()
                    var target = $"../Node" as Node
                    var matches = target is Node
                    var misses = target is not Node2D
                    var owner = self
                    var scene = preload("res://scene.tscn")
                    var awaited = await foo()
                    while true:
                        continue
                        break
                    breakpoint
                    match target:
                        var bound when bound is Node:
                            pass
                    assert(target != null, "target required")
                #endregion
                """;

        var result = map(source);

        assertFalse(hasErrors(result), () -> "Unexpected errors: " + result.diagnostics());
        assertTrue(result.diagnostics().isEmpty(), () -> "Unexpected diagnostics: " + result.diagnostics());
        assertTrue(containsAstValue(result.ast(), AnnotationStatement.class));
        assertTrue(containsAstValue(result.ast(), RegionDirectiveStatement.class));
        assertTrue(containsAstValue(result.ast(), EnumDeclaration.class));
        assertTrue(containsAstValue(result.ast(), ClassDeclaration.class));
        assertTrue(containsAstValue(result.ast(), ConstructorDeclaration.class));
        assertTrue(containsAstValue(result.ast(), GetNodeExpression.class));
        assertTrue(containsAstValue(result.ast(), CastExpression.class));
        assertTrue(containsAstValue(result.ast(), TypeTestExpression.class));
        assertTrue(containsAstValue(result.ast(), SelfExpression.class));
        assertTrue(containsAstValue(result.ast(), PreloadExpression.class));
        assertTrue(containsAstValue(result.ast(), AwaitExpression.class));
        assertTrue(containsAstValue(result.ast(), PatternBindingExpression.class));
        assertTrue(containsAstValue(result.ast(), ContinueStatement.class));
        assertTrue(containsAstValue(result.ast(), BreakStatement.class));
        assertTrue(containsAstValue(result.ast(), BreakpointStatement.class));
        assertTrue(containsAstValue(result.ast(), AssertStatement.class));
        assertNoUnknownNodes(result.ast(), "focused snippet");
    }

    @Test
    void staticFunctionShouldMapStaticFlag() {
        var source = """
                static func increment() -> void:
                    pass
                
                func decrement() -> void:
                    pass
                """;

        var result = map(source);
        assertFalse(hasErrors(result), () -> "Unexpected errors: " + result.diagnostics());

        var staticFunction = assertInstanceOf(FunctionDeclaration.class, result.ast().statements().getFirst());
        var instanceFunction = assertInstanceOf(FunctionDeclaration.class, result.ast().statements().getLast());

        assertTrue(staticFunction.isStatic());
        assertFalse(instanceFunction.isStatic());
    }

    @Test
    void invalidSourceShouldEmitErrorDiagnosticsWithSpan() {
        var source = "func _ready(: pass";
        var result = map(source);

        assertTrue(hasErrors(result));
        for (var diagnostic : result.diagnostics()) {
            assertNotNull(diagnostic.nodeType());
            assertTrue(diagnostic.range().endByte() >= diagnostic.range().startByte());
        }
    }

    @Test
    void mapStrictShouldThrowWhenLoweringHasErrors() {
        var source = "func _ready(: pass";
        var root = parserFacade.parseCstRoot(source);

        assertThrows(IllegalStateException.class, () -> mapper.mapStrict(source, root));
    }

    @TestFactory
    List<DynamicTest> fixtureScriptsShouldLowerWithoutDiagnosticsOrUnknownNodes() throws IOException {
        var scripts = fixtureScripts();
        var tests = new ArrayList<DynamicTest>();

        for (var script : scripts) {
            tests.add(dynamicTest("lower-fixture: " + script.getFileName(), () -> {
                var source = Files.readString(script, StandardCharsets.UTF_8);
                var result = map(source);

                assertFalse(result.ast().statements().isEmpty());
                assertTrue(result.diagnostics().isEmpty(), () -> "Unexpected diagnostics in " + script.getFileName() + ": " + result.diagnostics());
                assertNoUnknownNodes(result.ast(), script.getFileName().toString());
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

    private static boolean hasErrors(AstMappingResult result) {
        return result.diagnostics().stream().anyMatch(diagnostic -> diagnostic.severity() == AstDiagnosticSeverity.ERROR);
    }

    private static AstMappingResult map(String source) {
        var root = parserFacade.parseCstRoot(source);
        return mapper.map(source, root);
    }

    private static boolean containsAstValue(SourceFile sourceFile, Class<?> expectedType) {
        var found = new boolean[]{false};
        new ASTWalker(new ASTNodeHandler() {
            @Override
            public FrontendASTTraversalDirective handleNode(Node node) {
                if (expectedType.isInstance(node)) {
                    found[0] = true;
                    return FrontendASTTraversalDirective.SKIP_CHILDREN;
                }
                return FrontendASTTraversalDirective.CONTINUE;
            }
        }).walk(sourceFile);
        return found[0];
    }

    private static void assertNoUnknownNodes(SourceFile sourceFile, String context) {
        var unknownTypes = new ArrayList<String>();
        new ASTWalker(new ASTNodeHandler() {
            @Override
            public FrontendASTTraversalDirective handleNode(Node node) {
                if (node instanceof UnknownStatement || node instanceof UnknownExpression || node instanceof UnknownAttributeStep) {
                    unknownTypes.add(node.getClass().getSimpleName());
                }
                return FrontendASTTraversalDirective.CONTINUE;
            }
        }).walk(sourceFile);
        assertTrue(unknownTypes.isEmpty(), () -> "Unexpected unknown nodes in " + context + ": " + unknownTypes);
    }
}
