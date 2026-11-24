# GdParser Architecture Overview

This document explains how the repository is structured, how data flows through the system, and where to change code safely.

## 1. What This Repository Does

`gdparser` is a Java 25 library for parsing Godot 4 GDScript. Since 0.8.0, it has one frontend: a pure-Java port of Godot's official tokenizer and parser that produces a typed AST directly.

At a high level, the repository provides:

- Pure-Java Godot-port tokenizer + recursive-descent/Pratt parser with panic-mode recovery (`frontend.gdscript`)
- Pure-Java completion-context classification over tolerant syntax and recovery-independent tokens
- AST <-> S-expression serialization/deserialization
- Regression tests using real fixture scripts

## 2. End-to-End Data Flow

```text
GDScript Source (String)
  -> GdScriptParserFacade
  -> GdTokenizer (token stream + comment trivia)
  -> GdScriptParser (recursive descent + Pratt, panic-mode recovery)
  -> SourceFile AST + AstDiagnostic list (AstMappingResult)
  -> (optional) AstSexprSerializer / AstSexprDeserializer for the AST
```

Public entry points:

- `GdScriptParserFacade.parse(...)` returns a possibly partial AST and diagnostics on malformed input. `parseStrict(...)` returns the AST or throws on the first `ERROR` diagnostic; warnings alone do not fail.
- `tokenize(...)` exposes the raw token stream, without parser-driven multiline suppression. Its `ERROR` tokens alone do not establish whether a source file is valid.

Completion support flow:

- `GdScriptParserFacade.parseCompletionContext(source, byteOffset)` returns a pure-Java `frontend.gdscript.CompletionContext`, without loading a native runtime. Its eight kinds are `MEMBER_ACCESS`, `IDENTIFIER`, `TYPE_POSITION`, `CALL_ARGUMENT`, `PARAMETER_NAME`, `SUITE_START`, `EXPECTED_KEYWORD`, and `UNKNOWN`.
- Tolerant AST structure supplies surviving nodes; token-level fallback also covers expressions discarded during recovery, including incomplete `super`/member chains, type slots and EOF-unclosed calls. Get-node paths include quoted segments and slash-delimited unique names; prefix `%` is distinguished from modulo.
- The token view tracks inherited type-container layout and disabled function/lambda frames. Pending headers, bodyless signatures and inline/indented bodies have separate exit rules. Retained hard newlines, not physical rows, govern layout; explicit continuation does not overwrite the outer indentation baseline, which is restored when function frames unwind.
- The completion token view and parser use the same lazy bracket scanner. A closer pairs only with the nearest opener of the same kind. A truncated or replaced opener has an effective recovery end, not a valid partner. Unclosed delimiters cannot carry multiline layout or call-argument slots across a hard structural stop.
- Finite type states distinguish completed casts from following expressions and support inner types after string-based `extends`. An unfinished collection annotation can offer type completion on a nearby, more deeply indented type-only line without changing strict parsing. Match-pattern commas retain their lambda body.
- Empty slots are owned by their syntax: dictionary and inferred-initializer values return `IDENTIFIER`; signature parameter names return `PARAMETER_NAME`; a function's same-line body starts at `SUITE_START`; infix `not` expects `in` and returns `EXPECTED_KEYWORD`. These slots have zero-width replacement ranges at the UTF-8 cursor and no receiver. Otherwise the replacement covers the whole name when present; only `MEMBER_ACCESS` carries a receiver.
- Offsets clamp to the physical UTF-8 source bounds; points use zero-based rows and byte columns. At a name/dot shared boundary the left name wins. String/comment interiors return `UNKNOWN`, but a cursor before their first byte remains in the code context, including an empty call slot before an opening quote. Type slots never replace a following non-type keyword.
- Candidate generation, semantic resolution, and signature help remain downstream.

## 3. Package Map

### `frontend.ast`

Purpose: Domain AST model for GDScript semantics.

Characteristics:

- Top-level records/sealed interfaces (no monolithic nested AST class)
- Explicit source spans (`Range`, `Point`) on nodes
- `Unknown*` and `ErrorStatement` remain in the AST and S-expression model for compatibility; the parser uses `ErrorExpression` to keep a damaged or missing expression slot explicit
- `MissingAttributeStep` explicitly marks the absent final member step of an attribute chain such as `receiver.`
- Record constructors defensively freeze collection components (`List.copyOf`), so a published AST object graph is immutable

Main groups:

- Statements: `FunctionDeclaration`, `IfStatement`, `MatchStatement`, etc.
- Expressions: `CallExpression`, `BinaryExpression`, `DictionaryExpression`, etc.
- Diagnostic/result: `AstDiagnostic`, `RecoveryLink`, `AstMappingResult`

`frontend.cst.CstIssueKind` remains an exported enum used by AST records. Its package name does not imply a CST parser or native runtime.

### `frontend.gdscript`

Purpose: Pure-Java port of Godot's official GDScript tokenizer and parser (baseline pinned to `godotengine/godot` commit `c016f22`, vendor copies under `vendor/godot/`), producing `frontend.ast` records directly — no CST stage, no native dependency.

Main classes:

- `GdTokenizer`: incremental `scan()` port of `GDScriptTokenizerText` (indent stack, pending-newline model, comment trivia collection)
- `GdToken` / `GdTokenType` / `GdLiteralKind`: token model aligned with Godot's `Token::Type`
- `SourceTextIndex`: UTF-8 byte offset <-> row/column conversion; physical byte cursor is the single source of truth for `Range`
- `GdScriptParser`: recursive descent + Pratt expressions, panic-mode recovery with `synchronize()`, depth-budget guard against JVM stack overflow on pathological nesting
- `GdScriptParserFacade`: public entry points (`parse`, `parseStrict`, `tokenize`, `parseCompletionContext`)
- `CompletionContext` / `CompletionContextAnalyzer`: completion DTO and AST/token classification
- `CompletionTokenStream`: ERROR-skipping token view with bracket roles, effective multiline/body frames and type-position tracking; retains incomplete-string spans separately from syntax tokens

Concurrency contract:

- `GdScriptParser`/`GdTokenizer` instances are stateful and not thread-safe; the facade holds no state and creates fresh instances per call, so a shared facade is safe for concurrent use without locking or pooling.

### `frontend.serialize`

Purpose: stable textual format for AST persistence/debugging.

Main classes:

- `AstSexprSerializer`: AST -> canonical S-expression text
- `AstSexprDeserializer`: S-expression -> AST (strict validation)
- `AstSexprSchema`: record/tag metadata mapping

Current S-expression conventions:

- Record: `(<tag> (<field> <value>) ...)`
- List: `(list ...)`
- Null: `nil`
- Escapes: `\\n`, `\\r`, `\\t`, `\\"`, `\\\\`

## 4. Migrating to 0.8.0

The Tree-sitter dependency, native loader, raw CST API, lowering mapper, and old completion types have been removed. Consumers must switch imports and calls; there is no fallback to the old frontend.

| Before 0.8.0 | In 0.8.0 |
| --- | --- |
| `GdParserFacade.parseCstRoot(source)` then `CstToAstMapper.map(...)` | `new GdScriptParserFacade().parse(source)` returns an `AstMappingResult`. Read `ast()` and `diagnostics()` from the same result. |
| `CstToAstMapper.mapStrict(...)` | `GdScriptParserFacade.parseStrict(source)` returns `SourceFile` or throws on the first `ERROR`. |
| `GdParserFacade.parseSnapshot(...)` or direct CST traversal | No raw CST replacement. Use the typed AST and `Node.getChildren()` to inspect syntax, or `AstSexprSerializer` to serialize the AST, not the CST. |
| `infra.treesitter.CompletionContext` | Call `GdScriptParserFacade.parseCompletionContext(source, byteOffset)` and import `frontend.gdscript.CompletionContext`. Keep the UTF-8 byte cursor and handle the eight kinds listed in §2. |

If a downstream protocol still exposes only the previous five completion kinds, keep the five matching names and map `PARAMETER_NAME`, `SUITE_START`, and `EXPECTED_KEYWORD` to `UNKNOWN` without candidates in the downstream adapter. Matching names are not a claim of identical behavior on malformed source.

Remove the old loader/ABI checks and the `gdparser.gdscript.resourceDir`, `gdparser.gdscript.nativeLibPath`, and `gdparser.gdscript.nativeLibDir` settings. Remove `java.library.path` and `--enable-native-access` options only if they were added for this backend. There is no replacement loader configuration or standalone parser CLI. The removed backend's [implementation plan](tree-sitter-ng-gdscript-implementation-plan.md) is historical.

## 5. Error Model and Recovery Philosophy

The parser reports tokenizer and syntax errors in one `AstDiagnostic` list. A diagnostic has severity, message, node type, range, and optional `code` and `RecoveryLink`. Ranges use half-open UTF-8 byte spans and zero-based rows and byte columns. The panic-mode `synchronize()` policy advances to a statement or member boundary so parsing can continue with the siblings.

- Tolerant parsing reports malformed syntax through diagnostics instead of throwing. A nesting-depth budget (256) guards the JVM stack against pathological nesting.
- Expression and type parsing use five sealed `ParseOutcome` states: `Ok` carries a healthy value, `EnclosedDamage` carries a complete structure with damaged children, and `Damaged` carries a partial value. `Missing` consumes nothing and leaves the context diagnostic to the caller; `Failed` already reported its cause and has no value. Expression slots keep a zero-width `MISSING` marker or an `ERROR` fragment when there is no node to retain. A closed array, dictionary, call, or subscript can preserve child damage and still participate in expression folding and shape checks. A grouping is transparent: with a real `)` it preserves the inner outcome.
- A missing member name after `.` keeps a `MissingAttributeStep` in the surviving chain. Damaged control-flow headers and other expression slots survive with markers in place. Missing closers produce zero-width diagnostics with a stable `code` and a same-generation `RecoveryLink` to the owning construct. Type parsing preserves usable damaged `TypeRef` text and its byte span without changing the public AST or serde. Constructs without a node able to carry the failure (e.g. a missing function name) are still omitted; the parser does not synthesize `ErrorStatement`.
- Known recovery limitation: a depth-budget trip on a multiline grouping continuation line can mis-attribute sibling scopes (registered in `doc/godot-java-parser-implementation.md` §9; diagnostics and termination still hold)

The [pure-Java implementation guide](godot-java-parser-implementation.md) records the AST shape and coordinates (§5), the regression strategy (§7), and the recovery contract (§9).

## 6. Test Architecture

Test suite is layered by concern:

- `GdCompletionContextTest`: syntax categories, recovery/type/call fallbacks, UTF-8/CRLF points, trivia, truncations and shared-facade concurrency
- `AstCollectionFreezeTest`: AST collection immutability contract
- `NodeChildrenTest`: child ordering and traversal contracts
- `AstSexprSerdeTest`: AST serde unit + fixture round-trip
- `SourceTextIndexTest`: byte-offset/point conversion edge cases (CRLF, CJK, emoji, tabs, EOF)
- `GdTokenizerTest`: Godot-port token stream snapshots (indent/newline model, literals, errors)
- `GdScriptParserTest`: legal-syntax AST shapes, ranges, comment attribution
- `GdScriptAstContractTest`: stored S-expression snapshots for AST shape and physical ranges
- `GdScriptParserCorpusTest`: real scripts with LF and CRLF and representative inline syntax
- `GdScriptParserLineContinuationTest`: explicit and implicit continuation
- `GdScriptParserErrorRecoveryTest`: panic-mode recovery corpus with termination timeouts
- `GdScriptParserRangeInvariantTest`: AST range-envelope invariant over the recovery corpus and all fixtures
- `GdScriptParserFacadeTest`: Godot-port facade entry semantics + shared-facade concurrency check

Fixture corpus:

- `src/test/resources/gdscript`

Recommended targeted run during iteration on Linux:

```bash
script/run-gradle-targeted-tests.sh --gradle-task :test --tests GdScriptAstContractTest,GdScriptParserCorpusTest,GdScriptParserLineContinuationTest,AstSexprSerdeTest
```

## 7. Common Maintenance Tasks

### Add support for a new syntax node

1. Check the vendored Godot tokenizer and parser for the syntax and its error behavior.
2. Update `GdTokenizer` or `GdScriptParser` and adjust AST records only if needed.
3. Test valid syntax, recovery, and source ranges in the relevant layer.
4. If the AST schema changes, check S-expression compatibility and update serde tests.

### Update the Godot reference

Update the reference commit and the matching `vendor/godot/` files together. Review differences in tokenizer and parser behavior and verify the affected regression cases before updating the port.

### Debug a parse failure

1. Start with `GdScriptParserFacade.parse(source)` and inspect its diagnostic codes, ranges and partial AST.
2. If needed, compare tokens with `tokenize(source)` and account for parser-driven layout differences.
3. Serialize the AST to an S-expression when a deterministic text diff helps.

## 8. Current Known Gaps (Engineering Backlog)

- S-expression protocol version header is not yet explicit
- Performance regression thresholds are not formalized; the implementation guide §1.2 records a historical fixture timing for reference

## 9. New Maintainer Quick Start

1. Build classes:

```bash
./gradlew :classes --no-daemon --info --console=plain
```

2. Run core targeted tests:

```bash
script/run-gradle-targeted-tests.sh --gradle-task :test --tests GdScriptParserFacadeTest,GdScriptParserRangeInvariantTest,GdCompletionContextTest
```

3. Before PR, run full verification:

```bash
./gradlew :clean :build --no-daemon --info --console=plain
```

## 10. Design Principles to Keep

- Keep parsing and recovery logic in `frontend.gdscript`, separate from the AST model and its serialization
- Keep AST model explicit and immutable (constructors freeze collection components)
- Prefer deterministic output and canonical formats for diffability
- Preserve diagnosability over silent fallback
- Add tests at the same layer where behavior changes
