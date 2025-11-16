package com.dk.search.querynode.search;

import com.dk.search.common.exception.IndexOperationException;
import com.dk.search.common.exception.ParseGoneWrongException;
import com.dk.search.common.model.SearchHit;
import com.dk.search.common.model.SearchResult;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.queryparser.classic.MultiFieldQueryParser;
import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopDocs;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Level;
import java.util.logging.Logger;

public class SearchExecutor implements Closeable {

    private static final Logger LOGGER = Logger.getLogger(SearchExecutor.class.getName());

    private static final String[] DEFAULT_SEARCH_FIELDS = new String[]{"title", "body", "content"};
    private final Path baseDir;
    private final Map<Integer, ShardSearcher> shardSearcherMap = new ConcurrentHashMap<>();
    private final ExecutorService shardExecutor;
    private final Duration shardTimeout = Duration.ofSeconds(2);

    public SearchExecutor(ExecutorService shardExecutor, Path baseDir) {
        this.shardExecutor = shardExecutor;
        this.baseDir = baseDir;
    }

    public SearchExecutor(Path baseDir) {
        // FIXME: temp fix thread pool size
        this(Executors.newFixedThreadPool(Math.min(2, Runtime.getRuntime().availableProcessors())), baseDir);
    }

    private ShardSearcher getOrCreateShardSearcher(int shardId) throws IOException {
        return shardSearcherMap.computeIfAbsent(shardId, id -> {
            try {
                return new ShardSearcher(id, baseDir);
            } catch (IOException e) {
                throw new RuntimeException("Failed to create ShardSearcher for shard " + id, e);
            }
        });
    }

    private record InternalHit(String docId, float score) {}
    private record ShardSearchResult(int shardId, List<InternalHit> hits, long totalHits) {}

    /**
     * Global search across all shards (or a subset) with pagination.
     *
     * @param queryString user query
     * @param shardIds    shards to search; if null/empty -> all shards
     * @param page        zero-based page index
     * @param size        page size
     * @param topK       max hits to retrieve per shard
     */
    @SuppressWarnings({"all"})
    public SearchResult search(String queryString, List<Integer> shardIds, int page, int size, int topK)
            throws ParseException {

        long startNanos = System.nanoTime();
        if (page < 0) page = 0;
        if (size <= 0) size = 10;
        if (shardIds == null || shardIds.isEmpty()) {
            shardIds = new ArrayList<>(shardSearcherMap.keySet());
        }

        // How many docs we might need globally for this page
        int requiredForPage = (page + 1) * size;
        int perShardLimit = Math.max(topK, requiredForPage);
        List<CompletableFuture<ShardSearchResult>> futures = new ArrayList<>();
        for (int shardId : shardIds) {
            final int sid = shardId;
            CompletableFuture<ShardSearchResult> future =
                    CompletableFuture.supplyAsync(() -> searchOneShard(sid, queryString, perShardLimit), shardExecutor);
            futures.add(future);
        }

        long totalHits = 0L;
        List<InternalHit> allHits = new ArrayList<>();
        for (CompletableFuture<ShardSearchResult> future : futures) {
            try {
                ShardSearchResult shardResult = future.get(shardTimeout.toMillis(), TimeUnit.MILLISECONDS);
                if (shardResult != null) {
                    totalHits += shardResult.totalHits();
                    allHits.addAll(shardResult.hits());
                }
            } catch (TimeoutException te) {
                LOGGER.log(Level.WARNING, "Shard search timed out", te);
            } catch (ExecutionException ee) {
                LOGGER.log(Level.SEVERE, "Shard search failed", ee.getCause());
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                LOGGER.log(Level.WARNING, "Shard search interrupted", ie);
            }
        }

        allHits.sort(Comparator.comparingDouble(InternalHit::score).reversed());
        // Global pagination
        int fromIndex = page * size;
        int toIndex = Math.min(fromIndex + size, allHits.size());
        List<InternalHit> pageHits;
        if (fromIndex >= allHits.size()) {
            pageHits = Collections.emptyList();
        } else {
            pageHits = allHits.subList(fromIndex, toIndex);
        }

        List<SearchHit> hits = pageHits.stream()
                .map(h -> new SearchHit(h.docId(), h.score()))
                .toList();
        long tookMicros = TimeUnit.NANOSECONDS.toMicros(System.nanoTime() - startNanos);

        return new SearchResult(
                hits,
                totalHits,
                tookMicros,
                page
        );
    }

    private ShardSearchResult searchOneShard(int shardId, String queryString, int perShardLimit) {
        try {
            ShardSearcher shardSearcher = getOrCreateShardSearcher(shardId);
            if (shardSearcher == null) {
                LOGGER.warning("ShardSearcher not found for shardId=" + shardId);
                return new ShardSearchResult(shardId, List.of(), 0);
            }

            shardSearcher.maybeRefresh();
            IndexSearcher searcher = shardSearcher.getSearcher();
            Analyzer analyzer = shardSearcher.getAnalyzer();
            MultiFieldQueryParser parser = new MultiFieldQueryParser(DEFAULT_SEARCH_FIELDS, analyzer);
            Query luceneQuery = parser.parse(queryString);
            TopDocs topDocs = searcher.search(luceneQuery, perShardLimit);
            List<InternalHit> hits = new ArrayList<>(topDocs.scoreDocs.length);

            for (ScoreDoc sd : topDocs.scoreDocs) {
                Document doc = searcher.doc(sd.doc);
                String docId = doc.get("id");
                if (docId == null) continue;
                hits.add(new InternalHit(docId, sd.score));
            }

            return new ShardSearchResult(shardId, hits, topDocs.totalHits.value);

        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "I/O error searching shard " + shardId, e);
            throw new IndexOperationException("I/O error on shard " + shardId, e);
        } catch (ParseException e) {
            throw new ParseGoneWrongException("Failed to parse query for shard " + shardId, e);
        }
    }

    @Override
    public void close() throws IOException {
        for (ShardSearcher shardSearcher : shardSearcherMap.values()) {
            shardSearcher.close();
        }
    }

}