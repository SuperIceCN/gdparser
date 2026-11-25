package dev.superice.gdparser.frontend.ast;

import java.util.List;

/// The parsed source AST and its diagnostics.
public record AstMappingResult(SourceFile ast, List<AstDiagnostic> diagnostics) {

    public AstMappingResult {
        diagnostics = List.copyOf(diagnostics);
    }
}
