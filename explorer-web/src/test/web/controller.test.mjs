import assert from "node:assert/strict";
import test from "node:test";
import {createExplorerController, SOURCE_LIMIT_UNITS} from "../../main/web/controller.js";

const cleanResult = {
    treeText: "Script",
    diagnostics: [],
    omittedDiagnostics: 0,
    treeTruncated: false
};
const damagedResult = {
    treeText: "Script\n  MISSING",
    diagnostics: [{
        severity: "ERROR",
        code: "parse.missing",
        message: "Expected an expression",
        startByte: 5,
        endByte: 5,
        startRow: 1,
        startColumn: 0,
        endRow: 1,
        endColumn: 0
    }],
    omittedDiagnostics: 3,
    treeTruncated: true
};

function fakeClock() {
    let now = 0;
    let nextId = 0;
    const jobs = new Map();
    return {
        setTimeout(callback, delay) {
            const id = nextId++;
            jobs.set(id, {callback, due: now + delay});
            return id;
        },
        clearTimeout(id) {
            jobs.delete(id);
        },
        advance(ms) {
            const stop = now + ms;
            while (true) {
                const next = [...jobs].filter(([, job]) => job.due <= stop)
                    .sort(([, a], [, b]) => a.due - b.due)[0];
                if (next === undefined) {
                    break;
                }
                const [id, {due, callback}] = next;
                now = due;
                jobs.delete(id);
                callback();
            }
            now = stop;
        }
    };
}

function setup({postFails = false} = {}) {
    let source = "";
    let reads = 0;
    let terminations = 0;
    const sent = [];
    const rendered = [];
    const clock = fakeClock();
    const worker = {
        postMessage(message) {
            if (postFails) {
                throw new Error("Cannot post a request");
            }
            sent.push(structuredClone(message));
        },
        terminate() {
            terminations++;
        }
    };
    const controller = createExplorerController({
        worker,
        readSource() {
            reads++;
            return source;
        },
        render: value => rendered.push(value),
        timers: clock
    });
    const receive = data => worker.onmessage({data: structuredClone(data)});
    return {
        worker,
        clock,
        controller,
        sent,
        rendered,
        get reads() {
            return reads;
        },
        get terminations() {
            return terminations;
        },
        get view() {
            return rendered.at(-1);
        },
        setSource(value) {
            source = value;
        },
        edit(value, isComposing) {
            source = value;
            controller.input(isComposing);
        },
        receive,
        ready: () => receive({kind: "ready"}),
        result: (revision, result = cleanResult) => receive({kind: "result", revision, result}),
        failure: revision => receive({kind: "failure", revision})
    };
}

test("uses a 175 ms tail debounce, reads the current value unmodified, and submits empty text", () => {
    const editor = setup();
    editor.ready();
    const source = "\tvar 变量 = \"\uD800💡\"\r\n ";
    editor.edit("first");
    editor.clock.advance(100);
    editor.edit("not yet the final value");
    editor.clock.advance(174);
    assert.equal(editor.reads, 0);
    assert.deepEqual(editor.sent, []);
    assert.deepEqual(editor.view, {phase: "waiting", revision: 2});
    editor.setSource(source);
    editor.clock.advance(1);
    assert.equal(editor.reads, 1);
    assert.deepEqual(editor.sent, [{revision: 2, source}]);
    assert.deepEqual(editor.view, {phase: "parsing", revision: 2});
    editor.result(2);
    assert.deepEqual(editor.view, {phase: "ready", revision: 2, result: cleanResult});

    editor.edit("");
    assert.deepEqual(editor.view, {phase: "waiting", revision: 3});
    editor.clock.advance(175);
    assert.deepEqual(editor.sent.at(-1), {revision: 3, source: ""});
    editor.result(3);
    editor.clock.advance(10_000);
    assert.deepEqual(editor.view, {phase: "ready", revision: 3, result: cleanResult});
    assert.equal(editor.terminations, 0);
});

test("delayed startup sends only the latest mature revision as soon as it is ready", () => {
    const editor = setup();
    editor.edit("sample");
    editor.clock.advance(175);
    editor.edit("latest");
    editor.clock.advance(175);
    assert.equal(editor.reads, 2);
    assert.deepEqual(editor.sent, []);
    assert.deepEqual(editor.view, {phase: "waiting", revision: 2});

    editor.ready();
    assert.deepEqual(editor.sent, [{revision: 2, source: "latest"}]);
    editor.result(2, damagedResult);
    assert.deepEqual(editor.view, {phase: "ready", revision: 2, result: damagedResult});
});

test("a new edit invalidates a mature startup revision even if ready arrives during debounce", () => {
    const editor = setup();
    editor.edit("A");
    editor.clock.advance(175);
    editor.edit("B");
    editor.ready();
    editor.clock.advance(174);
    assert.deepEqual(editor.sent, []);
    assert.deepEqual(editor.view, {phase: "waiting", revision: 2});
    editor.clock.advance(1);
    assert.deepEqual(editor.sent, [{revision: 2, source: "B"}]);
});

test("startup stops after ten seconds without ready, and late ready cannot restart it", () => {
    const editor = setup();
    editor.edit("sample");
    editor.clock.advance(9_999);
    assert.deepEqual(editor.view, {phase: "waiting", revision: 1});
    editor.clock.advance(1);
    assert.deepEqual(editor.view, {phase: "unavailable", revision: 1});
    editor.ready();
    editor.edit("new edit");
    editor.clock.advance(175);
    assert.deepEqual(editor.sent, []);
    assert.deepEqual(editor.view, {phase: "unavailable", revision: 2});
    assert.equal(editor.reads, 1);
    assert.equal(editor.terminations, 1);
});

test("A finishing during B's debounce releases its slot without displaying A", () => {
    const editor = setup();
    editor.ready();
    editor.edit("A");
    editor.clock.advance(175);
    editor.edit("B");
    editor.clock.advance(174);
    const count = editor.rendered.length;
    editor.result(1, damagedResult);
    assert.equal(editor.rendered.length, count);
    assert.deepEqual(editor.sent, [{revision: 1, source: "A"}]);
    assert.deepEqual(editor.view, {phase: "waiting", revision: 2});
    editor.clock.advance(1);
    assert.deepEqual(editor.sent.at(-1), {revision: 2, source: "B"});
    editor.result(2);
    assert.deepEqual(editor.view, {phase: "ready", revision: 2, result: cleanResult});
});

test("sends A and C, then holds D while C is active despite duplicate and mismatched replies", () => {
    const editor = setup();
    editor.ready();
    editor.edit("A");
    editor.clock.advance(175);
    editor.edit("B");
    editor.clock.advance(175);
    editor.edit("C");
    editor.clock.advance(175);
    assert.deepEqual(editor.sent, [{revision: 1, source: "A"}]);

    editor.result(1, damagedResult);
    assert.deepEqual(editor.sent, [{revision: 1, source: "A"}, {revision: 3, source: "C"}]);
    assert.deepEqual(editor.view, {phase: "parsing", revision: 3});
    editor.edit("D");
    editor.clock.advance(175);
    editor.result(1);
    editor.failure(1);
    editor.result(4);
    editor.ready();
    assert.equal(editor.sent.length, 2);
    assert.deepEqual(editor.view, {phase: "waiting", revision: 4});

    editor.result(3);
    assert.deepEqual(editor.sent.at(-1), {revision: 4, source: "D"});
    editor.result(4, damagedResult);
    const count = editor.rendered.length;
    editor.failure(4);
    editor.result(3);
    assert.equal(editor.rendered.length, count);
    assert.deepEqual(editor.view, {phase: "ready", revision: 4, result: damagedResult});
});

test("an old failure is ignored; a current failure clears the view and the next edit can succeed", () => {
    const editor = setup();
    editor.ready();
    editor.edit("A");
    editor.clock.advance(175);
    editor.edit("B");
    editor.clock.advance(175);
    editor.failure(1);
    assert.deepEqual(editor.view, {phase: "parsing", revision: 2});
    assert.deepEqual(editor.sent.at(-1), {revision: 2, source: "B"});
    assert.equal(editor.rendered.some(({phase}) => phase === "request-error"), false);

    editor.failure(2);
    assert.deepEqual(editor.view, {phase: "request-error", revision: 2});
    editor.edit("C");
    editor.clock.advance(175);
    editor.result(3);
    assert.deepEqual(editor.view, {phase: "ready", revision: 3, result: cleanResult});
    assert.equal(editor.terminations, 0);
});

test("IME start cancels mature pending text, suppresses composition updates, and debounces the end", () => {
    const editor = setup();
    editor.ready();
    editor.edit("A");
    editor.clock.advance(175);
    editor.edit("pending before composition");
    editor.clock.advance(175);
    editor.controller.compositionStart();
    assert.deepEqual(editor.view, {phase: "waiting", revision: 3});
    editor.result(1);
    editor.edit("part 1", true);
    editor.clock.advance(175);
    editor.edit("part 2", false);
    editor.clock.advance(175);
    assert.deepEqual(editor.sent, [{revision: 1, source: "A"}]);
    assert.equal(editor.reads, 2);
    assert.deepEqual(editor.view, {phase: "waiting", revision: 5});

    editor.setSource("组合完成");
    editor.controller.compositionEnd();
    editor.clock.advance(174);
    assert.equal(editor.sent.length, 1);
    editor.edit("组合完成");
    editor.clock.advance(174);
    assert.equal(editor.sent.length, 1);
    editor.clock.advance(1);
    assert.deepEqual(editor.sent.at(-1), {revision: 7, source: "组合完成"});
    editor.result(7);
    assert.deepEqual(editor.view, {phase: "ready", revision: 7, result: cleanResult});
});

test("an input event marked composing also cancels an earlier debounce", () => {
    const editor = setup();
    editor.ready();
    editor.edit("queued");
    editor.clock.advance(174);
    editor.edit("partial", true);
    editor.clock.advance(175);
    assert.equal(editor.reads, 0);
    assert.deepEqual(editor.sent, []);
    assert.deepEqual(editor.view, {phase: "waiting", revision: 2});
    editor.setSource("completed");
    editor.controller.compositionEnd();
    editor.clock.advance(175);
    assert.deepEqual(editor.sent, [{revision: 3, source: "completed"}]);
});

test("uses an inclusive 32 Ki UTF-16 limit including pairs, and an over-limit edit stays visible", () => {
    const editor = setup();
    editor.ready();
    assert.equal(SOURCE_LIMIT_UNITS, 32 * 1024);
    const atLimit = "💡".repeat(SOURCE_LIMIT_UNITS / 2);
    editor.edit(atLimit);
    editor.clock.advance(175);
    assert.deepEqual(editor.sent, [{revision: 1, source: atLimit}]);

    editor.edit(atLimit + " ");
    editor.clock.advance(175);
    assert.deepEqual(editor.view, {phase: "over-limit", revision: 2});
    editor.result(1);
    assert.deepEqual(editor.view, {phase: "over-limit", revision: 2});
    assert.equal(editor.sent.length, 1);

    editor.edit(" ".repeat(SOURCE_LIMIT_UNITS));
    editor.clock.advance(175);
    assert.deepEqual(editor.sent.at(-1), {revision: 3, source: " ".repeat(SOURCE_LIMIT_UNITS)});
    editor.result(3);
    assert.deepEqual(editor.view, {phase: "ready", revision: 3, result: cleanResult});
});

test("a postMessage exception marks the worker unavailable without rendering a parse or leaking details", () => {
    const editor = setup({postFails: true});
    editor.ready();
    editor.edit("source");
    editor.clock.advance(175);
    assert.deepEqual(editor.rendered, [
        {phase: "waiting", revision: 1},
        {phase: "unavailable", revision: 1}
    ]);
    editor.edit("later");
    editor.clock.advance(175);
    assert.deepEqual(editor.view, {phase: "unavailable", revision: 2});
    assert.equal(editor.reads, 1);
    assert.equal(editor.terminations, 1);
});

for (const event of ["onerror", "onmessageerror"]) {
    test(`${event} marks the worker unavailable and discards a pending edit`, () => {
        const editor = setup();
        editor.ready();
        editor.edit("in flight");
        editor.clock.advance(175);
        editor.edit("pending");
        editor.clock.advance(175);
        editor.worker[event]();
        editor.result(1);
        editor.worker[event]();
        assert.deepEqual(editor.sent, [{revision: 1, source: "in flight"}]);
        assert.deepEqual(editor.view, {phase: "unavailable", revision: 2});
        assert.equal(editor.rendered.filter(({phase}) => phase === "unavailable").length, 1);
        assert.equal(editor.terminations, 1);
    });
}

test("a startup Worker error immediately cancels debounce and the startup timeout", () => {
    const editor = setup();
    editor.edit("not sent");
    editor.worker.onerror();
    editor.clock.advance(10_000);
    assert.deepEqual(editor.rendered, [
        {phase: "waiting", revision: 1},
        {phase: "unavailable", revision: 1}
    ]);
    assert.deepEqual(editor.sent, []);
    assert.equal(editor.reads, 0);
    assert.equal(editor.terminations, 1);
});

test("a malformed envelope or current result is unavailable, not a syntax error", () => {
    for (const message of [
        null,
        {kind: "unknown"},
        {kind: "failure", revision: "1"},
        {kind: "result", revision: 1, result: null},
        {kind: "result", revision: 1, result: {...cleanResult, omittedDiagnostics: -1}},
        {kind: "result", revision: 1, result: {...cleanResult, diagnostics: [{}]}},
        {kind: "result", revision: 1, result: {...cleanResult, diagnostics: Array(1)}}
    ]) {
        const editor = setup();
        editor.ready();
        editor.edit("source");
        editor.clock.advance(175);
        editor.receive(message);
        assert.deepEqual(editor.view, {phase: "unavailable", revision: 1});
        assert.equal(editor.terminations, 1);
    }
});
