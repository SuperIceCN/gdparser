package dev.superice.gdparser.frontend.ast;

/// Lowering diagnostic with source span.
public record AstDiagnostic(
        AstDiagnosticSeverity severity,
        String message,
        String nodeType,
        Range range
) {
}
