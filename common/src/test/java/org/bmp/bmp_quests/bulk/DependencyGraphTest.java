package org.bmp.bmp_quests.bulk;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class DependencyGraphTest {
    @Test void acceptsSharedDependenciesAndDisconnectedNodes() {
        assertTrue(DependencyGraph.isAcyclic(Map.of(1L, Set.of(3L), 2L, Set.of(3L), 4L, Set.of())));
    }
    @Test void rejectsSelfReferenceAndLongCycle() {
        assertFalse(DependencyGraph.isAcyclic(Map.of(1L, Set.of(1L))));
        assertFalse(DependencyGraph.isAcyclic(Map.of(1L, Set.of(2L), 2L, Set.of(3L), 3L, Set.of(1L))));
    }
    @Test void handlesLargeBooksWithoutRecursiveStack() {
        Map<Long, Set<Long>> graph = new HashMap<>();
        for (long i = 0; i < 100000; i++) graph.put(i, Set.of(i + 1));
        assertTrue(DependencyGraph.isAcyclic(graph));
        graph.put(100000L, Set.of(0L));
        assertFalse(DependencyGraph.isAcyclic(graph));
    }
}
