package dev.superice.gdparser.explorer;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.Request;
import com.microsoft.playwright.options.Cookie;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.opentest4j.AssertionFailedError;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static dev.superice.gdparser.explorer.BrowserTestSupport.Response;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("browser")
class ExplorerPageBrowserTest {
    private static final String READY = "Parse complete.";
    private static final String WAITING = "Waiting to parse…";
    private static final String PARSING = "Parsing…";
    private static final String UNAVAILABLE = "The local parser is unavailable. Reload this page to try again.";
    private static final String REQUEST_ERROR = "Could not parse this version. Edit the source to try again.";
    private static final String LIMIT = "Source exceeds the 32,768 UTF-16 code unit limit. Shorten it to resume parsing.";
    private static final Map<String, String> ASSETS = Map.of(
            "/", "text/html", "/style.css", "text/css", "/app.js", "text/javascript",
            "/controller.js", "text/javascript", "/worker.js", "text/javascript", "/gdparser.js", "text/javascript"
    );
    private static final Gson JSON = new GsonBuilder().serializeNulls().create();
    private static Playwright playwright;
    private static String probeScript;

    @BeforeAll
    static void startDriver() throws IOException {
        try (var resource = ExplorerPageBrowserTest.class.getResourceAsStream("browser-probe.js")) {
            assertNotNull(resource);
            probeScript = new String(resource.readAllBytes(), StandardCharsets.UTF_8);
        }
        playwright = Playwright.create();
    }

    @AfterAll
    static void stopDriver() {
        if (playwright != null) {
            playwright.close();
        }
    }

    @Test
    void stagedPageParsesLiveAndOnlyRequestsLocalStaticAssets() throws IOException {
        var marker = "no_egress_" + UUID.randomUUID().toString().replace('-', '_');
        try (var server = BrowserTestSupport.start(siteRoutes());
             var browser = BrowserTestSupport.launch(playwright);
             var context = browser.newContext()) {
            var requested = new ArrayList<Request>();
            var errors = new ArrayList<String>();
            context.onRequest(requested::add);
            var page = context.newPage();
            page.onPageError(errors::add);
            disableCache(context, page);
            page.navigate(server.url("/"));
            assertThat(page.getByLabel("GDScript source")).hasValue(GateCases.VALID);
            assertThat(page.locator("#source")).hasAttribute("spellcheck", "false");
            assertThat(page.locator("#tree")).isVisible();
            assertRendered(page, ExplorerProjection.parse(GateCases.VALID));
            System.out.println("Cold production page version: Chromium " + browser.version());

            fill(page, "");
            assertRendered(page, ExplorerProjection.parse(""));
            assertThat(page.locator("#source")).hasValue("");

            fill(page, GateCases.INCOMPLETE);
            assertRendered(page, ExplorerProjection.parse(GateCases.INCOMPLETE));
            assertThat(page.locator("#source")).hasValue(GateCases.INCOMPLETE);
            assertTrue(tree(page).contains("  VariableDeclaration name=b "));
            assertTrue(tree(page).contains("MISSING "));
            assertThat(page.locator("#diagnostics li")).hasCount(1);

            var repaired = "var a = 1\nvar b = 2\n";
            fill(page, repaired);
            assertRendered(page, ExplorerProjection.parse(repaired));
            assertThat(page.locator("#source")).hasValue(repaired);
            assertFalse(tree(page).contains("MISSING "));

            setSource(page, GateCases.CRLF_EMOJI);
            var normalized = "func f():\n\tvar x = \"😀中\" +";
            assertThat(page.locator("#source")).hasValue(normalized);
            assertRendered(page, ExplorerProjection.parse(normalized));
            assertThat(page.locator(".diagnostic-range"))
                    .hasText("Bytes [29, 30), row 1:19 to row 1:20");
            assertTrue(tree(page).lines().anyMatch(line -> line.contains("MISSING ")
                    && line.endsWith("[30,30) (1:20..1:20)")));

            var multiline = "var text = \"\"\"首😀\n\tfakeChild\n末\"\"\"\nvar other = 1\n";
            fill(page, multiline);
            assertRendered(page, ExplorerProjection.parse(multiline));
            assertThat(page.locator("#source")).hasValue(multiline);
            var literal = tree(page).lines().filter(line -> line.contains("fakeChild")).toList();
            assertEquals(1, literal.size());
            assertTrue(literal.getFirst().startsWith("    LiteralExpression "), literal.getFirst());
            assertTrue(literal.getFirst().contains("首😀\\n\\tfakeChild\\n末"), literal.getFirst());
            assertTrue(literal.getFirst().endsWith("[11,39) (0:11..2:6)"), literal.getFirst());
            assertTrue(tree(page).lines().anyMatch(line -> line.startsWith("  VariableDeclaration name=other ")));

            var markup = "<b id='injected-in-test'>示例</b>";
            fill(page, "var " + marker + " = \"" + markup + "\"\n");
            assertThat(page.locator("#parse-status")).hasText(READY);
            assertTrue(tree(page).contains(markup));
            assertThat(page.locator("#injected-in-test, #tree > *")).hasCount(0);
            assertTrue(errors.isEmpty(), errors.toString());
            assertBrowserTraffic(requested, server, marker);
            assertStaticTraffic(server, marker);
            System.out.println("Online HTTP page/Worker assets: " + server.requests());
        }
    }

    @Test
    void networkAuditRejectsAMarkerSentInARealCookieHeader() throws IOException {
        var marker = "cookie_probe_" + UUID.randomUUID().toString().replace('-', '_');
        try (var server = BrowserTestSupport.start(siteRoutes());
             var browser = BrowserTestSupport.launch(playwright);
             var context = browser.newContext()) {
            context.addCookies(List.of(new Cookie("acceptance_probe", marker).setUrl(server.url("/"))));
            var requests = new ArrayList<Request>();
            context.onRequest(requests::add);
            var page = context.newPage();
            page.navigate(server.url("/"));
            assertRendered(page, ExplorerProjection.parse(GateCases.VALID));
            var failure = assertThrows(AssertionFailedError.class, () -> assertBrowserTraffic(requests, server, marker));
            assertTrue(failure.getMessage().startsWith("Source marker in a browser request header"));
        }
    }

    @Test
    void renderingAuditRejectsDiagnosticsHiddenByARealStylesheet() throws IOException {
        var routes = siteRoutes();
        var css = new String(routes.get("/style.css").bytes(), StandardCharsets.UTF_8);
        routes.put("/style.css", Response.text("text/css", css + "\n#diagnostics { display: none !important; }\n"));
        try (var server = BrowserTestSupport.start(routes);
             var browser = BrowserTestSupport.launch(playwright);
             var page = browser.newPage()) {
            page.navigate(server.url("/"));
            fill(page, GateCases.INCOMPLETE);
            assertThat(page.locator("#parse-status")).hasText(READY);
            assertThat(page.locator("#diagnostics li")).hasCount(1);
            var result = ExplorerProjection.parse(GateCases.INCOMPLETE);
            var failure = assertThrows(AssertionFailedError.class, () -> assertRendered(page, result));
            assertTrue(failure.getMessage().startsWith("Diagnostics list must be visible"));
        }
    }

    @Test
    void coldPageWorksWithOnlyLoopbackReachable() throws IOException {
        try (var server = BrowserTestSupport.start(siteRoutes());
             var browser = BrowserTestSupport.launch(playwright, List.of(
                     "--proxy-server=" + server.url("/"), "--proxy-bypass-list=127.0.0.1"));
             var context = browser.newContext()) {
            context.route("**/*", route -> {
                if (route.request().url().startsWith(server.url("/"))) {
                    route.resume();
                } else {
                    route.abort();
                }
            });
            var page = context.newPage();
            disableCache(context, page);
            page.navigate(server.url("/"));
            assertRendered(page, ExplorerProjection.parse(GateCases.VALID));
            assertStaticTraffic(server, "");
        }
    }

    @Test
    void missingParserGivesAReal404AndLeavesEditorEditable() throws IOException {
        var routes = siteRoutes();
        routes.remove("/gdparser.js");
        try (var server = BrowserTestSupport.start(routes);
             var browser = BrowserTestSupport.launch(playwright);
             var page = browser.newPage()) {
            page.navigate(server.url("/"));
            assertThat(page.locator("#parse-status")).hasText(UNAVAILABLE);
            assertThat(page.locator("#source")).hasValue(GateCases.VALID);
            assertThat(page.locator("#tree")).hasText("");
            assertThat(page.locator("#diagnostics li")).hasCount(0);
            fill(page, "var still_editable = 1\n");
            assertThat(page.locator("#parse-status")).hasText(UNAVAILABLE);
            assertThat(page.locator("#source")).hasValue("var still_editable = 1\n");
            assertThat(page.locator("#tree")).hasText("");
            assertThat(page.locator("#diagnostics li")).hasCount(0);
            assertTrue(server.requests().stream().anyMatch(request -> request.path().equals("/gdparser.js")));
        }
    }

    @Test
    void staleRepliesNeverRenderAndCurrentRequestErrorRecoversWithSameWorker() throws IOException {
        try (var server = BrowserTestSupport.start(siteRoutes());
             var browser = BrowserTestSupport.launch(playwright);
             var page = browser.newPage()) {
            startGatedPage(page, server);

            var a = begin(page, "var race_a = \n");
            var before = sent(page);
            assertPendingAfterEdit(page, "var race_b = 1\n");
            advance(page, 174);
            release(page, a, false);
            assertPending(page);
            assertEquals(before, sent(page));
            advance(page, 1);
            assertEquals(a + 1, lastSent(page));
            assertCompleted(page, a + 1, "race_b", 0);
            System.out.printf("Race 1: sent %d,%d; first success released during second debounce; shown %d%n",
                    a, a + 1, a + 1);

            a = begin(page, "var queue_a = 1\n");
            before = sent(page);
            assertPendingAfterEdit(page, "var queue_b = 1\n");
            advance(page, 175);
            assertPending(page);
            assertEquals(before, sent(page));
            assertPendingAfterEdit(page, "var queue_c = \n");
            advance(page, 175);
            assertPending(page);
            assertEquals(before, sent(page));
            release(page, a, false);
            assertEquals(before.size() + 1, sent(page).size());
            assertEquals(a + 2, lastSent(page));
            assertCompleted(page, a + 2, "queue_c", 1);
            System.out.printf("Race 2: sent %d,%d; mature pending %d replaced; shown %d with its diagnostic%n",
                    a, a + 2, a + 1, a + 2);

            a = begin(page, "var stale_error = 1\n");
            assertPendingAfterEdit(page, "var after_stale_error = 1\n");
            advance(page, 174);
            release(page, a, true);
            assertPending(page);
            assertEquals(a, lastSent(page));
            advance(page, 1);
            assertEquals(a + 1, lastSent(page));
            assertCompleted(page, a + 1, "after_stale_error", 0);
            System.out.printf("Race 3: sent %d,%d; stale failure did not replace waiting; shown %d%n",
                    a, a + 1, a + 1);

            a = begin(page, "var current_error = 1\n");
            release(page, a, true);
            assertThat(page.locator("#parse-status")).hasText(REQUEST_ERROR);
            assertThat(page.locator("#tree")).hasText("");
            assertThat(page.locator("#diagnostics li")).hasCount(0);
            var recovered = begin(page, "var after_current_error = 1\n");
            assertCompleted(page, recovered, "after_current_error", 0);
            assertEquals(1, number(page.evaluate("acceptanceProbe.created.length")));
            assertEquals(0, number(page.evaluate("acceptanceProbe.terminations")));
            System.out.printf("Race 4: failed %d as a technical error; shown %d using the same Worker%n", a, recovered);
        }
    }

    @Test
    void trustedChromiumCompositionOnlyParsesTheCommittedText() throws IOException {
        try (var server = BrowserTestSupport.start(siteRoutes());
             var browser = BrowserTestSupport.launch(playwright);
             var context = browser.newContext()) {
            var page = context.newPage();
            startGatedPage(page, server);
            page.evaluate("""
                    () => {
                        const source = document.querySelector("#source");
                        window.qaComposition = [];
                        for (const type of ["compositionstart", "compositionupdate", "compositionend", "input"]) {
                            source.addEventListener(type, event => qaComposition.push({
                                type, trusted: event.isTrusted, prevented: event.defaultPrevented,
                            }));
                        }
                        source.value = "var  = 1\\n";
                        source.focus();
                        source.setSelectionRange(4, 4);
                    }
                    """);
            var before = sent(page);
            var cdp = context.newCDPSession(page);
            try {
                for (var draft : List.of("中", "中文")) {
                    cdp.send("Input.imeSetComposition", toJson(Map.of(
                            "text", draft, "selectionStart", draft.length(), "selectionEnd", draft.length())));
                    advance(page, 175);
                    assertPending(page);
                    assertEquals(before, sent(page));
                }
                cdp.send("Input.insertText", toJson(Map.of("text", "中文")));
            } finally {
                cdp.detach();
            }
            advance(page, 175);
            var committed = lastSent(page);
            assertEquals(before.size() + 1, sent(page).size());
            assertCompleted(page, committed, "中文", 0);
            assertThat(page.locator("#source")).hasValue("var 中文 = 1\n");
            // Chromium's CDP commit can report compositionend as untrusted; drafts are trusted.
            var compositionTrace = page.evaluate("qaComposition").toString();
            assertEquals(true, page.evaluate("""
                    qaComposition.every(event => !event.prevented && (event.type === "compositionend" || event.trusted))
                    """), compositionTrace);
            for (var event : List.of("compositionstart", "compositionend")) {
                assertEquals(1, number(page.evaluate("type => qaComposition.filter(e => e.type === type).length", event)));
            }
            System.out.printf("Chromium CDP composition sent draft=0, commit=1 (revision %d); OS IME not exercised%n",
                    committed);
        }
    }

    @Test
    void productionWorkerPreservesExactSourcesAndBoundsDiagnosticsBeforeCloning() throws IOException {
        try (var server = BrowserTestSupport.start(siteRoutes());
             var browser = BrowserTestSupport.launch(playwright);
             var page = browser.newPage()) {
            page.navigate(server.url("/"));
            for (var sample : List.of(
                    new GateCases.Sample("CRLF", GateCases.CRLF_EMOJI),
                    new GateCases.Sample("isolated surrogate", GateCases.LONE_SURROGATE),
                    new GateCases.Sample("diagnostic stress", GateCases.DIAGNOSTIC_STRESS, 3))) {
                var report = runDirectWorker(page, "./worker.js", sample, 8000);
                assertEquals(toJson(Map.of("kind", "ready")), report.getAsJsonObject("ready"));
                var expected = ExplorerProjection.parse(sample.source());
                var timings = new ArrayList<Double>();
                var revision = 0;
                for (var trial : report.getAsJsonArray("replies")) {
                    assertEquals(toJson(Map.of("kind", "result", "revision", ++revision, "result", expected)),
                            trial.getAsJsonObject().getAsJsonObject("message"));
                    timings.add(trial.getAsJsonObject().get("elapsedMs").getAsDouble());
                }
                var units = expected.treeText().length() + expected.diagnostics().stream().mapToInt(
                        diagnostic -> diagnostic.severity().length() + diagnostic.message().length()
                                + (diagnostic.code() == null ? 0 : diagnostic.code().length())).sum();
                assertTrue(units <= 98_304);
                assertTrue(expected.treeText().length() <= 65_536);
                assertTrue(expected.diagnostics().size() <= 200);
                if (sample.source().equals(GateCases.DIAGNOSTIC_STRESS)) {
                    assertEquals(200, expected.diagnostics().size());
                    assertEquals(1_802, expected.omittedDiagnostics());
                }
                System.out.printf("Production Worker %s: %d diagnostics + %d omitted; %d text units; ready %.1f ms; round trips %s ms%n",
                        sample.name(), expected.diagnostics().size(), expected.omittedDiagnostics(), units,
                        report.get("readyMs").getAsDouble(), timings);
            }
        }
    }

    @Test
    @Timeout(20)
    void directWorkerWaitTimesOutOnDroppedReplyAndServerRemainsUsable() throws IOException {
        var routes = siteRoutes();
        routes.put("/drop-reply.js", Response.text("text/javascript", """
                import "./worker.js";
                self.onmessage = () => {};
                """));
        try (var server = BrowserTestSupport.start(routes);
             var browser = BrowserTestSupport.launch(playwright);
             var page = browser.newPage()) {
            page.navigate(server.url("/"));
            var sample = new GateCases.Sample("dropped reply", GateCases.VALID);
            var failure = assertThrows(PlaywrightException.class, () -> runDirectWorker(page, "./drop-reply.js", sample, 200));
            assertTrue(failure.getMessage().contains("Timed out waiting for dropped reply (revision 1)"));
            assertTrue(server.requests().stream().anyMatch(request -> request.path().equals("/drop-reply.js")));
            var next = runDirectWorker(page, "./worker.js", new GateCases.Sample("after timeout", GateCases.VALID), 8000);
            assertEquals("result", next.getAsJsonArray("replies").get(0).getAsJsonObject()
                    .getAsJsonObject("message").get("kind").getAsString());
        }
    }

    private static JsonObject runDirectWorker(Page page, String path, GateCases.Sample sample, int replyDeadlineMs) {
        // Numeric code units avoid Java's UTF-8 transport replacing an unpaired surrogate.
        var input = sample.source().chars().boxed().toList();
        return toJson(page.evaluate("""
                async ({path, input, name, runs, replyDeadlineMs}) => {
                    const source = String.fromCharCode(...input);
                    const start = performance.now();
                    const worker = new Worker(path, {type: "module"});
                    const waitForMessage = (phase, deadline, send) => new Promise((resolve, reject) => {
                        let settled = false;
                        const finish = (callback, value) => {
                            if (settled) return;
                            settled = true;
                            clearTimeout(timer);
                            worker.removeEventListener("message", onMessage);
                            worker.removeEventListener("error", onError);
                            worker.removeEventListener("messageerror", onMessageError);
                            callback(value);
                        };
                        const fail = reason => finish(reject, new Error(reason));
                        const onMessage = event => finish(resolve, event.data);
                        const onError = event => fail(`Worker error waiting for ${phase}: ${event.message}`);
                        const onMessageError = () => fail(`Worker messageerror waiting for ${phase}`);
                        const timer = setTimeout(() => fail(`Timed out waiting for ${phase}`), deadline);
                        worker.addEventListener("message", onMessage);
                        worker.addEventListener("error", onError);
                        worker.addEventListener("messageerror", onMessageError);
                        try {
                            send?.();
                        } catch (error) {
                            fail(`Could not send ${phase}: ${error.message}`);
                        }
                    });
                    try {
                        const ready = await waitForMessage(`${name} ready`, 8000);
                        const readyMs = performance.now() - start;
                        const replies = [];
                        for (let revision = 1; revision <= runs; revision++) {
                            const before = performance.now();
                            const message = await waitForMessage(`${name} (revision ${revision})`, replyDeadlineMs,
                                () => worker.postMessage({revision, source}));
                            replies.push({elapsedMs: performance.now() - before, message});
                        }
                        return {ready, readyMs, replies};
                    } finally {
                        worker.terminate();
                    }
                }
                """, Map.of("path", path, "input", input, "name", sample.name(), "runs", sample.runs(),
                "replyDeadlineMs", replyDeadlineMs)));
    }

    @Test
    void measuresColdWorkerAndHeavyInputToPaintAndChecksInputBoundary() throws Exception {
        var routes = siteRoutes();
        var module = routes.get("/gdparser.js").bytes();
        System.out.printf("Staged gdparser.js: %,d bytes, SHA-256 %s; full static payload %,d bytes%n",
                module.length, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(module)),
                routes.values().stream().mapToInt(response -> response.bytes().length).sum());
        var valid = GateCases.longScript(32_768, false);
        var broken = GateCases.longScript(32_768, true);
        var dense = GateCases.DIAGNOSTIC_STRESS + valid.substring(0, 32_768 - GateCases.DIAGNOSTIC_STRESS.length());
        try (var server = BrowserTestSupport.start(routes)) {
            for (var sample : List.of(Map.entry("valid", valid), Map.entry("incomplete", broken), Map.entry("combined", dense))) {
                assertEquals(32_768, sample.getValue().length());
                var expected = ExplorerProjection.parse(sample.getValue());
                assertTrue(expected.treeTruncated());
                if (sample.getKey().equals("combined")) {
                    assertEquals(200, expected.diagnostics().size());
                    assertTrue(expected.omittedDiagnostics() > 0);
                }
                for (var trial = 1; trial <= 3; trial++) {
                    try (var browser = BrowserTestSupport.launch(playwright); var page = browser.newPage()) {
                        page.addInitScript(probeScript);
                        page.navigate(server.url("/?acceptance=timing"));
                        assertRendered(page, ExplorerProjection.parse(GateCases.VALID));
                        var measurement = measure(page, sample.getValue());
                        assertEquals(sample.getValue(), page.evaluate("acceptanceProbe.sent.at(-1).source"));
                        assertRendered(page, expected);
                        System.out.printf("Chromium %s %s 32 Ki trial %d: %s; %d tree units; %d diagnostics + %d omitted%n",
                                browser.version(), sample.getKey(), trial, measurement, expected.treeText().length(),
                                expected.diagnostics().size(), expected.omittedDiagnostics());
                        if (trial == 1 && sample.getKey().equals("valid")) {
                            assertInputBoundary(page);
                        }
                        if (trial == 3 && sample.getKey().equals("combined")) {
                            page.setViewportSize(390, 780);
                            assertEquals(true, page.evaluate("""
                                    () => {
                                        const [left, right] = document.querySelector("main").children;
                                        const tree = document.querySelector("#tree");
                                        tree.scrollTop = 400;
                                        return right.offsetTop > left.offsetTop && tree.scrollTop > 0
                                            && document.documentElement.scrollWidth === innerWidth;
                                    }
                                    """));
                        }
                    }
                }
            }
        }
    }

    private static JsonObject measure(Page page, String source) {
        return toJson(page.evaluate("""
                async source => {
                    const status = document.querySelector("#parse-status");
                    const input = document.querySelector("#source");
                    const {created, sent, incoming} = acceptanceProbe;
                    const t0 = performance.now();
                    input.value = source;
                    input.dispatchEvent(new InputEvent("input", {bubbles: true}));
                    const inputHandlerMs = performance.now() - t0;
                    await new Promise((resolve, reject) => {
                        const timer = setTimeout(() => {
                            observer.disconnect();
                            reject(Error("Timed out waiting for heavy parse: " + status.textContent));
                        }, 8_000);
                        const observer = new MutationObserver(() => {
                            if (status.textContent !== "Parse complete.") return;
                            observer.disconnect();
                            clearTimeout(timer);
                            resolve();
                        });
                        observer.observe(status, {childList: true});
                    });
                    const domMs = performance.now() - t0;
                    await new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)));
                    const paint = performance.now();
                    const latestSent = sent.at(-1).at;
                    const latestReply = incoming.at(-1).at;
                    return {
                        readyMs: incoming[0].at - created[0].start,
                        firstRoundTripMs: incoming[1].at - sent[0].at,
                        firstResultFromWorkerStartMs: incoming[1].at - created[0].start,
                        inputHandlerMs, workerRoundTripMs: latestReply - latestSent,
                        debounceAndQueueMs: latestSent - t0, domMs, paintMs: paint - t0,
                        replyToPaintMs: paint - latestReply,
                    };
                }
                """, source));
    }

    private static void assertInputBoundary(Page page) {
        var exact = "#" + "😀".repeat(16_383) + "\n";
        assertEquals(32_768, exact.length());
        setSource(page, exact);
        assertRendered(page, ExplorerProjection.parse(exact));
        var before = sent(page);
        assertEquals(exact, page.evaluate("acceptanceProbe.sent.at(-1).source"));
        setSource(page, exact + "#");
        assertThat(page.locator("#parse-status")).hasText(LIMIT);
        assertThat(page.locator("#source")).hasValue(exact + "#");
        assertThat(page.locator("#tree")).hasText("");
        assertEquals(before, sent(page));
        fill(page, "var after_limit = 1\n");
        assertRendered(page, ExplorerProjection.parse("var after_limit = 1\n"));
        assertEquals(before.size() + 1, sent(page).size());
        assertEquals(true, page.evaluate("acceptanceProbe.sent.every(req => req.keys.join(',') === 'revision,source')"));
    }

    private static void startGatedPage(Page page, BrowserTestSupport.Loopback server) {
        page.addInitScript(probeScript);
        page.navigate(server.url("/?acceptance=race"));
        advance(page, 175);
        page.waitForFunction("acceptanceProbe.held.has(1)");
        release(page, 1, false);
        assertRendered(page, ExplorerProjection.parse(GateCases.VALID));
    }

    private static void assertPendingAfterEdit(Page page, String source) {
        // Inject exactly one input event per revision, independent of Playwright fill's event count.
        setSource(page, source);
        assertPending(page);
    }

    private static void assertPending(Page page) {
        assertEquals(WAITING, page.locator("#parse-status").textContent());
        assertEquals("", tree(page));
        assertEquals(0, page.locator("#diagnostics li").count());
        assertFalse(page.locator("#diagnostics-empty").isVisible());
    }

    private static int begin(Page page, String source) {
        assertPendingAfterEdit(page, source);
        advance(page, 175);
        assertEquals(PARSING, page.locator("#parse-status").textContent());
        var revision = lastSent(page);
        page.waitForFunction("revision => acceptanceProbe.held.has(revision)", revision);
        return revision;
    }

    private static void assertCompleted(Page page, int revision, String name, int diagnosticCount) {
        page.waitForFunction("revision => acceptanceProbe.held.has(revision)", revision);
        release(page, revision, false);
        assertThat(page.locator("#parse-status")).hasText(READY);
        assertTrue(tree(page).contains("VariableDeclaration name=" + name + " "));
        assertThat(page.locator("#diagnostics li")).hasCount(diagnosticCount);
    }

    private static void advance(Page page, int milliseconds) {
        page.evaluate("time => acceptanceProbe.advance(time)", milliseconds);
    }

    private static void release(Page page, int revision, boolean fail) {
        page.evaluate("([revision, fail]) => acceptanceProbe.release(revision, fail)", List.of(revision, fail));
    }

    private static int lastSent(Page page) {
        return number(page.evaluate("acceptanceProbe.sent.at(-1).revision"));
    }

    private static List<Integer> sent(Page page) {
        return ((List<?>) page.evaluate("acceptanceProbe.sent.map(request => request.revision)"))
                .stream().map(ExplorerPageBrowserTest::number).toList();
    }

    private static void fill(Page page, String source) {
        page.locator("#source").fill(source);
    }

    private static void setSource(Page page, String source) {
        page.evaluate("""
                source => {
                    const input = document.querySelector("#source");
                    input.value = source;
                    input.dispatchEvent(new InputEvent("input", {bubbles: true}));
                }
                """, source);
    }

    private static String tree(Page page) {
        return page.locator("#tree").textContent();
    }

    private static void assertRendered(Page page, ExplorerProjection.Result expected) {
        assertThat(page.locator("#parse-status")).hasText(READY);
        assertEquals(expected.treeText(), tree(page));
        assertEquals(expected.treeTruncated(), page.locator("#tree-truncated").isVisible());
        assertEquals(expected.diagnostics().isEmpty() && expected.omittedDiagnostics() == 0,
                page.locator("#diagnostics-empty").isVisible());
        var omission = page.locator("#diagnostics-omitted");
        assertEquals(expected.omittedDiagnostics() != 0, omission.isVisible());
        if (expected.omittedDiagnostics() != 0) {
            assertTrue(omission.textContent().startsWith(expected.omittedDiagnostics() + " diagnostic"));
        }
        var rows = page.locator("#diagnostics li");
        assertThat(rows).hasCount(expected.diagnostics().size());
        if (expected.diagnostics().isEmpty()) {
            return;
        }
        assertTrue(page.locator("#diagnostics").isVisible(), "Diagnostics list must be visible");
        var rendered = toJson(Map.of("rows", rows.evaluateAll("""
                items => items.map(item => ({
                    visible: item.checkVisibility({visibilityProperty: true, opacityProperty: true}),
                    text: item.innerText,
                }))
                """))).getAsJsonArray("rows");
        for (var index = 0; index < expected.diagnostics().size(); index++) {
            var diagnostic = expected.diagnostics().get(index);
            var row = rendered.get(index).getAsJsonObject();
            assertTrue(row.get("visible").getAsBoolean(), "Diagnostic row " + index + " must be visible");
            var label = diagnostic.code() == null ? diagnostic.severity()
                    : "%s (%s)".formatted(diagnostic.severity(), diagnostic.code());
            var range = "Bytes [%d, %d), row %d:%d to row %d:%d".formatted(diagnostic.startByte(),
                    diagnostic.endByte(), diagnostic.startRow(), diagnostic.startColumn(), diagnostic.endRow(),
                    diagnostic.endColumn());
            assertEquals(visibleText(label + ": " + diagnostic.message() + " " + range),
                    visibleText(row.get("text").getAsString()));
        }
    }

    private static String visibleText(String text) {
        return text.replaceAll("\\s+", " ").strip();
    }

    private static void disableCache(BrowserContext context, Page page) {
        var cdp = context.newCDPSession(page);
        cdp.send("Network.enable");
        cdp.send("Network.setCacheDisabled", toJson(Map.of("cacheDisabled", true)));
    }

    private static void assertStaticTraffic(BrowserTestSupport.Loopback server, String marker) {
        var paths = new HashSet<String>();
        for (var request : server.requests()) {
            assertEquals("GET", request.method());
            assertEquals(0, request.bodyLength());
            assertTrue(marker.isEmpty() || !request.path().contains(marker), "Source marker in an HTTP request path");
            assertTrue(isStaticPath(request.path()), "Unexpected loopback request path");
            paths.add(request.path());
        }
        assertTrue(paths.containsAll(ASSETS.keySet()), paths.toString());
    }

    private static void assertBrowserTraffic(List<Request> requests, BrowserTestSupport.Loopback server, String marker) {
        var paths = new HashSet<String>();
        for (var request : List.copyOf(requests)) {
            assertEquals("GET", request.method());
            assertNull(request.postData(), "Browser request body must be empty");
            assertFalse(request.allHeaders().values().stream().anyMatch(value -> value.contains(marker)),
                    "Source marker in a browser request header");
            var path = URI.create(request.url()).getRawPath();
            assertTrue(path != null && isStaticPath(path) && request.url().equals(server.url(path)),
                    "Browser request must be an exact same-origin static URL");
            paths.add(path);
        }
        assertTrue(paths.containsAll(ASSETS.keySet()), paths.toString());
    }

    private static boolean isStaticPath(String path) {
        return ASSETS.containsKey(path) || path.equals("/favicon.ico");
    }

    private static Map<String, Response> siteRoutes() throws IOException {
        var site = Path.of(System.getProperty("gdparser.site"));
        var routes = new HashMap<String, Response>();
        for (var asset : ASSETS.entrySet()) {
            var url = asset.getKey();
            var filename = "/".equals(url) ? "index.html" : url.substring(1);
            routes.put(url, Response.file(site.resolve(filename), asset.getValue() + "; charset=utf-8"));
        }
        routes.put("/favicon.ico", new Response(204, "image/x-icon", new byte[0]));
        return routes;
    }

    private static int number(Object value) {
        return ((Number) value).intValue();
    }

    private static JsonObject toJson(Object value) {
        return JSON.toJsonTree(value).getAsJsonObject();
    }
}
