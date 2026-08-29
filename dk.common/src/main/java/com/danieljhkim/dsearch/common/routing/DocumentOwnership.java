package com.danieljhkim.dsearch.common.routing;

import java.util.Collection;
import java.util.Objects;

/**
 * Deterministic {@code (partitionId, documentId) -> nodeId} ownership.
 *
 * <p>A Lucene upsert is local to a single index node, so every mutation of a
 * document must reach the same node for the lifetime of that document.
 * Ownership is therefore computed as a pure function of the key and the node
 * id set - no shared state, no process-local assignment map - which makes the
 * decision identical in every gateway process and across restarts.
 *
 * <p>Selection uses rendezvous (highest-random-weight) hashing: each candidate
 * node is scored against the key and the highest score wins. Compared to plain
 * modulo hashing, adding or removing a node only reassigns the keys that
 * touched that node instead of reshuffling the whole key space.
 *
 * <p>The hash is FNV-1a 64 followed by the SplitMix64 finalizer so that scores
 * are well distributed for the short, highly similar node ids ("0", "1", ...)
 * this cluster uses. It is defined entirely in terms of UTF-16 code units, so
 * it does not depend on JVM version, locale, or iteration order.
 */
public final class DocumentOwnership {

    private static final long FNV_64_OFFSET_BASIS = 0xcbf29ce484222325L;
    private static final long FNV_64_PRIME = 0x100000001b3L;

    /** Separator that cannot appear in node, partition, or document ids joined into one hash input. */
    private static final char SEPARATOR = '\0';

    private DocumentOwnership() {}

    /**
     * Resolve the owning node id for a document key.
     *
     * @param nodeIds the ownership ring; must not be empty
     * @throws IllegalArgumentException if the ring is empty
     */
    public static String ownerNodeId(String partitionId, String documentId, Collection<String> nodeIds) {
        Objects.requireNonNull(partitionId, "partitionId must not be null");
        Objects.requireNonNull(documentId, "documentId must not be null");
        Objects.requireNonNull(nodeIds, "nodeIds must not be null");
        if (nodeIds.isEmpty()) {
            throw new IllegalArgumentException("Cannot resolve a document owner from an empty node set");
        }

        String owner = null;
        long bestScore = 0L;
        for (String nodeId : nodeIds) {
            Objects.requireNonNull(nodeId, "nodeIds must not contain null");
            long score = score(nodeId, partitionId, documentId);
            // Ties break on the lexicographically smallest node id so the result never
            // depends on the iteration order of the supplied collection.
            if (owner == null || score > bestScore || (score == bestScore && nodeId.compareTo(owner) < 0)) {
                owner = nodeId;
                bestScore = score;
            }
        }
        return owner;
    }

    /**
     * Rendezvous score of a node for a document key. Exposed for tests that pin
     * the hash to concrete values.
     */
    public static long score(String nodeId, String partitionId, String documentId) {
        long hash = FNV_64_OFFSET_BASIS;
        hash = hashChars(hash, nodeId);
        hash = hashChar(hash, SEPARATOR);
        hash = hashChars(hash, partitionId);
        hash = hashChar(hash, SEPARATOR);
        hash = hashChars(hash, documentId);
        return mix(hash);
    }

    private static long hashChars(long hash, String value) {
        for (int i = 0; i < value.length(); i++) {
            hash = hashChar(hash, value.charAt(i));
        }
        return hash;
    }

    private static long hashChar(long hash, char c) {
        hash = (hash ^ (c & 0xff)) * FNV_64_PRIME;
        return (hash ^ ((c >>> 8) & 0xff)) * FNV_64_PRIME;
    }

    /** SplitMix64 finalizer: avalanches the low-entropy FNV output of short ids. */
    private static long mix(long value) {
        long z = value + 0x9e3779b97f4a7c15L;
        z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L;
        z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL;
        return z ^ (z >>> 31);
    }
}
