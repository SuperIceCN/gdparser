package dev.superice.gdparser.frontend.ast;

import dev.superice.gdparser.frontend.cst.CstIssueKind;

/// Placeholder for malformed or missing expression syntax.
/// Carries the issue kind, a node or slot identifier, and the source fragment when present.
public record ErrorExpression(CstIssueKind kind, String nodeType, String sourceText,
                              Range range) implements Expression {
}
