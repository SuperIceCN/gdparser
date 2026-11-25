package dev.superice.gdparser.explorer;

import com.microsoft.playwright.Page;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.options.WaitUntilState;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BooleanSupplier;

final class ParityProgress {
    private final Page page;
    private Report latest;
    private String pageError;
    private PlaywrightException navigationError;

    ParityProgress(Page page, String url) {
        this.page = page;
        page.exposeFunction("publishExplorerParityProgress", args -> {
            latest = Report.read((Map<?, ?>) args[0]);
            return null;
        });
        page.onPageError(error -> pageError = error);
        page.onCrash(_ -> pageError = "The parity page crashed.");
        try {
            page.navigate(url, new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
        } catch (PlaywrightException error) {
            navigationError = error;
        }
    }

    Outcome await(Duration timeout) {
        return await(timeout, () -> latest != null && latest.finished());
    }

    Outcome awaitActiveCase(String name, Duration timeout) {
        return await(timeout, () -> latest != null && (name.equals(latest.activeCase()) || latest.finished()));
    }

    private Outcome await(Duration timeout, BooleanSupplier condition) {
        if (navigationError != null) {
            return new Outcome(latest, pageError, navigationError);
        }
        try {
            page.waitForCondition(() -> pageError != null || condition.getAsBoolean(),
                    new Page.WaitForConditionOptions().setTimeout(timeout.toMillis()));
            return new Outcome(latest, pageError, null);
        } catch (PlaywrightException error) {
            // The last published snapshot remains usable even when the browser no longer responds.
            return new Outcome(latest, pageError, error);
        }
    }

    record Outcome(Report report, String pageError, PlaywrightException browserError) {
        String details(long moduleBytes) {
            var text = new StringBuilder();
            if (browserError != null) {
                text.append("Browser gate failed: ").append(browserError.getMessage()).append('\n');
            }
            if (pageError != null) {
                text.append("Parity page failed: ").append(pageError).append('\n');
            }
            if (report == null) {
                return text.append("The browser did not publish a parity report.").toString();
            }
            if (report.error() != null) {
                text.append("Browser gate failed: ").append(report.error()).append('\n');
            }
            text.append(report.browser()).append('\n');
            text.append(String.format(Locale.ROOT, "Staged gdparser.js: %,d bytes%n", moduleBytes));
            for (var measurement : report.measurements()) {
                measurement.describeTo(text);
            }
            if (report.activeCase() != null) {
                text.append("Interrupted while running ").append(report.activeCase()).append(".\n");
            }
            if (!report.finished()) {
                text.append("Results are partial.\n");
            }
            text.append("%d completed: %d matched the JVM, %d known Unicode differences, %d unexpected failures."
                    .formatted(report.measurements().size(), report.matchedCount(), report.knownNames().size(),
                            report.failures().size()));
            return text.toString();
        }
    }

    record Report(String browser, boolean finished, String activeCase, String error,
                  List<Measurement> measurements) {
        static Report read(Map<?, ?> fields) {
            return new Report((String) fields.get("browser"), Boolean.TRUE.equals(fields.get("finished")),
                    (String) fields.get("activeCase"), (String) fields.get("error"),
                    ((List<?>) fields.get("measurements")).stream()
                            .map(fieldsOfCase -> new Measurement((Map<?, ?>) fieldsOfCase))
                            .toList());
        }

        long matchedCount() {
            return measurements.stream()
                    .filter(measurement -> measurement.differences().isEmpty() && measurement.known().isEmpty())
                    .count();
        }

        List<String> knownNames() {
            return measurements.stream()
                    .filter(measurement -> measurement.differences().isEmpty() && !measurement.known().isEmpty())
                    .map(Measurement::name)
                    .toList();
        }

        List<Measurement> failures() {
            return measurements.stream().filter(measurement -> !measurement.differences().isEmpty()).toList();
        }
    }

    record Measurement(Map<?, ?> fields) {
        String name() {
            return (String) fields.get("name");
        }

        List<String> differences() {
            return strings("differences");
        }

        List<String> known() {
            return strings("knownDifference");
        }

        private List<String> strings(String key) {
            return fields.get(key) instanceof List<?> values
                    ? values.stream().map(String.class::cast).toList() : List.of();
        }

        private void describeTo(StringBuilder text) {
            text.append(String.format(Locale.ROOT, "%s: %,d input units",
                    name(), ((Number) fields.get("inputUnits")).intValue()));
            if (fields.get("readyMs") instanceof Number ready) {
                text.append(String.format(Locale.ROOT, "; ready %.1f ms", ready.doubleValue()));
            }
            var times = ((List<?>) fields.get("times")).stream()
                    .map(fieldsOfTrial -> Trial.read((Map<?, ?>) fieldsOfTrial))
                    .toList();
            if (!times.isEmpty()) {
                var first = times.getFirst();
                var worstBridge = times.stream().mapToDouble(Trial::bridgeMs).max().orElseThrow();
                var worstRoundTrip = times.stream().mapToDouble(Trial::roundTripMs).max().orElseThrow();
                text.append(String.format(Locale.ROOT,
                        "; bridge first/worst %.1f/%.1f ms; round trip first/worst %.1f/%.1f ms (%d runs)",
                        first.bridgeMs(), worstBridge, first.roundTripMs(), worstRoundTrip, times.size()));
            }
            text.append('\n');
            if (!differences().isEmpty()) {
                differences().forEach(difference -> text.append("  ").append(difference).append('\n'));
                return;
            }
            if (!known().isEmpty()) {
                text.append("  Known Unicode difference (recorded JVM and browser replies verified):\n");
                known().forEach(difference -> text.append("    ").append(difference).append('\n'));
            }
            text.append(String.format(Locale.ROOT,
                    "  %d diagnostics, %d omitted; %,d tree units, %,d reply text units; tree truncated: %s%n",
                    integer("diagnostics"), integer("omittedDiagnostics"), integer("treeUnits"),
                    integer("replyTextUnits"), fields.get("treeTruncated")));
        }

        private int integer(String key) {
            return ((Number) fields.get(key)).intValue();
        }
    }

    private record Trial(double bridgeMs, double roundTripMs) {
        static Trial read(Map<?, ?> fields) {
            return new Trial(((Number) fields.get("bridgeMs")).doubleValue(),
                    ((Number) fields.get("roundTripMs")).doubleValue());
        }
    }
}
