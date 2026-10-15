package dev.superice.gdparser.frontend.ast;

import org.jetbrains.annotations.NotNull;

/// Same-generation association from a diagnostic to the final AST construct that owns the
/// damaged facet (e.g. a missing `)` owned by the enclosing variable declaration's
/// initializer). The owner is a direct node reference: the link is only valid together with
/// the parse result that produced it and must not be reused across re-parses or serde
/// round-trips.
///
/// @param owner the surviving AST construct responsible for the damaged facet
/// @param facet dotted slot name identifying the damaged facet, shared with the marker
///              `nodeType` vocabulary (e.g. `"variable.initializer"`)
public record RecoveryLink(@NotNull Node owner, @NotNull String facet) {
}
