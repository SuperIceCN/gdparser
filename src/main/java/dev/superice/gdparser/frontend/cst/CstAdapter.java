package dev.superice.gdparser.frontend.cst;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.treesitter.TSNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/// Builds immutable `CstNodeView` snapshots from `TSNode` trees.
public final class CstAdapter {

    private CstAdapter() {
    }

    public static @NotNull CstNodeView fromNode(TSNode node) {
        if (node == null || node.isNull()) {
            throw new IllegalArgumentException("Root TSNode must not be null");
        }
        return snapshot(node);
    }

    private static CstNodeView snapshot(TSNode node) {
        var children = new ArrayList<CstNodeView>();
        var namedChildren = new ArrayList<CstNodeView>();
        var fieldChildren = new LinkedHashMap<String, CstNodeView>();

        var childCount = node.getChildCount();
        for (var index = 0; index < childCount; index++) {
            var childNode = node.getChild(index);
            if (childNode == null || childNode.isNull()) {
                continue;
            }
            var childView = snapshot(childNode);
            children.add(childView);

            if (childNode.isNamed()) {
                namedChildren.add(childView);
            }

            var fieldName = node.getFieldNameForChild(index);
            if (fieldName != null && !fieldName.isBlank() && !fieldChildren.containsKey(fieldName)) {
                fieldChildren.put(fieldName, childView);
            }
        }

        return new ImmutableCstNodeView(
                node.getType(),
                new CstRange(
                        node.getStartByte(),
                        node.getEndByte(),
                        new CstPoint(node.getStartPoint().getRow(), node.getStartPoint().getColumn()),
                        new CstPoint(node.getEndPoint().getRow(), node.getEndPoint().getColumn())
                ),
                node.isNamed(),
                node.isMissing(),
                node.isError(),
                node.hasError(),
                node.toString(),
                List.copyOf(children),
                List.copyOf(namedChildren),
                Map.copyOf(fieldChildren)
        );
    }

    private record ImmutableCstNodeView(
            String type,
            CstRange range,
            boolean isNamed,
            boolean isMissing,
            boolean isError,
            boolean hasError,
            String sExpression,
            List<CstNodeView> children,
            List<CstNodeView> namedChildren,
            Map<String, CstNodeView> fieldChildren
    ) implements CstNodeView {

        @Override
        public @Nullable CstNodeView childByField(String fieldName) {
            return fieldChildren.get(fieldName);
        }
    }
}
