package dev.superice.gdparser.frontend.ast;

/// Base contract for AST expressions.
public sealed interface Expression extends Node permits IdentifierExpression,
        LiteralExpression,
        CallExpression,
        AttributeExpression,
        SubscriptExpression,
        BinaryExpression,
        UnaryExpression,
        ConditionalExpression,
        AssignmentExpression,
        ArrayExpression,
        DictionaryExpression,
        LambdaExpression,
        UnknownExpression {
}
