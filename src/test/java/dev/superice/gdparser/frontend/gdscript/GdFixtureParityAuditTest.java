package dev.superice.gdparser.frontend.gdscript;

import dev.superice.gdparser.frontend.ast.ASTNodeHandler;
import dev.superice.gdparser.frontend.ast.ASTWalker;
import dev.superice.gdparser.frontend.ast.BinaryExpression;
import dev.superice.gdparser.frontend.ast.CastExpression;
import dev.superice.gdparser.frontend.ast.Expression;
import dev.superice.gdparser.frontend.ast.FrontendASTTraversalDirective;
import dev.superice.gdparser.frontend.ast.GetNodeExpression;
import dev.superice.gdparser.frontend.ast.UnaryExpression;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

/// Fixture parity-prerequisite audit: the migration plan's dual-path parity promise only
/// covers fixtures free of the registered divergence combinations (operator-precedence
/// divergences between Godot and the frozen tree-sitter grammar, and Godot-only constructs
/// the reference path cannot express). This test re-derives that audit from the reference
/// path's AST so adding a fixture containing a divergence fails loudly instead of
/// silently breaking parity later.
///
/// Rules (anchored to the divergence registry in the migration plan):
/// - no `**` directly combined with `*`/`/`/`%` or with a unary operand (power/factor
///   precedence divergence);
/// - no binary operation with a cast (`as`) as its direct right operand (cast precedence
///   divergence), and no cast over a binary expression (the other side of the same rule);
/// - no `not in` operator;
/// - no `%` unique-name access under a `$` expression (`$%Child`, `$Root/%Child`; the
///   reference path rejects those). A bare `%name` expression is fine on both paths.
class GdFixtureParityAuditTest extends GdParserParityTestSupport {

    private static final Set<String> FACTOR_OPERATORS = Set.of("*", "/", "%");

    @TestFactory
    List<DynamicTest> fixturesAreFreeOfRegisteredDivergences() throws IOException {
        var scripts = fixtureScripts();
        assertEquals(13, scripts.size(), "fixture corpus size changed; review parity prerequisites");

        var tests = new ArrayList<DynamicTest>();
        for (var script : scripts) {
            tests.add(dynamicTest("parity-audit: " + script.getFileName(), () -> {
                var source = Files.readString(script, StandardCharsets.UTF_8);
                var result = parseViaTreeSitter(source);
                assertTrue(result.diagnostics().isEmpty(),
                        () -> "fixture no longer lowers cleanly: " + result.diagnostics());
                assertFalse(result.ast().statements().isEmpty(), "fixture produced an empty AST");

                var violations = new ArrayList<String>();
                new ASTWalker(new ASTNodeHandler() {
                    @Override
                    public FrontendASTTraversalDirective handleBinaryExpression(BinaryExpression node) {
                        if (node.operator().equals("not in")) {
                            violations.add("'not in' expression at " + node.range().startPoint());
                        }
                        if (node.operator().equals("**")) {
                            if (isFactorOrUnary(node.left()) || isFactorOrUnary(node.right())) {
                                violations.add("`**` mixed with factor/unary operand at "
                                        + node.range().startPoint());
                            }
                        }
                        if (node.right() instanceof CastExpression) {
                            violations.add("binary operator '" + node.operator()
                                    + "' with `as` right operand at " + node.range().startPoint());
                        }
                        return FrontendASTTraversalDirective.CONTINUE;
                    }

                    @Override
                    public FrontendASTTraversalDirective handleCastExpression(CastExpression node) {
                        if (node.value() instanceof BinaryExpression) {
                            violations.add("cast over a binary expression at " + node.range().startPoint());
                        }
                        return FrontendASTTraversalDirective.CONTINUE;
                    }

                    @Override
                    public FrontendASTTraversalDirective handleGetNodeExpression(GetNodeExpression node) {
                        if (node.sourceText().startsWith("$") && node.sourceText().contains("%")) {
                            violations.add("unique-name node path under `$` at " + node.range().startPoint());
                        }
                        return FrontendASTTraversalDirective.CONTINUE;
                    }

                    private boolean isFactorOrUnary(Expression expression) {
                        if (expression instanceof UnaryExpression) {
                            return true;
                        }
                        return expression instanceof BinaryExpression binary
                                && FACTOR_OPERATORS.contains(binary.operator());
                    }
                }).walk(result.ast());

                assertTrue(violations.isEmpty(),
                        () -> script.getFileName() + " contains registered divergences: " + violations);
            }));
        }
        return List.copyOf(tests);
    }
}
