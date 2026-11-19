package dev.superice.gdparser.frontend.ast;

/// Callback interface used by ASTWalker during a pre-order depth-first traversal.
///
/// Override the `handleXxx` methods for node types you care about. Returning
/// `SKIP_CHILDREN` prunes only the current node's subtree.
public interface ASTNodeHandler {

    /// Fallback hook used by the default node-specific handlers.
    default FrontendASTTraversalDirective handleNode(Node node) {
        return FrontendASTTraversalDirective.CONTINUE;
    }

    default FrontendASTTraversalDirective handleSourceFile(SourceFile node) {
        return handleNode(node);
    }

    default FrontendASTTraversalDirective handleAnnotationStatement(AnnotationStatement node) {
        return handleNode(node);
    }

    default FrontendASTTraversalDirective handleAssertStatement(AssertStatement node) {
        return handleNode(node);
    }

    default FrontendASTTraversalDirective handleBlock(Block node) {
        return handleNode(node);
    }

    default FrontendASTTraversalDirective handleBreakStatement(BreakStatement node) {
        return handleNode(node);
    }

    default FrontendASTTraversalDirective handleBreakpointStatement(BreakpointStatement node) {
        return handleNode(node);
    }

    default FrontendASTTraversalDirective handleClassDeclaration(ClassDeclaration node) {
        return handleNode(node);
    }

    default FrontendASTTraversalDirective handleClassNameStatement(ClassNameStatement node) {
        return handleNode(node);
    }

    default FrontendASTTraversalDirective handleCommentStatement(CommentStatement node) {
        return handleNode(node);
    }

    default FrontendASTTraversalDirective handleConstructorDeclaration(ConstructorDeclaration node) {
        return handleNode(node);
    }

    default FrontendASTTraversalDirective handleContinueStatement(ContinueStatement node) {
        return handleNode(node);
    }

    default FrontendASTTraversalDirective handleElifClause(ElifClause node) {
        return handleNode(node);
    }

    default FrontendASTTraversalDirective handleEnumDeclaration(EnumDeclaration node) {
        return handleNode(node);
    }

    default FrontendASTTraversalDirective handleEnumMember(EnumMember node) {
        return handleNode(node);
    }

    default FrontendASTTraversalDirective handleExpressionStatement(ExpressionStatement node) {
        return handleNode(node);
    }

    default FrontendASTTraversalDirective handleExtendsStatement(ExtendsStatement node) {
        return handleNode(node);
    }

    default FrontendASTTraversalDirective handleForStatement(ForStatement node) {
        return handleNode(node);
    }

    default FrontendASTTraversalDirective handleFunctionDeclaration(FunctionDeclaration node) {
        return handleNode(node);
    }

    default FrontendASTTraversalDirective handleIfStatement(IfStatement node) {
        return handleNode(node);
    }

    default FrontendASTTraversalDirective handleMatchSection(MatchSection node) {
        return handleNode(node);
    }

    default FrontendASTTraversalDirective handleMatchStatement(MatchStatement node) {
        return handleNode(node);
    }

    default FrontendASTTraversalDirective handlePassStatement(PassStatement node) {
        return handleNode(node);
    }

    default FrontendASTTraversalDirective handleErrorStatement(ErrorStatement node) {
        return handleNode(node);
    }

    default FrontendASTTraversalDirective handleRegionDirectiveStatement(RegionDirectiveStatement node) {
        return handleNode(node);
    }

    default FrontendASTTraversalDirective handleReturnStatement(ReturnStatement node) {
        return handleNode(node);
    }

    default FrontendASTTraversalDirective handleSignalStatement(SignalStatement node) {
        return handleNode(node);
    }

    default FrontendASTTraversalDirective handleUnknownStatement(UnknownStatement node) {
        return handleNode(node);
    }

    default FrontendASTTraversalDirective handleVariableDeclaration(VariableDeclaration node) {
        return handleNode(node);
    }

    default FrontendASTTraversalDirective handleWhileStatement(WhileStatement node) {
        return handleNode(node);
    }

    default FrontendASTTraversalDirective handleArrayExpression(ArrayExpression node) {
        return handleNode(node);
    }

    default FrontendASTTraversalDirective handleAssignmentExpression(AssignmentExpression node) {
        return handleNode(node);
    }

    default FrontendASTTraversalDirective handleAttributeExpression(AttributeExpression node) {
        return handleNode(node);
    }

    default FrontendASTTraversalDirective handleAwaitExpression(AwaitExpression node) {
        return handleNode(node);
    }

    default FrontendASTTraversalDirective handleBinaryExpression(BinaryExpression node) {
        return handleNode(node);
    }

    default FrontendASTTraversalDirective handleCallExpression(CallExpression node) {
        return handleNode(node);
    }

    default FrontendASTTraversalDirective handleCastExpression(CastExpression node) {
        return handleNode(node);
    }

    default FrontendASTTraversalDirective handleConditionalExpression(ConditionalExpression node) {
        return handleNode(node);
    }

    default FrontendASTTraversalDirective handleDictionaryExpression(DictionaryExpression node) {
        return handleNode(node);
    }

    default FrontendASTTraversalDirective handleDictEntry(DictEntry node) {
        return handleNode(node);
    }

    default FrontendASTTraversalDirective handleGetNodeExpression(GetNodeExpression node) {
        return handleNode(node);
    }

    default FrontendASTTraversalDirective handleIdentifierExpression(IdentifierExpression node) {
        return handleNode(node);
    }

    default FrontendASTTraversalDirective handleLambdaExpression(LambdaExpression node) {
        return handleNode(node);
    }

    default FrontendASTTraversalDirective handleLiteralExpression(LiteralExpression node) {
        return handleNode(node);
    }

    default FrontendASTTraversalDirective handlePatternBindingExpression(PatternBindingExpression node) {
        return handleNode(node);
    }

    default FrontendASTTraversalDirective handlePreloadExpression(PreloadExpression node) {
        return handleNode(node);
    }

    default FrontendASTTraversalDirective handleSelfExpression(SelfExpression node) {
        return handleNode(node);
    }

    default FrontendASTTraversalDirective handleSubscriptExpression(SubscriptExpression node) {
        return handleNode(node);
    }

    default FrontendASTTraversalDirective handleTypeTestExpression(TypeTestExpression node) {
        return handleNode(node);
    }

    default FrontendASTTraversalDirective handleUnaryExpression(UnaryExpression node) {
        return handleNode(node);
    }

    default FrontendASTTraversalDirective handleRecoveredInfixExpression(RecoveredInfixExpression node) {
        return handleNode(node);
    }

    default FrontendASTTraversalDirective handleUnknownExpression(UnknownExpression node) {
        return handleNode(node);
    }

    default FrontendASTTraversalDirective handleErrorExpression(ErrorExpression node) {
        return handleNode(node);
    }

    default FrontendASTTraversalDirective handleAttributeCallStep(AttributeCallStep node) {
        return handleNode(node);
    }

    default FrontendASTTraversalDirective handleAttributePropertyStep(AttributePropertyStep node) {
        return handleNode(node);
    }

    default FrontendASTTraversalDirective handleAttributeSubscriptStep(AttributeSubscriptStep node) {
        return handleNode(node);
    }

    default FrontendASTTraversalDirective handleUnknownAttributeStep(UnknownAttributeStep node) {
        return handleNode(node);
    }

    default FrontendASTTraversalDirective handleMissingAttributeStep(MissingAttributeStep node) {
        return handleNode(node);
    }

    default FrontendASTTraversalDirective handleParameter(Parameter node) {
        return handleNode(node);
    }

    default FrontendASTTraversalDirective handleTypeRef(TypeRef node) {
        return handleNode(node);
    }
}
