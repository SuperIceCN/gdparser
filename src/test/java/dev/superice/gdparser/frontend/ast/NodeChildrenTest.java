package dev.superice.gdparser.frontend.ast;

import dev.superice.gdparser.frontend.cst.CstIssueKind;
import org.junit.jupiter.api.Test;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Locks down the `Node#getChildren()` contract for every concrete AST node type.
class NodeChildrenTest {

    private static final Range RANGE = new Range(0, 1, new Point(0, 0), new Point(0, 1));

    @Test
    void everyConcreteNodeShouldExposeDirectChildrenInDeclarationOrder() {
        for (var nodeType : concreteNodeTypes()) {
            var node = instantiate(nodeType);
            assertEquals(expectedDirectChildren(node), node.getChildren(), () -> "Unexpected children for " + nodeType.getSimpleName());
        }
    }

    @Test
    void getChildrenShouldOmitNullOptionalsAndScalarMetadata() {
        var variable = new VariableDeclaration(
                DeclarationKind.VAR,
                "value",
                null,
                null,
                false,
                "variable_statement",
                RANGE
        );
        var className = new ClassNameStatement("Demo", "Node", RANGE);
        var ifStatement = new IfStatement(
                new IdentifierExpression("ready", RANGE),
                new Block(List.of(new PassStatement(RANGE)), RANGE),
                List.of(),
                null,
                RANGE
        );

        assertTrue(variable.getChildren().isEmpty());
        assertTrue(className.getChildren().isEmpty());
        assertEquals(
                List.of(ifStatement.condition(), ifStatement.body()),
                ifStatement.getChildren()
        );
    }

    @Test
    void getChildrenShouldReturnImmutableLists() {
        var sourceFile = new SourceFile(List.of(new PassStatement(RANGE)), RANGE);

        assertThrows(
                UnsupportedOperationException.class,
                () -> sourceFile.getChildren().add(new PassStatement(RANGE))
        );
    }

    @Test
    void dictEntryAndRecoveredInfixExpressionChildrenAndNullGuards() {
        var key = new LiteralExpression("string", "\"k\"", RANGE);
        var value = new IdentifierExpression("v", RANGE);
        var keyOnlyEntry = new DictEntry(key, null, RANGE);
        var pairedEntry = new DictEntry(key, value, RANGE);

        assertEquals(List.of(key), keyOnlyEntry.getChildren());
        assertEquals(List.of(key, value), pairedEntry.getChildren());
        assertThrows(UnsupportedOperationException.class, () -> keyOnlyEntry.getChildren().add(value));
        assertThrows(NullPointerException.class, () -> new DictEntry(null, value, RANGE));
        assertThrows(NullPointerException.class, () -> new DictEntry(key, null, null));

        var problem = new ErrorExpression(CstIssueKind.MISSING, "cast.type", "", RANGE);
        var recovered = new RecoveredInfixExpression(key, "as", problem, RANGE);
        assertEquals(List.of(key, problem), recovered.getChildren());
        assertThrows(UnsupportedOperationException.class, () -> recovered.getChildren().add(value));
        assertThrows(NullPointerException.class, () -> new RecoveredInfixExpression(null, "as", problem, RANGE));
        assertThrows(NullPointerException.class, () -> new RecoveredInfixExpression(key, null, problem, RANGE));
        assertThrows(NullPointerException.class, () -> new RecoveredInfixExpression(key, "as", null, RANGE));
        assertThrows(NullPointerException.class, () -> new RecoveredInfixExpression(key, "as", problem, null));
    }

    private static List<Class<? extends Node>> concreteNodeTypes() {
        var concreteTypes = new ArrayList<Class<? extends Node>>();
        collectConcreteNodeTypes(Node.class, concreteTypes);
        return concreteTypes.stream()
                .sorted(Comparator.comparing(Class::getSimpleName))
                .toList();
    }

    @SuppressWarnings("unchecked")
    private static void collectConcreteNodeTypes(Class<? extends Node> type, List<Class<? extends Node>> concreteTypes) {
        for (var permitted : type.getPermittedSubclasses()) {
            var nodeType = (Class<? extends Node>) permitted;
            if (nodeType.isInterface()) {
                collectConcreteNodeTypes(nodeType, concreteTypes);
                continue;
            }
            concreteTypes.add(nodeType);
        }
    }

    private static Node instantiate(Class<? extends Node> nodeType) {
        if (!nodeType.isRecord()) {
            throw new IllegalArgumentException("Expected record node type but got " + nodeType.getName());
        }

        var components = nodeType.getRecordComponents();
        var parameterTypes = new Class<?>[components.length];
        var arguments = new Object[components.length];
        for (var index = 0; index < components.length; index++) {
            var component = components[index];
            parameterTypes[index] = component.getType();
            arguments[index] = sampleValue(component.getGenericType(), component.getType());
        }

        try {
            var constructor = nodeType.getDeclaredConstructor(parameterTypes);
            constructor.setAccessible(true);
            return constructor.newInstance(arguments);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Cannot instantiate " + nodeType.getName(), exception);
        }
    }

    private static Object sampleValue(Type genericType, Class<?> rawType) {
        if (rawType == String.class) {
            return "value";
        }
        if (rawType == boolean.class || rawType == Boolean.class) {
            return false;
        }
        if (rawType == int.class || rawType == Integer.class) {
            return 1;
        }
        if (rawType == Range.class) {
            return RANGE;
        }
        if (rawType.isEnum()) {
            return rawType.getEnumConstants()[0];
        }
        if (Node.class.isAssignableFrom(rawType)) {
            return sampleNodeType(rawType.asSubclass(Node.class));
        }
        if (rawType == List.class && genericType instanceof ParameterizedType parameterizedType) {
            var elementType = parameterizedType.getActualTypeArguments()[0];
            return List.of(sampleListElement(elementType));
        }

        throw new IllegalArgumentException("Unsupported component type: " + rawType.getName());
    }

    private static Node sampleNodeType(Class<? extends Node> nodeType) {
        if (!nodeType.isInterface()) {
            return instantiate(nodeType);
        }
        return switch (nodeType.getSimpleName()) {
            case "Statement" -> new PassStatement(RANGE);
            case "Expression" -> new IdentifierExpression("value", RANGE);
            case "AttributeStep" -> new AttributePropertyStep("prop", RANGE);
            default -> throw new IllegalArgumentException("Unsupported abstract node type: " + nodeType.getName());
        };
    }

    private static Object sampleListElement(Type elementType) {
        if (elementType instanceof Class<?> elementClass) {
            return sampleValue(elementType, elementClass);
        }
        throw new IllegalArgumentException("Unsupported list element type: " + elementType.getTypeName());
    }

    private static List<Node> expectedDirectChildren(Node node) {
        var children = new ArrayList<Node>();
        for (var component : node.getClass().getRecordComponents()) {
            appendDirectChildren(children, readComponent(component, node));
        }
        return List.copyOf(children);
    }

    private static void appendDirectChildren(List<Node> children, Object value) {
        if (value instanceof Node child) {
            children.add(child);
            return;
        }
        if (value instanceof List<?> list) {
            for (var element : list) {
                if (element instanceof Node child) {
                    children.add(child);
                }
            }
        }
    }

    private static Object readComponent(RecordComponent component, Node node) {
        try {
            return component.getAccessor().invoke(node);
        } catch (RuntimeException exception) {
            throw exception;
        } catch (Throwable throwable) {
            throw new IllegalStateException(
                    "Cannot access record component '%s' on %s".formatted(component.getName(), node.getClass().getName()),
                    throwable
            );
        }
    }
}
