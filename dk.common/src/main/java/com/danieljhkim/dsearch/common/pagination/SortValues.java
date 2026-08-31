package com.danieljhkim.dsearch.common.pagination;

import com.danieljhkim.dsearch.proto.common.SortValue;
import java.nio.charset.StandardCharsets;

/**
 * Construction and ordering of individual sort values.
 *
 * <p>The comparison here has to agree exactly with the ordering Lucene applied inside each shard,
 * otherwise the distributed merge would reorder hits relative to the {@code search_after} boundary
 * the shards used and a traversal could repeat or skip documents. Two rules carry that weight:
 *
 * <ul>
 *   <li>Missing values order last in both directions. Lucene achieves this with per-direction
 *       sentinels; here it is explicit, so the two never disagree about where a null goes.
 *   <li>Strings compare as unsigned UTF-8 bytes, matching Lucene's {@code BytesRef} order rather
 *       than Java's UTF-16 {@code String.compareTo}. The two disagree on non-BMP characters.
 * </ul>
 */
public final class SortValues {

    private static final SortValue MISSING =
            SortValue.newBuilder().setMissing(true).build();

    private SortValues() {}

    public static SortValue missing() {
        return MISSING;
    }

    public static SortValue of(String value) {
        return value == null
                ? MISSING
                : SortValue.newBuilder().setStringValue(value).build();
    }

    public static SortValue of(long value) {
        return SortValue.newBuilder().setLongValue(value).build();
    }

    public static SortValue of(double value) {
        return SortValue.newBuilder().setDoubleValue(value).build();
    }

    public static SortValue of(float value) {
        return SortValue.newBuilder().setFloatValue(value).build();
    }

    public static boolean isMissing(SortValue value) {
        return value == null || value.getMissing() || value.getValueCase() == SortValue.ValueCase.VALUE_NOT_SET;
    }

    /**
     * Orders two values under one component's direction.
     *
     * @return negative when {@code left} comes first in the result order, positive when it comes
     *     later, zero when the component cannot separate them
     */
    public static int compare(SortValue left, SortValue right, boolean descending) {
        boolean leftMissing = isMissing(left);
        boolean rightMissing = isMissing(right);
        if (leftMissing || rightMissing) {
            if (leftMissing && rightMissing) {
                return 0;
            }
            // Direction deliberately ignored: a null is last whether ascending or descending.
            return leftMissing ? 1 : -1;
        }

        int natural;
        if (left.getValueCase() != right.getValueCase()) {
            // Mixed types mean a shard disagrees about a field's type. Order deterministically by
            // the type itself rather than throwing, so one bad shard cannot fail the whole merge.
            natural = Integer.compare(
                    left.getValueCase().getNumber(), right.getValueCase().getNumber());
        } else {
            natural = switch (left.getValueCase()) {
                case STRING_VALUE -> compareUtf8(left.getStringValue(), right.getStringValue());
                case LONG_VALUE -> Long.compare(left.getLongValue(), right.getLongValue());
                case DOUBLE_VALUE -> Double.compare(left.getDoubleValue(), right.getDoubleValue());
                case FLOAT_VALUE -> Float.compare(left.getFloatValue(), right.getFloatValue());
                case VALUE_NOT_SET -> 0;
            };
        }
        return descending ? -natural : natural;
    }

    /** Unsigned lexicographic comparison of UTF-8 encodings, matching Lucene's BytesRef order. */
    static int compareUtf8(String left, String right) {
        byte[] leftBytes = left.getBytes(StandardCharsets.UTF_8);
        byte[] rightBytes = right.getBytes(StandardCharsets.UTF_8);
        int shared = Math.min(leftBytes.length, rightBytes.length);
        for (int i = 0; i < shared; i++) {
            int difference = (leftBytes[i] & 0xFF) - (rightBytes[i] & 0xFF);
            if (difference != 0) {
                return difference;
            }
        }
        return leftBytes.length - rightBytes.length;
    }
}
