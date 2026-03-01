package dev.superice.gdparser.frontend.ast;

/// fallback attribute chain step.
public record UnknownAttributeStep(String nodeType, String sourceText, Range range) implements AttributeStep {
}
