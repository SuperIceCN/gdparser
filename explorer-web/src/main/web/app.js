import {createExplorerController, SOURCE_LIMIT_UNITS} from "./controller.js";

const textarea = document.getElementById("source");
const statusOutput = document.getElementById("parse-status");
const diagnosticsList = document.getElementById("diagnostics");
const noDiagnostics = document.getElementById("diagnostics-empty");
const rangeKey = document.getElementById("range-key");
const diagnosticsOmitted = document.getElementById("diagnostics-omitted");
const tree = document.getElementById("tree");
const treeTruncated = document.getElementById("tree-truncated");

const statusText = {
    waiting: "Waiting to parse…",
    parsing: "Parsing…",
    ready: "Parse complete.",
    "request-error": "Could not parse this version. Edit the source to try again.",
    unavailable: "The local parser is unavailable. Reload this page to try again.",
    "over-limit": `Source exceeds the ${SOURCE_LIMIT_UNITS.toLocaleString("en")} UTF-16 code unit limit. Shorten it to resume parsing.`,
};

function diagnosticItem(diagnostic) {
    const item = document.createElement("li");
    const label = document.createElement("strong");
    label.textContent = diagnostic.code ? `${diagnostic.severity} (${diagnostic.code})` : diagnostic.severity;

    const message = document.createElement("span");
    message.className = "diagnostic-message";
    message.textContent = diagnostic.message;

    const range = document.createElement("span");
    range.className = "diagnostic-range";
    range.textContent = `Bytes [${diagnostic.startByte}, ${diagnostic.endByte}), row ${diagnostic.startRow}:${diagnostic.startColumn} to row ${diagnostic.endRow}:${diagnostic.endColumn}`;

    item.append(label, ": ", message, range);
    return item;
}

function render({phase, result}) {
    if (statusOutput.textContent !== statusText[phase]) {
        statusOutput.textContent = statusText[phase];
    }

    if (phase === "ready") {
        const {diagnostics, omittedDiagnostics} = result;
        diagnosticsList.replaceChildren(...diagnostics.map(diagnosticItem));
        diagnosticsList.hidden = rangeKey.hidden = diagnostics.length === 0;
        noDiagnostics.hidden = diagnostics.length !== 0 || omittedDiagnostics > 0;
        diagnosticsOmitted.hidden = omittedDiagnostics === 0;
        diagnosticsOmitted.textContent = omittedDiagnostics === 1
            ? "1 diagnostic was omitted."
            : `${omittedDiagnostics} diagnostics were omitted.`;
        tree.textContent = result.treeText;
        treeTruncated.hidden = !result.treeTruncated;
    } else {
        diagnosticsList.replaceChildren();
        diagnosticsList.hidden = rangeKey.hidden = noDiagnostics.hidden = diagnosticsOmitted.hidden = true;
        tree.textContent = "";
        treeTruncated.hidden = true;
    }
}

function start() {
    textarea.value = "func f():\n\tpass\n";

    let worker;
    try {
        worker = new Worker("./worker.js", {type: "module"});
    } catch {
        render({phase: "unavailable"});
        return;
    }

    const controller = createExplorerController({worker, readSource: () => textarea.value, render});
    textarea.addEventListener("input", event => controller.input(event.isComposing));
    textarea.addEventListener("compositionstart", () => controller.compositionStart());
    textarea.addEventListener("compositionend", () => controller.compositionEnd());
    controller.input();
}

start();
