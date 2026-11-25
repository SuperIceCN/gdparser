package dev.superice.gdparser.explorer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.List;

public final class ParityFixtures {
    private ParityFixtures() {
    }

    static void main(String[] args) throws IOException {
        var output = Path.of(args[0]);
        Files.createDirectories(output.getParent());
        Files.writeString(output, json(GateCases.samples()));
    }

    static String json(List<GateCases.Sample> samples) {
        var json = new StringBuilder("[");
        for (var sample : samples) {
            if (json.length() > 1) {
                json.append(',');
            }
            var result = ExplorerProjection.parse(sample.source());
            json.append("{\"name\":");
            appendString(json, sample.name());
            json.append(",\"source\":");
            appendString(json, sample.source());
            json.append(",\"runs\":").append(sample.runs()).append(",\"expected\":");
            appendResult(json, result);
            json.append("}\n");
        }
        return json.append("]\n").toString();
    }

    private static void appendResult(StringBuilder json, ExplorerProjection.Result result) {
        json.append("{\"treeText\":");
        appendString(json, result.treeText());
        json.append(",\"diagnostics\":[");
        var first = true;
        for (var diagnostic : result.diagnostics()) {
            if (!first) {
                json.append(',');
            }
            first = false;
            json.append("{\"severity\":");
            appendString(json, diagnostic.severity());
            json.append(",\"code\":");
            appendString(json, diagnostic.code());
            json.append(",\"message\":");
            appendString(json, diagnostic.message());
            json.append(",\"startByte\":").append(diagnostic.startByte())
                    .append(",\"endByte\":").append(diagnostic.endByte())
                    .append(",\"startRow\":").append(diagnostic.startRow())
                    .append(",\"startColumn\":").append(diagnostic.startColumn())
                    .append(",\"endRow\":").append(diagnostic.endRow())
                    .append(",\"endColumn\":").append(diagnostic.endColumn())
                    .append('}');
        }
        json.append("],\"omittedDiagnostics\":").append(result.omittedDiagnostics())
                .append(",\"treeTruncated\":").append(result.treeTruncated()).append('}');
    }

    private static void appendString(StringBuilder json, String value) {
        if (value == null) {
            json.append("null");
            return;
        }
        json.append('"');
        for (var index = 0; index < value.length(); index++) {
            var ch = value.charAt(index);
            if (ch == '"' || ch == '\\') {
                json.append('\\').append(ch);
            } else if (ch < ' ' || ch > '~') {
                // Escape UTF-16 code units before writing UTF-8, including any unpaired surrogate.
                json.append("\\u").append(HexFormat.of().toHexDigits(ch));
            } else {
                json.append(ch);
            }
        }
        json.append('"');
    }
}
