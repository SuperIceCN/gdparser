open module gdparser {
    requires org.jetbrains.annotations;
    requires tree_sitter;

    exports dev.superice.gdparser.frontend.ast;
    exports dev.superice.gdparser.frontend.cst;
    exports dev.superice.gdparser.infra.treesitter;
    exports dev.superice.gdparser.frontend.lowering;
    exports dev.superice.gdparser.frontend.serialize;
}