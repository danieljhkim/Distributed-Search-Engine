package com.danieljhkim.dsearch.indexnode.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.danieljhkim.dsearch.common.enums.RoutingStrategy;
import com.danieljhkim.dsearch.common.exception.NodeUnavailableException;
import com.danieljhkim.dsearch.common.grpc.NodeClient;
import com.danieljhkim.dsearch.common.grpc.NodeClientManager;
import com.danieljhkim.dsearch.common.model.SearchDocument;
import com.danieljhkim.dsearch.common.model.SearchResult;
import com.danieljhkim.dsearch.ml.embedding.TextEmbedder;
import com.danieljhkim.dsearch.proto.cluster.NodeRole;
import com.danieljhkim.dsearch.proto.common.SearchType;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Two index nodes backed by real Lucene indexes, routed by the same ownership
 * rule the gateway uses.
 *
 * <p>The gateway's mutation path is exactly what happens here: resolve the owner
 * of {@code (partitionId, documentId)}, call that node, and adjust its shard doc
 * count only after the node confirms the mutation.
 */
class DocumentOwnershipRoutingIntegrationTest {

    private static final String PARTITION_ID = "tenant-a";
    private static final String DOCUMENT_ID = "doc-1";
    private static final Duration LONG_FLUSH_INTERVAL = Duration.ofHours(1);
    private static final TextEmbedder FAKE_EMBEDDER = ignored -> new float[] {1.0f, 0.0f, 0.0f};

    @TempDir
    Path tempDir;

    private final List<ManagedChannel> channels = new ArrayList<>();
    private final Map<String, IndexManager> indexNodes = new HashMap<>();
    private NodeClientManager<IndexManager> clientManager;

    @BeforeEach
    void startCluster() {
        Map<String, NodeClient<IndexManager>> clients = new HashMap<>();
        for (String nodeId : List.of("0", "1")) {
            IndexManager indexManager =
                    new IndexManager(tempDir.resolve("node-" + nodeId), 100, LONG_FLUSH_INTERVAL, null, FAKE_EMBEDDER);
            indexNodes.put(nodeId, indexManager);
            clients.put(nodeId, new NodeClient<>(nodeId, indexManager, channel(), "localhost", 5100));
        }
        // LEAST_LOADED is deliberate: it is the read strategy this cluster is configured with,
        // and mutations must ignore it.
        clientManager =
                new NodeClientManager<>(clients, RoutingStrategy.LEAST_LOADED, NodeRole.NODE_ROLE_INDEX, ch -> null);
    }

    @AfterEach
    void stopCluster() throws IOException {
        for (IndexManager indexManager : indexNodes.values()) {
            indexManager.close();
        }
        for (ManagedChannel channel : channels) {
            channel.shutdownNow();
        }
    }

    @Test
    void reindexingTheSameKeyUnderChangingLoadKeepsExactlyOneLogicalDocument() throws IOException {
        Set<String> owners = new LinkedHashSet<>();

        for (int version = 1; version <= 5; version++) {
            NodeClient<IndexManager> owner = clientManager.ownerClient(PARTITION_ID, DOCUMENT_ID);
            owners.add(owner.getNodeId());

            index(owner, document(DOCUMENT_ID, "Version " + version, "shared marker version" + version));

            // Skew the load so that least-loaded placement would send the next write to the
            // peer node, which is what used to leave two copies of the document behind.
            owner.getOrCreateShardState(PARTITION_ID).getDocCount().addAndGet(1_000);
        }

        assertEquals(1, owners.size(), "document changed owner mid-flight: " + owners);
        assertEquals(1, totalHitsAcrossCluster("shared"), "document exists on more than one node");
        assertEquals(1, totalHitsAcrossCluster("version5"), "latest version is not searchable exactly once");
        assertEquals(0, totalHitsAcrossCluster("version4"), "a superseded version survived on another node");

        String ownerNodeId = owners.iterator().next();
        String otherNodeId = ownerNodeId.equals("0") ? "1" : "0";
        assertEquals(1, totalHits(indexNodes.get(ownerNodeId), "shared"));
        assertEquals(0, totalHits(indexNodes.get(otherNodeId), "shared"));
    }

    @Test
    void deleteRoutesToTheOwnerAndRemovesTheDocumentFromSubsequentSearches() throws IOException {
        NodeClient<IndexManager> owner = clientManager.ownerClient(PARTITION_ID, DOCUMENT_ID);
        index(owner, document(DOCUMENT_ID, "Version 1", "shared marker version1"));
        assertEquals(1, totalHitsAcrossCluster("shared"));
        assertEquals(1, owner.getShardDocCount(PARTITION_ID));

        NodeClient<IndexManager> deleteTarget = clientManager.ownerClient(PARTITION_ID, DOCUMENT_ID);
        assertEquals(owner.getNodeId(), deleteTarget.getNodeId());
        deleteTarget.getStub().deleteDocument(PARTITION_ID, DOCUMENT_ID);
        deleteTarget.getStub().commitAll();
        deleteTarget.decrementDocFromShard(PARTITION_ID);

        assertEquals(0, totalHitsAcrossCluster("shared"));
        assertEquals(0, deleteTarget.getShardDocCount(PARTITION_ID));
    }

    @Test
    void deletingAMissingDocumentIsIdempotentAndLeavesTheClusterEmpty() throws IOException {
        NodeClient<IndexManager> owner = clientManager.ownerClient(PARTITION_ID, "never-indexed");
        owner.getStub().deleteDocument(PARTITION_ID, "never-indexed");
        owner.getStub().commitAll();

        assertEquals(0, totalHitsAcrossCluster("shared"));
        assertEquals(0, owner.getShardDocCount(PARTITION_ID));
    }

    @Test
    void mutationsForAnUnavailableOwnerAreRejectedRatherThanSentToThePeer() throws IOException {
        NodeClient<IndexManager> owner = clientManager.ownerClient(PARTITION_ID, DOCUMENT_ID);
        index(owner, document(DOCUMENT_ID, "Version 1", "shared marker version1"));

        owner.setActive(false);

        assertThrows(NodeUnavailableException.class, () -> clientManager.ownerClient(PARTITION_ID, DOCUMENT_ID));
        // The surviving node has not been handed a second copy of the document.
        assertEquals(1, totalHitsAcrossCluster("shared"));
    }

    @Test
    void documentsAreSpreadOverBothNodesAndEachIsOwnedByExactlyOne() throws IOException {
        Set<String> usedNodes = new HashSet<>();
        for (int i = 0; i < 40; i++) {
            String documentId = "doc-" + i;
            NodeClient<IndexManager> owner = clientManager.ownerClient(PARTITION_ID, documentId);
            usedNodes.add(owner.getNodeId());
            index(owner, document(documentId, "Title " + i, "shared marker body" + i));
        }

        assertEquals(Set.of("0", "1"), usedNodes, "one node was never used, so routing is not balanced");
        assertEquals(40, totalHitsAcrossCluster("shared"));
        assertNotEquals(0, totalHits(indexNodes.get("0"), "shared"));
        assertNotEquals(0, totalHits(indexNodes.get("1"), "shared"));

        for (int i = 0; i < 40; i++) {
            String documentId = "doc-" + i;
            String ownerNodeId = clientManager.ownerNodeId(PARTITION_ID, documentId);
            String otherNodeId = ownerNodeId.equals("0") ? "1" : "0";
            assertEquals(1, totalHits(indexNodes.get(ownerNodeId), "body" + i));
            assertEquals(0, totalHits(indexNodes.get(otherNodeId), "body" + i));
        }
    }

    @Test
    void ownershipIsUnchangedWhenTheClusterIsRebuiltAsAfterARestart() throws IOException {
        List<String> ownersBefore = ownersOf(clientManager);

        // Same configured node ids, brand new manager and clients: a gateway restart.
        Map<String, NodeClient<IndexManager>> clients = new HashMap<>();
        for (Map.Entry<String, IndexManager> entry : indexNodes.entrySet()) {
            clients.put(
                    entry.getKey(), new NodeClient<>(entry.getKey(), entry.getValue(), channel(), "localhost", 5100));
        }
        NodeClientManager<IndexManager> restarted =
                new NodeClientManager<>(clients, RoutingStrategy.LEAST_LOADED, NodeRole.NODE_ROLE_INDEX, ch -> null);

        assertEquals(ownersBefore, ownersOf(restarted));

        // And a document written before the restart is still updated in place afterwards.
        index(clientManager.ownerClient(PARTITION_ID, DOCUMENT_ID), document(DOCUMENT_ID, "Before", "shared before"));
        index(restarted.ownerClient(PARTITION_ID, DOCUMENT_ID), document(DOCUMENT_ID, "After", "shared after"));

        assertEquals(1, totalHitsAcrossCluster("shared"));
        assertEquals(1, totalHitsAcrossCluster("after"));
        assertEquals(0, totalHitsAcrossCluster("before"));
    }

    private static List<String> ownersOf(NodeClientManager<IndexManager> manager) {
        List<String> owners = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            owners.add(manager.ownerNodeId(PARTITION_ID, "doc-" + i));
        }
        return owners;
    }

    /** Mirrors the gateway mutation path: call the owner, then count the confirmed write. */
    private static void index(NodeClient<IndexManager> owner, SearchDocument document) throws IOException {
        owner.getStub().indexDocument(PARTITION_ID, document);
        owner.getStub().commitAll();
        owner.incrementDocToShard(PARTITION_ID);
    }

    private long totalHitsAcrossCluster(String query) throws IOException {
        long total = 0;
        for (IndexManager indexManager : indexNodes.values()) {
            total += totalHits(indexManager, query);
        }
        return total;
    }

    private static long totalHits(IndexManager indexManager, String query) throws IOException {
        SearchResult result = indexManager.searchDocument(PARTITION_ID, query, 100, 0, SearchType.BM25);
        return result.getTotalHits();
    }

    private static SearchDocument document(String id, String title, String content) {
        return new SearchDocument(id, Map.of("title", title, "content", content));
    }

    private ManagedChannel channel() {
        // gRPC channels connect lazily, so this never opens a socket; the clients in this test
        // invoke their node in-process through the stub.
        ManagedChannel channel = ManagedChannelBuilder.forAddress("localhost", 5000)
                .usePlaintext()
                .build();
        channels.add(channel);
        return channel;
    }
}
