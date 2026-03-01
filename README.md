# gdparser

A Java 25 GDScript parser pipeline built on Tree-sitter (`tree-sitter-ng`), with CST abstraction, typed AST lowering, diagnostics, and AST S-expression serde.

## What This Repo Provides

- Native loading of `tree-sitter-gdscript` with ABI compatibility checks
- Parse facade from source text to CST root
- Immutable CST view decoupled from raw Tree-sitter traversal
- CST -> typed Java AST mapping with tolerant/strict modes
- Stable diagnostics with source spans
- AST <-> canonical S-expression serialization/deserialization
- Fixture-based regression tests on real-world GDScript samples

## At a Glance

```text
Source -> Parser -> CST -> AST -> (optional) S-expr
```
## Minimal Usage

```java
var facade = GdParserFacade.withDefaultLanguage();
var cstRoot = facade.parseCstRoot(source);

var mapper = new CstToAstMapper();
var result = mapper.map(source, cstRoot); // tolerant mode

var serializer = new AstSexprSerializer();
var sexpr = serializer.serialize(result.ast());
```

## License

This project is licensed under the **GNU Lesser General Public License v3.0 (LGPL-3.0)**.
See `LICENSE` for the full license text.
