export const SOURCE_LIMIT_UNITS = 32 * 1024;
const DEBOUNCE_MS = 175;
const STARTUP_TIMEOUT_MS = 10_000;

export function createExplorerController({worker, readSource, render, timers = globalThis}) {
    let revision = 0;
    let composing = false;
    let ready = false;
    let unavailable = false;
    let debounceTimer;
    let inFlight;
    let pending;

    const startupTimer = timers.setTimeout(fail, STARTUP_TIMEOUT_MS);
    worker.onmessage = ({data}) => receive(data);
    worker.onerror = fail;
    worker.onmessageerror = fail;

    function show(phase, result) {
        render(result === undefined ? {phase, revision} : {phase, revision, result});
    }

    function discardPending() {
        if (debounceTimer !== undefined) {
            timers.clearTimeout(debounceTimer);
            debounceTimer = undefined;
        }
        pending = undefined;
    }

    function fail() {
        if (unavailable) {
            return;
        }
        unavailable = true;
        timers.clearTimeout(startupTimer);
        discardPending();
        inFlight = undefined;
        worker.terminate();
        show("unavailable");
    }

    function input(isComposing = false) {
        revision++;
        discardPending();
        if (unavailable) {
            show("unavailable");
            return;
        }
        // Invalidate the displayed result before checking composition or reading a new snapshot.
        show("waiting");
        if (!composing && !isComposing) {
            debounceTimer = timers.setTimeout(() => {
                debounceTimer = undefined;
                const source = readSource();
                if (source.length > SOURCE_LIMIT_UNITS) {
                    show("over-limit");
                    return;
                }
                pending = {revision, source};
                sendPending();
            }, DEBOUNCE_MS);
        }
    }

    function sendPending() {
        if (!ready || inFlight !== undefined || pending === undefined) {
            return;
        }
        const request = pending;
        pending = undefined;
        inFlight = request.revision;
        try {
            worker.postMessage(request);
        } catch {
            fail();
            return;
        }
        show("parsing");
    }

    function receive(reply) {
        if (unavailable) {
            return;
        }
        if (reply?.kind === "ready") {
            ready = true;
            timers.clearTimeout(startupTimer);
            sendPending();
            return;
        }
        if ((reply?.kind !== "result" && reply?.kind !== "failure")
            || !Number.isSafeInteger(reply.revision) || reply.revision < 1) {
            fail();
            return;
        }
        // A stale reply can free its own slot but cannot change the current view or free a newer slot.
        if (reply.revision !== inFlight) {
            return;
        }
        inFlight = undefined;
        if (reply.revision === revision) {
            if (reply.kind === "failure") {
                show("request-error");
            } else if (isResult(reply.result)) {
                show("ready", reply.result);
            } else {
                fail();
                return;
            }
        }
        sendPending();
    }

    return {
        input,
        compositionStart() {
            composing = true;
            input();
        },
        compositionEnd() {
            composing = false;
            input();
        }
    };
}

function isResult(result) {
    if (typeof result?.treeText !== "string" || !Array.isArray(result.diagnostics)
        || !isPosition(result.omittedDiagnostics) || typeof result.treeTruncated !== "boolean") {
        return false;
    }
    for (const diagnostic of result.diagnostics) {
        if (typeof diagnostic?.severity !== "string" || typeof diagnostic.message !== "string"
            || (diagnostic.code !== null && typeof diagnostic.code !== "string")
            || !isPosition(diagnostic.startByte) || !isPosition(diagnostic.endByte)
            || !isPosition(diagnostic.startRow) || !isPosition(diagnostic.endRow)
            || !isPosition(diagnostic.startColumn) || !isPosition(diagnostic.endColumn)) {
            return false;
        }
    }
    return true;
}

function isPosition(value) {
    return Number.isSafeInteger(value) && value >= 0;
}
