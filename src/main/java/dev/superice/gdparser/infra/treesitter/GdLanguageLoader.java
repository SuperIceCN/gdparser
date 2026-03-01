package dev.superice.gdparser.infra.treesitter;

import org.jetbrains.annotations.NotNull;
import org.treesitter.TSLanguage;

import java.io.IOException;
import java.io.InputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/// Loads the tree-sitter-gdscript language symbol for tree-sitter-ng.
public final class GdLanguageLoader {

    public static final String LIBRARY_BASE_NAME = "tree-sitter-gdscript";
    public static final String SYMBOL_NAME = "tree_sitter_gdscript";
    public static final String PROP_RESOURCE_DIR = "gdparser.gdscript.resourceDir";
    public static final String PROP_NATIVE_LIB_DIR = "gdparser.gdscript.nativeLibDir";
    public static final String PROP_NATIVE_LIB_PATH = "gdparser.gdscript.nativeLibPath";

    private static volatile TSLanguage cached;

    private GdLanguageLoader() {
    }

    public static @NotNull TSLanguage load() {
        var value = cached;
        if (value != null) {
            return value;
        }
        synchronized (GdLanguageLoader.class) {
            value = cached;
            if (value != null) {
                return value;
            }
            cached = loadInternal();
            return cached;
        }
    }

    static void clearCacheForTests() {
        cached = null;
    }

    private static TSLanguage loadInternal() {
        var failures = new ArrayList<String>();
        var osArch = normalizedOs() + "-" + normalizedArch();
        for (var attempt : lookupCandidates()) {
            try {
                var language = loadLanguageFromSymbol(attempt.lookup());
                GdLanguageAbiChecker.verify(language, SYMBOL_NAME);
                return language;
            } catch (RuntimeException | LinkageError exception) {
                failures.add(attempt.description() + " -> " + exception.getMessage());
            }
        }

        var mappedName = System.mapLibraryName(LIBRARY_BASE_NAME);
        var message = String.format(
                "Failed to load '%s' from mapped library '%s'. Checked managed resource directory (%s), java.library.path, explicit properties (%s/%s), and classpath fallback. osArch=%s, failures=%s",
                SYMBOL_NAME,
                mappedName,
                PROP_RESOURCE_DIR,
                PROP_NATIVE_LIB_DIR,
                PROP_NATIVE_LIB_PATH,
                osArch,
                failures
        );
        throw new IllegalStateException(message);
    }

    private static TSLanguage loadLanguageFromSymbol(SymbolLookup lookup) {
        var symbol = lookup.find(SYMBOL_NAME)
                .orElseThrow(() -> new IllegalStateException("Symbol not found: " + SYMBOL_NAME));
        var descriptor = FunctionDescriptor.of(ValueLayout.ADDRESS);
        var downcall = Linker.nativeLinker().downcallHandle(symbol, descriptor);
        var ptr = invokeLanguageFactory(downcall);
        if (ptr == 0) {
            throw new IllegalStateException("Language symbol returned null pointer: " + SYMBOL_NAME);
        }
        return new LoadedLanguage(ptr);
    }

    private static long invokeLanguageFactory(MethodHandle downcall) {
        try {
            return ((MemorySegment) downcall.invokeExact()).address();
        } catch (RuntimeException | Error exception) {
            throw exception;
        } catch (Throwable throwable) {
            throw new IllegalStateException("Failed to invoke language factory symbol: " + SYMBOL_NAME, throwable);
        }
    }

    private static List<LookupAttempt> lookupCandidates() {
        var attempts = new ArrayList<LookupAttempt>();
        var arena = Arena.global();
        var osArch = normalizedOs() + "-" + normalizedArch();
        var mappedName = System.mapLibraryName(LIBRARY_BASE_NAME);

        var managedPath = resolveManagedLibraryPath(mappedName, osArch);
        tryAddLookup(attempts, "managed resource directory path: " + managedPath, () ->
                SymbolLookup.libraryLookup(managedPath, arena)
        );

        tryAddLookup(attempts, "java.library.path lookup: " + mappedName, () ->
                SymbolLookup.libraryLookup(mappedName, arena)
        );

        var explicitPath = System.getProperty(PROP_NATIVE_LIB_PATH);
        if (explicitPath != null && !explicitPath.isBlank()) {
            var path = Path.of(explicitPath);
            tryAddLookup(attempts, "explicit file path: " + path, () ->
                    SymbolLookup.libraryLookup(path, arena)
            );
        }

        var explicitDir = System.getProperty(PROP_NATIVE_LIB_DIR);
        if (explicitDir != null && !explicitDir.isBlank()) {
            var path = Path.of(explicitDir).resolve(mappedName);
            tryAddLookup(attempts, "explicit directory path: " + path, () ->
                    SymbolLookup.libraryLookup(path, arena)
            );
        }

        var classpathPath = extractClasspathNativeToTemp(mappedName, osArch);
        if (classpathPath != null) {
            tryAddLookup(attempts, "classpath extracted path: " + classpathPath, () ->
                    SymbolLookup.libraryLookup(classpathPath, arena)
            );
        }

        return List.copyOf(attempts);
    }

    private static void tryAddLookup(List<LookupAttempt> attempts, String description, LookupFactory factory) {
        try {
            attempts.add(new LookupAttempt(description, factory.create()));
        } catch (RuntimeException _) {
            // Ignore and continue with fallback lookup strategies.
        }
    }

    private static Path resolveManagedLibraryPath(String mappedName, String osArch) {
        var managedDir = resolveConfiguredResourceDir();
        var directPath = managedDir.resolve(mappedName);
        if (Files.exists(directPath)) {
            return directPath;
        }

        var osArchPath = managedDir.resolve(osArch).resolve(mappedName);
        if (Files.exists(osArchPath)) {
            return osArchPath;
        }

        if (managedDir.getFileName() != null && managedDir.getFileName().toString().equals(osArch)) {
            return extractClasspathNativeToTarget(managedDir.resolve(mappedName), mappedName, osArch);
        }
        return extractClasspathNativeToTarget(osArchPath, mappedName, osArch);
    }

    private static Path resolveConfiguredResourceDir() {
        var configured = System.getProperty(PROP_RESOURCE_DIR);
        if (configured == null || configured.isBlank()) {
            return Path.of("").toAbsolutePath().normalize().resolve("native");
        }
        return Path.of(configured).toAbsolutePath().normalize();
    }

    private static Path extractClasspathNativeToTarget(Path targetPath, String mappedName, String osArch) {
        var stream = openClasspathNativeStream(mappedName, osArch);
        if (stream == null) {
            throw new IllegalStateException("Classpath native resource not found for " + osArch + "/" + mappedName);
        }
        try (stream) {
            var parent = targetPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.copy(stream, targetPath, StandardCopyOption.REPLACE_EXISTING);
            return targetPath;
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to extract native resource to: " + targetPath, exception);
        }
    }

    private static Path extractClasspathNativeToTemp(String mappedName, String osArch) {
        var stream = openClasspathNativeStream(mappedName, osArch);
        if (stream == null) {
            return null;
        }
        try (stream) {
            var tempDir = Files.createTempDirectory("gdscript-native-");
            tempDir.toFile().deleteOnExit();
            var extracted = tempDir.resolve(mappedName);
            Files.copy(stream, extracted, StandardCopyOption.REPLACE_EXISTING);
            extracted.toFile().deleteOnExit();
            return extracted;
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to extract native resource to temp directory", exception);
        }
    }

    private static InputStream openClasspathNativeStream(String mappedName, String osArch) {
        var directPath = "/" + osArch + "/" + mappedName;
        var direct = GdLanguageLoader.class.getResourceAsStream(directPath);
        if (direct != null) {
            return direct;
        }
        var legacyPath = "/native/" + osArch + "/" + mappedName;
        return GdLanguageLoader.class.getResourceAsStream(legacyPath);
    }

    private static @NotNull String normalizedOs() {
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

    private static @NotNull String normalizedArch() {
        var arch = System.getProperty("os.arch", "unknown").toLowerCase(Locale.ROOT);
        return switch (arch) {
            case "x86_64", "amd64" -> "x86_64";
            case "aarch64", "arm64" -> "aarch64";
            default -> arch;
        };
    }

    @FunctionalInterface
    private interface LookupFactory {
        SymbolLookup create();
    }

    private record LookupAttempt(String description, SymbolLookup lookup) {
    }

    private static final class LoadedLanguage extends TSLanguage {

        private LoadedLanguage(long ptr) {
            super(ptr);
        }

        @Override
        public TSLanguage copy() {
            return new LoadedLanguage(copyPtr());
        }
    }
}
