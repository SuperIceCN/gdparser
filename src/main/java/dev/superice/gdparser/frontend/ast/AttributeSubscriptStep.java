package dev.superice.gdparser.frontend.ast;

import java.util.List;

/// subscript call step in attribute chain.
public record AttributeSubscriptStep(String name, List<Expression> arguments, Range range) implements AttributeStep {
}
