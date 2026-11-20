package dev.superice.gdparser.frontend.gdscript;

import dev.superice.gdparser.frontend.ast.AstDiagnostic;
import dev.superice.gdparser.frontend.ast.AstDiagnosticSeverity;
import dev.superice.gdparser.frontend.ast.Node;
import dev.superice.gdparser.frontend.ast.Range;
import dev.superice.gdparser.frontend.ast.RecoveryLink;
import dev.superice.gdparser.frontend.ast.SourceFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/// Tracks provisional recovery frames, diagnostic entries, lookahead lexical-error staging, and
/// end-of-parse owner attribution with amortized O(frames + diagnostics) fallback resolution.
final class RecoveryTransactionTracker {

    private static final String DIAGNOSTIC_NODE_TYPE = "ERROR";

    enum AttributionPolicy {
        CURRENT_FRAME,
        TARGET_FRAME,
        STANDALONE
    }

    static final class ProvisionalFrame {
        private final @NotNull String facet;
        private final @Nullable ProvisionalFrame authorizedFallback;
        private final @Nullable String fallbackFacet;
        /// Indexes diagnostic entries currently attributed to this frame so frame-local queries,
        /// bulk re-attribution, or rollback can inspect or update a frame's diagnostics in O(k)
        /// without scanning the global `entries` list (while `finalizeDiagnostics` iterates `entries`
        /// to preserve emission order).
        private final List<DiagnosticEntry> pendingEntries = new ArrayList<>();
        private @Nullable Node committedOwner;
        private boolean abandoned;

        private ProvisionalFrame(
                @NotNull String facet,
                @Nullable ProvisionalFrame authorizedFallback,
                @Nullable String fallbackFacet
        ) {
            this.facet = facet;
            this.authorizedFallback = authorizedFallback;
            this.fallbackFacet = fallbackFacet;
        }

        @NotNull String facet() {
            return facet;
        }

        @Nullable ProvisionalFrame authorizedFallback() {
            return authorizedFallback;
        }

        @Nullable String fallbackFacet() {
            return fallbackFacet;
        }

        @Nullable Node committedOwner() {
            return committedOwner;
        }

        boolean isAbandoned() {
            return abandoned;
        }
    }

    private static final class DiagnosticEntry {
        private final @NotNull AstDiagnostic diagnostic;
        private @Nullable ProvisionalFrame targetFrame;

        private DiagnosticEntry(@NotNull AstDiagnostic diagnostic, @Nullable ProvisionalFrame targetFrame) {
            this.diagnostic = diagnostic;
            this.targetFrame = targetFrame;
        }
    }

    static final class LexicalBatch {
        private final List<DiagnosticEntry> entries = new ArrayList<>();
        private boolean settled;

        private LexicalBatch() {
        }

        boolean isSettled() {
            return settled;
        }
    }

    private record ResolvedTarget(@Nullable Node owner, @Nullable String facet) {
        private static final ResolvedTarget NONE = new ResolvedTarget(null, null);
    }

    private final List<DiagnosticEntry> entries = new ArrayList<>();
    private final Deque<ProvisionalFrame> frameStack = new ArrayDeque<>();
    private final Set<ProvisionalFrame> ownedFrames = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<LexicalBatch> ownedBatches = Collections.newSetFromMap(new IdentityHashMap<>());
    private final List<LexicalBatch> openBatches = new ArrayList<>();

    @NotNull ProvisionalFrame pushFrame(@NotNull String facet) {
        Objects.requireNonNull(facet, "facet must not be null");
        if (!DiagnosticCodes.isRegisteredFacet(facet)) {
            throw new IllegalArgumentException("Unregistered recovery facet: " + facet);
        }
        var frame = new ProvisionalFrame(facet, null, null);
        ownedFrames.add(frame);
        frameStack.addLast(frame);
        return frame;
    }

    @NotNull ProvisionalFrame pushChildFrame(
            @NotNull String facet,
            @NotNull ProvisionalFrame fallbackParent,
            @NotNull String fallbackFacet
    ) {
        Objects.requireNonNull(facet, "facet must not be null");
        Objects.requireNonNull(fallbackParent, "fallbackParent must not be null");
        Objects.requireNonNull(fallbackFacet, "fallbackFacet must not be null");
        if (!DiagnosticCodes.isRegisteredFacet(facet)) {
            throw new IllegalArgumentException("Unregistered recovery facet: " + facet);
        }
        if (!DiagnosticCodes.isRegisteredFacet(fallbackFacet)) {
            throw new IllegalArgumentException("Unregistered fallback recovery facet: " + fallbackFacet);
        }
        requireOwnedFrame(fallbackParent);
        var frame = new ProvisionalFrame(facet, fallbackParent, fallbackFacet);
        ownedFrames.add(frame);
        frameStack.addLast(frame);
        return frame;
    }

    @NotNull ProvisionalFrame popFrame() {
        var frame = frameStack.pollLast();
        if (frame == null) {
            throw new IllegalStateException("GDScript parser bug: recovery frame stack underflow.");
        }
        return frame;
    }

    @Nullable ProvisionalFrame activeFrame() {
        return frameStack.peekLast();
    }

    void commit(@NotNull ProvisionalFrame frame, @NotNull Node owner) {
        requireOwnedFrame(frame);
        Objects.requireNonNull(owner, "owner must not be null");
        if (frame.abandoned) {
            throw new IllegalStateException("Cannot commit an abandoned recovery frame: " + frame.facet);
        }
        if (frame.committedOwner != null) {
            throw new IllegalStateException("Recovery frame already committed; use rebind() to replace owner.");
        }
        frame.committedOwner = owner;
    }

    void rebind(@NotNull ProvisionalFrame frame, @NotNull Node replacementOwner) {
        requireOwnedFrame(frame);
        Objects.requireNonNull(replacementOwner, "replacementOwner must not be null");
        if (frame.abandoned) {
            throw new IllegalStateException("Cannot rebind an abandoned recovery frame: " + frame.facet);
        }
        frame.committedOwner = replacementOwner;
    }

    void abandon(@NotNull ProvisionalFrame frame) {
        requireOwnedFrame(frame);
        frame.abandoned = true;
        frame.committedOwner = null;
    }

    boolean isBalanced() {
        return frameStack.isEmpty();
    }

    int diagnosticCount() {
        return entries.size();
    }

    void reset() {
        entries.clear();
        frameStack.clear();
        for (var batch : openBatches) {
            batch.entries.clear();
            batch.settled = true;
        }
        openBatches.clear();
        ownedBatches.clear();
        ownedFrames.clear();
    }

    void emitError(
            @NotNull String message,
            @NotNull Range range,
            @NotNull String code,
            @NotNull AttributionPolicy policy,
            @Nullable ProvisionalFrame targetFrame
    ) {
        Objects.requireNonNull(message, "message must not be null");
        Objects.requireNonNull(range, "range must not be null");
        DiagnosticCodes.requireRegistered(code);
        Objects.requireNonNull(policy, "policy must not be null");

        ProvisionalFrame resolvedTarget = switch (policy) {
            case CURRENT_FRAME -> {
                if (targetFrame != null) {
                    throw new IllegalArgumentException("targetFrame must be null for CURRENT_FRAME policy.");
                }
                var active = frameStack.peekLast();
                if (active == null && !DiagnosticCodes.allowsNullRecovery(code)) {
                    throw new IllegalStateException(
                            "No active recovery frame for diagnostic code that requires an owner: " + code);
                }
                yield active;
            }
            case TARGET_FRAME -> {
                if (targetFrame == null) {
                    throw new IllegalArgumentException("targetFrame must not be null for TARGET_FRAME policy.");
                }
                requireOwnedFrame(targetFrame);
                yield targetFrame;
            }
            case STANDALONE -> {
                if (targetFrame != null) {
                    throw new IllegalArgumentException("targetFrame must be null for STANDALONE policy.");
                }
                if (!DiagnosticCodes.allowsNullRecovery(code)) {
                    throw new IllegalArgumentException(
                            "Diagnostic code does not allow standalone null recovery: " + code);
                }
                yield null;
            }
        };

        var diagnostic = new AstDiagnostic(
                AstDiagnosticSeverity.ERROR,
                message,
                DIAGNOSTIC_NODE_TYPE,
                range,
                code,
                null
        );
        var entry = new DiagnosticEntry(diagnostic, resolvedTarget);
        entries.add(entry);
        if (resolvedTarget != null) {
            resolvedTarget.pendingEntries.add(entry);
        }
    }

    /// Emits a diagnostic without binding it to a provisional recovery frame.
    void emitLegacyError(@NotNull String message, @NotNull Range range, @Nullable String code) {
        Objects.requireNonNull(message, "message must not be null");
        Objects.requireNonNull(range, "range must not be null");
        if (code != null) {
            DiagnosticCodes.requireRegistered(code);
        }
        var diagnostic = new AstDiagnostic(
                AstDiagnosticSeverity.ERROR,
                message,
                DIAGNOSTIC_NODE_TYPE,
                range,
                code,
                null
        );
        entries.add(new DiagnosticEntry(diagnostic, null));
    }

    void emitWarning(@NotNull String message, @NotNull String nodeType, @NotNull Range range) {
        Objects.requireNonNull(message, "message must not be null");
        Objects.requireNonNull(nodeType, "nodeType must not be null");
        Objects.requireNonNull(range, "range must not be null");
        var diagnostic = new AstDiagnostic(AstDiagnosticSeverity.WARNING, message, nodeType, range);
        entries.add(new DiagnosticEntry(diagnostic, null));
    }

    /// Opens a batch handle for lookahead lexical errors that can later be claimed or flushed in O(1)
    /// amortized time without scanning a shared staging list.
    @NotNull LexicalBatch openLexicalBatch() {
        var batch = new LexicalBatch();
        ownedBatches.add(batch);
        openBatches.add(batch);
        return batch;
    }

    /// Emits a standalone lexical error directly without opening a claimable batch.
    void stageLexicalError(@NotNull String message, @NotNull Range range, @NotNull String code) {
        Objects.requireNonNull(message, "message must not be null");
        Objects.requireNonNull(range, "range must not be null");
        DiagnosticCodes.requireRegistered(code);
        if (!DiagnosticCodes.allowsNullRecovery(code)) {
            throw new IllegalArgumentException("Staged lexical error code must allow standalone recovery: " + code);
        }
        var diagnostic = new AstDiagnostic(
                AstDiagnosticSeverity.ERROR,
                message,
                DIAGNOSTIC_NODE_TYPE,
                range,
                code,
                null
        );
        entries.add(new DiagnosticEntry(diagnostic, null));
    }

    /// Stages a lexical error into an open `LexicalBatch` while preserving exact global emission order.
    void stageLexicalError(
            @NotNull LexicalBatch batch,
            @NotNull String message,
            @NotNull Range range,
            @NotNull String code
    ) {
        requireOwnedBatch(batch);
        Objects.requireNonNull(message, "message must not be null");
        Objects.requireNonNull(range, "range must not be null");
        DiagnosticCodes.requireRegistered(code);
        if (!DiagnosticCodes.allowsNullRecovery(code)) {
            throw new IllegalArgumentException("Staged lexical error code must allow standalone recovery: " + code);
        }
        if (batch.settled) {
            throw new IllegalStateException("Cannot stage lexical error into a settled LexicalBatch.");
        }
        var diagnostic = new AstDiagnostic(
                AstDiagnosticSeverity.ERROR,
                message,
                DIAGNOSTIC_NODE_TYPE,
                range,
                code,
                null
        );
        var entry = new DiagnosticEntry(diagnostic, null);
        entries.add(entry);
        batch.entries.add(entry);
    }

    /// Binds every entry in `batch` to `targetFrame` in a single pass; settled batches are ignored.
    void claimLexicalBatch(@NotNull LexicalBatch batch, @NotNull ProvisionalFrame targetFrame) {
        requireOwnedBatch(batch);
        requireOwnedFrame(targetFrame);
        if (batch.settled) {
            return;
        }
        for (var entry : batch.entries) {
            entry.targetFrame = targetFrame;
            targetFrame.pendingEntries.add(entry);
        }
        batch.entries.clear();
        batch.settled = true;
    }

    /// Releases every entry in `batch` as a standalone (null recovery) diagnostic in O(1).
    void flushLexicalBatchAsStandalone(@NotNull LexicalBatch batch) {
        requireOwnedBatch(batch);
        if (batch.settled) {
            return;
        }
        batch.entries.clear();
        batch.settled = true;
    }

    /// Settles all remaining open lexical batches as standalone diagnostics.
    void flushAllLexicalBatchesAsStandalone() {
        if (openBatches.isEmpty()) {
            return;
        }
        for (var batch : openBatches) {
            if (!batch.settled) {
                batch.entries.clear();
                batch.settled = true;
            }
        }
        openBatches.clear();
    }

    /// Resolves all diagnostic recovery links against the final AST using one identity-based
    /// reachability walk and code-aware memoized fallback-chain traversal, then resets all
    /// per-parse state so no provisional AST references are retained.
    @NotNull List<AstDiagnostic> finalizeDiagnostics(@NotNull SourceFile ast) {
        Objects.requireNonNull(ast, "ast must not be null");
        try {
            flushAllLexicalBatchesAsStandalone();

            if (entries.isEmpty()) {
                return List.of();
            }
            var hasAttributedEntries = false;
            for (var entry : entries) {
                if (entry.targetFrame != null || entry.diagnostic.recovery() != null) {
                    hasAttributedEntries = true;
                    break;
                }
            }
            if (!hasAttributedEntries) {
                var result = new ArrayList<AstDiagnostic>(entries.size());
                for (var entry : entries) {
                    result.add(entry.diagnostic);
                }
                return List.copyOf(result);
            }

            var reachable = collectReachableNodes(ast);
            var fallbackCache = new IdentityHashMap<ProvisionalFrame, Map<String, ResolvedTarget>>();
            var result = new ArrayList<AstDiagnostic>(entries.size());
            for (var entry : entries) {
                var diagnostic = entry.diagnostic;
                var frame = entry.targetFrame;
                var code = diagnostic.code();
                if (frame == null || code == null) {
                    result.add(diagnostic.recovery() == null ? diagnostic : diagnostic.withRecovery(null));
                    continue;
                }
                var resolved = resolveFrameTarget(frame, code, reachable, fallbackCache);
                if (resolved.owner() != null && resolved.facet() != null) {
                    result.add(diagnostic.withRecovery(new RecoveryLink(resolved.owner(), resolved.facet())));
                } else {
                    result.add(diagnostic.recovery() == null ? diagnostic : diagnostic.withRecovery(null));
                }
            }
            return List.copyOf(result);
        } finally {
            reset();
        }
    }

    private void requireOwnedFrame(@NotNull ProvisionalFrame frame) {
        Objects.requireNonNull(frame, "frame must not be null");
        if (!ownedFrames.contains(frame)) {
            throw new IllegalArgumentException("ProvisionalFrame does not belong to this RecoveryTransactionTracker.");
        }
    }

    private void requireOwnedBatch(@NotNull LexicalBatch batch) {
        Objects.requireNonNull(batch, "batch must not be null");
        if (!ownedBatches.contains(batch)) {
            throw new IllegalArgumentException("LexicalBatch does not belong to this RecoveryTransactionTracker.");
        }
    }

    private static @NotNull Set<Node> collectReachableNodes(@NotNull SourceFile ast) {
        var reachable = Collections.newSetFromMap(new IdentityHashMap<Node, Boolean>());
        var work = new ArrayDeque<Node>();
        work.add(ast);
        while (!work.isEmpty()) {
            var node = work.poll();
            if (reachable.add(node)) {
                work.addAll(node.getChildren());
            }
        }
        return reachable;
    }

    private static @Nullable Node survivingOwner(
            @NotNull ProvisionalFrame frame,
            @NotNull Set<Node> reachable
    ) {
        return !frame.abandoned && frame.committedOwner != null && reachable.contains(frame.committedOwner)
                ? frame.committedOwner
                : null;
    }

    private static @NotNull ResolvedTarget resolveFrameTarget(
            @NotNull ProvisionalFrame frame,
            @NotNull String code,
            @NotNull Set<Node> reachable,
            @NotNull Map<ProvisionalFrame, Map<String, ResolvedTarget>> fallbackCache
    ) {
        var owner = survivingOwner(frame, reachable);
        if (owner != null && DiagnosticCodes.isValidPairing(code, owner.getClass(), frame.facet)) {
            return new ResolvedTarget(owner, frame.facet);
        }
        return resolveFallbackOf(frame, code, reachable, fallbackCache);
    }

    private static @NotNull ResolvedTarget resolveFallbackOf(
            @NotNull ProvisionalFrame startFrame,
            @NotNull String code,
            @NotNull Set<Node> reachable,
            @NotNull Map<ProvisionalFrame, Map<String, ResolvedTarget>> fallbackCache
    ) {
        var path = new ArrayList<ProvisionalFrame>();
        var curr = startFrame;
        ResolvedTarget result;
        // `authorizedFallback` edges point strictly to older frames registered earlier in
        // `ownedFrames`, forming an acyclic finite chain that always exits via cache hit, root
        // (`parent == null`), or a surviving compatible ancestor.
        while (true) {
            var byCode = fallbackCache.get(curr);
            if (byCode != null) {
                var cached = byCode.get(code);
                if (cached != null) {
                    result = cached;
                    break;
                }
            }
            var parent = curr.authorizedFallback;
            if (parent == null) {
                path.add(curr);
                result = ResolvedTarget.NONE;
                break;
            }
            var parentOwner = survivingOwner(parent, reachable);
            if (parentOwner != null
                    && curr.fallbackFacet != null
                    && DiagnosticCodes.isValidPairing(code, parentOwner.getClass(), curr.fallbackFacet)) {
                path.add(curr);
                result = new ResolvedTarget(parentOwner, curr.fallbackFacet);
                break;
            }
            path.add(curr);
            curr = parent;
        }
        for (var node : path) {
            fallbackCache.computeIfAbsent(node, _ -> new HashMap<>()).put(code, result);
        }
        return result;
    }
}
