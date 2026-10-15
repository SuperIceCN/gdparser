package dev.superice.gdparser.frontend.gdscript;

/// Stable diagnostic codes for parser recovery diagnostics. Consumers (e.g. GDCC) use them to
/// correlate a parser diagnostic with the construct it damages; the vocabulary is registered in
/// `doc/godot-java-parser-implementation.md`.
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

    // Attribute/super recovery.
    public static final String ATTRIBUTE_MISSING_NAME = "parse.attribute.missing_name";
    public static final String SUPER_MISSING_NAME = "parse.super.missing_name";
    public static final String SUPER_MISSING_CALL = "parse.super.missing_call";
    public static final String SUPER_MISSING_ACCESS = "parse.super.missing_access";

    // Signature recovery.
    public static final String SIGNATURE_MANDATORY_AFTER_OPTIONAL = "parse.signature.mandatory_after_optional";
    public static final String SIGNATURE_REST_WITH_DEFAULT = "parse.signature.rest_with_default";
    public static final String SIGNATURE_PARAMETER_AFTER_REST = "parse.signature.parameter_after_rest";
}
