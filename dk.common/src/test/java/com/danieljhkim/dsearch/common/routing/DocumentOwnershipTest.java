package com.danieljhkim.dsearch.common.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DocumentOwnershipTest {

    private static final List<String> TWO_NODES = List.of("0", "1");

    @Test
    void sameKeyAlwaysResolvesToTheSameNode() {
        String first = DocumentOwnership.ownerNodeId("tenant-a", "doc-1", TWO_NODES);

        for (int i = 0; i < 100; i++) {
            assertEquals(first, DocumentOwnership.ownerNodeId("tenant-a", "doc-1", TWO_NODES));
        }
    }

    @Test
    void ownerDoesNotDependOnNodeIdOrdering() {
        List<String> nodeIds = new ArrayList<>(List.of("0", "1", "2", "3", "4"));

        for (String documentId : documentIds(200)) {
            String expected = DocumentOwnership.ownerNodeId("tenant-a", documentId, nodeIds);
            List<String> shuffled = new ArrayList<>(nodeIds);
            Collections.shuffle(shuffled);
            assertEquals(expected, DocumentOwnership.ownerNodeId("tenant-a", documentId, shuffled));
        }
    }

    @Test
    void ownerIsPinnedToKnownValuesSoRestartsAndUpgradesKeepTheSameOwner() {
        // A gateway restart recomputes ownership from scratch; these expectations fail if the
        // hash ever changes, which would silently strand documents on their previous owner.
        assertEquals("0", DocumentOwnership.ownerNodeId("default", "doc-1", TWO_NODES));
        assertEquals("1", DocumentOwnership.ownerNodeId("default", "doc-2", TWO_NODES));
        assertEquals("1", DocumentOwnership.ownerNodeId("default", "doc-3", TWO_NODES));
        assertEquals("1", DocumentOwnership.ownerNodeId("tenant-a", "doc-1", TWO_NODES));
    }

    @Test
    void sameDocumentIdInDifferentPartitionsIsOwnedIndependently() {
        Set<String> owners = new HashSet<>();
        for (int partition = 0; partition < 50; partition++) {
            owners.add(DocumentOwnership.ownerNodeId("tenant-" + partition, "doc-1", TWO_NODES));
        }

        assertEquals(Set.of("0", "1"), owners);
    }

    @Test
    void keysAreSpreadAcrossNodes() {
        Map<String, Integer> counts = new HashMap<>();
        List<String> documentIds = documentIds(1000);
        for (String documentId : documentIds) {
            counts.merge(DocumentOwnership.ownerNodeId("tenant-a", documentId, TWO_NODES), 1, Integer::sum);
        }

        assertEquals(Set.of("0", "1"), counts.keySet());
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            assertTrue(
                    entry.getValue() > documentIds.size() / 4,
                    "node " + entry.getKey() + " owned only " + entry.getValue() + " of " + documentIds.size());
        }
    }

    @Test
    void addingANodeOnlyMovesKeysOntoTheNewNode() {
        List<String> before = List.of("0", "1", "2");
        List<String> after = List.of("0", "1", "2", "3");

        int moved = 0;
        List<String> documentIds = documentIds(1000);
        for (String documentId : documentIds) {
            String previousOwner = DocumentOwnership.ownerNodeId("tenant-a", documentId, before);
            String newOwner = DocumentOwnership.ownerNodeId("tenant-a", documentId, after);
            if (!previousOwner.equals(newOwner)) {
                assertEquals("3", newOwner, "key " + documentId + " moved between pre-existing nodes");
                moved++;
            }
        }

        // Rendezvous hashing reassigns roughly 1/N of the key space, never the whole of it.
        assertTrue(moved > 0 && moved < documentIds.size() / 2, "unexpected reassignment count: " + moved);
    }

    @Test
    void removingANodeOnlyMovesKeysItOwned() {
        List<String> before = List.of("0", "1", "2");
        List<String> after = List.of("0", "1");

        for (String documentId : documentIds(1000)) {
            String previousOwner = DocumentOwnership.ownerNodeId("tenant-a", documentId, before);
            String newOwner = DocumentOwnership.ownerNodeId("tenant-a", documentId, after);
            if (!previousOwner.equals("2")) {
                assertEquals(previousOwner, newOwner, "key " + documentId + " moved although its owner remained");
            }
        }
    }

    @Test
    void repeatedNodeIdsDoNotChangeTheOwner() {
        // Equal scores are the only case where iteration order could leak into the result,
        // and a node id repeated in the ring is the one way to produce them on demand.
        assertEquals(
                DocumentOwnership.ownerNodeId("tenant-a", "doc-1", List.of("0", "1")),
                DocumentOwnership.ownerNodeId("tenant-a", "doc-1", List.of("1", "0", "1", "0")));
    }

    @Test
    void scoreIsStableForTheSameKey() {
        assertEquals(
                DocumentOwnership.score("0", "tenant-a", "doc-1"), DocumentOwnership.score("0", "tenant-a", "doc-1"));
    }

    @Test
    void emptyRingIsRejected() {
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class, () -> DocumentOwnership.ownerNodeId("tenant-a", "doc-1", List.of()));

        assertTrue(ex.getMessage().contains("empty"));
    }

    private static List<String> documentIds(int count) {
        List<String> ids = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            ids.add("doc-" + i);
        }
        return ids;
    }
}
