package dev.superice.gdparser.frontend.cst;

import dev.superice.gdparser.infra.treesitter.GdParserFacade;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SuppressWarnings("deprecation") // The legacy backend remains a regression reference.
class CstAdapterTest {

    private static GdParserFacade parserFacade;

    @BeforeAll
    static void setUpFacade() {
        parserFacade = GdParserFacade.withDefaultLanguage();
    }

    @Test
    void rootNodeShouldExposeTypeRangeAndSExpression() {
        var source = "func _ready(): pass";
        var root = parse(source);

        assertEquals("source", root.type());
        assertTrue(root.isNamed());
        assertEquals(0, root.range().startByte());
        assertEquals(source.length(), root.range().endByte());
        assertEquals(0, root.range().startPoint().row());
        assertEquals(0, root.range().startPoint().column());
        assertTrue(root.sExpression().contains("source"));
        assertFalse(root.children().isEmpty());
    }

    @Test
    void childByFieldAndNamedChildrenOfTypeShouldWork() {
        var source = """
                func _ready():
                    pass
                
                func _process(delta):
                    pass
                """;
        var root = parse(source);
        var functions = root.namedChildrenOfType("function_definition");

        assertEquals(2, functions.size());

        var firstFunction = functions.getFirst();
        var nameNode = firstFunction.childByField("name");
        var parametersNode = firstFunction.childByField("parameters");

        assertNotNull(nameNode);
        assertFalse(nameNode.isError());
        assertFalse(nameNode.isMissing());
        assertTrue(nameNode.range().endByte() >= nameNode.range().startByte());
        assertNotNull(parametersNode);
        assertNull(firstFunction.childByField("unknown_field"));
        assertFalse(firstFunction.namedChildren().isEmpty());
    }

    @Test
    void namedChildrenMustBeSubsetOfAllChildren() {
        var root = parse("func _ready(): pass");

        for (var namedChild : root.namedChildren()) {
            assertTrue(root.children().contains(namedChild));
            assertTrue(namedChild.isNamed());
        }
    }

    @Test
    void errorDetectorShouldReturnNoIssuesForValidSource() {
        var root = parse("func _ready(): pass");
        var issues = CstErrorDetector.collect(root);

        assertTrue(issues.isEmpty());
        assertFalse(CstErrorDetector.hasIssues(root));
    }

    @Test
    void errorDetectorShouldCollectErrorAndMissingNodesForInvalidSource() {
        var root = parse("func _ready(: pass");
        var issues = CstErrorDetector.collect(root);

        assertFalse(issues.isEmpty());
        assertTrue(CstErrorDetector.hasIssues(root));
        assertTrue(
                issues.stream().anyMatch(issue -> issue.kind() == CstIssueKind.ERROR || issue.kind() == CstIssueKind.MISSING)
        );
        for (var issue : issues) {
            assertNotNull(issue.nodeType());
            assertTrue(issue.range().endByte() >= issue.range().startByte());
        }
    }

    @Test
    void errorDetectorShouldCollectZeroWidthPlaceholderAsMissingIssue() {
        // The grammar reports the absent member name in `node. + 1` as a zero-width identifier
        // placeholder carrying an error cost rather than a tree-sitter MISSING node.
        var root = parse("func f():\n\tvar x = node. + 1");
        var issues = CstErrorDetector.collect(root);

        var missing = issues.stream().filter(issue -> issue.kind() == CstIssueKind.MISSING).toList();
        assertEquals(1, missing.size(), () -> "Expected exactly one MISSING issue: " + issues);
        assertEquals("identifier", missing.getFirst().nodeType());
        assertEquals(24, missing.getFirst().range().startByte());
        assertEquals(24, missing.getFirst().range().endByte());
    }

    private static CstNodeView parse(String source) {
        return parserFacade.parseCstRoot(source);
    }
}
