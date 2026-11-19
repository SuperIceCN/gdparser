package dev.superice.gdparser.frontend.ast;

import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Objects;

/// Explicit recovery barrier for an infix/postfix operator whose right-hand operand or target
/// failed (such as `x as`, `x is`, `x is not`, or `x ?`). Preserves the surviving left `operand`
/// subtree alongside an `ErrorExpression` marker so editor consumers keep partial AST structure
/// without misrepresenting the incomplete construct as a valid value or type node.
///
/// @param operand surviving left-hand expression subtree
/// @param operator source operator spelling (`"as"`, `"is"`, `"is not"`, `"?"`, etc.)
/// @param problem recovery marker capturing the missing or damaged right-hand side
/// @param range full span covering the operand, consumed operator, and damaged tail
public record RecoveredInfixExpression(
        @NotNull Expression operand,
        @NotNull String operator,
        @NotNull ErrorExpression problem,
        @NotNull Range range
) implements Expression {

    public RecoveredInfixExpression {
        Objects.requireNonNull(operand, "operand must not be null");
        Objects.requireNonNull(operator, "operator must not be null");
        Objects.requireNonNull(problem, "problem must not be null");
        Objects.requireNonNull(range, "range must not be null");
    }

    @Override
    public List<Node> getChildren() {
        return List.of(operand, problem);
    }
}
