package com.danieljhkim.dsearch.querynode.grpc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.danieljhkim.dsearch.common.enums.RoutingStrategy;
import com.danieljhkim.dsearch.common.grpc.NodeClient;
import com.danieljhkim.dsearch.common.grpc.NodeClientManager;
import com.danieljhkim.dsearch.common.model.SearchResult;
import com.danieljhkim.dsearch.proto.cluster.NodeRole;
import com.danieljhkim.dsearch.proto.common.FacetRequest;
import com.danieljhkim.dsearch.proto.common.Filter;
import com.danieljhkim.dsearch.proto.common.FilterOperator;
import com.danieljhkim.dsearch.proto.common.SearchType;
import com.danieljhkim.dsearch.proto.index.IndexHit;
import com.danieljhkim.dsearch.proto.index.IndexSearchRequest;
import com.danieljhkim.dsearch.proto.index.IndexSearchResponse;
import com.danieljhkim.dsearch.proto.index.IndexServiceGrpc;
import io.grpc.Context;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class IndexServiceTest {

    private RecordingIndexGrpcService grpcService;
    private Server server;
    private ManagedChannel channel;
    private NodeClientManager<IndexServiceGrpc.IndexServiceBlockingStub> clientManager;
    private IndexService indexService;

    @BeforeEach
    void setUp() throws Exception {
        grpcService = new RecordingIndexGrpcService();
        server = NettyServerBuilder.forPort(0).addService(grpcService).build().start();
        channel = ManagedChannelBuilder.forAddress("localhost", server.getPort())
                .usePlaintext()
                .build();
        NodeClient<IndexServiceGrpc.IndexServiceBlockingStub> client = new NodeClient<>(
                "node-1", IndexServiceGrpc.newBlockingStub(channel), channel, "localhost", server.getPort());
        clientManager = new NodeClientManager<>(
                Map.of("node-1", client), RoutingStrategy.ROUND_ROBIN, NodeRole.NODE_ROLE_INDEX, ignored -> null);
        indexService = new IndexService(clientManager);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        if (clientManager != null) {
            clientManager.shutdown();
        }
        if (channel != null) {
            channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
        if (server != null) {
            server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void forwardsRequestAndTranslatesResponse() {
        grpcService.response = IndexSearchResponse.newBuilder()
                .setTotalHits(7)
                .addHits(IndexHit.newBuilder()
                        .setDocId("doc-1")
                        .setTitle("Title")
                        .setContent("Content")
                        .setScore(2.5f)
                        .putHighlightedFields("content", "<em>Content</em>")
                        .putFields("category", "books")
                        .build())
                .build();
        List<Filter> filters = List.of(Filter.newBuilder()
                .setField("category")
                .setOperator(FilterOperator.EQ)
                .addValues("books")
                .build());
        List<FacetRequest> facets = List.of(
                FacetRequest.newBuilder().setField("category").setSize(3).build());

        SearchResult result =
                indexService.search("coffee", "node-1", "shard-a", 2, 4, SearchType.BM25, filters, true, facets);

        assertEquals("coffee", grpcService.request.getQuery());
        assertEquals(8, grpcService.request.getFrom());
        assertEquals(4, grpcService.request.getSize());
        assertEquals("shard-a", grpcService.request.getPartitionId());
        assertEquals(SearchType.BM25, grpcService.request.getSearchType());
        assertTrue(grpcService.request.getHighlight());
        assertEquals(filters, grpcService.request.getFiltersList());
        assertEquals(facets, grpcService.request.getFacetsList());
        assertEquals(7, result.getTotalHits());
        assertEquals(2, result.getPage());
        assertEquals("doc-1", result.getHits().getFirst().getDocId());
        assertEquals(
                Map.of("content", "<em>Content</em>"),
                result.getHits().getFirst().getHighlightedFields());
        assertEquals(Map.of("category", "books"), result.getHits().getFirst().getFields());
    }

    @Test
    void topKUsesExplicitDeadlineAndAlwaysStartsAtFirstPage() {
        grpcService.response = IndexSearchResponse.newBuilder().setTotalHits(1).build();

        indexService.searchShardTopK(
                "coffee", "node-1", "shard-a", 12, SearchType.SEMANTIC, null, false, null, Duration.ofSeconds(3));

        assertEquals(0, grpcService.request.getFrom());
        assertEquals(12, grpcService.request.getSize());
        assertEquals(SearchType.SEMANTIC, grpcService.request.getSearchType());
    }

    @Test
    void unknownNodeFailsBeforeTransport() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> indexService.search("coffee", "missing", "shard-a", 0, 10, SearchType.BM25));

        assertEquals("Unknown nodeId: missing", error.getMessage());
        assertEquals(0, grpcService.calls);
    }

    @Test
    void transportErrorPreservesGrpcStatus() {
        grpcService.failure = Status.UNAVAILABLE.withDescription("index unavailable");

        StatusRuntimeException error = assertThrows(
                StatusRuntimeException.class,
                () -> indexService.search("coffee", "node-1", "shard-a", 0, 10, SearchType.BM25));

        assertEquals(Status.Code.UNAVAILABLE, error.getStatus().getCode());
        assertEquals("index unavailable", error.getStatus().getDescription());
    }

    @Test
    void expiredDeadlineCancelsServerCallWithoutWaitingForWallClockDeadline() {
        grpcService.block = true;

        StatusRuntimeException error = assertThrows(
                StatusRuntimeException.class,
                () -> indexService.searchShardTopK(
                        "coffee", "node-1", "shard-a", 10, SearchType.BM25, null, false, null, Duration.ZERO));

        assertEquals(Status.Code.DEADLINE_EXCEEDED, error.getStatus().getCode());
    }

    @Test
    void channelShutdownCancelsAnOutstandingAdapterCall() throws Exception {
        grpcService.block = true;
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            var call = executor.submit(() -> indexService.searchShardTopK(
                    "coffee", "node-1", "shard-a", 10, SearchType.BM25, null, false, null, Duration.ofSeconds(5)));
            assertTrue(await(grpcService.started));
            channel.shutdownNow();

            ExecutionException error = assertThrows(ExecutionException.class, () -> call.get(5, TimeUnit.SECONDS));
            assertTrue(error.getCause() instanceof StatusRuntimeException);
            assertTrue(await(grpcService.cancelled));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void callerCancellationPropagatesToHangingDownstreamCall() throws Exception {
        grpcService.block = true;
        Context.CancellableContext requestContext = Context.current().withCancellation();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            var call = executor.submit(() -> requestContext.call(() -> indexService.searchShardTopK(
                    "coffee", "node-1", "shard-a", 10, SearchType.BM25, null, false, null, Duration.ofSeconds(5))));
            assertTrue(await(grpcService.started));

            requestContext.cancel(new RuntimeException("client disconnected"));

            ExecutionException error = assertThrows(ExecutionException.class, () -> call.get(5, TimeUnit.SECONDS));
            assertTrue(error.getCause() instanceof StatusRuntimeException);
            assertEquals(
                    Status.Code.CANCELLED,
                    ((StatusRuntimeException) error.getCause()).getStatus().getCode());
            assertTrue(await(grpcService.cancelled));
        } finally {
            requestContext.cancel(null);
            executor.shutdownNow();
        }
    }

    @Test
    void defaultInterfaceMethodsPreserveBasicSearchContract() {
        SearchResult expected = new SearchResult(List.of(), 0, 0);
        BaseIndexService basic = (query, node, shard, page, size, type) -> expected;

        assertSame(expected, basic.search("q", "n", "s", 0, 5, SearchType.BM25, List.of(), true));
        assertSame(expected, basic.search("q", "n", "s", 0, 5, SearchType.BM25, List.of(), true, List.of()));
        assertSame(expected, basic.searchShardTopK("q", "n", "s", 5, SearchType.BM25));
        assertSame(expected, basic.searchShardTopK("q", "n", "s", 5, SearchType.BM25, List.of(), true, List.of()));
    }

    private static boolean await(CountDownLatch latch) {
        try {
            return latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static final class RecordingIndexGrpcService extends IndexServiceGrpc.IndexServiceImplBase {
        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch cancelled = new CountDownLatch(1);
        private volatile IndexSearchRequest request;
        private volatile IndexSearchResponse response = IndexSearchResponse.getDefaultInstance();
        private volatile Status failure;
        private volatile boolean block;
        private volatile int calls;

        @Override
        public void searchIndex(IndexSearchRequest request, StreamObserver<IndexSearchResponse> responseObserver) {
            this.request = request;
            calls++;
            started.countDown();
            Context.current().addListener(context -> cancelled.countDown(), Runnable::run);
            if (failure != null) {
                responseObserver.onError(failure.asRuntimeException());
                return;
            }
            if (block) {
                try {
                    cancelled.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return;
            }
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        }
    }
}
