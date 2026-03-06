package dev.superice.gdparser.frontend.ast;

/// Base contract for all AST nodes.
public sealed interface Node permits Statement, Expression, Parameter, TypeRef, MatchSection, DictEntry, AttributeStep, EnumMember {
    Range range();
}
