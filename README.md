# gdparser

A Java 25 GDScript parser. The primary path is a pure-Java port of Godot's official tokenizer and parser (no native dependencies), producing a parser-agnostic typed AST with diagnostics. The Tree-sitter (`tree-sitter-ng`) path is retained unchanged as the parity reference/fallback and for completion support.

## What This Repo Provides

- Pure-Java GDScript tokenizer + recursive-descent/Pratt parser faithful to Godot: explicit `NEWLINE`/`INDENT`/`DEDENT` semantics, panic-mode recovery with synchronization
- Tolerant and strict parse modes with stable diagnostics on UTF-8 byte-offset spans
- Raw token stream access for tooling and tests
- Immutable, parser-agnostic typed AST with canonical S-expression serialization/deserialization
- Dual-path parity testing: both parsers must produce byte-identical ASTs on the shared corpus
- Fixture-based regression tests on real-world GDScript samples

## At a Glance

```text
Source -> GdTokenizer -> GdScriptParser -> AST -> (optional) S-expr
```

## Minimal Usage

```java
var facade = new GdScriptParserFacade(); // stateless, safe to share across threads

var result = facade.parse(source);    // tolerant: AST + diagnostics, never throws
var ast = facade.parseStrict(source); // strict: throws on the first ERROR diagnostic

var tokens = facade.tokenize(source); // raw token stream (no parser multiline intervention)

var serializer = new AstSexprSerializer();
var sexpr = serializer.serialize(result.ast());
```

## Documentation

- `doc/godot-java-parser-implementation.md` — pure-Java parser contracts, parity rules, known limitations
- `doc/architecture-overview.md` — repository-wide architecture and maintenance guide
- `doc/tree-sitter-ng-gdscript-implementation-plan.md` — Tree-sitter path baseline

## License

This project is licensed under the **GNU Lesser General Public License v3.0 (LGPL-3.0)**.
See `LICENSE` for the full license text.
