package dev.superice.gdparser.frontend.ast;

/// Base contract for AST expressions.
public sealed interface Expression extends Node permits IdentifierExpression,
        SelfExpression,
        LiteralExpression,
        GetNodeExpression,
        PreloadExpression,
        CallExpression,
        BaseCallExpression,
        AttributeExpression,
        SubscriptExpression,
        BinaryExpression,
        CastExpression,
        TypeTestExpression,
        UnaryExpression,
        ConditionalExpression,
        AssignmentExpression,
        ArrayExpression,
        DictionaryExpression,
        LambdaExpression,
        AwaitExpression,
        PatternBindingExpression,
        UnknownExpression {
}
