import { parseForExplorer } from "../site/gdparser.js";

if (typeof parseForExplorer !== "function") {
    throw new TypeError("The staged module does not export parseForExplorer.");
}

self.onmessage = ({data: source}) => {
    try {
        const start = performance.now();
        const result = parseForExplorer(source);
        const bridgeMs = performance.now() - start;
        self.postMessage({result, bridgeMs});
    } catch (error) {
        self.postMessage({error: String(error), stack: error?.stack?.split("\n").slice(0, 12).join("\n")});
    }
};
self.postMessage({ready: true});
