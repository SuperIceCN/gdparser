(() => {
    // Instrument the real Worker boundary without changing the staged assets.
    const mode = new URLSearchParams(location.search).get("acceptance");
    if (!mode) return;

    const NativeWorker = window.Worker;
    const now = () => performance.now();
    const probe = window.acceptanceProbe = {
        created: [], sent: [], incoming: [], released: [], terminations: 0,
        held: new Map(), now,
        release(revision, injectFailure = false) {
            const held = this.held.get(revision);
            if (!held) throw Error(`No held reply for ${revision}`);
            this.held.delete(revision);
            const [worker, reply] = held;
            this.released.push([revision, injectFailure ? "failure" : reply.kind]);
            worker.deliver(injectFailure ? {kind: "failure", revision} : reply);
        },
    };

    if (mode === "race") {
        const set = window.setTimeout.bind(window);
        const clear = window.clearTimeout.bind(window);
        const tasks = new Map();
        let elapsed = 0;
        let counter = -1;
        window.setTimeout = (fn, delay, ...args) => {
            if (delay !== 175) return set(fn, delay, ...args);
            const id = counter--;
            tasks.set(id, {fn: () => fn(...args), time: elapsed + delay});
            return id;
        };
        window.clearTimeout = id => id < 0 ? tasks.delete(id) : clear(id);
        probe.advance = delta => {
            const end = elapsed + delta;
            while (true) {
                const next = [...tasks].filter(([, task]) => task.time <= end)
                    .sort((a, b) => a[1].time - b[1].time)[0];
                if (!next) break;
                const [id, task] = next;
                elapsed = task.time;
                tasks.delete(id);
                task.fn();
            }
            elapsed = end;
        };
    }

    window.Worker = class ObservedWorker extends EventTarget {
        constructor(url, options) {
            super();
            probe.created.push({url: String(url), options, start: now()});
            this.nativeWorker = new NativeWorker(url, options);
            this.nativeWorker.addEventListener("message", event => {
                const reply = event.data;
                probe.incoming.push({kind: reply.kind, revision: reply.revision, at: now()});
                if (mode === "race" && reply.kind !== "ready") {
                    probe.held.set(reply.revision, [this, reply]);
                } else {
                    this.deliver(reply);
                }
            });
            for (const type of ["error", "messageerror"]) {
                this.nativeWorker.addEventListener(type, () => this.deliver(undefined, type));
            }
        }

        postMessage(data, ...rest) {
            probe.sent.push({...data, at: now(), keys: Object.keys(data).sort()});
            this.nativeWorker.postMessage(data, ...rest);
        }

        terminate() {
            probe.terminations++;
            this.nativeWorker.terminate();
        }

        deliver(data, type = "message") {
            const event = new MessageEvent(type, {data});
            this.dispatchEvent(event);
            this[`on${type}`]?.(event);
        }
    };
})();
