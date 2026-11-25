# gdparser

A Java 25 library for parsing Godot 4 GDScript. Its pure-Java port of Godot's tokenizer and parser produces a typed AST with diagnostics and needs no native libraries.

## What This Repo Provides

- Pure-Java GDScript tokenizer + recursive-descent/Pratt parser faithful to Godot: explicit `NEWLINE`/`INDENT`/`DEDENT` semantics, panic-mode recovery with synchronization
- Tolerant and strict parse modes with stable diagnostics on UTF-8 byte-offset spans
- Raw token stream access for tooling and tests
- Syntax-aware completion context (eight kinds, including empty syntactic slots)
- Immutable, parser-agnostic typed AST with canonical S-expression serialization/deserialization
- Fixture-based regression tests on real-world GDScript samples

## At a Glance

```text
Source -> GdTokenizer -> GdScriptParser -> AST -> (optional) S-expr
```

## Minimal Usage

The default Maven publication coordinates for 0.8.0 are `dev.superice:gdparser:0.8.0`. In a Gradle Kotlin DSL build:

```kotlin
dependencies {
    implementation("dev.superice:gdparser:0.8.0")
}
```

For example, in a Java 25 application:

```java
import dev.superice.gdparser.frontend.gdscript.GdScriptParserFacade;
import dev.superice.gdparser.frontend.serialize.AstSexprSerializer;

class ParseExample {
    static void main() {
        var facade = new GdScriptParserFacade();
        var source = "func f():\n\tpass\n";
        var result = facade.parse(source); // Tolerant: AST plus diagnostics, even on syntax errors.

        System.out.println(result.diagnostics());
        System.out.println(new AstSexprSerializer().serialize(result.ast()));
    }
}
```

Use `parseStrict(source)` to reject the first `ERROR` diagnostic, `tokenize(source)` for the raw token stream, or `parseCompletionContext(source, byteOffset)` to classify a UTF-8 byte cursor position.

## Upgrading to 0.8.0

The Tree-sitter backend and the deprecated CST and completion APIs have been removed. Replace CST parsing plus mapping with `GdScriptParserFacade.parse(source)`, and use `ast()` and `diagnostics()` on the result. Replace strict mapping with `parseStrict(source)`. For completion, use `frontend.gdscript.CompletionContext` from the same facade and handle all eight kinds.

There is no raw CST, native loader or ABI configuration to migrate, and gdparser does not provide a parser CLI. See the [0.8.0 migration guide](doc/architecture-overview.md#4-migrating-to-080) for the API differences.

## Documentation

- [Pure-Java parser implementation](doc/godot-java-parser-implementation.md): parser contracts and known limitations
- [Architecture overview](doc/architecture-overview.md): architecture and maintenance guide

## License

This project is licensed under the **GNU Lesser General Public License v3.0 (LGPL-3.0)**.
See `LICENSE` for the full license text.
