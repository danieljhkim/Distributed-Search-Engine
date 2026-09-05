package com.danieljhkim.dsearch.common.validation;

import com.danieljhkim.dsearch.common.config.AppConfig;
import com.danieljhkim.dsearch.common.config.ConfigLoader;
import com.danieljhkim.dsearch.proto.common.FacetRequest;
import com.danieljhkim.dsearch.proto.common.Filter;
import com.danieljhkim.dsearch.proto.common.SortField;
import com.danieljhkim.dsearch.proto.index.BulkIndexDocumentRequest;
import com.danieljhkim.dsearch.proto.index.Document;
import com.danieljhkim.dsearch.proto.index.IndexSearchRequest;
import com.danieljhkim.dsearch.proto.query.QueryRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Validates request cost before fan-out, result-window allocation, or embedding inference. */
public final class RequestLimitsValidator {

    public static final int DEFAULT_FACET_SIZE = 10;

    private static final Logger LOGGER = Logger.getLogger(RequestLimitsValidator.class.getName());
    private static final AppConfig.RequestLimitsConfig DEFAULT_LIMITS = loadLimits();
    private static final AppConfig.PaginationConfig DEFAULT_PAGINATION = loadPagination();

    private RequestLimitsValidator() {}

    private static AppConfig.RequestLimitsConfig loadLimits() {
        try {
            AppConfig appConfig = ConfigLoader.load();
            if (appConfig != null && appConfig.getRequestLimits() != null) {
                return appConfig.getRequestLimits();
            }
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to load request limits; using safe built-in defaults", e);
        }
        return new AppConfig.RequestLimitsConfig();
    }

    public static AppConfig.RequestLimitsConfig limitsOrDefaults(AppConfig.RequestLimitsConfig limits) {
        return limits != null ? limits : DEFAULT_LIMITS;
    }

    public static AppConfig.PaginationConfig paginationOrDefaults(AppConfig.PaginationConfig pagination) {
        return pagination != null ? pagination : DEFAULT_PAGINATION;
    }

    private static AppConfig.PaginationConfig loadPagination() {
        try {
            AppConfig appConfig = ConfigLoader.load();
            if (appConfig != null && appConfig.getPagination() != null) {
                return appConfig.getPagination();
            }
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to load pagination config; using safe built-in defaults", e);
        }
        return new AppConfig.PaginationConfig();
    }

    public static void validateRequestLimits(String queryString, int pageSize) {
        validateSearchWindow(queryString, 0, pageSize, DEFAULT_LIMITS);
    }

    public static int validateSearchWindow(
            String queryString, int page, int pageSize, AppConfig.RequestLimitsConfig configuredLimits) {
        AppConfig.RequestLimitsConfig limits = limitsOrDefaults(configuredLimits);
        validateQueryLength(queryString, limits.getMaxQueryLength());
        validatePageSize(pageSize, limits.getMaxSize());
        if (page < 0) {
            throw new IllegalArgumentException("page must not be negative");
        }
        if (pageSize < 1) {
            throw new IllegalArgumentException("pageSize must be greater than 0");
        }

        long from = Math.multiplyExact((long) page, (long) pageSize);
        long resultEnd = Math.addExact(from, pageSize);
        if (resultEnd > limits.getMaxResultWindow() || resultEnd > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Requested result window (" + resultEnd + ") exceeds maximum allowed ("
                    + limits.getMaxResultWindow() + "); use cursor pagination for deeper results");
        }
        return Math.toIntExact(from);
    }

    public static void validateQueryRequest(QueryRequest request, AppConfig.RequestLimitsConfig limits) {
        validateQueryRequest(request, limits, DEFAULT_PAGINATION);
    }

    public static void validateQueryRequest(
            QueryRequest request, AppConfig.RequestLimitsConfig limits, AppConfig.PaginationConfig pagination) {
        Objects.requireNonNull(request, "request");
        AppConfig.RequestLimitsConfig effective = limitsOrDefaults(limits);
        if (request.getCursor().isEmpty()) {
            validateSearchWindow(request.getQueryString(), request.getPage(), request.getSize(), effective);
        } else {
            // A cursor already encodes the position, so offset paging must not also be requested:
            // honouring both would be ambiguous, and silently ignoring one would skip results.
            if (request.getPage() != 0) {
                throw new IllegalArgumentException(
                        "cursor and page are mutually exclusive; omit page when resuming from a cursor");
            }
            validateCursorWindow(request.getQueryString(), request.getSize(), effective);
        }
        validateSortFields(request.getSortList(), paginationOrDefaults(pagination));
        if (request.hasStoredFieldSelection()) {
            validateStoredFieldSelection(request.getStoredFieldSelection().getFieldsList(), effective);
        }
        validateSearchStructures(request.getFiltersList(), request.getFacetsList(), effective);
    }

    /**
     * Bounds a cursor page. Deliberately not bounded by {@code maxResultWindow}: a cursor costs
     * one page per node no matter how deep the traversal has gone, which is the whole point of
     * offering it alongside offset paging.
     */
    public static void validateCursorWindow(
            String queryString, int pageSize, AppConfig.RequestLimitsConfig configuredLimits) {
        AppConfig.RequestLimitsConfig limits = limitsOrDefaults(configuredLimits);
        validateQueryLength(queryString, limits.getMaxQueryLength());
        validatePageSize(pageSize, limits.getMaxSize());
        if (pageSize < 1) {
            throw new IllegalArgumentException("pageSize must be greater than 0");
        }
    }

    public static void validateSortFields(Collection<SortField> sortFields, AppConfig.PaginationConfig pagination) {
        if (sortFields == null || sortFields.isEmpty()) {
            return;
        }
        AppConfig.PaginationConfig effective = paginationOrDefaults(pagination);
        int maxSortFields = Math.max(1, effective.getMaxSortFields());
        if (sortFields.size() > maxSortFields) {
            throw new IllegalArgumentException(
                    "Sort field count (" + sortFields.size() + ") exceeds maximum allowed (" + maxSortFields + ")");
        }
        for (SortField sortField : sortFields) {
            if (sortField == null || sortField.getField().isBlank()) {
                throw new IllegalArgumentException("sort field name must not be blank");
            }
            validateUtf8Bytes("sort field", sortField.getField(), DEFAULT_LIMITS.getMaxFieldValueBytes());
        }
    }

    public static void validateIndexSearchRequest(IndexSearchRequest request, AppConfig.RequestLimitsConfig limits) {
        Objects.requireNonNull(request, "request");
        AppConfig.RequestLimitsConfig effective = limitsOrDefaults(limits);
        validateQueryLength(request.getQuery(), effective.getMaxQueryLength());
        validatePageSize(request.getSize(), effective.getMaxSize());
        if (request.getFrom() < 0 || request.getSize() < 1) {
            throw new IllegalArgumentException("from must not be negative and size must be greater than 0");
        }
        if (request.getHasSearchAfter()) {
            // A resuming shard search reads exactly one page, so the offset window does not apply;
            // an offset on top of a resume point would double-count the position.
            if (request.getFrom() != 0) {
                throw new IllegalArgumentException("from must be 0 when search_after is set");
            }
            if (request.getSearchAfterCount() != request.getSortCount()) {
                throw new IllegalArgumentException("search_after must have one value per sort field");
            }
        } else {
            long resultEnd = Math.addExact((long) request.getFrom(), request.getSize());
            if (resultEnd > effective.getMaxResultWindow()) {
                throw new IllegalArgumentException("Requested result window (" + resultEnd
                        + ") exceeds maximum allowed (" + effective.getMaxResultWindow() + ")");
            }
        }
        if (request.hasStoredFieldSelection()) {
            validateStoredFieldSelection(request.getStoredFieldSelection().getFieldsList(), effective);
        }
        validateSearchStructures(request.getFiltersList(), request.getFacetsList(), effective);
    }

    /** Bounds and validates an explicitly present stored-field response projection. */
    public static void validateStoredFieldSelection(
            Collection<String> fields, AppConfig.RequestLimitsConfig configuredLimits) {
        AppConfig.RequestLimitsConfig limits = limitsOrDefaults(configuredLimits);
        int count = fields == null ? 0 : fields.size();
        if (count > limits.getMaxFieldsPerDocument()) {
            throw new IllegalArgumentException("Stored field selection count (" + count + ") exceeds maximum allowed ("
                    + limits.getMaxFieldsPerDocument() + ")");
        }
        if (fields == null) {
            return;
        }
        HashSet<String> seen = new HashSet<>();
        for (String field : fields) {
            if (field == null || field.isBlank()) {
                throw new IllegalArgumentException("stored field name must not be blank");
            }
            validateUtf8Bytes("stored field name", field, limits.getMaxFieldValueBytes());
            if (!seen.add(field)) {
                throw new IllegalArgumentException("stored field selection must not contain duplicates: " + field);
            }
        }
    }

    public static void validateDocument(Document document, AppConfig.RequestLimitsConfig limits) {
        Objects.requireNonNull(document, "document");
        AppConfig.RequestLimitsConfig effective = limitsOrDefaults(limits);
        validateUtf8Bytes("document id", document.getId(), effective.getMaxDocumentIdBytes());
        if (document.getFieldsCount() > effective.getMaxFieldsPerDocument()) {
            throw new IllegalArgumentException("Document field count (" + document.getFieldsCount()
                    + ") exceeds maximum allowed (" + effective.getMaxFieldsPerDocument() + ")");
        }
        document.getFieldsList().forEach(field -> {
            validateUtf8Bytes("field name", field.getName(), effective.getMaxFieldValueBytes());
            validateUtf8Bytes("field value", field.getValue(), effective.getMaxFieldValueBytes());
        });
        validateIndexPayloadBytes(document.getSerializedSize(), effective);
    }

    public static void validateDocumentId(String documentId, AppConfig.RequestLimitsConfig limits) {
        AppConfig.RequestLimitsConfig effective = limitsOrDefaults(limits);
        validateUtf8Bytes("document id", documentId, effective.getMaxDocumentIdBytes());
    }

    public static void validateDocument(
            String documentId, Map<String, String> fields, AppConfig.RequestLimitsConfig limits) {
        AppConfig.RequestLimitsConfig effective = limitsOrDefaults(limits);
        validateUtf8Bytes("document id", documentId, effective.getMaxDocumentIdBytes());
        int fieldCount = fields == null ? 0 : fields.size();
        if (fieldCount > effective.getMaxFieldsPerDocument()) {
            throw new IllegalArgumentException("Document field count (" + fieldCount + ") exceeds maximum allowed ("
                    + effective.getMaxFieldsPerDocument() + ")");
        }
        long payloadBytes = utf8Length(documentId);
        if (fields != null) {
            for (Map.Entry<String, String> field : fields.entrySet()) {
                validateRequiredUtf8Bytes("field name", field.getKey(), effective.getMaxFieldValueBytes());
                validateRequiredUtf8Bytes("field value", field.getValue(), effective.getMaxFieldValueBytes());
                payloadBytes = Math.addExact(payloadBytes, utf8Length(field.getKey()));
                payloadBytes = Math.addExact(payloadBytes, utf8Length(field.getValue()));
            }
        }
        validateIndexPayloadBytes(payloadBytes, effective);
    }

    public static void validateBulkIndexRequest(
            BulkIndexDocumentRequest request, AppConfig.RequestLimitsConfig limits) {
        Objects.requireNonNull(request, "request");
        AppConfig.RequestLimitsConfig effective = limitsOrDefaults(limits);
        validateBulkItemCount(request.getDocumentsCount(), effective);
        validateIndexPayloadBytes(request.getSerializedSize(), effective);
        request.getDocumentsList().forEach(document -> validateDocument(document, effective));
    }

    public static void validateBulkItemCount(int itemCount, AppConfig.RequestLimitsConfig limits) {
        AppConfig.RequestLimitsConfig effective = limitsOrDefaults(limits);
        if (itemCount > effective.getMaxBulkItems()) {
            throw new IllegalArgumentException("Bulk item count (" + itemCount + ") exceeds maximum allowed ("
                    + effective.getMaxBulkItems() + ")");
        }
    }

    public static void validateBulkEmbeddingBytes(long embeddingBytes, AppConfig.RequestLimitsConfig limits) {
        AppConfig.RequestLimitsConfig effective = limitsOrDefaults(limits);
        if (embeddingBytes > effective.getMaxBulkEmbeddingBytes()) {
            throw new IllegalArgumentException("Bulk embedding bytes (" + embeddingBytes + ") exceeds maximum allowed ("
                    + effective.getMaxBulkEmbeddingBytes() + ")");
        }
    }

    private static void validateIndexPayloadBytes(long payloadBytes, AppConfig.RequestLimitsConfig limits) {
        if (payloadBytes > limits.getMaxIndexPayloadBytes()) {
            throw new IllegalArgumentException("Index payload bytes (" + payloadBytes + ") exceeds maximum allowed ("
                    + limits.getMaxIndexPayloadBytes() + ")");
        }
    }

    public static void validateSearchStructures(
            Collection<Filter> filters,
            Collection<FacetRequest> facets,
            AppConfig.RequestLimitsConfig configuredLimits) {
        AppConfig.RequestLimitsConfig limits = limitsOrDefaults(configuredLimits);
        validateFilters(filters, limits);
        int facetCount = 0;
        ArrayDeque<FacetAtDepth> pending = new ArrayDeque<>();
        if (facets != null) {
            facets.forEach(facet -> pending.addLast(new FacetAtDepth(facet, 1, 1L)));
        }
        long expandedBucketUpperBound = 0L;
        while (!pending.isEmpty()) {
            FacetAtDepth current = pending.removeFirst();
            facetCount++;
            if (facetCount > limits.getMaxFacetCount()) {
                throw new IllegalArgumentException(
                        "Facet count exceeds maximum allowed (" + limits.getMaxFacetCount() + ")");
            }
            if (current.depth() > limits.getMaxFacetDepth()) {
                throw new IllegalArgumentException("Facet depth (" + current.depth() + ") exceeds maximum allowed ("
                        + limits.getMaxFacetDepth() + ")");
            }
            if (current.facet().getFiltersCount() > 0) {
                throw new IllegalArgumentException(
                        "Facet-level filters are not supported; use top-level search filters instead");
            }
            int requestedSize = current.facet().getSize();
            if (requestedSize < 0 || requestedSize > limits.getMaxSize()) {
                throw new IllegalArgumentException(
                        "Facet size must be between 1 and " + limits.getMaxSize() + " when specified");
            }
            int effectiveSize = requestedSize > 0 ? requestedSize : DEFAULT_FACET_SIZE;
            if (effectiveSize > limits.getMaxSize()) {
                throw new IllegalArgumentException("Facet size must be between 1 and " + limits.getMaxSize());
            }
            FacetBucketUpperBound upperBound = accumulateFacetBucketUpperBound(
                    expandedBucketUpperBound,
                    current.parentBucketUpperBound(),
                    effectiveSize,
                    limits.getMaxFacetExpandedBuckets());
            long nodeBucketUpperBound = upperBound.nodeBuckets();
            expandedBucketUpperBound = upperBound.expandedBuckets();
            current.facet()
                    .getNestedList()
                    .forEach(nested ->
                            pending.addLast(new FacetAtDepth(nested, current.depth() + 1, nodeBucketUpperBound)));
        }
    }

    /** Adds one request-tree node to an overflow-safe expanded facet bucket estimate. */
    public static FacetBucketUpperBound accumulateFacetBucketUpperBound(
            long expandedBuckets, long parentBuckets, int facetSize, long maximum) {
        long nodeBuckets = multiplyFacetBuckets(parentBuckets, facetSize, maximum);
        return new FacetBucketUpperBound(addFacetBuckets(expandedBuckets, nodeBuckets, maximum), nodeBuckets);
    }

    private static long multiplyFacetBuckets(long parentBuckets, int facetSize, long maximum) {
        validateFacetBucketLimit(maximum);
        if (parentBuckets > maximum / facetSize) {
            throw expandedFacetBucketsExceeded(maximum);
        }
        return parentBuckets * facetSize;
    }

    private static long addFacetBuckets(long currentBuckets, long additionalBuckets, long maximum) {
        validateFacetBucketLimit(maximum);
        if (currentBuckets > maximum - additionalBuckets) {
            throw expandedFacetBucketsExceeded(maximum);
        }
        return currentBuckets + additionalBuckets;
    }

    private static void validateFacetBucketLimit(long maximum) {
        if (maximum < 1) {
            throw new IllegalArgumentException("maxFacetExpandedBuckets must be greater than 0");
        }
    }

    private static IllegalArgumentException expandedFacetBucketsExceeded(long maximum) {
        return new IllegalArgumentException(
                "Expanded facet bucket upper bound exceeds maximum allowed (" + maximum + ")");
    }

    private static int validateFilters(Collection<Filter> filters, AppConfig.RequestLimitsConfig limits) {
        int count = filters == null ? 0 : filters.size();
        if (count > limits.getMaxFilterClauses()) {
            throw new IllegalArgumentException(
                    "Filter clause count exceeds maximum allowed (" + limits.getMaxFilterClauses() + ")");
        }
        if (filters != null) {
            for (Filter filter : filters) {
                validateUtf8Bytes("filter field", filter.getField(), limits.getMaxFieldValueBytes());
                for (String value : filter.getValuesList()) {
                    validateUtf8Bytes("filter value", value, limits.getMaxFieldValueBytes());
                }
            }
        }
        return count;
    }

    public static void validatePageSize(int pageSize, int maxSize) {
        if (pageSize > maxSize) {
            throw new IllegalArgumentException(
                    String.format("Requested pageSize (%d) exceeds maximum allowed (%d)", pageSize, maxSize));
        }
    }

    public static void validateQueryLength(String query, int maxQueryLength) {
        if (query != null && query.length() > maxQueryLength) {
            throw new IllegalArgumentException(
                    String.format("Query length (%d) exceeds maximum allowed (%d)", query.length(), maxQueryLength));
        }
    }

    private static void validateUtf8Bytes(String label, String value, int maximum) {
        int bytes = utf8Length(value);
        if (bytes > maximum) {
            throw new IllegalArgumentException(
                    label + " bytes (" + bytes + ") exceeds maximum allowed (" + maximum + ")");
        }
    }

    private static void validateRequiredUtf8Bytes(String label, String value, int maximum) {
        if (value == null) {
            throw new IllegalArgumentException(label + " must not be null");
        }
        validateUtf8Bytes(label, value, maximum);
    }

    private static int utf8Length(String value) {
        return value == null ? 0 : value.getBytes(StandardCharsets.UTF_8).length;
    }

    private record FacetAtDepth(FacetRequest facet, int depth, long parentBucketUpperBound) {}

    public record FacetBucketUpperBound(long expandedBuckets, long nodeBuckets) {}
}
