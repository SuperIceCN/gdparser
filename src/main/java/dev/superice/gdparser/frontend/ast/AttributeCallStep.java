package dev.superice.gdparser.frontend.ast;

import java.util.List;

/// method call step in attribute chain.
public record AttributeCallStep(String name, List<Expression> arguments, Range range) implements AttributeStep {
}
