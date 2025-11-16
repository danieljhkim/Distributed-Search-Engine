package com.dk.search.querynode.search;

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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class SearchExecutor implements Closeable {

    private static final String[] DEFAULT_SEARCH_FIELDS = new String[]{"title", "body", "content"};
    private final Path baseDir;
    private final Map<Integer, ShardSearcher> shardSearcherMap = new ConcurrentHashMap<>();

    public SearchExecutor(Path baseDir) {
        this.baseDir = baseDir;
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

    public SearchResult search(String queryString, List<Integer> shardIds, int topK)
            throws IOException, ParseException {

        long startNanos = System.nanoTime();

        if (shardIds == null || shardIds.isEmpty()) {
            shardIds = new ArrayList<>(shardSearcherMap.keySet());
        }

        List<InternalHit> allHits = new ArrayList<>();
        long totalHits = 0;

        for (int shardId : shardIds) {
            ShardSearcher shardSearcher = getOrCreateShardSearcher(shardId);
            shardSearcher.maybeRefresh();

            IndexSearcher searcher = shardSearcher.getSearcher();
            Analyzer analyzer = shardSearcher.getAnalyzer();

            MultiFieldQueryParser parser = new MultiFieldQueryParser(DEFAULT_SEARCH_FIELDS, analyzer);
            Query luceneQuery = parser.parse(queryString);

            TopDocs topDocs = searcher.search(luceneQuery, topK);
            totalHits += topDocs.totalHits.value;

            for (ScoreDoc sd : topDocs.scoreDocs) {
                Document luceneDoc = searcher.doc(sd.doc);
                String docId = luceneDoc.get("id"); // we stored this as StringField
                if (docId == null) {
                    continue;
                }
                allHits.add(new InternalHit(docId, sd.score));
            }
        }

        // Merge topK globally
        allHits.sort(Comparator.comparingDouble(InternalHit::score).reversed());
        if (allHits.size() > topK) {
            allHits = allHits.subList(0, topK);
        }

        List<SearchResult.SearchHit> resultHits = new ArrayList<>();
        for (InternalHit h : allHits) {
            resultHits.add(new SearchResult.SearchHit(h.docId(), h.score()));
        }

        long tookMillis = (System.nanoTime() - startNanos) / 1_000_000;

        return new SearchResult(resultHits, totalHits, tookMillis);
    }

    @Override
    public void close() throws IOException {
        for (ShardSearcher shardSearcher : shardSearcherMap.values()) {
            shardSearcher.close();
        }
    }

    private record InternalHit(String docId, float score) {
    }
}