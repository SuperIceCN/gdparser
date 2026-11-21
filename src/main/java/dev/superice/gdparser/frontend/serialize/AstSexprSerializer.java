package dev.superice.gdparser.frontend.serialize;

import dev.superice.gdparser.frontend.ast.SourceFile;
import dev.superice.gdparser.frontend.ast.VariableDeclaration;
import org.jetbrains.annotations.NotNull;

import java.lang.reflect.RecordComponent;
import java.util.List;
import java.util.Objects;

/// Serializes AST records to S-expression text.
public final class AstSexprSerializer {

    private static final String INDENT = "  ";

    /// Controls how pretty S-expressions are rendered.
    public enum PrettyMode {
        FULL,
        OMIT_RANGE
    }

    /// Serializes an AST into a compact canonical S-expression.
    public @NotNull String serialize(SourceFile sourceFile) {
        Objects.requireNonNull(sourceFile, "sourceFile must not be null");

        var builder = new StringBuilder(4096);
        writeValue(sourceFile, builder);
        return builder.toString();
    }

    /// Serializes an AST into a human-readable indented S-expression.
    public @NotNull String serializePretty(SourceFile sourceFile) {
        return serializePretty(sourceFile, PrettyMode.FULL);
    }

    /// Serializes an AST into a human-readable indented S-expression using the given mode.
    public @NotNull String serializePretty(SourceFile sourceFile, PrettyMode prettyMode) {
        Objects.requireNonNull(sourceFile, "sourceFile must not be null");
        Objects.requireNonNull(prettyMode, "prettyMode must not be null");

        var builder = new StringBuilder(4096);
        writePrettyValue(sourceFile, builder, 0, prettyMode);
        return builder.toString();
    }

    private void writeValue(Object value, StringBuilder builder) {
        if (writeScalarValue(value, builder)) {
            return;
        }

        if (Objects.requireNonNull(value) instanceof List<?> list) {
            writeList(list, builder);
        } else {
            var type = value.getClass();
            if (!AstSexprSchema.hasType(type)) {
                throw new IllegalArgumentException("Unsupported AST value type: " + type.getName());
            }
            writeRecord(value, builder);
        }
    }

    private void writePrettyValue(Object value, StringBuilder builder, int indentLevel, PrettyMode prettyMode) {
        if (writeScalarValue(value, builder)) {
            return;
        }

        if (Objects.requireNonNull(value) instanceof List<?> list) {
            writePrettyList(list, builder, indentLevel, prettyMode);
        } else {
            var type = value.getClass();
            if (!AstSexprSchema.hasType(type)) {
                throw new IllegalArgumentException("Unsupported AST value type: " + type.getName());
            }
            writePrettyRecord(value, builder, indentLevel, prettyMode);
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

    private void writePrettyList(List<?> list, StringBuilder builder, int indentLevel, PrettyMode prettyMode) {
        builder.append("(list");
        if (list.isEmpty()) {
            builder.append(')');
            return;
        }

        for (var element : list) {
            builder.append('\n');
            appendIndent(builder, indentLevel + 1);
            writePrettyValue(element, builder, indentLevel + 1, prettyMode);
        }

        builder.append('\n');
        appendIndent(builder, indentLevel);
        builder.append(')');
    }

    private void writeRecord(Object value, StringBuilder builder) {
        var meta = AstSexprSchema.metadataForType(value.getClass());
        builder.append('(').append(meta.tag());

        for (var component : meta.components()) {
            var componentValue = readComponentValue(component, value);
            if (shouldOmitEmptyRecoveryAnnotations(meta.type(), component, componentValue)) {
                continue;
            }
            builder.append(" (").append(component.getName()).append(' ');
            writeValue(componentValue, builder);
            builder.append(')');
        }

        builder.append(')');
    }

    private void writePrettyRecord(Object value, StringBuilder builder, int indentLevel, PrettyMode prettyMode) {
        var meta = AstSexprSchema.metadataForType(value.getClass());
        builder.append('(').append(meta.tag());

        var wroteComponent = false;
        for (var component : meta.components()) {
            if (!shouldIncludePrettyComponent(component, prettyMode)) {
                continue;
            }
            var componentValue = readComponentValue(component, value);
            if (shouldOmitEmptyRecoveryAnnotations(meta.type(), component, componentValue)) {
                continue;
            }

            wroteComponent = true;
            builder.append('\n');
            appendIndent(builder, indentLevel + 1);
            builder.append('(').append(component.getName());

            if (isInlinePrettyValue(componentValue)) {
                builder.append(' ');
                writePrettyValue(componentValue, builder, indentLevel + 1, prettyMode);
                builder.append(')');
                continue;
            }

            builder.append('\n');
            appendIndent(builder, indentLevel + 2);
            writePrettyValue(componentValue, builder, indentLevel + 2, prettyMode);
            builder.append('\n');
            appendIndent(builder, indentLevel + 1);
            builder.append(')');
        }

        if (!wroteComponent) {
            builder.append(')');
            return;
        }

        builder.append('\n');
        appendIndent(builder, indentLevel);
        builder.append(')');
    }

    private static boolean shouldIncludePrettyComponent(RecordComponent component, PrettyMode prettyMode) {
        return prettyMode != PrettyMode.OMIT_RANGE || !"range".equals(component.getName());
    }

    private static boolean shouldOmitEmptyRecoveryAnnotations(
            Class<?> recordType,
            RecordComponent component,
            Object componentValue
    ) {
        return recordType == VariableDeclaration.class
                && "recoveryAnnotations".equals(component.getName())
                && componentValue instanceof List<?> list
                && list.isEmpty();
    }

    private static boolean writeScalarValue(Object value, StringBuilder builder) {
        return switch (value) {
            case null -> {
                builder.append("nil");
                yield true;
            }
            case String text -> {
                writeString(text, builder);
                yield true;
            }
            case Integer number -> {
                builder.append(number);
                yield true;
            }
            case Boolean bool -> {
                builder.append(bool);
                yield true;
            }
            case Enum<?> enumValue -> {
                builder.append(enumValue.name());
                yield true;
            }
            default -> false;
        };
    }

    private static boolean isInlinePrettyValue(Object value) {
        return switch (value) {
            case null -> true;
            case String _, Integer _, Boolean _, Enum<?> _ -> true;
            case List<?> list -> list.isEmpty();
            default -> false;
        };
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

    private static void appendIndent(StringBuilder builder, int indentLevel) {
        builder.repeat(INDENT, indentLevel);
    }
}
