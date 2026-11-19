package dev.superice.gdparser.frontend.lowering;

import dev.superice.gdparser.frontend.ast.AnnotationStatement;
import dev.superice.gdparser.frontend.ast.ArrayExpression;
import dev.superice.gdparser.frontend.ast.AssertStatement;
import dev.superice.gdparser.frontend.ast.AssignmentExpression;
import dev.superice.gdparser.frontend.ast.AttributeCallStep;
import dev.superice.gdparser.frontend.ast.AttributeExpression;
import dev.superice.gdparser.frontend.ast.AttributePropertyStep;
import dev.superice.gdparser.frontend.ast.AttributeStep;
import dev.superice.gdparser.frontend.ast.AttributeSubscriptStep;
import dev.superice.gdparser.frontend.ast.AstDiagnostic;
import dev.superice.gdparser.frontend.ast.AstDiagnosticSeverity;
import dev.superice.gdparser.frontend.ast.AstFactory;
import dev.superice.gdparser.frontend.ast.AstMappingResult;
import dev.superice.gdparser.frontend.ast.AwaitExpression;
import dev.superice.gdparser.frontend.ast.BinaryExpression;
import dev.superice.gdparser.frontend.ast.Block;
import dev.superice.gdparser.frontend.ast.BreakStatement;
import dev.superice.gdparser.frontend.ast.BreakpointStatement;
import dev.superice.gdparser.frontend.ast.CallExpression;
import dev.superice.gdparser.frontend.ast.CastExpression;
import dev.superice.gdparser.frontend.ast.ClassDeclaration;
import dev.superice.gdparser.frontend.ast.ClassNameStatement;
import dev.superice.gdparser.frontend.ast.CommentStatement;
import dev.superice.gdparser.frontend.ast.ConditionalExpression;
import dev.superice.gdparser.frontend.ast.ConstructorDeclaration;
import dev.superice.gdparser.frontend.ast.ContinueStatement;
import dev.superice.gdparser.frontend.ast.DeclarationKind;
import dev.superice.gdparser.frontend.ast.DictEntry;
import dev.superice.gdparser.frontend.ast.DictionaryExpression;
import dev.superice.gdparser.frontend.ast.ElifClause;
import dev.superice.gdparser.frontend.ast.EnumDeclaration;
import dev.superice.gdparser.frontend.ast.EnumMember;
import dev.superice.gdparser.frontend.ast.ErrorExpression;
import dev.superice.gdparser.frontend.ast.ErrorStatement;
import dev.superice.gdparser.frontend.ast.Expression;
import dev.superice.gdparser.frontend.ast.ExpressionStatement;
import dev.superice.gdparser.frontend.ast.ExtendsStatement;
import dev.superice.gdparser.frontend.ast.ForStatement;
import dev.superice.gdparser.frontend.ast.FunctionDeclaration;
import dev.superice.gdparser.frontend.ast.GetNodeExpression;
import dev.superice.gdparser.frontend.ast.IdentifierExpression;
import dev.superice.gdparser.frontend.ast.IfStatement;
import dev.superice.gdparser.frontend.ast.LambdaExpression;
import dev.superice.gdparser.frontend.ast.LiteralExpression;
import dev.superice.gdparser.frontend.ast.MatchSection;
import dev.superice.gdparser.frontend.ast.MatchStatement;
import dev.superice.gdparser.frontend.ast.MissingAttributeStep;
import dev.superice.gdparser.frontend.ast.Parameter;
import dev.superice.gdparser.frontend.ast.PassStatement;
import dev.superice.gdparser.frontend.ast.PatternBindingExpression;
import dev.superice.gdparser.frontend.ast.Point;
import dev.superice.gdparser.frontend.ast.PreloadExpression;
import dev.superice.gdparser.frontend.ast.Range;
import dev.superice.gdparser.frontend.ast.RegionDirectiveStatement;
import dev.superice.gdparser.frontend.ast.ReturnStatement;
import dev.superice.gdparser.frontend.ast.SelfExpression;
import dev.superice.gdparser.frontend.ast.SignalStatement;
import dev.superice.gdparser.frontend.ast.SourceFile;
import dev.superice.gdparser.frontend.ast.Statement;
import dev.superice.gdparser.frontend.ast.SubscriptExpression;
import dev.superice.gdparser.frontend.ast.TypeRef;
import dev.superice.gdparser.frontend.ast.TypeTestExpression;
import dev.superice.gdparser.frontend.ast.UnaryExpression;
import dev.superice.gdparser.frontend.ast.UnknownAttributeStep;
import dev.superice.gdparser.frontend.ast.VariableDeclaration;
import dev.superice.gdparser.frontend.ast.WhileStatement;
import dev.superice.gdparser.frontend.cst.CstErrorDetector;
import dev.superice.gdparser.frontend.cst.CstIssueKind;
import dev.superice.gdparser.frontend.cst.CstNodeView;
import dev.superice.gdparser.frontend.cst.CstRange;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/// Maps GDScript CST nodes to a stable Java AST and emits lowering diagnostics.
/// The lowered AST intentionally models GDScript 4.x only, so legacy 3.x syntax
/// that still appears in the bundled grammar is rejected with error diagnostics.
///
/// The mapper is stateless: every `map` call creates its own mapping context, so a shared
/// instance is safe to use from concurrent analyses.
public final class CstToAstMapper {

    public @NotNull AstMappingResult map(String source, CstNodeView root) {
        Objects.requireNonNull(source, "source must not be null");
        Objects.requireNonNull(root, "root must not be null");

        var context = new MappingContext(source.getBytes(StandardCharsets.UTF_8));

        if (!root.type().equals("source")) {
            context.warn("Expected source root node but got: " + root.type(), root);
        }

        // Statement mapping runs first so that bounded error recovery can record the ERROR nodes
        // it decomposed; structural issue collection then skips exactly those nodes.
        var statements = context.mapStatements(context.significantNamedChildren(root));
        context.collectStructuralIssues(root);
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

        /// The grammar models trailing `\` line continuations as named extra nodes, so they can
        /// surface between the named children of any construct that spans a continued line.
        private static final String LINE_CONTINUATION_TYPE = "line_continuation";
        private static final Pattern LINE_CONTINUATION_PATTERN = Pattern.compile("\\\\\\r?\\n");

        private final byte[] sourceBytes;
        private final List<AstDiagnostic> diagnostics;
        /// ERROR nodes decomposed by bounded tail recovery, tracked by identity so that the
        /// blanket whole-error structural diagnostic can be replaced by the precise synthesized
        /// diagnostics emitted during decomposition.
        private final Set<CstNodeView> recoveredErrors = Collections.newSetFromMap(new IdentityHashMap<>());

        private MappingContext(byte[] sourceBytes) {
            this.sourceBytes = sourceBytes;
            this.diagnostics = new ArrayList<>();
        }

        private @NotNull List<AstDiagnostic> diagnostics() {
            return List.copyOf(diagnostics);
        }

        private void collectStructuralIssues(CstNodeView root) {
            for (var issue : CstErrorDetector.collect(root)) {
                if (issue.kind() == CstIssueKind.ERROR && recoveredErrors.contains(issue.node())) {
                    continue;
                }
                var message = issue.kind() == CstIssueKind.MISSING
                        ? "Missing " + issue.nodeType()
                        : "CST structural issue: " + issue.kind();
                diagnostics.add(AstFactory.diagnostic(
                        AstDiagnosticSeverity.ERROR,
                        message,
                        issue.nodeType(),
                        issue.range()
                ));
            }
        }

        private @NotNull List<Statement> mapStatements(List<CstNodeView> nodes) {
            var statements = new ArrayList<Statement>();
            for (var node : nodes) {
                statements.addAll(mapStatementSequence(node));
            }
            return List.copyOf(statements);
        }

        private @NotNull List<Statement> mapStatementSequence(CstNodeView node) {
            return switch (node.type()) {
                case "annotation" -> List.of(mapAnnotationStatement(node));
                case "annotations" -> mapAnnotationStatements(node);
                default -> {
                    if (node.isError()) {
                        var recovered = decomposeErrorStatement(node);
                        yield recovered != null
                                ? recovered
                                : List.of(AstFactory.errorStatement(CstIssueKind.ERROR, node, text(node)));
                    }
                    var statements = new ArrayList<>(mapAnnotationStatements(findNamedChildByType(node, "annotations")));
                    statements.add(mapStatement(node));
                    yield List.copyOf(statements);
                }
            };
        }

        private @NotNull List<Statement> mapAnnotationStatements(@Nullable CstNodeView annotationsNode) {
            if (annotationsNode == null) {
                return List.of();
            }
            if (annotationsNode.type().equals("annotation")) {
                return List.of(mapAnnotationStatement(annotationsNode));
            }

            var statements = new ArrayList<Statement>();
            for (var child : significantNamedChildren(annotationsNode)) {
                if (child.type().equals("annotation")) {
                    statements.add(mapAnnotationStatement(child));
                }
            }
            return List.copyOf(statements);
        }

        private @NotNull Statement mapAnnotationStatement(CstNodeView node) {
            if (!textTrimmed(node).startsWith("@")) {
                error("The bare `tool` keyword was removed in GDScript 4.x. Use `@tool` instead.", node);
                return AstFactory.unknownStatement(node, text(node));
            }

            var nameNode = firstNamedChild(node);
            if (nameNode == null) {
                error("annotation missing name", node);
                return new AnnotationStatement("", List.of(), AstFactory.range(node.range()));
            }
            return new AnnotationStatement(
                    textTrimmed(nameNode),
                    mapArgumentList(node.childByField("arguments")),
                    AstFactory.range(node.range())
            );
        }

        private @NotNull Statement mapStatement(CstNodeView node) {
            if (node.isError()) {
                return AstFactory.errorStatement(CstIssueKind.ERROR, node, text(node));
            }
            if (CstErrorDetector.isMissingPlaceholder(node)) {
                return AstFactory.errorStatement(CstIssueKind.MISSING, node, text(node));
            }

            var legacyMessage = legacyStatementMessage(node);
            if (legacyMessage != null) {
                error(legacyMessage, node);
                return AstFactory.unknownStatement(node, text(node));
            }

            return switch (node.type()) {
                case "class_name_statement" -> mapClassNameStatement(node);
                case "extends_statement" -> mapExtendsStatement(node);
                case "signal_statement" -> mapSignalStatement(node);
                case "variable_statement" -> mapVariableDeclaration(node, DeclarationKind.VAR);
                case "const_statement" -> mapVariableDeclaration(node, DeclarationKind.CONST);
                case "function_definition" -> mapFunctionDeclaration(node);
                case "constructor_definition" -> mapConstructorDeclaration(node);
                case "class_definition" -> mapClassDeclaration(node);
                case "enum_definition" -> mapEnumDeclaration(node);
                case "if_statement" -> mapIfStatement(node);
                case "for_statement" -> mapForStatement(node);
                case "while_statement" -> mapWhileStatement(node);
                case "match_statement" -> mapMatchStatement(node);
                case "return_statement" -> mapReturnStatement(node);
                case "break_statement" -> new BreakStatement(AstFactory.range(node.range()));
                case "continue_statement" -> new ContinueStatement(AstFactory.range(node.range()));
                case "breakpoint_statement" -> new BreakpointStatement(AstFactory.range(node.range()));
                case "region_start", "region_end" -> mapRegionDirectiveStatement(node);
                case "comment" -> new CommentStatement(text(node), AstFactory.range(node.range()));
                case "expression_statement" -> mapExpressionStatement(node);
                case "pass_statement" -> new PassStatement(AstFactory.range(node.range()));
                default -> {
                    warn("Unsupported statement node: " + node.type(), node);
                    yield AstFactory.unknownStatement(node, text(node));
                }
            };
        }

        private @NotNull Statement mapClassNameStatement(CstNodeView node) {
            var nameNode = requireField(node, "name");
            if (nameNode == null) {
                return AstFactory.errorStatement(CstIssueKind.MISSING, node, text(node));
            }
            var extendsNode = node.childByField("extends");
            return new ClassNameStatement(
                    textTrimmed(nameNode),
                    extractExtendsTarget(extendsNode),
                    AstFactory.range(node.range())
            );
        }

        private @NotNull Statement mapClassDeclaration(CstNodeView node) {
            var nameNode = requireField(node, "name");
            if (nameNode == null) {
                return AstFactory.errorStatement(CstIssueKind.MISSING, node, text(node));
            }
            var extendsNode = node.childByField("extends");
            var bodyNode = requireField(node, "body");
            return new ClassDeclaration(
                    textTrimmed(nameNode),
                    extractExtendsTarget(extendsNode),
                    mapBody(bodyNode, AstFactory.range(node.range())),
                    AstFactory.range(node.range())
            );
        }

        private @NotNull ConstructorDeclaration mapConstructorDeclaration(CstNodeView node) {
            var parametersNode = requireField(node, "parameters");
            var returnTypeNode = node.childByField("return_type");
            var bodyNode = requireField(node, "body");
            return new ConstructorDeclaration(
                    mapParameters(parametersNode),
                    mapTypeRef(returnTypeNode),
                    mapBody(bodyNode, AstFactory.range(node.range())),
                    AstFactory.range(node.range())
            );
        }

        private @NotNull EnumDeclaration mapEnumDeclaration(CstNodeView node) {
            var nameNode = node.childByField("name");
            var bodyNode = requireField(node, "body");
            var members = new ArrayList<EnumMember>();
            if (bodyNode != null) {
                for (var child : significantNamedChildren(bodyNode)) {
                    if (child.type().equals("enumerator")) {
                        var member = mapEnumMember(child);
                        if (member != null) {
                            members.add(member);
                        }
                    }
                }
            }
            return new EnumDeclaration(
                    nameNode == null ? null : textTrimmed(nameNode),
                    List.copyOf(members),
                    AstFactory.range(node.range())
            );
        }

        private @Nullable EnumMember mapEnumMember(CstNodeView node) {
            var nameNode = requireField(node, "left");
            if (nameNode == null) {
                return null;
            }
            var valueNode = node.childByField("right");
            return new EnumMember(
                    textTrimmed(nameNode),
                    valueNode == null ? null : mapExpression(valueNode),
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

        private @NotNull Statement mapSignalStatement(CstNodeView node) {
            var nameNode = requireField(node, "name");
            if (nameNode == null) {
                return AstFactory.errorStatement(CstIssueKind.MISSING, node, text(node));
            }
            var parametersNode = node.childByField("parameters");
            return new SignalStatement(
                    textTrimmed(nameNode),
                    mapParameters(parametersNode),
                    AstFactory.range(node.range())
            );
        }

        private @NotNull Statement mapVariableDeclaration(CstNodeView node, DeclarationKind kind) {
            var nameNode = requireField(node, "name");
            if (nameNode == null) {
                return AstFactory.errorStatement(CstIssueKind.MISSING, node, text(node));
            }
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
            var isStatic = !node.namedChildrenOfType("static_keyword").isEmpty();

            var name = nameNode == null ? "<anonymous>" : textTrimmed(nameNode);
            var body = mapBody(bodyNode, AstFactory.range(node.range()));
            return new FunctionDeclaration(
                    name,
                    mapParameters(parametersNode),
                    mapTypeRef(returnTypeNode),
                    isStatic,
                    body,
                    AstFactory.range(node.range())
            );
        }

        private @NotNull IfStatement mapIfStatement(CstNodeView node) {
            var bodyNode = requireField(node, "body");
            var elifClauses = new ArrayList<ElifClause>();
            Block elseBody = null;

            for (var child : significantNamedChildren(node)) {
                if (child.type().equals("elif_clause")) {
                    var elifBody = requireField(child, "body");
                    elifClauses.add(new ElifClause(
                            mapRequiredExpression(child, "condition"),
                            mapBody(elifBody, AstFactory.range(child.range())),
                            AstFactory.range(child.range())
                    ));
                } else if (child.type().equals("else_clause")) {
                    var elseBodyNode = requireField(child, "body");
                    elseBody = mapBody(elseBodyNode, AstFactory.range(child.range()));
                }
            }

            return new IfStatement(
                    mapRequiredExpression(node, "condition"),
                    mapBody(bodyNode, AstFactory.range(node.range())),
                    List.copyOf(elifClauses),
                    elseBody,
                    AstFactory.range(node.range())
            );
        }

        private @NotNull Statement mapForStatement(CstNodeView node) {
            var leftNode = requireField(node, "left");
            if (leftNode == null) {
                return AstFactory.errorStatement(CstIssueKind.MISSING, node, text(node));
            }
            var bodyNode = requireField(node, "body");
            var typeNode = node.childByField("type");

            return new ForStatement(
                    textTrimmed(leftNode),
                    mapTypeRef(typeNode),
                    mapRequiredExpression(node, "right"),
                    mapBody(bodyNode, AstFactory.range(node.range())),
                    AstFactory.range(node.range())
            );
        }

        private @NotNull WhileStatement mapWhileStatement(CstNodeView node) {
            var bodyNode = requireField(node, "body");
            return new WhileStatement(
                    mapRequiredExpression(node, "condition"),
                    mapBody(bodyNode, AstFactory.range(node.range())),
                    AstFactory.range(node.range())
            );
        }

        private @NotNull MatchStatement mapMatchStatement(CstNodeView node) {
            var bodyNode = requireField(node, "body");
            var sections = new ArrayList<MatchSection>();

            if (bodyNode != null) {
                for (var child : significantNamedChildren(bodyNode)) {
                    if (child.type().equals("pattern_section")) {
                        sections.add(mapMatchSection(child));
                    }
                }
            }

            return new MatchStatement(
                    mapRequiredExpression(node, "value"),
                    List.copyOf(sections),
                    AstFactory.range(node.range())
            );
        }

        private @NotNull MatchSection mapMatchSection(CstNodeView sectionNode) {
            var bodyNode = requireField(sectionNode, "body");
            Expression guard = null;
            var patterns = new ArrayList<Expression>();

            for (var child : significantNamedChildren(sectionNode)) {
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
                patterns.add(mapMatchPattern(child));
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

        private @NotNull RegionDirectiveStatement mapRegionDirectiveStatement(CstNodeView node) {
            var labelNode = firstNamedChild(node);
            return new RegionDirectiveStatement(
                    node.type().equals("region_start") ? "start" : "end",
                    labelNode == null ? null : textTrimmed(labelNode),
                    AstFactory.range(node.range())
            );
        }

        private @NotNull Statement mapExpressionStatement(CstNodeView node) {
            var legacyMessage = legacyExpressionStatementMessage(node);
            if (legacyMessage != null) {
                error(legacyMessage, node);
                return AstFactory.unknownStatement(node, text(node));
            }

            var expressionNode = firstNamedChild(node);
            if (expressionNode == null) {
                error("expression_statement missing expression", node);
                return new ExpressionStatement(AstFactory.unknownExpression(node, text(node)), AstFactory.range(node.range()));
            }
            if (isCallToIdentifier(expressionNode, "assert")) {
                return mapAssertStatement(expressionNode);
            }
            return new ExpressionStatement(mapExpression(expressionNode), AstFactory.range(node.range()));
        }

        private @NotNull AssertStatement mapAssertStatement(CstNodeView callNode) {
            var arguments = mapArgumentList(callNode.childByField("arguments"));
            if (arguments.isEmpty()) {
                error("assert missing condition", callNode);
                return new AssertStatement(AstFactory.unknownExpression(callNode, text(callNode)), null, AstFactory.range(callNode.range()));
            }
            var message = arguments.size() > 1 ? arguments.get(1) : null;
            return new AssertStatement(arguments.getFirst(), message, AstFactory.range(callNode.range()));
        }

        private @NotNull Block mapBody(@Nullable CstNodeView bodyNode, Range fallbackRange) {
            if (bodyNode == null) {
                return new Block(List.of(), fallbackRange);
            }
            return new Block(mapStatements(significantNamedChildren(bodyNode)), AstFactory.range(bodyNode.range()));
        }

        private @NotNull Expression mapExpression(CstNodeView node) {
            if (node.isError()) {
                return AstFactory.errorExpression(CstIssueKind.ERROR, node, text(node));
            }
            if (CstErrorDetector.isMissingPlaceholder(node)) {
                return AstFactory.errorExpression(CstIssueKind.MISSING, node, text(node));
            }

            var legacyMessage = legacyExpressionMessage(node);
            if (legacyMessage != null) {
                error(legacyMessage, node);
                return AstFactory.unknownExpression(node, text(node));
            }

            return switch (node.type()) {
                case "identifier", "name" -> mapIdentifierExpression(node);
                case "integer", "float", "string", "string_name", "true", "false", "null", "node_path" ->
                        new LiteralExpression(node.type(), text(node), AstFactory.range(node.range()));
                case "get_node" -> new GetNodeExpression(text(node), AstFactory.range(node.range()));
                case "call" -> mapCallExpression(node);
                case "attribute" -> mapAttributeExpression(node);
                case "subscript" -> mapSubscriptExpression(node);
                case "binary_operator" -> mapBinaryExpression(node);
                case "unary_operator" -> mapUnaryExpression(node);
                case "conditional_expression" -> mapConditionalExpression(node);
                case "assignment", "augmented_assignment" -> mapAssignmentExpression(node);
                case "array" -> mapArrayExpression(node, false);
                case "dictionary" -> mapDictionaryExpression(node, false);
                case "lambda" -> mapLambdaExpression(node);
                case "await_expression" -> mapAwaitExpression(node);
                case "pattern_binding" -> mapPatternBindingExpression(node);
                case "parenthesized_expression" -> mapParenthesizedExpression(node);
                default -> {
                    warn("Unsupported expression node: " + node.type(), node);
                    yield AstFactory.unknownExpression(node, text(node));
                }
            };
        }

        /// Maps a `match` section pattern node. Only array/dictionary patterns and pattern
        /// bindings participate in pattern-specific rules (such as key-only dictionary entries and
        /// `..` open-ending); keys, guards, and ordinary expressions continue through `mapExpression`.
        private @NotNull Expression mapMatchPattern(CstNodeView node) {
            if (node.isError()) {
                return AstFactory.errorExpression(CstIssueKind.ERROR, node, text(node));
            }
            if (CstErrorDetector.isMissingPlaceholder(node)) {
                return AstFactory.errorExpression(CstIssueKind.MISSING, node, text(node));
            }

            var legacyMessage = legacyExpressionMessage(node);
            if (legacyMessage != null) {
                error(legacyMessage, node);
                return AstFactory.unknownExpression(node, text(node));
            }

            return switch (node.type()) {
                case "array" -> mapArrayExpression(node, true);
                case "dictionary" -> mapDictionaryExpression(node, true);
                case "pattern_binding" -> mapPatternBindingExpression(node);
                default -> mapExpression(node);
            };
        }

        private @NotNull Expression mapIdentifierExpression(CstNodeView node) {
            return textTrimmed(node).equals("self")
                    ? new SelfExpression(AstFactory.range(node.range()))
                    : new IdentifierExpression(textTrimmed(node), AstFactory.range(node.range()));
        }

        private @NotNull Expression mapPatternBindingExpression(CstNodeView node) {
            var nameNode = firstNamedChild(node);
            if (nameNode == null) {
                error("pattern_binding missing identifier", node);
                return AstFactory.unknownExpression(node, text(node));
            }
            return new PatternBindingExpression(textTrimmed(nameNode), AstFactory.range(node.range()));
        }

        private @NotNull Expression mapAwaitExpression(CstNodeView node) {
            var valueNode = firstNamedChild(node);
            if (valueNode == null) {
                error("await_expression missing awaited value", node);
                return new AwaitExpression(AstFactory.unknownExpression(node, text(node)), AstFactory.range(node.range()));
            }
            return new AwaitExpression(mapExpression(valueNode), AstFactory.range(node.range()));
        }

        private @NotNull Expression mapParenthesizedExpression(CstNodeView node) {
            var inner = firstNamedChild(node);
            if (inner == null) {
                warn("parenthesized_expression has no inner expression", node);
                return AstFactory.unknownExpression(node, text(node));
            }
            return mapExpression(inner);
        }

        private @NotNull Expression mapCallExpression(CstNodeView node) {
            var argumentsNode = node.childByField("arguments");
            var calleeNode = firstNamedChildExcluding(node, argumentsNode);
            if (calleeNode == null) {
                error("call missing callee", node);
                return new CallExpression(AstFactory.unknownExpression(node, text(node)), List.of(), AstFactory.range(node.range()));
            }

            var arguments = mapArgumentList(argumentsNode);
            if (isIdentifierNode(calleeNode, "preload")) {
                if (arguments.isEmpty()) {
                    error("preload missing path argument", node);
                    return new PreloadExpression(AstFactory.unknownExpression(node, text(node)), AstFactory.range(node.range()));
                }
                return new PreloadExpression(arguments.getFirst(), AstFactory.range(node.range()));
            }

            return new CallExpression(
                    mapExpression(calleeNode),
                    arguments,
                    AstFactory.range(node.range())
            );
        }

        private @NotNull AttributeExpression mapAttributeExpression(CstNodeView node) {
            var namedChildren = significantNamedChildren(node);
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

        /// Maps one chain step. A zero-width placeholder produced by the grammar when the member
        /// name after `.` is absent (e.g. `receiver. + 1`) marks the missing final step
        /// explicitly instead of materializing a property step with an empty name.
        private @NotNull AttributeStep mapAttributeStep(CstNodeView node) {
            if (CstErrorDetector.isMissingPlaceholder(node)) {
                return new MissingAttributeStep(AstFactory.range(node.range()));
            }
            return switch (node.type()) {
                case "identifier", "name" -> new AttributePropertyStep(
                        textTrimmed(node),
                        AstFactory.range(node.range())
                );
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

        private @NotNull Expression mapBinaryExpression(CstNodeView node) {
            var leftNode = node.childByField("left");
            var rightNode = node.childByField("right");
            var left = leftNode == null ? mapRequiredExpression(node, "left") : mapExpression(leftNode);
            var right = rightNode == null ? mapRequiredExpression(node, "right") : mapExpression(rightNode);
            if (leftNode == null || rightNode == null) {
                return new BinaryExpression("<binary-op>", left, right, AstFactory.range(node.range()));
            }

            var operator = operatorBetween(leftNode, rightNode, "<binary-op>");
            return switch (operator) {
                case "as" -> new CastExpression(left, mapTypeRefFromNode(rightNode), AstFactory.range(node.range()));
                case "is" ->
                        new TypeTestExpression(left, mapTypeRefFromNode(rightNode), false, AstFactory.range(node.range()));
                case "is not" ->
                        new TypeTestExpression(left, mapTypeRefFromNode(rightNode), true, AstFactory.range(node.range()));
                default -> new BinaryExpression(
                        operator,
                        left,
                        right,
                        AstFactory.range(node.range())
                );
            };
        }

        private @NotNull UnaryExpression mapUnaryExpression(CstNodeView node) {
            var operandNode = firstNamedChild(node);
            if (operandNode == null) {
                error("unary_operator missing operand", node);
                return new UnaryExpression("<unary-op>", AstFactory.unknownExpression(node, text(node)), AstFactory.range(node.range()));
            }
            var operator = prefixOperator(node, operandNode, "<unary-op>");
            return new UnaryExpression(operator, mapExpression(operandNode), AstFactory.range(node.range()));
        }

        private @NotNull ConditionalExpression mapConditionalExpression(CstNodeView node) {
            return new ConditionalExpression(
                    mapRequiredExpression(node, "condition"),
                    mapRequiredExpression(node, "left"),
                    mapRequiredExpression(node, "right"),
                    AstFactory.range(node.range())
            );
        }

        private @NotNull AssignmentExpression mapAssignmentExpression(CstNodeView node) {
            var leftNode = node.childByField("left");
            var rightNode = node.childByField("right");
            var operator = leftNode == null || rightNode == null
                    ? (node.type().equals("assignment") ? "=" : "<aug-assignment-op>")
                    : operatorBetween(leftNode, rightNode, node.type().equals("assignment") ? "=" : "<aug-assignment-op>");
            return new AssignmentExpression(
                    operator,
                    leftNode == null ? mapRequiredExpression(node, "left") : mapExpression(leftNode),
                    rightNode == null ? mapRequiredExpression(node, "right") : mapExpression(rightNode),
                    AstFactory.range(node.range())
            );
        }

        private @NotNull ArrayExpression mapArrayExpression(CstNodeView node, boolean inMatchPattern) {
            var elements = new ArrayList<Expression>();
            var openEnded = false;
            for (var child : significantNamedChildren(node)) {
                if (isContainerTrivia(child)) {
                    continue;
                }
                if (child.type().equals("pattern_open_ending")) {
                    if (inMatchPattern) {
                        openEnded = true;
                    } else {
                        error("The \"..\" pattern is only allowed in match patterns.", child);
                    }
                } else {
                    elements.add(inMatchPattern ? mapMatchPattern(child) : mapExpression(child));
                }
            }
            return new ArrayExpression(List.copyOf(elements), openEnded, AstFactory.range(node.range()));
        }

        private @NotNull DictionaryExpression mapDictionaryExpression(CstNodeView node, boolean inMatchPattern) {
            var entries = new ArrayList<DictEntry>();
            var openEnded = false;
            String lockedStyle = null;
            var healthyKeyOnlyKeys = inMatchPattern ? collectHealthyKeyOnlyPatternKeys(node) : Set.<CstNodeView>of();
            for (var child : significantNamedChildren(node)) {
                if (isContainerTrivia(child)) {
                    continue;
                }
                if (child.type().equals("pair")) {
                    var leftNode = child.childByField("left");
                    var valueNode = child.childByField("value");
                    var style = dictionaryEntryStyle(leftNode, valueNode);
                    if (style != null) {
                        if (lockedStyle == null) {
                            lockedStyle = style;
                        } else if (!lockedStyle.equals(style)) {
                            error("Mixing dictionary styles is not allowed.", child);
                        }
                    }
                    entries.add(mapDictionaryEntry(child, style, inMatchPattern));
                } else if (child.type().equals("pattern_open_ending")) {
                    if (inMatchPattern) {
                        openEnded = true;
                    } else {
                        error("The \"..\" pattern is only allowed in match patterns.", child);
                    }
                } else if (inMatchPattern && healthyKeyOnlyKeys.contains(child)) {
                    // Key-only dictionary pattern entry without a `:` value slot.
                    entries.add(new DictEntry(mapExpression(child), null, AstFactory.range(child.range())));
                } else {
                    warn("Unsupported dictionary child node: " + child.type(), child);
                }
            }
            return new DictionaryExpression(List.copyOf(entries), openEnded, AstFactory.range(node.range()));
        }

        private @NotNull DictEntry mapDictionaryEntry(CstNodeView node, @Nullable String style, boolean inMatchPattern) {
            var leftNode = node.childByField("left");
            var valueNode = node.childByField("value");
            var resolvedStyle = style != null ? style : dictionaryEntryStyle(leftNode, valueNode);
            var key = leftNode == null
                    ? mapRequiredExpression(node, "left")
                    : "=".equals(resolvedStyle)
                    ? mapLuaStyleDictionaryKey(leftNode)
                    : mapExpression(leftNode);
            var value = valueNode == null
                    ? mapRequiredExpression(node, "value")
                    : inMatchPattern ? mapMatchPattern(valueNode) : mapExpression(valueNode);
            return new DictEntry(key, value, AstFactory.range(node.range()));
        }

        private @Nullable String dictionaryEntryStyle(@Nullable CstNodeView leftNode, @Nullable CstNodeView valueNode) {
            if (leftNode == null || valueNode == null) {
                return null;
            }
            var operator = operatorBetween(leftNode, valueNode, "");
            if ("=".equals(operator)) {
                return "=";
            }
            if (":".equals(operator)) {
                return ":";
            }
            return null;
        }

        private @NotNull Expression mapLuaStyleDictionaryKey(CstNodeView leftNode) {
            var range = AstFactory.range(leftNode.range());
            return switch (leftNode.type()) {
                case "identifier", "name" -> {
                    var name = textTrimmed(leftNode);
                    yield new LiteralExpression("string_name", "&\"" + name + "\"", range);
                }
                case "string" -> new LiteralExpression("string_name", "&" + text(leftNode), range);
                default -> {
                    error("Expected identifier or string as Lua-style dictionary key", leftNode);
                    yield mapExpression(leftNode);
                }
            };
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
            for (var child : significantNamedChildren(parametersNode)) {
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
                case "default_parameter" -> {
                    var valueNode = node.childByField("value");
                    yield new Parameter(
                            textTrimmed(firstNamedChild(node)),
                            null,
                            valueNode == null ? mapRequiredExpression(node, "value") : mapExpression(valueNode),
                            false,
                            AstFactory.range(node.range())
                    );
                }
                case "typed_default_parameter" -> {
                    var valueNode = node.childByField("value");
                    yield new Parameter(
                            textTrimmed(firstNamedChild(node)),
                            mapTypeRef(node.childByField("type")),
                            valueNode == null ? mapRequiredExpression(node, "value") : mapExpression(valueNode),
                            false,
                            AstFactory.range(node.range())
                    );
                }
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

        private @NotNull TypeRef mapTypeRefFromNode(CstNodeView node) {
            return new TypeRef(textTrimmed(node), AstFactory.range(node.range()));
        }

        private @NotNull List<Expression> mapArgumentList(@Nullable CstNodeView argumentsNode) {
            if (argumentsNode == null) {
                return List.of();
            }
            var arguments = new ArrayList<Expression>();
            for (var child : significantNamedChildren(argumentsNode)) {
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

        private boolean isCallToIdentifier(CstNodeView node, String name) {
            if (!node.type().equals("call")) {
                return false;
            }
            var argumentsNode = node.childByField("arguments");
            var calleeNode = firstNamedChildExcluding(node, argumentsNode);
            return calleeNode != null && isIdentifierNode(calleeNode, name);
        }

        private boolean isIdentifierNode(CstNodeView node, String name) {
            return (node.type().equals("identifier") || node.type().equals("name"))
                    && textTrimmed(node).equals(name);
        }

        /// Rejects legacy 3.x forms that the bundled tree-sitter grammar still tokenizes,
        /// so the lowered AST remains a faithful representation of 4.x source only.
        private @Nullable String legacyStatementMessage(CstNodeView node) {
            return switch (node.type()) {
                case "export_variable_statement" ->
                        "The `export` keyword syntax was removed in GDScript 4.x. Use `@export` and related annotations instead.";
                case "onready_variable_statement" ->
                        "The `onready var` syntax was removed in GDScript 4.x. Use `@onready` instead.";
                case "constructor_definition" -> node.childByField("arguments") == null
                        ? null
                        : "Implicit constructor base arguments such as `func _init(...).(...):` were removed in GDScript 4.x. Call `super(...)` inside the constructor body instead.";
                case "class_name_statement" -> node.childByField("icon_path") == null
                        ? null
                        : "Inline `class_name Name, \"res://icon.svg\"` syntax was removed in GDScript 4.x. Use `@icon(\"...\")` together with `class_name` instead.";
                case "variable_statement" -> legacyVariableStatementMessage(node);
                case "function_definition" -> hasLegacyRemoteKeyword(node)
                        ? "Legacy multiplayer keywords (`remote`, `master`, `puppet`, `*sync`) were removed in GDScript 4.x. Use `@rpc` instead."
                        : null;
                default -> null;
            };
        }

        private @Nullable String legacyVariableStatementMessage(CstNodeView node) {
            if (hasLegacyRemoteKeyword(node)) {
                return "Legacy multiplayer keywords (`remote`, `master`, `puppet`, `*sync`) were removed in GDScript 4.x. Use `@rpc` instead.";
            }
            if (usesLegacySetgetKeyword(node)) {
                return "The `setget` keyword was removed in GDScript 4.x. Use property getter/setter blocks instead.";
            }
            return null;
        }

        private @Nullable String legacyExpressionStatementMessage(CstNodeView node) {
            var expressionNode = firstNamedChild(node);
            if (expressionNode != null && isIdentifierNode(expressionNode, "tool") && textTrimmed(node).equals("tool")) {
                return "The `tool` keyword was removed in GDScript 4.x. Use `@tool` instead.";
            }
            return null;
        }

        private @Nullable String legacyExpressionMessage(CstNodeView node) {
            return switch (node.type()) {
                case "base_call" ->
                        "Implicit base calls such as `.foo()` were removed in GDScript 4.x. Use `super.foo()` instead.";
                case "call" -> {
                    var argumentsNode = node.childByField("arguments");
                    var calleeNode = firstNamedChildExcluding(node, argumentsNode);
                    yield calleeNode != null && isIdentifierNode(calleeNode, "yield")
                            ? "The `yield` keyword was removed in GDScript 4.x. Use `await` instead."
                            : null;
                }
                default -> null;
            };
        }

        private boolean hasLegacyRemoteKeyword(CstNodeView node) {
            return node.childByField("remote_keyword") != null || !node.namedChildrenOfType("remote_keyword").isEmpty();
        }

        private boolean usesLegacySetgetKeyword(CstNodeView node) {
            var setgetNode = node.childByField("setget");
            return setgetNode != null && textTrimmed(setgetNode).startsWith("setget");
        }

        private @Nullable CstNodeView findNamedChildByType(CstNodeView node, String nodeType) {
            for (var child : significantNamedChildren(node)) {
                if (child.type().equals(nodeType)) {
                    return child;
                }
            }
            return null;
        }

        /// Resolves a required field or reports an error and returns `null`. Callers degrade to
        /// an explicit `Error*` node or an empty slot; the parent node is never returned as a
        /// substitute, which previously fabricated inaccurate nodes and could even recurse
        /// indefinitely when the parent was mapped again as its own field.
        private @Nullable CstNodeView requireField(CstNodeView node, String fieldName) {
            var field = node.childByField(fieldName);
            if (field == null) {
                error("Missing required field '" + fieldName + "'", node);
            }
            return field;
        }

        /// Maps a required expression field. When the field is absent, emits an error diagnostic
        /// and returns a zero-width `ErrorExpression` placeholder anchored at the start of the
        /// owning construct, with the diagnostic carrying exactly the placeholder's range.
        private @NotNull Expression mapRequiredExpression(CstNodeView node, String fieldName) {
            var field = node.childByField(fieldName);
            if (field != null) {
                return mapExpression(field);
            }
            var placeholder = missingFieldExpression(node, fieldName);
            diagnostics.add(new AstDiagnostic(
                    AstDiagnosticSeverity.ERROR,
                    "Missing required field '" + fieldName + "'",
                    placeholder.nodeType(),
                    placeholder.range()
            ));
            return placeholder;
        }

        /// Placeholder expression for a required field that is absent from the CST, anchored as a
        /// zero-width span at the start of the owning construct.
        private @NotNull ErrorExpression missingFieldExpression(CstNodeView node, String fieldName) {
            var start = node.range();
            var zeroRange = AstFactory.range(new CstRange(
                    start.startByte(),
                    start.startByte(),
                    start.startPoint(),
                    start.startPoint()
            ));
            return new ErrorExpression(CstIssueKind.MISSING, node.type() + "." + fieldName, "", zeroRange);
        }

        /// Bounded recovery for a statement-level `ERROR` node whose tail is a dangling member
        /// dot, as produced by the grammar for unfinished input at end of file (e.g.
        /// `func f():\n\tnode.` wraps the whole function into one `ERROR`). Recognizes an
        /// optional function header, mappable body fragments, and an optional partial-statement
        /// prefix (`var x =`, `return`, ...), rebuilding them around a normal attribute chain
        /// terminated by a `MissingAttributeStep`. Returns null for any unrecognized shape so the
        /// caller falls back to a single `ErrorStatement`; recovery never invents structure
        /// beyond these anchored patterns.
        private @Nullable List<Statement> decomposeErrorStatement(CstNodeView errorNode) {
            var children = new ArrayList<CstNodeView>();
            for (var child : errorNode.children()) {
                if (!isLineContinuation(child)) {
                    children.add(child);
                }
            }
            if (children.size() < 2 || hasBlockingDescendant(errorNode)) {
                return null;
            }

            var dot = children.getLast();
            if (!dot.type().equals(".") || dot.range().endByte() != errorNode.range().endByte()) {
                return null;
            }

            var chainStart = chainStartBeforeDot(children);
            if (chainStart < 0) {
                return null;
            }
            var parts = new ArrayList<CstNodeView>();
            for (var index = chainStart; index < children.size() - 1; index += 2) {
                var part = children.get(index);
                if (index > chainStart && !isChainStep(part)) {
                    return null;
                }
                parts.add(part);
            }

            var tail = matchTail(children, chainStart);
            if (tail == null) {
                return null;
            }
            var header = matchFunctionHeader(children, tail.startIndex());
            int middleStart;
            if (header != null) {
                middleStart = header.endIndex();
            } else if (tail.startIndex() == 0) {
                middleStart = 0;
            } else {
                return null;
            }
            // A bare chain starts a new expression statement, which is only sound when every
            // fragment before it is a complete statement: any leftover operator, keyword, or
            // partial construct (e.g. `await node.`, `pass + node.`) means the chain belongs to
            // an unrecognized partial construct and the whole error must fall back instead of
            // inventing structure.
            if (tail.kind() == TailKind.NONE) {
                for (var index = middleStart; index < chainStart; index++) {
                    if (!isCompleteStatementFragment(children.get(index))) {
                        return null;
                    }
                }
            }

            var chain = buildPartialChain(parts, dot);
            var bodyStatements = new ArrayList<Statement>();
            mapFragments(children, middleStart, tail.startIndex(), bodyStatements);
            bodyStatements.add(buildTailStatement(children, tail, chain, dot));

            recoveredErrors.add(errorNode);
            var missingRange = zeroWidthRangeAtEnd(dot);
            diagnostics.add(new AstDiagnostic(AstDiagnosticSeverity.ERROR, "Missing identifier", "identifier", missingRange));

            if (header == null) {
                return List.copyOf(bodyStatements);
            }
            var body = new Block(
                    List.copyOf(bodyStatements),
                    new Range(
                            bodyStatements.getFirst().range().startByte(),
                            dot.range().endByte(),
                            bodyStatements.getFirst().range().startPoint(),
                            new Point(dot.range().endPoint().row(), dot.range().endPoint().column())
                    )
            );
            return List.of(new FunctionDeclaration(
                    textTrimmed(header.nameNode()),
                    mapParameters(header.parametersNode()),
                    mapTypeRef(header.returnTypeNode()),
                    header.isStatic(),
                    body,
                    AstFactory.range(errorNode.range())
            ));
        }

        /// The enclosing scope of a recovered chain: a `func` header prefix consumed from the
        /// front of the ERROR children.
        private @Nullable HeaderMatch matchFunctionHeader(List<CstNodeView> children, int limit) {
            var index = 0;
            var isStatic = false;
            if (index < limit && children.get(index).type().equals("static_keyword")) {
                isStatic = true;
                index++;
            }
            if (index >= limit || !children.get(index).type().equals("func")) {
                return null;
            }
            index++;
            if (index >= limit || !children.get(index).type().equals("name")) {
                return null;
            }
            var nameNode = children.get(index);
            index++;
            if (index >= limit || !children.get(index).type().equals("parameters")) {
                return null;
            }
            var parametersNode = children.get(index);
            index++;
            CstNodeView returnTypeNode = null;
            if (index < limit && children.get(index).type().equals("->")) {
                index++;
                if (index >= limit || !children.get(index).type().equals("type")) {
                    return null;
                }
                returnTypeNode = children.get(index);
                index++;
            }
            if (index >= limit || !children.get(index).type().equals(":")) {
                return null;
            }
            index++;
            return new HeaderMatch(index, nameNode, parametersNode, returnTypeNode, isStatic);
        }

        /// The partial-statement prefix immediately before the chain (`return`, a variable
        /// declaration, or an assignment), if any.
        private @Nullable TailMatch matchTail(List<CstNodeView> children, int chainStart) {
            var index = chainStart - 1;
            if (index < 0) {
                return new TailMatch(chainStart, TailKind.NONE, null, false);
            }
            return switch (children.get(index).type()) {
                case "return" -> new TailMatch(index, TailKind.RETURN, null, false);
                case "=", "inferred_type" -> matchAssignmentTail(children, index);
                default -> new TailMatch(chainStart, TailKind.NONE, null, false);
            };
        }

        private @Nullable TailMatch matchAssignmentTail(List<CstNodeView> children, int opIndex) {
            var isAssignment = children.get(opIndex).type().equals("=");
            // Typed declaration: [var|const, name, :, type, =]
            if (isAssignment && opIndex >= 4
                    && children.get(opIndex - 1).type().equals("type")
                    && children.get(opIndex - 2).type().equals(":")
                    && children.get(opIndex - 3).type().equals("name")
                    && isVariableKeyword(children.get(opIndex - 4))) {
                return withOptionalStatic(children, new TailMatch(opIndex - 4, TailKind.VARIABLE, List.of(children.get(opIndex - 1)), false));
            }
            // Plain or inferred declaration: [var|const, name, =|inferred_type]
            if (opIndex >= 2
                    && children.get(opIndex - 1).type().equals("name")
                    && isVariableKeyword(children.get(opIndex - 2))) {
                return withOptionalStatic(children, new TailMatch(opIndex - 2, TailKind.VARIABLE, null, false));
            }
            // Assignment: [expression ("." step)*, =] - the left side is reconstructed as a whole
            // chain so `x.y = node.` keeps `x.y` as the assignment target instead of just `y`.
            if (isAssignment && opIndex >= 1 && isChainLink(children.get(opIndex - 1))) {
                var leftStart = opIndex - 1;
                while (leftStart - 2 >= 0
                        && children.get(leftStart - 1).type().equals(".")
                        && isChainLink(children.get(leftStart - 2))) {
                    leftStart -= 2;
                }
                if (!isChainReceiver(children.get(leftStart))) {
                    return null;
                }
                var leftParts = new ArrayList<CstNodeView>();
                for (var index = leftStart; index < opIndex; index += 2) {
                    var part = children.get(index);
                    if (index > leftStart && !isChainStep(part)) {
                        return null;
                    }
                    leftParts.add(part);
                }
                return new TailMatch(leftStart, TailKind.ASSIGN, List.copyOf(leftParts), false);
            }
            return null;
        }

        private static TailMatch withOptionalStatic(List<CstNodeView> children, TailMatch match) {
            if (match.startIndex() >= 1 && children.get(match.startIndex() - 1).type().equals("static_keyword")) {
                return new TailMatch(match.startIndex() - 1, match.kind(), match.aux(), true);
            }
            return match;
        }

        private static boolean isVariableKeyword(CstNodeView node) {
            return node.type().equals("var") || node.type().equals("const");
        }

        private @NotNull Statement buildTailStatement(List<CstNodeView> children, TailMatch tail, Expression chain, CstNodeView dot) {
            return switch (tail.kind()) {
                case NONE -> new ExpressionStatement(chain, chain.range());
                case RETURN -> new ReturnStatement(chain, rangeFromTo(children.get(tail.startIndex()), dot));
                case ASSIGN -> {
                    var leftParts = tail.aux();
                    if (leftParts == null || leftParts.isEmpty()) {
                        yield new ExpressionStatement(chain, chain.range());
                    }
                    var left = buildChainExpression(leftParts);
                    var range = rangeFromTo(leftParts.getFirst(), dot);
                    yield new ExpressionStatement(new AssignmentExpression("=", left, chain, range), range);
                }
                case VARIABLE -> {
                    var keywordIndex = tail.isStatic() ? tail.startIndex() + 1 : tail.startIndex();
                    var keyword = children.get(keywordIndex);
                    var nameNode = children.get(keywordIndex + 1);
                    var isConst = keyword.type().equals("const");
                    var typeNodes = tail.aux();
                    yield new VariableDeclaration(
                            isConst ? DeclarationKind.CONST : DeclarationKind.VAR,
                            textTrimmed(nameNode),
                            typeNodes == null || typeNodes.isEmpty() ? null : mapTypeRef(typeNodes.getFirst()),
                            chain,
                            tail.isStatic(),
                            isConst ? "const_statement" : "variable_statement",
                            rangeFromTo(children.get(tail.startIndex()), dot)
                    );
                }
            };
        }

        /// Maps body fragments of a recovered function: complete named statements are lowered
        /// normally, anything else is grouped into one `ErrorStatement` per contiguous junk run
        /// with a matching error diagnostic over exactly that span.
        private void mapFragments(List<CstNodeView> children, int start, int end, List<Statement> sink) {
            var junkStart = -1;
            for (var index = start; index < end; index++) {
                var child = children.get(index);
                if (isCompleteStatementFragment(child)) {
                    if (junkStart >= 0) {
                        sink.add(junkStatement(children, junkStart, index));
                        junkStart = -1;
                    }
                    sink.addAll(mapStatementSequence(child));
                } else if (junkStart < 0) {
                    junkStart = index;
                }
            }
            if (junkStart >= 0) {
                sink.add(junkStatement(children, junkStart, end));
            }
        }

        private @NotNull ErrorStatement junkStatement(List<CstNodeView> children, int start, int end) {
            var first = children.get(start);
            var last = children.get(end - 1);
            var range = rangeFromTo(first, last);
            diagnostics.add(new AstDiagnostic(AstDiagnosticSeverity.ERROR, "CST structural issue: ERROR", "ERROR", range));
            return new ErrorStatement(CstIssueKind.ERROR, "ERROR", textBetween(first, last), range);
        }

        private static boolean isCompleteStatementFragment(CstNodeView node) {
            if (!node.isNamed() || node.hasError()) {
                return false;
            }
            return switch (node.type()) {
                case "class_name_statement", "extends_statement", "signal_statement",
                     "variable_statement", "const_statement", "function_definition",
                     "constructor_definition", "class_definition", "enum_definition",
                     "if_statement", "for_statement", "while_statement", "match_statement",
                     "return_statement", "break_statement", "continue_statement",
                     "breakpoint_statement", "region_start", "region_end", "comment",
                     "expression_statement", "pass_statement" -> true;
                default -> false;
            };
        }

        /// Start index of the contiguous `expr ("." expr)*` run ending right before the final
        /// dot, or -1 when the tail does not form a chain. Mid-chain links may be call/subscript
        /// steps (`a.foo().b.`), but the chain must start at a valid receiver expression.
        private static int chainStartBeforeDot(List<CstNodeView> children) {
            var end = children.size() - 2;
            if (end < 0 || !isChainReceiver(children.get(end))) {
                return -1;
            }
            var start = end;
            while (start - 2 >= 0
                    && children.get(start - 1).type().equals(".")
                    && isChainLink(children.get(start - 2))) {
                start -= 2;
            }
            return isChainReceiver(children.get(start)) ? start : -1;
        }

        private static boolean isChainLink(CstNodeView node) {
            return isChainReceiver(node) || isChainStep(node);
        }

        private static boolean isChainReceiver(CstNodeView node) {
            if (!node.isNamed() || node.hasError()) {
                return false;
            }
            return switch (node.type()) {
                case "identifier", "name", "attribute", "call", "subscript", "get_node",
                     "parenthesized_expression" -> true;
                default -> false;
            };
        }

        private static boolean isChainStep(CstNodeView node) {
            if (!node.isNamed() || node.hasError()) {
                return false;
            }
            return switch (node.type()) {
                case "identifier", "name", "attribute_call", "attribute_subscript" -> true;
                default -> false;
            };
        }

        /// Builds `base.step1.step2.` as a normal `AttributeExpression` terminated by a
        /// zero-width `MissingAttributeStep` at the end of the dangling dot.
        private @NotNull Expression buildPartialChain(List<CstNodeView> parts, CstNodeView dot) {
            var base = buildChainExpression(parts);
            var missingStep = new MissingAttributeStep(zeroWidthRangeAtEnd(dot));
            if (base instanceof AttributeExpression attribute) {
                var steps = new ArrayList<>(attribute.steps());
                steps.add(missingStep);
                return new AttributeExpression(attribute.base(), steps, rangeFromTo(parts.getFirst(), dot));
            }
            return new AttributeExpression(base, List.of(missingStep), rangeFromTo(parts.getFirst(), dot));
        }

        /// Builds a complete chain expression from flat chain parts, flattening an already
        /// reduced `attribute` base so chains never nest.
        private @NotNull Expression buildChainExpression(List<CstNodeView> parts) {
            var base = mapExpression(parts.getFirst());
            if (parts.size() == 1) {
                return base;
            }
            var baseExpression = base;
            var steps = new ArrayList<AttributeStep>();
            if (base instanceof AttributeExpression attribute) {
                baseExpression = attribute.base();
                steps.addAll(attribute.steps());
            }
            for (var index = 1; index < parts.size(); index++) {
                steps.add(mapAttributeStep(parts.get(index)));
            }
            return new AttributeExpression(baseExpression, steps, rangeFromTo(parts.getFirst(), parts.getLast()));
        }

        /// Fail-closed gate: nested `ERROR` nodes or annotation fragments cannot be partitioned
        /// reliably, so recovery declines them.
        private static boolean hasBlockingDescendant(CstNodeView node) {
            var stack = new ArrayDeque<CstNodeView>();
            for (var child : node.children()) {
                stack.push(child);
            }
            while (!stack.isEmpty()) {
                var current = stack.pop();
                if (current.isError()
                        || current.type().equals("annotation")
                        || current.type().equals("annotations")) {
                    return true;
                }
                for (var child : current.children()) {
                    stack.push(child);
                }
            }
            return false;
        }

        private static @NotNull Range rangeFromTo(CstNodeView first, CstNodeView last) {
            var start = first.range();
            var end = last.range();
            return AstFactory.range(new CstRange(start.startByte(), end.endByte(), start.startPoint(), end.endPoint()));
        }

        private static @NotNull Range zeroWidthRangeAtEnd(CstNodeView node) {
            var range = node.range();
            return AstFactory.range(new CstRange(range.endByte(), range.endByte(), range.endPoint(), range.endPoint()));
        }

        private @NotNull String textBetween(CstNodeView first, CstNodeView last) {
            var start = Math.max(0, first.range().startByte());
            var end = Math.min(sourceBytes.length, last.range().endByte());
            if (start >= end) {
                return "";
            }
            return new String(sourceBytes, start, end - start, StandardCharsets.UTF_8);
        }

        private enum TailKind {
            NONE,
            RETURN,
            VARIABLE,
            ASSIGN
        }

        private record TailMatch(int startIndex, TailKind kind, @Nullable List<CstNodeView> aux, boolean isStatic) {
        }

        private record HeaderMatch(
                int endIndex,
                CstNodeView nameNode,
                CstNodeView parametersNode,
                @Nullable CstNodeView returnTypeNode,
                boolean isStatic
        ) {
        }

        private @Nullable CstNodeView firstNamedChild(CstNodeView node) {
            var children = significantNamedChildren(node);
            return children.isEmpty() ? null : children.getFirst();
        }

        private @Nullable CstNodeView firstNamedChildExcluding(CstNodeView node, @Nullable CstNodeView excludedNode) {
            for (var child : significantNamedChildren(node)) {
                if (child != excludedNode) {
                    return child;
                }
            }
            return null;
        }

        private boolean isLineContinuation(CstNodeView node) {
            return node.type().equals(LINE_CONTINUATION_TYPE);
        }

        private boolean isContainerTrivia(CstNodeView node) {
            return switch (node.type()) {
                case "comment", "region_start", "region_end", LINE_CONTINUATION_TYPE -> true;
                default -> false;
            };
        }

        /// Performs a single pass over `dictionaryNode.children()` to collect every candidate key
        /// that sits alone (ignoring trivia) inside a healthy `{`/`,` ... `,`/`}` segment.
        private @NotNull Set<CstNodeView> collectHealthyKeyOnlyPatternKeys(CstNodeView dictionaryNode) {
            var healthyKeys = Collections.newSetFromMap(new IdentityHashMap<CstNodeView, Boolean>());
            CstNodeView candidate = null;
            var segmentValid = true;
            for (var child : dictionaryNode.children()) {
                var type = child.type();
                if (type.equals("{")) {
                    candidate = null;
                    segmentValid = true;
                    continue;
                }
                if (type.equals(",") || type.equals("}")) {
                    if (segmentValid && isEligibleKeyOnlyCandidate(candidate)) {
                        healthyKeys.add(candidate);
                    }
                    candidate = null;
                    segmentValid = true;
                    continue;
                }
                if (isContainerTrivia(child)) {
                    continue;
                }
                if (candidate == null) {
                    candidate = child;
                } else {
                    segmentValid = false;
                }
            }
            if (segmentValid && isEligibleKeyOnlyCandidate(candidate)) {
                healthyKeys.add(candidate);
            }
            return healthyKeys;
        }

        private static boolean isEligibleKeyOnlyCandidate(@Nullable CstNodeView candidate) {
            return candidate != null
                    && !candidate.isError()
                    && !candidate.hasError()
                    && !CstErrorDetector.isMissingPlaceholder(candidate)
                    && !candidate.type().equals("pair")
                    && !candidate.type().equals("pattern_open_ending");
        }

        /// Named children with `line_continuation` extras removed. Continuations carry no
        /// semantic meaning for the AST, so every child enumeration during lowering skips them.
        private @NotNull List<CstNodeView> significantNamedChildren(CstNodeView node) {
            var children = node.namedChildren();
            var filtered = new ArrayList<CstNodeView>(children.size());
            for (var child : children) {
                if (!isLineContinuation(child)) {
                    filtered.add(child);
                }
            }
            return filtered.size() == children.size() ? children : List.copyOf(filtered);
        }

        private @NotNull String operatorBetween(CstNodeView leftNode, CstNodeView rightNode, String fallback) {
            var start = Math.max(0, leftNode.range().endByte());
            var end = Math.min(sourceBytes.length, rightNode.range().startByte());
            if (start >= end) {
                return fallback;
            }
            return normalizeOperatorText(new String(sourceBytes, start, end - start, StandardCharsets.UTF_8), fallback);
        }

        private @NotNull String prefixOperator(CstNodeView node, CstNodeView operandNode, String fallback) {
            var start = Math.max(0, node.range().startByte());
            var end = Math.min(sourceBytes.length, operandNode.range().startByte());
            if (start >= end) {
                return fallback;
            }
            return normalizeOperatorText(new String(sourceBytes, start, end - start, StandardCharsets.UTF_8), fallback);
        }

        /// Recovers the operator from raw source text that may embed `\` line continuations,
        /// then collapses whitespace so multi-word operators such as `is not` stay comparable.
        private @NotNull String normalizeOperatorText(String rawText, String fallback) {
            var operator = LINE_CONTINUATION_PATTERN.matcher(rawText).replaceAll("").replaceAll("\\s+", " ").trim();
            return operator.isEmpty() ? fallback : operator;
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
