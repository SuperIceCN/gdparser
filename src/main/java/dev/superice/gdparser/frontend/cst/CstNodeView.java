package dev.superice.gdparser.frontend.cst;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/// Stable CST node abstraction decoupled from specific tree-sitter runtime APIs.
public interface CstNodeView {

    String type();

    CstRange range();

    boolean isNamed();

    boolean isMissing();

    boolean isError();

    boolean hasError();

    String sExpression();

    List<CstNodeView> children();

    List<CstNodeView> namedChildren();

    @Nullable CstNodeView childByField(String fieldName);

    default List<CstNodeView> namedChildrenOfType(String nodeType) {
        var filtered = new ArrayList<CstNodeView>();
        for (var child : namedChildren()) {
            if (child.type().equals(nodeType)) {
                filtered.add(child);
            }
        }
        return List.copyOf(filtered);
    }
}
