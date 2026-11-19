package dev.superice.gdparser.frontend.ast;

import java.util.Objects;

/// Walks AST nodes in pre-order depth-first order and dispatches to type-specific handlers.
public final class ASTWalker {

    private final ASTNodeHandler handler;

    public ASTWalker(ASTNodeHandler handler) {
        this.handler = Objects.requireNonNull(handler, "handler must not be null");
    }

    /// Traverses the subtree rooted at `node`.
    public void walk(Node node) {
        walkNode(Objects.requireNonNull(node, "node must not be null"));
    }

    private void walkNode(Node node) {
        var directive = Objects.requireNonNull(
                dispatch(node),
                () -> "handler must not return null for " + node.getClass().getSimpleName()
        );
        if (directive == FrontendASTTraversalDirective.SKIP_CHILDREN) {
            return;
        }

        for (var child : node.getChildren()) {
            walkNode(Objects.requireNonNull(
                    child,
                    () -> "getChildren() must not contain null for " + node.getClass().getSimpleName()
            ));
        }
    }

    private FrontendASTTraversalDirective dispatch(Node node) {
        return switch (node) {
            case SourceFile sourceFile -> handler.handleSourceFile(sourceFile);
            case AnnotationStatement annotationStatement -> handler.handleAnnotationStatement(annotationStatement);
            case AssertStatement assertStatement -> handler.handleAssertStatement(assertStatement);
            case Block block -> handler.handleBlock(block);
            case BreakStatement breakStatement -> handler.handleBreakStatement(breakStatement);
            case BreakpointStatement breakpointStatement -> handler.handleBreakpointStatement(breakpointStatement);
            case ClassDeclaration classDeclaration -> handler.handleClassDeclaration(classDeclaration);
            case ClassNameStatement classNameStatement -> handler.handleClassNameStatement(classNameStatement);
            case CommentStatement commentStatement -> handler.handleCommentStatement(commentStatement);
            case ConstructorDeclaration constructorDeclaration ->
                    handler.handleConstructorDeclaration(constructorDeclaration);
            case ContinueStatement continueStatement -> handler.handleContinueStatement(continueStatement);
            case ElifClause elifClause -> handler.handleElifClause(elifClause);
            case EnumDeclaration enumDeclaration -> handler.handleEnumDeclaration(enumDeclaration);
            case EnumMember enumMember -> handler.handleEnumMember(enumMember);
            case ExpressionStatement expressionStatement -> handler.handleExpressionStatement(expressionStatement);
            case ExtendsStatement extendsStatement -> handler.handleExtendsStatement(extendsStatement);
            case ForStatement forStatement -> handler.handleForStatement(forStatement);
            case FunctionDeclaration functionDeclaration -> handler.handleFunctionDeclaration(functionDeclaration);
            case IfStatement ifStatement -> handler.handleIfStatement(ifStatement);
            case MatchSection matchSection -> handler.handleMatchSection(matchSection);
            case MatchStatement matchStatement -> handler.handleMatchStatement(matchStatement);
            case PassStatement passStatement -> handler.handlePassStatement(passStatement);
            case ErrorStatement errorStatement -> handler.handleErrorStatement(errorStatement);
            case RegionDirectiveStatement regionDirectiveStatement ->
                    handler.handleRegionDirectiveStatement(regionDirectiveStatement);
            case ReturnStatement returnStatement -> handler.handleReturnStatement(returnStatement);
            case SignalStatement signalStatement -> handler.handleSignalStatement(signalStatement);
            case UnknownStatement unknownStatement -> handler.handleUnknownStatement(unknownStatement);
            case VariableDeclaration variableDeclaration -> handler.handleVariableDeclaration(variableDeclaration);
            case WhileStatement whileStatement -> handler.handleWhileStatement(whileStatement);
            case ArrayExpression arrayExpression -> handler.handleArrayExpression(arrayExpression);
            case AssignmentExpression assignmentExpression -> handler.handleAssignmentExpression(assignmentExpression);
            case AttributeExpression attributeExpression -> handler.handleAttributeExpression(attributeExpression);
            case AwaitExpression awaitExpression -> handler.handleAwaitExpression(awaitExpression);
            case BinaryExpression binaryExpression -> handler.handleBinaryExpression(binaryExpression);
            case CallExpression callExpression -> handler.handleCallExpression(callExpression);
            case CastExpression castExpression -> handler.handleCastExpression(castExpression);
            case ConditionalExpression conditionalExpression ->
                    handler.handleConditionalExpression(conditionalExpression);
            case DictionaryExpression dictionaryExpression -> handler.handleDictionaryExpression(dictionaryExpression);
            case DictEntry dictEntry -> handler.handleDictEntry(dictEntry);
            case GetNodeExpression getNodeExpression -> handler.handleGetNodeExpression(getNodeExpression);
            case IdentifierExpression identifierExpression -> handler.handleIdentifierExpression(identifierExpression);
            case LambdaExpression lambdaExpression -> handler.handleLambdaExpression(lambdaExpression);
            case LiteralExpression literalExpression -> handler.handleLiteralExpression(literalExpression);
            case Parameter parameter -> handler.handleParameter(parameter);
            case PatternBindingExpression patternBindingExpression ->
                    handler.handlePatternBindingExpression(patternBindingExpression);
            case PreloadExpression preloadExpression -> handler.handlePreloadExpression(preloadExpression);
            case SelfExpression selfExpression -> handler.handleSelfExpression(selfExpression);
            case SubscriptExpression subscriptExpression -> handler.handleSubscriptExpression(subscriptExpression);
            case TypeRef typeRef -> handler.handleTypeRef(typeRef);
            case TypeTestExpression typeTestExpression -> handler.handleTypeTestExpression(typeTestExpression);
            case UnaryExpression unaryExpression -> handler.handleUnaryExpression(unaryExpression);
            case RecoveredInfixExpression recoveredInfixExpression ->
                    handler.handleRecoveredInfixExpression(recoveredInfixExpression);
            case ErrorExpression errorExpression -> handler.handleErrorExpression(errorExpression);
            case UnknownExpression unknownExpression -> handler.handleUnknownExpression(unknownExpression);
            case AttributeCallStep attributeCallStep -> handler.handleAttributeCallStep(attributeCallStep);
            case AttributePropertyStep attributePropertyStep ->
                    handler.handleAttributePropertyStep(attributePropertyStep);
            case AttributeSubscriptStep attributeSubscriptStep ->
                    handler.handleAttributeSubscriptStep(attributeSubscriptStep);
            case MissingAttributeStep missingAttributeStep -> handler.handleMissingAttributeStep(missingAttributeStep);
            case UnknownAttributeStep unknownAttributeStep -> handler.handleUnknownAttributeStep(unknownAttributeStep);
        };
    }
}
