package dev.superice.gdparser.infra.treesitter;

/// Minimal parse output for PoC verification.
public record GdParseSnapshot(String rootType, String sExpression, boolean hasError) {
}
