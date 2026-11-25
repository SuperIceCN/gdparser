# Browser parser parity gate

Run from the repository root with JDK 25:

```sh
./gradlew :explorer-web:installExplorerChromium --no-daemon --info --console=plain
script/run-gradle-targeted-tests.sh --gradle-task :explorer-web:test --tests GateJvmTest,ExplorerProjectionTest
script/run-gradle-targeted-tests.sh --gradle-task :explorer-web:browserTest --tests ParityBrowserTest
```

Install Chromium once, or set `PLAYWRIGHT_CHROMIUM_EXECUTABLE` to an existing Chromium executable. The `browserTest` task stages the browser module and generates the JVM reference fixtures. It runs the tagged JUnit tests with Playwright Java and headless Chromium.

`GateJvmTest` checks independent parser expectations and the projected diagnostic prefix against the complete facade result. `explorerParityFixtures` uses those same inputs to write JSON containing each source and its JVM projection. The browser gate sends the exact source to a dedicated module Worker and compares its structured-cloned reply with the JVM projection, including the entire indented tree and all diagnostic fields. The fixture transport preserves UTF-16 code units, including unpaired surrogates.

Four recorded TeaVM 0.17.0 differences involving U+0001 and U+2118 are permitted only for the exact named sources in `known-differences.json`. Each runtime must match its own full recorded reply. These cases are reported separately, not as JVM matches. Changes to either reply, any mismatch outside those fixtures, and tool failures still make the gate exit nonzero. Common ASCII and mixed CJK identifier fixtures require full equality.

The runner serves only test files, fixtures, and the exact staged `build/site/gdparser.js` over a loopback HTTP socket. It starts a fresh Worker per case, reports Worker startup and first/worst timings, and repeats the long scripts and diagnostic stress case. Bridge time includes parsing and generating the reply, before structured cloning. Round-trip time includes cloning and receipt. Reported lengths are UTF-16 code units unless labeled as bytes. None of the gate files are staged by `assemble`.

The test page publishes progress to Java as it runs. If the gate times out, the JUnit failure includes the last completed cases and the active case without waiting for a fresh browser reply. The same test class exercises duplicate fixture names, changed sources and replies, and an intentionally stalled Worker request.
