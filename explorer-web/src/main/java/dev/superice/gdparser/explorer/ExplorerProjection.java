package dev.superice.gdparser.explorer;

import dev.superice.gdparser.frontend.ast.AstMappingResult;
import dev.superice.gdparser.frontend.gdscript.GdScriptParserFacade;

import java.util.ArrayList;
import java.util.List;

final class ExplorerProjection {
    static final Limits DEFAULT_LIMITS = new Limits(40, 1_500, 64 * 1024, 200, 96 * 1024);

    private static final GdScriptParserFacade PARSER = new GdScriptParserFacade();

    private ExplorerProjection() {
    }

    static Result parse(String source) {
        return project(PARSER.parse(source), DEFAULT_LIMITS);
    }

    static Result project(AstMappingResult parsed, Limits limits) {
        var tree = ExplorerTree.render(parsed.ast(), limits.maxDepth(), limits.maxNodes(), limits.maxTreeText());
        var remainingText = limits.maxTotalText() - tree.text().length();
        var parsedDiagnostics = parsed.diagnostics();
        var diagnostics = new ArrayList<DiagnosticView>(Math.min(limits.maxDiagnostics(), parsedDiagnostics.size()));
        for (var diagnostic : parsedDiagnostics) {
            if (diagnostics.size() == limits.maxDiagnostics()) {
                break;
            }
            var severity = diagnostic.severity().name();
            var code = diagnostic.code();
            var message = diagnostic.message();
            var textLength = (long) severity.length() + (code == null ? 0 : code.length()) + message.length();
            if (textLength > remainingText) {
                break;
            }
            remainingText -= (int) textLength;
            var range = diagnostic.range();
            var start = range.startPoint();
            var end = range.endPoint();
            diagnostics.add(new DiagnosticView(severity, code, message, range.startByte(), range.endByte(),
                    start.row(), start.column(), end.row(), end.column()));
        }
        return new Result(tree.text(), List.copyOf(diagnostics), parsedDiagnostics.size() - diagnostics.size(),
                tree.truncated());
    }

    /// Text budgets count UTF-16 code units: the escaped tree and the raw diagnostic string fields.
    record Limits(int maxDepth, int maxNodes, int maxTreeText, int maxDiagnostics, int maxTotalText) {
        Limits {
            if (maxDepth < 0 || maxNodes < 0 || maxTreeText < 0 || maxDiagnostics < 0 || maxTotalText < maxTreeText) {
                throw new IllegalArgumentException("Invalid explorer display limits");
            }
        }
    }

    record Result(String treeText, List<DiagnosticView> diagnostics, int omittedDiagnostics, boolean treeTruncated) {
    }

    /// @param code raw parser diagnostic code, or null when the parser did not assign one
    record DiagnosticView(String severity, String code, String message, int startByte, int endByte,
                          int startRow, int startColumn, int endRow, int endColumn) {
    }
}
