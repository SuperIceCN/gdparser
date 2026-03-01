package dev.superice.gdparser.infra.treesitter;

import org.treesitter.TSLanguage;
import org.treesitter.TSParser;

/// ABI compatibility guard for loading tree-sitter language libraries.
public final class GdLanguageAbiChecker {

    private GdLanguageAbiChecker() {
    }

    public static void verify(TSLanguage language, String symbolName) {
        var abiVersion = language.abiVersion();
        var minCompatible = TSParser.TREE_SITTER_MIN_COMPATIBLE_LANGUAGE_VERSION;
        var maxCompatible = TSParser.TREE_SITTER_LANGUAGE_VERSION;
        if (abiVersion < minCompatible || abiVersion > maxCompatible) {
            var message = String.format(
                    "Incompatible Tree-sitter ABI for symbol '%s': language ABI=%d, supported range=[%d, %d]",
                    symbolName,
                    abiVersion,
                    minCompatible,
                    maxCompatible
            );
            throw new IllegalStateException(message);
        }
    }
}
