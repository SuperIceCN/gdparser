package dev.superice.gdparser.frontend.gdscript;

import org.jetbrains.annotations.Nullable;

/// Outcome of a parse step, replacing Godot's nullable returns with an explicit damage channel.
/// Every variant carries whether a diagnostic was already pushed inside, which is what lets a
/// host enforce single-root-cause reporting:
/// - `Ok`: a healthy node/value.
/// - `Missing`: nothing was consumed and no diagnostic was pushed; the host owns the context
///   message and materializes a zero-width MISSING marker if the slot must hold a node.
/// - `Failed`: a diagnostic was pushed but no structure could be built (usually tokens were
///   consumed; the depth-budget rejection is the sole zero-consumption source); the host
///   materializes an ERROR fragment over the consumed span without adding a generic message.
/// - `Damaged`: a partial node exists and a diagnostic was pushed inside; the host keeps the
///   node without adding a generic message.
sealed interface ParseOutcome<T> {

    record Ok<T>(T value) implements ParseOutcome<T> {
    }

    record Missing<T>() implements ParseOutcome<T> {
    }

    record Failed<T>() implements ParseOutcome<T> {
    }

    record Damaged<T>(T value) implements ParseOutcome<T> {
    }

    static <T> ParseOutcome<T> ok(T value) {
        return new Ok<>(value);
    }

    static <T> ParseOutcome<T> missing() {
        return new Missing<>();
    }

    static <T> ParseOutcome<T> failed() {
        return new Failed<>();
    }

    static <T> ParseOutcome<T> damaged(T value) {
        return new Damaged<>(value);
    }

    default boolean isOk() {
        return this instanceof Ok<T>;
    }

    /// The carried value for `Ok`/`Damaged`, `null` otherwise. Callers must treat a `Damaged`
    /// value as structurally incomplete; prefer pattern matching over this accessor.
    default @Nullable T valueOrNull() {
        return switch (this) {
            case Ok<T>(var value) -> value;
            case Damaged<T>(var value) -> value;
            default -> null;
        };
    }

    /// The carried value for outcomes that always carry one (`Ok`/`Damaged`); throws otherwise.
    /// For producers whose contract guarantees a value (e.g. an argument list that absorbs
    /// element damage into markers).
    default T valueOrThrow() {
        return switch (this) {
            case Ok<T>(var value) -> value;
            case Damaged<T>(var value) -> value;
            default -> throw new IllegalStateException("Outcome carries no value: " + this);
        };
    }
}
