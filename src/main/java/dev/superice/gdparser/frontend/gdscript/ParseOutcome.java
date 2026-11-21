package dev.superice.gdparser.frontend.gdscript;

/// Outcome of a parse step, replacing Godot's nullable returns with an explicit damage channel.
/// Every variant carries whether a diagnostic was already pushed inside, which is what lets a
/// host enforce single-root-cause reporting:
/// - `Ok`: a healthy node/value.
/// - `EnclosedDamage`: a structurally complete value whose boundary encloses child damage.
///   Its surface form can still be checked, and its children retain their root diagnostics.
/// - `Missing`: nothing was consumed and no diagnostic was pushed; the host owns the context
///   message and materializes a zero-width MISSING marker if the slot must hold a node.
/// - `Failed`: a diagnostic was pushed but no structure could be built (usually tokens were
///   consumed; the depth-budget rejection is the sole zero-consumption source); the host
///   materializes an ERROR fragment over the consumed span without adding a generic message.
/// - `Damaged`: a partial value with unenclosed damage; the host keeps it without adding
///   a generic message or treating its surface form as complete.
sealed interface ParseOutcome<T> {

    record Ok<T>(T value) implements ParseOutcome<T> {
    }

    record EnclosedDamage<T>(T value) implements ParseOutcome<T> {
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

    /// Keeps a complete value without discarding the damage enclosed by its structure.
    static <T> ParseOutcome<T> enclosedDamage(T value) {
        return new EnclosedDamage<>(value);
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

    /// Classifies a surviving value after its own syntax and its child slots have been checked.
    static <T> ParseOutcome<T> structured(T value, boolean complete, boolean hasChildDamage) {
        if (!complete) {
            return damaged(value);
        }
        return hasChildDamage ? enclosedDamage(value) : ok(value);
    }

    /// Only a value with complete surface syntax is suitable for shape checks.
    default boolean hasCompleteValue() {
        return this instanceof Ok<T> || this instanceof EnclosedDamage<T>;
    }

    /// Extracts the value when the producer guarantees one, such as a call argument list.
    default T valueOrThrow() {
        return switch (this) {
            case Ok<T>(var value) -> value;
            case EnclosedDamage<T>(var value) -> value;
            case Damaged<T>(var value) -> value;
            case Missing<T> _, Failed<T> _ -> throw new IllegalStateException("Outcome carries no value: " + this);
        };
    }
}
