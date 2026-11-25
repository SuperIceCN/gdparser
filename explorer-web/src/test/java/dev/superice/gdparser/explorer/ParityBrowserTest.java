package dev.superice.gdparser.explorer;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.TimeoutError;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import static dev.superice.gdparser.explorer.BrowserTestSupport.Response;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("browser")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ParityBrowserTest {
    private static final Duration GATE_TIMEOUT = Duration.ofSeconds(40);
    private static final Path WEB = Path.of("src", "test", "web");
    private static final String JSON = "application/json";
    private static final String JAVASCRIPT = "text/javascript";
    private static final List<String> KNOWN_CASES = List.of(
            "control-identifier-start", "script-p-identifier-start", "script-p-identifier-part", "bracket-recovery");
    private static final String STALLED_CASE = "long-incomplete-32k";

    private Playwright playwright;
    private Browser browser;
    private Path module;
    private Response defaultFixtures;
    private long moduleBytes;

    @BeforeAll
    void startBrowser() throws IOException {
        module = configuredPath("gdparser.site").resolve("gdparser.js");
        moduleBytes = Files.size(module);
        defaultFixtures = Response.file(configuredPath("gdparser.fixtures"), JSON);
        playwright = Playwright.create();
        browser = BrowserTestSupport.launch(playwright);
    }

    @AfterAll
    void closeBrowser() {
        if (playwright != null) {
            playwright.close();
        }
    }

    @Test
    void matchesTheCompleteJvmPayloadExceptTheFourRecordedUnicodeCases() throws IOException {
        var outcome = run(defaultFixtures, List.of(), null);
        System.out.println(outcome.details(moduleBytes));
        var report = requireCompleted(outcome);
        assertNull(report.error());
        assertEquals(GateCases.samples().stream().map(GateCases.Sample::name).toList(),
                report.measurements().stream().map(ParityProgress.Measurement::name).toList());
        assertTrue(report.failures().isEmpty(), () -> outcome.details(moduleBytes));
        assertEquals(KNOWN_CASES, report.knownNames());
        assertEquals(20, report.matchedCount());
    }

    @ParameterizedTest
    @ValueSource(strings = {"control-identifier-start", "valid-function"})
    void rejectsDuplicateNamesBeforeApplyingTheCaseFilter(String selected) throws IOException {
        var samples = new ArrayList<>(GateCases.samples());
        samples.add(new GateCases.Sample("control-identifier-start", "var\t\u0001x = 1\n"));

        var report = requireCompleted(run(Response.text(JSON, ParityFixtures.json(samples)), List.of(selected), null));
        assertEquals("Duplicate fixture name: control-identifier-start", report.error());
        assertTrue(report.measurements().isEmpty());
    }

    @Test
    void rejectsAnExceptionIfItsExactSourceChanges() throws IOException {
        var samples = GateCases.samples().stream().map(sample -> sample.name().equals("control-identifier-start")
                ? new GateCases.Sample(sample.name(), sample.source() + "\n") : sample).toList();

        var report = requireCompleted(run(Response.text(JSON, ParityFixtures.json(samples)),
                List.of("control-identifier-start"), null));
        assertEquals("Known Unicode case control-identifier-start is missing or its exact source changed.",
                report.error());
        assertTrue(report.measurements().isEmpty());
    }

    @Test
    void rejectsChangedPayloadsInBothStrictAndKnownCases() throws IOException {
        var report = requireCompleted(run(defaultFixtures, List.of("ascii-identifier", "bracket-recovery"),
                changeWorkerReplies(false)));
        assertNull(report.error());
        assertEquals(List.of("ascii-identifier", "bracket-recovery"),
                report.failures().stream().map(ParityProgress.Measurement::name).toList());
        assertTrue(report.failures().getFirst().differences().stream()
                .anyMatch(difference -> difference.contains("name=changed")));
        assertTrue(report.failures().getLast().differences().stream()
                .anyMatch(difference -> difference.contains("browser snapshot.diagnostics.0.code")));
        assertTrue(report.knownNames().isEmpty());
    }

    @Test
    void retainsCompletedResultsAndActiveCaseWhenTheGateTimesOut() throws IOException {
        var selected = List.of("valid-function", "control-identifier-start", "bracket-recovery", STALLED_CASE);
        try (var server = start(defaultFixtures); var context = browser.newContext()) {
            var page = context.newPage();
            page.addInitScript(changeWorkerReplies(true));
            var progress = new ParityProgress(page, gateUrl(server, selected));
            var atStall = requireReport(progress.awaitActiveCase(STALLED_CASE, GATE_TIMEOUT));
            assertEquals(STALLED_CASE, atStall.activeCase());

            var outcome = progress.await(Duration.ofMillis(200));
            var details = outcome.details(moduleBytes);
            assertInstanceOf(TimeoutError.class, outcome.browserError(), details);
            assertNull(outcome.pageError(), details);
            var report = outcome.report();
            assertNotNull(report, details);
            assertFalse(report.finished(), details);
            assertEquals(STALLED_CASE, report.activeCase(), details);
            assertEquals(1, report.matchedCount(), details);
            assertEquals(List.of("control-identifier-start"), report.knownNames(), details);
            assertEquals(List.of("bracket-recovery"),
                    report.failures().stream().map(ParityProgress.Measurement::name).toList(), details);
            assertTrue(details.contains("Browser gate failed:") && details.contains("Results are partial."), details);
            assertTrue(details.contains("browser snapshot.diagnostics.0.code"), details);
        }
    }

    private ParityProgress.Outcome run(Response fixtures, List<String> selected, String initScript) throws IOException {
        try (var server = start(fixtures); var context = browser.newContext()) {
            var page = context.newPage();
            if (initScript != null) {
                page.addInitScript(initScript);
            }
            return new ParityProgress(page, gateUrl(server, selected)).await(GATE_TIMEOUT);
        }
    }

    private BrowserTestSupport.Loopback start(Response fixtures) throws IOException {
        return BrowserTestSupport.start(Map.of(
                "/gate/", Response.file(WEB.resolve("index.html"), "text/html"),
                "/gate/gate.js", Response.file(WEB.resolve("gate.js"), JAVASCRIPT),
                "/gate/worker.js", Response.file(WEB.resolve("worker.js"), JAVASCRIPT),
                "/gate/fixtures.json", fixtures,
                "/gate/known-differences.json", Response.file(WEB.resolve("known-differences.json"), JSON),
                "/site/gdparser.js", Response.file(module, JAVASCRIPT)));
    }

    private static String gateUrl(BrowserTestSupport.Loopback server, List<String> selected) {
        var query = selected.stream().map(name -> "case=" + URLEncoder.encode(name, StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
        return server.url("/gate/" + (query.isEmpty() ? "" : "?" + query));
    }

    private ParityProgress.Report requireCompleted(ParityProgress.Outcome outcome) {
        var report = requireReport(outcome);
        assertTrue(report.finished(), () -> outcome.details(moduleBytes));
        return report;
    }

    private ParityProgress.Report requireReport(ParityProgress.Outcome outcome) {
        assertNull(outcome.browserError(), () -> outcome.details(moduleBytes));
        assertNull(outcome.pageError(), () -> outcome.details(moduleBytes));
        assertNotNull(outcome.report(), () -> outcome.details(moduleBytes));
        return outcome.report();
    }

    private static Path configuredPath(String name) {
        return Path.of(Objects.requireNonNull(System.getProperty(name), "Run this test using :explorer-web:browserTest."));
    }

    private static String changeWorkerReplies(boolean stallLongIncomplete) {
        return """
                window.Worker = class extends window.Worker {
                    postMessage(source) {
                        if (%s && source.length === 32768 && source.endsWith(",\\n")) {
                            return;
                        }
                        super.postMessage(source);
                    }
                    set onmessage(listener) {
                        super.onmessage = event => {
                            const result = event.data.result;
                            if (result?.treeText.includes("_sample_total2")) {
                                result.treeText = result.treeText.replace("_sample_total2", "changed");
                            }
                            if (result?.diagnostics[0]?.code === "parse.missing_separator.comma") {
                                result.diagnostics.reverse();
                            }
                            listener(event);
                        };
                    }
                };
                """.formatted(stallLongIncomplete);
    }
}
