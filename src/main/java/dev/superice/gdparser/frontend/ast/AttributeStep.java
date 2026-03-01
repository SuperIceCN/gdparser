package dev.superice.gdparser.frontend.ast;

/// one step after base expression in attribute chain.
public sealed interface AttributeStep extends Node permits AttributePropertyStep, AttributeCallStep, AttributeSubscriptStep, UnknownAttributeStep {
}
