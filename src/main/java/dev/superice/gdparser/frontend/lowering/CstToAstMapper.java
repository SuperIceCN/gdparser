package dev.superice.gdparser.frontend.lowering;

import dev.superice.gdparser.frontend.ast.*;
import dev.superice.gdparser.frontend.cst.CstErrorDetector;
import dev.superice.gdparser.frontend.cst.CstNodeView;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/// Maps GDScript CST nodes to a stable Java AST and emits lowering diagnostics.
public final class CstToAstMapper {

    public @NotNull AstMappingResult map(String source, CstNodeView root) {
        Objects.requireNonNull(source, "source must not be null");
        Objects.requireNonNull(root, "root must not be null");

        var context = new MappingContext(source.getBytes(StandardCharsets.UTF_8));
        context.collectStructuralIssues(root);

        if (!root.type().equals("source")) {
            context.warn("Expected source root node but got: " + root.type(), root);
        }

        var statements = context.mapStatements(root.namedChildren());
        var ast = new SourceFile(List.copyOf(statements), AstFactory.range(root.range()));
        return new AstMappingResult(ast, context.diagnostics());
    }

    @SuppressWarnings("unused")
    public @NotNull SourceFile mapStrict(String source, CstNodeView root) {
        var result = map(source, root);
        var hasErrors = result.diagnostics().stream().anyMatch(d -> d.severity() == AstDiagnosticSeverity.ERROR);
        if (hasErrors) {
            var firstError = result.diagnostics().stream()
                    .filter(d -> d.severity() == AstDiagnosticSeverity.ERROR)
                    .findFirst()
                    .orElseThrow();
            throw new IllegalStateException("Lowering failed at " + firstError.nodeType() + ": " + firstError.message());
        }
        return result.ast();
    }

    private static final class MappingContext {

        private final byte[] sourceBytes;
        private final List<AstDiagnostic> diagnostics;

        private MappingContext(byte[] sourceBytes) {
            this.sourceBytes = sourceBytes;
            this.diagnostics = new ArrayList<>();
        }

        private @NotNull List<AstDiagnostic> diagnostics() {
            return List.copyOf(diagnostics);
        }

        private void collectStructuralIssues(CstNodeView root) {
            for (var issue : CstErrorDetector.collect(root)) {
                diagnostics.add(AstFactory.diagnostic(
                        AstDiagnosticSeverity.ERROR,
                        "CST structural issue: " + issue.kind(),
                        issue.nodeType(),
                        issue.range()
                ));
            }
        }

        private @NotNull List<Statement> mapStatements(List<CstNodeView> nodes) {
            var statements = new ArrayList<Statement>();
            for (var node : nodes) {
                statements.add(mapStatement(node));
            }
            return List.copyOf(statements);
        }

        private @NotNull Statement mapStatement(CstNodeView node) {
            return switch (node.type()) {
                case "class_name_statement" -> mapClassNameStatement(node);
                case "extends_statement" -> mapExtendsStatement(node);
                case "signal_statement" -> mapSignalStatement(node);
                case "variable_statement", "onready_variable_statement", "export_variable_statement" ->
                        mapVariableDeclaration(node, DeclarationKind.VAR);
                case "const_statement" -> mapVariableDeclaration(node, DeclarationKind.CONST);
                case "function_definition" -> mapFunctionDeclaration(node);
                case "if_statement" -> mapIfStatement(node);
                case "for_statement" -> mapForStatement(node);
                case "while_statement" -> mapWhileStatement(node);
                case "match_statement" -> mapMatchStatement(node);
                case "return_statement" -> mapReturnStatement(node);
                case "expression_statement" -> mapExpressionStatement(node);
                case "pass_statement" -> new PassStatement(AstFactory.range(node.range()));
                default -> {
                    warn("Unsupported statement node: " + node.type(), node);
                    yield AstFactory.unknownStatement(node, text(node));
                }
            };
        }

        private @NotNull ClassNameStatement mapClassNameStatement(CstNodeView node) {
            var nameNode = requireField(node, "name");
            var extendsNode = node.childByField("extends");
            var iconNode = node.childByField("icon_path");
            return new ClassNameStatement(
                    textTrimmed(nameNode),
                    extractExtendsTarget(extendsNode),
                    iconNode == null ? null : textTrimmed(iconNode),
                    AstFactory.range(node.range())
            );
        }

        private @NotNull ExtendsStatement mapExtendsStatement(CstNodeView node) {
            var targetNode = firstNamedChild(node);
            if (targetNode == null) {
                error("extends_statement missing target", node);
                return new ExtendsStatement("", AstFactory.range(node.range()));
            }
            return new ExtendsStatement(textTrimmed(targetNode), AstFactory.range(node.range()));
        }

        private @NotNull SignalStatement mapSignalStatement(CstNodeView node) {
            var nameNode = requireField(node, "name");
            var parametersNode = node.childByField("parameters");
            return new SignalStatement(
                    textTrimmed(nameNode),
                    mapParameters(parametersNode),
                    AstFactory.range(node.range())
            );
        }

        private @NotNull VariableDeclaration mapVariableDeclaration(CstNodeView node, DeclarationKind kind) {
            var nameNode = requireField(node, "name");
            var typeNode = node.childByField("type");
            var valueNode = node.childByField("value");
            var isStatic = node.childByField("static") != null;

            return new VariableDeclaration(
                    kind,
                    textTrimmed(nameNode),
                    mapTypeRef(typeNode),
                    valueNode == null ? null : mapExpression(valueNode),
                    isStatic,
                    node.type(),
                    AstFactory.range(node.range())
            );
        }

        private @NotNull FunctionDeclaration mapFunctionDeclaration(CstNodeView node) {
            var nameNode = node.childByField("name");
            var parametersNode = requireField(node, "parameters");
            var returnTypeNode = node.childByField("return_type");
            var bodyNode = node.childByField("body");

            var name = nameNode == null ? "<anonymous>" : textTrimmed(nameNode);
            var body = mapBody(bodyNode, AstFactory.range(node.range()));
            return new FunctionDeclaration(
                    name,
                    mapParameters(parametersNode),
                    mapTypeRef(returnTypeNode),
                    body,
                    AstFactory.range(node.range())
            );
        }

        private @NotNull IfStatement mapIfStatement(CstNodeView node) {
            var conditionNode = requireField(node, "condition");
            var bodyNode = requireField(node, "body");
            var elifClauses = new ArrayList<ElifClause>();
            Block elseBody = null;

            for (var child : node.namedChildren()) {
                if (child.type().equals("elif_clause")) {
                    var elifCondition = requireField(child, "condition");
                    var elifBody = requireField(child, "body");
                    elifClauses.add(new ElifClause(
                            mapExpression(elifCondition),
                            mapBody(elifBody, AstFactory.range(child.range())),
                            AstFactory.range(child.range())
                    ));
                } else if (child.type().equals("else_clause")) {
                    var elseBodyNode = requireField(child, "body");
                    elseBody = mapBody(elseBodyNode, AstFactory.range(child.range()));
                }
            }

            return new IfStatement(
                    mapExpression(conditionNode),
                    mapBody(bodyNode, AstFactory.range(node.range())),
                    List.copyOf(elifClauses),
                    elseBody,
                    AstFactory.range(node.range())
            );
        }

        private @NotNull ForStatement mapForStatement(CstNodeView node) {
            var leftNode = requireField(node, "left");
            var rightNode = requireField(node, "right");
            var bodyNode = requireField(node, "body");
            var typeNode = node.childByField("type");

            return new ForStatement(
                    textTrimmed(leftNode),
                    mapTypeRef(typeNode),
                    mapExpression(rightNode),
                    mapBody(bodyNode, AstFactory.range(node.range())),
                    AstFactory.range(node.range())
            );
        }

        private @NotNull WhileStatement mapWhileStatement(CstNodeView node) {
            var conditionNode = requireField(node, "condition");
            var bodyNode = requireField(node, "body");
            return new WhileStatement(
                    mapExpression(conditionNode),
                    mapBody(bodyNode, AstFactory.range(node.range())),
                    AstFactory.range(node.range())
            );
        }

        private @NotNull MatchStatement mapMatchStatement(CstNodeView node) {
            var valueNode = requireField(node, "value");
            var bodyNode = requireField(node, "body");
            var sections = new ArrayList<MatchSection>();

            for (var child : bodyNode.namedChildren()) {
                if (child.type().equals("pattern_section")) {
                    sections.add(mapMatchSection(child));
                }
            }

            return new MatchStatement(mapExpression(valueNode), List.copyOf(sections), AstFactory.range(node.range()));
        }

        private @NotNull MatchSection mapMatchSection(CstNodeView sectionNode) {
            var bodyNode = requireField(sectionNode, "body");
            var patterns = new ArrayList<Expression>();
            Expression guard = null;

            for (var child : sectionNode.namedChildren()) {
                if (child == bodyNode) {
                    continue;
                }
                if (child.type().equals("pattern_guard")) {
                    var guardExpression = firstNamedChild(child);
                    if (guardExpression != null) {
                        guard = mapExpression(guardExpression);
                    }
                    continue;
                }
                patterns.add(mapExpression(child));
            }

            return new MatchSection(
                    List.copyOf(patterns),
                    guard,
                    mapBody(bodyNode, AstFactory.range(sectionNode.range())),
                    AstFactory.range(sectionNode.range())
            );
        }

        private @NotNull ReturnStatement mapReturnStatement(CstNodeView node) {
            var valueNode = firstNamedChild(node);
            return new ReturnStatement(
                    valueNode == null ? null : mapExpression(valueNode),
                    AstFactory.range(node.range())
            );
        }

        private @NotNull ExpressionStatement mapExpressionStatement(CstNodeView node) {
            var expressionNode = firstNamedChild(node);
            if (expressionNode == null) {
                error("expression_statement missing expression", node);
                return new ExpressionStatement(AstFactory.unknownExpression(node, text(node)), AstFactory.range(node.range()));
            }
            return new ExpressionStatement(mapExpression(expressionNode), AstFactory.range(node.range()));
        }

        private @NotNull Block mapBody(@Nullable CstNodeView bodyNode, Range fallbackRange) {
            if (bodyNode == null) {
                return new Block(List.of(), fallbackRange);
            }
            return new Block(mapStatements(bodyNode.namedChildren()), AstFactory.range(bodyNode.range()));
        }

        private @NotNull Expression mapExpression(CstNodeView node) {
            return switch (node.type()) {
                case "identifier", "name" ->
                        new IdentifierExpression(textTrimmed(node), AstFactory.range(node.range()));
                case "integer", "float", "string", "string_name", "true", "false", "null", "node_path", "get_node" ->
                        new LiteralExpression(node.type(), text(node), AstFactory.range(node.range()));
                case "call" -> mapCallExpression(node);
                case "attribute" -> mapAttributeExpression(node);
                case "subscript" -> mapSubscriptExpression(node);
                case "binary_operator" -> mapBinaryExpression(node);
                case "unary_operator" -> mapUnaryExpression(node);
                case "conditional_expression" -> mapConditionalExpression(node);
                case "assignment", "augmented_assignment" -> mapAssignmentExpression(node);
                case "array" -> mapArrayExpression(node);
                case "dictionary" -> mapDictionaryExpression(node);
                case "lambda" -> mapLambdaExpression(node);
                case "parenthesized_expression" -> mapParenthesizedExpression(node);
                default -> {
                    warn("Unsupported expression node: " + node.type(), node);
                    yield AstFactory.unknownExpression(node, text(node));
                }
            };
        }

        private @NotNull Expression mapParenthesizedExpression(CstNodeView node) {
            var inner = firstNamedChild(node);
            if (inner == null) {
                warn("parenthesized_expression has no inner expression", node);
                return AstFactory.unknownExpression(node, text(node));
            }
            return mapExpression(inner);
        }

        private @NotNull CallExpression mapCallExpression(CstNodeView node) {
            var argumentsNode = node.childByField("arguments");
            var calleeNode = firstNamedChildExcluding(node, argumentsNode);
            if (calleeNode == null) {
                error("call missing callee", node);
                return new CallExpression(AstFactory.unknownExpression(node, text(node)), List.of(), AstFactory.range(node.range()));
            }
            return new CallExpression(
                    mapExpression(calleeNode),
                    mapArgumentList(argumentsNode),
                    AstFactory.range(node.range())
            );
        }

        private @NotNull AttributeExpression mapAttributeExpression(CstNodeView node) {
            var namedChildren = node.namedChildren();
            if (namedChildren.isEmpty()) {
                error("attribute missing base expression", node);
                return new AttributeExpression(
                        AstFactory.unknownExpression(node, text(node)),
                        List.of(),
                        AstFactory.range(node.range())
                );
            }

            var base = mapExpression(namedChildren.getFirst());
            var steps = new ArrayList<AttributeStep>();
            for (var index = 1; index < namedChildren.size(); index++) {
                steps.add(mapAttributeStep(namedChildren.get(index)));
            }
            return new AttributeExpression(base, List.copyOf(steps), AstFactory.range(node.range()));
        }

        private @NotNull AttributeStep mapAttributeStep(CstNodeView node) {
            return switch (node.type()) {
                case "attribute_call" -> new AttributeCallStep(
                        textTrimmed(firstNamedChild(node)),
                        mapArgumentList(node.childByField("arguments")),
                        AstFactory.range(node.range())
                );
                case "attribute_subscript" -> new AttributeSubscriptStep(
                        textTrimmed(firstNamedChild(node)),
                        mapArgumentList(node.childByField("arguments")),
                        AstFactory.range(node.range())
                );
                default -> {
                    warn("Unsupported attribute step: " + node.type(), node);
                    yield new UnknownAttributeStep(node.type(), text(node), AstFactory.range(node.range()));
                }
            };
        }

        private @NotNull SubscriptExpression mapSubscriptExpression(CstNodeView node) {
            var argumentsNode = node.childByField("arguments");
            var baseNode = firstNamedChildExcluding(node, argumentsNode);
            if (baseNode == null) {
                error("subscript missing base expression", node);
                return new SubscriptExpression(
                        AstFactory.unknownExpression(node, text(node)),
                        List.of(),
                        AstFactory.range(node.range())
                );
            }
            return new SubscriptExpression(
                    mapExpression(baseNode),
                    mapArgumentList(argumentsNode),
                    AstFactory.range(node.range())
            );
        }

        private @NotNull BinaryExpression mapBinaryExpression(CstNodeView node) {
            var leftNode = requireField(node, "left");
            var rightNode = requireField(node, "right");
            var operator = firstOperator(node, "<binary-op>");
            return new BinaryExpression(
                    operator,
                    mapExpression(leftNode),
                    mapExpression(rightNode),
                    AstFactory.range(node.range())
            );
        }

        private @NotNull UnaryExpression mapUnaryExpression(CstNodeView node) {
            var operandNode = firstNamedChild(node);
            if (operandNode == null) {
                error("unary_operator missing operand", node);
                return new UnaryExpression("<unary-op>", AstFactory.unknownExpression(node, text(node)), AstFactory.range(node.range()));
            }
            var operator = firstOperator(node, "<unary-op>");
            return new UnaryExpression(operator, mapExpression(operandNode), AstFactory.range(node.range()));
        }

        private @NotNull ConditionalExpression mapConditionalExpression(CstNodeView node) {
            var conditionNode = requireField(node, "condition");
            var leftNode = requireField(node, "left");
            var rightNode = requireField(node, "right");
            return new ConditionalExpression(
                    mapExpression(conditionNode),
                    mapExpression(leftNode),
                    mapExpression(rightNode),
                    AstFactory.range(node.range())
            );
        }

        private @NotNull AssignmentExpression mapAssignmentExpression(CstNodeView node) {
            var leftNode = requireField(node, "left");
            var rightNode = requireField(node, "right");
            var operator = firstOperator(node, node.type().equals("assignment") ? "=" : "<aug-assignment-op>");
            return new AssignmentExpression(
                    operator,
                    mapExpression(leftNode),
                    mapExpression(rightNode),
                    AstFactory.range(node.range())
            );
        }

        private @NotNull ArrayExpression mapArrayExpression(CstNodeView node) {
            var elements = new ArrayList<Expression>();
            var openEnded = false;
            for (var child : node.namedChildren()) {
                if (child.type().equals("pattern_open_ending")) {
                    openEnded = true;
                } else {
                    elements.add(mapExpression(child));
                }
            }
            return new ArrayExpression(List.copyOf(elements), openEnded, AstFactory.range(node.range()));
        }

        private @NotNull DictionaryExpression mapDictionaryExpression(CstNodeView node) {
            var entries = new ArrayList<DictEntry>();
            var openEnded = false;
            for (var child : node.namedChildren()) {
                if (child.type().equals("pair")) {
                    entries.add(mapDictionaryEntry(child));
                } else if (child.type().equals("pattern_open_ending")) {
                    openEnded = true;
                } else {
                    warn("Unsupported dictionary child node: " + child.type(), child);
                }
            }
            return new DictionaryExpression(List.copyOf(entries), openEnded, AstFactory.range(node.range()));
        }

        private @NotNull DictEntry mapDictionaryEntry(CstNodeView node) {
            var leftNode = requireField(node, "left");
            var valueNode = requireField(node, "value");
            return new DictEntry(mapExpression(leftNode), mapExpression(valueNode), AstFactory.range(node.range()));
        }

        private @NotNull LambdaExpression mapLambdaExpression(CstNodeView node) {
            var nameNode = node.childByField("name");
            var parametersNode = requireField(node, "parameters");
            var returnTypeNode = node.childByField("return_type");
            var bodyNode = requireField(node, "body");

            return new LambdaExpression(
                    nameNode == null ? null : textTrimmed(nameNode),
                    mapParameters(parametersNode),
                    mapTypeRef(returnTypeNode),
                    mapBody(bodyNode, AstFactory.range(node.range())),
                    AstFactory.range(node.range())
            );
        }

        private @NotNull List<Parameter> mapParameters(@Nullable CstNodeView parametersNode) {
            if (parametersNode == null) {
                return List.of();
            }

            var parameters = new ArrayList<Parameter>();
            for (var child : parametersNode.namedChildren()) {
                parameters.add(mapParameter(child));
            }
            return List.copyOf(parameters);
        }

        private @NotNull Parameter mapParameter(CstNodeView node) {
            return switch (node.type()) {
                case "identifier", "name" -> new Parameter(
                        textTrimmed(node),
                        null,
                        null,
                        false,
                        AstFactory.range(node.range())
                );
                case "typed_parameter" -> new Parameter(
                        textTrimmed(firstNamedChild(node)),
                        mapTypeRef(node.childByField("type")),
                        null,
                        false,
                        AstFactory.range(node.range())
                );
                case "default_parameter" -> new Parameter(
                        textTrimmed(firstNamedChild(node)),
                        null,
                        mapExpression(requireField(node, "value")),
                        false,
                        AstFactory.range(node.range())
                );
                case "typed_default_parameter" -> new Parameter(
                        textTrimmed(firstNamedChild(node)),
                        mapTypeRef(node.childByField("type")),
                        mapExpression(requireField(node, "value")),
                        false,
                        AstFactory.range(node.range())
                );
                case "variadic_parameter" -> mapVariadicParameter(node);
                default -> {
                    warn("Unsupported parameter node: " + node.type(), node);
                    yield new Parameter(
                            textTrimmed(node),
                            null,
                            null,
                            false,
                            AstFactory.range(node.range())
                    );
                }
            };
        }

        private @NotNull Parameter mapVariadicParameter(CstNodeView node) {
            var nested = firstNamedChild(node);
            if (nested == null) {
                warn("variadic_parameter missing nested parameter", node);
                return new Parameter("", null, null, true, AstFactory.range(node.range()));
            }

            var inner = mapParameter(nested);
            return new Parameter(
                    inner.name(),
                    inner.type(),
                    inner.defaultValue(),
                    true,
                    AstFactory.range(node.range())
            );
        }

        private @Nullable TypeRef mapTypeRef(@Nullable CstNodeView node) {
            if (node == null) {
                return null;
            }
            return new TypeRef(textTrimmed(node), AstFactory.range(node.range()));
        }

        private @NotNull List<Expression> mapArgumentList(@Nullable CstNodeView argumentsNode) {
            if (argumentsNode == null) {
                return List.of();
            }
            var arguments = new ArrayList<Expression>();
            for (var child : argumentsNode.namedChildren()) {
                arguments.add(mapExpression(child));
            }
            return List.copyOf(arguments);
        }

        private @Nullable String extractExtendsTarget(@Nullable CstNodeView extendsNode) {
            if (extendsNode == null) {
                return null;
            }
            if (extendsNode.type().equals("extends_statement")) {
                var target = firstNamedChild(extendsNode);
                return target == null ? null : textTrimmed(target);
            }
            return textTrimmed(extendsNode);
        }

        private @NotNull CstNodeView requireField(CstNodeView node, String fieldName) {
            var field = node.childByField(fieldName);
            if (field == null) {
                error("Missing required field '" + fieldName + "'", node);
                return node;
            }
            return field;
        }

        private @Nullable CstNodeView firstNamedChild(CstNodeView node) {
            var children = node.namedChildren();
            return children.isEmpty() ? null : children.getFirst();
        }

        private @Nullable CstNodeView firstNamedChildExcluding(CstNodeView node, @Nullable CstNodeView excludedNode) {
            for (var child : node.namedChildren()) {
                if (child != excludedNode) {
                    return child;
                }
            }
            return null;
        }

        private @NotNull String firstOperator(CstNodeView node, String fallback) {
            for (var child : node.children()) {
                if (!child.isNamed()) {
                    return child.type();
                }
            }
            return fallback;
        }

        private @NotNull String text(CstNodeView node) {
            var start = Math.max(0, node.range().startByte());
            var end = Math.min(sourceBytes.length, node.range().endByte());
            if (start >= end) {
                return "";
            }
            return new String(sourceBytes, start, end - start, StandardCharsets.UTF_8);
        }

        private @NotNull String textTrimmed(@Nullable CstNodeView node) {
            if (node == null) {
                return "";
            }
            return text(node).trim();
        }

        private void warn(String message, CstNodeView node) {
            diagnostics.add(AstFactory.diagnostic(AstDiagnosticSeverity.WARNING, message, node));
        }

        private void error(String message, CstNodeView node) {
            diagnostics.add(AstFactory.diagnostic(AstDiagnosticSeverity.ERROR, message, node));
        }
    }
}
