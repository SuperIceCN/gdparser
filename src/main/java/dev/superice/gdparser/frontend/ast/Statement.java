package dev.superice.gdparser.frontend.ast;

/// Base contract for AST statements.
public sealed interface Statement extends Node permits Block,
        AnnotationStatement,
        AssertStatement,
        BreakStatement,
        BreakpointStatement,
        ClassDeclaration,
        ClassNameStatement,
        CommentStatement,
        ConstructorDeclaration,
        ContinueStatement,
        EnumDeclaration,
        ExtendsStatement,
        SignalStatement,
        VariableDeclaration,
        FunctionDeclaration,
        IfStatement,
        ForStatement,
        WhileStatement,
        MatchStatement,
        RegionDirectiveStatement,
        ReturnStatement,
        ExpressionStatement,
        PassStatement,
        UnknownStatement {
}
