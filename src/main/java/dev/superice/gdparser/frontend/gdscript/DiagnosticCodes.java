package dev.superice.gdparser.frontend.gdscript;

import dev.superice.gdparser.frontend.ast.AnnotationStatement;
import dev.superice.gdparser.frontend.ast.AssertStatement;
import dev.superice.gdparser.frontend.ast.Block;
import dev.superice.gdparser.frontend.ast.BreakStatement;
import dev.superice.gdparser.frontend.ast.CastExpression;
import dev.superice.gdparser.frontend.ast.ClassDeclaration;
import dev.superice.gdparser.frontend.ast.ClassNameStatement;
import dev.superice.gdparser.frontend.ast.CommentStatement;
import dev.superice.gdparser.frontend.ast.ConstructorDeclaration;
import dev.superice.gdparser.frontend.ast.ContinueStatement;
import dev.superice.gdparser.frontend.ast.DictEntry;
import dev.superice.gdparser.frontend.ast.DictionaryExpression;
import dev.superice.gdparser.frontend.ast.ElifClause;
import dev.superice.gdparser.frontend.ast.EnumDeclaration;
import dev.superice.gdparser.frontend.ast.EnumMember;
import dev.superice.gdparser.frontend.ast.ErrorStatement;
import dev.superice.gdparser.frontend.ast.ExpressionStatement;
import dev.superice.gdparser.frontend.ast.ExtendsStatement;
import dev.superice.gdparser.frontend.ast.ForStatement;
import dev.superice.gdparser.frontend.ast.FunctionDeclaration;
import dev.superice.gdparser.frontend.ast.IfStatement;
import dev.superice.gdparser.frontend.ast.LambdaExpression;
import dev.superice.gdparser.frontend.ast.MatchSection;
import dev.superice.gdparser.frontend.ast.MatchStatement;
import dev.superice.gdparser.frontend.ast.Node;
import dev.superice.gdparser.frontend.ast.Parameter;
import dev.superice.gdparser.frontend.ast.RecoveryLink;
import dev.superice.gdparser.frontend.ast.RegionDirectiveStatement;
import dev.superice.gdparser.frontend.ast.ReturnStatement;
import dev.superice.gdparser.frontend.ast.SignalStatement;
import dev.superice.gdparser.frontend.ast.Statement;
import dev.superice.gdparser.frontend.ast.TypeRef;
import dev.superice.gdparser.frontend.ast.TypeTestExpression;
import dev.superice.gdparser.frontend.ast.UnknownStatement;
import dev.superice.gdparser.frontend.ast.VariableDeclaration;
import dev.superice.gdparser.frontend.ast.WhileStatement;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/// Closed registry of stable diagnostic codes and legal `(code, ownerType, facet)` recovery
/// pairings for the pure-Java GDScript parser. Consumers (e.g. GDCC) use these codes to correlate
/// diagnostics with the AST construct facets they damage.
public final class DiagnosticCodes {

    private DiagnosticCodes() {
    }

    // Missing closers: zero-width diagnostics at the failure point, carrying a RecoveryLink.
    public static final String MISSING_CLOSER_GROUPING = "parse.missing_closer.grouping";
    public static final String MISSING_CLOSER_ARRAY = "parse.missing_closer.array";
    public static final String MISSING_CLOSER_DICTIONARY = "parse.missing_closer.dictionary";
    public static final String MISSING_CLOSER_CALL = "parse.missing_closer.call";
    public static final String MISSING_CLOSER_SUBSCRIPT = "parse.missing_closer.subscript";
    public static final String MISSING_CLOSER_ANNOTATION = "parse.missing_closer.annotation";
    public static final String MISSING_CLOSER_PRELOAD = "parse.missing_closer.preload";
    public static final String MISSING_CLOSER_ASSERT = "parse.missing_closer.assert";
    public static final String MISSING_CLOSER_PARAMETER_LIST = "parse.missing_closer.parameter_list";
    public static final String MISSING_CLOSER_TYPE_BRACKET = "parse.missing_closer.type_bracket";
    public static final String MISSING_CLOSER_MATCH_ARRAY_PATTERN = "parse.missing_closer.match_array_pattern";
    public static final String MISSING_CLOSER_MATCH_DICTIONARY_PATTERN = "parse.missing_closer.match_dictionary_pattern";
    public static final String MISSING_CLOSER_ENUM = "parse.missing_closer.enum";

    // Mismatched / unexpected closers.
    public static final String MISMATCHED_CLOSER_GROUPING = "parse.mismatched_closer.grouping";
    public static final String MISMATCHED_CLOSER_ARRAY = "parse.mismatched_closer.array";
    public static final String MISMATCHED_CLOSER_DICTIONARY = "parse.mismatched_closer.dictionary";
    public static final String MISMATCHED_CLOSER_CALL = "parse.mismatched_closer.call";
    public static final String MISMATCHED_CLOSER_SUBSCRIPT = "parse.mismatched_closer.subscript";
    public static final String MISMATCHED_CLOSER_ANNOTATION = "parse.mismatched_closer.annotation";
    public static final String MISMATCHED_CLOSER_PRELOAD = "parse.mismatched_closer.preload";
    public static final String MISMATCHED_CLOSER_ASSERT = "parse.mismatched_closer.assert";
    public static final String MISMATCHED_CLOSER_PARAMETER_LIST = "parse.mismatched_closer.parameter_list";
    public static final String MISMATCHED_CLOSER_TYPE_BRACKET = "parse.mismatched_closer.type_bracket";
    public static final String MISMATCHED_CLOSER_MATCH_ARRAY_PATTERN = "parse.mismatched_closer.match_array_pattern";
    public static final String MISMATCHED_CLOSER_MATCH_DICTIONARY_PATTERN = "parse.mismatched_closer.match_dictionary_pattern";
    public static final String MISMATCHED_CLOSER_ENUM = "parse.mismatched_closer.enum";
    public static final String UNEXPECTED_CLOSER = "parse.unexpected_closer";

    // Opened expression slots whose content failed.
    public static final String SLOT_VARIABLE_INITIALIZER = "parse.missing_expression.variable_initializer";
    public static final String SLOT_CONSTANT_VALUE = "parse.missing_expression.constant_value";
    public static final String SLOT_PARAMETER_DEFAULT = "parse.missing_expression.parameter_default";
    public static final String SLOT_ANNOTATION_ARGUMENT = "parse.missing_expression.annotation_argument";
    public static final String SLOT_ARRAY_ELEMENT = "parse.missing_expression.array_element";
    public static final String SLOT_DICTIONARY_KEY = "parse.missing_expression.dictionary_key";
    public static final String SLOT_DICTIONARY_VALUE = "parse.missing_expression.dictionary_value";
    public static final String SLOT_ASSERT_CONDITION = "parse.missing_expression.assert_condition";
    public static final String SLOT_ASSERT_MESSAGE = "parse.missing_expression.assert_message";
    public static final String SLOT_MATCH_VALUE = "parse.missing_expression.match_value";
    public static final String SLOT_MATCH_GUARD = "parse.missing_expression.match_guard";
    public static final String SLOT_MATCH_PATTERN = "parse.missing_expression.match_pattern";
    public static final String SLOT_DICTIONARY_PATTERN_KEY = "parse.missing_expression.dictionary_pattern_key";
    public static final String SLOT_ENUM_VALUE = "parse.missing_expression.enum_value";
    public static final String SLOT_IF_CONDITION = "parse.missing_expression.if_condition";
    public static final String SLOT_ELIF_CONDITION = "parse.missing_expression.elif_condition";
    public static final String SLOT_WHILE_CONDITION = "parse.missing_expression.while_condition";
    public static final String SLOT_FOR_ITERABLE = "parse.missing_expression.for_iterable";
    public static final String SLOT_GROUPING = "parse.missing_expression.grouping";
    public static final String SLOT_SUBSCRIPT_INDEX = "parse.missing_expression.subscript_index";
    public static final String SLOT_CALL_ARGUMENT = "parse.missing_expression.call_argument";
    public static final String SLOT_UNARY_OPERAND = "parse.missing_expression.unary_operand";
    public static final String SLOT_BINARY_RIGHT = "parse.missing_expression.binary_right";
    public static final String SLOT_TERNARY_CONDITION = "parse.missing_expression.ternary_condition";
    public static final String SLOT_TERNARY_ELSE = "parse.missing_expression.ternary_else";
    public static final String SLOT_AWAIT_OPERAND = "parse.missing_expression.await_operand";
    public static final String SLOT_PRELOAD_PATH = "parse.missing_expression.preload_path";
    public static final String SLOT_ASSIGNMENT_VALUE = "parse.missing_expression.assignment_value";
    public static final String SLOT_RETURN_VALUE = "parse.missing_expression.return_value";

    // Explicit type slots whose type specifier failed or is missing.
    public static final String MISSING_TYPE_VARIABLE = "parse.missing_type.variable";
    public static final String MISSING_TYPE_CONSTANT = "parse.missing_type.constant";
    public static final String MISSING_TYPE_PARAMETER = "parse.missing_type.parameter";
    public static final String MISSING_TYPE_LAMBDA_PARAMETER = "parse.missing_type.lambda_parameter";
    public static final String MISSING_TYPE_SIGNAL_PARAMETER = "parse.missing_type.signal_parameter";
    public static final String MISSING_TYPE_RETURN_TYPE = "parse.missing_type.return_type";
    public static final String MISSING_TYPE_FOR_ITERATOR = "parse.missing_type.for_iterator";
    public static final String MISSING_TYPE_CAST = "parse.missing_type.cast";
    public static final String MISSING_TYPE_TYPE_TEST = "parse.missing_type.type_test";
    public static final String MISSING_TYPE_GENERIC_ARGUMENT = "parse.missing_type.generic_argument";

    // Missing separators.
    public static final String MISSING_SEPARATOR_COMMA = "parse.missing_separator.comma";
    public static final String MISSING_SEPARATOR_DICTIONARY = "parse.missing_separator.dictionary_separator";
    public static final String MISSING_SEPARATOR_MATCH_COLON = "parse.missing_separator.match_colon";
    public static final String MISSING_SEPARATOR_SUITE_COLON = "parse.missing_separator.suite_colon";
    public static final String MISSING_SEPARATOR_FOR_IN = "parse.missing_separator.for_in";

    // Statement trailing garbage.
    public static final String STATEMENT_TRAILING = "parse.statement.trailing";

    // Attribute/super recovery.
    public static final String ATTRIBUTE_MISSING_NAME = "parse.attribute.missing_name";
    public static final String SUPER_MISSING_NAME = "parse.super.missing_name";
    public static final String SUPER_MISSING_CALL = "parse.super.missing_call";
    public static final String SUPER_MISSING_ACCESS = "parse.super.missing_access";

    // Signature recovery.
    public static final String SIGNATURE_MANDATORY_AFTER_OPTIONAL = "parse.signature.mandatory_after_optional";
    public static final String SIGNATURE_REST_WITH_DEFAULT = "parse.signature.rest_with_default";
    public static final String SIGNATURE_PARAMETER_AFTER_REST = "parse.signature.parameter_after_rest";
    public static final String SIGNATURE_DUPLICATE_PARAMETER = "parse.signature.duplicate_parameter";
    public static final String SIGNATURE_SIGNAL_PARAMETER_DEFAULT = "parse.signature.signal_parameter_default";

    // Dictionary literal and node-path recovery.
    public static final String DICTIONARY_MIXED_STYLE = "parse.dictionary.mixed_style";
    public static final String DICTIONARY_INVALID_LUA_KEY = "parse.dictionary.invalid_lua_key";
    public static final String NODE_PATH_INVALID = "parse.node_path.invalid";

    // Property recovery.
    public static final String PROPERTY_DUPLICATE_GETTER = "parse.property.duplicate_getter";
    public static final String PROPERTY_DUPLICATE_SETTER = "parse.property.duplicate_setter";
    public static final String PROPERTY_INVALID_ACCESSOR = "parse.property.invalid_accessor";

    // Match pattern recovery.
    public static final String MATCH_BIND_MISSING_NAME = "parse.match.bind_missing_name";
    public static final String MATCH_REST_MISPLACED = "parse.match.rest_misplaced";
    public static final String MATCH_REST_NOT_LAST = "parse.match.rest_not_last";

    // Assignment, call, lambda, function, class, annotation, declaration, and loop header errors.
    public static final String ASSIGNMENT_DISALLOWED_IN_EXPRESSION = "parse.assignment.disallowed_in_expression";
    public static final String ASSIGNMENT_INVALID_TARGET = "parse.assignment.invalid_target";
    public static final String CALL_INVALID_CALLEE = "parse.call.invalid_callee";
    public static final String LAMBDA_BARE_STATEMENT = "parse.lambda.bare_statement";
    public static final String FUNCTION_INVALID_STATIC_INIT = "parse.function.invalid_static_init";
    public static final String CLASS_DUPLICATE_EXTENDS = "parse.class.duplicate_extends";
    public static final String ANNOTATION_DANGLING = "parse.annotation.dangling";
    public static final String ANNOTATION_INVALID_TARGET = "parse.annotation.invalid_target";
    public static final String ANNOTATION_INVALID_LEVEL = "parse.annotation.invalid_level";
    public static final String DECLARATION_MISSING_NAME = "parse.declaration.missing_name";
    public static final String FOR_MISSING_ITERATOR = "parse.for.missing_iterator";
    public static final String FOR_MISSING_IN = "parse.for.missing_in";

    // Explicit standalone-allowed diagnostics (may have `recovery == null` when no construct owns them).
    public static final String LEXICAL_ERROR = "parse.lexical.error";
    public static final String NESTING_DEPTH_EXCEEDED = "parse.nesting.depth_exceeded";
    public static final String STATEMENT_TOO_MANY_ERRORS = "parse.statement.too_many_errors";
    public static final String SYNTAX_UNEXPECTED_TOKEN = "parse.syntax.unexpected_token";

    private static final Set<String> STANDALONE_ALLOWED_CODES = Set.of(
            LEXICAL_ERROR,
            UNEXPECTED_CLOSER,
            SYNTAX_UNEXPECTED_TOKEN,
            NESTING_DEPTH_EXCEEDED,
            STATEMENT_TOO_MANY_ERRORS
    );

    /// Expression-bearing facets that can embed general sub-expressions (and therefore host
    /// nested expression container/slot/operator diagnostics).
    private static final Set<String> EXPRESSION_HOST_FACETS = Set.of(
            "return.value",
            "expression",
            "if.condition",
            "elif.condition",
            "while.condition",
            "for.iterable",
            "assert.arguments",
            "assert.condition",
            "assert.message",
            "match.value",
            "match.pattern",
            "match.guard",
            "match.section",
            "annotation.arguments",
            "variable.initializer",
            "variable.property",
            "constant.value",
            "parameter.default",
            "enum.value",
            "match.dictionary_pattern",
            "dictionary.entry",
            "function.signature",
            "signal.parameter",
            "suite.body"
    );

    /// Type-bearing facets that can host type-bracket and generic type argument diagnostics.
    private static final Set<String> TYPE_HOST_FACETS = Set.of(
            "type.argument",
            "variable.type",
            "constant.type",
            "parameter.type",
            "function.return_type",
            "function.signature",
            "for.iterator",
            "signal.parameter",
            "match.section",
            "suite.body"
    );

    private static final Map<String, Set<Class<? extends Node>>> FACET_ALLOWED_OWNERS;
    private static final Map<String, Set<String>> CODE_ALLOWED_FACETS;
    private static final Set<String> REGISTERED_CODES;

    static {
        var facets = new HashMap<String, Set<Class<? extends Node>>>();
        // Existing parser facets
        facets.put("return.value", Set.of(ReturnStatement.class));
        facets.put("expression", Set.of(ExpressionStatement.class));
        facets.put("if.condition", Set.of(IfStatement.class));
        facets.put("elif.condition", Set.of(ElifClause.class));
        facets.put("while.condition", Set.of(WhileStatement.class));
        facets.put("for.iterable", Set.of(ForStatement.class));
        facets.put("assert.arguments", Set.of(AssertStatement.class));
        facets.put("assert.condition", Set.of(AssertStatement.class));
        facets.put("assert.message", Set.of(AssertStatement.class));
        facets.put("match.value", Set.of(MatchStatement.class));
        facets.put("match.pattern", Set.of(MatchSection.class));
        facets.put("match.guard", Set.of(MatchSection.class));
        facets.put("annotation.arguments", Set.of(AnnotationStatement.class));
        facets.put("variable.initializer", Set.of(VariableDeclaration.class));
        facets.put("constant.value", Set.of(VariableDeclaration.class));
        facets.put("parameter.default", Set.of(Parameter.class));
        facets.put("enum.value", Set.of(EnumMember.class));
        facets.put("enum.body", Set.of(EnumDeclaration.class));

        // Structural, declaration, signature, and pattern facets
        facets.put("statement.trailing", Set.of(Statement.class));
        facets.put("statement.control", Set.of(BreakStatement.class, ContinueStatement.class, ReturnStatement.class));
        facets.put("variable.property", Set.of(VariableDeclaration.class));
        facets.put("annotation.target", Set.of(AnnotationStatement.class));
        facets.put("class.extends", Set.of(ClassDeclaration.class, ClassNameStatement.class, ExtendsStatement.class));
        facets.put("class.body", Set.of(ClassDeclaration.class));
        facets.put("suite.body", Set.of(
                IfStatement.class,
                ElifClause.class,
                WhileStatement.class,
                ForStatement.class,
                FunctionDeclaration.class,
                ConstructorDeclaration.class,
                LambdaExpression.class,
                MatchSection.class
        ));
        facets.put("parameter.type", Set.of(Parameter.class));
        facets.put("variable.type", Set.of(VariableDeclaration.class));
        facets.put("constant.type", Set.of(VariableDeclaration.class));
        facets.put("function.return_type", Set.of(FunctionDeclaration.class, ConstructorDeclaration.class, LambdaExpression.class));
        facets.put("function.signature", Set.of(FunctionDeclaration.class, ConstructorDeclaration.class, LambdaExpression.class));
        facets.put("function.name", Set.of(FunctionDeclaration.class));
        facets.put("signal.parameter", Set.of(SignalStatement.class, Parameter.class));
        facets.put("for.iterator", Set.of(ForStatement.class));
        facets.put("declaration.name", Set.of(
                VariableDeclaration.class,
                FunctionDeclaration.class,
                SignalStatement.class,
                ClassDeclaration.class,
                ClassNameStatement.class,
                EnumDeclaration.class,
                EnumMember.class,
                Parameter.class
        ));
        facets.put("match.section", Set.of(MatchSection.class, MatchStatement.class));
        facets.put("match.dictionary_pattern", Set.of(DictionaryExpression.class, MatchSection.class, MatchStatement.class));
        facets.put("dictionary.entry", Set.of(DictEntry.class, DictionaryExpression.class));
        facets.put("type.argument", Set.of(
                TypeRef.class,
                VariableDeclaration.class,
                Parameter.class,
                FunctionDeclaration.class,
                ConstructorDeclaration.class,
                LambdaExpression.class,
                ForStatement.class,
                CastExpression.class,
                TypeTestExpression.class
        ));
        FACET_ALLOWED_OWNERS = Map.copyOf(facets);

        var pairings = new HashMap<String, Set<String>>();
        // Expression-embedded closers, sub-slots, and operator diagnostics
        registerPairings(pairings, EXPRESSION_HOST_FACETS,
                MISSING_CLOSER_GROUPING, MISMATCHED_CLOSER_GROUPING,
                MISSING_CLOSER_ARRAY, MISMATCHED_CLOSER_ARRAY,
                MISSING_CLOSER_DICTIONARY, MISMATCHED_CLOSER_DICTIONARY,
                MISSING_CLOSER_CALL, MISMATCHED_CLOSER_CALL,
                MISSING_CLOSER_SUBSCRIPT, MISMATCHED_CLOSER_SUBSCRIPT,
                MISSING_CLOSER_PRELOAD, MISMATCHED_CLOSER_PRELOAD,
                SLOT_ARRAY_ELEMENT,
                SLOT_DICTIONARY_KEY,
                SLOT_DICTIONARY_VALUE,
                SLOT_GROUPING,
                SLOT_SUBSCRIPT_INDEX,
                SLOT_CALL_ARGUMENT,
                SLOT_UNARY_OPERAND,
                SLOT_BINARY_RIGHT,
                SLOT_TERNARY_CONDITION,
                SLOT_TERNARY_ELSE,
                SLOT_AWAIT_OPERAND,
                SLOT_PRELOAD_PATH,
                SLOT_ASSIGNMENT_VALUE,
                ATTRIBUTE_MISSING_NAME,
                SUPER_MISSING_NAME,
                SUPER_MISSING_CALL,
                SUPER_MISSING_ACCESS,
                ASSIGNMENT_DISALLOWED_IN_EXPRESSION,
                ASSIGNMENT_INVALID_TARGET,
                CALL_INVALID_CALLEE,
                MISSING_TYPE_CAST,
                MISSING_TYPE_TYPE_TEST,
                DICTIONARY_MIXED_STYLE,
                DICTIONARY_INVALID_LUA_KEY,
                NODE_PATH_INVALID
        );

        // Comma and dictionary separators
        var commaFacets = new HashSet<>(EXPRESSION_HOST_FACETS);
        commaFacets.add("function.signature");
        commaFacets.add("signal.parameter");
        commaFacets.add("type.argument");
        commaFacets.add("match.section");
        pairings.put(MISSING_SEPARATOR_COMMA, Set.copyOf(commaFacets));

        var dictSeparatorFacets = new HashSet<>(EXPRESSION_HOST_FACETS);
        dictSeparatorFacets.add("match.dictionary_pattern");
        dictSeparatorFacets.add("dictionary.entry");
        pairings.put(MISSING_SEPARATOR_DICTIONARY, Set.copyOf(dictSeparatorFacets));

        // Type brackets & generic arguments can appear in type facets or inside expression-embedded `is`/`as`/lambdas
        var typeOrExprFacets = new HashSet<>(TYPE_HOST_FACETS);
        typeOrExprFacets.addAll(EXPRESSION_HOST_FACETS);
        registerPairings(pairings, typeOrExprFacets,
                MISSING_CLOSER_TYPE_BRACKET,
                MISMATCHED_CLOSER_TYPE_BRACKET,
                MISSING_TYPE_GENERIC_ARGUMENT
        );

        // Slot-specific codes (also hostable by enclosing property accessor fallback `variable.property`)
        pairings.put(SLOT_VARIABLE_INITIALIZER, Set.of("variable.initializer", "variable.property"));
        pairings.put(SLOT_CONSTANT_VALUE, Set.of("constant.value", "variable.property"));
        pairings.put(SLOT_PARAMETER_DEFAULT, Set.of(
                "parameter.default", "function.signature", "signal.parameter", "variable.property"));
        pairings.put(SLOT_ENUM_VALUE, Set.of("enum.value", "variable.property"));
        pairings.put(SLOT_RETURN_VALUE, Set.of("return.value", "variable.property"));
        pairings.put(SLOT_IF_CONDITION, Set.of("if.condition", "variable.property"));
        pairings.put(SLOT_ELIF_CONDITION, Set.of("elif.condition", "variable.property"));
        pairings.put(SLOT_WHILE_CONDITION, Set.of("while.condition", "variable.property"));
        pairings.put(SLOT_FOR_ITERABLE, Set.of("for.iterable", "variable.property"));
        pairings.put(SLOT_ASSERT_CONDITION, Set.of("assert.condition", "assert.arguments", "variable.property"));
        pairings.put(SLOT_ASSERT_MESSAGE, Set.of("assert.message", "assert.arguments", "variable.property"));
        pairings.put(SLOT_MATCH_VALUE, Set.of("match.value", "variable.property"));
        pairings.put(SLOT_MATCH_GUARD, Set.of("match.guard", "variable.property"));
        pairings.put(SLOT_MATCH_PATTERN, Set.of("match.pattern", "match.section", "variable.property"));
        pairings.put(SLOT_DICTIONARY_PATTERN_KEY, Set.of("match.pattern", "match.dictionary_pattern", "dictionary.entry", "variable.property"));
        pairings.put(SLOT_ANNOTATION_ARGUMENT, Set.of("annotation.arguments", "match.section", "variable.property"));

        // Construct-specific closers
        registerPairings(pairings, Set.of("assert.arguments", "variable.property"),
                MISSING_CLOSER_ASSERT, MISMATCHED_CLOSER_ASSERT);
        registerPairings(pairings, Set.of("annotation.arguments", "match.section", "variable.property"),
                MISSING_CLOSER_ANNOTATION, MISMATCHED_CLOSER_ANNOTATION);
        registerPairings(pairings, Set.of("function.signature", "signal.parameter", "variable.property"),
                MISSING_CLOSER_PARAMETER_LIST, MISMATCHED_CLOSER_PARAMETER_LIST);
        registerPairings(pairings, Set.of("match.pattern", "match.section", "variable.property"),
                MISSING_CLOSER_MATCH_ARRAY_PATTERN, MISMATCHED_CLOSER_MATCH_ARRAY_PATTERN);
        registerPairings(pairings, Set.of("match.pattern", "match.dictionary_pattern", "match.section", "variable.property"),
                MISSING_CLOSER_MATCH_DICTIONARY_PATTERN, MISMATCHED_CLOSER_MATCH_DICTIONARY_PATTERN);
        registerPairings(pairings, Set.of("enum.body"),
                MISSING_CLOSER_ENUM, MISMATCHED_CLOSER_ENUM);

        // Explicit type slot codes
        pairings.put(MISSING_TYPE_VARIABLE, Set.of("variable.type", "variable.property"));
        pairings.put(MISSING_TYPE_CONSTANT, Set.of("constant.type", "variable.property"));
        pairings.put(MISSING_TYPE_PARAMETER, Set.of("parameter.type", "function.signature", "variable.property"));
        pairings.put(MISSING_TYPE_LAMBDA_PARAMETER, Set.of("parameter.type", "function.signature", "variable.property"));
        pairings.put(MISSING_TYPE_SIGNAL_PARAMETER, Set.of("signal.parameter", "parameter.type", "variable.property"));
        pairings.put(MISSING_TYPE_RETURN_TYPE, Set.of("function.return_type", "variable.property"));
        pairings.put(MISSING_TYPE_FOR_ITERATOR, Set.of("for.iterator", "variable.property"));

        // Structural / header / separator / property / match / annotation codes
        pairings.put(STATEMENT_TRAILING, Set.of("statement.trailing", "variable.property"));
        pairings.put(MISSING_SEPARATOR_MATCH_COLON, Set.of("match.section", "variable.property"));
        pairings.put(MISSING_SEPARATOR_SUITE_COLON, Set.of(
                "if.condition", "elif.condition", "while.condition", "for.iterable",
                "match.value", "function.signature", "class.body", "suite.body", "variable.property"
        ));
        pairings.put(MISSING_SEPARATOR_FOR_IN, Set.of("for.iterator", "for.iterable", "variable.property"));
        pairings.put(FOR_MISSING_ITERATOR, Set.of("for.iterator", "variable.property"));
        pairings.put(FOR_MISSING_IN, Set.of("for.iterator", "for.iterable", "variable.property"));

        registerPairings(pairings, Set.of("function.signature", "signal.parameter", "variable.property"),
                SIGNATURE_MANDATORY_AFTER_OPTIONAL,
                SIGNATURE_REST_WITH_DEFAULT,
                SIGNATURE_PARAMETER_AFTER_REST,
                SIGNATURE_DUPLICATE_PARAMETER
        );
        pairings.put(SIGNATURE_SIGNAL_PARAMETER_DEFAULT, Set.of("signal.parameter", "variable.property"));
        registerPairings(pairings, Set.of("variable.property"),
                PROPERTY_DUPLICATE_GETTER, PROPERTY_DUPLICATE_SETTER, PROPERTY_INVALID_ACCESSOR);
        registerPairings(pairings, Set.of("match.pattern", "match.section", "match.dictionary_pattern", "dictionary.entry", "variable.property"),
                MATCH_BIND_MISSING_NAME, MATCH_REST_MISPLACED, MATCH_REST_NOT_LAST);
        pairings.put(LAMBDA_BARE_STATEMENT, Set.of("expression", "variable.property"));
        pairings.put(FUNCTION_INVALID_STATIC_INIT, Set.of("function.signature", "function.name"));
        pairings.put(CLASS_DUPLICATE_EXTENDS, Set.of("class.extends"));
        registerPairings(pairings, Set.of("annotation.target", "match.section", "variable.property"),
                ANNOTATION_DANGLING, ANNOTATION_INVALID_LEVEL);
        registerPairings(pairings, Set.of("annotation.target", "variable.property"),
                ANNOTATION_INVALID_TARGET);
        pairings.put(DECLARATION_MISSING_NAME, Set.of(
                "declaration.name", "function.name", "enum.body", "function.signature", "signal.parameter", "variable.property"));

        // Standalone-allowed codes may also be attributed to any valid construct facet when claimed
        // inside that construct (e.g. a lexical error claimed by an open statement frame).
        var allFacets = Set.copyOf(FACET_ALLOWED_OWNERS.keySet());
        registerPairings(pairings, allFacets,
                LEXICAL_ERROR,
                UNEXPECTED_CLOSER,
                SYNTAX_UNEXPECTED_TOKEN,
                NESTING_DEPTH_EXCEEDED
        );
        pairings.put(STATEMENT_TOO_MANY_ERRORS, Set.of());

        CODE_ALLOWED_FACETS = Map.copyOf(pairings);
        REGISTERED_CODES = Collections.unmodifiableSet(new HashSet<>(CODE_ALLOWED_FACETS.keySet()));
    }

    private static void registerPairings(
            @NotNull Map<String, Set<String>> pairings,
            @NotNull Set<String> allowedFacets,
            @NotNull String... codes
    ) {
        var frozen = Set.copyOf(allowedFacets);
        for (var code : codes) {
            pairings.put(code, frozen);
        }
    }

    public static boolean isRegistered(@Nullable String code) {
        return code != null && REGISTERED_CODES.contains(code);
    }

    public static @NotNull Set<String> registeredCodes() {
        return REGISTERED_CODES;
    }

    public static @NotNull String requireRegistered(@NotNull String code) {
        Objects.requireNonNull(code, "code must not be null");
        if (!REGISTERED_CODES.contains(code)) {
            throw new IllegalArgumentException("Unregistered diagnostic code: " + code);
        }
        return code;
    }

    /// Returns `true` only for explicit standalone diagnostic categories that may legally have
    /// `recovery == null` when no construct owns the offending input.
    public static boolean allowsNullRecovery(@NotNull String code) {
        Objects.requireNonNull(code, "code must not be null");
        return STANDALONE_ALLOWED_CODES.contains(code);
    }

    public static boolean isRegisteredFacet(@Nullable String facet) {
        return facet != null && FACET_ALLOWED_OWNERS.containsKey(facet);
    }

    /// Checks whether `ownerType` is a valid AST owner class for `facet`. `SourceFile` is never a
    /// catch-all owner, and `statement.trailing` rejects abstract interfaces, suite `Block`s,
    /// trivia nodes, and error/unknown placeholders.
    public static boolean isValidOwnerForFacet(@NotNull String facet, @NotNull Class<? extends Node> ownerType) {
        Objects.requireNonNull(facet, "facet must not be null");
        Objects.requireNonNull(ownerType, "ownerType must not be null");
        var allowedOwners = FACET_ALLOWED_OWNERS.get(facet);
        if (allowedOwners == null || ownerType.isInterface()) {
            return false;
        }
        if ("statement.trailing".equals(facet)) {
            return Statement.class.isAssignableFrom(ownerType)
                    && ownerType != Block.class
                    && ownerType != CommentStatement.class
                    && ownerType != RegionDirectiveStatement.class
                    && ownerType != ErrorStatement.class
                    && ownerType != UnknownStatement.class;
        }
        for (var allowed : allowedOwners) {
            if (allowed.isAssignableFrom(ownerType)) {
                return true;
            }
        }
        return false;
    }

    /// Checks whether `(code, ownerType, facet)` is a closed-vocabulary valid recovery pairing.
    public static boolean isValidPairing(
            @NotNull String code,
            @NotNull Class<? extends Node> ownerType,
            @NotNull String facet
    ) {
        Objects.requireNonNull(code, "code must not be null");
        Objects.requireNonNull(ownerType, "ownerType must not be null");
        Objects.requireNonNull(facet, "facet must not be null");
        if (!isRegistered(code) || !isValidOwnerForFacet(facet, ownerType)) {
            return false;
        }
        var allowedFacets = CODE_ALLOWED_FACETS.get(code);
        return allowedFacets != null && allowedFacets.contains(facet);
    }

    public static boolean isValidRecovery(@NotNull String code, @NotNull RecoveryLink link) {
        Objects.requireNonNull(link, "link must not be null");
        return isValidPairing(code, link.owner().getClass(), link.facet());
    }

    /// Derives and validates the registered `parse.missing_expression.*` code for an expression
    /// slot (`variable.initializer` -> `parse.missing_expression.variable_initializer`).
    public static @NotNull String slotCode(@NotNull String slotName) {
        Objects.requireNonNull(slotName, "slotName must not be null");
        return requireRegistered("parse.missing_expression." + slotName.replace('.', '_'));
    }
}
