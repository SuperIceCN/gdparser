package dev.superice.gdparser.frontend.lowering;

import dev.superice.gdparser.frontend.ast.AstDiagnosticSeverity;
import dev.superice.gdparser.frontend.ast.AstMappingResult;
import dev.superice.gdparser.frontend.ast.ASTNodeHandler;
import dev.superice.gdparser.frontend.ast.ASTWalker;
import dev.superice.gdparser.frontend.ast.ConstructorDeclaration;
import dev.superice.gdparser.frontend.ast.FrontendASTTraversalDirective;
import dev.superice.gdparser.frontend.ast.Node;
import dev.superice.gdparser.frontend.ast.SourceFile;
import dev.superice.gdparser.frontend.ast.UnknownExpression;
import dev.superice.gdparser.frontend.ast.UnknownStatement;
import dev.superice.gdparser.infra.treesitter.GdParserFacade;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SuppressWarnings("deprecation") // The legacy backend remains a regression reference.
class CstToAstMapperLegacySyntaxTest {

    private static GdParserFacade parserFacade;
    private static CstToAstMapper mapper;

    @BeforeAll
    static void setUp() {
        parserFacade = GdParserFacade.withDefaultLanguage();
        mapper = new CstToAstMapper();
    }

    @Test
    void gdscriptThreeToolKeywordShouldBeRejected() {
        assertLegacySyntaxRejected(
                "tool",
                "Use `@tool` instead.",
                UnknownStatement.class
        );
    }

    @Test
    void gdscriptThreeYieldShouldBeRejected() {
        assertLegacySyntaxRejected(
                """
                        func demo():
                            yield(timer, "timeout")
                        """,
                "Use `await` instead.",
                UnknownExpression.class
        );
    }

    @Test
    void gdscriptThreeExportKeywordShouldBeRejected() {
        assertLegacySyntaxRejected(
                "export var health := 100",
                "Use `@export` and related annotations instead.",
                UnknownStatement.class
        );
    }

    @Test
    void gdscriptThreeOnreadyKeywordShouldBeRejected() {
        assertLegacySyntaxRejected(
                "onready var player = $Player",
                "Use `@onready` instead.",
                UnknownStatement.class
        );
    }

    @Test
    void gdscriptThreeRemoteKeywordsShouldBeRejected() {
        assertLegacySyntaxRejected(
                """
                        remote func sync_health():
                            pass
                        """,
                "Use `@rpc` instead.",
                UnknownStatement.class
        );
    }

    @Test
    void gdscriptThreeRemoteVariableKeywordsShouldBeRejected() {
        assertLegacySyntaxRejected(
                "master var health := 100",
                "Use `@rpc` instead.",
                UnknownStatement.class
        );
    }

    @Test
    void gdscriptThreeSetgetKeywordShouldBeRejected() {
        assertLegacySyntaxRejected(
                """
                        var health := 100 setget set_health, get_health
                        """,
                "The `setget` keyword was removed in GDScript 4.x.",
                UnknownStatement.class
        );
    }

    @Test
    void gdscriptThreeImplicitBaseCallShouldBeRejected() {
        assertLegacySyntaxRejected(
                """
                        func _ready():
                            .tick()
                        """,
                "Use `super.foo()` instead.",
                UnknownExpression.class
        );
    }

    @Test
    void gdscriptThreeInlineClassNameIconShouldBeRejected() {
        assertLegacySyntaxRejected(
                "class_name Demo, \"res://icons/demo.svg\"",
                "Use `@icon(\"...\")` together with `class_name` instead.",
                UnknownStatement.class
        );
    }

    @Test
    void gdscriptThreeConstructorBaseArgumentsShouldBeRejected() {
        var source = """
                func _init(value).(value):
                    pass
                """;

        var result = map(source);

        assertTrue(hasErrors(result));
        assertTrue(hasErrorMessage(result, "Call `super(...)` inside the constructor body instead."));
        assertFalse(containsAstValue(result.ast(), ConstructorDeclaration.class));
        assertTrue(containsAstValue(result.ast(), UnknownStatement.class));
        assertThrows(IllegalStateException.class, () -> mapStrict(source));
    }

    @Test
    void gdscriptFourPropertyBlocksAndSuperCallsShouldStillLower() {
        var source = """
                @tool
                @icon("res://icons/derived.svg")
                class_name DerivedNode
                
                class Base:
                    func _init(value):
                        pass
                
                    func greet():
                        pass
                
                class Derived extends Base:
                    @export var health: int = 100:
                        get:
                            return health
                        set(value):
                            health = value
                
                    @onready var child = $Child
                
                    func _init(value):
                        super(value)
                        super.greet()
                """;

        var result = map(source);

        assertFalse(hasErrors(result), () -> "Unexpected errors: " + result.diagnostics());
        assertFalse(containsAstValue(result.ast(), UnknownStatement.class));
        assertFalse(containsAstValue(result.ast(), UnknownExpression.class));
        assertFalse(hasErrorMessage(result, "GDScript 4.x"));
    }

    private static void assertLegacySyntaxRejected(String source, String expectedMessageFragment, Class<?> expectedUnknownType) {
        var result = map(source);

        assertTrue(hasErrors(result), () -> "Expected errors for source:\n" + source);
        assertTrue(hasErrorMessage(result, expectedMessageFragment), () -> "Missing error containing: " + expectedMessageFragment);
        assertTrue(containsAstValue(result.ast(), expectedUnknownType), () -> "Expected rejected syntax to lower to " + expectedUnknownType.getSimpleName());
        assertThrows(IllegalStateException.class, () -> mapStrict(source));
    }

    private static boolean hasErrors(AstMappingResult result) {
        return result.diagnostics().stream().anyMatch(diagnostic -> diagnostic.severity() == AstDiagnosticSeverity.ERROR);
    }

    private static boolean hasErrorMessage(AstMappingResult result, String expectedMessageFragment) {
        return result.diagnostics().stream()
                .filter(diagnostic -> diagnostic.severity() == AstDiagnosticSeverity.ERROR)
                .anyMatch(diagnostic -> diagnostic.message().contains(expectedMessageFragment));
    }

    private static AstMappingResult map(String source) {
        return mapper.map(source, parserFacade.parseCstRoot(source));
    }

    private static SourceFile mapStrict(String source) {
        return mapper.mapStrict(source, parserFacade.parseCstRoot(source));
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
}
