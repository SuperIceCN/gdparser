package dev.superice.gdparser.frontend.cst;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/// Detects `ERROR`/`MISSING` nodes from a CST view tree.
public final class CstErrorDetector {

    private CstErrorDetector() {
    }

    public static @NotNull List<CstStructuralIssue> collect(CstNodeView root) {
        var issues = new ArrayList<CstStructuralIssue>();
        var stack = new ArrayDeque<CstNodeView>();
        stack.push(root);

        while (!stack.isEmpty()) {
            var node = stack.pop();
            if (node.isError()) {
                issues.add(new CstStructuralIssue(CstIssueKind.ERROR, node.type(), node.range()));
            }
            if (node.isMissing()) {
                issues.add(new CstStructuralIssue(CstIssueKind.MISSING, node.type(), node.range()));
            }
            for (var child : node.children()) {
                stack.push(child);
            }
        }

        return List.copyOf(issues);
    }

    public static boolean hasIssues(CstNodeView root) {
        return !collect(root).isEmpty();
    }
}
