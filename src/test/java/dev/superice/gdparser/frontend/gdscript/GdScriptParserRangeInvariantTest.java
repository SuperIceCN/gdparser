package dev.superice.gdparser.frontend.gdscript;

import dev.superice.gdparser.frontend.ast.AstDiagnostic;
import dev.superice.gdparser.frontend.ast.AstDiagnosticSeverity;
import dev.superice.gdparser.frontend.ast.AstMappingResult;
import dev.superice.gdparser.frontend.ast.BreakStatement;
import dev.superice.gdparser.frontend.ast.CallExpression;
import dev.superice.gdparser.frontend.ast.ClassDeclaration;
import dev.superice.gdparser.frontend.ast.ClassNameStatement;
import dev.superice.gdparser.frontend.ast.ContinueStatement;
import dev.superice.gdparser.frontend.ast.ExtendsStatement;
import dev.superice.gdparser.frontend.ast.FunctionDeclaration;
import dev.superice.gdparser.frontend.ast.LambdaExpression;
import dev.superice.gdparser.frontend.ast.MatchStatement;
import dev.superice.gdparser.frontend.ast.Node;
import dev.superice.gdparser.frontend.ast.Range;
import dev.superice.gdparser.frontend.ast.ReturnStatement;
import dev.superice.gdparser.frontend.ast.SignalStatement;
import dev.superice.gdparser.frontend.ast.SourceFile;
import dev.superice.gdparser.frontend.ast.VariableDeclaration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Range-envelope (`I3`) and diagnostic-coding/ownership (`I5`) invariants:
/// - `I3`: every AST node satisfies `parent.startByte <= child.startByte && child.endByte <= parent.endByte`.
/// - `I5`: every pure-Java `ERROR` diagnostic carries a non-null registered `code`, a valid UTF-8
///   physical range consistent with `SourceTextIndex`, and either an identity-reachable owner whose
///   `(code, ownerType, facet)` pairing is registered, or a `null` recovery strictly within
///   `DiagnosticCodes.allowsNullRecovery(code)`.
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
            "var x = g(func():\n" + "\tvar\n".repeat(102) + "\tvar z = [{1: 2}]\n\t, 3)\nvar after = 1\n",
            // L-29 universal diagnostic coding and owner-attribution coverage.
            "var a = 1\n\"unterminated\n",
            "var a = 1\n0x\n",
            "var a = [1,\n0x\n]\n",
            "func f():\n\tbreak\n\tcontinue\n",
            "func _init():\n\treturn 1\n",
            "class_name Foo extends A\nextends B\n",
            "class Inner extends A:\n\textends B\n\tpass\n",
            "enum E { , }\n",
            "signal s(a = 1, a)\n",
            "func f(a, a = ): pass\n",
            "func f() -> :\n\tpass\n",
            "func f():\n\tmatch x:\n\t\tpass 1\n\t\t@export\n\t\t@warning_ignore(\"unused\")\n",
            "func f():\n\tvar x:\n\tpass\n",
            "func f():\n\treturn =😀\r\n",
            // L-13, L-14, L-20 single-extends, missing type slots, and for-colon recovery.
            "extends A\nclass_name C extends B\n",
            "extends\nextends B\n",
            "class Inner extends :\n\textends B\n\tpass\n",
            "func f(x:, y: int): pass\n",
            "signal s(x:, x:)\n",
            "var fn = func(x:): return x\n",
            "func f():\n\tvar x:;\n\tpass\n",
            "const X:\n",
            "func f():\n\tfor i: ??? in xs:\n\t\tpass\n",
            "func f():\n\tfor i: ??? x:\n\t\tpass\n",
            // L-12 scope-aware annotations and accessor recovery children.
            "@tool\n@tool\n@icon(\"\")\n@export_group(\"G\") var bad = 1\n",
            "var head = 1\n@tool\n@export\nfunc f(): pass\n@rpc\nvar x = 1\n",
            "class Inner:\n\t@export\nvar outer = 1\n",
            "func f():\n\t@warning_ignore(\"a\")\n\tvar\n\tvar ok = 1\n",
            "var g = g(func(): @x, 2)\n",
            "var prop:\n\tget:\n\t\t@warning_ignore(1+)\n\tset(v):\n\t\t@export\nvar sibling = 2\n",
            // L-30a/b/c/d lvalue, direct call, bare suite lambda, and _static_init.
            "func f():\n\t1 = 2\n\tf() = 2\n\tobj.foo() = 2\n\tf(1)(2)\n\ta.b(1)(2)\n\ttable[i](2)\n",
            "func f():\n\t() = 2\n\tself. = 2\n\t()(2)\n\tself.b.(1)\n\tg(1 = 2)\n",
            "func f():\n\tfunc(): pass\n\tfunc named(a: int) -> int: return a\n\tpass\n",
            "func _static_init(a, ...rest):\n\treturn 1\n",
            "var prop:\n\tget:\n\t\tfunc(): pass\n",
            // Review regressions: grouped attribute callees, unshielded return/for boundaries,
            // same-line lexical claims, suite/match/accessor recovery owners, @static_unload level,
            // inline-lambda trailing garbage, and escaped annotation directive matching.
            "func f():\n\t(obj.method)(1)\n\t(self.)(1)\n\t(f(1))(2)\n\t(table[i])(2)\n",
            "func f(): g(func(): return =[, 1)\n",
            "func f(): g(func(): return ={1, 2)\n",
            "func f(): g(func(): return =(1, 2), 3)\n",
            "func f(): g(func(): for x: 3, 1)\n",
            "func f(): g(func(): for x: 3 in xs: pass, 1)\n",
            "func f():\n\treturn \"oops",
            "var x = §\n",
            "var x = 1\n§\n",
            "func f():\n\treturn\n\t§\n",
            "func f():\n\t$foo//\n",
            "func f():\n\tmatch x:\n\t\t@warning_ignore(,)\n\t\t_: pass\n",
            "var prop:\n\tget:\n\t\tmatch x:\n\t\t\t@export\n\t\t\t_: pass\n\tset(v):\n\t\tmatch x:\n\t\t\t@warning_ignore(\"a\")\n",
            "extends Node\n@static_unload\nclass Inner: pass\n",
            "var x\n@static_unload(123)\nclass Inner: pass\n",
            "func f(): g(func(): return 1 2, 3)\n",
            "@warning_ignore_start(\"unused_\\u0076ariable\")\n@warning_ignore_restore(\"unused_variable\")\n",
            // Lexical slots at headers/containers, trailing return lexical claims, accessor match
            // recovery annotation order, and deep unmatched delimiter comma shields.
            "func f(): g(func(): match §, 1)\n",
            "func f(): g(func(): if §, 1)\n",
            "func f(): g(func(): while §, 1)\n",
            "func f():\n\tmatch §:\n\t\tpass\n",
            "var x = f(1, §)\n",
            "var x = f(§)\n",
            "var x = [1, §]\n",
            "var x = [§]\n",
            "var x = [\n\t1,\n\t§\n]\n",
            "var x = {1: 2, §}\n",
            "func f():\n\tassert(1, §)\n",
            "@warning_ignore(\"a\", §)\nvar x = 1\n",
            "func f():\n\tmatch x:\n\t\t[1, §]: pass\n\t\t{1: 2, §}: pass\n",
            "func f(): g(func(): return =3 §, 1)\n",
            "func f(): return =3 §",
            "func f():\n\treturn =3\n\t§\n",
            "var prop:\n\tget:\n\t\tmatch x:\n\t\t\t@warning_ignore(\"unused_variable\")\n\t\t\t@export\n",
            "func f(): g(func(): return =" + "[".repeat(100) + "(1, 2, 3, 4), 5)\n",
            // Explicit backslash continuation lexical claims and duplicate accessor recovery annotations.
            "func f(): g(func(): return =3 \\\n §, 1)\n",
            "func f():\n\treturn \\\n\t§\n",
            "var x = \\\n\t§\n",
            "func f():\n\treturn =3 \\\n\t§\n\t§\n",
            "var prop:\n" + "\tget:\n\t\tmatch x:\n\t\t\t@export\n".repeat(40),
            // Queued continuation errors, malformed backslashes, continuation across skipped errors,
            // and lexical-only statement tails.
            "func f():\n\treturn \\\n\t\\ \n\tvar x = 1\n",
            "func f():\n\treturn \\\n\t\r§\n",
            "func f():\n\treturn \\\n\t\r \n\tvar x = 1\n",
            "func f():\n\tpass 0\\\n\t§ var local = 1\n\tpass\n",
            "func f():\n\tpass \\\n\t§ var local = 1\n\tpass\n",
            "func f():\n\tpass 0\\\n\t§\n\tvar local = 1\n\tpass\n",
            "func f():\n\treturn 1 §\n",
            "func f(): g(func(): return 1 §, 2)\n",
            "func f():\n\treturn 1 \\\n\t§\n",
            "func f(): return 1 §",
            "func f():\n\treturn 1\n\t§\n",
            // Continuation after an earlier skipped error and multiline-suppressed hard-break resets.
            "func f():\n\tpass 0 §\\\n\t§ var local = 1\n\tpass\n",
            "func f():\n\tpass 0 §\\\n\t§\n\tvar local = 1\n\tpass\n",
            "func f():\n\tvar d = {\n\t\t1 §\\\n\t\t§ 2\n\t}\n",
            "func f():\n\tvar d = {\n\t\t1 §\\\n\t\t§\n\t\t§ 2\n\t}\n",
            // Hard-newline monotonic counter checks across multiline suppression, comments, and strings.
            "func f():\n\tvar d = {\n\t\t1\n\t\t§\\\n\t\t§ 2\n\t}\n",
            "func f():\n\tvar d = {\n\t\t1\n\t\t\\\n\t\t2\n\t}\n",
            "func f():\n\tvar d = {\n\t\t1\n\t\t# comment\n\t\t§\\\n\t\t§ 2\n\t}\n",
            "func f():\n\tvar d = {\n\t\t1 \\\n\t\t§\\\n\t\t§ 2\n\t}\n",
            "func f():\n\tvar d = {\n\t\t1 \\\n\t\t# comment\n\t\t§\\\n\t\t§ 2\n\t}\n",
            "func f():\n\tvar x = 1 \\\n\t\"\"\"a\nb\"\"\"\n",
            // Layout replay splits pre-newline and post-newline lexical batches (LF and CRLF).
            "func f():\n\tvar x = (1 §\n§\nvar after = 3\n",
            "func f():\r\n\tvar x = (1 §\r\n§\r\nvar after = 3\r\n",
            "func f():\n\tvar x = (1 §\nvar after = 3\n",
            "func f():\r\n\tvar x = (1 §\r\nvar after = 3\r\n",
            "func f():\n\tvar x = (1\n§\nvar after = 3\n",
            "func f():\r\n\tvar x = (1\r\n§\r\nvar after = 3\r\n",
            "func f():\n\tvar x = (1 §\n§\n2)\n",
            "func f():\r\n\tvar x = (1 §\r\n§\r\n2)\r\n",
            "func f():\n\tvar x = (1 + §\n§\n2)\n",
            "func f():\r\n\tvar x = (1 + §\r\n§\r\n2)\r\n",
            // Unclosed multiline slot claims in-scope post-newline lexical prefix (LF and CRLF).
            "func f():\n\tvar x = (1 +\n\t\t§\n§\nvar after = 3\n",
            "func f():\r\n\tvar x = (1 +\r\n\t\t§\r\n§\r\nvar after = 3\r\n",
            "func f():\n\tvar x = (1 +\n§\nvar after = 3\n",
            "func f():\r\n\tvar x = (1 +\r\n§\r\nvar after = 3\r\n",
            "func f():\n\tvar x = (1 +\n\t\t§\n\t\t§\n§\nvar after = 3\n",
            "func f():\r\n\tvar x = (1 +\r\n\t\t§\r\n\t\t§\r\n§\r\nvar after = 3\r\n",
            "func f():\n\tvar x = (1 + §\n\t\t§\n§\nvar after = 3\n",
            "func f():\r\n\tvar x = (1 + §\r\n\t\t§\r\n§\r\nvar after = 3\r\n",
            "func f():\n\tvar x = (1 +\n\t\t§\n§\n2)\n",
            "func f():\r\n\tvar x = (1 +\r\n\t\t§\r\n§\r\n2)\r\n",
            "func f():\n\tvar x = (1\n\t§\n\tvar after = 3\n",
            "func f():\r\n\tvar x = (1\r\n\t§\r\n\tvar after = 3\r\n",
            // Multiline container loops, explicit continuation provenance, and LIFO physical cutoff (LF and CRLF).
            "func f():\n\tvar x = [1,\n\t\t§\n§\nvar after = 3\n",
            "func f():\r\n\tvar x = [1,\r\n\t\t§\r\n§\r\nvar after = 3\r\n",
            "func f():\n\tvar x = [1,\n\t\t§\n§\nprint(1)\n",
            "func f():\r\n\tvar x = [1,\r\n\t\t§\r\n§\r\nprint(1)\r\n",
            "func f():\n\tif true:\n\t\tvar x = [1,\n\t\t\t§\n\t§\n\tprint(1)\n",
            "func f():\r\n\tif true:\r\n\t\tvar x = [1,\r\n\t\t\t§\r\n\t§\r\n\tprint(1)\r\n",
            "func f():\n\tvar x = [1,\n§\nvar after = 3\n",
            "func f():\r\n\tvar x = [1,\r\n§\r\nvar after = 3\r\n",
            "func f():\n\tvar x = {1: 2,\n\t\t§\n§\nvar after = 3\n",
            "func f():\r\n\tvar x = {1: 2,\r\n\t\t§\r\n§\r\nvar after = 3\r\n",
            "func f():\n\tvar x = f(1,\n\t\t§\n§\nvar after = 3\n",
            "func f():\r\n\tvar x = f(1,\r\n\t\t§\r\n§\r\nvar after = 3\r\n",
            "func f():\n\tassert(true,\n\t\t§\n§\nvar after = 3\n",
            "func f():\r\n\tassert(true,\r\n\t\t§\r\n§\r\nvar after = 3\r\n",
            "func f():\n\t@warning_ignore(\"a\",\n\t\t§\n§\nvar after = 3\n",
            "func f():\r\n\t@warning_ignore(\"a\",\r\n\t\t§\r\n§\r\nvar after = 3\r\n",
            "func f():\n\tmatch x:\n\t\t[1,\n\t\t\t§\n§\nvar after = 3\n",
            "func f():\r\n\tmatch x:\r\n\t\t[1,\r\n\t\t\t§\r\n§\r\nvar after = 3\r\n",
            "func f():\n\tvar x = (1 +\n\t\t§ \\\n§\n§\nvar after = 3\n",
            "func f():\r\n\tvar x = (1 +\r\n\t\t§ \\\r\n§\r\n§\r\nvar after = 3\r\n",
            "func f():\n\tvar x = (1 +\n\t\t§ \\\n§ §\n§\nvar after = 3\n",
            "func f():\r\n\tvar x = (1 +\r\n\t\t§ \\\r\n§ §\r\n§\r\nvar after = 3\r\n",
            "func f():\n\tvar x = (1 +\n\t\t\r # in\n\r # out\nvar after = 3\n",
            "func f():\r\n\tvar x = (1 +\r\n\t\t\r # in\r\n\r # out\r\nvar after = 3\r\n",
            // Unclosed multiline trailing-comma guards, explicit `\` continuation across hard newlines,
            // error-prefixed line-start stops, and signal/function/enum header lexical attribution (LF and CRLF).
            "var xs = [func():\n\tassert(true,\n\t\t§\n, 2]\n",
            "var xs = [func():\r\n\tassert(true,\r\n\t\t§\r\n, 2]\r\n",
            "var xs = [func():\n\tassert(true\n, 2]\n",
            "var xs = [func():\r\n\tassert(true\r\n, 2]\r\n",
            "class C:\n\tfunc f():\n\t\tvar xs = [preload(\"res://a.gd\"\n\t, 2]\n",
            "class C:\r\n\tfunc f():\r\n\t\tvar xs = [preload(\"res://a.gd\"\r\n\t, 2]\r\n",
            "func f():\n\tvar x = (1 +\n\t\t§ \\\n2\nvar after = 3\n",
            "func f():\r\n\tvar x = (1 +\r\n\t\t§ \\\r\n2\r\nvar after = 3\r\n",
            "func f():\n\tvar x = (1 +\n§ \\\n2\nvar after = 3\n",
            "func f():\r\n\tvar x = (1 +\r\n§ \\\r\n2\r\nvar after = 3\r\n",
            "func f():\n\tvar x = (1 +\n\t\t§\n§ \\\n2\nvar after = 3\n",
            "func f():\r\n\tvar x = (1 +\r\n\t\t§\r\n§ \\\r\n2\r\nvar after = 3\r\n",
            "func f():\n\tvar x = (1\n\\\nvar after = 3\n",
            "func f():\r\n\tvar x = (1\r\n\\\r\nvar after = 3\r\n",
            "func f():\n\tvar x = [1,\n\t\t§\n§ func after(): pass\n",
            "func f():\r\n\tvar x = [1,\r\n\t\t§\r\n§ func after(): pass\r\n",
            "var x = [1,\n\t§\n§ func after(): pass\n",
            "var x = [1,\r\n\t§\r\n§ func after(): pass\r\n",
            "class C:\n\tsignal changed(value,\n\t\t§\n§\nvar after = 3\n",
            "class C:\r\n\tsignal changed(value,\r\n\t\t§\r\n§\r\nvar after = 3\r\n",
            "class C:\n\tsignal changed(\n\t\t§\n§\nvar after = 3\n",
            "class C:\r\n\tsignal changed(\r\n\t\t§\r\n§\r\nvar after = 3\r\n",
            "class C:\n\tfunc method(value,\n\t\t§\n§\nvar after = 3\n",
            "class C:\r\n\tfunc method(value,\r\n\t\t§\r\n§\r\nvar after = 3\r\n",
            "class C:\n\tfunc method(\n\t\t§\n§\nvar after = 3\n",
            "class C:\r\n\tfunc method(\r\n\t\t§\r\n§\r\nvar after = 3\r\n",
            "class C:\n\tenum E {\n\t\tA,\n\t\t§\n§\nvar after = 3\n",
            "class C:\r\n\tenum E {\r\n\t\tA,\r\n\t\t§\r\n§\r\nvar after = 3\r\n",
            "class C:\n\tenum E {\n\t\t§\n§\nvar after = 3\n",
            "class C:\r\n\tenum E {\r\n\t\t§\r\n§\r\nvar after = 3\r\n",
            // Error-before-operand continuation, stray CR / malformed backslash line-start stops,
            // and EOF signal/function/enum/lambda header lexical attribution (LF and CRLF).
            "func f():\n\tvar x = (1 +\n\t\t\\\n§ 2\nvar after = 3\n",
            "func f():\r\n\tvar x = (1 +\r\n\t\t\\\r\n§ 2\r\nvar after = 3\r\n",
            "func f():\n\tvar x = (1 +\n§ \\\n§ 2\nvar after = 3\n",
            "func f():\r\n\tvar x = (1 +\r\n§ \\\r\n§ 2\r\nvar after = 3\r\n",
            "var x = [1,\n\t§\n\rfunc after(): pass\n",
            "var x = [1,\r\n\t§\r\n\rfunc after(): pass\r\n",
            "var x = [1,\n\t§\n\\ func after(): pass\n",
            "var x = [1,\r\n\t§\r\n\\ func after(): pass\r\n",
            "func f():\n\tvar x = [1,\n\t\t§\n\rfunc after(): pass\n",
            "func f():\r\n\tvar x = [1,\r\n\t\t§\r\n\rfunc after(): pass\r\n",
            "func f():\n\tvar x = [1,\n\t\t§\n\\ func after(): pass\n",
            "func f():\r\n\tvar x = [1,\r\n\t\t§\r\n\\ func after(): pass\r\n",
            "signal changed(value,\n\t§",
            "signal changed(value,\r\n\t§",
            "signal changed(value,\n\t§\n",
            "signal changed(value,\r\n\t§\r\n",
            "signal changed(\n\t§",
            "signal changed(\r\n\t§",
            "signal changed(value\n\t§",
            "signal changed(value\r\n\t§",
            "class C:\n\tsignal changed(value,\n\t\t§\n§",
            "class C:\r\n\tsignal changed(value,\r\n\t\t§\r\n§",
            "func method(value,\n\t§",
            "func method(value,\r\n\t§",
            "func method(value,\n\t§\n",
            "func method(value,\r\n\t§\r\n",
            "func method(\n\t§",
            "func method(\r\n\t§",
            "func method(value\n\t§",
            "func method(value\r\n\t§",
            "class C:\n\tfunc method(value,\n\t\t§\n§",
            "class C:\r\n\tfunc method(value,\r\n\t\t§\r\n§",
            "enum E { A,\n\t§",
            "enum E { A,\r\n\t§",
            "enum E { A,\n\t§\n",
            "enum E { A,\r\n\t§\r\n",
            "enum E {\n\t§",
            "enum E {\r\n\t§",
            "enum E { A\n\t§",
            "enum E { A\r\n\t§",
            "class C:\n\tenum E {\n\t\tA,\n\t\t§\n§",
            "class C:\r\n\tenum E {\r\n\t\tA,\r\n\t\t§\r\n§",
            "var f = func(value,\n\t§",
            "var f = func(value,\r\n\t§",
            "var f = func(\n\t§",
            "var f = func(\r\n\t§",
            // Malformed backslash/stray-CR continuations and stray-CR + tabs leading indentation (LF and CRLF).
            "var x = [1, \\\n\\ \nfunc after(): pass\n",
            "var x = [1, \\\r\n\\ \r\nfunc after(): pass\r\n",
            "var x = [1, \\\n\r # bad\nfunc after(): pass\n",
            "var x = [1, \\\r\n\r # bad\r\nfunc after(): pass\r\n",
            "class C:\n\tsignal changed(value,\n\r\t\t§",
            "class C:\r\n\tsignal changed(value,\r\n\r\t\t§",
            "class C:\n\tsignal changed(value,\n\r\t\t§\n",
            "class C:\r\n\tsignal changed(value,\r\n\r\t\t§\r\n",
            "class C:\n\tfunc method(value,\n\r\t\t§",
            "class C:\r\n\tfunc method(value,\r\n\r\t\t§",
            "class C:\n\tenum E {\n\t\tA,\n\r\t\t§",
            "class C:\r\n\tenum E {\r\n\t\tA,\r\n\r\t\t§",
            "class C:\n\tsignal changed(value,\n\r§",
            "class C:\r\n\tsignal changed(value,\r\n\r§",
            // Multi-error declaration line prefixes and advance() multiline post-newline filtering (LF and CRLF).
            "var x = [1,\n\r§func after(): pass\n",
            "var x = [1,\r\n\r§func after(): pass\r\n",
            "var x = [1,\n§§func after(): pass\n",
            "var x = [1,\r\n§§func after(): pass\r\n",
            "func f():\n\tvar x = [1,\n§\n\t\t2\n\tpass\n",
            "func f():\r\n\tvar x = [1,\r\n§\r\n\t\t2\r\n\tpass\r\n",
            "func f():\n\tvar x = [1,\n§\n\t\t2]\n\tpass\n",
            "func f():\r\n\tvar x = [1,\r\n§\r\n\t\t2]\r\n\tpass\r\n",
            "func f():\n\tvar x = [1,\n\t\t§\n§\n\t\t2\n\tpass\n",
            "func f():\r\n\tvar x = [1,\r\n\t\t§\r\n§\r\n\t\t2\r\n\tpass\r\n",
            // Matched inner closer inside missing outer array & per-logical-line advance() filtering (LF and CRLF).
            "func f():\n\tvar x = [(1\n§\n\t\t)\n\tpass\n",
            "func f():\r\n\tvar x = [(1\r\n§\r\n\t\t)\r\n\tpass\r\n",
            "func f():\n\tvar x = [(1\n§\n\t\t)]\n\tpass\n",
            "func f():\r\n\tvar x = [(1\r\n§\r\n\t\t)]\r\n\tpass\r\n",
            "func f():\n\tvar x = [(1\n§\n\t\t}\n\tpass\n",
            "func f():\r\n\tvar x = [(1\r\n§\r\n\t\t}\r\n\tpass\r\n",
            "func f():\n\tvar x = [1,\n§\n\t\t§ 2\n\tpass\n",
            "func f():\r\n\tvar x = [1,\r\n§\r\n\t\t§ 2\r\n\tpass\r\n",
            "func f():\n\tvar x = [1,\n§\n\t\t§\n\t\t2\n\tpass\n",
            "func f():\r\n\tvar x = [1,\r\n§\r\n\t\t§\r\n\t\t2\r\n\tpass\r\n",
            "func f():\n\tvar x = [1,\n\t\t§ \\\n§\n§\n\t\t2\n\tpass\n",
            "func f():\r\n\tvar x = [1,\r\n\t\t§ \\\r\n§\r\n§\r\n\t\t2\r\n\tpass\r\n",
            // Logical line start row ownership across bare backslashes, comment lines, and stray CR (LF and CRLF).
            "func f():\n\tvar x = [1,\n§\n\t\t\\\n§\n\t\t2\n\tpass\n",
            "func f():\r\n\tvar x = [1,\r\n§\r\n\t\t\\\r\n§\r\n\t\t2\r\n\tpass\r\n",
            "func f():\n\tvar x = [1,\n\t\t§\n\\\n§\n\t\t2\n\tpass\n",
            "func f():\r\n\tvar x = [1,\r\n\t\t§\r\n\\\r\n§\r\n\t\t2\r\n\tpass\r\n",
            "func f():\n\tvar x = [1,\n\t\t§\n\\\n§\nvar after = 3\n",
            "func f():\r\n\tvar x = [1,\r\n\t\t§\r\n\\\r\n§\r\nvar after = 3\r\n",
            "func f():\n\tvar x = [1,\n\t\t\\\n§\n§\nvar after = 3\n",
            "func f():\r\n\tvar x = [1,\r\n\t\t\\\r\n§\r\n§\r\nvar after = 3\r\n",
            "func f():\n\tvar x = [1,\n\t\t§ \\\n§ §\n§\nvar after = 3\n",
            "func f():\r\n\tvar x = [1,\r\n\t\t§ \\\r\n§ §\r\n§\r\nvar after = 3\r\n",
            "func f():\n\tvar x = (1 +\n\t\t\r # in\n\r # out\nvar after = 3\n",
            "func f():\r\n\tvar x = (1 +\r\n\t\t\r # in\r\n\r # out\r\nvar after = 3\r\n",
            "func f():\n\tvar x = [1,\n\t\t\\\n\t# comment\n§\n\t\t2\n\tpass\n",
            "func f():\r\n\tvar x = [1,\r\n\t\t\\\r\n\t# comment\r\n§\r\n\t\t2\r\n\tpass\r\n",
            "func f():\n\tvar x = [1,\n\\\n\t\t# comment\n§\n\t\t2\n\tpass\n",
            "func f():\r\n\tvar x = [1,\r\n\\\r\n\t\t# comment\r\n§\r\n\t\t2\r\n\tpass\r\n",
            // Operand logical start line continuation & consumeCloser in-scope post-newline claim (LF and CRLF).
            "func f():\n\tvar x = (1\n§\nvar after = 3\n",
            "func f():\r\n\tvar x = (1\r\n§\r\nvar after = 3\r\n",
            "func f():\n\tvar x = (1 +\n§\n\t\t\\\n2\nvar after = 3\n",
            "func f():\r\n\tvar x = (1 +\r\n§\r\n\t\t\\\r\n2\r\nvar after = 3\r\n",
            "func f():\n\tvar x = (1 +\n§ \\\n2\nvar after = 3\n",
            "func f():\r\n\tvar x = (1 +\r\n§ \\\r\n2\r\nvar after = 3\r\n",
            "func f():\n\tvar x = (1 +\n\t\t\\\n§ 2\nvar after = 3\n",
            "func f():\r\n\tvar x = (1 +\r\n\t\t\\\r\n§ 2\r\nvar after = 3\r\n",
            "func f():\n\tvar x = (1 +\n§ \\\n§ 2\nvar after = 3\n",
            "func f():\r\n\tvar x = (1 +\r\n§ \\\r\n§ 2\r\nvar after = 3\r\n",
            "func f():\n\tvar x = [1\n\t\t\\\n§\nvar after = 3\n",
            "func f():\r\n\tvar x = [1\r\n\t\t\\\r\n§\r\nvar after = 3\r\n",
            "func f():\n\tvar x = [1\n\t\t\\\n§\n§\nvar after = 3\n",
            "func f():\r\n\tvar x = [1\r\n\t\t\\\r\n§\r\n§\r\nvar after = 3\r\n",
            "func f():\n\tvar x = [1\n\t\t§\n§\nvar after = 3\n",
            "func f():\r\n\tvar x = [1\r\n\t\t§\r\n§\r\nvar after = 3\r\n",
            "func f():\n\tassert((1\n\t\t§\n)\n",
            "func f():\r\n\tassert((1\r\n\t\t§\r\n)\r\n",
            "func f():\n\tassert([1\n\t\t§\n)\n",
            "func f():\r\n\tassert([1\r\n\t\t§\r\n)\r\n",
            "func f():\n\tvar x = (1\n\t§ var after = 3\n\t)\n",
            "func f():\r\n\tvar x = (1\r\n\t§ var after = 3\r\n\t)\r\n",
            "func f():\n\tvar x = (1\n\t§ var after = 3\n",
            "func f():\r\n\tvar x = (1\r\n\t§ var after = 3\r\n",
            "func f():\n\tvar x = (1\n\t\t§\n\t§ var after = 3\n\t)\n",
            "func f():\r\n\tvar x = (1\r\n\t\t§\r\n\t§ var after = 3\r\n\t)\r\n",
            "func f():\n\tvar x = (1\n\t\t§\n\t§ var after = 3\n",
            "func f():\r\n\tvar x = (1\r\n\t\t§\r\n\t§ var after = 3\r\n",
            "func f():\n\tvar x = (1\n\t\t§",
            "func f():\r\n\tvar x = (1\r\n\t\t§",
            "func f():\n\tvar x = (1\n\t\t§\n",
            "func f():\r\n\tvar x = (1\r\n\t\t§\r\n",
            "func f():\n\tvar x = [1\n\t\t§",
            "func f():\r\n\tvar x = [1\r\n\t\t§",
            "func f():\n\tvar x = [1\n\t\t§\n",
            "func f():\r\n\tvar x = [1\r\n\t\t§\r\n",
            "func f():\n\tvar x = f(1\n\t\t§",
            "func f():\r\n\tvar x = f(1\r\n\t\t§",
            "func f():\n\tvar x = f(1\n\t\t§\n",
            "func f():\r\n\tvar x = f(1\r\n\t\t§\r\n",
            "func f():\n\tvar x = (1\n§",
            "func f():\r\n\tvar x = (1\r\n§",
            "func f():\n\tvar x = (1\n§\n",
            "func f():\r\n\tvar x = (1\r\n§\r\n",
            "func f():\n\tvar x = (1\n\t§ \\\n\tvar after = 3\n\t)\n",
            "func f():\r\n\tvar x = (1\r\n\t§ \\\r\n\tvar after = 3\r\n\t)\r\n",
            "func f():\n\tvar x = (1\n\t\t§\n\t§ \\\n\tvar after = 3\n\t)\n",
            "func f():\r\n\tvar x = (1\r\n\t\t§\r\n\t§ \\\r\n\tvar after = 3\r\n\t)\r\n",
            "func f():\n\tvar x = (1 +\n\t§ var after = 3\n\t)\n",
            "func f():\r\n\tvar x = (1 +\r\n\t§ var after = 3\r\n\t)\r\n",
            "func f():\n\tvar x = (1 +\n\t§ var after = 3\n",
            "func f():\r\n\tvar x = (1 +\r\n\t§ var after = 3\r\n",
            "func f():\n\tvar x = (1 +\n\t\t§\n\t§ var after = 3\n\t)\n",
            "func f():\r\n\tvar x = (1 +\r\n\t\t§\r\n\t§ var after = 3\r\n\t)\r\n",
            "func f():\n\tvar x = (1 +\n\t§ \\\n\tvar after = 3\n\t)\n",
            "func f():\r\n\tvar x = (1 +\r\n\t§ \\\r\n\tvar after = 3\r\n\t)\r\n",
            "func f():\n\tvar x = (1 +\n\t§\n\t)\n",
            "func f():\r\n\tvar x = (1 +\r\n\t§\r\n\t)\r\n",
            "func f():\n\tvar x = (1 +\n\t§ if ready else 2)\n",
            "func f():\r\n\tvar x = (1 +\r\n\t§ if ready else 2)\r\n",
            "func f():\n\tvar x = (1 +\n\t§ if ready:\n\t\tpass\n\t)\n",
            "func f():\r\n\tvar x = (1 +\r\n\t§ if ready:\r\n\t\tpass\r\n\t)\r\n",
            "func f():\n\tvar x = (1 +\n\t§ if ready:\n\t\tpass\n",
            "func f():\r\n\tvar x = (1 +\r\n\t§ if ready:\r\n\t\tpass\r\n",
            "var x = (1\nif func(): pass\nelse 2)\n",
            "var x = (1\r\nif func(): pass\r\nelse 2)\r\n",
            "var x = (1\nif func named(a: int) -> int: return a\nelse 2)\n",
            "var x = (1\r\nif func named(a: int) -> int: return a\r\nelse 2)\r\n",
            "var x = (1\nif func(): if ready: pass\nelse 2)\n",
            "var x = (1\r\nif func(): if ready: pass\r\nelse 2)\r\n",
            "var x = (1\nif func(): for i in a: pass\nelse 2)\n",
            "var x = (1\r\nif func(): for i in a: pass\r\nelse 2)\r\n",
            "var x = (1\nif func():\n\tif ready: pass\nelse 2)\n",
            "var x = (1\r\nif func():\r\n\tif ready: pass\r\nelse 2)\r\n",
            "var x = (1\nif a and func(): pass\nelse 2)\n",
            "var x = (1\r\nif a and func(): pass\r\nelse 2)\r\n",
            "func f():\n\tvar x = (1 +\n\t§ if obj.func():\n\t\tpass\n\t)\n",
            "func f():\r\n\tvar x = (1 +\r\n\t§ if obj.func():\r\n\t\tpass\r\n\t)\r\n",
            "func f():\n\tvar x = (1 +\n\t§ if (func(): pass):\n\t\tpass\n\t)\n",
            "func f():\r\n\tvar x = (1 +\r\n\t§ if (func(): pass):\r\n\t\tpass\r\n\t)\r\n",
            "func f():\n\tvar x = (1 +\n\t§ if func(): pass\n\telse 2)\n",
            "func f():\r\n\tvar x = (1 +\r\n\t§ if func(): pass\r\n\telse 2)\r\n",
            "func f():\n\tvar x = (1 +\n\tif func():\n\t\tpass\n\t:\n\t\tpass\n\t)\n",
            "func f():\r\n\tvar x = (1 +\r\n\tif func():\r\n\t\tpass\r\n\t:\r\n\t\tpass\r\n\t)\r\n",
            "func f():\n\tvar x = (1 +\n\tif func():\n\t\tpass\n\tand ready:\n\t\tpass\n\t)\n",
            "func f():\r\n\tvar x = (1 +\r\n\tif func():\r\n\t\tpass\r\n\tand ready:\r\n\t\tpass\r\n\t)\r\n",
            "func f():\n\tvar x = (1 +\n\tif func():\n\t\tvar s = \"\"\"\ncol0\n\"\"\"\n\t:\n\t\tpass\n\t)\n",
            "func f():\r\n\tvar x = (1 +\r\n\tif func():\r\n\t\tvar s = \"\"\"\r\ncol0\r\n\"\"\"\r\n\t:\r\n\t\tpass\r\n\t)\r\n",
            "var x = (1\nif func():\n\treturn true\nelse 2)\n",
            "var x = (1\r\nif func():\r\n\treturn true\r\nelse 2)\r\n",
            "var x = (1\nif §func(): pass\nelse 2)\n",
            "var x = (1\r\nif §func(): pass\r\nelse 2)\r\n",
            "var x = (1\nif func§(): pass\nelse 2)\n",
            "var x = (1\r\nif func§(): pass\r\nelse 2)\r\n",
            "func f():\n\tvar x = (1 +\n\t§ if obj.else():\n\t\tpass\n\t)\n",
            "func f():\r\n\tvar x = (1 +\r\n\t§ if obj.else():\r\n\t\tpass\r\n\t)\r\n",
            "func f():\n\tvar x = (1 +\n\t§ if true if ready else false:\n\t\tpass\n\t)\n",
            "func f():\r\n\tvar x = (1 +\r\n\t§ if true if ready else false:\r\n\t\tpass\r\n\t)\r\n",
            "func f():\n\tvar x = (1 +\n\t§ if a if b else c else 2)\n",
            "func f():\r\n\tvar x = (1 +\r\n\t§ if a if b else c else 2)\r\n",
            "var x = {1\nif ready: 3, 4: 5}\n",
            "var x = {1\r\nif ready: 3, 4: 5}\r\n",
            "func f():\n\tvar x = {1\n\tif ready:\n\t\tpass\n",
            "func f():\r\n\tvar x = {1\r\n\tif ready:\r\n\t\tpass\r\n",
            "func f():\n\tvar x = (1 +\n\tif func(): §\n\t\tpass\n\t:\n\t\tpass\n\t)\n",
            "func f():\r\n\tvar x = (1 +\r\n\tif func(): §\r\n\t\tpass\r\n\t:\r\n\t\tpass\r\n\t)\r\n",
            "func f():\n\tvar x = (1 +\n\t§ if $else:\n\t\tpass\n\t)\n",
            "func f():\r\n\tvar x = (1 +\r\n\t§ if $else:\r\n\t\tpass\r\n\t)\r\n",
            "func f():\n\tvar x = (1 +\n\t§ if %else:\n\t\tpass\n\t)\n",
            "func f():\r\n\tvar x = (1 +\r\n\t§ if %else:\r\n\t\tpass\r\n\t)\r\n",
            "func f():\n\tvar x = (1 +\n\t§ if $parent/else:\n\t\tpass\n\t)\n",
            "func f():\r\n\tvar x = (1 +\r\n\t§ if $parent/else:\r\n\t\tpass\r\n\t)\r\n",
            "func f():\n\tvar x = (1 +\n\t§ if $if:\n\t\tpass\n\t)\n",
            "func f():\r\n\tvar x = (1 +\r\n\t§ if $if:\r\n\t\tpass\r\n\t)\r\n",
            "func f():\n\tvar x = (1 +\n\t§ if $parent/func():\n\t\tpass\n\t)\n",
            "func f():\r\n\tvar x = (1 +\r\n\t§ if $parent/func():\r\n\t\tpass\r\n\t)\r\n",
            "func f():\n\tvar x = {1\n\tif ready:\n\t\tpass\n\t}\n",
            "func f():\r\n\tvar x = {1\r\n\tif ready:\r\n\t\tpass\r\n\t}\r\n",
            "func f():\n\tvar x = {1\n\tif ready:\n\t\tfoo()\n\t}\n",
            "func f():\r\n\tvar x = {1\r\n\tif ready:\r\n\t\tfoo()\r\n\t}\r\n",
            "var x = {1 +\n§ if ready: 3, 4: 5}\n",
            "var x = {1 +\r\n§ if ready: 3, 4: 5}\r\n",
            "func f():\n\tvar x = {1 +\n\t§ if ready:\n\t\tpass\n\t}\n",
            "func f():\r\n\tvar x = {1 +\r\n\t§ if ready:\r\n\t\tpass\r\n\t}\r\n",
            "func f():\n\tvar x = (1 +\n\t§ \\\n\tif ready:\n\t\tpass\n\t)\n",
            "func f():\r\n\tvar x = (1 +\r\n\t§ \\\r\n\tif ready:\r\n\t\tpass\r\n\t)\r\n",
            "func f():\n\tvar x = [1\n\tif \"%s\" % func():\n\t\tpass\n\telse 3, 4]\n",
            "func f():\r\n\tvar x = [1\r\n\tif \"%s\" % func():\r\n\t\tpass\r\n\telse 3, 4]\r\n",
            "func f():\n\tvar x = [1\n\tif a / func():\n\t\tpass\n\telse 3, 4]\n",
            "func f():\r\n\tvar x = [1\r\n\tif a / func():\r\n\t\tpass\r\n\telse 3, 4]\r\n",
            "func f():\n\tvar x = [1\n\tif $parent/a % func():\n\t\tpass\n\telse 3, 4]\n",
            "func f():\r\n\tvar x = [1\r\n\tif $parent/a % func():\r\n\t\tpass\r\n\telse 3, 4]\r\n",
            "func f():\n\tvar x = [1\n\tif ($parent/a) % func():\n\t\tpass\n\telse 3, 4]\n",
            "func f():\r\n\tvar x = [1\r\n\tif ($parent/a) % func():\r\n\t\tpass\r\n\telse 3, 4]\r\n",
            "func f():\n\tvar x = (1 +\n\t§ if $parent/%else:\n\t\tpass\n\t)\n",
            "func f():\r\n\tvar x = (1 +\r\n\t§ if $parent/%else:\r\n\t\tpass\r\n\t)\r\n",
            "func f():\n\tvar x = (1 +\n\t§ if not %else:\n\t\tpass\n\t)\n",
            "func f():\r\n\tvar x = (1 +\r\n\t§ if not %else:\r\n\t\tpass\r\n\t)\r\n",
            "func f():\n\tvar x = (1 +\n\t§ if a and %else:\n\t\tpass\n\t)\n",
            "func f():\r\n\tvar x = (1 +\r\n\t§ if a and %else:\r\n\t\tpass\r\n\t)\r\n",
            "func f():\n\tvar x = (1 +\n\t§ if obj.if():\n\t\tpass\n\t)\n",
            "func f():\r\n\tvar x = (1 +\r\n\t§ if obj.if():\r\n\t\tpass\r\n\t)\r\n",
            "var x = {1 +\n§ if ready: match(0), 4: 5}\n",
            "var x = {1 +\r\n§ if ready: match(0), 4: 5}\r\n",
            "var x = {1\nif ready:\n3, 4: 5}\n",
            "var x = {1\r\nif ready:\r\n3, 4: 5}\r\n",
            "func f():\n\tvar x = {1\n\tif ready:\n\t\tmatch z:\n\t\t\t1: pass\n\t}\n",
            "func f():\r\n\tvar x = {1\r\n\tif ready:\r\n\t\tmatch z:\r\n\t\t\t1: pass\r\n\t}\r\n",
            "func f():\n\tvar x = (1 +\n\tif func(): 0x\n\t\tpass\n\t:\n\t\tpass\n\t)\n",
            "func f():\r\n\tvar x = (1 +\r\n\tif func(): 0x\r\n\t\tpass\r\n\t:\r\n\t\tpass\r\n\t)\r\n",
            "func f():\n\tvar x = (1 +\n\tif func(): 0b\n\t\tpass\n\t:\n\t\tpass\n\t)\n",
            "func f():\r\n\tvar x = (1 +\r\n\tif func(): 0b\r\n\t\tpass\r\n\t:\r\n\t\tpass\r\n\t)\r\n",
            "func f():\n\tvar x = (1 +\n\tif func(): 0x_\n\t\tpass\n\t:\n\t\tpass\n\t)\n",
            "func f():\r\n\tvar x = (1 +\r\n\tif func(): 0x_\r\n\t\tpass\r\n\t:\r\n\t\tpass\r\n\t)\r\n",
            "var x = (1\nif func(): 0x_1\nelse 2)\n",
            "var x = (1\r\nif func(): 0x_1\r\nelse 2)\r\n",
            "func f():\n\tvar x = (1 +\n\t§ if $parent / else:\n\t\tpass\n\t)\n",
            "func f():\r\n\tvar x = (1 +\r\n\t§ if $parent / else:\r\n\t\tpass\r\n\t)\r\n",
            "func f():\n\tvar x = (1 +\n\t§ if $parent / %else:\n\t\tpass\n\t)\n",
            "func f():\r\n\tvar x = (1 +\r\n\t§ if $parent / %else:\r\n\t\tpass\r\n\t)\r\n",
            "func f():\n\tvar x = [1\n\tif $a % func():\n\t\tpass\n\telse 3, 4]\n",
            "func f():\r\n\tvar x = [1\r\n\tif $a % func():\r\n\t\tpass\r\n\telse 3, 4]\r\n",
            "func f():\n\tvar x = [1\n\tif ($a) % func():\n\t\tpass\n\telse 3, 4]\n",
            "func f():\r\n\tvar x = [1\r\n\tif ($a) % func():\r\n\t\tpass\r\n\telse 3, 4]\r\n",
            "func f():\n\tvar x = [1\n\tif match%func():\n\t\tpass\n\telse 3, 4]\n",
            "func f():\r\n\tvar x = [1\r\n\tif match%func():\r\n\t\tpass\r\n\telse 3, 4]\r\n",
            "func f():\n\tvar x = [1\n\tif when%func():\n\t\tpass\n\telse 3, 4]\n",
            "func f():\r\n\tvar x = [1\r\n\tif when%func():\r\n\t\tpass\r\n\telse 3, 4]\r\n",
            "var x = (1\nif $a else 2)\n",
            "var x = (1\r\nif $a else 2)\r\n",
            "var x = {1\nif ready:\n\t3, 4: 5}\n",
            "var x = {1\r\nif ready:\r\n\t3, 4: 5}\r\n",
            "var x = {1 +\n§ if ready: match + 1, 4: 5}\n",
            "var x = {1 +\r\n§ if ready: match + 1, 4: 5}\r\n",
            "func f():\n\tvar x = {1\n\tif ready: match z:\n\t\t1: pass\n\t}\n",
            "func f():\r\n\tvar x = {1\r\n\tif ready: match z:\r\n\t\t1: pass\r\n\t}\r\n",
            "func f():\n\tvar x = {1\n\tif ready:\n\t\tprint(1, 2)\n\t}\n",
            "func f():\r\n\tvar x = {1\r\n\tif ready:\r\n\t\tprint(1, 2)\r\n\t}\r\n",
            "func f():\n\tvar x = {1\n\tif ready: match (z):\n\t\t1: pass\n\t}\n",
            "func f():\r\n\tvar x = {1\r\n\tif ready: match (z):\r\n\t\t1: pass\r\n\t}\r\n",
            "func f():\n\tvar x = {1\n\tif ready: match [z]:\n\t\t1: pass\n\t}\n",
            "func f():\r\n\tvar x = {1\r\n\tif ready: match [z]:\r\n\t\t1: pass\r\n\t}\r\n",
            "var x = {1 +\n§ if ready: match[0], 4: 5}\n",
            "var x = {1 +\r\n§ if ready: match[0], 4: 5}\r\n",
            "var x = {1\nif ready:\n\t3 \\\n\t, 4: 5}\n",
            "var x = {1\r\nif ready:\r\n\t3 \\\r\n\t, 4: 5}\r\n",
            "var x = {1\nif ready: \\\n\t3, 4: 5}\n",
            "var x = {1\r\nif ready: \\\r\n\t3, 4: 5}\r\n",
            "func f():\n\tvar x = {1\n\tif ready: match .5:\n\t\t1: pass\n\t}\n",
            "func f():\r\n\tvar x = {1\r\n\tif ready: match .5:\r\n\t\t1: pass\r\n\t}\r\n",
            "var x = {1\nif ready: match.member, 4: 5}\n",
            "var x = {1\r\nif ready: match.member, 4: 5}\r\n",
            "var x = {1\nif ready: match.member}\n",
            "var x = {1\r\nif ready: match.member}\r\n",
            "var x = {1\nif ready:\n\t3}\n",
            "var x = {1\r\nif ready:\r\n\t3}\r\n",
            "var x = {1\nif ready:\n\t3 \\\n\t}\n",
            "var x = {1\r\nif ready:\r\n\t3 \\\r\n\t}\r\n",
            "var x = {1\nif ready:\n\t{2: 3}}\n",
            "var x = {1\r\nif ready:\r\n\t{2: 3}}\r\n",
            "func f():\n\tif ready: match (z) §:\n\t\t1: pass\n",
            "func f():\r\n\tif ready: match (z) §:\r\n\t\t1: pass\r\n",
            "func f():\n\tvar x = {1\n\tif ready: match (z) §:\n\t\t1: pass\n\t}\n",
            "func f():\r\n\tvar x = {1\r\n\tif ready: match (z) §:\r\n\t\t1: pass\r\n\t}\r\n",
            "func f():\n\tif cond §:\n\t\tpass\n\telif alt ¤:\n\t\tpass\n",
            "func f():\r\n\tif cond §:\r\n\t\tpass\r\n\telif alt ¤:\r\n\t\tpass\r\n",
            "func f():\n\twhile cond §:\n\t\tpass\n",
            "func f():\r\n\twhile cond §:\r\n\t\tpass\r\n",
            "func f():\n\tfor x § in xs ¤:\n\t\tpass\n",
            "func f():\r\n\tfor x § in xs ¤:\r\n\t\tpass\r\n",
            "func f():\n\tmatch z:\n\t\t1 §:\n\t\t\tpass\n\t\t2 when ok ¤:\n\t\t\tpass\n",
            "func f():\r\n\tmatch z:\r\n\t\t1 §:\r\n\t\t\tpass\r\n\t\t2 when ok ¤:\r\n\t\t\tpass\r\n",
            "func f():\n\tvar x = {1\n\tif ready:\n\t\t§ pass}\n",
            "func f():\r\n\tvar x = {1\r\n\tif ready:\r\n\t\t§ pass}\r\n",
            "func f():\n\tvar x = {1\n\tif ready:\n\t\ty = 2}\n",
            "func f():\r\n\tvar x = {1\r\n\tif ready:\r\n\t\ty = 2}\r\n",
            "func f():\n\tvar x = {1\n\tif ready:\n\t\ty += 2}\n",
            "func f():\r\n\tvar x = {1\r\n\tif ready:\r\n\t\ty += 2}\r\n",
            "var x = {1\nif ready:\n\t3 == 4}\n",
            "var x = {1\r\nif ready:\r\n\t3 == 4}\r\n",
            "func f():\n\tvar x = {1\n\tif ready: match .5: §\n\t\t1: pass\n\t}\n",
            "func f():\r\n\tvar x = {1\r\n\tif ready: match .5: §\r\n\t\t1: pass\r\n\t}\r\n",
            "func f():\n\tvar x = {1\n\tif ready: match .5: § # c\n\t\t1: pass\n\t}\n",
            "func f():\r\n\tvar x = {1\r\n\tif ready: match .5: § # c\r\n\t\t1: pass\r\n\t}\r\n",
            "func f():\n\tvar x = {1\n\tif ready: match .5: \\ §\n\t\t1: pass\n\t}\n",
            "func f():\r\n\tvar x = {1\r\n\tif ready: match .5: \\ §\r\n\t\t1: pass\r\n\t}\r\n",
            "var x = {1\nif ready: match .5: \\\n\t1, 4: 5}\n",
            "var x = {1\r\nif ready: match .5: \\\r\n\t1, 4: 5}\r\n",
            "func f():\n\tif ready:\n\t\tpass\n\telse §:\n\t\tpass\n",
            "func f():\r\n\tif ready:\r\n\t\tpass\r\n\telse §:\r\n\t\tpass\r\n",
            "func f():\n\tmatch z: §\n\t\t1: pass\n",
            "func f():\r\n\tmatch z: §\r\n\t\t1: pass\r\n",
            "func f():\n\tmatch z: §\n\tpass\n",
            "func f():\r\n\tmatch z: §\r\n\tpass\r\n",
            "func f():\n\tif ready: §\n\t\tpass\n",
            "func f():\r\n\tif ready: §\r\n\t\tpass\r\n",
            "func f():\n\tif ready:\n\t\tpass\n\telif ok: §\n\t\tpass\n",
            "func f():\r\n\tif ready:\r\n\t\tpass\r\n\telif ok: §\r\n\t\tpass\r\n",
            "func f():\n\tif ready:\n\t\tpass\n\telse: §\n\t\tpass\n",
            "func f():\r\n\tif ready:\r\n\t\tpass\r\n\telse: §\r\n\t\tpass\r\n",
            "func f():\n\twhile ok: §\n\t\tpass\n",
            "func f():\r\n\twhile ok: §\r\n\t\tpass\r\n",
            "func f():\n\tfor x in xs: §\n\t\tpass\n",
            "func f():\r\n\tfor x in xs: §\r\n\t\tpass\r\n",
            "func f(): §\n\tpass\n",
            "func f(): §\r\n\tpass\r\n",
            "class A: §\n\tpass\n",
            "class A: §\r\n\tpass\r\n",
            "func f():\n\tmatch z:\n\t\t1: §\n\t\t\tpass\n",
            "func f():\r\n\tmatch z:\r\n\t\t1: §\r\n\t\t\tpass\r\n",
            "var p:\n\tget: §\n\t\treturn 1\n",
            "var p:\r\n\tget: §\r\n\t\treturn 1\r\n",
            "func f():\n\tmatch z:\n§\n\t1: pass\n",
            "func f():\r\n\tmatch z:\r\n§\r\n\t1: pass\r\n",
            "func f():\n\tif ready:\n\t\tpass\n§\n",
            "func f():\r\n\tif ready:\r\n\t\tpass\r\n§\r\n",
            "var x = {1\nif ready:\n\tfunc(): y = 2}\n",
            "var x = {1\r\nif ready:\r\n\tfunc(): y = 2}\r\n",
            "var x = {1\nif ready:\n\tfunc named(a = 1) -> void: y = 2}\n",
            "var x = {1\r\nif ready:\r\n\tfunc named(a = 1) -> void: y = 2}\r\n",
            "var x = {1\nif ready:\n\t1 + func(): y = 2}\n",
            "var x = {1\r\nif ready:\r\n\t1 + func(): y = 2}\r\n",
            "func f():\n\tvar x = {1\n\tif ready:\n\t\ty = func(): z = 3}\n",
            "func f():\r\n\tvar x = {1\r\n\tif ready:\r\n\t\ty = func(): z = 3}\r\n",
            "func f():\n\tvar x = {1\n\tif ready:\n\t\tfunc.x = 2}\n",
            "func f():\r\n\tvar x = {1\r\n\tif ready:\r\n\t\tfunc.x = 2}\r\n",
            "func f():\n\tvar x = {1\n\tif ready:\n\t\tobj.func = 2}\n",
            "func f():\r\n\tvar x = {1\r\n\tif ready:\r\n\t\tobj.func = 2}\r\n",
            "func f():\n\tmatch z: § pass\n",
            "func f():\r\n\tmatch z: § pass\r\n",
            "func f():\n\tif ready: § pass\n",
            "func f():\r\n\tif ready: § pass\r\n",
            "func f():\n\twhile ok: § break\n",
            "func f():\r\n\twhile ok: § break\r\n",
            "func f():\n\tfor x in xs: § continue\n",
            "func f():\r\n\tfor x in xs: § continue\r\n",
            "func f(): § pass\n",
            "func f(): § pass\r\n",
            "func f():\n\tmatch z:\n\t\t1: § pass\n",
            "func f():\r\n\tmatch z:\r\n\t\t1: § pass\r\n",
            "class A: § pass\n",
            "class A: § pass\r\n",
            "func f():\n\tif ready: pass\n\t§\n",
            "func f():\r\n\tif ready: pass\r\n\t§\r\n",
            "func f():\n\tvar x = {1\n\tif ready:\n\t\tobj. § func().x = 2}\n",
            "func f():\r\n\tvar x = {1\r\n\tif ready:\r\n\t\tobj. § func().x = 2}\r\n",
            "func f():\n\tvar x = {1\n\tif ready:\n\t\tobj. 0x func().x = 2}\n",
            "func f():\r\n\tvar x = {1\r\n\tif ready:\r\n\t\tobj. 0x func().x = 2}\r\n",
            "func f():\n\tvar x = {1\n\tif ready:\n\t\tobj. \\ func().x = 2}\n",
            "func f():\r\n\tvar x = {1\r\n\tif ready:\r\n\t\tobj. \\ func().x = 2}\r\n",
            "func f():\n\tmatch z:\n\t\t1 § pass\n\t\t2: pass\n",
            "func f():\r\n\tmatch z:\r\n\t\t1 § pass\r\n\t\t2: pass\r\n",
            "func f():\n\tmatch z:\n\t\t1 pass § foo\n\t\t2: pass\n",
            "func f():\r\n\tmatch z:\r\n\t\t1 pass § foo\r\n\t\t2: pass\r\n",
            "func f():\n\tmatch z:\n\t\t1 pass §\n\t\t2: pass\n",
            "func f():\r\n\tmatch z:\r\n\t\t1 pass §\r\n\t\t2: pass\r\n",
            "func f():\n\tmatch z:\n\t\t1 when ok \\\n\t\t§ junk\n\t\t2: pass\n",
            "func f():\r\n\tmatch z:\r\n\t\t1 when ok \\\r\n\t\t§ junk\r\n\t\t2: pass\r\n",
            "func f():\n\tmatch z:\n\t\t1 when ok \\\n\t\t§ pass\n\t\t2: pass\n",
            "func f():\r\n\tmatch z:\r\n\t\t1 when ok \\\r\n\t\t§ pass\r\n\t\t2: pass\r\n",
            "func f():\n\tmatch z:\n\t\t1 when ok \\\n\t\t§\n\t\t2: pass\n",
            "func f():\r\n\tmatch z:\r\n\t\t1 when ok \\\r\n\t\t§\r\n\t\t2: pass\r\n",
            "func f():\n\tmatch z:\n\t\t1 when ok\n\t\t2: pass\n",
            "func f():\r\n\tmatch z:\r\n\t\t1 when ok\r\n\t\t2: pass\r\n",
            "func f():\n\tmatch z:\n\t\t1 when func(): pass\n\t\t2: pass\n",
            "func f():\r\n\tmatch z:\r\n\t\t1 when func(): pass\r\n\t\t2: pass\r\n",
            "func f():\n\tmatch z:\n\t\t1 when (ok\n\t\t) \\\n\t\t§ junk\n\t\t2: pass\n",
            "func f():\r\n\tmatch z:\r\n\t\t1 when (ok\r\n\t\t) \\\r\n\t\t§ junk\r\n\t\t2: pass\r\n",
            "func f():\n\tmatch z:\n\t\t1 when (ok\n\t\t) \\\n\t\t§ pass\n\t\t2: pass\n",
            "func f():\r\n\tmatch z:\r\n\t\t1 when (ok\r\n\t\t) \\\r\n\t\t§ pass\r\n\t\t2: pass\r\n",
            "func f():\n\tmatch z:\n\t\t1 when (ok\n\t\t) \\\n\t\t§\n\t\t2: pass\n",
            "func f():\r\n\tmatch z:\r\n\t\t1 when (ok\r\n\t\t) \\\r\n\t\t§\r\n\t\t2: pass\r\n",
            "func f():\n\tmatch z:\n\t\t1 when (ok\n\t\t)\n\t\t2: pass\n",
            "func f():\r\n\tmatch z:\r\n\t\t1 when (ok\r\n\t\t)\r\n\t\t2: pass\r\n",
            "func f():\n\tmatch z:\n\t\tpass §\n\t\t2: pass\n",
            "func f():\r\n\tmatch z:\r\n\t\tpass §\r\n\t\t2: pass\r\n",
            "func f():\n\tmatch z:\n\t\tpass § junk\n\t\t2: pass\n",
            "func f():\r\n\tmatch z:\r\n\t\tpass § junk\r\n\t\t2: pass\r\n",
            "func f():\n\tmatch z:\n\t\t1: pass\n\t\tpass §\n",
            "func f():\r\n\tmatch z:\r\n\t\t1: pass\r\n\t\tpass §\r\n",
            "func f():\n\tmatch z:\n\t\t§ pass\n\t\t2: pass\n",
            "func f():\r\n\tmatch z:\r\n\t\t§ pass\r\n\t\t2: pass\r\n",
            "func f():\n\tmatch z:\n\t\tpass\n\t\t§ 2: pass\n",
            "func f():\r\n\tmatch z:\r\n\t\tpass\r\n\t\t§ 2: pass\r\n"
    );

    @Test
    void recoveryCorpusKeepsRangeEnvelopeAndDiagnosticInvariants() {
        for (var source : RECOVERY_CORPUS) {
            var result = new GdScriptParser().parse(source);
            assertEnvelope(result.ast(), () -> "envelope violation in: " + source);
            assertDiagnosticInvariants(source, result, () -> "diagnostic invariant violation in: " + source);
        }
    }

    @Test
    void legalFixturesKeepRangeEnvelopeAndDiagnosticInvariants() throws IOException {
        try (var stream = Files.list(FIXTURE_DIR)) {
            for (var script : stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".gd"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList()) {
                var source = Files.readString(script, StandardCharsets.UTF_8);
                var result = new GdScriptParser().parse(source);
                assertEnvelope(result.ast(), () -> "envelope violation in fixture: " + script.getFileName());
                assertDiagnosticInvariants(source, result,
                        () -> "diagnostic invariant violation in fixture: " + script.getFileName());
            }
        }
    }

    @Test
    void mandatoryIdentityAttributionAndStandaloneExceptions() {
        var parser = new GdScriptParser();

        // 1. Built markers bind to their direct owning statement/declaration by object identity.
        var varInit = parser.parse("var hp =\n");
        var hpDecl = assertInstanceOf(VariableDeclaration.class, varInit.ast().statements().getFirst());
        var hpDiag = requireError(varInit, DiagnosticCodes.SLOT_VARIABLE_INITIALIZER);
        assertNotNull(hpDiag.recovery());
        assertSame(hpDecl, hpDiag.recovery().owner());
        assertEquals("variable.initializer", hpDiag.recovery().facet());

        var retSlot = parser.parse("func f():\n\treturn =3\n");
        var retSlotStmt = assertInstanceOf(ReturnStatement.class,
                assertInstanceOf(FunctionDeclaration.class, retSlot.ast().statements().getFirst()).body().statements().getFirst());
        var retSlotDiag = requireError(retSlot, DiagnosticCodes.SLOT_RETURN_VALUE);
        assertNotNull(retSlotDiag.recovery());
        assertSame(retSlotStmt, retSlotDiag.recovery().owner());
        assertEquals("return.value", retSlotDiag.recovery().facet());

        var retTrailing = parser.parse("func f():\n\treturn 1 2\n");
        var retTrailStmt = assertInstanceOf(ReturnStatement.class,
                assertInstanceOf(FunctionDeclaration.class, retTrailing.ast().statements().getFirst()).body().statements().getFirst());
        var retTrailDiag = requireError(retTrailing, DiagnosticCodes.STATEMENT_TRAILING);
        assertNotNull(retTrailDiag.recovery());
        assertSame(retTrailStmt, retTrailDiag.recovery().owner());
        assertEquals("statement.trailing", retTrailDiag.recovery().facet());

        // 2. Rebuilt variadic Parameter inherits type/default diagnostics by identity.
        var variadicDefault = parser.parse("func f(...args = 1+): pass\n");
        var variadicFn = assertInstanceOf(FunctionDeclaration.class, variadicDefault.ast().statements().getFirst());
        var rebuiltParam = variadicFn.parameters().getFirst();
        var binaryRightDiag = requireError(variadicDefault, DiagnosticCodes.SLOT_BINARY_RIGHT);
        assertNotNull(binaryRightDiag.recovery());
        assertSame(rebuiltParam, binaryRightDiag.recovery().owner());
        assertEquals("parameter.default", binaryRightDiag.recovery().facet());
        var restDefaultDiag = requireError(variadicDefault, DiagnosticCodes.SIGNATURE_REST_WITH_DEFAULT);
        assertSame(variadicFn, restDefaultDiag.recovery().owner());
        assertEquals("function.signature", restDefaultDiag.recovery().facet());

        var variadicLambda = parser.parse("func f(...args = (1: pass\n");
        var variadicLambdaFn = assertInstanceOf(FunctionDeclaration.class, variadicLambda.ast().statements().getFirst());
        var rebuiltLambdaParam = variadicLambdaFn.parameters().getFirst();
        var groupCloserDiag = requireError(variadicLambda, DiagnosticCodes.MISSING_CLOSER_GROUPING);
        assertSame(rebuiltLambdaParam, groupCloserDiag.recovery().owner());
        assertEquals("parameter.default", groupCloserDiag.recovery().facet());
        var paramCloserDiag = requireError(variadicLambda, DiagnosticCodes.MISSING_CLOSER_PARAMETER_LIST);
        assertSame(variadicLambdaFn, paramCloserDiag.recovery().owner());
        assertEquals("function.signature", paramCloserDiag.recovery().facet());
        assertTrue(rebuiltLambdaParam.variadic());

        // 3. Inner and outer lambda scopes attribute to their own exact owners without stealing.
        var nestedLambdas = parser.parse("var x = f(, func(): return 1+)\n");
        var outerVar = assertInstanceOf(VariableDeclaration.class, nestedLambdas.ast().statements().getFirst());
        var outerCall = assertInstanceOf(CallExpression.class, outerVar.value());
        var innerLambda = assertInstanceOf(LambdaExpression.class, outerCall.arguments().get(1));
        var innerReturn = assertInstanceOf(ReturnStatement.class, innerLambda.body().statements().getFirst());
        var outerCallArgDiag = requireError(nestedLambdas, DiagnosticCodes.SLOT_CALL_ARGUMENT);
        var innerBinaryDiag = requireError(nestedLambdas, DiagnosticCodes.SLOT_BINARY_RIGHT);
        assertSame(outerVar, outerCallArgDiag.recovery().owner());
        assertEquals("variable.initializer", outerCallArgDiag.recovery().facet());
        assertSame(innerReturn, innerBinaryDiag.recovery().owner());
        assertEquals("return.value", innerBinaryDiag.recovery().facet());

        var lambdaBodyLayout = parser.parse("var x = func():\npass\n");
        var lambdaVar = assertInstanceOf(VariableDeclaration.class, lambdaBodyLayout.ast().statements().getFirst());
        var lambdaExpr = assertInstanceOf(LambdaExpression.class, lambdaVar.value());
        var lambdaIndentDiag = requireError(lambdaBodyLayout, DiagnosticCodes.SYNTAX_UNEXPECTED_TOKEN);
        assertNotNull(lambdaIndentDiag.recovery());
        assertSame(lambdaExpr, lambdaIndentDiag.recovery().owner());
        assertEquals("suite.body", lambdaIndentDiag.recovery().facet());

        // 4. UTF-8, CRLF, and EOF-cut inputs preserve physical byte/line/col invariants and ownership.
        var utf8Crlf = "func f():\r\n\treturn =😀\r\n";
        var utf8Result = parser.parse(utf8Crlf);
        assertDiagnosticInvariants(utf8Crlf, utf8Result, () -> "UTF-8/CRLF return recovery");
        var utf8Ret = assertInstanceOf(ReturnStatement.class,
                assertInstanceOf(FunctionDeclaration.class, utf8Result.ast().statements().getFirst()).body().statements().getFirst());
        assertSame(utf8Ret, requireError(utf8Result, DiagnosticCodes.SLOT_RETURN_VALUE).recovery().owner());

        // 5. Lookahead lexical errors on a following line never attach to a healthy previous statement,
        //    whereas lexical errors inside an active construct are claimed by that construct.
        var lookaheadLex = parser.parse("var a = 1\n0x\n");
        assertDiagnosticInvariants("var a = 1\n0x\n", lookaheadLex, () -> "lookahead lex");
        var lexStandalone = requireError(lookaheadLex, DiagnosticCodes.LEXICAL_ERROR);
        assertNull(lexStandalone.recovery());

        var lookaheadInsideList = parser.parse("var a = [1,\n0x\n]\n");
        var listVar = assertInstanceOf(VariableDeclaration.class, lookaheadInsideList.ast().statements().getFirst());
        var lexClaimed = requireError(lookaheadInsideList, DiagnosticCodes.LEXICAL_ERROR);
        assertNotNull(lexClaimed.recovery());
        assertSame(listVar, lexClaimed.recovery().owner());
        assertEquals("variable.initializer", lexClaimed.recovery().facet());

        // 6. Authorized parent-chain fallbacks: property accessor, dropped duplicate params, match/control/extends.
        var propResult = parser.parse("var x:\n\tget:\n\t\tvar y = [1 2]\n");
        var propVar = assertInstanceOf(VariableDeclaration.class, propResult.ast().statements().getFirst());
        var propComma = requireError(propResult, DiagnosticCodes.MISSING_SEPARATOR_COMMA);
        assertSame(propVar, propComma.recovery().owner());
        assertEquals("variable.property", propComma.recovery().facet());

        var dupSignal = parser.parse("signal s(a, a = 1)\n");
        var sigNode = assertInstanceOf(SignalStatement.class, dupSignal.ast().statements().getFirst());
        var sigDefault = requireError(dupSignal, DiagnosticCodes.SIGNATURE_SIGNAL_PARAMETER_DEFAULT);
        var sigDup = requireError(dupSignal, DiagnosticCodes.SIGNATURE_DUPLICATE_PARAMETER);
        assertSame(sigNode, sigDefault.recovery().owner());
        assertEquals("signal.parameter", sigDefault.recovery().facet());
        assertSame(sigNode, sigDup.recovery().owner());
        assertEquals("signal.parameter", sigDup.recovery().facet());

        var controlLoop = parser.parse("func _init():\n\tbreak\n\tcontinue\n\treturn 1\n");
        var initCtor = onlyStatement(controlLoop.ast(), 0);
        var bodyStmts = assertInstanceOf(dev.superice.gdparser.frontend.ast.ConstructorDeclaration.class, initCtor)
                .body().statements();
        var controlDiags = controlLoop.diagnostics().stream()
                .filter(d -> DiagnosticCodes.SYNTAX_UNEXPECTED_TOKEN.equals(d.code()))
                .toList();
        assertEquals(3, controlDiags.size(), () -> controlDiags.toString());
        assertSame(assertInstanceOf(BreakStatement.class, bodyStmts.get(0)), controlDiags.get(0).recovery().owner());
        assertSame(assertInstanceOf(ContinueStatement.class, bodyStmts.get(1)), controlDiags.get(1).recovery().owner());
        assertSame(assertInstanceOf(ReturnStatement.class, bodyStmts.get(2)), controlDiags.get(2).recovery().owner());

        var dupExtendsTop = parser.parse("class_name Foo extends A\nextends B\n");
        var classNameNode = assertInstanceOf(ClassNameStatement.class, dupExtendsTop.ast().statements().getFirst());
        var dupExtDiag = requireError(dupExtendsTop, DiagnosticCodes.CLASS_DUPLICATE_EXTENDS);
        assertSame(classNameNode, dupExtDiag.recovery().owner());
        assertEquals("class.extends", dupExtDiag.recovery().facet());

        var dupExtendsNested = parser.parse("class Inner extends A:\n\textends B\n\tpass\n");
        var innerDecl = assertInstanceOf(ClassDeclaration.class, dupExtendsNested.ast().statements().getFirst());
        assertSame(innerDecl, requireError(dupExtendsNested, DiagnosticCodes.CLASS_DUPLICATE_EXTENDS).recovery().owner());

        var matchLayout = parser.parse("func f():\n\tmatch x:\n\t\tpass 1\n\t\t@warning_ignore(\"unused\")\n");
        var matchStmt = assertInstanceOf(MatchStatement.class,
                assertInstanceOf(FunctionDeclaration.class, matchLayout.ast().statements().getFirst()).body().statements().getFirst());
        assertSame(matchStmt, requireError(matchLayout, DiagnosticCodes.ANNOTATION_DANGLING).recovery().owner());
        assertSame(matchStmt, requireError(matchLayout, DiagnosticCodes.SYNTAX_UNEXPECTED_TOKEN).recovery().owner());

        // 7. Explicit standalone top-level garbage keeps recovery == null with registered standalone codes.
        var orphanGarbage = parser.parse("% ^ &\nvar good = 2\n");
        assertDiagnosticInvariants("% ^ &\nvar good = 2\n", orphanGarbage, () -> "top-level orphan garbage");
        assertFalse(orphanGarbage.diagnostics().isEmpty());
        for (var diag : orphanGarbage.diagnostics()) {
            assertNull(diag.recovery(), () -> diag.toString());
            assertTrue(DiagnosticCodes.allowsNullRecovery(Objects.requireNonNull(diag.code())), () -> diag.toString());
        }
    }

    private static Node onlyStatement(SourceFile ast, int index) {
        return ast.statements().get(index);
    }

    private static AstDiagnostic requireError(AstMappingResult result, String code) {
        return result.diagnostics().stream()
                .filter(d -> d.severity() == AstDiagnosticSeverity.ERROR && code.equals(d.code()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing diagnostic " + code + " in: " + result.diagnostics()));
    }

    private static void assertDiagnosticInvariants(
            String source,
            AstMappingResult result,
            java.util.function.Supplier<String> context
    ) {
        var reachable = collectReachableNodes(result.ast());
        var textIndex = SourceTextIndex.of(source);
        for (var diagnostic : result.diagnostics()) {
            if (diagnostic.severity() != AstDiagnosticSeverity.ERROR) {
                continue;
            }
            var code = diagnostic.code();
            assertNotNull(code, () -> context.get() + "\nERROR diagnostic missing code: " + diagnostic);
            assertTrue(DiagnosticCodes.isRegistered(code),
                    () -> context.get() + "\nUnregistered diagnostic code: " + diagnostic);

            var r = diagnostic.range();
            assertTrue(r.startByte() >= 0 && r.startByte() <= r.endByte() && r.endByte() <= textIndex.utf8Length(),
                    () -> context.get() + "\nDiagnostic range out of UTF-8 bounds [0, " + textIndex.utf8Length()
                            + "]: " + diagnostic);
            assertEquals(textIndex.pointAt(r.startByte()), r.startPoint(),
                    () -> context.get() + "\nDiagnostic startPoint mismatch: " + diagnostic);
            assertEquals(textIndex.pointAt(r.endByte()), r.endPoint(),
                    () -> context.get() + "\nDiagnostic endPoint mismatch: " + diagnostic);

            var recovery = diagnostic.recovery();
            if (recovery != null) {
                assertTrue(reachable.contains(recovery.owner()),
                        () -> context.get() + "\nRecovery owner not reachable by identity in AST: " + diagnostic);
                assertTrue(DiagnosticCodes.isValidRecovery(code, recovery),
                        () -> context.get() + "\nInvalid (code, ownerType, facet) recovery pairing: " + diagnostic);
            } else {
                assertTrue(DiagnosticCodes.allowsNullRecovery(code),
                        () -> context.get() + "\nDiagnostic with null recovery is not standalone-allowed: "
                                + diagnostic);
            }
        }
    }

    private static Set<Node> collectReachableNodes(SourceFile ast) {
        var reachable = Collections.newSetFromMap(new IdentityHashMap<Node, Boolean>());
        var work = new ArrayDeque<Node>();
        work.add(ast);
        reachable.add(ast);
        while (!work.isEmpty()) {
            var current = work.removeFirst();
            for (var child : current.getChildren()) {
                if (reachable.add(child)) {
                    work.addLast(child);
                }
            }
        }
        return reachable;
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
