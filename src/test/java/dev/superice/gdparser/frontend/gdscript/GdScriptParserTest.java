package dev.superice.gdparser.frontend.gdscript;

import dev.superice.gdparser.frontend.ast.AnnotationStatement;
import dev.superice.gdparser.frontend.ast.ArrayExpression;
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
import dev.superice.gdparser.frontend.ast.CallExpression;
import dev.superice.gdparser.frontend.ast.CastExpression;
import dev.superice.gdparser.frontend.ast.ClassDeclaration;
import dev.superice.gdparser.frontend.ast.ClassNameStatement;
import dev.superice.gdparser.frontend.ast.ConditionalExpression;
import dev.superice.gdparser.frontend.ast.ConstructorDeclaration;
import dev.superice.gdparser.frontend.ast.DeclarationKind;
import dev.superice.gdparser.frontend.ast.DictionaryExpression;
import dev.superice.gdparser.frontend.ast.EnumDeclaration;
import dev.superice.gdparser.frontend.ast.ExpressionStatement;
import dev.superice.gdparser.frontend.ast.ExtendsStatement;
import dev.superice.gdparser.frontend.ast.FunctionDeclaration;
import dev.superice.gdparser.frontend.ast.GetNodeExpression;
import dev.superice.gdparser.frontend.ast.IdentifierExpression;
import dev.superice.gdparser.frontend.ast.LiteralExpression;
import dev.superice.gdparser.frontend.ast.PassStatement;
import dev.superice.gdparser.frontend.ast.Point;
import dev.superice.gdparser.frontend.ast.PreloadExpression;
import dev.superice.gdparser.frontend.ast.Range;
import dev.superice.gdparser.frontend.ast.SelfExpression;
import dev.superice.gdparser.frontend.ast.SignalStatement;
import dev.superice.gdparser.frontend.ast.SourceFile;
import dev.superice.gdparser.frontend.ast.Statement;
import dev.superice.gdparser.frontend.ast.TypeTestExpression;
import dev.superice.gdparser.frontend.ast.UnaryExpression;
import dev.superice.gdparser.frontend.ast.VariableDeclaration;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
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
        assertInstanceOf(ExtendsStatement.class, ast.statements().get(0));
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
        var empty = cleanAst("");
        assertTrue(empty.statements().isEmpty());
        assertEquals(new Range(0, 0, new Point(0, 0), new Point(0, 0)), empty.range());

        // A newline-only source takes Godot's EOF quirk path: consuming the final '\n' fires
        // the tokenizer's EOF hook, which synthesizes a pending NEWLINE; the class-body dispatch
        // has no NEWLINE case, so Godot reports it as unexpected. The AST itself is still an
        // empty source file with a zero-width range at the physical EOF (parity holds).
        var result = parse("\n");
        assertTrue(result.ast().statements().isEmpty());
        assertEquals(new Range(1, 1, new Point(1, 0), new Point(1, 0)), result.ast().range());
        assertEquals(List.of("Unexpected \"Newline\" in class body."), errorMessages(result));
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
        assertTrue(errors.contains("\"class_name\" can only be used once."), () -> errors.toString());
    }

    @Test
    void duplicateExtendsIsReported() {
        var errors = errorMessages(parse("extends A\nextends B\n"));
        assertTrue(errors.contains("\"extends\" can only be used once."), () -> errors.toString());
    }

    @Test
    void classNameWithoutIdentifier() {
        var errors = errorMessages(parse("class_name\n"));
        assertTrue(errors.contains("Expected identifier for the global class name after \"class_name\"."),
                () -> errors.toString());
    }

    @Test
    void extendsWithoutName() {
        var errors = errorMessages(parse("extends\n"));
        assertTrue(errors.contains("Expected superclass name after \"extends\"."), () -> errors.toString());
    }

    @Test
    void extendsWithNonStringLiteral() {
        var errors = errorMessages(parse("extends 123\n"));
        assertTrue(errors.contains("Only strings or identifiers can be used after \"extends\", found \"int\" instead."),
                () -> errors.toString());
    }

    @Test
    void trailingTokenAfterClassName() {
        var errors = errorMessages(parse("class_name X 5\n"));
        assertTrue(errors.contains("Expected end of statement after class_name statement, found \"Literal\" instead."),
                () -> errors.toString());
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
        assertInstanceOf(VariableDeclaration.class, ast.statements().get(0));
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
        assertTrue(errors.contains("Expected newline after comment string."), () -> errors.toString());
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
                () -> errors.toString());
    }

    @Test
    void nestedClassMissingColon() {
        var errors = errorMessages(parse("class Inner\n\tpass\n"));
        assertTrue(errors.contains("Expected \":\" after class declaration."), () -> errors.toString());
    }

    @Test
    void nestedClassMissingIndentedBlock() {
        var errors = errorMessages(parse("class Inner:\nvar x = 1\n"));
        assertTrue(errors.contains("Expected indented block after class declaration."), () -> errors.toString());
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
        var a = assertInstanceOf(VariableDeclaration.class, ast.statements().get(0));
        assertEquals(DeclarationKind.VAR, a.kind());
        assertNull(a.type());
        assertNull(a.value());
        assertFalse(a.isStatic());
        assertEquals("variable_statement", a.sourceNodeType());

        var d = assertInstanceOf(VariableDeclaration.class, ast.statements().get(3));
        assertEquals("int", d.type().sourceText());
        assertInstanceOf(LiteralExpression.class, d.value());
    }

    @Test
    void inferredTypeMarkerShapes() {
        var ast = cleanAst("var x := 1\nvar y : = 2\n");
        var x = assertInstanceOf(VariableDeclaration.class, ast.statements().get(0));
        assertEquals(":=", x.type().sourceText());
        assertEquals(new Range(6, 8, new Point(0, 6), new Point(0, 8)), x.type().range());

        var y = assertInstanceOf(VariableDeclaration.class, ast.statements().get(1));
        // The spaced form preserves the inner whitespace in both source text and range.
        assertEquals(": =", y.type().sourceText());
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
        assertTrue(errors.contains("Expected \"func\" or \"var\" after \"static\"."), () -> errors.toString());
    }

    @Test
    void constRequiresInitializer() {
        var errors = errorMessages(parse("const A\n"));
        assertTrue(errors.contains("Expected initializer after constant name."), () -> errors.toString());
    }

    @Test
    void constInferredForm() {
        var ast = cleanAst("const A := 1\n");
        var constant = assertInstanceOf(VariableDeclaration.class, onlyStatement(ast));
        assertEquals(DeclarationKind.CONST, constant.kind());
        assertEquals(":=", constant.type().sourceText());
        assertEquals("const_statement", constant.sourceNodeType());
    }

    @Test
    void variableMissingName() {
        var errors = errorMessages(parse("var = 1\n"));
        assertTrue(errors.contains("Expected variable name after \"var\"."), () -> errors.toString());
    }

    @Test
    void variableMissingInitializerExpression() {
        var errors = errorMessages(parse("var x = \n"));
        assertTrue(errors.contains("Expected expression for variable initial value after \"=\"."),
                () -> errors.toString());
    }

    @Test
    void localVariableWithColonNewlineIsRejected() {
        // Local variables cannot have property blocks, so a newline right after `:` is an error.
        var errors = errorMessages(parse("func f():\n\tvar x:\n"));
        assertTrue(errors.contains("Expected type after \":\""), () -> errors.toString());
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
        var x = assertInstanceOf(VariableDeclaration.class, ast.statements().get(0));
        assertEquals("int", x.type().sourceText());
        assertNull(x.value());
        // The declaration range covers the whole property tail, but no accessor nodes exist.
        assertEquals(28, x.range().endByte());
        assertInstanceOf(VariableDeclaration.class, ast.statements().get(1));
    }

    @Test
    void propertyInlineBlockFormIsConsumed() {
        var ast = cleanAst("var x: int:\n\tget:\n\t\tpass\n\tset(value):\n\t\tpass\nvar y = 2\n");
        var x = assertInstanceOf(VariableDeclaration.class, ast.statements().get(0));
        assertEquals("int", x.type().sourceText());
        assertNull(x.value());
        assertInstanceOf(VariableDeclaration.class, ast.statements().get(1));
    }

    @Test
    void propertyDuplicateGetterIsReported() {
        var errors = errorMessages(parse("var x: int: get = a, get = b\n"));
        assertTrue(errors.contains("Properties can only have one getter."), () -> errors.toString());
    }

    @Test
    void propertyUnknownAccessorIsReported() {
        var errors = errorMessages(parse("var x: int: foo = a\n"));
        assertTrue(errors.contains("Expected \"get\" or \"set\" for property declaration."),
                () -> errors.toString());
    }

    // -----------------------------------------------------------------------------------------
    // Signals.
    // -----------------------------------------------------------------------------------------

    @Test
    void signalForms() {
        var ast = cleanAst("signal bare\nsignal with_params(a, b: int)\n");
        var bare = assertInstanceOf(SignalStatement.class, ast.statements().get(0));
        assertEquals("bare", bare.name());
        assertTrue(bare.parameters().isEmpty());

        var withParams = assertInstanceOf(SignalStatement.class, ast.statements().get(1));
        assertEquals(2, withParams.parameters().size());
        assertEquals("a", withParams.parameters().get(0).name());
        assertEquals("int", withParams.parameters().get(1).type().sourceText());
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
        assertTrue(errors.contains("Expected signal name after \"signal\"."), () -> errors.toString());
    }

    // -----------------------------------------------------------------------------------------
    // Enums.
    // -----------------------------------------------------------------------------------------

    @Test
    void enumForms() {
        var ast = cleanAst("enum {A, B = 2, C}\nenum Named {D}\n");
        var anon = assertInstanceOf(EnumDeclaration.class, ast.statements().get(0));
        assertNull(anon.name());
        assertEquals(3, anon.members().size());
        assertEquals("A", anon.members().get(0).name());
        assertNull(anon.members().get(0).value());
        assertInstanceOf(LiteralExpression.class, anon.members().get(1).value());
        assertEquals(6, anon.members().get(0).range().startByte());

        var named = assertInstanceOf(EnumDeclaration.class, ast.statements().get(1));
        assertEquals("Named", named.name());
    }

    @Test
    void enumMissingBrace() {
        var errors = errorMessages(parse("enum A\n"));
        assertTrue(errors.contains("Expected \"{\" after enum name."), () -> errors.toString());
    }

    @Test
    void enumNonIdentifierKey() {
        var errors = errorMessages(parse("enum {1}\n"));
        assertTrue(errors.contains("Expected identifier for enum key."), () -> errors.toString());
    }

    @Test
    void enumMissingValueExpression() {
        var errors = errorMessages(parse("enum {A = }\n"));
        assertTrue(errors.contains("Expected expression value after \"=\"."), () -> errors.toString());
    }

    @Test
    void enumUnclosedTerminates() {
        var errors = errorMessages(parse("enum {A\n"));
        assertTrue(errors.contains("Expected closing \"}\" for enum."), () -> errors.toString());
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
        assertEquals("int", function.parameters().get(1).type().sourceText());
        assertInstanceOf(LiteralExpression.class, function.parameters().get(2).defaultValue());
        assertEquals("x", assertInstanceOf(LiteralExpression.class,
                function.parameters().get(3).defaultValue()).sourceText().replace("\"", ""));
        assertEquals("String", function.returnType().sourceText());
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
        assertEquals(new Range(0, 8, new Point(0, 0), new Point(0, 8)), function.body().range());
        assertEquals(new Range(0, 8, new Point(0, 0), new Point(0, 8)), function.range());
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
        assertEquals("void", assertInstanceOf(ConstructorDeclaration.class, onlyStatement(typed))
                .returnType().sourceText());
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
        assertTrue(errors.contains("Expected function name after \"func\"."), () -> errors.toString());
    }

    @Test
    void functionMissingOpeningParen() {
        var errors = errorMessages(parse("func f: pass\n"));
        assertTrue(errors.contains("Expected opening \"(\" after function name."), () -> errors.toString());
    }

    @Test
    void functionParameterAfterRestIsReported() {
        var errors = errorMessages(parse("func f(...a, b): pass\n"));
        assertTrue(errors.contains("Cannot have parameters after the rest parameter."), () -> errors.toString());
    }

    @Test
    void restParameterCannotHaveDefaultValue() {
        var errors = errorMessages(parse("func f(...a = 1): pass\n"));
        assertTrue(errors.contains("The rest parameter cannot have a default value."), () -> errors.toString());
    }

    @Test
    void mandatoryParameterAfterOptionalIsReported() {
        var errors = errorMessages(parse("func f(a = 1, b): pass\n"));
        assertTrue(errors.contains("Cannot have mandatory parameters after optional parameters."),
                () -> errors.toString());
    }

    @Test
    void duplicateParameterIsReported() {
        var errors = errorMessages(parse("func f(a, a): pass\n"));
        assertTrue(errors.contains("Parameter with name \"a\" was already declared for this function."),
                () -> errors.toString());
    }

    @Test
    void functionMissingReturnType() {
        var errors = errorMessages(parse("func f() ->: pass\n"));
        assertTrue(errors.contains("Expected return type or \"void\" after \"->\"."), () -> errors.toString());
    }

    @Test
    void functionMissingIndentedBlock() {
        var errors = errorMessages(parse("func f():\nvar x = 1\n"));
        assertTrue(errors.contains("Expected indented block after function declaration."), () -> errors.toString());
    }

    @Test
    void functionBodyStatements() {
        var ast = cleanAst("func f():\n\tvar x = 1\n\tpass\n");
        var function = assertInstanceOf(FunctionDeclaration.class, onlyStatement(ast));
        assertEquals(2, function.body().statements().size());
        assertInstanceOf(VariableDeclaration.class, function.body().statements().get(0));
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
        var annotation = assertInstanceOf(AnnotationStatement.class, ast.statements().get(0));
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
        var annotation = assertInstanceOf(AnnotationStatement.class, ast.statements().get(0));
        assertEquals("rpc", annotation.name());
        assertEquals(2, annotation.arguments().size());
        assertEquals(31, annotation.range().endByte());
    }

    @Test
    void scriptLevelAnnotationPrecedesClassName() {
        var ast = cleanAst("@tool\nclass_name Foo\n");
        assertInstanceOf(AnnotationStatement.class, ast.statements().get(0));
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
        assertTrue(errors.contains("Expected expression as the annotation argument."), () -> errors.toString());
    }

    // -----------------------------------------------------------------------------------------
    // Types.
    // -----------------------------------------------------------------------------------------

    @Test
    void typeRefShapes() {
        var ast = cleanAst("var a: Array[int]\nvar b: Dictionary[String, int]\nvar c: Foo.Bar\n");
        var a = assertInstanceOf(VariableDeclaration.class, ast.statements().get(0));
        assertEquals("Array[int]", a.type().sourceText());
        assertEquals(new Range(7, 17, new Point(0, 7), new Point(0, 17)), a.type().range());

        var b = assertInstanceOf(VariableDeclaration.class, ast.statements().get(1));
        // Container type text keeps internal whitespace.
        assertEquals("Dictionary[String, int]", b.type().sourceText());

        assertEquals("Foo.Bar", assertInstanceOf(VariableDeclaration.class, ast.statements().get(2))
                .type().sourceText());
    }

    @Test
    void nestedContainerTypesAreRejected() {
        var errors = errorMessages(parse("var x: Array[Array[int]]\n"));
        assertTrue(errors.contains("Nested typed collections are not supported."), () -> errors.toString());
    }

    @Test
    void missingContainerElementType() {
        var errors = errorMessages(parse("var x: Array[]\n"));
        assertTrue(errors.contains("Expected type for collection after \"[\"."), () -> errors.toString());
    }

    @Test
    void missingContainerCloseBracket() {
        var errors = errorMessages(parse("var x: Array[int\n"));
        assertTrue(errors.contains("Expected closing \"]\" after collection type."), () -> errors.toString());
    }

    @Test
    void missingInnerTypeAfterPeriod() {
        var errors = errorMessages(parse("var x: Foo.\n"));
        assertTrue(errors.contains("Expected inner type name after \".\"."), () -> errors.toString());
    }

    @Test
    void voidIsNotAVariableType() {
        var errors = errorMessages(parse("var x: void\n"));
        assertTrue(errors.contains("\"void\" is only allowed for a function return type."), () -> errors.toString());
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
        assertInstanceOf(AttributePropertyStep.class, attribute.steps().get(0));
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
    // Phase 3 expression forms: grouping, arrays, dictionaries.
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
        // An empty argument slot reports and recovers at the comma; a missing comma reports the
        // closing parenthesis while keeping the successfully parsed arguments (vendor shape).
        var empty = parse("func f():\n\tfoo(,)\n");
        assertTrue(errorMessages(empty).contains("Expected expression as the function argument."));
        var missingComma = parse("func f():\n\tfoo(1 2)\n");
        assertTrue(errorMessages(missingComma).contains("Expected closing \")\" after call arguments."));
        var function = assertInstanceOf(FunctionDeclaration.class, missingComma.ast().statements().getFirst());
        var call = assertInstanceOf(CallExpression.class,
                assertInstanceOf(ExpressionStatement.class, function.body().statements().getFirst()).expression());
        assertEquals(1, call.arguments().size());
    }

    @Test
    void arrayLiteralShapes() {
        var ast = cleanAst("const A = [1, 2, 3]\nconst B = []\nconst C = [[1], [2]]\n");
        var a = assertInstanceOf(ArrayExpression.class,
                assertInstanceOf(VariableDeclaration.class, ast.statements().get(0)).value());
        assertEquals(3, a.elements().size());
        assertFalse(a.openEnded());
        assertEquals("1", assertInstanceOf(LiteralExpression.class, a.elements().getFirst()).sourceText());
        assertEquals(new Range(10, 19, new Point(0, 10), new Point(0, 19)), a.range());
        var b = assertInstanceOf(ArrayExpression.class,
                assertInstanceOf(VariableDeclaration.class, ast.statements().get(1)).value());
        assertEquals(0, b.elements().size());
        var c = assertInstanceOf(ArrayExpression.class,
                assertInstanceOf(VariableDeclaration.class, ast.statements().get(2)).value());
        assertEquals(2, c.elements().size());
        assertInstanceOf(ArrayExpression.class, c.elements().getFirst());
    }

    @Test
    void arrayTrailingCommaAndMultiline() {
        var ast = cleanAst("const A = [1, 2,]\nconst B = [\n\t1,\n\t2,\n]\n");
        var a = assertInstanceOf(ArrayExpression.class,
                assertInstanceOf(VariableDeclaration.class, ast.statements().get(0)).value());
        assertEquals(2, a.elements().size());
        var b = assertInstanceOf(ArrayExpression.class,
                assertInstanceOf(VariableDeclaration.class, ast.statements().get(1)).value());
        assertEquals(2, b.elements().size());
    }

    @Test
    void arrayRecovery() {
        // A failed element is reported and skipped; the surviving elements are kept (Godot
        // inserts no placeholder).
        var missing = parse("var x = [1, , 2]\n");
        assertTrue(errorMessages(missing).contains("Expected expression as array element."));
        var array = assertInstanceOf(ArrayExpression.class,
                assertInstanceOf(VariableDeclaration.class, missing.ast().statements().getFirst()).value());
        assertEquals(2, array.elements().size());

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
                assertInstanceOf(VariableDeclaration.class, ast.statements().get(0)).value());
        var aKey = assertInstanceOf(LiteralExpression.class, a.entries().getFirst().key());
        // Lua-style identifier keys normalize to synthesized StringName literals.
        assertEquals("string_name", aKey.kind());
        assertEquals("&\"x\"", aKey.sourceText());
        var b = assertInstanceOf(DictionaryExpression.class,
                assertInstanceOf(VariableDeclaration.class, ast.statements().get(1)).value());
        var bKey = assertInstanceOf(LiteralExpression.class, b.entries().getFirst().key());
        assertEquals("string_name", bKey.kind());
        assertEquals("&\"name\"", bKey.sourceText());
    }

    @Test
    void mixedDictionaryStylesAreRejected() {
        // Python style decided first; the `=` separator is rejected. The vendor consumes one
        // extra token after the wrong separator, so the value is lost too (mirrored quirk).
        var pythonFirst = parse("var d = {\"a\": 1, b = 2}\n");
        var pythonErrors = errorMessages(pythonFirst);
        assertTrue(pythonErrors.contains("Expected \":\" after dictionary key. Mixing dictionary styles is not allowed."));
        assertTrue(pythonErrors.contains("Expected expression as dictionary value."));
        var dict = assertInstanceOf(DictionaryExpression.class,
                assertInstanceOf(VariableDeclaration.class, pythonFirst.ast().statements().getFirst()).value());
        assertEquals(2, dict.entries().size());
        var placeholder = assertInstanceOf(LiteralExpression.class, dict.entries().get(1).value());
        assertEquals("null", placeholder.kind());

        var luaFirst = parse("var e = {a = 1, \"b\": 2}\n");
        assertTrue(errorMessages(luaFirst)
                .contains("Expected \"=\" after dictionary key. Mixing dictionary styles is not allowed."));
    }

    @Test
    void dictionaryRecovery() {
        // A key-only entry reports the separator and value errors; the pair is kept with a
        // zero-width null placeholder (Godot's dummy recovery literal).
        var keyOnly = parse("var d = {name}\n");
        var keyOnlyErrors = errorMessages(keyOnly);
        assertTrue(keyOnlyErrors.contains("Expected \":\" or \"=\" after dictionary key."));
        assertTrue(keyOnlyErrors.contains("Expected \":\" after dictionary key."));
        assertTrue(keyOnlyErrors.contains("Expected expression as dictionary value."));
        var keyOnlyDict = assertInstanceOf(DictionaryExpression.class,
                assertInstanceOf(VariableDeclaration.class, keyOnly.ast().statements().getFirst()).value());
        assertEquals(1, keyOnlyDict.entries().size());
        assertInstanceOf(IdentifierExpression.class, keyOnlyDict.entries().getFirst().key());
        assertEquals("null", assertInstanceOf(LiteralExpression.class,
                keyOnlyDict.entries().getFirst().value()).kind());

        var missingValue = parse("var e = {\"a\": }\n");
        assertTrue(errorMessages(missingValue).contains("Expected expression as dictionary value."));
        assertTrue(errorMessages(parse("var f = {\"a\": 1\n"))
                .contains("Expected closing \"}\" after dictionary elements."));
    }

    @Test
    void dictionaryMissingValueEntrySpansGroupedKey() {
        // Recovery branch range: the entry spans the key's whole consumed span, including a
        // grouping's closing parenthesis (review-driven range completion on an illegal source).
        var result = parse("var d = {(a)}\n");
        assertTrue(errorMessages(result).contains("Expected expression as dictionary value."));
        var dict = assertInstanceOf(DictionaryExpression.class,
                assertInstanceOf(VariableDeclaration.class, result.ast().statements().getFirst()).value());
        assertEquals(new Range(9, 12, new Point(0, 9), new Point(0, 12)), dict.entries().getFirst().range());
    }

    @Test
    void dictionaryMissingSeparatorKeepsKeyAndValue() {
        // `{1 2}`: the invalid separator leaves the style undecided-then-defaulted; Godot still
        // parses the following token as the value and keeps the pair (vendor `decided_style`
        // is set unconditionally, gdscript_parser.cpp:3285).
        var result = parse("var d = {1 2}\n");
        var errors = errorMessages(result);
        assertTrue(errors.contains("Expected \":\" or \"=\" after dictionary key."));
        assertTrue(errors.contains("Expected \":\" after dictionary key."));
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
    // Phase 3 expression forms: get_node, preload, await, super.
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
        // §5.7 (ii): legal in Godot, rejected by the tree-sitter path — no parity, Godot shapes.
        var ast = cleanAst("var a = $%Child\nvar b = $Root/%Child\n");
        assertEquals("$%Child", assertInstanceOf(GetNodeExpression.class,
                assertInstanceOf(VariableDeclaration.class, ast.statements().get(0)).value()).sourceText());
        assertEquals("$Root/%Child", assertInstanceOf(GetNodeExpression.class,
                assertInstanceOf(VariableDeclaration.class, ast.statements().get(1)).value()).sourceText());
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
                assertInstanceOf(VariableDeclaration.class, ast.statements().get(0)).value());
        assertEquals("\"res://a.gd\"", assertInstanceOf(LiteralExpression.class, p.path()).sourceText());
        assertEquals(new Range(10, 31, new Point(0, 10), new Point(0, 31)), p.range());
        // The path is a general expression; constant/resource checks are cut (later Godot phases).
        assertInstanceOf(IdentifierExpression.class,
                assertInstanceOf(PreloadExpression.class,
                        assertInstanceOf(VariableDeclaration.class, ast.statements().get(1)).value()).path());
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
                assertInstanceOf(ExpressionStatement.class, function.body().statements().get(0)).expression());
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
        assertTrue(errorMessages(parse("func f():\n\tsuper.1\n"))
                .contains("Expected function name after \".\"."));
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

    // -----------------------------------------------------------------------------------------
    // Phase 3 expression forms: as/is, ternary, not-in, unary family, assignments.
    // -----------------------------------------------------------------------------------------

    @Test
    void castShapes() {
        var ast = cleanAst("const A = x as int\nconst B = y as Array[int]\n");
        var cast = assertInstanceOf(CastExpression.class,
                assertInstanceOf(VariableDeclaration.class, ast.statements().get(0)).value());
        assertInstanceOf(IdentifierExpression.class, cast.value());
        assertEquals("int", cast.targetType().sourceText());
        assertEquals(cast.value().range().startByte(), cast.range().startByte());
        assertEquals(cast.targetType().range().endByte(), cast.range().endByte());
        assertEquals("Array[int]", assertInstanceOf(CastExpression.class,
                assertInstanceOf(VariableDeclaration.class, ast.statements().get(1)).value()).targetType().sourceText());
    }

    @Test
    void castBindsWeakerThanAdditionLikeGodot() {
        // Godot anchors `1 + 2 as int` as `(1 + 2) as int` (PREC_CAST below addition); the
        // tree-sitter path groups `1 + (2 as int)` (registered §5.8 divergence, no parity).
        var ast = cleanAst("const A = 1 + 2 as int\n");
        var cast = assertInstanceOf(CastExpression.class,
                assertInstanceOf(VariableDeclaration.class, onlyStatement(ast)).value());
        assertEquals("+", assertInstanceOf(BinaryExpression.class, cast.value()).operator());
        assertEquals("int", cast.targetType().sourceText());
    }

    @Test
    void typeTestShapes() {
        var ast = cleanAst("var a = x is Node\nvar b = y is not int\n");
        var plain = assertInstanceOf(TypeTestExpression.class,
                assertInstanceOf(VariableDeclaration.class, ast.statements().get(0)).value());
        assertFalse(plain.negated());
        assertEquals("Node", plain.targetType().sourceText());
        var negated = assertInstanceOf(TypeTestExpression.class,
                assertInstanceOf(VariableDeclaration.class, ast.statements().get(1)).value());
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
        var ternary = assertInstanceOf(ConditionalExpression.class,
                assertInstanceOf(VariableDeclaration.class, onlyStatement(ast)).value());
        assertEquals("c", assertInstanceOf(IdentifierExpression.class, ternary.condition()).name());
        assertEquals("1", assertInstanceOf(LiteralExpression.class, ternary.left()).sourceText());
        assertEquals("2", assertInstanceOf(LiteralExpression.class, ternary.right()).sourceText());
    }

    @Test
    void ternaryNestsOnFalseBranch() {
        var ast = cleanAst("const A = 1 if a else 2 if b else 3\n");
        var outer = assertInstanceOf(ConditionalExpression.class,
                assertInstanceOf(VariableDeclaration.class, onlyStatement(ast)).value());
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
        var binary = assertInstanceOf(BinaryExpression.class,
                assertInstanceOf(VariableDeclaration.class, onlyStatement(ast)).value());
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
        // ported set (plan §5.6); the invalid target produces an AssignmentExpression for the
        // analyzer to reject, with no parser diagnostic.
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
        // `if` is deliberately outside the sync set; the NEWLINE terminates synchronization here.
        var result = parse("func f():\n\tif\n\tpass\n");
        assertTrue(errorMessages(result).contains("Expected statement, found \"if\" instead."));
        var function = assertInstanceOf(FunctionDeclaration.class, result.ast().statements().getFirst());
        assertTrue(function.body().statements().stream().anyMatch(PassStatement.class::isInstance));
    }

    @Test
    void statementLevelAnnotationAttachesToNextStatement() {
        var ast = cleanAst("func f():\n\t@warning_ignore(\"unused_variable\")\n\tvar x = 1\n");
        var function = assertInstanceOf(FunctionDeclaration.class, onlyStatement(ast));
        assertEquals(2, function.body().statements().size());
        var annotation = assertInstanceOf(AnnotationStatement.class, function.body().statements().get(0));
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
        assertTrue(errors.contains("Unexpected \"Literal\" in class body."), () -> errors.toString());
    }

    @Test
    void unexpectedIdentifierInClassBody() {
        var errors = errorMessages(parse("foo\n"));
        assertTrue(errors.contains("Unexpected identifier \"foo\" in class body."), () -> errors.toString());
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
        assertEquals("match", assertInstanceOf(FunctionDeclaration.class, ast.statements().get(0)).name());
        assertEquals("when", assertInstanceOf(VariableDeclaration.class, ast.statements().get(1)).name());
        assertEquals("match", assertInstanceOf(SignalStatement.class, ast.statements().get(2))
                .parameters().getFirst().name());
    }

    @Test
    void builtinConstantsAreUsableAsNames() {
        var ast = cleanAst("var PI = 1\nconst A = Vector2.INF\n");
        assertEquals("PI", assertInstanceOf(VariableDeclaration.class, ast.statements().get(0)).name());
        var attribute = assertInstanceOf(AttributeExpression.class,
                assertInstanceOf(VariableDeclaration.class, ast.statements().get(1)).value());
        assertEquals("INF", assertInstanceOf(AttributePropertyStep.class, attribute.steps().getFirst()).name());
    }

    @Test
    void keywordsAreUsableAsAttributeNames() {
        var ast = cleanAst("var ok = \"abc\".match(\"a*\")\nvar cls = obj.class\n");
        var call = assertInstanceOf(AttributeExpression.class,
                assertInstanceOf(VariableDeclaration.class, ast.statements().get(0)).value());
        assertInstanceOf(AttributeCallStep.class, call.steps().getFirst());
        var property = assertInstanceOf(AttributeExpression.class,
                assertInstanceOf(VariableDeclaration.class, ast.statements().get(1)).value());
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
        assertInstanceOf(VariableDeclaration.class, ast.statements().get(0));
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
        assertTrue(errors.contains("\"extends\" can only be used once."), () -> errors.toString());
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
        var function = assertInstanceOf(FunctionDeclaration.class, ast.statements().get(0));
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
        // Each pair counts twice (the annotation-only round and the failed declaration), so 60
        // pairs pass the 102-failure threshold.
        var source = "func f():\n" + "\t@x\n\tvar =\n".repeat(60);
        var errors = errorMessages(parse(source));
        assertTrue(errors.contains("Too many statement errors."), () -> "count=" + errors.size());
    }

    @Test
    void signalRejectsRestParameter() {
        var errors = errorMessages(parse("signal s(...a)\n"));
        assertTrue(errors.contains("Expected signal parameter name."), () -> errors.toString());
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
                () -> errors.toString());
    }

    @Test
    void danglingAttributeDotBeforeNewlineDegradesToBase() {
        var result = parse("var x = a.\nvar y = 2\n");
        assertTrue(errorMessages(result).contains("Expected identifier after \".\" for attribute access."));
        // The failed member access degrades to the base expression and the sibling parses.
        var x = assertInstanceOf(VariableDeclaration.class, result.ast().statements().get(0));
        assertEquals("a", assertInstanceOf(IdentifierExpression.class, x.value()).name());
        assertInstanceOf(VariableDeclaration.class, result.ast().statements().get(1));
    }

    @Test
    void missingMemberNameDoesNotBreakChainRecovery() {
        // After a failed member name, a following call/subscript still folds onto the previous
        // property step (the recovery shape of the former per-infix implementation).
        var result = parse("var x = a.b.(1)\nvar y = a.b.[0]\nvar z = a.b. .c\n");
        assertEquals(3, errorMessages(result).size());

        var x = assertInstanceOf(VariableDeclaration.class, result.ast().statements().get(0));
        var xChain = assertInstanceOf(AttributeExpression.class, x.value());
        assertEquals("a", assertInstanceOf(IdentifierExpression.class, xChain.base()).name());
        assertEquals(1, xChain.steps().size());
        assertEquals("b", assertInstanceOf(AttributeCallStep.class, xChain.steps().getFirst()).name());

        var y = assertInstanceOf(VariableDeclaration.class, result.ast().statements().get(1));
        var yChain = assertInstanceOf(AttributeExpression.class, y.value());
        assertEquals(1, yChain.steps().size());
        assertInstanceOf(AttributeSubscriptStep.class, yChain.steps().getFirst());

        var z = assertInstanceOf(VariableDeclaration.class, result.ast().statements().get(2));
        var zChain = assertInstanceOf(AttributeExpression.class, z.value());
        assertEquals("a", assertInstanceOf(IdentifierExpression.class, zChain.base()).name());
        assertEquals(2, zChain.steps().size());
        assertEquals("b", assertInstanceOf(AttributePropertyStep.class, zChain.steps().get(0)).name());
        assertEquals("c", assertInstanceOf(AttributePropertyStep.class, zChain.steps().get(1)).name());
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
        assertInstanceOf(AttributeCallStep.class, chain.steps().get(0));
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
        var extendsStatement = assertInstanceOf(ExtendsStatement.class, ast.statements().get(0));
        // UTF-8 slices must keep the multi-byte path intact.
        assertEquals("\"res://场景.gd\"", extendsStatement.target());

        var variable = assertInstanceOf(VariableDeclaration.class, ast.statements().get(1));
        assertEquals("数据", variable.name());
        // `extends "res://场景.gd"` is 25 UTF-8 bytes + '\n', so `var` starts at byte 26.
        assertEquals(26, variable.range().startByte());
        assertEquals(new Point(1, 0), variable.range().startPoint());
    }
}
