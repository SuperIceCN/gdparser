package dev.superice.gdparser.frontend.ast;

import org.jetbrains.annotations.Nullable;

/// Parser diagnostic with a source span.
///
/// @param code     stable machine-readable identifier for cross-stage owner correlation, or `null`
///             if no code was assigned
/// @param recovery same-generation link to the construct owning the damaged facet, present only
///                 on damage-bearing diagnostics (e.g. missing closers)
public record AstDiagnostic(
        AstDiagnosticSeverity severity,
        String message,
        String nodeType,
        Range range,
        @Nullable String code,
        @Nullable RecoveryLink recovery
) {
    /// Creates a diagnostic without a code or recovery link.
    public AstDiagnostic(AstDiagnosticSeverity severity, String message, String nodeType, Range range) {
        this(severity, message, nodeType, range, null, null);
    }

    public AstDiagnostic withRecovery(@Nullable RecoveryLink link) {
        return new AstDiagnostic(severity, message, nodeType, range, code, link);
    }
}
