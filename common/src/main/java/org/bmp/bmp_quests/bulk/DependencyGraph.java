package org.bmp.bmp_quests.bulk;

import java.util.*;

public final class DependencyGraph {
    private DependencyGraph() {}

    /** Iterative DFS avoids a stack overflow on large imported books. */
    public static boolean isAcyclic(Map<Long, Set<Long>> graph) {
        Map<Long, Integer> indegree = new HashMap<>();
        graph.forEach((id, edges) -> {
            indegree.putIfAbsent(id, 0);
            edges.forEach(edge -> indegree.merge(edge, 1, Integer::sum));
        });
        ArrayDeque<Long> queue = new ArrayDeque<>();
        indegree.forEach((id, count) -> { if (count == 0) queue.add(id); });
        int visited = 0;
        while (!queue.isEmpty()) {
            long id = queue.remove();
            visited++;
            for (long edge : graph.getOrDefault(id, Set.of())) {
                if (indegree.merge(edge, -1, Integer::sum) == 0) queue.add(edge);
            }
        }
        return visited == indegree.size();
    }
}
