package dev.superice.gdparser.frontend.ast;

/// Base contract for AST statements.
public sealed interface Statement extends Node permits Block,
        ClassNameStatement,
        ExtendsStatement,
        SignalStatement,
        VariableDeclaration,
        FunctionDeclaration,
        IfStatement,
        ForStatement,
        WhileStatement,
        MatchStatement,
        ReturnStatement,
        ExpressionStatement,
        PassStatement,
        UnknownStatement {
}
