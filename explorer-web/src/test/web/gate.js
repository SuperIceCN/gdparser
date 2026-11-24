function describe(value) {
    if (typeof value === "string") {
        return JSON.stringify(value.length > 180 ? `${value.slice(0, 180)}…` : value);
    }
    if (value !== null && typeof value === "object") {
        return Array.isArray(value) ? `array of length ${value.length}` : "object";
    }
    return String(value);
}

function firstDifference(expected, actual, path) {
    if (expected === actual) {
        return null;
    }
    if (expected === null || actual === null || typeof expected !== "object" || typeof actual !== "object"
        || Array.isArray(expected) !== Array.isArray(actual)) {
        return `${path}: expected ${describe(expected)}; got ${describe(actual)}`;
    }
    if (Array.isArray(expected) && expected.length !== actual.length) {
        return `${path}.length: expected ${expected.length}; got ${actual.length}`;
    }
    const expectedKeys = Object.keys(expected);
    const actualKeys = Object.keys(actual);
    if (actualKeys.length !== expectedKeys.length || expectedKeys.some(key => !Object.hasOwn(actual, key))) {
        return `${path} keys: expected ${expectedKeys}; got ${actualKeys}`;
    }
    for (const key of expectedKeys) {
        const difference = firstDifference(expected[key], actual[key], `${path}.${key}`);
        if (difference) {
            return difference;
        }
    }
    return null;
}

function payloadDifferences(expected, actual) {
    if (actual === null || typeof actual !== "object") {
        return [`Expected an object, got ${describe(actual)}`];
    }
    const {treeText: expectedTree, ...expectedFields} = expected;
    const {treeText: actualTree, ...actualFields} = actual;
    const differences = [firstDifference(expectedFields, actualFields, "reply")].filter(Boolean);
    if (firstDifference(expected.diagnostics, actual.diagnostics, "diagnostics")) {
        differences.push(`JVM diagnostics: ${diagnosticDetails(expected.diagnostics)}`);
        differences.push(`Browser diagnostics: ${diagnosticDetails(actual.diagnostics)}`);
    }
    if (typeof actualTree !== "string") {
        differences.push(`reply.treeText: expected a string, got ${describe(actualTree)}`);
    } else if (expectedTree !== actualTree) {
        const expectedLines = expectedTree.split("\n");
        const actualLines = actualTree.split("\n");
        let index = 0;
        while (expectedLines[index] === actualLines[index]) {
            index++;
        }
        differences.push(`tree line ${index + 1}: JVM ${describe(expectedLines[index])}; `
            + `browser ${describe(actualLines[index])}`);
    }
    return differences;
}

function diagnosticDetails(diagnostics) {
    if (!Array.isArray(diagnostics)) {
        return describe(diagnostics);
    }
    const details = diagnostics.slice(0, 5).map(({severity, code, message, startByte, endByte}) =>
        `${severity} ${code} [${startByte},${endByte}): ${describe(message)}`);
    if (details.length < diagnostics.length) {
        details.push(`and ${diagnostics.length - details.length} more`);
    }
    return details.length > 0 ? details.join("; ") : "none";
}

async function measure({name, source, expected, runs}, known) {
    const measurement = {name, inputUnits: source.length, times: [], differences: []};
    const start = performance.now();
    const worker = new Worker("./worker.js", {type: "module"});
    try {
        const nextMessage = () => new Promise((resolve, reject) => {
            const timer = setTimeout(() => reject(new Error("Worker timed out")), 10000);
            worker.onmessage = event => {
                clearTimeout(timer);
                resolve(event.data);
            };
            worker.onerror = event => {
                clearTimeout(timer);
                reject(new Error(event.message || "Worker failed to start"));
            };
            worker.onmessageerror = () => {
                clearTimeout(timer);
                reject(new Error("Worker reply could not be decoded"));
            };
        });
        const ready = await nextMessage();
        if (ready.ready !== true) {
            throw new Error("Expected Worker ready");
        }
        measurement.readyMs = performance.now() - start;
        for (let run = 0; run < runs; run++) {
            const started = performance.now();
            const reply = nextMessage();
            worker.postMessage(source);
            const {result, bridgeMs, error, stack} = await reply;
            const roundTripMs = performance.now() - started;
            if (error) {
                throw new Error(stack ? `${error}\n${stack}` : error);
            }
            measurement.times.push({bridgeMs, roundTripMs});
            const differences = payloadDifferences(expected, result);
            if (known) {
                const drift = [
                    firstDifference(known.jvm, expected, "JVM snapshot"),
                    firstDifference(known.browser, result, "browser snapshot")
                ].filter(Boolean);
                if (drift.length > 0 || differences.length === 0) {
                    measurement.differences.push("Known Unicode case changed from its recorded behavior.", ...drift);
                    break;
                }
                measurement.knownDifference = differences;
            } else if (differences.length > 0) {
                measurement.differences.push(...differences);
                break;
            }
            measurement.diagnostics = result.diagnostics.length;
            measurement.omittedDiagnostics = result.omittedDiagnostics;
            measurement.treeTruncated = result.treeTruncated;
            measurement.treeUnits = result.treeText.length;
            measurement.replyTextUnits = result.treeText.length + result.diagnostics.reduce(
                (sum, {severity, code, message}) => sum + severity.length + (code?.length ?? 0) + message.length, 0);
        }
    } catch (error) {
        measurement.differences.push(error.message);
    } finally {
        worker.terminate();
    }
    return measurement;
}

async function readJson(url) {
    const response = await fetch(url);
    if (!response.ok) {
        throw new Error(`Could not load ${url}: HTTP ${response.status}`);
    }
    return response.json();
}

function publish(report) {
    window.publishExplorerParityProgress?.(report);
}

async function run(report) {
    const [cases, knownDifferences] = await Promise.all([
        readJson("./fixtures.json"),
        readJson("./known-differences.json")
    ]);
    const samplesByName = new Map();
    for (const sample of cases) {
        if (samplesByName.has(sample.name)) {
            throw new Error(`Duplicate fixture name: ${sample.name}`);
        }
        samplesByName.set(sample.name, sample);
    }
    const knownBySample = new Map();
    for (const [name, known] of Object.entries(knownDifferences)) {
        const sample = samplesByName.get(name);
        if (!sample || sample.source !== known.source) {
            throw new Error(`Known Unicode case ${name} is missing or its exact source changed.`);
        }
        knownBySample.set(sample, known);
    }
    const selected = new URLSearchParams(location.search).getAll("case");
    for (const name of selected) {
        if (!samplesByName.has(name)) {
            throw new Error(`No fixture named ${name}`);
        }
    }
    for (const sample of samplesByName.values()) {
        if (!selected.length || selected.includes(sample.name)) {
            report.activeCase = sample.name;
            publish(report);
            report.measurements.push(await measure(sample, knownBySample.get(sample)));
            report.activeCase = null;
            publish(report);
        }
    }
}

const report = {browser: navigator.userAgent, measurements: [], activeCase: null, finished: false};
window.parityReport = report;
publish(report);
run(report).catch(error => { report.error = error.message; }).finally(() => {
    report.finished = true;
    document.querySelector("p").textContent = "Parser parity gate finished.";
    publish(report);
});
