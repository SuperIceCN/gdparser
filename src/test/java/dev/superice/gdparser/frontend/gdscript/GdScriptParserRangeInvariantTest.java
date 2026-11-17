package dev.superice.gdparser.frontend.gdscript;

import dev.superice.gdparser.frontend.ast.Node;
import dev.superice.gdparser.frontend.ast.Range;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

/// Range-envelope invariant (I3): every node satisfies
/// `parent.startByte <= child.startByte && child.endByte <= parent.endByte` — a zero-width child
/// exactly on a parent endpoint is legal. The walk runs over a recovery corpus (markers, damaged
/// chains, missing closers) plus every legal fixture; no exemptions.
@Timeout(value = 10, unit = TimeUnit.SECONDS)
class GdScriptParserRangeInvariantTest {

    private static final Path FIXTURE_DIR = Path.of("src", "test", "resources", "gdscript");

    /// Recovery-oriented corpus: every case produces markers, damaged nodes, or zero-width
    /// recovery constructs, including trailing-whitespace, EOF-cut, and garbage-prefix forms.
    private static final List<String> RECOVERY_CORPUS = List.of(
            "var hp =\nvar mp = 2\n",
            "var hp = 1+\n",
            "var hp = $/!!\nvar mp = 2\n",
            "var hp = (1",
            "var x = target(1",
            "var cb = (func(): return 1\n",
            "var cb = func(): return (1\n",
            "func f():\n\treturn 1+\n",
            "func f():\n\treturn =3\n",
            "func f():\n\tif 1+: pass\n",
            "func f():\n\twhile : pass\n",
            "func f():\n\tfor x in : pass\n",
            "func f():\n\tassert(1+, \"msg\")\n",
            "const A = 1+\nvar b = 2\n",
            "func f():\n\tmatch 1+:\n\t\t1: pass\n",
            "func f():\n\tmatch x:\n\t\t1 when : pass\n",
            "enum E { A =, B }\n",
            "func f():\n\tif a: pass\n\telif : pass\n",
            "func f():\n\tmatch x:\n\t\t{1+: 1}: pass\n",
            "func f():\n\tmatch x:\n\t\t{1+}: pass\n",
            "func f():\n\tmatch x:\n\t\t{1+, 2: 3}: pass\n",
            "func f():\n\tmatch x:\n\t\t{1+ var x: 3}: pass\n",
            // A missing pattern in a follow-up branch anchors before the section's content start.
            "func f():\n\tmatch x:\n\t\t1: pass\n\t\t: pass\n",
            // A parameterized lambda default rewrites the shared parameter-start field; the
            // variadic rebuild must still span from the `...` marker.
            "func f(...args = func(b): pass\n): pass\n",
            "var a = [1, , 3]\n",
            "var d = {\"a\":}\n",
            "var d = {:1}\n",
            "var d = {1+:2}\n",
            "var d = {:}\n",
            "var d = {(a): 1}\n",
            "@export_range(0, 1+, 2)\nvar x = 1\n",
            "@export_enum(\"A\", ,\"B\")\nvar x = 1\n",
            "func f(x: int = 1+): pass\n",
            "func f(x=): pass\n",
            "func f(a: int = 1, b: int): pass\n",
            "func f(...args = 1+): pass\n",
            "func f(...args = func(): pass\n): pass\n",
            "func f(...args = (1: pass\n",
            "var x = self.b.(1)\n",
            "var y = self.p.[0]\n",
            "func f():\n\tnode.",
            "func f():\n\tsuper.\n\tsuper.ba\n",
            "func f():\n\tsuper. var y = 1\n",
            "func f():\n\tsuper.ba + 1\n",
            "func f():\n\tf(func(): super. + 1)\n",
            "func f():\n\tf(func(): super., 1)\n",
            "func f():\n\tvar f = func():\n\t\tsuper.;\n\tvar y = 1\n",
            "func f():\n\tfoo(1+)\nvar y = 1\n",
            "func f():\n\ta[1+]+2\n",
            "func f():\n\t(1+)+2\n",
            "func f():\n\tfoo(1+, [2+])\n",
            "func f():\n\tfoo([1+])\n",
            "func f():\n\tfoo(1+)(2)\n",
            "func f():\n\ta[1+][2]\n",
            "func f():\n\tfoo(1+, func(): pass)\n",
            "var x = func(a = 1+): pass + 2\n",
            "func f():\n\tsuper(1+)+2\n",
            "func f():\n\tvar x = ()\n",
            "var x = (\n",
            "var x = a = 1\n",
            "func f():\n\treturn a =\n",
            // Trailing whitespace before EOF.
            "var x = 1 +   ",
            // Garbage prefix before a valid member.
            "% ^ &\nvar good = 2\n",
            // The extentEnd() special case: a trailing lambda argument whose body end, not the
            // last consumed token, anchors the enclosing ranges.
            "var x = f(, func(): return 1\n",
            // Unindented class/function bodies across LF, CRLF, and trailing header comments.
            "class Inner:\r\nvar x = 1\r\n",
            "class Inner: # c  \nvar x = 1\n",
            "class Inner: # c  \r\nvar x = 1\r\n",
            "func f():\r\nvar x = 1\r\n"
    );

    @Test
    void recoveryCorpusKeepsRangeEnvelope() {
        for (var source : RECOVERY_CORPUS) {
            var result = new GdScriptParser().parse(source);
            assertEnvelope(result.ast(), () -> "envelope violation in: " + source);
        }
    }

    @Test
    void legalFixturesKeepRangeEnvelope() throws IOException {
        try (var stream = Files.list(FIXTURE_DIR)) {
            for (var script : stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".gd"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList()) {
                var source = Files.readString(script, StandardCharsets.UTF_8);
                var result = new GdScriptParser().parse(source);
                assertEnvelope(result.ast(), () -> "envelope violation in fixture: " + script.getFileName());
            }
        }
    }

    private static void assertEnvelope(Node node, java.util.function.Supplier<String> context) {
        for (var child : node.getChildren()) {
            assertEnvelopeEdge(node.range(), child.range(), node, child, context);
            assertEnvelope(child, context);
        }
    }

    private static void assertEnvelopeEdge(Range parent, Range child, Node parentNode, Node childNode,
                                           java.util.function.Supplier<String> context) {
        assertTrue(parent.startByte() <= child.startByte() && child.endByte() <= parent.endByte(),
                () -> context.get() + "\nparent " + parentNode.getClass().getSimpleName() + " " + parent
                        + " does not enclose child " + childNode.getClass().getSimpleName() + " " + child);
    }
}
