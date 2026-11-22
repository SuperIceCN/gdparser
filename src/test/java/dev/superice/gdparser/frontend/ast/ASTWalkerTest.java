package dev.superice.gdparser.frontend.ast;

import dev.superice.gdparser.frontend.cst.CstIssueKind;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Verifies traversal order, dispatch, pruning, and defensive checks for ASTWalker.
class ASTWalkerTest {

    private static final Range RANGE = new Range(0, 1, new Point(0, 0), new Point(0, 1));

    @Test
    void walkShouldVisitNodesInPreOrderAndUseTypeSpecificHandlers() {
        var visited = new ArrayList<String>();
        var walker = new ASTWalker(new ASTNodeHandler() {
            @Override
            public FrontendASTTraversalDirective handleNode(Node node) {
                visited.add("generic:" + node.getClass().getSimpleName());
                return FrontendASTTraversalDirective.CONTINUE;
            }

            @Override
            public FrontendASTTraversalDirective handleSourceFile(SourceFile node) {
                visited.add("specific:SourceFile");
                return FrontendASTTraversalDirective.CONTINUE;
            }

            @Override
            public FrontendASTTraversalDirective handleFunctionDeclaration(FunctionDeclaration node) {
                visited.add("specific:FunctionDeclaration");
                return FrontendASTTraversalDirective.CONTINUE;
            }

            @Override
            public FrontendASTTraversalDirective handleCallExpression(CallExpression node) {
                visited.add("specific:CallExpression");
                return FrontendASTTraversalDirective.CONTINUE;
            }
        });

        walker.walk(sampleTree());

        assertEquals(List.of(
                "specific:SourceFile",
                "specific:FunctionDeclaration",
                "generic:Parameter",
                "generic:TypeRef",
                "generic:TypeRef",
                "generic:Block",
                "generic:VariableDeclaration",
                "specific:CallExpression",
                "generic:IdentifierExpression",
                "generic:LiteralExpression",
                "generic:ReturnStatement",
                "generic:IdentifierExpression",
                "generic:PassStatement"
        ), visited);
    }

    @Test
    void walkShouldSkipChildrenOfTheCurrentNodeOnly() {
        var visited = new ArrayList<String>();
        var walker = new ASTWalker(new ASTNodeHandler() {
            @Override
            public FrontendASTTraversalDirective handleNode(Node node) {
                visited.add(node.getClass().getSimpleName());
                return FrontendASTTraversalDirective.CONTINUE;
            }

            @Override
            public FrontendASTTraversalDirective handleFunctionDeclaration(FunctionDeclaration node) {
                visited.add(node.getClass().getSimpleName());
                return FrontendASTTraversalDirective.SKIP_CHILDREN;
            }
        });

        walker.walk(sampleTree());

        assertEquals(List.of("SourceFile", "FunctionDeclaration", "PassStatement"), visited);
    }

    @Test
    void walkShouldRejectNullRootsAndNullDirectives() {
        var walker = new ASTWalker(new ASTNodeHandler() {
            @Override
            public FrontendASTTraversalDirective handlePassStatement(PassStatement node) {
                return null;
            }
        });

        assertThrows(NullPointerException.class, () -> new ASTWalker(new ASTNodeHandler() {
        }).walk(null));
        assertThrows(NullPointerException.class, () -> walker.walk(new PassStatement(RANGE)));
    }

    @Test
    void walkParsedDictionaryPatternsAndLiteralsVisitsOnlyExistingValues() {
        var root = DictionaryPatternFixture.parse();
        var patterns = DictionaryPatternFixture.patternEntries(root);
        var literals = DictionaryPatternFixture.literalEntries(root);
        DictionaryPatternFixture.assertKeys(patterns);
        DictionaryPatternFixture.assertKeys(literals);

        var visited = new ArrayList<Node>();
        new ASTWalker(new ASTNodeHandler() {
            @Override
            public FrontendASTTraversalDirective handleNode(Node node) {
                visited.add(node);
                return FrontendASTTraversalDirective.CONTINUE;
            }
        }).walk(root);

        var entries = new ArrayList<>(patterns);
        entries.addAll(literals);
        assertEquals(entries, visited.stream().filter(DictEntry.class::isInstance).toList());
        for (var i = 0; i < entries.size(); i++) {
            var entry = entries.get(i);
            var index = visited.indexOf(entry);
            var subtree = new ArrayList<Node>();
            subtree.add(entry);
            subtree.add(entry.key());
            if (entry.key() instanceof BinaryExpression sum) {
                subtree.add(sum.left());
                subtree.add(sum.right());
            }
            if (i < patterns.size()) {
                assertNull(entry.value());
            } else {
                assertNotNull(entry.value());
                subtree.add(entry.value());
            }
            for (var j = 0; j < subtree.size(); j++) {
                assertSame(subtree.get(j), visited.get(index + j));
            }
            if (i + 1 < entries.size() && i + 1 != patterns.size()) {
                assertSame(entries.get(i + 1), visited.get(index + subtree.size()));
            }
        }
    }

    @Test
    void walkShouldVisitNullableDictEntryAndRecoveredInfixExpression() {
        var keyOnlyEntry = new DictEntry(new LiteralExpression("string", "\"name\"", RANGE), null, RANGE);
        var dictVisited = new ArrayList<String>();
        new ASTWalker(new ASTNodeHandler() {
            @Override
            public FrontendASTTraversalDirective handleNode(Node node) {
                dictVisited.add(node.getClass().getSimpleName());
                return FrontendASTTraversalDirective.CONTINUE;
            }
        }).walk(keyOnlyEntry);
        assertEquals(List.of("DictEntry", "LiteralExpression"), dictVisited);

        var recovered = new RecoveredInfixExpression(
                new IdentifierExpression("x", RANGE),
                "is",
                new ErrorExpression(CstIssueKind.MISSING, "type_test.type", "", RANGE),
                RANGE
        );

        // Fallback to handleNode when handleRecoveredInfixExpression is not overridden
        var fallbackVisited = new ArrayList<String>();
        new ASTWalker(new ASTNodeHandler() {
            @Override
            public FrontendASTTraversalDirective handleNode(Node node) {
                fallbackVisited.add("fallback:" + node.getClass().getSimpleName());
                return FrontendASTTraversalDirective.CONTINUE;
            }
        }).walk(recovered);
        assertEquals(
                List.of("fallback:RecoveredInfixExpression", "fallback:IdentifierExpression", "fallback:ErrorExpression"),
                fallbackVisited
        );

        // Specific handler dispatch + SKIP_CHILDREN pruning
        var specificVisited = new ArrayList<String>();
        new ASTWalker(new ASTNodeHandler() {
            @Override
            public FrontendASTTraversalDirective handleNode(Node node) {
                specificVisited.add("generic:" + node.getClass().getSimpleName());
                return FrontendASTTraversalDirective.CONTINUE;
            }

            @Override
            public FrontendASTTraversalDirective handleRecoveredInfixExpression(RecoveredInfixExpression node) {
                specificVisited.add("specific:" + node.operator());
                return FrontendASTTraversalDirective.SKIP_CHILDREN;
            }
        }).walk(recovered);
        assertEquals(List.of("specific:is"), specificVisited);
    }

    private static SourceFile sampleTree() {
        var parameterType = new TypeRef("int", RANGE);
        var returnType = new TypeRef("void", RANGE);
        var function = new FunctionDeclaration(
                "run",
                List.of(new Parameter("value", parameterType, null, false, RANGE)),
                returnType,
                false,
                new Block(List.of(
                        new VariableDeclaration(
                                DeclarationKind.VAR,
                                "result",
                                null,
                                new CallExpression(
                                        new IdentifierExpression("compute", RANGE),
                                        List.of(new LiteralExpression("number", "1", RANGE)),
                                        RANGE
                                ),
                                false,
                                "variable_statement",
                                RANGE
                        ),
                        new ReturnStatement(new IdentifierExpression("result", RANGE), RANGE)
                ), RANGE),
                RANGE
        );

        return new SourceFile(List.of(function, new PassStatement(RANGE)), RANGE);
    }
}
