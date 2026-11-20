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
            "func f():\r\nvar x = 1\r\n",
            // Escaped multiline groupings, mismatched closers, header-to-suite layout replay,
            // outer type-bracket boundaries, and UTF-8 multi-byte payloads.
            "var a = [1,\nvar b = 2\n",
            "func f():\n\tprint(1\n\tprint(2)\nvar after = 2\n",
            "func f(a, b:\n\tpass\nvar after = 2\n",
            "var g = foo([1)\n",
            "var h = [1)\n",
            "var x = g(func() -> Array[, node.\n\tpa)\n",
            "var s = [\"😀中文\", \r\nvar t = 1\r\n",
            "func f():\n\tmatch x:\n\t\t[\n\t\t\t" + "[".repeat(254) + "1"
                    + "]".repeat(254) + "\n\t\t]:\n\t\t\tpass\n\t\t_: return 7\n"
                    + "\tvar local_after = 7\nvar after = 2\n",
            // Truncated lambdas at EOF, equal-indent depth trip, suffix escape, unclosed match
            // patterns, damaged Dictionary return type, inline lambda replacement closer, and
            // backslash continuation across comment-only lines.
            "func f():\n\tvar x = g(func(): pass",
            "func f():\n\tvar x = g(func():\n\t\tpass",
            "func f():\n\tvar x = g(func()",
            "func f():\n\tvar x = [" + "(".repeat(260) + "\n\t1\n\t" + ")".repeat(260) + "]\n",
            "func f():\n\tvar x = (a.\n\tvar y = 2\nvar after = 3\n",
            "func f():\n\tvar x = (foo.bar\n[2]\n",
            "func f():\n\tmatch x:\n\t\t[1,\n\tvar local_after = 7\nvar after = 2\n",
            "func f():\n\tmatch x:\n\t\t{\"a\": 1,\n\tvar local_after = 7\nvar after = 2\n",
            "var x = g(func() -> Dictionary[String, node.pa)\n",
            "var x = g(func(): pass]\n",
            "var x = g(func(): super.]\n",
            "var x = g(func():\n\tpass]\n\treturn 1\n)\n",
            "var x = g(func():\n\tvar cb = func(): pass]\n\treturn 1\n)\n",
            "func f():\n\tmatch x:\n\t\t" + "[".repeat(260) + "\n\t1\n\t\t" + "]".repeat(260)
                    + ": pass\n\t\t_:\n\t\t\tpass\n\tvar local_after = 1\nvar after = 2\n",
            "func f():\n\tvar x = (a.\\\n\t\t# note\n\t\tmatch\n\tvar y = 2\n",
            // Recovery corpus covering folded damaged expressions, dictionary literals,
            // comma-delimited lists/patterns, property blocks, and header/suite recovery.
            "func f():\n\tsuper. + 1\n",
            "var d = {\"a\": 1, b = 2}\n",
            "var d = {\"a\": 1, b = }\n",
            "var d = {name}\n",
            "var d = {1 2}\n",
            "var d = {\n\t\"a\"\n\t\"b\": 1\n}\n",
            "var d = {\n\t\"a\"\n\t+ 1 \"b\": 2\n}\n",
            "var d = {\"a\" \"b\": 2}\n",
            "var a = [1 2]\n",
            "func f(a b):\n\tpass\n",
            "signal s(a b)\n",
            "func f():\n\tmatch x:\n\t\t{1+ var x: 3, 2: 3}: pass\n",
            "func f():\n\tmatch x:\n\t\t{1+ var y(}: pass\n\t\t_: pass\nvar after = 1\n",
            "func f():\n\tmatch x:\n\t\t[1 _]: pass\n",
            "func f():\n\tmatch x:\n\t\t1 when func(): pass\n\t\t2: pass\n",
            "func f():\n\tmatch x:\n\t\t1 when func():\n\t\t\tpass\n\t\t2: pass\n",
            "var p: get = g1, get = g2, foo = 3\n",
            "var p: get = g,\nvar after = 1\n",
            "var p:\n\t;\nvar after = 1\n",
            "var p:\n\tunknown(\n\t\tpass\nvar after = 1\n",
            "var p:\n\tget = getter\n\tunknown:\n\t\tpass\nvar after = 1\n",
            "var p:\n\tget:\n\t\tpass\n\tget:\n\t\tpass\n\tfoo:\n\t\tvar inner = 1\n\tbar\nvar after = 1\n",
            "var x = g(func(a:\n\tpass\n)\nvar ok = 1\n",
            "func f():\n\tsuper. 0 var y = 1\n\tvar z = 2\n",
            "func f():\n\tprint(1\n\t\t1 + 2\n\tprint(2)\n",
            "func f():\n\tvar a = [1\n\t\t2\nvar y = 3\n",
            "func f():\n\tmatch x:\n\t\t1\n\t\t\tpass\n\t\t2:\n\t\t\tpass\n",
            "func f():\n\tfor in arr:\n\t\tpass\n",
            "const A\nvar = 1\nfunc (a: int):\n\tpass\n",
            "func f():\r\n\t@warning_ignore(\"中文😀\")\r\n\tvar ok = 1\r\n",
            "func f():\n\tpass\n\t\tvar garbage = 0\n\tvar ok = 1\nvar top = 2\n",
            "class C:\n\tvar a = 1\n\t\tvar b = 2\n\tvar c = 3\nvar top = 4\n",
            "var x:\n\tget = g\n\t\tjunk\n\tget = g2\nvar after = 1\n",
            "var x:\n\tget = g\n\tset = s\n",
            "var x = g(func(a -> int:\n\tpass\n)\nvar ok = 1\n",
            "func f(a = 1: pass\nvar ok = 1\n",
            "func f():\n\tsuper. \\\n\tvar y = 1\n\tvar ok = 2\n",
            "func f():\n\tsuper. \\\n\t0 var y = 1\n\tvar ok = 2\n",
            "var d = {1 \\\n\t2}\n",
            "func f():\n\tif true:\n\t\tvar d = {1\n\tprint(2)\n\tvar ok = 1\nvar top = 2\n",
            "var x = g(func(a:\n\tint) \\\n\t# comment\n\t: pass\n",
            "class C:\n\tfunc f():\n\t\tpass\n\t\t\t" + "var\n\t\t\t".repeat(102)
                    + "var extra = 0\n\t\tvar ok = 1\n\tvar inner = 2\nvar top = 3\n",
            "var x:\n\tget = g,\nvar after = 1\n",
            "var x = g(func():\n" + "\tvar\n".repeat(102) + "\tpass, 2)\nvar after=1\n",
            "class A:\n\tvar z:\n\t\tget = g # c\n\t\t\tpass\n",
            "var x = g(func(a:\n\tpass\n) \\ \n: pass\nvar after=1\n",
            "var x:\n\toops = g\n\tget:\n\t\tpass\nvar after=1\n",
            "var x:\n\toops:\n\t\tpass\n\tget = g,\n\tset = s\nvar after=1\n",
            "var x = g(func():\n" + "\tvar\n".repeat(102) + "\tmatch 1:\n\t\t1, 2: pass\n, 3)\nvar after=1\n",
            "var x = g(func():\n" + "\tvar\n".repeat(102) + "\t[1)\nvar after=1\n",
            "var p:\n\tget =\n\tset(v):\n\t\tpass\nvar ok=1\n",
            "var p:\n\tset(v\n\tget = g,\n\tset = s\nvar ok=1\n",
            "var x = g(func():\n" + "\tvar\n".repeat(102) + "\tif obj.match():\n\t\tpass, 2)\nvar after=1\n",
            "var x = g(func():\n" + "\tvar\n".repeat(102) + "\tvar z = (1\n\tpass, 2)\nvar after=1\n",
            "var x = g(func():\n" + "\tvar\n".repeat(102) + "\tvar z = [1\n\tpass, 2)\nvar after=1\n",
            "var x = g(func():\n" + "\tvar\n".repeat(102) + "\tvar z = (\n\t\t1,\n\t\t2\n\t), 3)\nvar after=1\n",
            "var x = g(func():\n" + "\tvar\n".repeat(102) + "\tmatch (\n\t\t1\n\t):\n\t\t1, 2: pass\n, 3)\nvar after=1\n",
            "var x = g(func():\n" + "\tvar\n".repeat(102) + "\tif true:\n\t\tvar z = (1\n\t, 2)\nvar after=1\n",
            "var x = g(func():\n" + "\tvar\n".repeat(102) + "\tif true:\n\t\tvar z = [1\n\t, 2)\nvar after=1\n",
            "var x = g(func():\n" + "\tvar\n".repeat(102) + "\tif true:\n\t\tvar z = (\n\t1\n\t), 2)\nvar after=1\n",
            "var x = g(func():\n" + "\tvar\n".repeat(102) + "\tif true:\n\t\tvar z = f(\n\t1\n\t, 2\n\t), 3)\nvar after=1\n",
            "var x = g(func():\n" + "\tvar\n".repeat(102) + "\tvar z = (1\n\tif \"a\nb\":\n\t\tpass, 2)\nvar after=1\n",
            "var x = g(func():\n" + "\tvar\n".repeat(102) + "\tvar z = (1\n\tif true \\\n\t# c\n\t:\n\t\tpass, 2)\nvar after=1\n",
            "var x = g(func():\n" + "\tvar\n".repeat(102) + "\tvar z = (1\n\tif true \\ # bad\n\t:\n\t\tpass, 2), 3)\nvar after=1\n",
            "var p: get = g, 123, get = h\nvar after = 1\n",
            "var p: int: 123, get = g, get = h\nvar after = 1\n",
            "var p:\n\toops = f, get = g, get = h\nvar after = 1\n",
            "var p:\n\toops = f\n\tget:\n\t\treturn 1\nvar after = 1\n",
            "var x = g(func():\n" + "\tvar\n".repeat(102) + "\tmatch (\n\t\t1):\n\t\t1, 2: pass\n, 3)\nvar after = 1\n",
            "var x = g(func():\n" + "\tvar\n".repeat(102) + "\tmatch (\n\t\t1):\n\t\t\t1, 2: pass\n, 3)\nvar after = 1\n",
            "var x = g(func():\n" + "\tvar\n".repeat(102) + "\tif true:\n\t\tvar z = [\n\t\t\t1\n\t, 2]\nvar after = 1\n",
            "var p: int: oops, get = g, get = h\nvar after = 1\n",
            "var p:\n\toops, get = g, get = h\nvar after = 1\n",
            "var p:\n\toops:\n\t\tpass\n\tget:\n\t\treturn 1\nvar after = 1\n",
            "var p:\n\tget:\n\t\treturn 1\n\toops:\n\t\tpass\nvar after = 1\n",
            "var x = g(func():\n" + "\tvar\n".repeat(102) + "\tmatch (\n\t\t(\n\t\t\t1)):\n\t\t1, 2: pass\n, 3)\nvar after = 1\n",
            "var x = g(func():\n" + "\tvar\n".repeat(102) + "\tmatch (\n\t\t1):\n, 3)\nvar after = 1\n",
            "var x = g(func():\n" + "\tvar\n".repeat(102) + "\tif true:\n\t\tvar z = f(\n\t\t\t1\n\t, 2\n\t\t)\n\tpass\nvar after = 1\n",
            "var x = g(func():\n" + "\tvar\n".repeat(102) + "\tif true:\n\t\tvar z = f(\n\t\t\t1\n\t, 2\n\t)\n\tpass\nvar after = 1\n",
            "var x = g(func():\n" + "\tvar\n".repeat(102) + "\tif true:\n\t\tvar z = (1\n\t, 2\n)\nvar after = 1\n",
            "var x = g(func():\n" + "\tvar\n".repeat(102) + "\tmatch 1:\n\t\t[\n\t\t\t1\n\t\t\t], 2: pass\n, 3)\nvar after = 1\n",
            "var x = g(func():\n" + "\tvar\n".repeat(102) + "\tmatch 1:\n\t\t[1\n, 3)\nvar after = 1\n",
            "var x = g(func():\n" + "\tvar\n".repeat(102) + "\tif true:\n\t\tvar z = f(1\n\t, 2)\n\tpass\nvar after = 1\n",
            "var x = g(func():\n" + "\tvar\n".repeat(102) + "\tif true:\n\t\tvar z = (\"a\nb\"\n\t, 2)\nvar after = 1\n",
            "var p:\n\tget:\n\t\tpass\n\toops, get:\n\t\tpass\nvar after = 1\n",
            "var p:\n\tget:\n\t\tpass\n\t123, get:\n\t\tpass\nvar after = 1\n",
            "var p:\n\toops(1, 2):\n\t\tpass\n\tget:\n\t\treturn 1\nvar after = 1\n",
            "var x = [func():\n" + "\tvar\n".repeat(102) + "\tif true:\n\t\tvar z = a[1\n\t, 2]\nvar after = 1\n",
            "var x = [func():\n" + "\tvar\n".repeat(102) + "\tif true:\n\t\tvar z = obj.match[1\n\t, 2]\nvar after = 1\n",
            "var x = [func():\n" + "\tvar\n".repeat(102) + "\tif true:\n\t\tvar z = [1\n\t, 2]\n\tpass\nvar after = 1\n",
            "var x = [func():\n" + "\tvar\n".repeat(102) + "\tif true:\n\t\tvar d: Dictionary[String\n\t, int]\n\tpass\nvar after = 1\n",
            "var x = g(func():\n" + "\tvar\n".repeat(102) + "\tif true:\n\t\tvar z = obj.match(1\n\t, 2)\n\tpass\nvar after = 1\n",
            "var x = g(func():\n" + "\tvar\n".repeat(102) + "\tif true:\n\t\tvar z = obj.if(1\n\t, 2)\n\tpass\nvar after = 1\n",
            "var x = g(func():\n" + "\tvar\n".repeat(102) + "\tif true:\n\t\tmatch (1\n\t, 2)\nvar after = 1\n",
            "var x = g(func():\n" + "\tvar\n".repeat(102) + "\tif true:\n\t\tvar z = match(1\n\t, 2)\n\tpass\nvar after = 1\n",
            "var x = g(func():\n" + "\tvar\n".repeat(102) + "\tif true:\n\t\tif true: match (1\n\t, 2)\nvar after = 1\n",
            "var x = [func():\n" + "\tvar\n".repeat(102) + "\tif true:\n\t\tif true: Dictionary[1\n\t, 2]\nvar after = 1\n",
            "var x = [func():\n" + "\tvar\n".repeat(102) + "\tif true:\n\t\tif cond: Dictionary[1\n\t, 2]\nvar after = 1\n",
            "var x = [func():\n" + "\tvar\n".repeat(102) + "\tif true:\n\t\tfunc h() -> Dictionary[String\n\t, int]:\n\t\t\tpass\nvar after = 1\n",
            "func f():\n\tmatch 0:\n\t\t1: pass\n\t\t= 1\n\t\t2: pass\nvar after = 1\n",
            "func f():\n\tmatch 0:\n\t\t1: pass\n\t\t= 1\n",
            "var x = g(func():\n" + "\tvar\n".repeat(102) + "\tvar z = f(\n\tmatch(1), 2)\n, 3)\nvar after = 1\n",
            "var x = g(func():\n" + "\tvar\n".repeat(102) + "\tvar z = (1\n\tmatch 1:\n\t\t1, 2: pass\n, 3)\nvar after = 1\n",
            "var x = g(func():\n" + "\tvar\n".repeat(102) + "\t@warning_ignore(\"unused_variable\") match 1:\n\t\t1, 2: pass\n, 3)\nvar after = 1\n",
            "var x = [func():\n" + "\tvar\n".repeat(102) + "\t@export var d: Dictionary[String\n\t, int]\n\tpass\nvar after = 1\n",
            "var x = [func():\n" + "\tvar\n".repeat(102) + "\tif true:\n\t\tvar d = v is not Dictionary[String\n\t, int]\n\tpass\nvar after = 1\n",
            "var x = [func():\n" + "\tvar\n".repeat(102) + "\tif true:\n\t\tvar d = not Dictionary[1\n\t, 2]\nvar after = 1\n",
            "var x:\n\tget = g,, set = s\nvar after = 1\n",
            "var x:\n\tget = g,,\nvar after = 1\n",
            "var x:\n\tget = g\n\tset = s\nvar after = 1\n",
            "var x = g(func():\n" + "\tvar\n".repeat(102) + "\tvar z = {\n\tmatch(1): 2, 4: 5}\n, 3)\nvar after = 1\n",
            "var x = g(func():\n" + "\tvar\n".repeat(102) + "\tvar z = {\n\tmatch(1):\n\t\t2, 4: 5}\n, 3)\nvar after = 1\n",
            "var x = g(func():\n" + "\tvar\n".repeat(102) + "\tvar z = {1\n\tmatch 1:\n\t\t1, 2: pass\n, 3)\nvar after = 1\n",
            "var x = g(func():\n" + "\tvar\n".repeat(102) + "\t@warning_ignore(\n\t\"unused_variable\") match 1:\n\t\t1, 2: pass\n, 3)\nvar after = 1\n",
            "var x = g(func():\n" + "\tvar\n".repeat(102) + "\t@warning_ignore(\n\t\t\"unused_variable\"\n\t) match 1:\n\t\t1, 2: pass\n, 3)\nvar after = 1\n",
            "var x = g(func():\n" + "\tvar\n".repeat(102) + "\tvar z = {1\n\tmatch 1:\n\t\t1, 2: pass\n\t, 3])\nvar after = 1\n",
            "var x = g(func():\n" + "\tvar\n".repeat(102) + "\tvar z = (1\n\tmatch (\n\t\t1\n\t):\n\t\t1, 2: pass\n\t, 3)\nvar after = 1\n",
            "var x = g(func():\n" + "\tvar\n".repeat(102) + "\tvar z = (1\n\tmatch (1):\n\t\t1, 2: pass\n\t, 3)\nvar after = 1\n",
            "var x = g(func():\n" + "\tvar\n".repeat(102) + "\tvar z = (1\n\tif (\n\t\ttrue\n\t):\n\t\tpass\n\t, 3)\nvar after = 1\n",
            "var x = g(func():\n" + "\tvar\n".repeat(102) + "\t@warning_ignore(\n\t\t\"x\") match 1:\n\t\t1, 2: pass\n\t, 3)\nvar after = 1\n",
            "var x = g(func():\n" + "\tvar\n".repeat(102) + "\t@warning_ignore(\n\t\t\"x\"\n\t)\n\tmatch 1:\n\t\t1, 2: pass\n\t, 3)\nvar after = 1\n",
            "var x = g(func():\n" + "\tvar\n".repeat(102) + "\tvar z = {1: 2]\n\t, 3)\nvar after = 1\n",
            "var x = g(func():\n" + "\tvar\n".repeat(102) + "\tvar z = {1: 2}\n\t, 3)\nvar after = 1\n",
            "var x = g(func():\n" + "\tvar\n".repeat(102) + "\tvar z = {\n\t\tmatch(1):\n\t\t\t2, 4: 5]\n\t, 3)\nvar after = 1\n",
            "var x = g(func():\n" + "\tvar\n".repeat(102) + "\tvar z = {\n\tmatch(1):\n\t\t2, 4: 5]\n\t, 3)\nvar after = 1\n",
            "var x = g(func():\n" + "\tvar\n".repeat(102) + "\t@warning_ignore(\n\t\twarnings.match) match 1:\n\t\t1, 2: pass\n\t, 3)\nvar after = 1\n",
            "var x = g(func():\n" + "\tvar\n".repeat(102) + "\t@warning_ignore(\n\t\tmatch(\"x\")) match 1:\n\t\t1, 2: pass\n\t, 3)\nvar after = 1\n",
            "var x = g(func():\n" + "\tvar\n".repeat(102) + "\tvar z = [{1: 2]\n\t, 3)\nvar after = 1\n",
            "var x = g(func():\n" + "\tvar\n".repeat(102) + "\tvar z = [({1: 2]\n\t, 3)\nvar after = 1\n",
            "var x = g(func():\n" + "\tvar\n".repeat(102) + "\tvar z = [{1: 2}]\n\t, 3)\nvar after = 1\n"
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
