package dev.superice.gdparser.explorer;

import org.teavm.jso.JSBody;
import org.teavm.jso.JSExport;
import org.teavm.jso.JSObject;

public final class ExplorerBridge {
    private ExplorerBridge() {
    }

    @JSExport
    public static JSObject parseForExplorer(String source) {
        var projected = ExplorerProjection.parse(source);
        var result = createResult(projected.treeText(), projected.omittedDiagnostics(), projected.treeTruncated());
        for (var diagnostic : projected.diagnostics()) {
            addDiagnostic(result, diagnostic.severity(), diagnostic.code(), diagnostic.message(),
                    diagnostic.startByte(), diagnostic.endByte(), diagnostic.startRow(), diagnostic.startColumn(),
                    diagnostic.endRow(), diagnostic.endColumn());
        }
        return result;
    }

    @JSBody(params = {"treeText", "omittedDiagnostics", "treeTruncated"}, script = """
            return {treeText, diagnostics: [], omittedDiagnostics, treeTruncated};
            """)
    private static native JSObject createResult(String treeText, int omittedDiagnostics, boolean treeTruncated);

    @JSBody(params = {"result", "severity", "code", "message", "startByte", "endByte",
            "startRow", "startColumn", "endRow", "endColumn"}, script = """
            result.diagnostics.push({severity, code, message, startByte, endByte,
                startRow, startColumn, endRow, endColumn});
            """)
    private static native void addDiagnostic(JSObject result, String severity, String code, String message,
                                             int startByte, int endByte, int startRow, int startColumn,
                                             int endRow, int endColumn);
}
