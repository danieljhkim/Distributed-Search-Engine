package com.danieljhkim.dsearch.common.pagination;

import com.danieljhkim.dsearch.proto.common.SortValue;
import java.util.List;

/**
 * The ordering half of a search request: what to order by, and where to resume.
 *
 * <p>Bundled because the two are only meaningful together — a resume point is a tuple whose
 * shape is defined by the spec — and because threading two more positional parameters through
 * the shard, manager, and gRPC layers would make those signatures unreadable.
 *
 * @param spec effective ordering, already tie-broken
 * @param searchAfter tuple of the last hit already delivered, or null to start from the top
 */
public record SortOptions(SortSpec spec, List<SortValue> searchAfter) {

    public static final SortOptions NONE = new SortOptions(SortSpec.unsorted(), null);

    public SortOptions {
        spec = spec == null ? SortSpec.unsorted() : spec;
        searchAfter = searchAfter == null ? null : List.copyOf(searchAfter);
    }

    public static SortOptions sortedBy(SortSpec spec) {
        return new SortOptions(spec, null);
    }

    public boolean isSorted() {
        return !spec.isUnsorted();
    }

    public boolean hasSearchAfter() {
        return searchAfter != null;
    }
}
