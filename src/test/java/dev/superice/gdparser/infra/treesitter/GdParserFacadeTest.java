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
import java.util.Comparator;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class GdParserFacadeTest {

    private static final Path TEST_RESOURCE_DIR = Path.of("tmp", "native").toAbsolutePath().normalize();

    @BeforeAll
    static void setUpResourceDir() throws IOException {
        GdLanguageLoader.clearCacheForTests();
        clearDirectory(TEST_RESOURCE_DIR);
        System.setProperty(GdLanguageLoader.PROP_RESOURCE_DIR, TEST_RESOURCE_DIR.toString());
    }

    @AfterAll
    static void tearDownResourceDir() {
        GdLanguageLoader.clearCacheForTests();
        System.clearProperty(GdLanguageLoader.PROP_RESOURCE_DIR);
    }

    @Test
    @Order(1)
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
    @Order(2)
    void parseMinimalReadyFunctionWithoutErrors() {
        ensureTreeSitterRuntimeReady();
        var source = "func _ready(): pass";
        var facade = GdParserFacade.withDefaultLanguage();

        var snapshot = facade.parseSnapshot(source);

        assertEquals("source", snapshot.rootType());
        assertFalse(snapshot.hasError(), () -> "Expected no parse errors, got S-expression: " + snapshot.sExpression());
        assertTrue(snapshot.sExpression().contains("function_definition"));
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
            var _ = org.treesitter.TSParser.TREE_SITTER_LANGUAGE_VERSION;
        } catch (LinkageError error) {
            fail("Failed to initialize tree-sitter-ng runtime", error);
        }
    }
}
