package dev.superice.gdparser.frontend.ast;

/// plain property access step.
public record AttributePropertyStep(String name, Range range) implements AttributeStep {
}
