# GdParser Architecture Overview

This document explains how the repository is structured, how data flows through the system, and where to change code safely.

## 1. What This Repository Does

`gdparser` is a Java 25 project with two GDScript parse frontends that converge on one stable Java AST: the deprecated original Tree-sitter pipeline (CST -> lowering, retained for compatibility and parity) and the primary pure-Java port of Godot's official GDScript tokenizer/parser (no native dependency).

At a high level, the repository provides:

- Native language loading for `tree-sitter-gdscript`
- Parse facade for source text -> CST root
- CST abstraction layer independent from raw `org.treesitter` API
- CST -> AST lowering with diagnostics
- Pure-Java Godot-port tokenizer + recursive-descent/Pratt parser with panic-mode recovery (`frontend.gdscript`)
- Pure-Java completion-context classification over tolerant syntax and recovery-independent tokens
- AST <-> S-expression serialization/deserialization
- Regression tests using real fixture scripts, including dual-path parity tests

## 2. End-to-End Data Flow

Two parse pipelines converge on the shared `frontend.ast` model:

```text
GDScript Source (String)
  |-- Deprecated Tree-sitter path (reference / fallback):
  |     GdParserFacade (TSParser + TSLanguage)
  |       -> TSNode root
  |       -> CstAdapter -> CstNodeView immutable tree
  |       -> CstToAstMapper ---------------------+
  |                                             |
  |-- Pure-Java Godot-port path:                |
  |     GdScriptParserFacade                    |
  |       -> GdTokenizer (token stream + comment trivia)
  |       -> GdScriptParser (recursive descent + Pratt, panic-mode recovery)
  |       --------------------------------------+
                                                v
                          SourceFile AST + AstDiagnostic list (AstMappingResult)
                          -> (optional) AstSexprSerializer / AstSexprDeserializer
```

Key mode differences:

- Tree-sitter path: `CstToAstMapper.map(...)` is tolerant (AST + diagnostics); `mapStrict(...)` throws on any `ERROR` diagnostic.
- Godot-port path: `GdScriptParserFacade.parse(...)` is tolerant; `parseStrict(...)` mirrors the strict contract (throws on the first `ERROR` diagnostic). `tokenize(...)` exposes the raw token stream for tests/tooling — without parser-driven multiline suppression, so its ERROR tokens are not a source-validity criterion.

Completion support flow:

- `GdScriptParserFacade.parseCompletionContext(source, byteOffset)` returns a pure-Java `frontend.gdscript.CompletionContext`, without loading a native runtime. Its eight kinds are `MEMBER_ACCESS`, `IDENTIFIER`, `TYPE_POSITION`, `CALL_ARGUMENT`, `PARAMETER_NAME`, `SUITE_START`, `EXPECTED_KEYWORD`, and `UNKNOWN`.
- Tolerant AST structure supplies surviving nodes; token-level fallback also covers expressions discarded during recovery, including incomplete `super`/member chains, type slots and EOF-unclosed calls. Get-node paths include quoted segments and slash-delimited unique names; prefix `%` is distinguished from modulo.
- The token view tracks inherited type-container layout and disabled function/lambda frames. Pending headers, bodyless signatures and inline/indented bodies have separate exit rules. Retained hard newlines, not physical rows, govern layout; explicit continuation does not overwrite the outer indentation baseline, which is restored when function frames unwind.
- The completion token view and parser use the same lazy bracket scanner. A closer pairs only with the nearest opener of the same kind. A truncated or replaced opener has an effective recovery end, not a valid partner. Unclosed delimiters cannot carry multiline layout or call-argument slots across a hard structural stop.
- Finite type states distinguish completed casts from following expressions and support inner types after string-based `extends`. An unfinished collection annotation can offer type completion on a nearby, more deeply indented type-only line without changing strict parsing. Match-pattern commas retain their lambda body.
- Empty slots are owned by their syntax: dictionary and inferred-initializer values return `IDENTIFIER`; signature parameter names return `PARAMETER_NAME`; a function's same-line body starts at `SUITE_START`; infix `not` expects `in` and returns `EXPECTED_KEYWORD`. These slots have zero-width replacement ranges at the UTF-8 cursor and no receiver. Otherwise the replacement covers the whole name when present; only `MEMBER_ACCESS` carries a receiver.
- Offsets clamp to the physical UTF-8 source bounds; points use zero-based rows and byte columns. At a name/dot shared boundary the left name wins. String/comment interiors return `UNKNOWN`, but a cursor before their first byte remains in the code context, including an empty call slot before an opening quote. Type slots never replace a following non-type keyword.
- The deprecated `infra.treesitter` entry point and five-kind DTO remain unchanged. GDCC has not migrated to the pure-Java entry point. During migration, an adapter preserving the old five-value contract should map the three new kinds to `UNKNOWN` with no candidates and retain the original five mappings. Candidate generation, semantic resolution, and signature help remain downstream.

## 3. Package Map

### `infra.treesitter`

Purpose: Native loading + parser bootstrap.

Main classes:

- `GdLanguageLoader`: resolves/loads `tree_sitter_gdscript` symbol with fallback strategy
- `GdLanguageAbiChecker`: verifies language ABI compatibility
- `GdParserFacade`: deprecated parse entrypoints (`parseSnapshot`, `parseCstRoot`, `parseCompletionContext`)
- `GdParseSnapshot`: deprecated small parse result record for smoke checks
- `CompletionContext` / `CompletionContextAnalyzer`: deprecated CST completion classification, retained for compatibility

Concurrency contract:

- `GdParserFacade` holds only the shared immutable `TSLanguage`; every parse call creates a fresh `TSParser`, so concurrent parse calls on a shared instance are safe. `CstToAstMapper` is likewise stateless. Callers may share one facade and one mapper across analyses without locking or pooling.

### `frontend.cst`

Purpose: Stable CST view for the rest of the codebase.

Main classes:

- `CstNodeView`: immutable CST node API (`type`, `children`, `range`, `field` helpers)
- `CstAdapter`: converts `TSNode` to `CstNodeView`
- `CstErrorDetector`: collects `ERROR` / `MISSING` structural issues; grammar-specific zero-width placeholder nodes (e.g. the absent member name in `receiver. + 1`) are reported as `MISSING` issues whose node type names the expected symbol

Design rule:

- Code outside this package should not depend on raw `org.treesitter` node traversal.

### `frontend.ast`

Purpose: Domain AST model for GDScript semantics.

Characteristics:

- Top-level records/sealed interfaces (no monolithic nested AST class)
- Explicit source spans (`Range`, `Point`) on nodes
- `Unknown*` nodes to preserve unsupported syntax safely
- `ErrorStatement` / `ErrorExpression` nodes preserve CST `ERROR`/`MISSING` regions (kind, original node type, raw fragment, span) instead of guessing inaccurate nodes
- `MissingAttributeStep` explicitly marks the absent final member step of an attribute chain such as `receiver.`
- Record constructors defensively freeze collection components (`List.copyOf`), so a published AST object graph is immutable

Main groups:

- Statements: `FunctionDeclaration`, `IfStatement`, `MatchStatement`, etc.
- Expressions: `CallExpression`, `BinaryExpression`, `DictionaryExpression`, etc.
- Utility/diagnostic: `AstDiagnostic`, `AstMappingResult`, `AstFactory`

### `frontend.lowering`

Purpose: CST -> AST mapping and diagnostics.

Main class:

- `CstToAstMapper`

Responsibilities:

- Map supported CST node types into typed AST records
- Emit warnings for unsupported/unknown forms
- Convert CST structural errors into `ERROR` diagnostics
- Reject legacy GDScript 3.x syntax during lowering so the typed AST stays 4.x-only

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

- `GdScriptParser`/`GdTokenizer` instances are stateful and not thread-safe; the facade holds no state and creates fresh instances per call, so a shared facade is safe for concurrent use without locking or pooling (same contract shape as `GdParserFacade`).

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

## 4. Native Library Loading Strategy

`GdLanguageLoader` attempts lookups in this order:

1. Managed resource directory (`gdparser.gdscript.resourceDir`, default `native` beside the loader code source / jar)
2. `java.library.path`
3. Explicit file path (`gdparser.gdscript.nativeLibPath`)
4. Explicit directory (`gdparser.gdscript.nativeLibDir`)
5. Classpath extraction fallback to a temporary directory

Why this matters:

- Local dev, CI, and packaged runtime can use different deployment layouts
- Errors must stay diagnosable (attempted locations and reasons)

## 5. Error Model and Recovery Philosophy

Both pipelines report through the shared `AstDiagnostic(severity, message, nodeType, range)` model; ranges are always UTF-8 byte offsets with byte-based points.

The tree-sitter stack separates three error classes:

1. CST structural issues (`ERROR`, `MISSING`) from Tree-sitter
2. Lowering-time unsupported constructs
3. Hard incompatibilities (native load/ABI mismatch)

Behavior policy:

- Preserve progress when possible (`Unknown*` + warning)
- Map CST `ERROR` nodes to `ErrorStatement` / `ErrorExpression` with kind, span, and raw fragment; sibling mapping continues
- Map a partial attribute chain (`receiver.`) to a normal `AttributeExpression` plus a `MissingAttributeStep` marker so the receiver prefix stays analyzable
- Recover end-of-file dangling-dot `ERROR` nodes (e.g. `func f():\n\tnode.`, where the grammar wraps the whole function into one error) via bounded, fail-closed tail decomposition: an anchored function header, mappable body fragments, and an optional partial-statement prefix are rebuilt around the recovered chain; unrecognized shapes stay a single `ErrorStatement`
- Emit `MISSING` diagnostics that name the expected token/symbol (e.g. `Missing identifier`) and keep carrying the error span
- Fail fast for runtime incompatibility or strict-mode mapping
- Keep spans available so diagnostics are actionable
- Treat legacy GDScript 3.x syntax accepted by the upstream grammar as lowering-time `ERROR`s

The Godot-port stack reports tokenizer errors (from `ERROR` tokens) and parser errors (ported `push_error` messages) into the same diagnostic list, and recovers with Godot's panic-mode + `synchronize()` policy: advance to a statement/member boundary, then resume sibling parsing. Contract highlights:

- Illegal sources always terminate with diagnostics — never exceptions; a nesting-depth budget (256) guards the JVM stack against pathological nesting (Godot has no such limit; deliberate deviation)
- Expression and type parsing use five sealed `ParseOutcome` states: `Ok` carries a healthy value, `EnclosedDamage` carries a complete structure with damaged children, and `Damaged` carries a partial value. `Missing` consumes nothing and leaves the context diagnostic to the caller; `Failed` already reported its cause and has no value. Expression slots keep a zero-width `MISSING` marker or an `ERROR` fragment when there is no node to retain. A closed array, dictionary, call, or subscript can preserve child damage and still participate in expression folding and shape checks. A grouping is transparent: with a real `)` it preserves the inner outcome.
- A missing member name after `.` keeps a `MissingAttributeStep` in the surviving chain. Damaged control-flow headers and other expression slots survive with markers in place. Missing closers produce zero-width diagnostics with a stable `code` and a same-generation `RecoveryLink` to the owning construct. Type parsing preserves usable damaged `TypeRef` text and its byte span without changing the public AST or serde. Constructs without a node able to carry the failure (e.g. a missing function name) are still omitted; the parser does not synthesize `ErrorStatement`.
- Known recovery limitation: a depth-budget trip on a multiline grouping continuation line can mis-attribute sibling scopes (registered in `doc/godot-java-parser-implementation.md` §9; diagnostics and termination still hold)

### Parity rule index

The canonical contract for dual-path equivalence lives in `doc/godot-java-parser-implementation.md`:

- §5.1-§5.6: AST shape adaptation, literal kind vocabulary, comment/trivia attribution, sign-absorption deviation, diagnostic message alignment
- §5.7: divergence registration policy — legal sources must serialize identically across both paths; registered divergences and illegal sources follow their own assertion clauses
- §7: layered test strategy (`GdTokenizerTest` -> `GdScriptParserTest` -> `GdScriptParserErrorRecoveryTest` -> `GdParserParityTest` -> `GdScriptParserFacadeTest`)

## 6. Test Architecture

Test suite is layered by concern:

- `GdParserFacadeTest`: loader/bootstrap + parse smoke tests + shared-facade concurrency check
- `CompletionContextTest`: deprecated CST completion classification, retained as the migration reference
- `GdCompletionContextTest`: numbered mapping of the 21 legacy cases, recovery/type/call fallbacks, UTF-8/CRLF points, trivia, truncations and shared-facade concurrency
- `CstAdapterTest`: CST API behavior and invariants
- `CstFixtureScriptsTest`: fixture-wide CST structure baseline
- `CstToAstMapperTest`: lowering correctness and diagnostics
- `CstToAstMapperErrorRecoveryTest`: error-node mapping, partial chains, sibling continuation
- `AstCollectionFreezeTest`: AST collection immutability contract
- `AstSexprSerdeTest`: AST serde unit + fixture round-trip
- `SourceTextIndexTest`: byte-offset/point conversion edge cases (CRLF, CJK, emoji, tabs, EOF)
- `GdTokenizerTest`: Godot-port token stream snapshots (indent/newline model, literals, errors)
- `GdScriptParserTest`: legal-syntax AST shapes, ranges, comment attribution
- `GdScriptParserErrorRecoveryTest`: panic-mode recovery corpus with termination timeouts
- `GdScriptParserRangeInvariantTest`: AST range-envelope invariant over the recovery corpus and all fixtures
- `GdParserParityTest` / `GdParityReferenceTest` / `GdFixtureParityAuditTest`: dual-path parity over inline corpus and all fixtures
- `GdScriptParserFacadeTest`: Godot-port facade entry semantics + shared-facade concurrency check

Fixture corpus:

- `src/test/resources/gdscript`

Recommended targeted runs during iteration:

```bash
./gradlew test --tests dev.superice.gdparser.frontend.lowering.CstToAstMapperTest --no-daemon --info --console=plain
./gradlew test --tests dev.superice.gdparser.frontend.serialize.AstSexprSerdeTest --no-daemon --info --console=plain
./gradlew test --tests dev.superice.gdparser.frontend.gdscript.GdParserParityTest --no-daemon --info --console=plain
```

## 7. Common Maintenance Tasks

### Add support for a new syntax node

1. Inspect CST shape in `CstNodeView` (type/fields/range)
2. Add/adjust AST record(s) if needed
3. Implement mapping in `CstToAstMapper`
4. Decide fallback policy (`Unknown*` vs strict error)
5. Add focused mapper tests and fixture regression
6. If serialization is affected, update serde tests

### Upgrade grammar / parser artifacts

1. Update grammar/native artifact source
2. Verify ABI compatibility and loader behavior
3. Re-run fixture CST + lowering + serde tests
4. Review semantic diffs before accepting changes

### Debug a parse/lowering failure

1. Start from `GdParserFacade.parseSnapshot(...)` (root + s-expression)
2. Check `CstErrorDetector.collect(...)`
3. Inspect mapper diagnostics (`AstMappingResult.diagnostics()`)
4. If needed, serialize AST to S-expression for deterministic diff

## 8. Current Known Gaps (Engineering Backlog)

- Linux/macOS end-to-end validation in CI is still pending
- AST golden snapshot (`input.gd -> ast.json`) baseline is not yet established
- S-expression protocol version header is not yet explicit
- Performance regression thresholds are not formalized (a Godot-port fixture timing baseline is recorded in `doc/godot-java-parser-implementation.md` §1.2)

## 9. New Maintainer Quick Start

1. Build classes:

```bash
./gradlew classes --no-daemon --info --console=plain
```

2. Run core targeted tests:

```bash
./gradlew test --tests dev.superice.gdparser.infra.treesitter.GdParserFacadeTest --no-daemon --info --console=plain
./gradlew test --tests dev.superice.gdparser.frontend.cst.CstFixtureScriptsTest --no-daemon --info --console=plain
./gradlew test --tests dev.superice.gdparser.frontend.lowering.CstToAstMapperTest --no-daemon --info --console=plain
./gradlew test --tests dev.superice.gdparser.frontend.serialize.AstSexprSerdeTest --no-daemon --info --console=plain
```

3. Before PR, run full verification:

```bash
./gradlew clean build --no-daemon --info --console=plain
```

## 10. Design Principles to Keep

- Keep Tree-sitter-specific details inside `infra.treesitter` and `frontend.cst`
- Keep AST model explicit and immutable (constructors freeze collection components)
- Prefer deterministic output and canonical formats for diffability
- Preserve diagnosability over silent fallback
- Add tests at the same layer where behavior changes
