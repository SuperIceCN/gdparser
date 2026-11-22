package dev.superice.gdparser.frontend.gdscript;

import dev.superice.gdparser.frontend.ast.AssertStatement;
import dev.superice.gdparser.frontend.ast.AttributeCallStep;
import dev.superice.gdparser.frontend.ast.AttributeExpression;
import dev.superice.gdparser.frontend.ast.AttributePropertyStep;
import dev.superice.gdparser.frontend.ast.AttributeSubscriptStep;
import dev.superice.gdparser.frontend.ast.BinaryExpression;
import dev.superice.gdparser.frontend.ast.CallExpression;
import dev.superice.gdparser.frontend.ast.CommentStatement;
import dev.superice.gdparser.frontend.ast.ConstructorDeclaration;
import dev.superice.gdparser.frontend.ast.DictionaryExpression;
import dev.superice.gdparser.frontend.ast.ExpressionStatement;
import dev.superice.gdparser.frontend.ast.FunctionDeclaration;
import dev.superice.gdparser.frontend.ast.IdentifierExpression;
import dev.superice.gdparser.frontend.ast.IfStatement;
import dev.superice.gdparser.frontend.ast.LiteralExpression;
import dev.superice.gdparser.frontend.ast.MatchStatement;
import dev.superice.gdparser.frontend.ast.PassStatement;
import dev.superice.gdparser.frontend.ast.RegionDirectiveStatement;
import dev.superice.gdparser.frontend.ast.Statement;
import dev.superice.gdparser.frontend.ast.SubscriptExpression;
import dev.superice.gdparser.frontend.ast.TypeTestExpression;
import dev.superice.gdparser.frontend.ast.VariableDeclaration;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

/// Locks the AST shapes that the Godot-port parser must reproduce, by asserting them
/// against the current tree-sitter reference path. Each corpus sample is pinned twice:
/// structurally (the semantic contract) and via a stored serialization snapshot (guarding
/// the reference path — which must not change during the migration — from drifting).
class GdParityReferenceTest extends GdParserParityTestSupport {

    private static final Path SNAPSHOT_DIR = Path.of("src", "test", "resources", "gdparser-parity");

    /// Parity reference corpus. Keep in sync with the stored snapshots; any intentional
    /// change requires regenerating the snapshot and re-checking the structural contract.
    private static final Map<String, String> CORPUS = new LinkedHashMap<>();

    static {
        CORPUS.put("elif_chain", """
                func f(x):
                \tif x > 0:
                \t\treturn 1
                \telif x < 0:
                \t\treturn -1
                \telse:
                \t\treturn 0
                """);
        CORPUS.put("constructor_plain", """
                func _init():
                \tpass
                """);
        CORPUS.put("constructor_typed", """
                func _init() -> void:
                \tpass
                """);
        CORPUS.put("suffix_chain", """
                func f():
                \tobj.a.b(1).c[2]
                \tarr[0]
                \tf(x)
                """);
        CORPUS.put("super_forms", """
                func f():
                \tsuper.foo()
                \tsuper()
                """);
        CORPUS.put("inferred_type", """
                var x := 1
                var y : = 2
                """);
        CORPUS.put("not_in", """
                func f():
                \tif a not in b:
                \t\tpass
                \tif c is not int:
                \t\tpass
                """);
        CORPUS.put("assert_stmt", """
                func f():
                \tassert(x > 0, "bad")
                """);
        CORPUS.put("lua_dict_key", """
                var d = { name = 1 }
                """);
        CORPUS.put("class_body_pass", """
                extends Node
                pass
                """);
        CORPUS.put("key_only_dict_pattern", """
                func f(d):
                \tmatch d:
                \t\t{"name"}:
                \t\t\tpass
                """);
        CORPUS.put("comments_and_regions", """
                #region 初始化
                var x = 1 # 尾注释
                # 整行注释
                func f():
                \tpass # 尾
                #endregion
                """);
    }

    @TestFactory
    List<DynamicTest> referencePathMatchesStoredSnapshots() {
        var tests = new ArrayList<DynamicTest>();
        for (var entry : CORPUS.entrySet()) {
            tests.add(dynamicTest("snapshot: " + entry.getKey(), () -> {
                var snapshot = Files.readString(SNAPSHOT_DIR.resolve(entry.getKey() + ".sexp"),
                        StandardCharsets.UTF_8);
                assertEquals(snapshot, treeSitterSexp(entry.getValue()),
                        () -> "reference path drifted for " + entry.getKey()
                                + "; if the change is intentional, regenerate the snapshot");
            }));
        }
        return List.copyOf(tests);
    }

    @Test
    void elifChainFoldsIntoElifClauses() {
        var function = assertInstanceOf(FunctionDeclaration.class,
                parseViaTreeSitter(CORPUS.get("elif_chain")).ast().statements().getFirst());
        var ifStatement = assertInstanceOf(IfStatement.class, function.body().statements().getFirst());
        assertEquals(1, ifStatement.elifClauses().size());
        assertNotNull(ifStatement.elseBody());
    }

    @Test
    void initFunctionBecomesConstructorWithOptionalReturnType() {
        var plain = assertInstanceOf(ConstructorDeclaration.class,
                parseViaTreeSitter(CORPUS.get("constructor_plain")).ast().statements().getFirst());
        assertNull(plain.returnType());

        var typed = assertInstanceOf(ConstructorDeclaration.class,
                parseViaTreeSitter(CORPUS.get("constructor_typed")).ast().statements().getFirst());
        assertNotNull(typed.returnType());
        assertEquals("void", typed.returnType().sourceText());
    }

    @Test
    void suffixChainsFoldIntoAttributeSteps() {
        var function = assertInstanceOf(FunctionDeclaration.class,
                parseViaTreeSitter(CORPUS.get("suffix_chain")).ast().statements().getFirst());
        var body = function.body().statements();

        var chain = assertInstanceOf(AttributeExpression.class,
                assertInstanceOf(ExpressionStatement.class, body.get(0)).expression());
        assertEquals(3, chain.steps().size());
        assertInstanceOf(AttributePropertyStep.class, chain.steps().get(0));
        assertInstanceOf(AttributeCallStep.class, chain.steps().get(1));
        assertInstanceOf(AttributeSubscriptStep.class, chain.steps().get(2));
        assertEquals("a", ((AttributePropertyStep) chain.steps().get(0)).name());
        assertEquals("b", ((AttributeCallStep) chain.steps().get(1)).name());
        assertEquals("c", ((AttributeSubscriptStep) chain.steps().get(2)).name());

        assertInstanceOf(SubscriptExpression.class,
                assertInstanceOf(ExpressionStatement.class, body.get(1)).expression());
        assertInstanceOf(CallExpression.class,
                assertInstanceOf(ExpressionStatement.class, body.get(2)).expression());
    }

    @Test
    void superFormsUsePlainIdentifierShapes() {
        var function = assertInstanceOf(FunctionDeclaration.class,
                parseViaTreeSitter(CORPUS.get("super_forms")).ast().statements().getFirst());
        var body = function.body().statements();

        var methodCall = assertInstanceOf(AttributeExpression.class,
                assertInstanceOf(ExpressionStatement.class, body.get(0)).expression());
        var base = assertInstanceOf(IdentifierExpression.class, methodCall.base());
        assertEquals("super", base.name());
        assertInstanceOf(AttributeCallStep.class, methodCall.steps().getFirst());

        var directCall = assertInstanceOf(CallExpression.class,
                assertInstanceOf(ExpressionStatement.class, body.get(1)).expression());
        assertEquals("super", assertInstanceOf(IdentifierExpression.class, directCall.callee()).name());
    }

    @Test
    void inferredTypeMarkerKeepsSourceSliceIncludingInnerWhitespace() {
        var statements = parseViaTreeSitter(CORPUS.get("inferred_type")).ast().statements();

        var compact = assertInstanceOf(VariableDeclaration.class, statements.get(0));
        assertNotNull(compact.type());
        assertEquals(":=", compact.type().sourceText());

        var spaced = assertInstanceOf(VariableDeclaration.class, statements.get(1));
        assertNotNull(spaced.type());
        assertEquals(": =", spaced.type().sourceText());
    }

    @Test
    void inferredMarkersKeepTheirExactPhysicalSliceInBothParsers() {
        for (var marker : List.of(":=", ": =", ":\t=")) {
            var source = "var local " + marker + " 1\nconst C " + marker
                    + " 2\nfunc f(p " + marker + " 3): pass\n";
            for (var result : List.of(parseViaTreeSitter(source), parseViaGdScript(source))) {
                assertTrue(result.diagnostics().isEmpty(), () -> result.diagnostics().toString());
                var statements = result.ast().statements();
                var variable = assertInstanceOf(VariableDeclaration.class, statements.getFirst());
                var constant = assertInstanceOf(VariableDeclaration.class, statements.get(1));
                var parameter = assertInstanceOf(FunctionDeclaration.class, statements.getLast())
                        .parameters().getFirst();
                var cursor = 0;
                for (var type : List.of(variable.type(), constant.type(), parameter.type())) {
                    var start = source.indexOf(marker, cursor);
                    cursor = start + marker.length();
                    assertEquals(marker, type.sourceText());
                    assertEquals(GdScriptParserErrorRecoveryTest.utf8Range(source, start, cursor), type.range());
                }
            }
        }
    }

    @Test
    void notInAndIsNotFoldIntoDedicatedShapes() {
        var function = assertInstanceOf(FunctionDeclaration.class,
                parseViaTreeSitter(CORPUS.get("not_in")).ast().statements().getFirst());
        var body = function.body().statements();

        var notInIf = assertInstanceOf(IfStatement.class, body.get(0));
        var notIn = assertInstanceOf(BinaryExpression.class, notInIf.condition());
        assertEquals("not in", notIn.operator());

        var isNotIf = assertInstanceOf(IfStatement.class, body.get(1));
        var typeTest = assertInstanceOf(TypeTestExpression.class, isNotIf.condition());
        assertTrue(typeTest.negated());
    }

    @Test
    void assertCallStatementPromotesToAssertStatement() {
        var function = assertInstanceOf(FunctionDeclaration.class,
                parseViaTreeSitter(CORPUS.get("assert_stmt")).ast().statements().getFirst());
        var assertStatement = assertInstanceOf(AssertStatement.class,
                function.body().statements().getFirst());
        assertNotNull(assertStatement.message());
    }

    @Test
    void luaStyleDictionaryKeySynthesizesStringNameLiteral() {
        var declaration = assertInstanceOf(VariableDeclaration.class,
                parseViaTreeSitter(CORPUS.get("lua_dict_key")).ast().statements().getFirst());
        var dictionary = assertInstanceOf(DictionaryExpression.class, declaration.value());
        var key = assertInstanceOf(LiteralExpression.class, dictionary.entries().getFirst().key());
        // The synthesized text does not exist in the source; it is a documented contract.
        assertEquals("string_name", key.kind());
        assertEquals("&\"name\"", key.sourceText());
    }

    @Test
    void classBodyPassProducesPassStatement() {
        var statements = parseViaTreeSitter(CORPUS.get("class_body_pass")).ast().statements();
        assertInstanceOf(PassStatement.class, statements.get(1));
    }

    @Test
    void keyOnlyDictionaryPatternPreservesKeyWithNullValueAndNoDiagnostics() {
        var result = parseViaTreeSitter(CORPUS.get("key_only_dict_pattern"));
        assertTrue(result.diagnostics().isEmpty(), () -> "Unexpected diagnostics: " + result.diagnostics());
        var function = assertInstanceOf(FunctionDeclaration.class, result.ast().statements().getFirst());
        var match = assertInstanceOf(MatchStatement.class, function.body().statements().getFirst());
        var dictionary = assertInstanceOf(DictionaryExpression.class,
                match.sections().getFirst().patterns().getFirst());
        assertEquals(1, dictionary.entries().size());
        var key = assertInstanceOf(LiteralExpression.class, dictionary.entries().getFirst().key());
        assertEquals("string", key.kind());
        assertEquals("\"name\"", key.sourceText());
        assertNull(dictionary.entries().getFirst().value());
    }

    @Test
    void commentsAndRegionsInsertIntoStatementSequence() {
        var statements = parseViaTreeSitter(CORPUS.get("comments_and_regions")).ast().statements();

        var regionStart = assertInstanceOf(RegionDirectiveStatement.class, statements.get(0));
        assertEquals("start", regionStart.directive());
        assertEquals("初始化", regionStart.label());

        assertInstanceOf(VariableDeclaration.class, statements.get(1));
        // A trailing comment lands right after the statement it follows.
        var trailing = assertInstanceOf(CommentStatement.class, statements.get(2));
        assertEquals("# 尾注释", trailing.text());
        // A whole-line comment takes its source position between siblings.
        assertEquals("# 整行注释", assertInstanceOf(CommentStatement.class, statements.get(3)).text());

        var function = assertInstanceOf(FunctionDeclaration.class, statements.get(4));
        var body = function.body().statements();
        assertInstanceOf(PassStatement.class, body.get(0));
        assertEquals("# 尾", assertInstanceOf(CommentStatement.class, body.get(1)).text());

        var regionEnd = assertInstanceOf(RegionDirectiveStatement.class, statements.get(5));
        assertEquals("end", regionEnd.directive());
        assertNull(regionEnd.label());
    }

    @Test
    void allCorpusSamplesHaveNoDiagnostics() {
        for (var entry : CORPUS.entrySet()) {
            var result = parseViaTreeSitter(entry.getValue());
            assertTrue(result.diagnostics().isEmpty(),
                    () -> entry.getKey() + " diagnostics: " + result.diagnostics());
        }
    }

    @Test
    void corpusCoversAllStoredSnapshots() throws IOException {
        List<String> snapshots;
        try (var stream = Files.list(SNAPSHOT_DIR)) {
            snapshots = stream.filter(p -> p.getFileName().toString().endsWith(".sexp"))
                    .map(p -> p.getFileName().toString())
                    .sorted()
                    .toList();
        }
        var corpusNames = CORPUS.keySet().stream().map(name -> name + ".sexp").sorted().toList();
        assertEquals(corpusNames, snapshots, "corpus and snapshot files must stay in sync");
    }
}
