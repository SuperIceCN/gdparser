package dev.superice.gdparser.frontend.ast;

import dev.superice.gdparser.frontend.cst.CstNodeView;
import dev.superice.gdparser.frontend.cst.CstRange;
import org.jetbrains.annotations.NotNull;

/// Centralized constructors used by lowering logic.
public final class AstFactory {

    private AstFactory() {
    }

    public static @NotNull Range range(CstRange range) {
        return new Range(
                range.startByte(),
                range.endByte(),
                new Point(range.startPoint().row(), range.startPoint().column()),
                new Point(range.endPoint().row(), range.endPoint().column())
        );
    }

    public static @NotNull AstDiagnostic diagnostic(AstDiagnosticSeverity severity, String message, CstNodeView node) {
        return new AstDiagnostic(severity, message, node.type(), range(node.range()));
    }

    public static @NotNull AstDiagnostic diagnostic(AstDiagnosticSeverity severity, String message, String nodeType, CstRange range) {
        return new AstDiagnostic(severity, message, nodeType, AstFactory.range(range));
    }

    public static @NotNull UnknownStatement unknownStatement(CstNodeView node, String sourceText) {
        return new UnknownStatement(node.type(), sourceText, range(node.range()));
    }

    public static @NotNull UnknownExpression unknownExpression(CstNodeView node, String sourceText) {
        return new UnknownExpression(node.type(), sourceText, range(node.range()));
    }
}
