package dev.superice.gdparser.frontend.serialize;

import dev.superice.gdparser.frontend.ast.SourceFile;
import org.jetbrains.annotations.NotNull;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;

/// Deserializes AST records from canonical S-expression text.
public final class AstSexprDeserializer {

    public @NotNull SourceFile deserialize(String sexpr) {
        Objects.requireNonNull(sexpr, "sexpr must not be null");

        var parser = new Parser(sexpr);
        var root = parser.parseDocument();
        var value = decodeValue(root, SourceFile.class, "root");
        if (value == null) {
            throw new IllegalArgumentException("Root value must not be null");
        }
        return (SourceFile) value;
    }

    private Object decodeValue(SExpr expr, Type expectedType, String path) {
        if (isNil(expr)) {
            if (expectedType instanceof Class<?> expectedClass && expectedClass.isPrimitive()) {
                throw new IllegalArgumentException("Cannot decode nil into primitive at " + path);
            }
            return null;
        }

        if (expectedType instanceof Class<?> expectedClass) {
            return decodeClassValue(expr, expectedClass, path);
        }

        if (expectedType instanceof ParameterizedType parameterizedType) {
            return decodeParameterizedValue(expr, parameterizedType, path);
        }

        throw new IllegalArgumentException("Unsupported expected type at %s: %s".formatted(path, expectedType.getTypeName()));
    }

    private Object decodeClassValue(SExpr expr, Class<?> expectedClass, String path) {
        if (expectedClass == String.class) {
            return decodeString(expr, path);
        }
        if (expectedClass == int.class || expectedClass == Integer.class) {
            return decodeInteger(expr, path);
        }
        if (expectedClass == boolean.class || expectedClass == Boolean.class) {
            return decodeBoolean(expr, path);
        }
        if (expectedClass.isEnum()) {
            return decodeEnum(expr, expectedClass, path);
        }
        if (expectedClass.isInterface()) {
            return decodeRecord(expr, expectedClass, path, true);
        }
        if (AstSexprSchema.hasType(expectedClass)) {
            return decodeRecord(expr, expectedClass, path, false);
        }

        throw new IllegalArgumentException("Unsupported class type at %s: %s".formatted(path, expectedClass.getName()));
    }

    private Object decodeParameterizedValue(SExpr expr, ParameterizedType parameterizedType, String path) {
        var rawType = parameterizedType.getRawType();
        if (!(rawType instanceof Class<?> rawClass) || rawClass != List.class) {
            throw new IllegalArgumentException("Unsupported parameterized type at %s: %s".formatted(path, parameterizedType.getTypeName()));
        }

        var listExpr = requireList(expr, path);
        if (listExpr.elements().isEmpty()) {
            throw new IllegalArgumentException("List expression must include marker at " + path);
        }

        var marker = requireUnquotedSymbol(listExpr.elements().getFirst(), path + "[0]");
        if (!marker.equals("list")) {
            throw new IllegalArgumentException("Expected list marker '(list ...)' at %s, got '%s'".formatted(path, marker));
        }

        var elementType = parameterizedType.getActualTypeArguments()[0];
        var values = new ArrayList<>();
        for (var index = 1; index < listExpr.elements().size(); index++) {
            values.add(decodeValue(listExpr.elements().get(index), elementType, path + "[" + (index - 1) + "]"));
        }
        return List.copyOf(values);
    }

    private Object decodeRecord(SExpr expr, Class<?> expectedType, String path, boolean polymorphic) {
        var listExpr = requireList(expr, path);
        if (listExpr.elements().isEmpty()) {
            throw new IllegalArgumentException("Record expression is empty at " + path);
        }

        var tag = requireUnquotedSymbol(listExpr.elements().getFirst(), path + ".tag");
        var meta = AstSexprSchema.metadataForTag(tag);

        if (!expectedType.isAssignableFrom(meta.type())) {
            throw new IllegalArgumentException(
                    "Tag '%s' is not assignable to expected type %s at %s"
                            .formatted(tag, expectedType.getName(), path)
            );
        }

        if (!polymorphic && meta.type() != expectedType) {
            throw new IllegalArgumentException(
                    "Tag '%s' does not match expected concrete type %s at %s"
                            .formatted(tag, expectedType.getName(), path)
            );
        }

        var fieldValues = new HashMap<String, SExpr>();
        for (var index = 1; index < listExpr.elements().size(); index++) {
            var fieldPath = path + ".field[" + index + "]";
            var fieldExpr = requireList(listExpr.elements().get(index), fieldPath);
            if (fieldExpr.elements().size() != 2) {
                throw new IllegalArgumentException("Field expression must have exactly 2 elements at " + fieldPath);
            }

            var fieldName = requireUnquotedSymbol(fieldExpr.elements().getFirst(), fieldPath + ".name");
            if (!meta.componentsByName().containsKey(fieldName)) {
                throw new IllegalArgumentException(
                        "Unknown field '%s' for tag '%s' at %s".formatted(fieldName, tag, fieldPath)
                );
            }
            if (fieldValues.put(fieldName, fieldExpr.elements().get(1)) != null) {
                throw new IllegalArgumentException(
                        "Duplicate field '%s' for tag '%s' at %s".formatted(fieldName, tag, fieldPath)
                );
            }
        }

        var args = new Object[meta.components().size()];
        for (var index = 0; index < meta.components().size(); index++) {
            var component = meta.components().get(index);
            var valueExpr = fieldValues.get(component.getName());
            if (valueExpr == null) {
                throw new IllegalArgumentException(
                        "Missing field '%s' for tag '%s' at %s".formatted(component.getName(), tag, path)
                );
            }
            args[index] = decodeValue(valueExpr, component.getGenericType(), path + "." + component.getName());
        }

        try {
            return meta.canonicalConstructor().newInstance(args);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(
                    "Failed to instantiate '%s' at %s".formatted(meta.type().getName(), path),
                    exception
            );
        }
    }

    private static String decodeString(SExpr expr, String path) {
        if (expr instanceof SAtom atom) {
            return atom.text();
        }
        throw new IllegalArgumentException("Expected string atom at " + path);
    }

    private static int decodeInteger(SExpr expr, String path) {
        var token = requireUnquotedSymbol(expr, path);
        try {
            return Integer.parseInt(token);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Expected integer at %s but got '%s'".formatted(path, token), exception);
        }
    }

    private static boolean decodeBoolean(SExpr expr, String path) {
        var token = requireUnquotedSymbol(expr, path);
        return switch (token) {
            case "true" -> true;
            case "false" -> false;
            default -> throw new IllegalArgumentException("Expected boolean at %s but got '%s'".formatted(path, token));
        };
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object decodeEnum(SExpr expr, Class<?> enumClass, String path) {
        var token = requireUnquotedSymbol(expr, path);
        try {
            return Enum.valueOf((Class<Enum>) enumClass, token);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "Expected enum constant of %s at %s but got '%s'".formatted(enumClass.getName(), path, token),
                    exception
            );
        }
    }

    private static boolean isNil(SExpr expr) {
        return expr instanceof SAtom atom && !atom.quoted() && atom.text().equals("nil");
    }

    private static SList requireList(SExpr expr, String path) {
        if (expr instanceof SList list) {
            return list;
        }
        throw new IllegalArgumentException("Expected list at " + path);
    }

    private static String requireUnquotedSymbol(SExpr expr, String path) {
        if (expr instanceof SAtom atom && !atom.quoted()) {
            return atom.text();
        }
        throw new IllegalArgumentException("Expected symbol at " + path);
    }

    private sealed interface SExpr permits SList, SAtom {
    }

    private record SList(List<SExpr> elements, int position) implements SExpr {
    }

    private record SAtom(String text, boolean quoted, int position) implements SExpr {
    }

    private static final class Parser {

        private final Lexer lexer;
        private Token current;

        private Parser(String source) {
            this.lexer = new Lexer(source);
            this.current = lexer.nextToken();
        }

        private SExpr parseDocument() {
            var root = parseValue();
            if (current.type() != TokenType.EOF) {
                throw error("Unexpected trailing token: " + current.type());
            }
            return root;
        }

        private SExpr parseValue() {
            return switch (current.type()) {
                case LPAREN -> parseList();
                case STRING -> {
                    var token = current;
                    advance();
                    yield new SAtom(token.text(), true, token.position());
                }
                case SYMBOL -> {
                    var token = current;
                    advance();
                    yield new SAtom(token.text(), false, token.position());
                }
                case RPAREN, EOF -> throw error("Unexpected token: " + current.type());
            };
        }

        private SList parseList() {
            var start = current.position();
            expect(TokenType.LPAREN);

            var values = new ArrayList<SExpr>();
            while (current.type() != TokenType.RPAREN) {
                if (current.type() == TokenType.EOF) {
                    throw error("Unterminated list");
                }
                values.add(parseValue());
            }

            expect(TokenType.RPAREN);
            return new SList(List.copyOf(values), start);
        }

        private void expect(TokenType expected) {
            if (current.type() != expected) {
                throw error("Expected " + expected + " but got " + current.type());
            }
            advance();
        }

        private void advance() {
            current = lexer.nextToken();
        }

        private IllegalArgumentException error(String message) {
            return new IllegalArgumentException(message + " at index " + current.position());
        }
    }

    private enum TokenType {
        LPAREN,
        RPAREN,
        SYMBOL,
        STRING,
        EOF
    }

    private record Token(TokenType type, String text, int position) {
    }

    private static final class Lexer {

        private final String source;
        private int index;

        private Lexer(String source) {
            this.source = source;
            this.index = 0;
        }

        private Token nextToken() {
            skipWhitespace();
            if (index >= source.length()) {
                return new Token(TokenType.EOF, "", index);
            }

            var start = index;
            var ch = source.charAt(index);
            if (ch == '(') {
                index++;
                return new Token(TokenType.LPAREN, "(", start);
            }
            if (ch == ')') {
                index++;
                return new Token(TokenType.RPAREN, ")", start);
            }
            if (ch == '"') {
                return readString(start);
            }

            return readSymbol(start);
        }

        private Token readString(int start) {
            index++;
            var builder = new StringBuilder();
            while (index < source.length()) {
                var ch = source.charAt(index++);
                if (ch == '"') {
                    return new Token(TokenType.STRING, builder.toString(), start);
                }
                if (ch == '\\') {
                    if (index >= source.length()) {
                        throw new IllegalArgumentException("Unterminated escape sequence at index " + start);
                    }
                    var escaped = source.charAt(index++);
                    switch (escaped) {
                        case 'n' -> builder.append('\n');
                        case 'r' -> builder.append('\r');
                        case 't' -> builder.append('\t');
                        case '"' -> builder.append('"');
                        case '\\' -> builder.append('\\');
                        default -> throw new IllegalArgumentException(
                                "Unsupported escape '\\%s' at index %d".formatted(escaped, index - 1)
                        );
                    }
                    continue;
                }
                builder.append(ch);
            }

            throw new IllegalArgumentException("Unterminated string literal at index " + start);
        }

        private Token readSymbol(int start) {
            var begin = index;
            while (index < source.length()) {
                var ch = source.charAt(index);
                if (Character.isWhitespace(ch) || ch == '(' || ch == ')') {
                    break;
                }
                index++;
            }

            var symbol = source.substring(begin, index);
            if (symbol.isEmpty()) {
                throw new IllegalArgumentException("Expected symbol at index " + start);
            }
            return new Token(TokenType.SYMBOL, symbol, start);
        }

        private void skipWhitespace() {
            while (index < source.length() && Character.isWhitespace(source.charAt(index))) {
                index++;
            }
        }
    }
}
