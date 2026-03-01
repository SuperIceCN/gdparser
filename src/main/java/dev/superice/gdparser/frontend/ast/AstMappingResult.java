package dev.superice.gdparser.frontend.ast;

import java.util.List;

/// Result of CST to AST lowering with diagnostics.
public record AstMappingResult(SourceFile ast, List<AstDiagnostic> diagnostics) {

    public AstMappingResult {
        diagnostics = List.copyOf(diagnostics);
    }
}
