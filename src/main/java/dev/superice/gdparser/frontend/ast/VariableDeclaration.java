package dev.superice.gdparser.frontend.ast;

import org.jetbrains.annotations.Nullable;

import java.util.List;

/// var/const declaration.
///
/// @param kind declaration kind (`VAR` or `CONST`)
/// @param name declared identifier name
/// @param type optional explicit or inferred type marker
/// @param value optional initializer expression
/// @param isStatic whether the declaration is static
/// @param sourceNodeType originating CST/AST node type label
/// @param range source byte/point span
/// @param recoveryAnnotations unattached recovery annotations retained from property accessors in source order
public record VariableDeclaration(
        DeclarationKind kind,
        String name,
        @Nullable TypeRef type,
        @Nullable Expression value,
        boolean isStatic,
        String sourceNodeType,
        Range range,
        List<AnnotationStatement> recoveryAnnotations
) implements Statement {

    public VariableDeclaration {
        recoveryAnnotations = recoveryAnnotations == null ? List.of() : List.copyOf(recoveryAnnotations);
    }

    public VariableDeclaration(
            DeclarationKind kind,
            String name,
            @Nullable TypeRef type,
            @Nullable Expression value,
            boolean isStatic,
            String sourceNodeType,
            Range range
    ) {
        this(kind, name, type, value, isStatic, sourceNodeType, range, List.of());
    }

    @Override
    public List<Node> getChildren() {
        return NodeChildren.builder()
                .add(type)
                .add(value)
                .addAll(recoveryAnnotations)
                .toList();
    }
}
