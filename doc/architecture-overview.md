# GdParser Architecture Overview

This document explains how the repository is structured, how data flows through the system, and where to change code safely.

## 1. What This Repository Does

`gdparser` is a Java 25 project that parses GDScript using Tree-sitter, then maps CST (concrete syntax tree) into a stable Java AST.

At a high level, the repository provides:

- Native language loading for `tree-sitter-gdscript`
- Parse facade for source text -> CST root
- CST abstraction layer independent from raw `org.treesitter` API
- CST -> AST lowering with diagnostics
- AST <-> S-expression serialization/deserialization
- Regression tests using real fixture scripts

## 2. End-to-End Data Flow

```text
GDScript Source (String)
  -> GdParserFacade (TSParser + TSLanguage)
  -> TSNode root
  -> CstAdapter
  -> CstNodeView immutable tree
  -> CstToAstMapper
  -> SourceFile AST + AstDiagnostic list
  -> (optional) AstSexprSerializer / AstSexprDeserializer
```

Key mode difference in lowering:

- `map(...)`: tolerant, returns AST + diagnostics
- `mapStrict(...)`: throws on any `ERROR` diagnostic

## 3. Package Map

### `infra.treesitter`

Purpose: Native loading + parser bootstrap.

Main classes:

- `GdLanguageLoader`: resolves/loads `tree_sitter_gdscript` symbol with fallback strategy
- `GdLanguageAbiChecker`: verifies language ABI compatibility
- `GdParserFacade`: minimal parse entrypoints (`parseSnapshot`, `parseCstRoot`)
- `GdParseSnapshot`: small parse result record for smoke checks

### `frontend.cst`

Purpose: Stable CST view for the rest of the codebase.

Main classes:

- `CstNodeView`: immutable CST node API (`type`, `children`, `range`, `field` helpers)
- `CstAdapter`: converts `TSNode` to `CstNodeView`
- `CstErrorDetector`: collects `ERROR` / `MISSING` structural issues

Design rule:

- Code outside this package should not depend on raw `org.treesitter` node traversal.

### `frontend.ast`

Purpose: Domain AST model for GDScript semantics.

Characteristics:

- Top-level records/sealed interfaces (no monolithic nested AST class)
- Explicit source spans (`Range`, `Point`) on nodes
- `Unknown*` nodes to preserve unsupported syntax safely

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

1. Managed resource directory (`gdparser.gdscript.resourceDir`, default `./native`)
2. `java.library.path`
3. Explicit file path (`gdparser.gdscript.nativeLibPath`)
4. Explicit directory (`gdparser.gdscript.nativeLibDir`)
5. Classpath extraction fallback

Why this matters:

- Local dev, CI, and packaged runtime can use different deployment layouts
- Errors must stay diagnosable (attempted locations and reasons)

## 5. Error Model and Recovery Philosophy

The parser/lowering stack separates three error classes:

1. CST structural issues (`ERROR`, `MISSING`) from Tree-sitter
2. Lowering-time unsupported constructs
3. Hard incompatibilities (native load/ABI mismatch)

Behavior policy:

- Preserve progress when possible (`Unknown*` + warning)
- Fail fast for runtime incompatibility or strict-mode mapping
- Keep spans available so diagnostics are actionable

## 6. Test Architecture

Test suite is layered by concern:

- `GdParserFacadeTest`: loader/bootstrap + parse smoke tests
- `CstAdapterTest`: CST API behavior and invariants
- `CstFixtureScriptsTest`: fixture-wide CST structure baseline
- `CstToAstMapperTest`: lowering correctness and diagnostics
- `AstSexprSerdeTest`: AST serde unit + fixture round-trip

Fixture corpus:

- `src/test/resources/gdscript`

Recommended targeted runs during iteration:

```bash
./gradlew test --tests dev.superice.gdparser.frontend.lowering.CstToAstMapperTest --no-daemon --info --console=plain
./gradlew test --tests dev.superice.gdparser.frontend.serialize.AstSexprSerdeTest --no-daemon --info --console=plain
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
- Performance baseline and regression thresholds are not yet formalized

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
- Keep AST model explicit and immutable
- Prefer deterministic output and canonical formats for diffability
- Preserve diagnosability over silent fallback
- Add tests at the same layer where behavior changes
