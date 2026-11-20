package dev.superice.gdparser.frontend.gdscript;

import dev.superice.gdparser.frontend.ast.AnnotationStatement;
import dev.superice.gdparser.frontend.ast.ArrayExpression;
import dev.superice.gdparser.frontend.ast.AssertStatement;
import dev.superice.gdparser.frontend.ast.AssignmentExpression;
import dev.superice.gdparser.frontend.ast.AstDiagnostic;
import dev.superice.gdparser.frontend.ast.AstDiagnosticSeverity;
import dev.superice.gdparser.frontend.ast.AstMappingResult;
import dev.superice.gdparser.frontend.ast.AttributeCallStep;
import dev.superice.gdparser.frontend.ast.AttributeExpression;
import dev.superice.gdparser.frontend.ast.AttributePropertyStep;
import dev.superice.gdparser.frontend.ast.AttributeSubscriptStep;
import dev.superice.gdparser.frontend.ast.AwaitExpression;
import dev.superice.gdparser.frontend.ast.BinaryExpression;
import dev.superice.gdparser.frontend.ast.BreakStatement;
import dev.superice.gdparser.frontend.ast.BreakpointStatement;
import dev.superice.gdparser.frontend.ast.CallExpression;
import dev.superice.gdparser.frontend.ast.CastExpression;
import dev.superice.gdparser.frontend.ast.ClassDeclaration;
import dev.superice.gdparser.frontend.ast.ClassNameStatement;
import dev.superice.gdparser.frontend.ast.CommentStatement;
import dev.superice.gdparser.frontend.ast.ConditionalExpression;
import dev.superice.gdparser.frontend.ast.ConstructorDeclaration;
import dev.superice.gdparser.frontend.ast.ContinueStatement;
import dev.superice.gdparser.frontend.ast.DeclarationKind;
import dev.superice.gdparser.frontend.ast.DictionaryExpression;
import dev.superice.gdparser.frontend.ast.EnumDeclaration;
import dev.superice.gdparser.frontend.ast.ErrorExpression;
import dev.superice.gdparser.frontend.ast.ExpressionStatement;
import dev.superice.gdparser.frontend.ast.ExtendsStatement;
import dev.superice.gdparser.frontend.ast.ForStatement;
import dev.superice.gdparser.frontend.ast.FunctionDeclaration;
import dev.superice.gdparser.frontend.ast.GetNodeExpression;
import dev.superice.gdparser.frontend.ast.IdentifierExpression;
import dev.superice.gdparser.frontend.ast.IfStatement;
import dev.superice.gdparser.frontend.ast.LambdaExpression;
import dev.superice.gdparser.frontend.ast.LiteralExpression;
import dev.superice.gdparser.frontend.ast.MatchStatement;
import dev.superice.gdparser.frontend.ast.MissingAttributeStep;
import dev.superice.gdparser.frontend.ast.PassStatement;
import dev.superice.gdparser.frontend.ast.PatternBindingExpression;
import dev.superice.gdparser.frontend.ast.Point;
import dev.superice.gdparser.frontend.ast.PreloadExpression;
import dev.superice.gdparser.frontend.ast.Range;
import dev.superice.gdparser.frontend.ast.RegionDirectiveStatement;
import dev.superice.gdparser.frontend.ast.ReturnStatement;
import dev.superice.gdparser.frontend.ast.SelfExpression;
import dev.superice.gdparser.frontend.ast.SignalStatement;
import dev.superice.gdparser.frontend.ast.SourceFile;
import dev.superice.gdparser.frontend.ast.Statement;
import dev.superice.gdparser.frontend.ast.SubscriptExpression;
import dev.superice.gdparser.frontend.ast.TypeTestExpression;
import dev.superice.gdparser.frontend.ast.UnaryExpression;
import dev.superice.gdparser.frontend.ast.VariableDeclaration;
import dev.superice.gdparser.frontend.ast.WhileStatement;
import dev.superice.gdparser.frontend.cst.CstIssueKind;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Behavior-anchoring tests for the declaration-layer parser: positive cases pin AST shapes and
/// physical byte-based ranges; negative cases pin Godot's exact diagnostics, recovery and
/// termination. Parity with the tree-sitter path on legal sources is covered separately by
/// `GdParserParityTest`.
class GdScriptParserTest {

    private static AstMappingResult parse(String source) {
        return new GdScriptParser().parse(source);
    }

    /// Parses and asserts that no ERROR diagnostics were produced.
    private static SourceFile cleanAst(String source) {
        var result = parse(source);
        assertEquals(List.of(), errorMessages(result),
                () -> "expected a clean parse but got errors: " + errorMessages(result));
        return result.ast();
    }

    private static List<String> errorMessages(AstMappingResult result) {
        return result.diagnostics().stream()
                .filter(d -> d.severity() == AstDiagnosticSeverity.ERROR)
                .map(AstDiagnostic::message)
                .toList();
    }

    private static <T> @NotNull T requireNotNull(@Nullable T value) {
        assertNotNull(value);
        return value;
    }

    private static Statement onlyStatement(SourceFile ast) {
        assertEquals(1, ast.statements().size(), () -> "expected one statement in: " + ast);
        return ast.statements().getFirst();
    }

    // -----------------------------------------------------------------------------------------
    // Program prefix.
    // -----------------------------------------------------------------------------------------

    @Test
    void classNameOnly() {
        var ast = cleanAst("class_name Foo\n");
        var statement = assertInstanceOf(ClassNameStatement.class, onlyStatement(ast));
        assertEquals("Foo", statement.name());
        assertNull(statement.extendsTarget());
        assertEquals(new Range(0, 14, new Point(0, 0), new Point(0, 14)), statement.range());
    }

    @Test
    void extendsOnly() {
        var ast = cleanAst("extends Node\n");
        var statement = assertInstanceOf(ExtendsStatement.class, onlyStatement(ast));
        assertEquals("Node", statement.target());
        assertEquals(new Range(0, 12, new Point(0, 0), new Point(0, 12)), statement.range());
    }

    @Test
    void classNameAndExtendsOnSameLine() {
        var ast = cleanAst("class_name Foo extends Node\n");
        var statement = assertInstanceOf(ClassNameStatement.class, onlyStatement(ast));
        assertEquals("Foo", statement.name());
        assertEquals("Node", statement.extendsTarget());
        assertEquals(27, statement.range().endByte());
    }

    @Test
    void extendsMayPrecedeClassName() {
        var ast = cleanAst("extends Node\nclass_name Foo\n");
        assertInstanceOf(ExtendsStatement.class, ast.statements().getFirst());
        assertInstanceOf(ClassNameStatement.class, ast.statements().get(1));
    }

    @Test
    void extendsStringPathKeepsQuotes() {
        var ast = cleanAst("extends \"res://base.gd\"\n");
        assertEquals("\"res://base.gd\"",
                assertInstanceOf(ExtendsStatement.class, onlyStatement(ast)).target());
    }

    @Test
    void extendsDottedChain() {
        var ast = cleanAst("extends Foo.Bar.Baz\n");
        assertEquals("Foo.Bar.Baz", assertInstanceOf(ExtendsStatement.class, onlyStatement(ast)).target());
    }

    @Test
    void emptyAndWhitespaceOnlyFiles() {
        var facade = new GdScriptParserFacade();
        var emptyCases = List.of("", "\n", "\n\n", "\r\n", "\r\n\r\n", "   \t  ", "   \n\t \n");
        for (var source : emptyCases) {
            var ast = cleanAst(source);
            assertTrue(ast.statements().isEmpty(), () -> "expected empty AST for: " + source);
            var index = SourceTextIndex.of(source);
            var eof = index.utf8Length();
            assertEquals(index.rangeOf(eof, eof), ast.range(), () -> "expected zero-width EOF range for: " + source);
            assertTrue(facade.parseStrict(source).statements().isEmpty());
        }
    }

    @Test
    void commentAndRegionOnlyFilesParseCleanlyAndPreserveTriviaNodes() {
        var facade = new GdScriptParserFacade();

        var singleComment = facade.parseStrict("# comment");
        assertEquals(1, singleComment.statements().size());
        assertEquals("# comment", assertInstanceOf(CommentStatement.class, singleComment.statements().getFirst()).text());

        var crlfComments = facade.parseStrict("# c1\r\n# c2\r\n");
        assertEquals(2, crlfComments.statements().size());
        assertInstanceOf(CommentStatement.class, crlfComments.statements().get(0));
        assertInstanceOf(CommentStatement.class, crlfComments.statements().get(1));

        var regionOnly = facade.parseStrict("#region foo\n# x\n#endregion\n");
        assertEquals(3, regionOnly.statements().size());
        var start = assertInstanceOf(RegionDirectiveStatement.class, regionOnly.statements().get(0));
        assertEquals("start", start.directive());
        assertEquals("foo", start.label());
        assertEquals("# x", assertInstanceOf(CommentStatement.class, regionOnly.statements().get(1)).text());
        var end = assertInstanceOf(RegionDirectiveStatement.class, regionOnly.statements().get(2));
        assertEquals("end", end.directive());

        var regionNoTrailingNewline = facade.parseStrict("#region foo\n#endregion");
        assertEquals(2, regionNoTrailingNewline.statements().size());
    }

    @Test
    void leadingLexicalErrorBeforeNewlineIsReportedWithoutCascadingClassBodyNewlineError() {
        var invalidWhitespace = parse("\u2003\n");
        assertEquals(List.of("Invalid white space character U+2003."), errorMessages(invalidWhitespace));

        var invalidChar = parse("\u0001\n");
        assertEquals(1, errorMessages(invalidChar).size());
        assertTrue(errorMessages(invalidChar).getFirst().contains("U+0001"));

        var strayCr = parse("\ra\n");
        assertEquals(List.of("Stray carriage return character in source code."), errorMessages(strayCr));

        var strayCrBeforeDecl = parse("\r\nvar a = 1\n");
        assertEquals(List.of(), errorMessages(strayCrBeforeDecl));
        assertEquals(1, strayCrBeforeDecl.ast().statements().size());
        assertInstanceOf(VariableDeclaration.class, strayCrBeforeDecl.ast().statements().getFirst());

        // Leading lexical error on a blank line resets panic mode before parsing declarations
        // outside the synchronization set.
        var enumAfterLeadingError = parse("\u2003\nenum E { A }\n");
        assertEquals(List.of("Invalid white space character U+2003."), errorMessages(enumAfterLeadingError));
        var survivingEnum = assertInstanceOf(EnumDeclaration.class,
                enumAfterLeadingError.ast().statements().getFirst());
        assertEquals("E", survivingEnum.name());

        var passAfterLeadingError = parse("\u2003\npass\n");
        assertEquals(List.of("Invalid white space character U+2003."), errorMessages(passAfterLeadingError));
        assertInstanceOf(PassStatement.class, passAfterLeadingError.ast().statements().getFirst());

        // Malformed blank lines skipped by the tokenizer emit no NEWLINE token, so leading
        // stray-CR errors on earlier lines must not suppress declarations on subsequent lines.
        var enumAfterLeadingStrayCrBlankLine = parse("\r \nenum E { A }\n");
        assertEquals(List.of("Stray carriage return character in source code."),
                errorMessages(enumAfterLeadingStrayCrBlankLine));
        var enumAfterCr = assertInstanceOf(EnumDeclaration.class,
                enumAfterLeadingStrayCrBlankLine.ast().statements().getFirst());
        assertEquals("E", enumAfterCr.name());

        var passAfterLeadingStrayCrBlankLine = parse("\r \npass\n");
        assertEquals(List.of("Stray carriage return character in source code."),
                errorMessages(passAfterLeadingStrayCrBlankLine));
        assertInstanceOf(PassStatement.class, passAfterLeadingStrayCrBlankLine.ast().statements().getFirst());

        var lifoStrayCrErrorsSameLineIdentifier = parse("\r \n\ra\n");
        assertEquals(List.of("Stray carriage return character in source code.",
                        "Stray carriage return character in source code."),
                errorMessages(lifoStrayCrErrorsSameLineIdentifier));

        var strayCrTrailingWhitespaceLine = parse("a\n\r \n");
        assertEquals(List.of("Unexpected identifier \"a\" in class body.",
                        "Stray carriage return character in source code."),
                errorMessages(strayCrTrailingWhitespaceLine));

        var strayCrAfterValidDecl = parse("var a = 1\n\r \n");
        assertEquals(List.of("Stray carriage return character in source code."),
                errorMessages(strayCrAfterValidDecl));
    }

    @Test
    void trailingBackslashContinuationAtEofInIndentedBlockParsesCleanly() {
        var facade = new GdScriptParserFacade();
        for (var source : List.of("func f():\n\tpass\\\n", "func f():\n\tpass\\\r\n")) {
            var ast = facade.parseStrict(source);
            var fn = assertInstanceOf(FunctionDeclaration.class, ast.statements().getFirst());
            assertEquals("f", fn.name());
            assertInstanceOf(PassStatement.class, fn.body().statements().getFirst());
        }
    }

    @Test
    void trailingIndentedStrayCarriageReturnAtEofDoesNotEmitSpuriousSuiteErrors() {
        for (var source : List.of(
                "func f():\n\tpass\n\t\t\r",
                "func f():\n\tpass\n\t\t\r ",
                "func f():\n\tpass\n\t\t\r \n")) {
            var result = parse(source);
            assertEquals(List.of("Stray carriage return character in source code."),
                    errorMessages(result), () -> "source: " + source);
            var fn = assertInstanceOf(FunctionDeclaration.class, result.ast().statements().getFirst());
            assertEquals("f", fn.name());
            assertInstanceOf(PassStatement.class, fn.body().statements().getFirst());
        }
    }

    @Test
    void commentOnlyFunctionAndClassBodiesStillReportMissingIndentedBlockAndKeepSiblings() {
        var funcResult = parse("func f():\n\t# comment\nfunc g():\n\tpass\n");
        assertTrue(errorMessages(funcResult).contains("Expected indented block after function declaration."));
        var functions = funcResult.ast().statements().stream()
                .filter(FunctionDeclaration.class::isInstance)
                .map(FunctionDeclaration.class::cast)
                .toList();
        assertEquals(List.of("f", "g"), functions.stream().map(FunctionDeclaration::name).toList());

        var classResult = parse("class Inner:\n\t# comment\nvar ok = 1\n");
        assertTrue(errorMessages(classResult).contains("Expected indented block after class declaration."));
        assertTrue(classResult.ast().statements().stream().anyMatch(ClassDeclaration.class::isInstance));
        assertTrue(classResult.ast().statements().stream()
                .anyMatch(s -> s instanceof VariableDeclaration v && v.name().equals("ok")));
    }

    @Test
    void fileWithoutTrailingNewline() {
        var ast = cleanAst("var x = 1");
        assertEquals(9, ast.range().endByte());
        assertEquals(9, onlyStatement(ast).range().endByte());
    }

    @Test
    void duplicateClassNameIsReported() {
        var errors = errorMessages(parse("class_name A\nclass_name B\n"));
        assertTrue(errors.contains("\"class_name\" can only be used once."), errors::toString);
    }

    @Test
    void duplicateExtendsIsReported() {
        var errors = errorMessages(parse("extends A\nextends B\n"));
        assertTrue(errors.contains("\"extends\" can only be used once."), errors::toString);
    }

    @Test
    void classNameWithoutIdentifier() {
        var errors = errorMessages(parse("class_name\n"));
        assertTrue(errors.contains("Expected identifier for the global class name after \"class_name\"."),
                errors::toString);
    }

    @Test
    void extendsWithoutName() {
        var errors = errorMessages(parse("extends\n"));
        assertTrue(errors.contains("Expected superclass name after \"extends\"."), errors::toString);
    }

    @Test
    void extendsWithNonStringLiteral() {
        var errors = errorMessages(parse("extends 123\n"));
        assertTrue(errors.contains("Only strings or identifiers can be used after \"extends\", found \"int\" instead."),
                errors::toString);
    }

    @Test
    void trailingTokenAfterClassName() {
        var errors = errorMessages(parse("class_name X 5\n"));
        assertTrue(errors.contains("Expected end of statement after class_name statement, found \"Literal\" instead."),
                errors::toString);
    }

    // -----------------------------------------------------------------------------------------
    // Docstrings (Godot behavior: consumed at prefix / class body, no AST node).
    // -----------------------------------------------------------------------------------------

    @Test
    void prefixDocstringIsConsumedWithoutNode() {
        var ast = cleanAst("\"\"\"doc\"\"\"\nclass_name X\n");
        assertInstanceOf(ClassNameStatement.class, onlyStatement(ast));
    }

    @Test
    void classBodyDocstringIsConsumedWithoutNode() {
        var ast = cleanAst("var x = 1\n\"\"\"doc\"\"\"\nvar y = 2\n");
        assertEquals(2, ast.statements().size());
        assertInstanceOf(VariableDeclaration.class, ast.statements().getFirst());
        assertInstanceOf(VariableDeclaration.class, ast.statements().get(1));
    }

    @Test
    void docstringOnlyFileHasNoStatements() {
        var ast = cleanAst("\"\"\"doc\"\"\"\n");
        assertTrue(ast.statements().isEmpty());
    }

    @Test
    void standaloneStringRequiresNewline() {
        var errors = errorMessages(parse("\"\"\"a\"\"\" 5\n"));
        assertTrue(errors.contains("Expected newline after comment string."), errors::toString);
    }

    // -----------------------------------------------------------------------------------------
    // Nested classes.
    // -----------------------------------------------------------------------------------------

    @Test
    void nestedClassBasic() {
        var ast = cleanAst("class Inner:\n\tpass\n");
        var declaration = assertInstanceOf(ClassDeclaration.class, onlyStatement(ast));
        assertEquals("Inner", declaration.name());
        assertNull(declaration.extendsTarget());
        assertInstanceOf(PassStatement.class, declaration.body().statements().getFirst());
        assertEquals(new Range(0, 18, new Point(0, 0), new Point(1, 5)), declaration.range());
        // Multi-line blocks span from the newline after the colon to the last statement end.
        assertEquals(new Range(12, 18, new Point(0, 12), new Point(1, 5)), declaration.body().range());
    }

    @Test
    void nestedClassWithSameLineExtends() {
        var ast = cleanAst("class Inner extends Base:\n\tpass\n");
        var declaration = assertInstanceOf(ClassDeclaration.class, onlyStatement(ast));
        assertEquals("Base", declaration.extendsTarget());
    }

    @Test
    void nestedClassWithInBodyExtendsUsesGodotShape() {
        // Godot treats an extends line at the start of an indented class body as the superclass;
        // the tree-sitter path instead emits an ExtendsStatement inside the body. This pins the
        // Godot behavior (registered parity divergence).
        var ast = cleanAst("class Inner:\n\textends Base\n\tpass\n");
        var declaration = assertInstanceOf(ClassDeclaration.class, onlyStatement(ast));
        assertEquals("Base", declaration.extendsTarget());
        assertEquals(1, declaration.body().statements().size());
        assertInstanceOf(PassStatement.class, declaration.body().statements().getFirst());
    }

    @Test
    void nestedClassRejectsSecondExtends() {
        var errors = errorMessages(parse("class Inner extends A:\n\textends B\n\tpass\n"));
        assertTrue(errors.contains("Cannot use \"extends\" more than once in the same class."),
                errors::toString);
    }

    @Test
    void nestedClassMissingColon() {
        var errors = errorMessages(parse("class Inner\n\tpass\n"));
        assertTrue(errors.contains("Expected \":\" after class declaration."), errors::toString);
    }

    @Test
    void nestedClassMissingIndentedBlock() {
        var errors = errorMessages(parse("class Inner:\nvar x = 1\n"));
        assertTrue(errors.contains("Expected indented block after class declaration."), errors::toString);
    }

    @Test
    void nestedClassSingleLineBody() {
        var ast = cleanAst("class Inner: pass\n");
        var declaration = assertInstanceOf(ClassDeclaration.class, onlyStatement(ast));
        assertEquals(1, declaration.body().statements().size());
    }

    // -----------------------------------------------------------------------------------------
    // Variables and constants.
    // -----------------------------------------------------------------------------------------

    @Test
    void variableForms() {
        var ast = cleanAst("var a\nvar b = 1\nvar c: int\nvar d: int = 2\n");
        var a = assertInstanceOf(VariableDeclaration.class, ast.statements().getFirst());
        assertEquals(DeclarationKind.VAR, a.kind());
        assertNull(a.type());
        assertNull(a.value());
        assertFalse(a.isStatic());
        assertEquals("variable_statement", a.sourceNodeType());

        var d = assertInstanceOf(VariableDeclaration.class, ast.statements().get(3));
        assertEquals("int", requireNotNull(d.type()).sourceText());
        assertInstanceOf(LiteralExpression.class, d.value());
    }

    @Test
    void inferredTypeMarkerShapes() {
        var ast = cleanAst("var x := 1\nvar y : = 2\n");
        var x = assertInstanceOf(VariableDeclaration.class, ast.statements().getFirst());
        assertEquals(":=", requireNotNull(x.type()).sourceText());
        assertEquals(new Range(6, 8, new Point(0, 6), new Point(0, 8)), x.type().range());

        var y = assertInstanceOf(VariableDeclaration.class, ast.statements().get(1));
        // The spaced form preserves the inner whitespace in both source text and range.
        assertEquals(": =", requireNotNull(y.type()).sourceText());
        assertEquals(17, y.type().range().startByte());
        assertEquals(20, y.type().range().endByte());
    }

    @Test
    void staticVariable() {
        var ast = cleanAst("static var x\n");
        var variable = assertInstanceOf(VariableDeclaration.class, onlyStatement(ast));
        assertTrue(variable.isStatic());
        assertEquals(new Range(0, 12, new Point(0, 0), new Point(0, 12)), variable.range());
    }

    @Test
    void staticWithoutFuncOrVarIsReported() {
        var errors = errorMessages(parse("static const A = 1\n"));
        assertTrue(errors.contains("Expected \"func\" or \"var\" after \"static\"."), errors::toString);
    }

    @Test
    void constRequiresInitializer() {
        var errors = errorMessages(parse("const A\n"));
        assertTrue(errors.contains("Expected initializer after constant name."), errors::toString);
    }

    @Test
    void constInferredForm() {
        var ast = cleanAst("const A := 1\n");
        var constant = assertInstanceOf(VariableDeclaration.class, onlyStatement(ast));
        assertEquals(DeclarationKind.CONST, constant.kind());
        assertEquals(":=", requireNotNull(constant.type()).sourceText());
        assertEquals("const_statement", constant.sourceNodeType());
    }

    @Test
    void variableMissingName() {
        var errors = errorMessages(parse("var = 1\n"));
        assertTrue(errors.contains("Expected variable name after \"var\"."), errors::toString);
    }

    @Test
    void variableMissingInitializerExpression() {
        var errors = errorMessages(parse("var x = \n"));
        assertTrue(errors.contains("Expected expression for variable initial value after \"=\"."),
                errors::toString);
    }

    @Test
    void localVariableWithColonNewlineIsRejected() {
        // Local variables cannot have property blocks, so a newline right after `:` is an error.
        var errors = errorMessages(parse("func f():\n\tvar x:\n"));
        assertTrue(errors.contains("Expected type after \":\""), errors::toString);
    }

    @Test
    void multibyteContentUsesBytePositions() {
        var ast = cleanAst("const A = \"中文\"\n");
        var constant = assertInstanceOf(VariableDeclaration.class, onlyStatement(ast));
        var value = assertInstanceOf(LiteralExpression.class, constant.value());
        // "中文" is 6 UTF-8 bytes; ranges and columns are byte-based.
        assertEquals(new Range(10, 18, new Point(0, 10), new Point(0, 18)), value.range());
        assertEquals("\"中文\"", value.sourceText());
    }

    // -----------------------------------------------------------------------------------------
    // Property get/set consumption (no AST, per the AST contract).
    // -----------------------------------------------------------------------------------------

    @Test
    void propertyReferenceFormIsConsumed() {
        var ast = cleanAst("var x: int: get = a, set = b\nvar y = 2\n");
        var x = assertInstanceOf(VariableDeclaration.class, ast.statements().getFirst());
        assertEquals("int", requireNotNull(x.type()).sourceText());
        assertNull(x.value());
        // The declaration range covers the whole property tail, but no accessor nodes exist.
        assertEquals(28, x.range().endByte());
        assertInstanceOf(VariableDeclaration.class, ast.statements().get(1));
    }

    @Test
    void propertyInlineBlockFormIsConsumed() {
        var ast = cleanAst("var x: int:\n\tget:\n\t\tpass\n\tset(value):\n\t\tpass\nvar y = 2\n");
        var x = assertInstanceOf(VariableDeclaration.class, ast.statements().getFirst());
        assertEquals("int", requireNotNull(x.type()).sourceText());
        assertNull(x.value());
        assertInstanceOf(VariableDeclaration.class, ast.statements().get(1));
    }

    @Test
    void propertyDuplicateGetterIsReported() {
        var errors = errorMessages(parse("var x: int: get = a, get = b\n"));
        assertTrue(errors.contains("Properties can only have one getter."), errors::toString);
    }

    @Test
    void propertyUnknownAccessorIsReported() {
        var errors = errorMessages(parse("var x: int: foo = a\n"));
        assertTrue(errors.contains("Expected \"get\" or \"set\" for property declaration."),
                errors::toString);
    }

    // -----------------------------------------------------------------------------------------
    // Signals.
    // -----------------------------------------------------------------------------------------

    @Test
    void signalForms() {
        var ast = cleanAst("signal bare\nsignal with_params(a, b: int)\n");
        var bare = assertInstanceOf(SignalStatement.class, ast.statements().getFirst());
        assertEquals("bare", bare.name());
        assertTrue(bare.parameters().isEmpty());

        var withParams = assertInstanceOf(SignalStatement.class, ast.statements().get(1));
        assertEquals(2, withParams.parameters().size());
        assertEquals("a", withParams.parameters().getFirst().name());
        assertEquals("int", requireNotNull(withParams.parameters().get(1).type()).sourceText());
        assertEquals(new Range(0, 11, new Point(0, 0), new Point(0, 11)), bare.range());
    }

    @Test
    void signalParameterCannotHaveDefaultValue() {
        var result = parse("signal s(a = 1)\n");
        assertTrue(errorMessages(result).contains("Signal parameters cannot have a default value."));
        // The parameter is still produced (Godot keeps it after reporting the error).
        var signal = assertInstanceOf(SignalStatement.class, result.ast().statements().getFirst());
        assertEquals(1, signal.parameters().size());
    }

    @Test
    void signalDuplicateParameterIsReported() {
        var result = parse("signal s(a, a)\n");
        assertTrue(errorMessages(result).contains("Parameter with name \"a\" was already declared for this signal."));
        assertEquals(1, assertInstanceOf(SignalStatement.class, result.ast().statements().getFirst())
                .parameters().size());
    }

    @Test
    void signalMissingName() {
        var errors = errorMessages(parse("signal (a)\n"));
        assertTrue(errors.contains("Expected signal name after \"signal\"."), errors::toString);
    }

    // -----------------------------------------------------------------------------------------
    // Enums.
    // -----------------------------------------------------------------------------------------

    @Test
    void enumForms() {
        var ast = cleanAst("enum {A, B = 2, C}\nenum Named {D}\n");
        var anon = assertInstanceOf(EnumDeclaration.class, ast.statements().getFirst());
        assertNull(anon.name());
        assertEquals(3, anon.members().size());
        assertEquals("A", anon.members().getFirst().name());
        assertNull(anon.members().getFirst().value());
        assertInstanceOf(LiteralExpression.class, anon.members().get(1).value());
        assertEquals(6, anon.members().getFirst().range().startByte());

        var named = assertInstanceOf(EnumDeclaration.class, ast.statements().get(1));
        assertEquals("Named", named.name());
    }

    @Test
    void enumMissingBrace() {
        var errors = errorMessages(parse("enum A\n"));
        assertTrue(errors.contains("Expected \"{\" after enum name."), errors::toString);
    }

    @Test
    void enumNonIdentifierKey() {
        var errors = errorMessages(parse("enum {1}\n"));
        assertTrue(errors.contains("Expected identifier for enum key."), errors::toString);
    }

    @Test
    void enumMissingValueExpression() {
        var errors = errorMessages(parse("enum {A = }\n"));
        assertTrue(errors.contains("Expected expression value after \"=\"."), errors::toString);
    }

    @Test
    void enumUnclosedTerminates() {
        var errors = errorMessages(parse("enum {A\n"));
        assertTrue(errors.contains("Expected closing \"}\" for enum."), errors::toString);
    }

    // -----------------------------------------------------------------------------------------
    // Functions and constructors.
    // -----------------------------------------------------------------------------------------

    @Test
    void functionSignatureForms() {
        var ast = cleanAst("func f(a, b: int, c = 1, d: String = \"x\") -> String: pass\n");
        var function = assertInstanceOf(FunctionDeclaration.class, onlyStatement(ast));
        assertEquals("f", function.name());
        assertFalse(function.isStatic());
        assertEquals(4, function.parameters().size());
        assertEquals("int", requireNotNull(function.parameters().get(1).type()).sourceText());
        assertInstanceOf(LiteralExpression.class, function.parameters().get(2).defaultValue());
        assertEquals("x", requireNotNull(assertInstanceOf(LiteralExpression.class,
                function.parameters().get(3).defaultValue())).sourceText().replace("\"", ""));
        assertEquals("String", requireNotNull(function.returnType()).sourceText());
    }

    @Test
    void functionVariadicParameter() {
        var ast = cleanAst("func f(a, ...args): pass\n");
        var function = assertInstanceOf(FunctionDeclaration.class, onlyStatement(ast));
        var variadic = function.parameters().get(1);
        assertTrue(variadic.variadic());
        assertEquals("args", variadic.name());
        // The variadic parameter range spans from the `...` marker.
        assertEquals(10, variadic.range().startByte());
        assertEquals(17, variadic.range().endByte());
    }

    @Test
    void bodylessFunctionHasEmptyBlockSpanningDeclaration() {
        var ast = cleanAst("func f()\n");
        var function = assertInstanceOf(FunctionDeclaration.class, onlyStatement(ast));
        assertTrue(function.body().statements().isEmpty());
        var declarationRange = new Range(0, 8, new Point(0, 0), new Point(0, 8));
        assertEquals(declarationRange, function.body().range());
        assertEquals(declarationRange, function.range());
    }

    @Test
    void staticFunction() {
        var ast = cleanAst("static func f(): pass\n");
        var function = assertInstanceOf(FunctionDeclaration.class, onlyStatement(ast));
        assertTrue(function.isStatic());
        assertEquals(0, function.range().startByte());
    }

    @Test
    void constructorForms() {
        var plain = cleanAst("func _init(): pass\n");
        var constructor = assertInstanceOf(ConstructorDeclaration.class, onlyStatement(plain));
        assertNull(constructor.returnType());

        var typed = cleanAst("func _init() -> void: pass\n");
        assertEquals("void", requireNotNull(assertInstanceOf(ConstructorDeclaration.class, onlyStatement(typed))
                .returnType()).sourceText());
    }

    @Test
    void staticInitIsAPlainFunctionDeclaration() {
        var ast = cleanAst("static func _static_init(): pass\n");
        var function = assertInstanceOf(FunctionDeclaration.class, onlyStatement(ast));
        assertEquals("_static_init", function.name());
    }

    @Test
    void functionMissingName() {
        var errors = errorMessages(parse("func (): pass\n"));
        assertTrue(errors.contains("Expected function name after \"func\"."), errors::toString);
    }

    @Test
    void functionMissingOpeningParen() {
        var errors = errorMessages(parse("func f: pass\n"));
        assertTrue(errors.contains("Expected opening \"(\" after function name."), errors::toString);
    }

    @Test
    void functionParameterAfterRestIsReported() {
        var errors = errorMessages(parse("func f(...a, b): pass\n"));
        assertTrue(errors.contains("Cannot have parameters after the rest parameter."), errors::toString);
    }

    @Test
    void restParameterCannotHaveDefaultValue() {
        var errors = errorMessages(parse("func f(...a = 1): pass\n"));
        assertTrue(errors.contains("The rest parameter cannot have a default value."), errors::toString);
    }

    @Test
    void mandatoryParameterAfterOptionalIsReported() {
        var errors = errorMessages(parse("func f(a = 1, b): pass\n"));
        assertTrue(errors.contains("Cannot have mandatory parameters after optional parameters."),
                errors::toString);
    }

    @Test
    void duplicateParameterIsReported() {
        var errors = errorMessages(parse("func f(a, a): pass\n"));
        assertTrue(errors.contains("Parameter with name \"a\" was already declared for this function."),
                errors::toString);
    }

    @Test
    void parameterOrderErrorsKeepParametersInList() {
        // Ordering violations are reported but the parameter keeps its position (the vendor
        // skips it; this AST's recovery contract keeps positions).
        var result = parse("func f(a: int = 1, b: int): pass\n");
        assertTrue(errorMessages(result).contains("Cannot have mandatory parameters after optional parameters."),
                () -> errorMessages(result).toString());
        var function = assertInstanceOf(FunctionDeclaration.class, result.ast().statements().getFirst());
        assertEquals(2, function.parameters().size());
        assertEquals("a", function.parameters().getFirst().name());
        assertNotNull(function.parameters().getFirst().defaultValue());
        assertEquals("b", function.parameters().get(1).name());
        assertEquals("int", requireNotNull(function.parameters().get(1).type()).sourceText());
    }

    @Test
    void restParameterWithDamagedDefaultKeepsDefaultAndRange() {
        // The variadic rebuild preserves the parsed default (a damaged binary with a MISSING
        // marker here) and spans from the `...` marker over the default.
        var result = parse("func f(...args = 1+): pass\n");
        assertTrue(errorMessages(result).contains("The rest parameter cannot have a default value."),
                () -> errorMessages(result).toString());
        var function = assertInstanceOf(FunctionDeclaration.class, result.ast().statements().getFirst());
        assertEquals(1, function.parameters().size());
        var rest = function.parameters().getFirst();
        assertTrue(rest.variadic());
        assertEquals("args", rest.name());
        assertEquals(new Range(7, 19, new Point(0, 7), new Point(0, 19)), rest.range());
        var defaultValue = assertInstanceOf(BinaryExpression.class, rest.defaultValue());
        assertInstanceOf(ErrorExpression.class, defaultValue.right());
    }

    @Test
    void restParameterWithLambdaDefaultKeepsRangeAtBodyEnd() {
        // A lambda default's trailing layout consumption must not invert the rebuilt variadic
        // parameter's range: it spans from `...` to the lambda body end.
        var result = parse("func f(...args = func(): pass\n): pass\n");
        assertTrue(errorMessages(result).contains("The rest parameter cannot have a default value."),
                () -> errorMessages(result).toString());
        var function = assertInstanceOf(FunctionDeclaration.class, result.ast().statements().getFirst());
        var rest = function.parameters().getFirst();
        assertTrue(rest.variadic());
        assertEquals(new Range(7, 29, new Point(0, 7), new Point(0, 29)), rest.range());
        assertInstanceOf(LambdaExpression.class, rest.defaultValue());
        assertEquals(1, function.body().statements().size());
    }

    @Test
    void restParameterWithParameterizedLambdaDefaultKeepsRangeAtMarkerStart() {
        // A lambda default with its own parameters rewrites the shared parameter-start field;
        // the variadic rebuild must keep the outer parameter's range (from the `...` marker),
        // or the parameter would not enclose its default value.
        var result = parse("func f(...args = func(b): pass\n): pass\n");
        assertTrue(errorMessages(result).contains("The rest parameter cannot have a default value."),
                () -> errorMessages(result).toString());
        var function = assertInstanceOf(FunctionDeclaration.class, result.ast().statements().getFirst());
        var rest = function.parameters().getFirst();
        assertTrue(rest.variadic());
        assertEquals(7, rest.range().startByte());
        var defaultValue = assertInstanceOf(LambdaExpression.class, rest.defaultValue());
        assertTrue(rest.range().startByte() <= defaultValue.range().startByte()
                        && defaultValue.range().endByte() <= rest.range().endByte(),
                () -> "rest " + rest.range() + " must enclose default " + defaultValue.range());
        assertEquals(1, defaultValue.parameters().size());
    }

    @Test
    void missingCloserInRestDefaultBindsToRebuiltParameter() {
        // The default value's owner frame migrates to the rebuilt variadic parameter: the
        // missing `)` link's owner is the final Parameter node inside the function (assertSame
        // identity semantics).
        var result = parse("func f(...args = (1: pass\n");
        var function = assertInstanceOf(FunctionDeclaration.class, result.ast().statements().getFirst());
        assertEquals(1, function.parameters().size());
        var rest = function.parameters().getFirst();
        assertTrue(rest.variadic());
        var closer = result.diagnostics().stream()
                .filter(d -> "parse.missing_closer.grouping".equals(d.code()))
                .findFirst().orElseThrow();
        assertNotNull(closer.recovery());
        assertSame(rest, closer.recovery().owner());
        assertEquals("parameter.default", closer.recovery().facet());
    }

    @Test
    void functionMissingReturnType() {
        var errors = errorMessages(parse("func f() ->: pass\n"));
        assertTrue(errors.contains("Expected return type or \"void\" after \"->\"."), errors::toString);
    }

    @Test
    void functionMissingIndentedBlock() {
        var errors = errorMessages(parse("func f():\nvar x = 1\n"));
        assertTrue(errors.contains("Expected indented block after function declaration."), errors::toString);
    }

    @Test
    void functionBodyStatements() {
        var ast = cleanAst("func f():\n\tvar x = 1\n\tpass\n");
        var function = assertInstanceOf(FunctionDeclaration.class, onlyStatement(ast));
        assertEquals(2, function.body().statements().size());
        assertInstanceOf(VariableDeclaration.class, function.body().statements().getFirst());
        assertInstanceOf(PassStatement.class, function.body().statements().get(1));
        // Block spans from the newline after the colon to the last statement end.
        assertEquals(9, function.body().range().startByte());
        assertEquals(26, function.body().range().endByte());
    }

    @Test
    void singleLineSuiteWithSemicolons() {
        var ast = cleanAst("func f(): pass; pass\n");
        var function = assertInstanceOf(FunctionDeclaration.class, onlyStatement(ast));
        assertEquals(2, function.body().statements().size());
        assertEquals(10, function.body().range().startByte());
        assertEquals(20, function.body().range().endByte());
    }

    // -----------------------------------------------------------------------------------------
    // Annotations.
    // -----------------------------------------------------------------------------------------

    @Test
    void annotationShapesAndAttachment() {
        var ast = cleanAst("@export var x = 1\n");
        assertEquals(2, ast.statements().size());
        var annotation = assertInstanceOf(AnnotationStatement.class, ast.statements().getFirst());
        assertEquals("export", annotation.name());
        assertTrue(annotation.arguments().isEmpty());
        assertEquals(new Range(0, 7, new Point(0, 0), new Point(0, 7)), annotation.range());
        // A same-line annotation is spanned by the annotated statement's range.
        var variable = assertInstanceOf(VariableDeclaration.class, ast.statements().get(1));
        assertEquals(0, variable.range().startByte());
    }

    @Test
    void ownLineAnnotationIsNotSpannedByStatement() {
        var ast = cleanAst("@export\nvar x = 1\n");
        var variable = assertInstanceOf(VariableDeclaration.class, ast.statements().get(1));
        assertEquals(8, variable.range().startByte());
    }

    @Test
    void multipleSameLineAnnotationsExtendStatementStart() {
        var ast = cleanAst("@export @rpc var x = 1\n");
        assertEquals(3, ast.statements().size());
        var variable = assertInstanceOf(VariableDeclaration.class, ast.statements().get(2));
        assertEquals(0, variable.range().startByte());
    }

    @Test
    void annotationArguments() {
        var ast = cleanAst("@rpc(\"any_peer\", \"call_local\",)\nfunc f(): pass\n");
        var annotation = assertInstanceOf(AnnotationStatement.class, ast.statements().getFirst());
        assertEquals("rpc", annotation.name());
        assertEquals(2, annotation.arguments().size());
        assertEquals(31, annotation.range().endByte());
    }

    @Test
    void scriptLevelAnnotationPrecedesClassName() {
        var ast = cleanAst("@tool\nclass_name Foo\n");
        assertInstanceOf(AnnotationStatement.class, ast.statements().getFirst());
        assertInstanceOf(ClassNameStatement.class, ast.statements().get(1));
    }

    @Test
    void unclosedAnnotationArgumentsRecover() {
        var result = parse("@export(1\nvar x = 2\n");
        assertTrue(errorMessages(result).contains("Expected \")\" after annotation arguments."));
        // The following member still parses after recovery.
        assertTrue(result.ast().statements().stream().anyMatch(VariableDeclaration.class::isInstance));
    }

    @Test
    void emptyAnnotationArgumentIsReported() {
        var errors = errorMessages(parse("@a(,)\nvar x = 1\n"));
        assertTrue(errors.contains("Expected expression as the annotation argument."), errors::toString);
    }

    // -----------------------------------------------------------------------------------------
    // Types.
    // -----------------------------------------------------------------------------------------

    @Test
    void typeRefShapes() {
        var ast = cleanAst("var a: Array[int]\nvar b: Dictionary[String, int]\nvar c: Foo.Bar\n");
        var a = assertInstanceOf(VariableDeclaration.class, ast.statements().getFirst());
        assertEquals("Array[int]", requireNotNull(a.type()).sourceText());
        assertEquals(new Range(7, 17, new Point(0, 7), new Point(0, 17)), a.type().range());

        var b = assertInstanceOf(VariableDeclaration.class, ast.statements().get(1));
        // Container type text keeps internal whitespace.
        assertEquals("Dictionary[String, int]", requireNotNull(b.type()).sourceText());

        assertEquals("Foo.Bar", requireNotNull(assertInstanceOf(VariableDeclaration.class, ast.statements().get(2))
                .type()).sourceText());
    }

    @Test
    void nestedContainerTypesAreRejected() {
        var errors = errorMessages(parse("var x: Array[Array[int]]\n"));
        assertTrue(errors.contains("Nested typed collections are not supported."), errors::toString);
    }

    @Test
    void missingContainerElementType() {
        var errors = errorMessages(parse("var x: Array[]\n"));
        assertTrue(errors.contains("Expected type for collection after \"[\"."), errors::toString);
    }

    @Test
    void missingContainerCloseBracket() {
        var errors = errorMessages(parse("var x: Array[int\n"));
        assertTrue(errors.contains("Expected closing \"]\" after collection type."), errors::toString);
    }

    @Test
    void missingInnerTypeAfterPeriod() {
        var errors = errorMessages(parse("var x: Foo.\n"));
        assertTrue(errors.contains("Expected inner type name after \".\"."), errors::toString);
    }

    @Test
    void voidIsNotAVariableType() {
        var errors = errorMessages(parse("var x: void\n"));
        assertTrue(errors.contains("\"void\" is only allowed for a function return type."), errors::toString);
    }

    // -----------------------------------------------------------------------------------------
    // Expression subset shapes (declaration contexts).
    // -----------------------------------------------------------------------------------------

    @Test
    void unaryMinusShape() {
        var ast = cleanAst("var x = -1\n");
        var variable = assertInstanceOf(VariableDeclaration.class, onlyStatement(ast));
        var unary = assertInstanceOf(UnaryExpression.class, variable.value());
        assertEquals("-", unary.operator());
        assertInstanceOf(LiteralExpression.class, unary.operand());
        assertEquals(new Range(8, 10, new Point(0, 8), new Point(0, 10)), unary.range());
    }

    @Test
    void binaryPrecedenceShape() {
        var ast = cleanAst("const A = 1 + 2 * 3\n");
        var constant = assertInstanceOf(VariableDeclaration.class, onlyStatement(ast));
        var plus = assertInstanceOf(BinaryExpression.class, constant.value());
        assertEquals("+", plus.operator());
        assertInstanceOf(LiteralExpression.class, plus.left());
        var times = assertInstanceOf(BinaryExpression.class, plus.right());
        assertEquals("*", times.operator());
    }

    @Test
    void powerIsLeftAssociativeLikeGodot() {
        var ast = cleanAst("const A = 2 ** 3 ** 2\n");
        var constant = assertInstanceOf(VariableDeclaration.class, onlyStatement(ast));
        var outer = assertInstanceOf(BinaryExpression.class, constant.value());
        assertEquals("**", outer.operator());
        assertInstanceOf(BinaryExpression.class, outer.left());
        assertInstanceOf(LiteralExpression.class, outer.right());
    }

    @Test
    void unarySignBindsWeakerThanPowerLikeGodot() {
        // Godot anchors `-2 ** 2` as `-(2 ** 2)` (PREC_SIGN below PREC_POWER); the tree-sitter
        // path produces `(-2) ** 2` (registered precedence divergence).
        var ast = cleanAst("const A = -2 ** 2\n");
        var constant = assertInstanceOf(VariableDeclaration.class, onlyStatement(ast));
        var unary = assertInstanceOf(UnaryExpression.class, constant.value());
        assertEquals("-", unary.operator());
        var power = assertInstanceOf(BinaryExpression.class, unary.operand());
        assertEquals("**", power.operator());
    }

    @Test
    void suffixChainFolding() {
        var ast = cleanAst("const A = obj.a.b(1).c[2]\n");
        var constant = assertInstanceOf(VariableDeclaration.class, onlyStatement(ast));
        var attribute = assertInstanceOf(AttributeExpression.class, constant.value());
        assertEquals("obj", assertInstanceOf(IdentifierExpression.class, attribute.base()).name());
        assertEquals(3, attribute.steps().size());
        assertInstanceOf(AttributePropertyStep.class, attribute.steps().getFirst());
        assertInstanceOf(AttributeCallStep.class, attribute.steps().get(1));
        assertInstanceOf(AttributeSubscriptStep.class, attribute.steps().get(2));
    }

    @Test
    void builtinConstantsAreIdentifiers() {
        var ast = cleanAst("const A = PI\nconst B = TAU\nconst C = INF\nconst D = NAN\n");
        for (var statement : ast.statements()) {
            var constant = assertInstanceOf(VariableDeclaration.class, statement);
            assertInstanceOf(IdentifierExpression.class, constant.value());
        }
    }

    @Test
    void selfIsASelfExpression() {
        var ast = cleanAst("const A = self\n");
        var constant = assertInstanceOf(VariableDeclaration.class, onlyStatement(ast));
        assertInstanceOf(SelfExpression.class, constant.value());
    }

    @Test
    void expressionStatementsInSuites() {
        var ast = cleanAst("func f():\n\tfoo(1)\n");
        var function = assertInstanceOf(FunctionDeclaration.class, onlyStatement(ast));
        var statement = assertInstanceOf(ExpressionStatement.class, function.body().statements().getFirst());
        assertEquals(11, statement.range().startByte());
    }

    // -----------------------------------------------------------------------------------------
    // Expression forms: grouping, arrays, dictionaries.
    // -----------------------------------------------------------------------------------------

    @Test
    void groupingShapeAndErrors() {
        var ast = cleanAst("const A = (1 + 2) * 3\n");
        var constant = assertInstanceOf(VariableDeclaration.class, onlyStatement(ast));
        var times = assertInstanceOf(BinaryExpression.class, constant.value());
        assertEquals("*", times.operator());
        // The grouping produces the inner expression itself (no wrapper node); the enclosing
        // node's range still spans the consumed parentheses (tree-sitter-path range contract).
        assertEquals("+", assertInstanceOf(BinaryExpression.class, times.left()).operator());
        assertEquals(new Range(10, 21, new Point(0, 10), new Point(0, 21)), times.range());
        assertEquals(new Range(11, 16, new Point(0, 11), new Point(0, 16)), times.left().range());

        assertTrue(errorMessages(parse("var x = ()\n")).contains("Expected grouping expression."));
        assertTrue(errorMessages(parse("var y = (1\n")).contains("Expected closing \")\" after grouping expression."));
    }

    @Test
    void unclosedGroupingConstructsAtEofTerminate() {
        // EOF directly after an unclosed grouping token: multiline push eats the trailing
        // newline, the prefix fails at EOF — must report, pop multiline and terminate.
        assertTrue(errorMessages(parse("var x = (\n")).contains("Expected grouping expression."));
        assertTrue(errorMessages(parse("var x = {\n")).contains("Expected expression as dictionary key."));
        assertTrue(errorMessages(parse("func f():\n\tfoo(\n"))
                .contains("Expected expression as the function argument."));
    }

    @Test
    void callArgumentRecovery() {
        // An empty argument slot reports and recovers at the comma; a missing comma inserts a
        // zero-width MISSING_SEPARATOR_COMMA diagnostic and preserves subsequent arguments.
        var empty = parse("func f():\n\tfoo(,)\n");
        assertTrue(errorMessages(empty).contains("Expected expression as the function argument."));
        var missingComma = parse("func f():\n\tfoo(1 2)\n");
        assertEquals(List.of("Expected \",\" between call arguments."), errorMessages(missingComma),
                () -> errorMessages(missingComma).toString());
        var function = assertInstanceOf(FunctionDeclaration.class, missingComma.ast().statements().getFirst());
        var call = assertInstanceOf(CallExpression.class,
                assertInstanceOf(ExpressionStatement.class, function.body().statements().getFirst()).expression());
        assertEquals(2, call.arguments().size());
        assertEquals("1", assertInstanceOf(LiteralExpression.class, call.arguments().getFirst()).sourceText());
        assertEquals("2", assertInstanceOf(LiteralExpression.class, call.arguments().get(1)).sourceText());
    }

    @Test
    void arrayLiteralShapes() {
        var ast = cleanAst("const A = [1, 2, 3]\nconst B = []\nconst C = [[1], [2]]\n");
        var a = requireNotNull(assertInstanceOf(ArrayExpression.class,
                assertInstanceOf(VariableDeclaration.class, ast.statements().getFirst()).value()));
        assertEquals(3, a.elements().size());
        assertFalse(a.openEnded());
        assertEquals("1", assertInstanceOf(LiteralExpression.class, a.elements().getFirst()).sourceText());
        assertEquals(new Range(10, 19, new Point(0, 10), new Point(0, 19)), a.range());
        var b = requireNotNull(assertInstanceOf(ArrayExpression.class,
                assertInstanceOf(VariableDeclaration.class, ast.statements().get(1)).value()));
        assertEquals(0, b.elements().size());
        var c = requireNotNull(assertInstanceOf(ArrayExpression.class,
                assertInstanceOf(VariableDeclaration.class, ast.statements().get(2)).value()));
        assertEquals(2, c.elements().size());
        assertInstanceOf(ArrayExpression.class, c.elements().getFirst());
    }

    @Test
    void arrayTrailingCommaAndMultiline() {
        var ast = cleanAst("const A = [1, 2,]\nconst B = [\n\t1,\n\t2,\n]\n");
        var a = requireNotNull(assertInstanceOf(ArrayExpression.class,
                assertInstanceOf(VariableDeclaration.class, ast.statements().getFirst()).value()));
        assertEquals(2, a.elements().size());
        var b = requireNotNull(assertInstanceOf(ArrayExpression.class,
                assertInstanceOf(VariableDeclaration.class, ast.statements().get(1)).value()));
        assertEquals(2, b.elements().size());
    }

    @Test
    void arrayRecovery() {
        // A failed element is reported and its slot keeps a zero-width MISSING marker, so the
        // surviving elements keep their positions (the AST analog of skipping, position-aware).
        var missing = parse("var x = [1, , 2]\n");
        assertTrue(errorMessages(missing).contains("Expected expression as array element."));
        var array = assertInstanceOf(ArrayExpression.class,
                assertInstanceOf(VariableDeclaration.class, missing.ast().statements().getFirst()).value());
        assertEquals(3, array.elements().size());
        assertEquals("1", assertInstanceOf(LiteralExpression.class, array.elements().getFirst()).sourceText());
        var marker = assertInstanceOf(ErrorExpression.class, array.elements().get(1));
        assertEquals(CstIssueKind.MISSING, marker.kind());
        assertEquals("array.element", marker.nodeType());
        assertEquals("2", assertInstanceOf(LiteralExpression.class, array.elements().get(2)).sourceText());

        assertTrue(errorMessages(parse("var y = [1, 2\n"))
                .contains("Expected closing \"]\" after array elements."));
    }

    @Test
    void pythonDictionaryShape() {
        var ast = cleanAst("const D = {\"a\": 1, b: 2}\n");
        var constant = assertInstanceOf(VariableDeclaration.class, onlyStatement(ast));
        var dictionary = assertInstanceOf(DictionaryExpression.class, constant.value());
        assertFalse(dictionary.openEnded());
        assertEquals(2, dictionary.entries().size());
        var first = dictionary.entries().getFirst();
        assertEquals("a", assertInstanceOf(LiteralExpression.class, first.key()).sourceText().replace("\"", ""));
        // An unquoted Python-style key stays a plain identifier expression.
        assertInstanceOf(IdentifierExpression.class, dictionary.entries().get(1).key());
        // Entry ranges span key start through value end; the dictionary spans the braces.
        assertEquals(new Range(11, 17, new Point(0, 11), new Point(0, 17)), first.range());
        assertEquals(new Range(10, 24, new Point(0, 10), new Point(0, 24)), dictionary.range());
    }

    @Test
    void luaDictionaryKeyNormalization() {
        var ast = cleanAst("const A = {x = 1}\nconst B = {\"name\" = 2}\n");
        var a = assertInstanceOf(DictionaryExpression.class,
                assertInstanceOf(VariableDeclaration.class, ast.statements().getFirst()).value());
        var aKey = assertInstanceOf(LiteralExpression.class, a.entries().getFirst().key());
        // Lua-style identifier keys normalize to synthesized StringName literals.
        assertEquals("string_name", aKey.kind());
        assertEquals("&\"x\"", aKey.sourceText());
        var b = requireNotNull(assertInstanceOf(DictionaryExpression.class,
                assertInstanceOf(VariableDeclaration.class, ast.statements().get(1)).value()));
        var bKey = assertInstanceOf(LiteralExpression.class, b.entries().getFirst().key());
        assertEquals("string_name", bKey.kind());
        assertEquals("&\"name\"", bKey.sourceText());
    }

    @Test
    void mixedDictionaryStylesAreRejected() {
        // Style-conflict separators are consumed only once; the actual following value token is
        // still parsed rather than skipped by a redundant advance().
        var pythonFirst = parse("var d = {\"a\": 1, b = 2}\n");
        assertEquals(List.of("Expected \":\" after dictionary key. Mixing dictionary styles is not allowed."),
                errorMessages(pythonFirst), () -> errorMessages(pythonFirst).toString());
        var dict = assertInstanceOf(DictionaryExpression.class,
                assertInstanceOf(VariableDeclaration.class, pythonFirst.ast().statements().getFirst()).value());
        assertEquals(2, dict.entries().size());
        assertEquals("2", assertInstanceOf(LiteralExpression.class, dict.entries().get(1).value()).sourceText());

        var pythonMissingValue = parse("var dEmpty = {\"a\": 1, b = }\n");
        assertEquals(List.of(
                "Expected \":\" after dictionary key. Mixing dictionary styles is not allowed.",
                "Expected expression as dictionary value."
        ), errorMessages(pythonMissingValue), () -> errorMessages(pythonMissingValue).toString());
        var dictEmpty = assertInstanceOf(DictionaryExpression.class,
                assertInstanceOf(VariableDeclaration.class, pythonMissingValue.ast().statements().getFirst()).value());
        assertEquals(2, dictEmpty.entries().size());
        var marker = assertInstanceOf(ErrorExpression.class, dictEmpty.entries().get(1).value());
        assertEquals(CstIssueKind.MISSING, marker.kind());
        assertEquals("dictionary.value", marker.nodeType());

        var luaFirst = parse("var e = {a = 1, \"b\": 2}\n");
        assertEquals(List.of("Expected \"=\" after dictionary key. Mixing dictionary styles is not allowed."),
                errorMessages(luaFirst), () -> errorMessages(luaFirst).toString());
        var luaDict = assertInstanceOf(DictionaryExpression.class,
                assertInstanceOf(VariableDeclaration.class, luaFirst.ast().statements().getFirst()).value());
        assertEquals(2, luaDict.entries().size());
        assertEquals("2", assertInstanceOf(LiteralExpression.class, luaDict.entries().get(1).value()).sourceText());
    }

    @Test
    void dictionaryRecovery() {
        // A key-only entry emits a single separator diagnostic and fills the value slot with a
        // zero-width MISSING marker without emitting duplicate missing-value noise.
        var keyOnly = parse("var d = {name}\n");
        var keyOnlyErrors = keyOnly.diagnostics().stream()
                .filter(d -> d.severity() == AstDiagnosticSeverity.ERROR)
                .toList();
        assertEquals(1, keyOnlyErrors.size(), () -> keyOnlyErrors.toString());
        assertEquals(DiagnosticCodes.MISSING_SEPARATOR_DICTIONARY, keyOnlyErrors.getFirst().code());
        assertEquals("Expected \":\" or \"=\" after dictionary key.", keyOnlyErrors.getFirst().message());
        var keyOnlyDict = assertInstanceOf(DictionaryExpression.class,
                assertInstanceOf(VariableDeclaration.class, keyOnly.ast().statements().getFirst()).value());
        assertEquals(1, keyOnlyDict.entries().size());
        assertInstanceOf(IdentifierExpression.class, keyOnlyDict.entries().getFirst().key());
        var valueMarker = assertInstanceOf(ErrorExpression.class, keyOnlyDict.entries().getFirst().value());
        assertEquals(CstIssueKind.MISSING, valueMarker.kind());
        assertEquals("dictionary.value", valueMarker.nodeType());

        var missingValue = parse("var e = {\"a\": }\n");
        assertTrue(errorMessages(missingValue).contains("Expected expression as dictionary value."));
        assertTrue(errorMessages(parse("var f = {\"a\": 1\n"))
                .contains("Expected closing \"}\" after dictionary elements."));
    }

    @Test
    void dictionaryMissingValueEntrySpansGroupedKey() {
        // Recovery branch range: the entry spans the key's whole consumed span, including a
        // grouping's closing parenthesis.
        var result = parse("var d = {(a)}\n");
        assertEquals(List.of("Expected \":\" or \"=\" after dictionary key."), errorMessages(result),
                () -> errorMessages(result).toString());
        var dict = assertInstanceOf(DictionaryExpression.class,
                assertInstanceOf(VariableDeclaration.class, result.ast().statements().getFirst()).value());
        assertEquals(new Range(9, 12, new Point(0, 9), new Point(0, 12)), dict.entries().getFirst().range());
    }

    @Test
    void dictionaryMissingSeparatorKeepsKeyAndValue() {
        // `{1 2}` reports the missing separator once while still parsing the clear same-line
        // expression starter `2` as the entry value.
        var result = parse("var d = {1 2}\n");
        assertEquals(List.of("Expected \":\" or \"=\" after dictionary key."), errorMessages(result),
                () -> errorMessages(result).toString());
        var dict = assertInstanceOf(DictionaryExpression.class,
                assertInstanceOf(VariableDeclaration.class, result.ast().statements().getFirst()).value());
        assertEquals(1, dict.entries().size());
        assertEquals("1", assertInstanceOf(LiteralExpression.class, dict.entries().getFirst().key()).sourceText());
        assertEquals("2", assertInstanceOf(LiteralExpression.class, dict.entries().getFirst().value()).sourceText());
    }

    @Test
    void luaDictionaryRejectsNonNameKeys() {
        var result = parse("var d = {1 = \"x\"}\n");
        assertTrue(errorMessages(result)
                .contains("Expected identifier or string as Lua-style dictionary key (e.g \"{ key = value }\")."));
        // The pair is still kept (Godot appends it despite the key-type error).
        var dict = assertInstanceOf(DictionaryExpression.class,
                assertInstanceOf(VariableDeclaration.class, result.ast().statements().getFirst()).value());
        assertEquals(1, dict.entries().size());
    }

    // -----------------------------------------------------------------------------------------
    // Expression forms: get_node, preload, await, super.
    // -----------------------------------------------------------------------------------------

    @Test
    void getNodeShapes() {
        var ast = cleanAst(
                "var a = $Child\nvar b = $\"a/b\"\nvar c = $/root\nvar d = $A/B\nvar e = %Unique\n");
        var expected = List.of("$Child", "$\"a/b\"", "$/root", "$A/B", "%Unique");
        for (var i = 0; i < expected.size(); i++) {
            var variable = assertInstanceOf(VariableDeclaration.class, ast.statements().get(i));
            var getNode = assertInstanceOf(GetNodeExpression.class, variable.value());
            // The raw source slice (including the leading `$`/`%`) is stored verbatim.
            assertEquals(expected.get(i), getNode.sourceText());
            assertEquals(variable.value().range().startByte(), getNode.range().startByte());
        }
    }

    @Test
    void getNodeGodotOnlyForms() {
        // A registered divergence: legal in Godot, rejected by the tree-sitter path — no parity, Godot shapes.
        var ast = cleanAst("var a = $%Child\nvar b = $Root/%Child\n");
        assertEquals("$%Child", requireNotNull(assertInstanceOf(GetNodeExpression.class,
                assertInstanceOf(VariableDeclaration.class, ast.statements().getFirst()).value())).sourceText());
        assertEquals("$Root/%Child", requireNotNull(assertInstanceOf(GetNodeExpression.class,
                assertInstanceOf(VariableDeclaration.class, ast.statements().get(1)).value())).sourceText());
    }

    @Test
    void getNodeErrors() {
        assertTrue(errorMessages(parse("var a = $1\n"))
                .contains("Expected node path as string or identifier after \"$\"."));
        assertTrue(errorMessages(parse("var b = $a/1\n"))
                .contains("Expected node path as string or identifier after \"/\"."));
        assertTrue(errorMessages(parse("var c = $a%b\n"))
                .contains("\"%\" is only valid in the beginning of a node name (either after \"$\" or after \"/\")"));
        assertTrue(errorMessages(parse("var d = $a//b\n"))
                .contains("\"/\" is only valid at the beginning of the path or after a node name."));
    }

    @Test
    void preloadShapes() {
        var ast = cleanAst("const P = preload(\"res://a.gd\")\nconst Q = preload(path_var)\n");
        var p = assertInstanceOf(PreloadExpression.class,
                assertInstanceOf(VariableDeclaration.class, ast.statements().getFirst()).value());
        assertEquals("\"res://a.gd\"", assertInstanceOf(LiteralExpression.class, p.path()).sourceText());
        assertEquals(new Range(10, 31, new Point(0, 10), new Point(0, 31)), p.range());
        // The path is a general expression; constant/resource checks are cut (later Godot phases).
        assertInstanceOf(IdentifierExpression.class,
                requireNotNull(assertInstanceOf(PreloadExpression.class,
                        assertInstanceOf(VariableDeclaration.class, ast.statements().get(1)).value())).path());
        // One trailing comma is allowed.
        assertInstanceOf(PreloadExpression.class,
                assertInstanceOf(VariableDeclaration.class, cleanAst("const R = preload(\"a\",)\n")
                        .statements().getFirst()).value());
    }

    @Test
    void preloadErrors() {
        assertTrue(errorMessages(parse("var a = preload()\n")).contains("Expected resource path after \"(\"."));
        assertTrue(errorMessages(parse("var b = preload\n")).contains("Expected \"(\" after \"preload\"."));
        assertTrue(errorMessages(parse("var c = preload(\"a\"\n")).contains("Expected \")\" after preload path."));
    }

    @Test
    void awaitShapes() {
        var ast = cleanAst("func f():\n\tawait foo()\n");
        var function = assertInstanceOf(FunctionDeclaration.class, onlyStatement(ast));
        var statement = assertInstanceOf(ExpressionStatement.class, function.body().statements().getFirst());
        var await = assertInstanceOf(AwaitExpression.class, statement.expression());
        assertInstanceOf(CallExpression.class, await.value());

        // Suffixes bind into the awaited operand (PREC_AWAIT): `await timer.timeout`.
        var chained = cleanAst("func f():\n\tawait get_tree().create_timer(1).timeout\n");
        var chainedAwait = assertInstanceOf(AwaitExpression.class,
                assertInstanceOf(ExpressionStatement.class,
                        assertInstanceOf(FunctionDeclaration.class, onlyStatement(chained))
                                .body().statements().getFirst()).expression());
        assertInstanceOf(AttributeExpression.class, chainedAwait.value());
    }

    @Test
    void awaitOperandStopsBelowAwaitPrecedence() {
        // `+` (PREC_ADDITION_SUBTRACTION) is below PREC_AWAIT, so the binary wraps the await.
        var ast = cleanAst("func f():\n\tawait 1 + 2\n");
        var function = assertInstanceOf(FunctionDeclaration.class, onlyStatement(ast));
        var binary = assertInstanceOf(BinaryExpression.class,
                assertInstanceOf(ExpressionStatement.class, function.body().statements().getFirst()).expression());
        assertEquals("+", binary.operator());
        assertInstanceOf(AwaitExpression.class, binary.left());
    }

    @Test
    void awaitMissingOperand() {
        assertTrue(errorMessages(parse("func f():\n\tawait\n"))
                .contains("Expected signal or coroutine after \"await\"."));
    }

    @Test
    void superCallShapes() {
        var ast = cleanAst("func f():\n\tsuper()\n\tsuper.foo(1)\n");
        var function = assertInstanceOf(FunctionDeclaration.class, onlyStatement(ast));
        var implicit = assertInstanceOf(CallExpression.class,
                assertInstanceOf(ExpressionStatement.class, function.body().statements().getFirst()).expression());
        assertEquals("super", assertInstanceOf(IdentifierExpression.class, implicit.callee()).name());
        assertEquals(0, implicit.arguments().size());
        var explicit = assertInstanceOf(AttributeExpression.class,
                assertInstanceOf(ExpressionStatement.class, function.body().statements().get(1)).expression());
        assertEquals("super", assertInstanceOf(IdentifierExpression.class, explicit.base()).name());
        assertEquals(1, explicit.steps().size());
        var step = assertInstanceOf(AttributeCallStep.class, explicit.steps().getFirst());
        assertEquals("foo", step.name());
        assertEquals(1, step.arguments().size());
    }

    @Test
    void superErrors() {
        assertTrue(errorMessages(parse("func f():\n\tsuper\n"))
                .contains("Expected \".\" or \"(\" after \"super\"."));
        // The tokenizer lexes `.1` as a float literal, so no `.` token exists here and the
        // failure is the missing-access branch (a single diagnostic per the recovery policy).
        assertTrue(errorMessages(parse("func f():\n\tsuper.1\n"))
                .contains("Expected \".\" or \"(\" after \"super\"."));
        assertTrue(errorMessages(parse("func f():\n\tsuper.foo\n"))
                .contains("Expected \"(\" after function name."));
        // Ordinary keywords outside the `is_identifier()` set are still rejected.
        assertTrue(errorMessages(parse("func f():\n\tsuper.if\n"))
                .contains("Expected function name after \".\"."));
    }

    @Test
    void superMethodNameAcceptsIdentifierLikeTokens() {
        // Vendor `consume(IDENTIFIER)` accepts the `is_identifier()` set (match/when/builtin
        // constants), not just plain IDENTIFIER tokens.
        var ast = cleanAst("func f():\n\tsuper.match()\n\tsuper.when()\n");
        var function = assertInstanceOf(FunctionDeclaration.class, onlyStatement(ast));
        var names = List.of("match", "when");
        for (var i = 0; i < names.size(); i++) {
            var attribute = assertInstanceOf(AttributeExpression.class,
                    assertInstanceOf(ExpressionStatement.class, function.body().statements().get(i)).expression());
            assertEquals(names.get(i),
                    assertInstanceOf(AttributeCallStep.class, attribute.steps().getFirst()).name());
        }
    }

    @Test
    void superPrefixShapesSurviveAtStatementEnd() {
        // `super.` keeps the receiver with a MissingAttributeStep; `super.ba` keeps the known
        // name as a property step. Both survive at statement boundaries (the receiver stays
        // visible for completion).
        var result = parse("func f():\n\tsuper.\n\tsuper.ba\n");
        assertEquals(List.of("Expected function name after \".\".", "Expected \"(\" after function name."),
                errorMessages(result), () -> errorMessages(result).toString());
        var body = assertInstanceOf(FunctionDeclaration.class, result.ast().statements().getFirst())
                .body().statements();
        assertEquals(2, body.size());
        var missingName = assertInstanceOf(AttributeExpression.class,
                assertInstanceOf(ExpressionStatement.class, body.getFirst()).expression());
        assertEquals("super", assertInstanceOf(IdentifierExpression.class, missingName.base()).name());
        assertInstanceOf(MissingAttributeStep.class, missingName.steps().getFirst());
        var missingCall = assertInstanceOf(AttributeExpression.class,
                assertInstanceOf(ExpressionStatement.class, body.get(1)).expression());
        assertEquals("ba", assertInstanceOf(AttributePropertyStep.class, missingCall.steps().getFirst()).name());
    }

    @Test
    void damagedSuperPrefixDiscardsStatementOffBoundary() {
        // `super. var y = 1`: the damaged chain stays retained in its ExpressionStatement while
        // `endStatement` consumes same-line trailing tokens without turning `var y = 1` into a
        // valid sibling declaration.
        var sameLine = parse("func f():\n\tsuper. var y = 1\n");
        assertEquals(List.of(
                "Expected function name after \".\".",
                "Expected end of statement after expression, found \"var\" instead."
        ), errorMessages(sameLine), () -> errorMessages(sameLine).toString());
        var sameLineBody = assertInstanceOf(FunctionDeclaration.class, sameLine.ast().statements().getFirst())
                .body().statements();
        assertEquals(1, sameLineBody.size());
        var superStmt = assertInstanceOf(ExpressionStatement.class, sameLineBody.getFirst());
        assertInstanceOf(MissingAttributeStep.class,
                assertInstanceOf(AttributeExpression.class, superStmt.expression()).steps().getFirst());

        // `super.ba + 1`: the damaged chain continues folding `+ 1` and retains the statement.
        var trailing = parse("func f():\n\tsuper.ba + 1\n");
        assertEquals(List.of("Expected \"(\" after function name."), errorMessages(trailing),
                () -> errorMessages(trailing).toString());
        var trailingBody = assertInstanceOf(FunctionDeclaration.class, trailing.ast().statements().getFirst())
                .body().statements();
        assertEquals(1, trailingBody.size());
        var binary = assertInstanceOf(BinaryExpression.class,
                assertInstanceOf(ExpressionStatement.class, trailingBody.getFirst()).expression());
        assertEquals("+", binary.operator());
        assertInstanceOf(AttributeExpression.class, binary.left());
        assertEquals("1", assertInstanceOf(LiteralExpression.class, binary.right()).sourceText());
    }

    @Test
    void damagedSuperInsideLambdaArgumentStopsFold() {
        // `f(func(): super. + 1)`: the damaged `super.` continues folding `+ 1` inside the
        // lambda body while preserving the closing `)` and the outer call statement.
        var result = parse("func f():\n\tf(func(): super. + 1)\n");
        assertEquals(List.of("Expected function name after \".\"."), errorMessages(result),
                () -> errorMessages(result).toString());
        var body = assertInstanceOf(FunctionDeclaration.class, result.ast().statements().getFirst())
                .body().statements();
        assertEquals(1, body.size());
        var call = assertInstanceOf(CallExpression.class,
                assertInstanceOf(ExpressionStatement.class, body.getFirst()).expression());
        var lambda = assertInstanceOf(LambdaExpression.class, call.arguments().getFirst());
        assertEquals(1, lambda.body().statements().size());
        var folded = assertInstanceOf(BinaryExpression.class,
                assertInstanceOf(ExpressionStatement.class, lambda.body().statements().getFirst()).expression());
        assertEquals("+", folded.operator());
        assertInstanceOf(AttributeExpression.class, folded.left());

        // `f(func(): super., 1)`: the outer `,` is an ancestor-owned separator, so synchronization
        // ends the inline lambda at `,` and preserves both the damaged `super.` statement and `1`.
        var comma = parse("func f():\n\tf(func(): super., 1)\n");
        assertEquals(List.of("Expected function name after \".\"."), errorMessages(comma),
                () -> errorMessages(comma).toString());
        var commaCall = assertInstanceOf(CallExpression.class,
                assertInstanceOf(ExpressionStatement.class, assertInstanceOf(FunctionDeclaration.class,
                        comma.ast().statements().getFirst()).body().statements().getFirst()).expression());
        assertEquals(2, commaCall.arguments().size());
        var commaLambda = assertInstanceOf(LambdaExpression.class, commaCall.arguments().getFirst());
        assertEquals(1, commaLambda.body().statements().size());
        assertEquals("1", assertInstanceOf(LiteralExpression.class, commaCall.arguments().get(1)).sourceText());
    }

    @Test
    void damagedSuperWithSemicolonStaysInDeclaringScope() {
        // Registered intentional delta: `super.;` survives inside the lambda body at the real
        // boundary (`;`), and endStatement's run consumption keeps the following sibling in the
        // declaring scope (the lambda body) rather than dropping it to outer recovery.
        var result = parse("func f():\n\tvar f = func():\n\t\tsuper.;\n\tvar y = 1\n");
        assertEquals(List.of("Expected function name after \".\"."), errorMessages(result),
                () -> errorMessages(result).toString());
        var body = assertInstanceOf(FunctionDeclaration.class, result.ast().statements().getFirst())
                .body().statements();
        assertEquals(2, body.size());
        var f = assertInstanceOf(VariableDeclaration.class, body.getFirst());
        var lambda = assertInstanceOf(LambdaExpression.class, f.value());
        assertEquals(1, lambda.body().statements().size());
        var superStatement = assertInstanceOf(ExpressionStatement.class,
                lambda.body().statements().getFirst());
        assertInstanceOf(MissingAttributeStep.class, assertInstanceOf(AttributeExpression.class,
                superStatement.expression()).steps().getFirst());
        assertEquals("y", assertInstanceOf(VariableDeclaration.class, body.get(1)).name());
    }

    // -----------------------------------------------------------------------------------------
    // Expression forms: as/is, ternary, not-in, unary family, assignments.
    // -----------------------------------------------------------------------------------------

    @Test
    void castShapes() {
        var ast = cleanAst("const A = x as int\nconst B = y as Array[int]\n");
        var cast = requireNotNull(assertInstanceOf(CastExpression.class,
                assertInstanceOf(VariableDeclaration.class, ast.statements().getFirst()).value()));
        assertInstanceOf(IdentifierExpression.class, cast.value());
        assertEquals("int", cast.targetType().sourceText());
        assertEquals(cast.value().range().startByte(), cast.range().startByte());
        assertEquals(cast.targetType().range().endByte(), cast.range().endByte());
        assertEquals("Array[int]", requireNotNull(assertInstanceOf(CastExpression.class,
                assertInstanceOf(VariableDeclaration.class, ast.statements().get(1)).value()))
                .targetType().sourceText());
    }

    @Test
    void castBindsWeakerThanAdditionLikeGodot() {
        // Godot anchors `1 + 2 as int` as `(1 + 2) as int` (PREC_CAST below addition); the
        // tree-sitter path groups `1 + (2 as int)` (a registered precedence divergence, no parity).
        var ast = cleanAst("const A = 1 + 2 as int\n");
        var cast = requireNotNull(assertInstanceOf(CastExpression.class,
                assertInstanceOf(VariableDeclaration.class, onlyStatement(ast)).value()));
        assertEquals("+", requireNotNull(assertInstanceOf(BinaryExpression.class, cast.value())).operator());
        assertEquals("int", cast.targetType().sourceText());
    }

    @Test
    void typeTestShapes() {
        var ast = cleanAst("var a = x is Node\nvar b = y is not int\n");
        var plain = requireNotNull(assertInstanceOf(TypeTestExpression.class,
                assertInstanceOf(VariableDeclaration.class, ast.statements().getFirst()).value()));
        assertFalse(plain.negated());
        assertEquals("Node", plain.targetType().sourceText());
        var negated = requireNotNull(assertInstanceOf(TypeTestExpression.class,
                assertInstanceOf(VariableDeclaration.class, ast.statements().get(1)).value()));
        assertTrue(negated.negated());
        assertEquals("int", negated.targetType().sourceText());
    }

    @Test
    void castAndTypeTestMissingType() {
        // A missing type reports and degrades the expression to the left operand.
        var castResult = parse("var a = x as\n");
        assertTrue(errorMessages(castResult).contains("Expected type specifier after \"as\"."));
        assertInstanceOf(IdentifierExpression.class,
                assertInstanceOf(VariableDeclaration.class, castResult.ast().statements().getFirst()).value());

        assertTrue(errorMessages(parse("var b = x is\n")).contains("Expected type specifier after \"is\"."));
        assertTrue(errorMessages(parse("var c = x is not\n")).contains("Expected type specifier after \"is not\"."));
    }

    @Test
    void ternaryShape() {
        var ast = cleanAst("const A = 1 if c else 2\n");
        var ternary = requireNotNull(assertInstanceOf(ConditionalExpression.class,
                assertInstanceOf(VariableDeclaration.class, onlyStatement(ast)).value()));
        assertEquals("c", assertInstanceOf(IdentifierExpression.class, ternary.condition()).name());
        assertEquals("1", assertInstanceOf(LiteralExpression.class, ternary.left()).sourceText());
        assertEquals("2", assertInstanceOf(LiteralExpression.class, ternary.right()).sourceText());
    }

    @Test
    void ternaryNestsOnFalseBranch() {
        var ast = cleanAst("const A = 1 if a else 2 if b else 3\n");
        var outer = requireNotNull(assertInstanceOf(ConditionalExpression.class,
                assertInstanceOf(VariableDeclaration.class, onlyStatement(ast)).value()));
        assertInstanceOf(LiteralExpression.class, outer.left());
        var inner = assertInstanceOf(ConditionalExpression.class, outer.right());
        assertEquals("b", assertInstanceOf(IdentifierExpression.class, inner.condition()).name());
    }

    @Test
    void ternaryErrors() {
        assertTrue(errorMessages(parse("var a = 1 if c\n"))
                .contains("Expected \"else\" after ternary operator condition."));
        assertTrue(errorMessages(parse("var b = 1 if else 2\n"))
                .contains("Expected expression as ternary condition after \"if\"."));
        assertTrue(errorMessages(parse("var c = 1 if c else\n")).contains("Expected expression after \"else\"."));
    }

    @Test
    void notInShape() {
        var ast = cleanAst("var a = x not in y\n");
        var binary = requireNotNull(assertInstanceOf(BinaryExpression.class,
                assertInstanceOf(VariableDeclaration.class, onlyStatement(ast)).value()));
        assertEquals("not in", binary.operator());
        assertEquals("x", assertInstanceOf(IdentifierExpression.class, binary.left()).name());
        assertEquals("y", assertInstanceOf(IdentifierExpression.class, binary.right()).name());
    }

    @Test
    void notInMissingIn() {
        assertTrue(errorMessages(parse("var a = x not\n"))
                .contains("Expected \"in\" after \"not\" in content-test operator."));
    }

    @Test
    void unaryOperatorFamily() {
        var ast = cleanAst("const A = not b\nconst B = !c\nconst C = ~d\n");
        var operators = List.of("not", "!", "~");
        for (var i = 0; i < operators.size(); i++) {
            var constant = assertInstanceOf(VariableDeclaration.class, ast.statements().get(i));
            var unary = assertInstanceOf(UnaryExpression.class, constant.value());
            assertEquals(operators.get(i), unary.operator());
            assertInstanceOf(IdentifierExpression.class, unary.operand());
        }
        assertTrue(errorMessages(parse("var x = not\n")).contains("Expected expression after \"not\" operator."));
    }

    @Test
    void questionMarkReportsTernaryHint() {
        assertTrue(errorMessages(parse("var a = x ? y : z\n")).contains(
                "Unexpected \"?\" in source. If you want a ternary operator, use \"truthy_value if true_condition else falsy_value\"."));
    }

    @Test
    void assignmentShapes() {
        var ast = cleanAst("func f():\n\tx = 1\n\ty += 2\n\tz **= 3\n\ta.b = 4\n\tc[0] = 5\n");
        var function = assertInstanceOf(FunctionDeclaration.class, onlyStatement(ast));
        var operators = List.of("=", "+=", "**=", "=", "=");
        for (var i = 0; i < operators.size(); i++) {
            var assignment = assertInstanceOf(AssignmentExpression.class,
                    assertInstanceOf(ExpressionStatement.class, function.body().statements().get(i)).expression());
            assertEquals(operators.get(i), assignment.operator());
        }
        // Attribute and subscription chains are valid targets.
        assertInstanceOf(AttributeExpression.class,
                assertInstanceOf(AssignmentExpression.class,
                        assertInstanceOf(ExpressionStatement.class, function.body().statements().get(3))
                                .expression()).left());
    }

    @Test
    void chainedAssignmentReportsNestedDiagnostic() {
        // Godot: the right-hand side parses with assignment disallowed.
        var result = parse("func f():\n\ta = b = 1\n");
        assertTrue(errorMessages(result).contains("Assignment is not allowed inside an expression."));
    }

    @Test
    void assignmentRejectedInsideSubexpressions() {
        // Variable initializers and call arguments are not assignment contexts (vendor flags).
        assertTrue(errorMessages(parse("var a = b = 1\n"))
                .contains("Assignment is not allowed inside an expression."));
        assertTrue(errorMessages(parse("func f():\n\tfoo(x = 1)\n"))
                .contains("Assignment is not allowed inside an expression."));
    }

    @Test
    void assignmentTargetCheckIsDeferredByDesign() {
        // The vendor's assignee node-type check is a parse-time semantic check outside the
        // ported set (left to the analysis layer); the invalid target produces an
        // AssignmentExpression for the analyzer to reject, with no parser diagnostic.
        var ast = cleanAst("func f():\n\t1 = 2\n");
        var function = assertInstanceOf(FunctionDeclaration.class, onlyStatement(ast));
        var assignment = assertInstanceOf(AssignmentExpression.class,
                assertInstanceOf(ExpressionStatement.class, function.body().statements().getFirst()).expression());
        assertInstanceOf(LiteralExpression.class, assignment.left());
    }

    // -----------------------------------------------------------------------------------------
    // Godot 3.x removed keywords (migration diagnostics).
    // -----------------------------------------------------------------------------------------

    @Test
    void removedKeywordsCarryMigrationMessages() {
        record Case(String source, String message) {
        }
        var cases = List.of(
                new Case("export var x = 1\n",
                        "The \"export\" keyword was removed in Godot 4. Use an export annotation (\"@export\", \"@export_range\", etc.) instead."),
                new Case("tool\nvar x = 1\n",
                        "The \"tool\" keyword was removed in Godot 4. Use the \"@tool\" annotation instead."),
                new Case("onready var x = 1\n",
                        "The \"onready\" keyword was removed in Godot 4. Use the \"@onready\" annotation instead."),
                new Case("remote func f(): pass\n",
                        "The \"remote\" keyword was removed in Godot 4. Use the \"@rpc\" annotation with \"any_peer\" instead."),
                new Case("remotesync func f(): pass\n",
                        "The \"remotesync\" keyword was removed in Godot 4. Use the \"@rpc\" annotation with \"any_peer\" and \"call_local\" instead."),
                new Case("puppet func f(): pass\n",
                        "The \"puppet\" keyword was removed in Godot 4. Use the \"@rpc\" annotation with \"authority\" instead."),
                new Case("puppetsync func f(): pass\n",
                        "The \"puppetsync\" keyword was removed in Godot 4. Use the \"@rpc\" annotation with \"authority\" and \"call_local\" instead."),
                new Case("master func f(): pass\n",
                        "The \"master\" keyword was removed in Godot 4. Use the \"@rpc\" annotation with \"any_peer\" and perform a check inside the function instead."),
                new Case("mastersync func f(): pass\n",
                        "The \"mastersync\" keyword was removed in Godot 4. Use the \"@rpc\" annotation with \"any_peer\" and \"call_local\", and perform a check inside the function instead.")
        );
        for (var c : cases) {
            var errors = errorMessages(parse(c.source()));
            assertTrue(errors.contains(c.message()), () -> c.source() + " -> " + errors);
        }
    }

    @Test
    void memberAfterRemovedKeywordRecovers() {
        var result = parse("export var x = 1\nfunc f(): pass\n");
        assertFalse(errorMessages(result).isEmpty());
        assertTrue(result.ast().statements().stream().anyMatch(VariableDeclaration.class::isInstance));
        assertTrue(result.ast().statements().stream().anyMatch(FunctionDeclaration.class::isInstance));
    }

    // -----------------------------------------------------------------------------------------
    // Recovery, synchronization and termination.
    // -----------------------------------------------------------------------------------------

    @Test
    void badMemberThenGoodSiblingRecovers() {
        var result = parse("var = \nfunc f(): pass\n");
        assertTrue(errorMessages(result).contains("Expected variable name after \"var\"."));
        assertTrue(result.ast().statements().stream().anyMatch(FunctionDeclaration.class::isInstance));
    }

    @Test
    void badStatementInSuiteThenSiblingRecovers() {
        // `if` now dispatches to the if-statement parser (like Godot), so a bare `if` reports the
        // missing condition; the NEWLINE still terminates synchronization and the sibling recovers.
        var result = parse("func f():\n\tif\n\tpass\n");
        assertTrue(errorMessages(result).contains("Expected conditional expression after \"if\"."));
        var function = assertInstanceOf(FunctionDeclaration.class, result.ast().statements().getFirst());
        assertTrue(function.body().statements().stream().anyMatch(PassStatement.class::isInstance));
    }

    @Test
    void statementLevelAnnotationAttachesToNextStatement() {
        var ast = cleanAst("func f():\n\t@warning_ignore(\"unused_variable\")\n\tvar x = 1\n");
        var function = assertInstanceOf(FunctionDeclaration.class, onlyStatement(ast));
        assertEquals(2, function.body().statements().size());
        var annotation = assertInstanceOf(AnnotationStatement.class, function.body().statements().getFirst());
        assertEquals("warning_ignore", annotation.name());
        assertInstanceOf(VariableDeclaration.class, function.body().statements().get(1));
    }

    @Test
    void tooManyStatementErrorsCircuitBreakerTerminates() {
        // 105 unparseable statements trip the suite circuit breaker at the 102nd failure.
        var source = "func f():\n" + "\t?\n".repeat(105);
        var errors = errorMessages(parse(source));
        assertTrue(errors.contains("Too many statement errors."), () -> "count=" + errors.size());
    }

    @Test
    void tokenizerErrorsBecomeDiagnosticsAndParsingContinues() {
        var result = parse("var x § = 1\n");
        assertEquals(1, errorMessages(result).size());
        // The error token is skipped by the cursor loop, so the declaration still parses.
        var variable = assertInstanceOf(VariableDeclaration.class, result.ast().statements().getFirst());
        assertInstanceOf(LiteralExpression.class, variable.value());
    }

    @Test
    void unexpectedTokenInClassBody() {
        var errors = errorMessages(parse("123\n"));
        assertTrue(errors.contains("Unexpected \"Literal\" in class body."), errors::toString);
    }

    @Test
    void unexpectedIdentifierInClassBody() {
        var errors = errorMessages(parse("foo\n"));
        assertTrue(errors.contains("Unexpected identifier \"foo\" in class body."), errors::toString);
    }

    @Test
    void leftoverTokensAfterBodyReportEndOfFile() {
        // A dedented garbage line after a complete nested class leaves tokens the program-level
        // body cannot consume either; both errors surface and the parse terminates.
        var result = parse("class Inner:\n\tpass\n§\n");
        assertFalse(errorMessages(result).isEmpty());
    }

    // -----------------------------------------------------------------------------------------
    // Identifier-like keywords (Godot's is_identifier / is_node_name).
    // -----------------------------------------------------------------------------------------

    @Test
    void matchAndWhenAreUsableAsNames() {
        var ast = cleanAst("func match(): pass\nvar when = 1\nsignal s(match)\n");
        assertEquals("match", assertInstanceOf(FunctionDeclaration.class, ast.statements().getFirst()).name());
        assertEquals("when", assertInstanceOf(VariableDeclaration.class, ast.statements().get(1)).name());
        assertEquals("match", assertInstanceOf(SignalStatement.class, ast.statements().get(2))
                .parameters().getFirst().name());
    }

    @Test
    void builtinConstantsAreUsableAsNames() {
        var ast = cleanAst("var PI = 1\nconst A = Vector2.INF\n");
        assertEquals("PI", assertInstanceOf(VariableDeclaration.class, ast.statements().getFirst()).name());
        var attribute = requireNotNull(assertInstanceOf(AttributeExpression.class,
                assertInstanceOf(VariableDeclaration.class, ast.statements().get(1)).value()));
        assertEquals("INF", assertInstanceOf(AttributePropertyStep.class, attribute.steps().getFirst()).name());
    }

    @Test
    void keywordsAreUsableAsAttributeNames() {
        var ast = cleanAst("var ok = \"abc\".match(\"a*\")\nvar cls = obj.class\n");
        var call = requireNotNull(assertInstanceOf(AttributeExpression.class,
                assertInstanceOf(VariableDeclaration.class, ast.statements().getFirst()).value()));
        assertInstanceOf(AttributeCallStep.class, call.steps().getFirst());
        var property = requireNotNull(assertInstanceOf(AttributeExpression.class,
                assertInstanceOf(VariableDeclaration.class, ast.statements().get(1)).value()));
        assertEquals("class", assertInstanceOf(AttributePropertyStep.class, property.steps().getFirst()).name());
    }

    // -----------------------------------------------------------------------------------------
    // Review-driven recovery and state-machine regressions.
    // -----------------------------------------------------------------------------------------

    @Test
    void failedExpressionStatementAtEofDoesNotThrow() {
        // No trailing newline: the failed expression leaves the cursor at EOF, where Godot's
        // soft-failing advance must not become an exception.
        var unary = parse("func f(): +");
        assertFalse(errorMessages(unary).isEmpty());
        var call = parse("func f(): foo(");
        assertFalse(errorMessages(call).isEmpty());
    }

    @Test
    void brokenSubscriptDoesNotLeakMultilineMode() {
        var result = parse("var bad = a[]\nfunc good():\n\tpass\n\tvar local = 1\n");
        assertTrue(errorMessages(result).contains("Expected expression after \"[\"."));
        // Multiline mode must be restored, or the function body would misparse as a single-line
        // suite and the local declaration would escape to the root sequence.
        assertEquals(2, result.ast().statements().size());
        var good = assertInstanceOf(FunctionDeclaration.class, result.ast().statements().get(1));
        assertEquals(2, good.body().statements().size());
        assertTrue(good.body().statements().stream().anyMatch(PassStatement.class::isInstance));
        assertTrue(good.body().statements().stream().anyMatch(VariableDeclaration.class::isInstance));
    }

    @Test
    void danglingAnnotationInsideAccessorSuiteIsDiscarded() {
        var ast = cleanAst("var x:\n\tget:\n\t\tpass\n\t\t@export\nvar y = 1\n");
        // The accessor suite's content is discarded entirely; its dangling annotation must not
        // leak onto the next outer member.
        assertEquals(2, ast.statements().size());
        assertInstanceOf(VariableDeclaration.class, ast.statements().getFirst());
        var y = assertInstanceOf(VariableDeclaration.class, ast.statements().get(1));
        assertEquals("y", y.name());
    }

    @Test
    void errorneousStaticPrefixDoesNotPolluteRecoveredSibling() {
        var result = parse("static ?\nfunc good(): pass\n");
        assertTrue(errorMessages(result).contains("Expected \"func\" or \"var\" after \"static\"."));
        var good = assertInstanceOf(FunctionDeclaration.class, result.ast().statements().getFirst());
        assertFalse(good.isStatic());
    }

    @Test
    void sameLineExtendsCountsTowardDuplicateCheck() {
        var errors = errorMessages(parse("class_name C extends A\nextends B\n"));
        assertTrue(errors.contains("\"extends\" can only be used once."), errors::toString);
    }

    @Test
    void extendsBeforeClassNameDoesNotDuplicateLikeGodot() {
        // Godot only checks the duplicate on the standalone `extends` line; a same-line extends
        // inside `class_name` is parsed unconditionally (anchored vendor behavior, do not
        // "fix" this into an error).
        var result = parse("extends A\nclass_name C extends B\n");
        assertFalse(errorMessages(result).contains("\"extends\" can only be used once."),
                () -> errorMessages(result).toString());
        assertEquals("B", assertInstanceOf(ClassNameStatement.class, result.ast().statements().get(1))
                .extendsTarget());
    }

    @Test
    void trailingAnnotationStaysInItsOwnScope() {
        var ast = cleanAst("func f():\n\tpass\n\t@x\nvar outside = 1\n");
        // The annotation dangles at the end of f's suite; it must stay in that scope instead of
        // escaping onto the next outer member.
        var function = assertInstanceOf(FunctionDeclaration.class, ast.statements().getFirst());
        assertEquals(2, function.body().statements().size());
        assertInstanceOf(AnnotationStatement.class, function.body().statements().get(1));
        assertEquals(2, ast.statements().size());
        assertInstanceOf(VariableDeclaration.class, ast.statements().get(1));
    }

    @Test
    void tokenizerErrorInsideGroupingPushIsReported() {
        var result = parse("enum E\n§{A}\n");
        var errors = result.diagnostics().stream()
                .filter(d -> d.severity() == AstDiagnosticSeverity.ERROR)
                .toList();
        assertFalse(errors.isEmpty());
        assertTrue(errors.getFirst().message().contains("Invalid character"),
                () -> errors.getFirst().message());
        // The error range anchors the offending byte (§ starts at byte 7).
        assertEquals(7, errors.getFirst().range().startByte());
    }

    @Test
    void circuitBreakerCountsAnnotatedFailuresLikeGodot() {
        // Legal leading annotations return ConsumedPrefix and never consume the failure budget:
        // 120 valid `@warning_ignore(...)` prefixes before a target statement parse cleanly.
        var validMany = "func f():\n" + "\t@warning_ignore(\"unused_variable\")\n".repeat(120) + "\tvar ok = 1\n";
        var clean = cleanAst(validMany);
        var cleanFn = assertInstanceOf(FunctionDeclaration.class, clean.statements().getFirst());
        assertEquals(121, cleanFn.body().statements().size());
        assertEquals("ok", assertInstanceOf(VariableDeclaration.class, cleanFn.body().statements().getLast()).name());

        // 60 pairs of `@x` + failing `var` only count 60 real failures, below the 102 threshold.
        var underLimit = parse("func f():\n" + "\t@x\n\tvar\n".repeat(60));
        assertFalse(errorMessages(underLimit).contains("Too many statement errors."),
                () -> errorMessages(underLimit).toString());

        // 105 genuinely failing `var` lines preceded by `@x` still trip STATEMENT_TOO_MANY_ERRORS.
        var overLimit = parse("func f():\n" + "\t@x\n\tvar\n".repeat(105));
        assertTrue(overLimit.diagnostics().stream()
                .anyMatch(d -> DiagnosticCodes.STATEMENT_TOO_MANY_ERRORS.equals(d.code())),
                () -> overLimit.diagnostics().toString());
    }

    @Test
    void signalRejectsRestParameter() {
        var result = parse("signal s(...a)\n");
        var errors = errorMessages(result);
        assertTrue(errors.contains("Expected parameter name."), errors::toString);
        assertFalse(errors.contains("Expected signal parameter name."), errors::toString);
    }

    @Test
    void sameInstanceReuseAfterFailure() {
        var parser = new GdScriptParser();
        var broken = parser.parse("var bad = a[]\n");
        assertFalse(errorMessages(broken).isEmpty());
        var clean = parser.parse("var ok = 1\n");
        assertEquals(List.of(), errorMessages(clean));
        assertInstanceOf(VariableDeclaration.class, clean.ast().statements().getFirst());
    }

    @Test
    void danglingAttributeDotAtEofDoesNotThrow() {
        var errors = errorMessages(parse("var x = a."));
        assertTrue(errors.contains("Expected identifier after \".\" for attribute access."),
                errors::toString);
    }

    @Test
    void danglingAttributeDotBeforeNewlineKeepsChainWithMissingStep() {
        var result = parse("var x = a.\nvar y = 2\n");
        assertTrue(errorMessages(result).contains("Expected identifier after \".\" for attribute access."));
        // The failed member access keeps the receiver chain with a zero-width
        // `MissingAttributeStep` at the dot's end, and the sibling parses.
        var x = assertInstanceOf(VariableDeclaration.class, result.ast().statements().getFirst());
        var chain = assertInstanceOf(AttributeExpression.class, x.value());
        assertEquals("a", assertInstanceOf(IdentifierExpression.class, chain.base()).name());
        assertEquals(1, chain.steps().size());
        assertInstanceOf(MissingAttributeStep.class, chain.steps().getFirst());
        assertInstanceOf(VariableDeclaration.class, result.ast().statements().get(1));
    }

    @Test
    void missingMemberNameKeepsChainAsFoldBarrier() {
        // A missing member name leaves a `MissingAttributeStep` in the chain; the marker acts as
        // a fold barrier, so a following call/subscript wraps the chain instead of folding onto
        // the previous property step.
        var result = parse("var x = a.b.(1)\nvar y = a.b.[0]\nvar z = a.b. .c\n");
        assertEquals(3, errorMessages(result).size());

        var x = assertInstanceOf(VariableDeclaration.class, result.ast().statements().getFirst());
        var xCall = assertInstanceOf(CallExpression.class, x.value());
        var xChain = assertInstanceOf(AttributeExpression.class, xCall.callee());
        assertEquals("a", assertInstanceOf(IdentifierExpression.class, xChain.base()).name());
        assertEquals(2, xChain.steps().size());
        assertEquals("b", assertInstanceOf(AttributePropertyStep.class, xChain.steps().getFirst()).name());
        assertInstanceOf(MissingAttributeStep.class, xChain.steps().get(1));
        assertEquals(1, xCall.arguments().size());

        var y = assertInstanceOf(VariableDeclaration.class, result.ast().statements().get(1));
        var ySubscript = assertInstanceOf(SubscriptExpression.class, y.value());
        var yChain = assertInstanceOf(AttributeExpression.class, ySubscript.base());
        assertEquals(2, yChain.steps().size());
        assertInstanceOf(AttributePropertyStep.class, yChain.steps().getFirst());
        assertInstanceOf(MissingAttributeStep.class, yChain.steps().get(1));

        // A name after the missing-name dot still joins the chain normally.
        var z = assertInstanceOf(VariableDeclaration.class, result.ast().statements().get(2));
        var zChain = assertInstanceOf(AttributeExpression.class, z.value());
        assertEquals("a", assertInstanceOf(IdentifierExpression.class, zChain.base()).name());
        assertEquals(3, zChain.steps().size());
        assertEquals("b", assertInstanceOf(AttributePropertyStep.class, zChain.steps().getFirst()).name());
        assertInstanceOf(MissingAttributeStep.class, zChain.steps().get(1));
        assertEquals("c", assertInstanceOf(AttributePropertyStep.class, zChain.steps().get(2)).name());
    }

    @Test
    void doubleDotIsASingleTokenNotAMissingMember() {
        // `a..(1)`: the tokenizer produces one `..` token, so the chain ends at `a` and the
        // statement tail reports the stray `..` — no missing-member diagnostic, no marker step.
        var result = parse("var q = a..(1)\n");
        assertEquals(List.of("Expected end of statement after variable declaration, found \"..\" instead."),
                errorMessages(result), () -> errorMessages(result).toString());
        var q = assertInstanceOf(VariableDeclaration.class, result.ast().statements().getFirst());
        assertEquals("a", assertInstanceOf(IdentifierExpression.class, q.value()).name());
    }

    @Test
    void whitespaceAfterDotIsALegalMemberName() {
        var ast = cleanAst("var q = a. b\n");
        var q = assertInstanceOf(VariableDeclaration.class, onlyStatement(ast));
        var chain = assertInstanceOf(AttributeExpression.class, q.value());
        assertEquals("b", assertInstanceOf(AttributePropertyStep.class, chain.steps().getFirst()).name());
    }

    @Test
    void callWrapsNonPropertyChainEnd() {
        // A call that does not follow `.name` wraps the expression built so far instead of
        // folding into the attribute chain.
        var ast = cleanAst("const A = a.b(1)(2)\n");
        var constant = assertInstanceOf(VariableDeclaration.class, onlyStatement(ast));
        var outerCall = assertInstanceOf(dev.superice.gdparser.frontend.ast.CallExpression.class, constant.value());
        var chain = assertInstanceOf(AttributeExpression.class, outerCall.callee());
        assertEquals(1, chain.steps().size());
        assertInstanceOf(AttributeCallStep.class, chain.steps().getFirst());
    }

    @Test
    void multilineCallInsideChain() {
        var ast = cleanAst("const A = a.b(\n\t1\n).c\n");
        var constant = assertInstanceOf(VariableDeclaration.class, onlyStatement(ast));
        var chain = assertInstanceOf(AttributeExpression.class, constant.value());
        assertEquals(2, chain.steps().size());
        assertInstanceOf(AttributeCallStep.class, chain.steps().getFirst());
        assertEquals("c", assertInstanceOf(AttributePropertyStep.class, chain.steps().get(1)).name());
    }

    @Test
    void deeplyNestedSingleLineClasses() {
        var ast = cleanAst("class A: class B: pass\n");
        var a = assertInstanceOf(ClassDeclaration.class, onlyStatement(ast));
        var b = assertInstanceOf(ClassDeclaration.class, a.body().statements().getFirst());
        assertInstanceOf(PassStatement.class, b.body().statements().getFirst());
    }

    @Test
    void multibyteSourceSlicesAndCrlfRanges() {
        var ast = cleanAst("extends \"res://场景.gd\"\nvar 数据 = 1\r\n");
        var extendsStatement = assertInstanceOf(ExtendsStatement.class, ast.statements().getFirst());
        // UTF-8 slices must keep the multi-byte path intact.
        assertEquals("\"res://场景.gd\"", extendsStatement.target());

        var variable = assertInstanceOf(VariableDeclaration.class, ast.statements().get(1));
        assertEquals("数据", variable.name());
        // `extends "res://场景.gd"` is 25 UTF-8 bytes + '\n', so `var` starts at byte 26.
        assertEquals(26, variable.range().startByte());
        assertEquals(new Point(1, 0), variable.range().startPoint());
    }

    // -----------------------------------------------------------------------------------------
    // Control flow: if/elif/else, while, for, return, break/continue, breakpoint, assert.
    // -----------------------------------------------------------------------------------------

    @Test
    void ifElifElseFoldsIntoClauses() {
        var ast = cleanAst("func f(x):\n\tif x > 0:\n\t\treturn 1\n\telif x < 0:\n\t\treturn -1\n\telse:\n\t\treturn 0\n");
        var function = assertInstanceOf(FunctionDeclaration.class, onlyStatement(ast));
        var ifStatement = assertInstanceOf(IfStatement.class, function.body().statements().getFirst());
        // The statement spans from the `if` keyword to the end of the else suite.
        assertEquals(new Range(12, 75, new Point(1, 1), new Point(6, 10)), ifStatement.range());
        assertInstanceOf(BinaryExpression.class, ifStatement.condition());
        assertEquals(new Range(21, 32, new Point(1, 10), new Point(2, 10)), ifStatement.body().range());

        assertEquals(1, ifStatement.elifClauses().size());
        var elif = ifStatement.elifClauses().getFirst();
        // An elif clause spans from the `elif` keyword to the end of its own suite.
        assertEquals(new Range(34, 57, new Point(3, 1), new Point(4, 11)), elif.range());
        assertEquals(new Range(45, 57, new Point(3, 12), new Point(4, 11)), elif.body().range());

        assertNotNull(ifStatement.elseBody());
        assertEquals(new Range(64, 75, new Point(5, 6), new Point(6, 10)), ifStatement.elseBody().range());
    }

    @Test
    void ifWithSingleLineSuite() {
        var ast = cleanAst("func f():\n\tif x: return 1\n\tpass\n");
        var function = assertInstanceOf(FunctionDeclaration.class, onlyStatement(ast));
        var ifStatement = assertInstanceOf(IfStatement.class, function.body().statements().getFirst());
        // A single-line body block spans exactly its statement.
        assertEquals(new Range(17, 25, new Point(1, 7), new Point(1, 15)), ifStatement.body().range());
        assertEquals(new Range(11, 25, new Point(1, 1), new Point(1, 15)), ifStatement.range());
        assertTrue(ifStatement.elifClauses().isEmpty());
        assertNull(ifStatement.elseBody());
        assertInstanceOf(PassStatement.class, function.body().statements().get(1));
    }

    @Test
    void ifMissingConditionSurvivesWithMarkerAndConsumesChain() {
        var result = parse("func f():\n\tif:\n\t\tpass\n\telse:\n\t\tpass\n\tvar after = 1\n");
        assertTrue(errorMessages(result).contains("Expected conditional expression after \"if\"."));
        var function = assertInstanceOf(FunctionDeclaration.class, result.ast().statements().getFirst());
        // The if statement survives with a MISSING marker condition; the elif/else chain is
        // consumed as usual, so the sibling statement recovers cleanly instead of cascading.
        assertEquals(2, function.body().statements().size());
        var ifStatement = assertInstanceOf(IfStatement.class, function.body().statements().getFirst());
        var marker = assertInstanceOf(ErrorExpression.class, ifStatement.condition());
        assertEquals(CstIssueKind.MISSING, marker.kind());
        assertEquals("if.condition", marker.nodeType());
        assertEquals(1, ifStatement.body().statements().size());
        assertNotNull(ifStatement.elseBody());
        assertInstanceOf(VariableDeclaration.class, function.body().statements().get(1));
    }

    @Test
    void whileLoopWithBreakAndContinue() {
        var ast = cleanAst("func f():\n\twhile x < 3:\n\t\tbreak\n\t\tcontinue\n");
        var function = assertInstanceOf(FunctionDeclaration.class, onlyStatement(ast));
        var whileStatement = assertInstanceOf(WhileStatement.class, function.body().statements().getFirst());
        assertEquals(new Range(11, 42, new Point(1, 1), new Point(3, 10)), whileStatement.range());
        assertEquals(new Range(23, 42, new Point(1, 13), new Point(3, 10)), whileStatement.body().range());
        assertInstanceOf(BreakStatement.class, whileStatement.body().statements().getFirst());
        assertInstanceOf(ContinueStatement.class, whileStatement.body().statements().get(1));
    }

    @Test
    void breakAndContinueOutsideLoopAreErrors() {
        var breakResult = parse("func f():\n\tbreak\n");
        assertEquals(List.of("Cannot use \"break\" outside of a loop."), errorMessages(breakResult));
        var continueResult = parse("func f():\n\tcontinue\n");
        assertEquals(List.of("Cannot use \"continue\" outside of a loop."), errorMessages(continueResult));
    }

    @Test
    void breakInsideIfInsideLoopIsAllowed() {
        // Loop flags are parser state that flows through nested control-flow suites.
        var ast = cleanAst("func f():\n\twhile true:\n\t\tif x:\n\t\t\tbreak\n");
        var function = assertInstanceOf(FunctionDeclaration.class, onlyStatement(ast));
        var whileStatement = assertInstanceOf(WhileStatement.class, function.body().statements().getFirst());
        var ifStatement = assertInstanceOf(IfStatement.class, whileStatement.body().statements().getFirst());
        assertInstanceOf(BreakStatement.class, ifStatement.body().statements().getFirst());
    }

    @Test
    void breakAfterLoopIsRejectedAgain() {
        // The loop flags must be restored after the loop suite, not left enabled.
        var result = parse("func f():\n\twhile x:\n\t\tpass\n\tbreak\n");
        assertEquals(List.of("Cannot use \"break\" outside of a loop."), errorMessages(result));
    }

    @Test
    void forLoopPlainAndTyped() {
        var ast = cleanAst("func f():\n\tfor i in range(3):\n\t\tpass\n\tfor j: int in arr:\n\t\tpass\n");
        var function = assertInstanceOf(FunctionDeclaration.class, onlyStatement(ast));

        var plain = assertInstanceOf(ForStatement.class, function.body().statements().getFirst());
        assertEquals("i", plain.iterator());
        assertNull(plain.iteratorType());
        assertInstanceOf(CallExpression.class, plain.iterable());
        assertEquals(new Range(11, 36, new Point(1, 1), new Point(2, 6)), plain.range());

        var typed = assertInstanceOf(ForStatement.class, function.body().statements().get(1));
        assertEquals("j", typed.iterator());
        assertNotNull(typed.iteratorType());
        assertEquals("int", typed.iteratorType().sourceText());
        assertEquals(new Range(45, 48, new Point(3, 8), new Point(3, 11)), typed.iteratorType().range());
        assertInstanceOf(IdentifierExpression.class, typed.iterable());
    }

    @Test
    void forLoopMissingInUsesContextualMessage() {
        var untyped = parse("func f():\n\tfor i x:\n\t\tpass\n");
        assertTrue(errorMessages(untyped).contains("Expected \"in\" or \":\" after \"for\" variable name."));
        var typed = parse("func f():\n\tfor i: int x:\n\t\tpass\n");
        assertTrue(errorMessages(typed).contains("Expected \"in\" after \"for\" variable type specifier."));
    }

    @Test
    void returnBareAndValued() {
        var ast = cleanAst("func f():\n\treturn\nfunc g():\n\treturn 1\n");
        var f = assertInstanceOf(FunctionDeclaration.class, ast.statements().getFirst());
        var bare = assertInstanceOf(ReturnStatement.class, f.body().statements().getFirst());
        assertNull(bare.value());
        assertEquals(new Range(11, 17, new Point(1, 1), new Point(1, 7)), bare.range());

        var g = assertInstanceOf(FunctionDeclaration.class, ast.statements().get(1));
        var valued = assertInstanceOf(ReturnStatement.class, g.body().statements().getFirst());
        assertNotNull(valued.value());
        assertEquals(new Range(29, 37, new Point(3, 1), new Point(3, 9)), valued.range());
    }

    @Test
    void constructorReturnValueIsRejected() {
        var result = parse("func _init():\n\treturn 1\n");
        assertEquals(List.of("Constructor cannot return a value."), errorMessages(result));
        // The value expression is still parsed and kept, like Godot's error-annotated node.
        var constructor = assertInstanceOf(ConstructorDeclaration.class, result.ast().statements().getFirst());
        assertNotNull(assertInstanceOf(ReturnStatement.class,
                constructor.body().statements().getFirst()).value());

        var staticResult = parse("static func _static_init():\n\treturn 1\n");
        assertTrue(errorMessages(staticResult).contains("Constructor cannot return a value."));
        // A plain function named like a constructor keeps returning values legally.
        cleanAst("func _initish():\n\treturn 1\n");
    }

    @Test
    void assertConditionAndMessage() {
        var ast = cleanAst("func f():\n\tassert(x > 0, \"bad\")\n");
        var function = assertInstanceOf(FunctionDeclaration.class, onlyStatement(ast));
        var assertStatement = assertInstanceOf(AssertStatement.class, function.body().statements().getFirst());
        assertInstanceOf(BinaryExpression.class, assertStatement.condition());
        assertNotNull(assertStatement.message());
        // The statement spans the whole parenthesized form.
        assertEquals(new Range(11, 31, new Point(1, 1), new Point(1, 21)), assertStatement.range());
    }

    @Test
    void assertFormsWithoutMessageAndWithTrailingComma() {
        var ast = cleanAst("func f():\n\tassert(x)\n\tassert(y,)\n");
        var function = assertInstanceOf(FunctionDeclaration.class, onlyStatement(ast));
        var plain = assertInstanceOf(AssertStatement.class, function.body().statements().getFirst());
        assertNull(plain.message());
        var trailing = assertInstanceOf(AssertStatement.class, function.body().statements().get(1));
        assertNull(trailing.message());
    }

    @Test
    void assertErrorsMatchGodot() {
        var noParens = parse("func f():\n\tassert x > 0\n");
        assertTrue(errorMessages(noParens).contains("Expected \"(\" after \"assert\"."));
        var missingCondition = parse("func f():\n\tassert()\n");
        assertTrue(errorMessages(missingCondition).contains("Expected expression to assert."));
        var thirdArgument = parse("func f():\n\tassert(1, \"m\", 3)\n");
        assertTrue(errorMessages(thirdArgument).contains("Expected \")\" after assert expression."));
    }

    @Test
    void breakpointStatementParses() {
        var ast = cleanAst("func f():\n\tbreakpoint\n");
        var function = assertInstanceOf(FunctionDeclaration.class, onlyStatement(ast));
        var breakpoint = assertInstanceOf(BreakpointStatement.class, function.body().statements().getFirst());
        assertEquals(new Range(11, 21, new Point(1, 1), new Point(1, 11)), breakpoint.range());
    }

    // -----------------------------------------------------------------------------------------
    // Match statement and patterns.
    // -----------------------------------------------------------------------------------------

    @Test
    void matchLiteralBindWildcardSections() {
        var ast = cleanAst("func f(v):\n\tmatch v:\n\t\t1:\n\t\t\tpass\n\t\tvar a:\n\t\t\tpass\n\t\t_:\n\t\t\tpass\n");
        var function = assertInstanceOf(FunctionDeclaration.class, onlyStatement(ast));
        var match = assertInstanceOf(MatchStatement.class, function.body().statements().getFirst());
        // The statement spans from the `match` keyword to the last section, excluding the DEDENT.
        assertEquals(new Range(12, 63, new Point(1, 1), new Point(7, 7)), match.range());
        assertEquals("v", assertInstanceOf(IdentifierExpression.class, match.value()).name());
        assertEquals(3, match.sections().size());

        var literal = match.sections().getFirst();
        assertEquals(new Range(23, 33, new Point(2, 2), new Point(3, 7)), literal.range());
        assertEquals(new Range(23, 24, new Point(2, 2), new Point(2, 3)),
                assertInstanceOf(LiteralExpression.class, literal.patterns().getFirst()).range());
        assertNull(literal.guard());
        assertEquals(new Range(25, 33, new Point(2, 4), new Point(3, 7)), literal.body().range());

        var bind = match.sections().get(1);
        var binding = assertInstanceOf(PatternBindingExpression.class, bind.patterns().getFirst());
        assertEquals("a", binding.name());
        // A bind pattern spans the whole `var a` form.
        assertEquals(new Range(36, 41, new Point(4, 2), new Point(4, 7)), binding.range());

        var wildcard = match.sections().get(2);
        // The wildcard is a plain `_` identifier in the AST contract.
        assertEquals("_", assertInstanceOf(IdentifierExpression.class,
                wildcard.patterns().getFirst()).name());
    }

    @Test
    void matchMultiplePatternsAndGuard() {
        var ast = cleanAst("func f(v):\n\tmatch v:\n\t\t1, 2:\n\t\t\tpass\n\t\t3 when ok:\n\t\t\tbreakpoint\n");
        var function = assertInstanceOf(FunctionDeclaration.class, onlyStatement(ast));
        var match = assertInstanceOf(MatchStatement.class, function.body().statements().getFirst());

        var multi = match.sections().getFirst();
        assertEquals(2, multi.patterns().size());
        assertEquals(new Range(23, 36, new Point(2, 2), new Point(3, 7)), multi.range());

        var guarded = match.sections().get(1);
        assertEquals("ok", assertInstanceOf(IdentifierExpression.class, guarded.guard()).name());
        assertEquals(new Range(46, 48, new Point(4, 9), new Point(4, 11)), guarded.guard().range());
        assertInstanceOf(BreakpointStatement.class, guarded.body().statements().getFirst());
    }

    @Test
    void matchArrayPatternsWithRestMarker() {
        var ast = cleanAst("func f(v):\n\tmatch v:\n\t\t[1, 2]:\n\t\t\tpass\n\t\t[_, var a, ..]:\n\t\t\tpass\n");
        var function = assertInstanceOf(FunctionDeclaration.class, onlyStatement(ast));
        var match = assertInstanceOf(MatchStatement.class, function.body().statements().getFirst());

        var closed = assertInstanceOf(ArrayExpression.class, match.sections().getFirst().patterns().getFirst());
        assertFalse(closed.openEnded());
        assertEquals(2, closed.elements().size());
        assertEquals(new Range(23, 29, new Point(2, 2), new Point(2, 8)), closed.range());

        var open = assertInstanceOf(ArrayExpression.class, match.sections().get(1).patterns().getFirst());
        assertTrue(open.openEnded());
        // The `..` marker sets openEnded instead of adding an element.
        assertEquals(2, open.elements().size());
        assertEquals("_", assertInstanceOf(IdentifierExpression.class, open.elements().getFirst()).name());
        assertInstanceOf(PatternBindingExpression.class, open.elements().get(1));
        assertEquals(new Range(41, 55, new Point(4, 2), new Point(4, 16)), open.range());
    }

    @Test
    void matchDictionaryPatterns() {
        var ast = cleanAst("func f(v):\n\tmatch v:\n\t\t{\"k\": 1, \"j\": var b, ..}:\n\t\t\tpass\n");
        var function = assertInstanceOf(FunctionDeclaration.class, onlyStatement(ast));
        var match = assertInstanceOf(MatchStatement.class, function.body().statements().getFirst());
        var dictionary = assertInstanceOf(DictionaryExpression.class, match.sections().getFirst().patterns().getFirst());
        assertTrue(dictionary.openEnded());
        assertEquals(2, dictionary.entries().size());
        var first = dictionary.entries().getFirst();
        assertEquals(new Range(24, 30, new Point(2, 3), new Point(2, 9)), first.range());
        assertInstanceOf(LiteralExpression.class, first.value());
        var second = dictionary.entries().get(1);
        assertInstanceOf(PatternBindingExpression.class, second.value());
        assertEquals(new Range(23, 47, new Point(2, 2), new Point(2, 26)), dictionary.range());
    }

    @Test
    void matchNestedAndExpressionPatterns() {
        var ast = cleanAst("func f(v):\n\tmatch v:\n\t\t[{\"k\": _}, [..]]:\n\t\t\tpass\n\t\t1 + 2:\n\t\t\tpass\n\t\t'y':\n\t\t\tpass\n");
        var function = assertInstanceOf(FunctionDeclaration.class, onlyStatement(ast));
        var match = assertInstanceOf(MatchStatement.class, function.body().statements().getFirst());

        var nested = assertInstanceOf(ArrayExpression.class, match.sections().getFirst().patterns().getFirst());
        var dict = assertInstanceOf(DictionaryExpression.class, nested.elements().getFirst());
        assertEquals("_", assertInstanceOf(IdentifierExpression.class, dict.entries().getFirst().value()).name());
        var restOnly = assertInstanceOf(ArrayExpression.class, nested.elements().get(1));
        assertTrue(restOnly.openEnded());
        assertEquals(0, restOnly.elements().size());

        assertInstanceOf(BinaryExpression.class, match.sections().get(1).patterns().getFirst());
        // Single quotes produce a plain string literal (StringName needs the `&` prefix).
        var stringPattern = assertInstanceOf(LiteralExpression.class, match.sections().get(2).patterns().getFirst());
        assertEquals("string", stringPattern.kind());
    }

    @Test
    void matchKeyOnlyDictionaryPatternPreservesKeyWithNullValue() {
        var result = parse("""
                func f(v):
                \tmatch v:
                \t\t{"name"}:
                \t\t\tpass
                \t\t[{"k"}]:
                \t\t\tpass
                \t\t{"a": {"b"}}:
                \t\t\tpass
                \t\t{"a": _}:
                \t\t\tpass
                """);
        assertTrue(result.diagnostics().isEmpty(), () -> "Unexpected diagnostics: " + result.diagnostics());
        var function = assertInstanceOf(FunctionDeclaration.class, result.ast().statements().getFirst());
        var match = assertInstanceOf(MatchStatement.class, function.body().statements().getFirst());
        assertEquals(4, match.sections().size());

        var dict = assertInstanceOf(DictionaryExpression.class, match.sections().get(0).patterns().getFirst());
        assertEquals(1, dict.entries().size());
        var key = assertInstanceOf(LiteralExpression.class, dict.entries().getFirst().key());
        assertEquals("\"name\"", key.sourceText());
        assertNull(dict.entries().getFirst().value());

        var arrPattern = assertInstanceOf(ArrayExpression.class, match.sections().get(1).patterns().getFirst());
        var nestedDict = assertInstanceOf(DictionaryExpression.class, arrPattern.elements().getFirst());
        assertNull(nestedDict.entries().getFirst().value());

        var outerDict = assertInstanceOf(DictionaryExpression.class, match.sections().get(2).patterns().getFirst());
        var innerDictVal = assertInstanceOf(DictionaryExpression.class, outerDict.entries().getFirst().value());
        assertNull(innerDictVal.entries().getFirst().value());

        // `{"a": _}` remains distinct from key-only `{"a"}`.
        var wildcardDict = assertInstanceOf(DictionaryExpression.class, match.sections().get(3).patterns().getFirst());
        var wildcardVal = assertInstanceOf(IdentifierExpression.class, wildcardDict.entries().getFirst().value());
        assertEquals("_", wildcardVal.name());

        // A broken pair `{"a": }` in match pattern still reports an error with a non-null ErrorExpression value.
        var broken = parse("func f(v):\n\tmatch v:\n\t\t{\"a\": }:\n\t\t\tpass\n");
        assertFalse(errorMessages(broken).isEmpty());
        var brokenMatch = assertInstanceOf(MatchStatement.class,
                assertInstanceOf(FunctionDeclaration.class, broken.ast().statements().getFirst())
                        .body().statements().getFirst());
        var brokenDict = assertInstanceOf(DictionaryExpression.class,
                brokenMatch.sections().getFirst().patterns().getFirst());
        assertInstanceOf(ErrorExpression.class, brokenDict.entries().getFirst().value());
    }

    @Test
    void matchPassBranchIsConsumedWithoutNode() {
        // Godot consumes a branch-level `pass` without producing a node; the reference path
        // rejects it (a registered parity divergence).
        var ast = cleanAst("func f(v):\n\tmatch v:\n\t\t1:\n\t\t\tpass\n\t\tpass\n");
        var function = assertInstanceOf(FunctionDeclaration.class, onlyStatement(ast));
        var match = assertInstanceOf(MatchStatement.class, function.body().statements().getFirst());
        assertEquals(1, match.sections().size());
    }

    @Test
    void matchLevelAnnotations() {
        // `@warning_ignore` is consumed and dropped (this AST has no branch annotation field).
        var ignored = cleanAst("func f(v):\n\tmatch v:\n\t\t@warning_ignore(\"unused\")\n\t\t1:\n\t\t\tpass\n");
        var function = assertInstanceOf(FunctionDeclaration.class, onlyStatement(ignored));
        assertEquals(1, assertInstanceOf(MatchStatement.class,
                function.body().statements().getFirst()).sections().size());
        // Any other annotation is rejected, like Godot.
        var result = parse("func f(v):\n\tmatch v:\n\t\t@export\n\t\t1:\n\t\t\tpass\n");
        assertTrue(errorMessages(result).contains("Annotation \"@export\" is not allowed in this level."));
    }

    @Test
    void matchRestPatternMisuseErrors() {
        var branchLevel = parse("func f(v):\n\tmatch v:\n\t\t..:\n\t\t\tpass\n");
        assertTrue(errorMessages(branchLevel).contains(
                "Rest pattern can only be used inside array and dictionary patterns."));
        var arrayTrailing = parse("func f(v):\n\tmatch v:\n\t\t[1, .., 2]:\n\t\t\tpass\n");
        assertTrue(errorMessages(arrayTrailing).contains(
                "The \"..\" pattern must be the last element in the pattern array."));
        var dictTrailing = parse("func f(v):\n\tmatch v:\n\t\t{.., \"k\": 1}:\n\t\t\tpass\n");
        assertTrue(errorMessages(dictTrailing).contains(
                "The \"..\" pattern must be the last element in the pattern dictionary."));
        var restValue = parse("func f(v):\n\tmatch v:\n\t\t{\"k\": ..}:\n\t\t\tpass\n");
        assertTrue(errorMessages(restValue).contains("The \"..\" pattern cannot be used as a value."));
    }

    @Test
    void matchHeaderAndBranchErrors() {
        var noValue = parse("func f():\n\tmatch:\n\t\t1:\n\t\t\tpass\n");
        assertTrue(errorMessages(noValue).contains("Expected expression to test after \"match\"."));
        var noGuard = parse("func f(v):\n\tmatch v:\n\t\t1 when:\n\t\t\tpass\n");
        assertTrue(errorMessages(noGuard).contains("Expected expression for pattern guard after \"when\"."));
        var noColon = parse("func f(v):\n\tmatch v:\n\t\t1\n\t\t\tpass\n\t\t2:\n\t\t\tpass\n");
        assertTrue(errorMessages(noColon).contains("Expected \":\" or \"when\" after \"match\" patterns."));
    }

    @Test
    void matchSectionMissingColonRecoversToNextBranch() {
        var result = parse("func f(v):\n\tmatch v:\n\t\t1\n\t\t2:\n\t\t\tpass\n");
        assertTrue(errorMessages(result).contains("Expected \":\" or \"when\" after \"match\" patterns."));
        var function = assertInstanceOf(FunctionDeclaration.class, result.ast().statements().getFirst());
        var match = assertInstanceOf(MatchStatement.class, function.body().statements().getFirst());
        // The recovery consumes the broken line; the next line parses as a normal branch.
        assertEquals(2, match.sections().size());
        assertEquals(1, match.sections().get(1).body().statements().size());
    }

    // -----------------------------------------------------------------------------------------
    // Lambda expressions.
    // -----------------------------------------------------------------------------------------

    @Test
    void lambdaSingleLineBody() {
        var ast = cleanAst("var f = func(a): return a\n");
        var declaration = assertInstanceOf(VariableDeclaration.class, onlyStatement(ast));
        var lambda = assertInstanceOf(LambdaExpression.class, declaration.value());
        assertNull(lambda.name());
        assertNull(lambda.returnType());
        assertEquals(1, lambda.parameters().size());
        assertEquals("a", lambda.parameters().getFirst().name());
        // The lambda spans from `func` to the body end; a single-line body starts at its statement.
        assertEquals(new Range(8, 25, new Point(0, 8), new Point(0, 25)), lambda.range());
        assertEquals(new Range(17, 25, new Point(0, 17), new Point(0, 25)), lambda.body().range());
        assertInstanceOf(ReturnStatement.class, lambda.body().statements().getFirst());
    }

    @Test
    void lambdaMultilineBody() {
        var ast = cleanAst("var f = func(a):\n\treturn a\n");
        var declaration = assertInstanceOf(VariableDeclaration.class, onlyStatement(ast));
        var lambda = assertInstanceOf(LambdaExpression.class, declaration.value());
        assertEquals(new Range(8, 26, new Point(0, 8), new Point(1, 9)), lambda.range());
        assertEquals(new Range(16, 26, new Point(0, 16), new Point(1, 9)), lambda.body().range());
        var returnStatement = assertInstanceOf(ReturnStatement.class, lambda.body().statements().getFirst());
        assertEquals(new Range(18, 26, new Point(1, 1), new Point(1, 9)), returnStatement.range());
    }

    @Test
    void lambdaNamedWithReturnTypeAndDefaults() {
        var ast = cleanAst("var f = func named(x) -> int:\n\treturn x\n");
        var declaration = assertInstanceOf(VariableDeclaration.class, onlyStatement(ast));
        var lambda = assertInstanceOf(LambdaExpression.class, declaration.value());
        assertEquals("named", lambda.name());
        assertNotNull(lambda.returnType());
        assertEquals("int", lambda.returnType().sourceText());
        assertEquals(new Range(8, 39, new Point(0, 8), new Point(1, 9)), lambda.range());

        var withDefaults = cleanAst("var g = func(a: int, b = 1): return a + b\n");
        var defaulted = requireNotNull(assertInstanceOf(LambdaExpression.class,
                assertInstanceOf(VariableDeclaration.class, onlyStatement(withDefaults)).value()));
        assertEquals(2, defaulted.parameters().size());
        assertNotNull(defaulted.parameters().getFirst().type());
        assertNotNull(defaulted.parameters().get(1).defaultValue());
    }

    @Test
    void lambdaInsideCallArguments() {
        // Single-line body inside parentheses: the enclosing multiline context is snapshotted
        // and restored, so the call keeps parsing after the lambda.
        var ast = cleanAst("var x = g(func(a): return a, 2)\n");
        var declaration = assertInstanceOf(VariableDeclaration.class, onlyStatement(ast));
        var call = assertInstanceOf(CallExpression.class, declaration.value());
        assertEquals(2, call.arguments().size());
        assertInstanceOf(LambdaExpression.class, call.arguments().getFirst());
    }

    @Test
    void lambdaMultilineBodyInsideCallArguments() {
        // A multiline lambda body inside call parentheses re-enables indentation tracking via the
        // tokenizer's expression-indented-block snapshot; layout tokens before `, 2)` are skipped.
        var ast = cleanAst("var x = g(func():\n\treturn 1\n, 2)\n");
        var declaration = assertInstanceOf(VariableDeclaration.class, onlyStatement(ast));
        var call = assertInstanceOf(CallExpression.class, declaration.value());
        assertEquals(2, call.arguments().size());
        var lambda = assertInstanceOf(LambdaExpression.class, call.arguments().getFirst());
        assertInstanceOf(ReturnStatement.class, lambda.body().statements().getFirst());
    }

    @Test
    void lambdaAtStatementLevelBecomesAnonymousFunction() {
        // Reference-path contract: a statement-position `func` maps to a FunctionDeclaration
        // (named or `<anonymous>`), not a lambda in an expression statement.
        var ast = cleanAst("func f():\n\tfunc(): pass\n\tpass\n");
        var function = assertInstanceOf(FunctionDeclaration.class, onlyStatement(ast));
        var anonymous = assertInstanceOf(FunctionDeclaration.class, function.body().statements().getFirst());
        assertEquals("<anonymous>", anonymous.name());
        assertNull(anonymous.returnType());
        assertEquals(new Range(11, 23, new Point(1, 1), new Point(1, 13)), anonymous.range());
        // The lambda-end state must be consumed so the sibling statement still parses.
        assertInstanceOf(PassStatement.class, function.body().statements().get(1));

        var named = cleanAst("func f():\n\tfunc named(): pass\n");
        var namedFn = assertInstanceOf(FunctionDeclaration.class,
                assertInstanceOf(FunctionDeclaration.class, onlyStatement(named)).body().statements().getFirst());
        assertEquals("named", namedFn.name());

        var typed = cleanAst("func f():\n\tfunc() -> int: return 1\n");
        var typedFn = assertInstanceOf(FunctionDeclaration.class,
                assertInstanceOf(FunctionDeclaration.class, onlyStatement(typed)).body().statements().getFirst());
        assertEquals("<anonymous>", typedFn.name());
        assertEquals("int", requireNotNull(typedFn.returnType()).sourceText());
    }

    @Test
    void classBodyAnonymousFunctionIsRejected() {
        // Godot requires a name on class-body functions; the reference path accepts anonymous
        // ones (a registered parity divergence).
        var result = parse("func(): pass\n");
        assertTrue(errorMessages(result).contains("Expected function name after \"func\"."));
    }

    // -----------------------------------------------------------------------------------------
    // Comment and region-directive attribution (rules pinned from the reference path).
    // -----------------------------------------------------------------------------------------

    @Test
    void commentsAndRegionsInsertIntoStatementSequence() {
        var ast = cleanAst("#region 初始化\nvar x = 1 # 尾注释\n# 整行注释\nfunc f():\n\tpass # 尾\n#endregion\n");
        var statements = ast.statements();

        var regionStart = assertInstanceOf(RegionDirectiveStatement.class, statements.getFirst());
        assertEquals("start", regionStart.directive());
        assertEquals("初始化", regionStart.label());

        assertInstanceOf(VariableDeclaration.class, statements.get(1));
        // A trailing comment lands right after the statement it follows.
        assertEquals("# 尾注释", assertInstanceOf(CommentStatement.class, statements.get(2)).text());
        // A whole-line comment takes its source position between siblings.
        assertEquals("# 整行注释", assertInstanceOf(CommentStatement.class, statements.get(3)).text());

        var function = assertInstanceOf(FunctionDeclaration.class, statements.get(4));
        var body = function.body().statements();
        assertInstanceOf(PassStatement.class, body.getFirst());
        var trailing = assertInstanceOf(CommentStatement.class, body.get(1));
        assertEquals("# 尾", trailing.text());
        // Columns are byte-based on both paths (`# 尾` is 5 UTF-8 bytes).
        assertEquals(new Range(71, 76, new Point(4, 6), new Point(4, 11)), trailing.range());

        var regionEnd = assertInstanceOf(RegionDirectiveStatement.class, statements.get(5));
        assertEquals("end", regionEnd.directive());
        assertNull(regionEnd.label());
    }

    @Test
    void regionClassificationBoundaryForms() {
        // `#regionfoo` counts as a start directive with label `foo`; `# region` with a space is
        // a plain comment; `#endregion` always has a null label, even with trailing text.
        var ast = cleanAst("#regionfoo\nvar x = 1\n#endregion done\n# region\n#region\nvar y = 1\n");
        var statements = ast.statements();
        var foo = assertInstanceOf(RegionDirectiveStatement.class, statements.getFirst());
        assertEquals("start", foo.directive());
        assertEquals("foo", foo.label());
        var end = assertInstanceOf(RegionDirectiveStatement.class, statements.get(2));
        assertEquals("end", end.directive());
        assertNull(end.label());
        assertEquals("# region", assertInstanceOf(CommentStatement.class, statements.get(3)).text());
        var noLabel = assertInstanceOf(RegionDirectiveStatement.class, statements.get(4));
        assertEquals("start", noLabel.directive());
        assertNull(noLabel.label());
    }

    @Test
    void wholeLineCommentBeforeFirstBodyStatement() {
        var ast = cleanAst("func f():\n\t# first\n\tpass\n");
        var function = assertInstanceOf(FunctionDeclaration.class, onlyStatement(ast));
        var body = function.body().statements();
        assertEquals("# first", assertInstanceOf(CommentStatement.class, body.getFirst()).text());
        assertInstanceOf(PassStatement.class, body.get(1));
    }

    @Test
    void dedentedWholeLineCommentBelongsToOuterSequence() {
        var ast = cleanAst("func f():\n\tpass\n# hello\nfunc g():\n\tpass\n");
        var statements = ast.statements();
        assertEquals(3, statements.size());
        var f = assertInstanceOf(FunctionDeclaration.class, statements.getFirst());
        // f's body keeps only `pass`; the col-0 comment lands at the top level.
        assertEquals(1, f.body().statements().size());
        assertEquals("# hello", assertInstanceOf(CommentStatement.class, statements.get(1)).text());
        assertInstanceOf(FunctionDeclaration.class, statements.get(2));
    }

    @Test
    void indentedTailCommentStaysInBody() {
        var ast = cleanAst("class Inner:\n\tpass\n\t# inner\nvar x = 1\n");
        var statements = ast.statements();
        var inner = assertInstanceOf(ClassDeclaration.class, statements.getFirst());
        // The indented comment reaches the class-body indentation, so it stays inside and the
        // body block extends over it.
        assertEquals(2, inner.body().statements().size());
        assertEquals("# inner", assertInstanceOf(CommentStatement.class,
                inner.body().statements().get(1)).text());
        assertEquals(new Range(12, 27, new Point(0, 12), new Point(2, 8)), inner.body().range());
        assertInstanceOf(VariableDeclaration.class, statements.get(1));
    }

    @Test
    void colZeroCommentMidBodyStaysWhenBodyContinues() {
        var ast = cleanAst("func f():\n\tpass\n# mid\n\tpass\n");
        var function = assertInstanceOf(FunctionDeclaration.class, onlyStatement(ast));
        var body = function.body().statements();
        // The next code line still belongs to the body, so the col-0 comment stays inside.
        assertEquals(3, body.size());
        assertEquals("# mid", assertInstanceOf(CommentStatement.class, body.get(1)).text());
    }

    @Test
    void trailingCommentExtendsSingleLineSuite() {
        var ast = cleanAst("func f():\n\tif x: pass # c\n\tpass\n");
        var function = assertInstanceOf(FunctionDeclaration.class, onlyStatement(ast));
        var ifStatement = assertInstanceOf(IfStatement.class, function.body().statements().getFirst());
        var ifBody = ifStatement.body().statements();
        assertEquals(2, ifBody.size());
        assertEquals("# c", assertInstanceOf(CommentStatement.class, ifBody.get(1)).text());
        assertEquals(new Range(17, 25, new Point(1, 7), new Point(1, 15)), ifStatement.body().range());
    }

    @Test
    void suiteHeaderTrailingCommentsAreDropped() {
        // Both paths drop comments trailing a suite-header line.
        var ast = cleanAst("func f(): # c\n\tpass\nfunc g():\n\tif x: # i\n\t\tpass\n\telse: # e\n\t\tpass\n");
        var statements = ast.statements();
        var f = assertInstanceOf(FunctionDeclaration.class, statements.getFirst());
        assertEquals(1, f.body().statements().size());
        var g = assertInstanceOf(FunctionDeclaration.class, statements.get(1));
        var ifStatement = assertInstanceOf(IfStatement.class, g.body().statements().getFirst());
        assertEquals(1, ifStatement.body().statements().size());
        assertEquals(1, requireNotNull(ifStatement.elseBody()).statements().size());
    }

    @Test
    void matchBranchLevelCommentsAreDropped() {
        var ast = cleanAst("func f(v):\n\tmatch v:\n\t\t# c\n\t\t1:\n\t\t\tpass\n\t\t# tail\n");
        var function = assertInstanceOf(FunctionDeclaration.class, onlyStatement(ast));
        var match = assertInstanceOf(MatchStatement.class, function.body().statements().getFirst());
        assertEquals(1, match.sections().size());
        // The section body keeps its own comment-free content; branch-level comments vanish.
        assertEquals(1, match.sections().getFirst().body().statements().size());
    }

    @Test
    void commentInsideExpressionIsIgnored() {
        // Registered parity difference: the reference path materializes expression-internal
        // comments as unknown-expression placeholders, which this parser cannot reproduce —
        // comments never enter its token stream, so they are silently ignored.
        var ast = cleanAst("var a = [\n\t1, # one\n\t2\n]\n");
        var declaration = assertInstanceOf(VariableDeclaration.class, onlyStatement(ast));
        var array = assertInstanceOf(ArrayExpression.class, declaration.value());
        assertEquals(2, array.elements().size());
    }

    @Test
    void commentBetweenAnnotationAndStatementKeepsByteOrder() {
        var ast = cleanAst("@export\n# note\nvar x = 1\n");
        var statements = ast.statements();
        assertEquals(3, statements.size());
        assertInstanceOf(AnnotationStatement.class, statements.getFirst());
        assertEquals("# note", assertInstanceOf(CommentStatement.class, statements.get(1)).text());
        assertInstanceOf(VariableDeclaration.class, statements.get(2));

        var reordered = cleanAst("# note\n@export\nvar x = 1\n");
        var reorderedStatements = reordered.statements();
        assertEquals("# note", assertInstanceOf(CommentStatement.class, reorderedStatements.getFirst()).text());
        assertInstanceOf(AnnotationStatement.class, reorderedStatements.get(1));
    }

    @Test
    void newlineBetweenStaticAndMemberIsRejectedLikeGodot() {
        // A registered parity divergence: the reference path accepts (and spans) a newline
        // between `static` and the member; Godot rejects it immediately.
        var result = parse("static\nvar x = 1\n");
        assertTrue(errorMessages(result).contains("Expected \"func\" or \"var\" after \"static\"."));
    }

    @Test
    void commentAtEofLandsAtTopLevel() {
        var ast = cleanAst("var x = 1\n# eof comment\n");
        var statements = ast.statements();
        assertEquals(2, statements.size());
        assertEquals("# eof comment", assertInstanceOf(CommentStatement.class, statements.get(1)).text());
        var noNewline = cleanAst("var x = 1 # tail");
        assertEquals(2, noNewline.statements().size());
        assertEquals("# tail", assertInstanceOf(CommentStatement.class,
                noNewline.statements().get(1)).text());
    }

    @Test
    void commentOnlyBodyIsRejectedLikeGodot() {
        // A registered parity divergence: comment-only lines never establish indentation, so a
        // body with only comments is a missing indented block (the reference path accepts it).
        var result = parse("func f():\n\t# only\nvar x = 1\n");
        assertTrue(errorMessages(result).contains("Expected indented block after function declaration."));
        // The comment is not lost into the void: it lands at the outer (top-level) sequence.
        assertTrue(result.ast().statements().stream().anyMatch(CommentStatement.class::isInstance));
    }

    // -----------------------------------------------------------------------------------------
    // Review-driven regressions: lambda state machine, match recovery, grouping trivia.
    // -----------------------------------------------------------------------------------------

    @Test
    void lambdaArgumentDoesNotSwallowFollowingReturnValue() {
        // Vendor clears the lambda-end marker on every real token advance: after a lambda in a
        // condition's call, the following `return` must still parse its value.
        var ast = cleanAst("func test():\n\tif f(func(): return 1, 2):\n\t\treturn 3\n");
        var function = assertInstanceOf(FunctionDeclaration.class, onlyStatement(ast));
        var ifStatement = assertInstanceOf(IfStatement.class, function.body().statements().getFirst());
        var returnStatement = assertInstanceOf(ReturnStatement.class,
                ifStatement.body().statements().getFirst());
        assertNotNull(returnStatement.value());
    }

    @Test
    void lambdaBodyFailedStatementEndsLambdaAtOuterSeparator() {
        // A token that cannot start a statement ends the lambda instead of being consumed
        // (vendor's in-lambda branch); the enclosing call's separator stays intact.
        var ast = cleanAst("var x = g(func():\n\treturn 1\n\t, 2)\n");
        var declaration = assertInstanceOf(VariableDeclaration.class, onlyStatement(ast));
        var call = assertInstanceOf(CallExpression.class, declaration.value());
        assertEquals(2, call.arguments().size());
        var lambda = assertInstanceOf(LambdaExpression.class, call.arguments().getFirst());
        assertEquals(1, lambda.body().statements().size());
        assertInstanceOf(ReturnStatement.class, lambda.body().statements().getFirst());
    }

    @Test
    void multilineLambdaDefaultKeepsParameterRangeAtBodyEnd() {
        // The lambda body's trailing layout consumption must not stretch the parameter's range
        // past the body (regression: it used to end at the trailing DEDENT).
        var ast = cleanAst("func f(cb = func():\n\treturn 1\n):\n\tpass\n");
        var function = assertInstanceOf(FunctionDeclaration.class, onlyStatement(ast));
        var parameter = function.parameters().getFirst();
        assertEquals(new Range(7, 29, new Point(0, 7), new Point(1, 9)), parameter.range());
        assertInstanceOf(LambdaExpression.class, parameter.defaultValue());
    }

    @Test
    void matchMissingIndentDoesNotEatOuterDedent() {
        var result = parse("func f():\n\tmatch v:\npass\nfunc g():\n\tpass\n");
        assertTrue(errorMessages(result).contains("Expected an indented block after \"match\" statement."));
        // Vendor returns immediately on the missing indented block: the outer function's DEDENT
        // stays put, so the sibling declaration recovers as a top-level function.
        var statements = result.ast().statements();
        assertEquals(2, statements.stream().filter(FunctionDeclaration.class::isInstance).count());
        var g = assertInstanceOf(FunctionDeclaration.class, statements.getLast());
        assertEquals("g", g.name());
        assertEquals(1, g.body().statements().size());
    }

    @Test
    void matchDanglingWarningIgnoreIsDiagnosed() {
        // A `@warning_ignore` left unattached (only `pass` follows, or it sits after the last
        // branch) is reported, like the vendor's end-of-match sweep.
        var beforePass = parse("func f(v):\n\tmatch v:\n\t\t@warning_ignore(\"unused\")\n\t\tpass\n");
        assertTrue(errorMessages(beforePass).contains(
                "Annotation \"@warning_ignore\" does not precede a valid target, so it will have no effect."));
        var afterLastBranch = parse("func f(v):\n\tmatch v:\n\t\t1:\n\t\t\tpass\n\t\t@warning_ignore(\"unused\")\n");
        assertTrue(errorMessages(afterLastBranch).contains(
                "Annotation \"@warning_ignore\" does not precede a valid target, so it will have no effect."));
        // Attached to a branch: consumed and dropped without diagnostics.
        cleanAst("func f(v):\n\tmatch v:\n\t\t@warning_ignore(\"unused\")\n\t\t1:\n\t\t\tpass\n");
    }

    @Test
    void matchTailKeepsLessIndentedCommentsForOuterSequence() {
        var ast = cleanAst("func f(v):\n\tmatch v:\n\t\t_: pass\n# outer\nvar after = 1\n");
        var statements = ast.statements();
        assertEquals(3, statements.size());
        assertEquals("# outer", assertInstanceOf(CommentStatement.class, statements.get(1)).text());
        assertInstanceOf(VariableDeclaration.class, statements.get(2));
    }

    @Test
    void matchBodyColZeroCommentIsDroppedLikeGodot() {
        // A registered parity divergence: a col-0 comment between branches is trivia to Godot
        // (and this parser) but breaks the reference grammar, so there is no parity promise.
        var ast = cleanAst("func f(v):\n\tmatch v:\n\t\t1: pass\n# low\n\t\t2: pass\n\tpass\n");
        var function = assertInstanceOf(FunctionDeclaration.class, onlyStatement(ast));
        var match = assertInstanceOf(MatchStatement.class, function.body().statements().getFirst());
        assertEquals(2, match.sections().size());
        assertFalse(function.body().statements().stream().anyMatch(CommentStatement.class::isInstance));
    }

    @Test
    void groupingInternalCommentIsSkippedWithoutEatingSequenceComments() {
        var ast = cleanAst("# before\n@export_range(\n\t0, # internal\n\t10\n)\nvar x = 1\n");
        var statements = ast.statements();
        // The sequence comment before the annotation materializes; the argument-list-internal
        // one is silently ignored (registered parity difference).
        assertEquals(3, statements.size());
        assertEquals("# before", assertInstanceOf(CommentStatement.class, statements.getFirst()).text());
        assertInstanceOf(AnnotationStatement.class, statements.get(1));
        assertInstanceOf(VariableDeclaration.class, statements.get(2));
        assertFalse(statements.stream().anyMatch(s -> s instanceof CommentStatement c
                && c.text().contains("internal")));
    }

    @Test
    void multiDedentMixedIndentCommentRunKeepsSimpleRule() {
        // A registered known difference: the reference scanner's col-0-comment lookahead
        // backfills indentation across levels (an upstream-acknowledged heuristic), while this
        // parser applies the plain indentation-threshold rule — a mixed run at a multi-dedent
        // boundary lands at the top level here instead of the middle body.
        var ast = cleanAst("class Outer:\n    class Inner:\n        pass\n# low\n        # high\nvar after = 1\n");
        var statements = ast.statements();
        assertInstanceOf(ClassDeclaration.class, statements.getFirst());
        assertEquals("# low", assertInstanceOf(CommentStatement.class, statements.get(1)).text());
        assertEquals("# high", assertInstanceOf(CommentStatement.class, statements.get(2)).text());
        assertInstanceOf(VariableDeclaration.class, statements.get(3));
    }

    @Test
    void forLoopTypeParseFailureUsesPlainInMessage() {
        // Vendor `parse_type` returns null on failure, which selects the untyped message (a
        // review-suggested alternate message was verified against the vendor and rejected).
        var result = parse("func f():\n\tfor i: ??? in xs:\n\t\tpass\n");
        assertTrue(errorMessages(result).contains("Expected \"in\" or \":\" after \"for\" variable name."));
    }

    @Test
    void operatorAfterLambdaBoundaryIsNotFolded() {
        // Vendor's infix-loop head stops on a pending lambda end: the same-indent `+ 2` is a
        // new unary statement, not part of the return value (the reference path agrees).
        var ast = cleanAst("func f():\n\treturn func(): return 1\n\t+ 2\n");
        var function = assertInstanceOf(FunctionDeclaration.class, onlyStatement(ast));
        var body = function.body().statements();
        assertEquals(2, body.size());
        var returnStatement = assertInstanceOf(ReturnStatement.class, body.getFirst());
        assertInstanceOf(LambdaExpression.class, returnStatement.value());
        var unary = assertInstanceOf(ExpressionStatement.class, body.get(1));
        assertInstanceOf(UnaryExpression.class, unary.expression());
    }

    @Test
    void commentBeforeLambdaInsideGroupingIsIgnored() {
        // A comment inside an enclosing grouping but before the `func` keyword is grouping-
        // internal trivia (registered difference), not a lambda-body comment.
        var ast = cleanAst("var x = g(\n\t# internal\n\tfunc():\n\t\treturn 2\n)\n");
        var declaration = assertInstanceOf(VariableDeclaration.class, onlyStatement(ast));
        var call = assertInstanceOf(CallExpression.class, declaration.value());
        var lambda = assertInstanceOf(LambdaExpression.class, call.arguments().getFirst());
        assertEquals(1, lambda.body().statements().size());
        assertInstanceOf(ReturnStatement.class, lambda.body().statements().getFirst());

        // A comment after the keyword stays a lambda-body comment.
        var withBody = cleanAst("var x = g(\n\tfunc():\n\t\t# body\n\t\treturn 2\n)\n");
        var bodyLambda = requireNotNull(assertInstanceOf(LambdaExpression.class,
                requireNotNull(assertInstanceOf(CallExpression.class,
                        assertInstanceOf(VariableDeclaration.class, onlyStatement(withBody)).value()))
                        .arguments().getFirst()));
        assertEquals(2, bodyLambda.body().statements().size());
        assertEquals("# body", assertInstanceOf(CommentStatement.class,
                bodyLambda.body().statements().getFirst()).text());
    }

    @Test
    void lambdaBodyDoesNotStealPreKeywordPendingComments() {
        // Pending comments before the `func` keyword are fenced off for the body: the sequence
        // comment keeps its enclosing position, the argument-list one is ignored once the
        // enclosing grouping closes, and only the body's own comment is drained.
        var ast = cleanAst("# before\n@custom(\n\t# internal\n\tfunc():\n\t\t# body\n\t\treturn 2\n)\nvar x = 1\n");
        var statements = ast.statements();
        assertEquals(3, statements.size());
        assertEquals("# before", assertInstanceOf(CommentStatement.class, statements.getFirst()).text());
        assertInstanceOf(AnnotationStatement.class, statements.get(1));
        assertInstanceOf(VariableDeclaration.class, statements.get(2));
        assertFalse(statements.stream().anyMatch(s -> s instanceof CommentStatement c
                && c.text().contains("internal")));
        var annotation = assertInstanceOf(AnnotationStatement.class, statements.get(1));
        var lambda = requireNotNull(assertInstanceOf(LambdaExpression.class, annotation.arguments().getFirst()));
        assertEquals(2, lambda.body().statements().size());
        assertEquals("# body", assertInstanceOf(CommentStatement.class,
                lambda.body().statements().getFirst()).text());
    }

    @Test
    void lambdaBodyDoesNotStealEnclosingPendingAnnotations() {
        // Annotations collected by the enclosing sequence stay there; the lambda body's merged
        // drain is isolated from them (same pattern as the accessor-suite isolation).
        var ast = cleanAst("# before\n@first\n@custom(func(): return 2)\nvar x = 1\n");
        var statements = ast.statements();
        var names = statements.stream()
                .filter(AnnotationStatement.class::isInstance)
                .map(s -> ((AnnotationStatement) s).name())
                .toList();
        assertEquals(List.of("first", "custom"), names);
        var custom = statements.stream()
                .filter(AnnotationStatement.class::isInstance)
                .map(AnnotationStatement.class::cast)
                .filter(a -> a.name().equals("custom"))
                .findFirst().orElseThrow();
        var lambda = assertInstanceOf(LambdaExpression.class, custom.arguments().getFirst());
        assertEquals(1, lambda.body().statements().size());
        assertInstanceOf(ReturnStatement.class, lambda.body().statements().getFirst());
    }

    @Test
    void nestedLambdaDefaultDoesNotStealEnclosingPendingAnnotations() {
        var ast = cleanAst("@first\n@custom(func(cb = func(): return 1): return cb)\nvar x = 1\n");
        var statements = ast.statements();
        var names = statements.stream()
                .filter(AnnotationStatement.class::isInstance)
                .map(s -> ((AnnotationStatement) s).name())
                .toList();
        assertEquals(List.of("first", "custom"), names);
    }

    @Test
    void nestedLambdaKeepsOuterBodyComments() {
        // The inner lambda's fence must not swallow comments of the outer lambda's body.
        var ast = cleanAst("var x = g(func():\n\t# outerBody\n\t@custom(func(): return 2)\n\tvar y = 1\n\treturn y\n)\n");
        var declaration = assertInstanceOf(VariableDeclaration.class, onlyStatement(ast));
        var outer = requireNotNull(assertInstanceOf(LambdaExpression.class,
                requireNotNull(assertInstanceOf(CallExpression.class, declaration.value())).arguments().getFirst()));
        assertEquals("# outerBody", assertInstanceOf(CommentStatement.class,
                outer.body().statements().getFirst()).text());
        assertEquals(4, outer.body().statements().size());
    }

    @Test
    void matchTailDiscardedCommentsStillSpanMatchExtent() {
        var ast = cleanAst("func f(v):\n\tmatch v:\n\t\t_: pass\n\t\t# branch\n\treturn 3\n");
        var function = assertInstanceOf(FunctionDeclaration.class, onlyStatement(ast));
        var match = requireNotNull(assertInstanceOf(MatchStatement.class, function.body().statements().getFirst()));
        // The tail branch-level comment produces no node, but the match node spans it (the
        // reference path's extent covers it too).
        assertEquals(new Range(12, 41, new Point(1, 1), new Point(3, 10)), match.range());
        assertFalse(function.body().statements().stream().anyMatch(CommentStatement.class::isInstance));
    }

    @Test
    void singleDedentMixedIndentCommentRunKeepsSimpleRule() {
        // The multi-level mixed-run difference (a registered known difference) also applies to a
        // single dedent: the reference scanner backfills the col-0 comment's level from the
        // following indented comment, this parser keeps the plain threshold rule.
        var ast = cleanAst("func f():\n\tpass\n# low\n\t# high\nvar after = 1\n");
        var statements = ast.statements();
        assertEquals(4, statements.size());
        assertInstanceOf(FunctionDeclaration.class, statements.getFirst());
        assertEquals(1, assertInstanceOf(FunctionDeclaration.class, statements.getFirst())
                .body().statements().size());
        assertEquals("# low", assertInstanceOf(CommentStatement.class, statements.get(1)).text());
        assertEquals("# high", assertInstanceOf(CommentStatement.class, statements.get(2)).text());
    }

    @Test
    void lambdaDisallowsBreakButRestoresLoopState() {
        var result = parse("func f():\n\twhile x:\n\t\tg(func(): break)\n\t\tbreak\n");
        // `break` inside the lambda is rejected (vendor resets the loop flags for lambda bodies)…
        assertTrue(errorMessages(result).contains("Cannot use \"break\" outside of a loop."));
        // …but the enclosing loop's own `break` afterwards stays legal.
        var function = assertInstanceOf(FunctionDeclaration.class, result.ast().statements().getFirst());
        var whileStatement = assertInstanceOf(WhileStatement.class, function.body().statements().getFirst());
        assertInstanceOf(BreakStatement.class, whileStatement.body().statements().get(1));
    }

    @Test
    void lambdaBareReturnForms() {
        // `return` at the end of the line stays a bare return…
        var ast = cleanAst("var f = func(): return\n");
        var lambda = requireNotNull(assertInstanceOf(LambdaExpression.class,
                assertInstanceOf(VariableDeclaration.class, onlyStatement(ast)).value()));
        assertNull(assertInstanceOf(ReturnStatement.class, lambda.body().statements().getFirst()).value());
        // …and `return` right before the call's closing paren parses as bare too (the vendor
        // rescues the failed value parse into no-return without an error).
        var inCall = cleanAst("var x = g(func(): return)\n");
        var callLambda = requireNotNull(assertInstanceOf(LambdaExpression.class,
                requireNotNull(assertInstanceOf(CallExpression.class,
                        assertInstanceOf(VariableDeclaration.class, onlyStatement(inCall)).value()))
                        .arguments().getFirst()));
        assertNull(assertInstanceOf(ReturnStatement.class,
                callLambda.body().statements().getFirst()).value());
    }

    @Test
    void lambdaInsideParameterDefaultKeepsOuterRanges() {
        // The lambda body's statements and the lambda signature's parameters reuse the shared
        // statement/parameter start fields; the enclosing statement and parameter ranges must
        // keep their own starts (regression: ranges were clobbered by nested parses).
        var ast = cleanAst("func f(cb = func(a): return a):\n\tpass\n");
        var function = assertInstanceOf(FunctionDeclaration.class, onlyStatement(ast));
        var parameter = function.parameters().getFirst();
        assertEquals("cb", parameter.name());
        assertEquals(new Range(7, 29, new Point(0, 7), new Point(0, 29)), parameter.range());
        assertInstanceOf(LambdaExpression.class, parameter.defaultValue());
    }

    @Test
    void lambdaHeaderErrors() {
        var namedNoParen = parse("var f = func named x:\n");
        assertTrue(errorMessages(namedNoParen).contains("Expected opening \"(\" after lambda name."));
        // `func x` treats `x` as the lambda name (the vendor matches any identifier), so the
        // anonymous-message path needs a non-identifier after `func`.
        var anonNoParen = parse("var f = func : return\n");
        assertTrue(errorMessages(anonNoParen).contains("Expected opening \"(\" after \"func\"."));
        var noColon = parse("var f = func(a) return a\n");
        assertTrue(errorMessages(noColon).contains("Expected \":\" after lambda declaration."));
        // The body is still parsed after the missing colon, like the vendor.
        var lambda = assertInstanceOf(LambdaExpression.class,
                assertInstanceOf(VariableDeclaration.class, noColon.ast().statements().getFirst()).value());
        assertEquals(1, lambda.body().statements().size());
    }
}
