package dev.superice.gdparser.frontend.gdscript;

import dev.superice.gdparser.frontend.ast.AstDiagnostic;
import dev.superice.gdparser.frontend.ast.AstDiagnosticSeverity;
import dev.superice.gdparser.frontend.ast.Node;
import dev.superice.gdparser.frontend.ast.Range;
import dev.superice.gdparser.frontend.ast.RecoveryLink;
import dev.superice.gdparser.frontend.ast.SourceFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;

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
        private final int logicalStartLine;
        private @Nullable ProvisionalFrame targetFrame;

        private DiagnosticEntry(
                @NotNull AstDiagnostic diagnostic,
                @Nullable ProvisionalFrame targetFrame,
                int logicalStartLine
        ) {
            this.diagnostic = diagnostic;
            this.targetFrame = targetFrame;
            this.logicalStartLine = logicalStartLine;
        }
    }

    static final class LexicalBatch {
        private final List<DiagnosticEntry> entries = new ArrayList<>();
        private final boolean followedContinuation;
        private boolean settled;
        private int minStartByte = Integer.MAX_VALUE;
        private int maxEndByte = -1;
        private int firstStartLine = Integer.MAX_VALUE;
        private int lastEndLine = -1;
        private int @Nullable [] physicalOrderIndices;
        private int @Nullable [] physicalOrderStartBytes;
        private int @Nullable [] physicalOrderEndBytes;

        private LexicalBatch(boolean followedContinuation) {
            this.followedContinuation = followedContinuation;
        }

        boolean followedContinuation() {
            return followedContinuation;
        }

        boolean isSettled() {
            return settled;
        }

        boolean isEmpty() {
            return entries.isEmpty();
        }

        int minStartByte() {
            return minStartByte;
        }

        int maxEndByte() {
            return maxEndByte;
        }

        int firstStartLine() {
            return firstStartLine;
        }

        int lastEndLine() {
            return lastEndLine;
        }

        int entryCount() {
            return entries.size();
        }

        int entryStartByte(int index) {
            return entries.get(index).diagnostic.range().startByte();
        }

        int entryEndByte(int index) {
            return entries.get(index).diagnostic.range().endByte();
        }

        int entryLogicalStartLine(int index) {
            return entries.get(index).logicalStartLine;
        }

        int[] physicalOrderIndices() {
            ensurePhysicalOrderCache();
            return Objects.requireNonNull(physicalOrderIndices);
        }

        int[] physicalOrderStartBytes() {
            ensurePhysicalOrderCache();
            return Objects.requireNonNull(physicalOrderStartBytes);
        }

        int[] physicalOrderEndBytes() {
            ensurePhysicalOrderCache();
            return Objects.requireNonNull(physicalOrderEndBytes);
        }

        private void invalidatePhysicalOrderCache() {
            physicalOrderIndices = null;
            physicalOrderStartBytes = null;
            physicalOrderEndBytes = null;
        }

        private void ensurePhysicalOrderCache() {
            if (physicalOrderIndices != null) {
                return;
            }
            var n = entries.size();
            var indices = new int[n];
            var starts = new int[n];
            var ends = new int[n];
            var alreadySorted = true;
            var prevStart = Integer.MIN_VALUE;
            for (var i = 0; i < n; i++) {
                var range = entries.get(i).diagnostic.range();
                var start = range.startByte();
                if (start < prevStart) {
                    alreadySorted = false;
                }
                prevStart = start;
                indices[i] = i;
                starts[i] = start;
                ends[i] = range.endByte();
            }
            if (!alreadySorted) {
                var rawStarts = starts;
                var boxed = new Integer[n];
                for (var i = 0; i < n; i++) {
                    boxed[i] = i;
                }
                Arrays.sort(boxed, Comparator.comparingInt((Integer a) -> rawStarts[a]).thenComparingInt(a -> a));
                var sortedStarts = new int[n];
                var sortedEnds = new int[n];
                for (var i = 0; i < n; i++) {
                    var idx = boxed[i];
                    indices[i] = idx;
                    sortedStarts[i] = rawStarts[idx];
                    sortedEnds[i] = ends[idx];
                }
                starts = sortedStarts;
                ends = sortedEnds;
            }
            physicalOrderIndices = indices;
            physicalOrderStartBytes = starts;
            physicalOrderEndBytes = ends;
        }
    }

    record LexicalSpan(int startByte, int endByte) {
    }

    private record ResolvedTarget(@Nullable Node owner, @Nullable String facet) {
        private static final ResolvedTarget NONE = new ResolvedTarget(null, null);
    }

    private final List<DiagnosticEntry> entries = new ArrayList<>();
    private final Deque<ProvisionalFrame> frameStack = new ArrayDeque<>();
    private final Set<ProvisionalFrame> ownedFrames = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<LexicalBatch> ownedBatches = Collections.newSetFromMap(new IdentityHashMap<>());
    private final List<LexicalBatch> openBatches = new ArrayList<>();
    private long fallbackResolutionSteps;

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

    /// Checks only errors settled on this frame, not lookahead or child-frame diagnostics.
    boolean hasErrors(@NotNull ProvisionalFrame frame) {
        requireOwnedFrame(frame);
        for (var entry : frame.pendingEntries) {
            if (entry.diagnostic.severity() == AstDiagnosticSeverity.ERROR) {
                return true;
            }
        }
        return false;
    }

    void reset() {
        fallbackResolutionSteps = 0;
        clearState();
    }

    /// Fallback frame visits and cache writes from the most recent finalization.
    long fallbackResolutionSteps() {
        return fallbackResolutionSteps;
    }

    private void clearState() {
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
        var entry = new DiagnosticEntry(diagnostic, resolvedTarget, range.startPoint().row() + 1);
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
        entries.add(new DiagnosticEntry(diagnostic, null, range.startPoint().row() + 1));
    }

    void emitWarning(@NotNull String message, @NotNull String nodeType, @NotNull Range range) {
        Objects.requireNonNull(message, "message must not be null");
        Objects.requireNonNull(nodeType, "nodeType must not be null");
        Objects.requireNonNull(range, "range must not be null");
        var diagnostic = new AstDiagnostic(AstDiagnosticSeverity.WARNING, message, nodeType, range);
        entries.add(new DiagnosticEntry(diagnostic, null, range.startPoint().row() + 1));
    }

    /// Opens a batch handle for lookahead lexical errors that can later be claimed or flushed in O(1)
    /// amortized time without scanning a shared staging list.
    @NotNull LexicalBatch openLexicalBatch() {
        return openLexicalBatch(false);
    }

    @NotNull LexicalBatch openLexicalBatch(boolean followedContinuation) {
        var batch = new LexicalBatch(followedContinuation);
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
        entries.add(new DiagnosticEntry(diagnostic, null, range.startPoint().row() + 1));
    }

    /// Stages a lexical error into an open `LexicalBatch` while preserving exact global emission order.
    void stageLexicalError(
            @NotNull LexicalBatch batch,
            @NotNull String message,
            @NotNull Range range,
            @NotNull String code
    ) {
        stageLexicalError(batch, message, range, code, range.startPoint().row() + 1);
    }

    void stageLexicalError(
            @NotNull LexicalBatch batch,
            @NotNull String message,
            @NotNull Range range,
            @NotNull String code,
            int logicalStartLine
    ) {
        requireOwnedBatch(batch);
        Objects.requireNonNull(message, "message must not be null");
        Objects.requireNonNull(range, "range must not be null");
        DiagnosticCodes.requireRegistered(code);
        if (!DiagnosticCodes.allowsNullRecovery(code)) {
            throw new IllegalArgumentException("Staged lexical error code must allow standalone recovery: " + code);
        }
        if (logicalStartLine < 1) {
            throw new IllegalArgumentException("logicalStartLine must be >= 1, got: " + logicalStartLine);
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
        var entry = new DiagnosticEntry(diagnostic, null, logicalStartLine);
        entries.add(entry);
        batch.entries.add(entry);
        batch.invalidatePhysicalOrderCache();
        batch.minStartByte = Math.min(batch.minStartByte, range.startByte());
        batch.maxEndByte = Math.max(batch.maxEndByte, range.endByte());
        batch.firstStartLine = Math.min(batch.firstStartLine, range.startPoint().row() + 1);
        batch.lastEndLine = Math.max(batch.lastEndLine, range.endPoint().row() + 1);
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
        batch.invalidatePhysicalOrderCache();
        batch.settled = true;
    }

    /// Binds the masked subset of entries in `batch` to `targetFrame` (or releases them standalone
    /// when `targetFrame == null`) without changing global diagnostic emission order, recomputing
    /// summary bounds over any remaining unclaimed entries so unsettled tails stay queued.
    @Nullable LexicalSpan claimLexicalBatchSubset(
            @NotNull LexicalBatch batch,
            boolean @NotNull [] claimMask,
            @Nullable ProvisionalFrame targetFrame
    ) {
        requireOwnedBatch(batch);
        if (targetFrame != null) {
            requireOwnedFrame(targetFrame);
        }
        if (batch.settled || batch.entries.isEmpty()) {
            return null;
        }
        if (claimMask.length != batch.entries.size()) {
            throw new IllegalArgumentException(
                    "claimMask length (%d) must match batch entry count (%d)"
                            .formatted(claimMask.length, batch.entries.size()));
        }
        var spanStart = Integer.MAX_VALUE;
        var spanEnd = Integer.MIN_VALUE;
        var remaining = new ArrayList<DiagnosticEntry>();
        var remMinStart = Integer.MAX_VALUE;
        var remMaxEnd = -1;
        var remFirstLine = Integer.MAX_VALUE;
        var remLastLine = -1;
        var claimedAny = false;
        for (var i = 0; i < batch.entries.size(); i++) {
            var entry = batch.entries.get(i);
            var range = entry.diagnostic.range();
            if (claimMask[i]) {
                claimedAny = true;
                spanStart = Math.min(spanStart, range.startByte());
                spanEnd = Math.max(spanEnd, range.endByte());
                if (targetFrame != null) {
                    entry.targetFrame = targetFrame;
                    targetFrame.pendingEntries.add(entry);
                }
            } else {
                remaining.add(entry);
                remMinStart = Math.min(remMinStart, range.startByte());
                remMaxEnd = Math.max(remMaxEnd, range.endByte());
                remFirstLine = Math.min(remFirstLine, range.startPoint().row() + 1);
                remLastLine = Math.max(remLastLine, range.endPoint().row() + 1);
            }
        }
        if (!claimedAny) {
            return null;
        }
        batch.entries.clear();
        batch.entries.addAll(remaining);
        batch.invalidatePhysicalOrderCache();
        if (batch.entries.isEmpty()) {
            batch.settled = true;
            batch.minStartByte = Integer.MAX_VALUE;
            batch.maxEndByte = -1;
            batch.firstStartLine = Integer.MAX_VALUE;
            batch.lastEndLine = -1;
        } else {
            batch.minStartByte = remMinStart;
            batch.maxEndByte = remMaxEnd;
            batch.firstStartLine = remFirstLine;
            batch.lastEndLine = remLastLine;
        }
        return new LexicalSpan(spanStart, spanEnd);
    }

    /// Releases every entry in `batch` as a standalone (null recovery) diagnostic in O(1).
    void flushLexicalBatchAsStandalone(@NotNull LexicalBatch batch) {
        requireOwnedBatch(batch);
        if (batch.settled) {
            return;
        }
        batch.entries.clear();
        batch.invalidatePhysicalOrderCache();
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
        fallbackResolutionSteps = 0;
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
            clearState();
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

    private @NotNull ResolvedTarget resolveFrameTarget(
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

    private @NotNull ResolvedTarget resolveFallbackOf(
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
            fallbackResolutionSteps++;
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
            fallbackResolutionSteps++;
            fallbackCache.computeIfAbsent(node, _ -> new HashMap<>()).put(code, result);
        }
        return result;
    }
}
