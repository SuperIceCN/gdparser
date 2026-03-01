package dev.superice.gdparser.frontend.serialize;

import dev.superice.gdparser.frontend.ast.SourceFile;
import org.jetbrains.annotations.NotNull;

import java.lang.reflect.RecordComponent;
import java.util.List;
import java.util.Objects;

/// Serializes AST records to canonical S-expression text.
public final class AstSexprSerializer {

    public @NotNull String serialize(SourceFile sourceFile) {
        Objects.requireNonNull(sourceFile, "sourceFile must not be null");

        var builder = new StringBuilder(4096);
        writeValue(sourceFile, builder);
        return builder.toString();
    }

    private void writeValue(Object value, StringBuilder builder) {
        switch (value) {
            case null -> builder.append("nil");
            case String text -> writeString(text, builder);
            case Integer number -> builder.append(number);
            case Boolean bool -> builder.append(bool);
            case Enum<?> enumValue -> builder.append(enumValue.name());
            case List<?> list -> writeList(list, builder);
            default -> {
                var type = value.getClass();
                if (!AstSexprSchema.hasType(type)) {
                    throw new IllegalArgumentException("Unsupported AST value type: " + type.getName());
                }
                writeRecord(value, builder);
            }
        }
    }

    private void writeList(List<?> list, StringBuilder builder) {
        builder.append("(list");
        for (var element : list) {
            builder.append(' ');
            writeValue(element, builder);
        }
        builder.append(')');
    }

    private void writeRecord(Object value, StringBuilder builder) {
        var meta = AstSexprSchema.metadataForType(value.getClass());
        builder.append('(').append(meta.tag());

        for (var component : meta.components()) {
            builder.append(" (").append(component.getName()).append(' ');
            writeValue(readComponentValue(component, value), builder);
            builder.append(')');
        }

        builder.append(')');
    }

    private static Object readComponentValue(RecordComponent component, Object instance) {
        try {
            return component.getAccessor().invoke(instance);
        } catch (RuntimeException exception) {
            throw exception;
        } catch (Throwable throwable) {
            throw new IllegalStateException(
                    "Cannot access record component '%s' on %s".formatted(component.getName(), instance.getClass().getName()),
                    throwable
            );
        }
    }

    private static void writeString(String text, StringBuilder builder) {
        builder.append('"');
        for (var index = 0; index < text.length(); index++) {
            var ch = text.charAt(index);
            switch (ch) {
                case '\\' -> builder.append("\\\\");
                case '"' -> builder.append("\\\"");
                case '\n' -> builder.append("\\n");
                case '\r' -> builder.append("\\r");
                case '\t' -> builder.append("\\t");
                default -> builder.append(ch);
            }
        }
        builder.append('"');
    }
}
