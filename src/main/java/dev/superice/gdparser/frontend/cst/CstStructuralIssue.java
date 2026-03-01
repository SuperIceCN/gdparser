package dev.superice.gdparser.frontend.cst;

/// A detected structural issue in a CST node.
public record CstStructuralIssue(CstIssueKind kind, String nodeType, CstRange range) {
}
