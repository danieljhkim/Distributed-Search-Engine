package com.danieljhkim.dsearch.querynode.grpc;

import com.danieljhkim.dsearch.common.config.AppConfig;
import com.danieljhkim.dsearch.common.exception.InvalidCursorException;
import com.danieljhkim.dsearch.common.exception.ParseGoneWrongException;
import com.danieljhkim.dsearch.common.exception.SchemaMismatchException;
import com.danieljhkim.dsearch.common.model.SearchResult;
import com.danieljhkim.dsearch.common.pagination.RequestFingerprint;
import com.danieljhkim.dsearch.common.pagination.SearchCursorCodec;
import com.danieljhkim.dsearch.common.pagination.SortOptions;
import com.danieljhkim.dsearch.common.pagination.SortSpec;
import com.danieljhkim.dsearch.common.schema.IndexSchema;
import com.danieljhkim.dsearch.common.schema.IndexSchemaCompatibility;
import com.danieljhkim.dsearch.common.validation.RequestAdmissionException;
import com.danieljhkim.dsearch.common.validation.RequestLimitsValidator;
import com.danieljhkim.dsearch.proto.common.FacetRequest;
import com.danieljhkim.dsearch.proto.common.Filter;
import com.danieljhkim.dsearch.proto.common.FusionStrategy;
import com.danieljhkim.dsearch.proto.common.SearchCursorPayload;
import com.danieljhkim.dsearch.proto.common.SearchType;
import com.danieljhkim.dsearch.proto.common.SortValue;
import com.danieljhkim.dsearch.proto.query.FanoutMetadata;
import com.danieljhkim.dsearch.proto.query.FanoutStatus;
import com.danieljhkim.dsearch.proto.query.QueryRequest;
import com.danieljhkim.dsearch.proto.query.QueryResponse;
import com.danieljhkim.dsearch.proto.query.QueryServiceGrpc;
import com.danieljhkim.dsearch.proto.query.SearchHit;
import com.danieljhkim.dsearch.querynode.search.SearchExecutor;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

public class QueryServiceImpl extends QueryServiceGrpc.QueryServiceImplBase {

    private static final Logger LOGGER = Logger.getLogger(QueryServiceImpl.class.getName());

    private final SearchExecutor searchExecutor;
    private final BaseIndexService indexService;
    private final AppConfig.RequestLimitsConfig requestLimits;
    private final AppConfig.PaginationConfig paginationConfig;
    private final SearchCursorCodec cursorCodec;
    private final IndexSchema expectedSchema;

    public QueryServiceImpl(SearchExecutor searchExecutor, BaseIndexService indexService) {
        this(searchExecutor, indexService, new AppConfig.RequestLimitsConfig(), null);
    }

    public QueryServiceImpl(
            SearchExecutor searchExecutor, BaseIndexService indexService, AppConfig.RequestLimitsConfig requestLimits) {
        this(searchExecutor, indexService, requestLimits, null);
    }

    public QueryServiceImpl(
            SearchExecutor searchExecutor,
            BaseIndexService indexService,
            AppConfig.RequestLimitsConfig requestLimits,
            IndexSchema expectedSchema) {
        this(searchExecutor, indexService, requestLimits, expectedSchema, new AppConfig.PaginationConfig());
    }

    public QueryServiceImpl(
            SearchExecutor searchExecutor,
            BaseIndexService indexService,
            AppConfig.RequestLimitsConfig requestLimits,
            IndexSchema expectedSchema,
            AppConfig.PaginationConfig paginationConfig) {
        this.searchExecutor = searchExecutor;
        this.indexService = indexService;
        this.requestLimits = RequestLimitsValidator.limitsOrDefaults(requestLimits);
        this.paginationConfig = RequestLimitsValidator.paginationOrDefaults(paginationConfig);
        this.cursorCodec = new SearchCursorCodec(this.paginationConfig.getCursorSigningKey());
        this.expectedSchema = expectedSchema;
    }

    @Override
    public void search(QueryRequest request, StreamObserver<QueryResponse> responseObserver) {
        String queryString = request.getQueryString();
        int page = request.getPage();
        int size = request.getSize();
        String partitionId = request.getPartitionId();
        SearchType searchType = request.getSearchType();
        List<Filter> filters = request.getFiltersList();
        boolean highlight = request.getHighlight();
        List<FacetRequest> facetRequests = request.getFacetsList();

        try {
            RequestLimitsValidator.validateQueryRequest(request, requestLimits, paginationConfig);

            boolean resuming = !request.getCursor().isEmpty();
            boolean ordered = resuming || request.getSortCount() > 0;

            // One inspect call serves three purposes: the existing schema refusal, sort-field
            // eligibility, and the cursor's schema/generation binding. Skipped entirely for the
            // unsorted path so ordinary relevance search keeps its current cost.
            BaseIndexService.IndexSnapshot snapshot =
                    expectedSchema != null || ordered ? inspectSnapshot(partitionId) : null;
            refuseIncompatibleSchema(snapshot);

            IndexSchema effectiveSchema = snapshot != null ? snapshot.schema() : expectedSchema;
            long indexGeneration = snapshot != null ? snapshot.generation() : 0L;

            SortSpec sortSpec = SortSpec.effective(request.getSortList(), resuming);
            sortSpec.validateAgainst(effectiveSchema);

            boolean cursorSupported = ordered && supportsCursor(searchType, sortSpec);
            byte[] fingerprint = ordered
                    ? RequestFingerprint.of(
                            queryString,
                            partitionId,
                            searchType.name(),
                            request.getFusionStrategy().name(),
                            filters,
                            sortSpec,
                            size,
                            effectiveSchema)
                    : null;

            SortOptions sortOptions = SortOptions.NONE;
            SearchCursorPayload cursor = null;
            if (resuming) {
                requireCursorSupported(searchType, sortSpec, cursorSupported);
                cursor = cursorCodec.decode(request.getCursor(), fingerprint, indexGeneration);
                if (cursor.getSortValuesCount() != sortSpec.size()) {
                    throw new InvalidCursorException(
                            InvalidCursorException.Reason.MALFORMED,
                            "Cursor carries " + cursor.getSortValuesCount() + " sort values but the effective sort has "
                                    + sortSpec.size() + " components");
                }
                sortOptions = new SortOptions(sortSpec, cursor.getSortValuesList());
            } else if (ordered) {
                sortOptions = SortOptions.sortedBy(sortSpec);
            }

            SearchResult result;
            if (searchType == SearchType.HYBRID) {
                FusionStrategy fusionStrategy = request.getFusionStrategy();
                result = searchExecutor.searchHybrid(
                        queryString,
                        partitionId,
                        page,
                        size,
                        indexService,
                        fusionStrategy,
                        filters,
                        highlight,
                        facetRequests,
                        sortOptions);
            } else {
                result = searchExecutor.search(
                        queryString,
                        partitionId,
                        page,
                        size,
                        searchType,
                        indexService,
                        filters,
                        highlight,
                        facetRequests,
                        sortOptions);
            }
            SearchResult.FanoutMetadata fanoutMetadata = result.getFanoutMetadata();
            if (isTotalFanoutFailure(fanoutMetadata)) {
                responseObserver.onError(toFanoutFailureStatus(fanoutMetadata).asRuntimeException());
                return;
            }
            // A resumed page reports the total the traversal started with, so the denominator does
            // not drift under concurrent writes while a client is paging through it.
            long totalHits = cursor != null ? cursor.getTotalHits() : result.getTotalHits();
            String nextCursor =
                    cursorSupported ? issueCursor(result, size, fingerprint, indexGeneration, totalHits) : null;
            QueryResponse response = buildQueryResponse(result, page, size, totalHits, nextCursor);
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        } catch (InvalidCursorException e) {
            responseObserver.onError(toCursorStatus(e).withCause(e).asRuntimeException());
        } catch (RequestAdmissionException e) {
            responseObserver.onError(Status.RESOURCE_EXHAUSTED
                    .withDescription(e.getMessage())
                    .withCause(e)
                    .asRuntimeException());
        } catch (SchemaMismatchException e) {
            responseObserver.onError(Status.FAILED_PRECONDITION
                    .withDescription(e.getMessage())
                    .withCause(e)
                    .asRuntimeException());
        } catch (IllegalArgumentException e) {
            responseObserver.onError(Status.INVALID_ARGUMENT
                    .withDescription(e.getMessage())
                    .withCause(e)
                    .asRuntimeException());
        } catch (StatusRuntimeException e) {
            responseObserver.onError(e);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to parse query: " + queryString, e);
            responseObserver.onError(new ParseGoneWrongException("Failed to parse query: " + queryString, e));
        }
    }

    private BaseIndexService.IndexSnapshot inspectSnapshot(String partitionId) {
        if (partitionId == null || partitionId.isBlank()) {
            return null;
        }
        return indexService.inspectIndexSnapshot(partitionId);
    }

    private void refuseIncompatibleSchema(BaseIndexService.IndexSnapshot snapshot) {
        if (expectedSchema == null || snapshot == null || snapshot.schema() == null) {
            return;
        }
        IndexSchemaCompatibility.requireCompatible(snapshot.schema(), expectedSchema);
    }

    /**
     * Whether this request shape can be paged with a cursor.
     *
     * <p>A cursor needs a total order that every node can independently resume within. Two shapes
     * cannot provide one:
     *
     * <ul>
     *   <li>Ordering by {@code _score} under BM25 or hybrid. Lucene derives BM25 from each node's
     *       local term statistics, so scores from different nodes are not on a common scale; a
     *       score boundary means something different on every node.
     *   <li>Semantic and hybrid search of any ordering. Both draw from a fixed per-node kNN
     *       candidate pool rather than the whole partition, so resuming past the pool would stop
     *       returning documents that exist rather than reaching the end of the result set.
     * </ul>
     *
     * <p>Those requests still sort — they just cannot be traversed with a cursor, and say so
     * explicitly instead of returning a page that looks plausible and is wrong.
     */
    private static boolean supportsCursor(SearchType searchType, SortSpec sortSpec) {
        if (searchType == SearchType.SEMANTIC || searchType == SearchType.HYBRID) {
            return false;
        }
        return !sortSpec.isUnsorted() && !sortSpec.components().get(0).isScore();
    }

    private static void requireCursorSupported(SearchType searchType, SortSpec sortSpec, boolean supported) {
        if (supported) {
            return;
        }
        if (searchType == SearchType.SEMANTIC || searchType == SearchType.HYBRID) {
            throw new InvalidCursorException(
                    InvalidCursorException.Reason.UNSUPPORTED_REQUEST,
                    "Cursor pagination is not available for " + searchType.name()
                            + " search, which ranks within a bounded nearest-neighbour candidate pool rather than a "
                            + "total order over the partition. Use offset paging, or sort by a field with BM25.");
        }
        throw new InvalidCursorException(
                InvalidCursorException.Reason.UNSUPPORTED_REQUEST,
                "Cursor pagination requires ordering by a sortable field first: relevance scores are computed from "
                        + "each node's local term statistics and are not comparable across nodes.");
    }

    /**
     * Issues the cursor for the next page, or null when there is no next page to describe.
     *
     * <p>A short page means the result set is exhausted. A full page yields a cursor even if it
     * happened to be the last one; the following request then returns an empty page, which is the
     * conventional and cheaper of the two ways to detect the end.
     */
    private String issueCursor(
            SearchResult result, int size, byte[] fingerprint, long indexGeneration, long totalHits) {
        List<com.danieljhkim.dsearch.common.model.SearchHit> hits = result.getHits();
        if (hits.isEmpty() || hits.size() < size) {
            return null;
        }
        List<SortValue> lastSortValues = hits.get(hits.size() - 1).getSortValues();
        if (lastSortValues == null || lastSortValues.isEmpty()) {
            return null;
        }
        return cursorCodec.encode(fingerprint, indexGeneration, lastSortValues, totalHits);
    }

    private static Status toCursorStatus(InvalidCursorException e) {
        // An index that moved under the traversal is a precondition failure the client can act on
        // by restarting; everything else is a problem with the cursor or request as submitted.
        return e.getReason() == InvalidCursorException.Reason.INDEX_CHANGED
                ? Status.FAILED_PRECONDITION.withDescription(e.getMessage())
                : Status.INVALID_ARGUMENT.withDescription(e.getMessage());
    }

    private QueryResponse buildQueryResponse(
            SearchResult result, int page, int size, long totalHits, String nextCursor) {
        QueryResponse.Builder respBuilder =
                QueryResponse.newBuilder().setTotalHits(totalHits).setPage(page).setSize(size);
        if (nextCursor != null) {
            respBuilder.setNextCursor(nextCursor);
        }
        SearchResult.FanoutMetadata fanoutMetadata = result.getFanoutMetadata();
        if (fanoutMetadata != null) {
            respBuilder.setFanout(toProtoFanout(fanoutMetadata));
        }
        for (com.danieljhkim.dsearch.common.model.SearchHit hit : result.getHits()) {
            SearchHit.Builder hitBuilder =
                    SearchHit.newBuilder().setDocId(hit.getDocId()).setScore(hit.getScore());

            if (hit.getTitle() != null) {
                hitBuilder.setTitle(hit.getTitle());
            }
            if (hit.getContent() != null) {
                hitBuilder.setContent(hit.getContent());
            }
            if (hit.getHighlightedFields() != null
                    && !hit.getHighlightedFields().isEmpty()) {
                hitBuilder.putAllHighlightedFields(hit.getHighlightedFields());
            }
            if (hit.getFields() != null && !hit.getFields().isEmpty()) {
                hitBuilder.putAllFields(hit.getFields());
            }
            if (hit.getSortValues() != null && !hit.getSortValues().isEmpty()) {
                hitBuilder.addAllSortValues(hit.getSortValues());
            }
            SearchHit protoHit = hitBuilder.build();
            respBuilder.addHits(protoHit);
        }

        // Add aggregated facets to response
        if (result.getFacets() != null && !result.getFacets().isEmpty()) {
            respBuilder.addAllFacets(result.getFacets());
        }

        return respBuilder.build();
    }

    private static boolean isTotalFanoutFailure(SearchResult.FanoutMetadata fanoutMetadata) {
        return fanoutMetadata != null && fanoutMetadata.status() == SearchResult.FanoutStatus.FAILED;
    }

    private static Status toFanoutFailureStatus(SearchResult.FanoutMetadata fanoutMetadata) {
        String description = fanoutFailureDescription(fanoutMetadata);
        if (fanoutMetadata.attemptedNodes() > 0 && fanoutMetadata.timedOutNodes() == fanoutMetadata.attemptedNodes()) {
            return Status.DEADLINE_EXCEEDED.withDescription(description);
        }
        return Status.UNAVAILABLE.withDescription(description);
    }

    private static String fanoutFailureDescription(SearchResult.FanoutMetadata fanoutMetadata) {
        return "Search fanout failed: attemptedNodes=%d succeededNodes=%d failedNodes=%d timedOutNodes=%d"
                .formatted(
                        fanoutMetadata.attemptedNodes(),
                        fanoutMetadata.succeededNodes(),
                        fanoutMetadata.failedNodes(),
                        fanoutMetadata.timedOutNodes());
    }

    private static FanoutMetadata toProtoFanout(SearchResult.FanoutMetadata fanoutMetadata) {
        return FanoutMetadata.newBuilder()
                .setAttemptedNodes(fanoutMetadata.attemptedNodes())
                .setSucceededNodes(fanoutMetadata.succeededNodes())
                .setFailedNodes(fanoutMetadata.failedNodes())
                .setTimedOutNodes(fanoutMetadata.timedOutNodes())
                .setStatus(toProtoFanoutStatus(fanoutMetadata.status()))
                .build();
    }

    private static FanoutStatus toProtoFanoutStatus(SearchResult.FanoutStatus status) {
        return switch (status) {
            case SUCCESS -> FanoutStatus.FANOUT_STATUS_SUCCESS;
            case PARTIAL_FAILURE -> FanoutStatus.FANOUT_STATUS_PARTIAL_FAILURE;
            case FAILED -> FanoutStatus.FANOUT_STATUS_FAILED;
        };
    }
}
