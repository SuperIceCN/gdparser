import {parseForExplorer} from "./gdparser.js";

if (typeof parseForExplorer !== "function") {
    throw new TypeError("The parser module does not export a callable parser.");
}

self.onmessage = ({data}) => {
    const revision = data?.revision;
    if (!Number.isSafeInteger(revision) || revision < 1) {
        return;
    }
    if (typeof data.source !== "string") {
        self.postMessage({kind: "failure", revision});
        return;
    }
    try {
        const result = parseForExplorer(data.source);
        self.postMessage({kind: "result", revision, result});
    } catch {
        // Do not forward parser exceptions or source. Posting the result can also fail to clone.
        self.postMessage({kind: "failure", revision});
    }
};

self.postMessage({kind: "ready"});
