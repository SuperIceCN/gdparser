import assert from "node:assert/strict";
import {registerHooks} from "node:module";
import test from "node:test";

let fixture = 0;

async function importWorker(bridge) {
    const endpoint = new URL("../../main/web/worker.js", import.meta.url);
    endpoint.searchParams.set("fixture", fixture++);
    // Replace only the generated parser import, exercising the unchanged production endpoint.
    const hooks = registerHooks({
        resolve(specifier, context, nextResolve) {
            if (context.parentURL === endpoint.href && specifier === "./gdparser.js") {
                return {
                    url: "data:text/javascript," + encodeURIComponent(bridge),
                    shortCircuit: true
                };
            }
            return nextResolve(specifier, context);
        }
    });
    try {
        await import(endpoint.href);
    } finally {
        hooks.deregister();
    }
}

function workerScope(t, parse) {
    const messages = [];
    const worker = {
        parse,
        postMessage(message) {
            if (message.kind === "ready") {
                assert.equal(typeof worker.onmessage, "function");
            }
            messages.push(structuredClone(message));
        }
    };
    const self = Object.getOwnPropertyDescriptor(globalThis, "self");
    globalThis.self = worker;
    t.after(() => {
        if (self === undefined) {
            delete globalThis.self;
        } else {
            Object.defineProperty(globalThis, "self", self);
        }
    });
    return {worker, messages};
}

test("the staged protocol sends ready and parses each request once with no source normalization", async t => {
    const sources = [];
    const result = {
        treeText: "Script\n  MISSING",
        diagnostics: [{
            severity: "ERROR",
            code: null,
            message: "Expected an expression",
            startByte: 5,
            endByte: 5,
            startRow: 1,
            startColumn: 0,
            endRow: 1,
            endColumn: 0
        }],
        omittedDiagnostics: 2,
        treeTruncated: true
    };
    const {worker, messages} = workerScope(t, source => {
        sources.push(source);
        if (source === "throws") {
            throw new Error(source);
        }
        if (source === "not cloneable") {
            return () => source;
        }
        return result;
    });
    await importWorker("export const parseForExplorer = source => self.parse(source);");

    assert.deepEqual(messages, [{kind: "ready"}]);
    const source = "\tvar 中文 = \"\uD800💡\"\r\n ";
    for (const data of [
        {revision: 1, source},
        {revision: 2, source: 42},
        {revision: -1, source: "invalid revision"},
        null,
        {revision: 3, source: "throws"},
        {revision: 4, source: "not cloneable"},
        {revision: 5, source: ""}
    ]) {
        worker.onmessage({data});
    }
    assert.deepEqual(sources, [source, "throws", "not cloneable", ""]);
    assert.deepEqual(messages, [
        {kind: "ready"},
        {kind: "result", revision: 1, result},
        {kind: "failure", revision: 2},
        {kind: "failure", revision: 3},
        {kind: "failure", revision: 4},
        {kind: "result", revision: 5, result}
    ]);
});

test("the Worker does not signal ready if the bridge export is not callable", async t => {
    const {worker, messages} = workerScope(t);
    await assert.rejects(importWorker("export const parseForExplorer = 42;"), TypeError);
    assert.equal(worker.onmessage, undefined);
    assert.deepEqual(messages, []);
});
