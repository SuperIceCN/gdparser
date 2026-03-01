package dev.superice.gdparser.frontend.serialize;

import dev.superice.gdparser.frontend.ast.ArrayExpression;
import dev.superice.gdparser.frontend.ast.AssignmentExpression;
import dev.superice.gdparser.frontend.ast.AttributeCallStep;
import dev.superice.gdparser.frontend.ast.AttributeExpression;
import dev.superice.gdparser.frontend.ast.AttributePropertyStep;
import dev.superice.gdparser.frontend.ast.AttributeSubscriptStep;
import dev.superice.gdparser.frontend.ast.BinaryExpression;
import dev.superice.gdparser.frontend.ast.Block;
import dev.superice.gdparser.frontend.ast.CallExpression;
import dev.superice.gdparser.frontend.ast.ClassNameStatement;
import dev.superice.gdparser.frontend.ast.ConditionalExpression;
import dev.superice.gdparser.frontend.ast.DictEntry;
import dev.superice.gdparser.frontend.ast.DictionaryExpression;
import dev.superice.gdparser.frontend.ast.ElifClause;
import dev.superice.gdparser.frontend.ast.ExpressionStatement;
import dev.superice.gdparser.frontend.ast.ExtendsStatement;
import dev.superice.gdparser.frontend.ast.ForStatement;
import dev.superice.gdparser.frontend.ast.FunctionDeclaration;
import dev.superice.gdparser.frontend.ast.IdentifierExpression;
import dev.superice.gdparser.frontend.ast.IfStatement;
import dev.superice.gdparser.frontend.ast.LambdaExpression;
import dev.superice.gdparser.frontend.ast.LiteralExpression;
import dev.superice.gdparser.frontend.ast.MatchSection;
import dev.superice.gdparser.frontend.ast.MatchStatement;
import dev.superice.gdparser.frontend.ast.Parameter;
import dev.superice.gdparser.frontend.ast.PassStatement;
import dev.superice.gdparser.frontend.ast.Point;
import dev.superice.gdparser.frontend.ast.Range;
import dev.superice.gdparser.frontend.ast.ReturnStatement;
import dev.superice.gdparser.frontend.ast.SignalStatement;
import dev.superice.gdparser.frontend.ast.SourceFile;
import dev.superice.gdparser.frontend.ast.SubscriptExpression;
import dev.superice.gdparser.frontend.ast.TypeRef;
import dev.superice.gdparser.frontend.ast.UnaryExpression;
import dev.superice.gdparser.frontend.ast.UnknownAttributeStep;
import dev.superice.gdparser.frontend.ast.UnknownExpression;
import dev.superice.gdparser.frontend.ast.UnknownStatement;
import dev.superice.gdparser.frontend.ast.VariableDeclaration;
import dev.superice.gdparser.frontend.ast.WhileStatement;
import org.jetbrains.annotations.NotNull;

import java.lang.reflect.Constructor;
import java.lang.reflect.RecordComponent;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/// Shared tag/type metadata used by AST S-expression serializer/deserializer.
final class AstSexprSchema {

    private static final Map<Class<?>, RecordMeta> METADATA_BY_TYPE = new HashMap<>();
    private static final Map<String, RecordMeta> METADATA_BY_TAG = new HashMap<>();

    static {
        register(SourceFile.class);
        register(Point.class);
        register(Range.class);
        register(Block.class);
        register(ClassNameStatement.class);
        register(ExtendsStatement.class);
        register(SignalStatement.class);
        register(VariableDeclaration.class);
        register(FunctionDeclaration.class);
        register(ElifClause.class);
        register(IfStatement.class);
        register(ForStatement.class);
        register(WhileStatement.class);
        register(MatchSection.class);
        register(MatchStatement.class);
        register(ReturnStatement.class);
        register(ExpressionStatement.class);
        register(PassStatement.class);
        register(UnknownStatement.class);
        register(TypeRef.class);
        register(Parameter.class);
        register(IdentifierExpression.class);
        register(LiteralExpression.class);
        register(CallExpression.class);
        register(AttributePropertyStep.class);
        register(AttributeCallStep.class);
        register(AttributeSubscriptStep.class);
        register(UnknownAttributeStep.class);
        register(AttributeExpression.class);
        register(SubscriptExpression.class);
        register(BinaryExpression.class);
        register(UnaryExpression.class);
        register(ConditionalExpression.class);
        register(AssignmentExpression.class);
        register(ArrayExpression.class);
        register(DictEntry.class);
        register(DictionaryExpression.class);
        register(LambdaExpression.class);
        register(UnknownExpression.class);
    }

    private AstSexprSchema() {
    }

    static boolean hasType(Class<?> type) {
        return METADATA_BY_TYPE.containsKey(type);
    }

    static @NotNull RecordMeta metadataForType(Class<?> type) {
        var meta = METADATA_BY_TYPE.get(type);
        if (meta == null) {
            throw new IllegalArgumentException("Unsupported AST record type: " + type.getName());
        }
        return meta;
    }

    static @NotNull RecordMeta metadataForTag(String tag) {
        var meta = METADATA_BY_TAG.get(tag);
        if (meta == null) {
            throw new IllegalArgumentException("Unknown AST tag: " + tag);
        }
        return meta;
    }

    private static void register(Class<?> type) {
        if (!type.isRecord()) {
            throw new IllegalArgumentException("Type is not a record: " + type.getName());
        }

        var components = type.getRecordComponents();
        var componentTypes = new Class<?>[components.length];
        var byName = new LinkedHashMap<String, RecordComponent>();
        for (var index = 0; index < components.length; index++) {
            var component = components[index];
            componentTypes[index] = component.getType();
            byName.put(component.getName(), component);
        }

        var constructor = canonicalConstructor(type, componentTypes);
        var tag = toTag(type.getSimpleName());
        var meta = new RecordMeta(type, tag, List.of(components), Map.copyOf(byName), constructor);

        if (METADATA_BY_TYPE.put(type, meta) != null) {
            throw new IllegalStateException("Duplicate AST type registration: " + type.getName());
        }
        if (METADATA_BY_TAG.put(tag, meta) != null) {
            throw new IllegalStateException("Duplicate AST tag registration: " + tag);
        }
    }

    private static Constructor<?> canonicalConstructor(Class<?> type, Class<?>[] componentTypes) {
        try {
            var constructor = type.getDeclaredConstructor(componentTypes);
            constructor.setAccessible(true);
            return constructor;
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Cannot resolve canonical constructor for " + type.getName(), exception);
        }
    }

    private static @NotNull String toTag(String name) {
        var builder = new StringBuilder(name.length() + 8);
        for (var index = 0; index < name.length(); index++) {
            var ch = name.charAt(index);
            if (Character.isUpperCase(ch)) {
                if (index > 0) {
                    builder.append('-');
                }
                builder.append(Character.toLowerCase(ch));
            } else {
                builder.append(ch);
            }
        }
        return builder.toString().toLowerCase(Locale.ROOT);
    }

    record RecordMeta(
            Class<?> type,
            String tag,
            List<RecordComponent> components,
            Map<String, RecordComponent> componentsByName,
            Constructor<?> canonicalConstructor
    ) {
    }
}
