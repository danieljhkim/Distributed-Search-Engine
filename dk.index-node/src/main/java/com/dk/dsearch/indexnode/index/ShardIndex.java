package com.dk.dsearch.indexnode.index;

import com.dk.dsearch.common.exception.IndexOperationException;
import com.dk.dsearch.common.exception.ParseGoneWrongException;
import com.dk.dsearch.common.model.SearchDocument;
import com.dk.dsearch.common.model.SearchHit;
import com.dk.dsearch.common.model.SearchResult;
import com.dk.dsearch.ml.embedding.TextEmbeddingService;
import lombok.Getter;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.*;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.queryparser.classic.MultiFieldQueryParser;
import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.search.*;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

public class ShardIndex implements Closeable {

    public static final String FIELD_ID = "id";
    public static final String FIELD_CONTENT = "content";
    public static final String FIELD_TITLE = "title";
    public static final String FIELD_EMBEDDING = "text_embedding";

    private static final java.util.logging.Logger LOGGER =
            java.util.logging.Logger.getLogger(ShardIndex.class.getName());

    private static final String[] DEFAULT_SEARCH_FIELDS =
            new String[]{FIELD_TITLE, FIELD_CONTENT};

    @Getter
    private final String shardId;
    private final Path indexPath;
    private final Directory directory;
    private final Analyzer analyzer;
    private final IndexWriter indexWriter;
    private final TextEmbeddingService embeddingService;

    private DirectoryReader reader;
    private IndexSearcher searcher;

    public ShardIndex(String shardId, Path baseDir) {
        try {
            this.shardId = shardId;
            this.indexPath = baseDir.resolve("shard-" + shardId);
            Files.createDirectories(indexPath);

            this.directory = FSDirectory.open(indexPath);
            this.analyzer = new StandardAnalyzer();

            IndexWriterConfig config = new IndexWriterConfig(analyzer);
            config.setOpenMode(IndexWriterConfig.OpenMode.CREATE_OR_APPEND);
            this.indexWriter = new IndexWriter(directory, config);

            if (!DirectoryReader.indexExists(directory)) {
                indexWriter.commit();
            }

            this.embeddingService = new TextEmbeddingService();
            this.reader = DirectoryReader.open(directory);
            this.searcher = new IndexSearcher(reader);
        } catch (IOException e) {
            throw new RuntimeException("Failed to initialize ShardIndex for shard " + shardId, e);
        }
    }

    /**
     * Upsert a document by id. Synchronized to serialize with commit/search.
     */
    public synchronized void index(SearchDocument doc) throws IOException {
        Document luceneDoc = toLuceneDocument(doc);
        indexWriter.updateDocument(new Term(FIELD_ID, doc.getId()), luceneDoc);
    }

    /**
     * Delete by docId. Synchronized to serialize with commit/search.
     */
    public synchronized void delete(String docId) throws IOException {
        indexWriter.deleteDocuments(new Term(FIELD_ID, docId));
    }

    /**
     * Search against the current committed index state.
     * NOTE: this sees only what has been committed. Buffered writes in IndexManager
     * are not visible until a flush/commit happens.
     */
    public synchronized SearchResult search(String queryString, int limit, int from) {
        try {
            MultiFieldQueryParser parser = new MultiFieldQueryParser(DEFAULT_SEARCH_FIELDS, analyzer);
            Query luceneQuery = parser.parse(queryString);

            TopDocs topDocs = searcher.search(luceneQuery, limit + from);
            return buildPagedResult(topDocs, limit, from);
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "I/O error searching shard " + shardId, e);
            throw new IndexOperationException("I/O error on shard " + shardId, e);
        } catch (ParseException e) {
            throw new ParseGoneWrongException("Failed to parse query for shard " + shardId, e);
        }
    }

    /**
     * Semantic kNN search using pre-computed text embeddings.
     */
    public synchronized SearchResult semanticSearch(String queryText, int limit, int from) {
        try {
            float[] queryEmbedding = embeddingService.embed(queryText);
            if (queryEmbedding == null || queryEmbedding.length == 0) {
                LOGGER.warning("Empty embedding for query on shard " + shardId);
                return new SearchResult(new ArrayList<>(), 0);
            }
            Query knnQuery = new KnnVectorQuery(FIELD_EMBEDDING, queryEmbedding, limit + from);
            TopDocs topDocs = searcher.search(knnQuery, limit + from);
            return buildPagedResult(topDocs, limit, from);
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "I/O error during semantic search on shard " + shardId, e);
            throw new IndexOperationException("I/O error on shard " + shardId, e);
        }
    }

    private SearchResult buildPagedResult(TopDocs topDocs, int limit, int from) throws IOException {
        ScoreDoc[] scoreDocs = topDocs.scoreDocs;

        int end = Math.min(scoreDocs.length, from + limit);
        if (from >= scoreDocs.length || from >= end) {
            return new SearchResult(new ArrayList<>(), topDocs.totalHits.value);
        }

        List<SearchHit> hits = new ArrayList<>(end - from);
        for (int i = from; i < end; i++) {
            ScoreDoc sd = scoreDocs[i];
            Document doc = searcher.doc(sd.doc);
            String docId = doc.get(FIELD_ID);
            if (docId == null) {
                continue;
            }
            hits.add(new SearchHit(docId, doc.get(FIELD_TITLE), doc.get(FIELD_CONTENT), sd.score));
        }

        return new SearchResult(hits, topDocs.totalHits.value);
    }

    /**
     * Commit all pending index changes and refresh the NRT reader/searcher so that
     * subsequent searches see the new state.
     */
    public synchronized void commit() throws IOException {
        indexWriter.commit();

        // Refresh reader/searcher to reflect new segments.
        DirectoryReader newReader = DirectoryReader.openIfChanged(reader);
        if (newReader != null) {
            DirectoryReader old = reader;
            reader = newReader;
            searcher = new IndexSearcher(reader);
            old.close();
        }
    }

    @SuppressWarnings("all")
    private Document toLuceneDocument(SearchDocument doc) throws IOException {
        Document luceneDoc = new Document();
        // 1) ID: stored, not tokenized
        luceneDoc.add(new StringField(FIELD_ID, doc.getId(), Field.Store.YES));
        // 2) Build combined text from all fields (excluding id)
        StringBuilder contentBuilder = new StringBuilder();
        for (Map.Entry<String, String> entry : doc.getFields().entrySet()) {
            String name = entry.getKey();
            String value = entry.getValue();
            if (value == null || value.isBlank()) {
                continue;
            }
            if (!FIELD_ID.equals(name)) {
                contentBuilder.append(value).append(' ');
            }
            // Store title/content separately if present
            if (FIELD_TITLE.equals(name)) {
                luceneDoc.add(new StoredField(FIELD_TITLE, value));
            }
            if (FIELD_CONTENT.equals(name)) {
                luceneDoc.add(new StoredField(FIELD_CONTENT, value));
            }
        }
        String combinedText = contentBuilder.toString().trim();
        // 3) Full-text field for BM25 / keyword search
        if (!combinedText.isEmpty()) {
            luceneDoc.add(new TextField(FIELD_CONTENT, combinedText, Field.Store.NO));
        }
        // 4) Embedding for semantic search
        if (!combinedText.isEmpty()) {
            float[] embedding = embeddingService.embed(combinedText);
            if (embedding != null && embedding.length > 0) {
                luceneDoc.add(new KnnVectorField(FIELD_EMBEDDING, embedding));
            }
        }

        return luceneDoc;
    }

    @Override
    public synchronized void close() throws IOException {
        try {
            if (reader != null) {
                reader.close();
            }
        } finally {
            try {
                indexWriter.close();
            } finally {
                directory.close();
                analyzer.close();
            }
        }
    }
}