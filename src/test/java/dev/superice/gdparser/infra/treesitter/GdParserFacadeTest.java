package dev.superice.gdparser.infra.treesitter;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@SuppressWarnings("deprecation") // The legacy backend remains a regression reference.
class GdParserFacadeTest {

    private static final Path TEST_RESOURCE_DIR = Path.of("tmp", "native").toAbsolutePath().normalize();

    @BeforeAll
    static void setUpResourceDir() throws IOException {
        GdLanguageLoader.clearCacheForTests();
        clearDirectory(TEST_RESOURCE_DIR);
        System.setProperty(GdLanguageLoader.PROP_RESOURCE_DIR, TEST_RESOURCE_DIR.toString());
        System.clearProperty(GdTreeSitterRuntimeBootstrap.PROP_TREE_SITTER_LIB);
    }

    @AfterAll
    static void tearDownResourceDir() {
        GdLanguageLoader.clearCacheForTests();
        System.clearProperty(GdLanguageLoader.PROP_RESOURCE_DIR);
        System.clearProperty(GdTreeSitterRuntimeBootstrap.PROP_TREE_SITTER_LIB);
    }

    @Test
    @Order(1)
    void bootstrapSetsTreeSitterLibAndExtractsRuntimeLibraryToManagedResourceDirectory() {
        var extractedRuntime = GdTreeSitterRuntimeBootstrap.runtimeLibraryPath(TEST_RESOURCE_DIR);

        assertFalse(Files.exists(extractedRuntime), "Runtime library should not exist before bootstrap");

        GdTreeSitterRuntimeBootstrap.initialize();

        assertEquals(TEST_RESOURCE_DIR.toString(), System.getProperty(GdTreeSitterRuntimeBootstrap.PROP_TREE_SITTER_LIB));
        assertTrue(Files.exists(extractedRuntime), "Runtime library should be extracted to managed resource directory");
    }

    @Test
    @Order(2)
    void extractLibraryFromClasspathWhenManagedResourceDirectoryMissingLibrary() {
        ensureTreeSitterRuntimeReady();
        var mappedName = System.mapLibraryName(GdLanguageLoader.LIBRARY_BASE_NAME);
        var extractedLibrary = TEST_RESOURCE_DIR.resolve(osArch()).resolve(mappedName);

        assertFalse(Files.exists(extractedLibrary), "Library should not exist before loader initialization");

        var language = GdLanguageLoader.load();

        assertTrue(language.abiVersion() > 0, "Language ABI should be available after loading");
        assertTrue(Files.exists(extractedLibrary), "Library should be extracted to managed resource directory");
    }

    @Test
    @Order(3)
    void parseMinimalReadyFunctionWithoutErrors() {
        ensureTreeSitterRuntimeReady();
        var source = "func _ready(): pass";
        var facade = GdParserFacade.withDefaultLanguage();

        var snapshot = facade.parseSnapshot(source);

        assertEquals("source", snapshot.rootType());
        assertFalse(snapshot.hasError(), () -> "Expected no parse errors, got S-expression: " + snapshot.sExpression());
        assertTrue(snapshot.sExpression().contains("function_definition"));
    }

    @Test
    @Order(4)
    void concurrentParsesOnSharedFacadeShouldProduceIdenticalResults() throws Exception {
        // The facade holds no mutable parse state (a fresh TSParser per call), so concurrent
        // parse calls on one shared instance must behave identically to sequential calls.
        ensureTreeSitterRuntimeReady();
        var facade = GdParserFacade.withDefaultLanguage();
        var source = "func f():\n\tvar x = node.pa + 1\n\treturn x";
        var expectedSexpr = facade.parseSnapshot(source).sExpression();
        var expectedContext = facade.parseCompletionContext(source, 24);

        var threadCount = 8;
        var iterations = 25;
        var pool = Executors.newFixedThreadPool(threadCount);
        try {
            var futures = new ArrayList<Future<?>>();
            for (var thread = 0; thread < threadCount; thread++) {
                futures.add(pool.submit(() -> {
                    for (var iteration = 0; iteration < iterations; iteration++) {
                        assertEquals(expectedSexpr, facade.parseSnapshot(source).sExpression());
                        assertEquals(expectedContext, facade.parseCompletionContext(source, 24));
                        assertEquals(expectedSexpr, facade.parseCstRoot(source).sExpression());
                    }
                }));
            }
            for (var future : futures) {
                future.get();
            }
        } catch (ExecutionException exception) {
            throw new AssertionError("Concurrent parse failed", exception.getCause());
        } finally {
            pool.shutdownNow();
        }
    }

    private static void clearDirectory(Path directory) throws IOException {
        if (Files.exists(directory)) {
            try (var walk = Files.walk(directory)) {
                for (var path : walk.sorted(Comparator.reverseOrder()).toList()) {
                    if (!path.equals(directory)) {
                        Files.deleteIfExists(path);
                    }
                }
            }
        }
        Files.createDirectories(directory);
    }

    private static String osArch() {
        return normalizedOs() + "-" + normalizedArch();
    }

    private static String normalizedOs() {
        var osName = System.getProperty("os.name", "unknown").toLowerCase(Locale.ROOT);
        if (osName.contains("win")) {
            return "windows";
        }
        if (osName.contains("mac") || osName.contains("darwin")) {
            return "macos";
        }
        if (osName.contains("nux") || osName.contains("nix")) {
            return "linux";
        }
        return "unknown";
    }

    private static String normalizedArch() {
        var arch = System.getProperty("os.arch", "unknown").toLowerCase(Locale.ROOT);
        return switch (arch) {
            case "x86_64", "amd64" -> "x86_64";
            case "aarch64", "arm64" -> "aarch64";
            default -> arch;
        };
    }

    private static void ensureTreeSitterRuntimeReady() {
        try {
            GdTreeSitterRuntimeBootstrap.initialize();
            var _ = org.treesitter.TSParser.TREE_SITTER_LANGUAGE_VERSION;
        } catch (LinkageError error) {
            fail("Failed to initialize tree-sitter-ng runtime", error);
        }
    }
}
