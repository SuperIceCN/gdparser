package dev.superice.gdparser.infra.treesitter;

import dev.superice.gdparser.frontend.cst.CstAdapter;
import dev.superice.gdparser.frontend.cst.CstNodeView;
import org.treesitter.TSLanguage;
import org.treesitter.TSParser;
import org.treesitter.TSNode;

/// Minimal parser facade for GDScript parse flow.
public final class GdParserFacade {

    private final TSLanguage language;

    public GdParserFacade(TSLanguage language) {
        this.language = language;
    }

    public static GdParserFacade withDefaultLanguage() {
        return new GdParserFacade(GdLanguageLoader.load());
    }

    public GdParseSnapshot parseSnapshot(String source) {
        var root = parseRootNode(source);
        return new GdParseSnapshot(root.getType(), root.toString(), root.hasError());
    }

    public CstNodeView parseCstRoot(String source) {
        return CstAdapter.fromNode(parseRootNode(source));
    }

    private TSNode parseRootNode(String source) {
        var parser = new TSParser();
        if (!parser.setLanguage(language)) {
            throw new IllegalStateException("Failed to set parser language: " + GdLanguageLoader.SYMBOL_NAME);
        }
        var tree = parser.parseString(null, source);
        if (tree == null) {
            throw new IllegalStateException("Parser returned null tree for source input");
        }
        return tree.getRootNode();
    }
}
