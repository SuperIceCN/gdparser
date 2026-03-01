package dev.superice.gdparser.frontend.cst;

import dev.superice.gdparser.infra.treesitter.GdParserFacade;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

class CstFixtureScriptsTest {

    private static final Path FIXTURE_DIR = Path.of("src", "test", "resources", "gdscript");
    private static final Map<String, Integer> EXPECTED_ISSUE_COUNT_BY_FILE = Map.of();
    private static GdParserFacade parserFacade;

    @BeforeAll
    static void setUpFacade() {
        parserFacade = GdParserFacade.withDefaultLanguage();
    }

    @TestFactory
    List<DynamicTest> fixtureScriptsShouldMatchExpectedStructuralIssues() throws IOException {
        var scripts = fixtureScripts();
        var tests = new ArrayList<DynamicTest>();
        for (var script : scripts) {
            var displayName = "parse-issues-match-expectation: " + script.getFileName();
            tests.add(dynamicTest(displayName, () -> {
                var source = Files.readString(script, StandardCharsets.UTF_8);
                var root = parse(source);
                var issues = CstErrorDetector.collect(root);
                var expectedIssueCount = EXPECTED_ISSUE_COUNT_BY_FILE.getOrDefault(script.getFileName().toString(), 0);

                assertEquals("source", root.type());
                assertTrue(root.isNamed());
                assertEquals(0, root.range().startByte());
                assertEquals(source.getBytes(StandardCharsets.UTF_8).length, root.range().endByte());
                assertEquals(0, root.range().startPoint().row());
                assertEquals(0, root.range().startPoint().column());
                assertTrue(root.sExpression().contains("source"));
                assertFalse(root.namedChildren().isEmpty());

                assertEquals(
                        expectedIssueCount,
                        issues.size(),
                        () -> "Unexpected CST issue count in " + script.getFileName() + ": " + issues
                );
                if (expectedIssueCount == 0) {
                    assertFalse(root.hasError(), () -> "Expected clean CST but hasError() is true for " + script.getFileName());
                } else {
                    assertTrue(root.hasError(), () -> "Expected CST errors but hasError() is false for " + script.getFileName());
                }
            }));
        }
        return List.copyOf(tests);
    }

    @TestFactory
    List<DynamicTest> fixtureScriptsShouldRespectCstInvariants() throws IOException {
        var scripts = fixtureScripts();
        var tests = new ArrayList<DynamicTest>();
        for (var script : scripts) {
            var displayName = "cst-invariants: " + script.getFileName();
            tests.add(dynamicTest(displayName, () -> {
                var source = Files.readString(script, StandardCharsets.UTF_8);
                var root = parse(source);
                var nodes = flatten(root);

                assertFalse(nodes.isEmpty());
                for (var node : nodes) {
                    assertTrue(node.range().startByte() <= node.range().endByte());
                    assertTrue(node.range().startPoint().row() >= 0);
                    assertTrue(node.range().startPoint().column() >= 0);
                    assertTrue(node.range().endPoint().row() >= 0);
                    assertTrue(node.range().endPoint().column() >= 0);

                    for (var namedChild : node.namedChildren()) {
                        assertTrue(node.children().contains(namedChild));
                        assertTrue(namedChild.isNamed());
                    }

                    for (var child : node.children()) {
                        assertTrue(child.range().startByte() >= node.range().startByte());
                        assertTrue(child.range().endByte() <= node.range().endByte());
                    }
                }

                var lastNode = nodes.getLast();
                assertNotNull(lastNode.type());
                assertFalse(source.isBlank());
            }));
        }
        return List.copyOf(tests);
    }

    @TestFactory
    List<DynamicTest> functionDefinitionsShouldExposeStableFields() throws IOException {
        var scripts = fixtureScripts();
        var tests = new ArrayList<DynamicTest>();
        for (var script : scripts) {
            var displayName = "function-fields: " + script.getFileName();
            tests.add(dynamicTest(displayName, () -> {
                var source = Files.readString(script, StandardCharsets.UTF_8);
                var root = parse(source);
                var functions = flatten(root).stream()
                        .filter(node -> node.type().equals("function_definition"))
                        .toList();

                assertFalse(functions.isEmpty(), () -> "No function_definition nodes in " + script.getFileName());
                for (var function : functions) {
                    var nameNode = function.childByField("name");
                    var parametersNode = function.childByField("parameters");

                    assertNotNull(nameNode);
                    assertFalse(nameNode.isError());
                    assertFalse(nameNode.isMissing());
                    assertTrue(nameNode.range().endByte() >= nameNode.range().startByte());

                    assertNotNull(parametersNode);
                    assertFalse(parametersNode.isError());
                    assertFalse(parametersNode.isMissing());
                }
            }));
        }
        return List.copyOf(tests);
    }

    private static CstNodeView parse(String source) {
        return parserFacade.parseCstRoot(source);
    }

    private static List<Path> fixtureScripts() throws IOException {
        assertTrue(Files.exists(FIXTURE_DIR), () -> "Fixture directory not found: " + FIXTURE_DIR);
        try (var stream = Files.list(FIXTURE_DIR)) {
            var files = stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".gd"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
            assertFalse(files.isEmpty(), () -> "No .gd files found in fixture directory: " + FIXTURE_DIR);
            return files;
        }
    }

    private static List<CstNodeView> flatten(CstNodeView root) {
        var result = new ArrayList<CstNodeView>();
        var queue = new ArrayDeque<CstNodeView>();
        queue.add(root);
        while (!queue.isEmpty()) {
            var node = queue.removeFirst();
            result.add(node);
            for (var child : node.children()) {
                queue.addLast(child);
            }
        }
        return List.copyOf(result);
    }
}
