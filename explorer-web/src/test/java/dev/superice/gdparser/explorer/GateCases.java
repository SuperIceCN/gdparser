package dev.superice.gdparser.explorer;

import java.util.List;

final class GateCases {
    static final String VALID = "func f():\n\tpass\n";
    static final String INCOMPLETE = "var a = \nvar b = 2\n";
    static final String CRLF_EMOJI = "func f():\r\n\tvar x = \"😀中\" +";
    static final String LONE_SURROGATE = "var x = \"\uD800\" +";
    static final String CONTROL_START = "var \u0001x = 1\n";
    static final String CONTROL_PART = "var x\u0001y = 1\n";
    static final String SCRIPT_P_START = "var ℘x = 1\n";
    static final String SCRIPT_P_PART = "var x℘y = 1\n";
    static final String ASCII_IDENTIFIER = "var _sample_total2 = 1\n";
    static final String CJK_START = "var 中x = 1\n";
    static final String CJK_MIXED = "var cost中文_2 = 1\nvar 变量_total2 = 2\n";
    static final String BRACKET_RECOVERY = "var x = [1,\n\u0001func after(): pass\n";
    static final String TRIPLE_QUOTED = "var text = \"\"\"first\n\tfakeChild\nlast\"\"\"\nvar other = 1\n";
    static final String HUGE_GROUPING = "var x = " + "(".repeat(4096);
    static final String HUGE_SUITE = hugeSuite();
    static final String DIAGNOSTIC_STRESS =
            "func f():\n\tvar x = [1,\n\t\t§\n" + "§".repeat(2000) + " func after(): pass\n";

    private GateCases() {
    }

    static List<Sample> samples() {
        return List.of(
                new Sample("valid-function", VALID),
                new Sample("incomplete-declaration", INCOMPLETE),
                new Sample("exact-crlf-cjk-emoji", CRLF_EMOJI),
                new Sample("lone-surrogate", LONE_SURROGATE),
                new Sample("control-identifier-start", CONTROL_START),
                new Sample("control-identifier-part", CONTROL_PART),
                new Sample("script-p-identifier-start", SCRIPT_P_START),
                new Sample("script-p-identifier-part", SCRIPT_P_PART),
                new Sample("ascii-identifier", ASCII_IDENTIFIER),
                new Sample("cjk-identifier-start", CJK_START),
                new Sample("cjk-mixed-identifiers", CJK_MIXED),
                new Sample("bracket-recovery", BRACKET_RECOVERY),
                new Sample("triple-quoted-literal", TRIPLE_QUOTED),
                new Sample("empty", ""),
                new Sample("lambda-below-depth-boundary", lambdaDepth(255)),
                new Sample("lambda-at-depth-boundary", lambdaDepth(256)),
                new Sample("lambda-above-depth-boundary", lambdaDepth(257)),
                new Sample("huge-grouping", HUGE_GROUPING),
                new Sample("huge-suite", HUGE_SUITE),
                new Sample("long-valid-32k", longScript(32 * 1024, false), 3),
                new Sample("long-incomplete-32k", longScript(32 * 1024, true), 3),
                new Sample("long-valid-64k", longScript(64 * 1024, false), 3),
                new Sample("long-incomplete-64k", longScript(64 * 1024, true), 3),
                new Sample("diagnostic-stress", DIAGNOSTIC_STRESS, 3));
    }

    static String lambdaDepth(int depth) {
        return "var x = " + "func(a = ".repeat(depth) + "1" + "): pass".repeat(depth) + "\nvar after = 2\n";
    }

    private static String hugeSuite() {
        var source = new StringBuilder("func f():\n");
        for (var depth = 1; depth <= 600; depth++) {
            source.repeat('\t', depth).append("if true:\n");
        }
        return source.toString();
    }

    static String longScript(int length, boolean incomplete) {
        var source = new StringBuilder("extends Node\n\n");
        var suffix = incomplete ? "\nvar editing = [\"中\", 2,\n" : "\nvar complete = [\"中\", 2]\n";
        for (var index = 0; ; index++) {
            var block = """
                    func sample_%d(samples: Array[float], threshold: float) -> float:
                    \tvar kept: Array[float] = []
                    \tfor value in samples:
                    \t\tif value > threshold:
                    \t\t\tkept.append(value * 2.0)
                    \tvar summary = {"total": 0.0, "count": kept.size()}
                    \tfor value in kept:
                    \t\tsummary["total"] += value
                    \treturn summary["total"]
                    
                    """.formatted(index);
            var remaining = length - source.length() - suffix.length();
            if (block.length() + 2 > remaining) {
                source.append('#').repeat(' ', remaining - 2).append('\n');
                return source.append(suffix).toString();
            }
            source.append(block);
        }
    }

    record Sample(String name, String source, int runs) {
        Sample(String name, String source) {
            this(name, source, 1);
        }

    }
}
