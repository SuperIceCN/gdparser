package dev.superice.gdparser.infra.treesitter;

import dev.superice.gdparser.frontend.gdscript.GdScriptParserFacade;

/// Legacy native parser snapshot.
///
/// @deprecated Use [GdScriptParserFacade#parse(String)] for typed syntax and diagnostics.
@Deprecated
public record GdParseSnapshot(String rootType, String sExpression, boolean hasError) {
}
