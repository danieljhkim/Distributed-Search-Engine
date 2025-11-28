package com.dk.dsearch.indexnode.index;

import com.dk.dsearch.common.exception.IndexOperationException;
import com.dk.dsearch.common.exception.ParseGoneWrongException;
import com.dk.dsearch.common.model.SearchDocument;
import com.dk.dsearch.common.model.SearchHit;
import com.dk.dsearch.common.model.SearchResult;
import lombok.Getter;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.queryparser.classic.MultiFieldQueryParser;
import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Level;

public class ShardIndex implements Closeable {

    public static final String FIELD_ID = "id";
    public static final String FIELD_CONTENT = "content";

    private static final java.util.logging.Logger LOGGER =
            java.util.logging.Logger.getLogger(ShardIndex.class.getName());

    private static final String[] DEFAULT_SEARCH_FIELDS =
            new String[]{"title", "body", FIELD_CONTENT};

    @Getter
    private final String shardId;
    private final Path indexPath;
    private final Directory directory;
    private final Analyzer analyzer;
    private final IndexWriter indexWriter;

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
            ScoreDoc[] scoreDocs = topDocs.scoreDocs;

            int end = Math.min(scoreDocs.length, from + limit);
            if (from >= scoreDocs.length || from >= end) {
                return new SearchResult(new ArrayList<>(), topDocs.totalHits.value);
            }

            List<ScoreDoc> pageHits = Arrays.asList(scoreDocs).subList(from, end);
            List<SearchHit> hits = new ArrayList<>(pageHits.size());

            for (ScoreDoc sd : pageHits) {
                Document doc = searcher.doc(sd.doc);
                String docId = doc.get(FIELD_ID);
                if (docId == null) continue;
                hits.add(new SearchHit(docId, sd.score, doc.toString()));
            }

            return new SearchResult(hits, topDocs.totalHits.value);
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "I/O error searching shard " + shardId, e);
            throw new IndexOperationException("I/O error on shard " + shardId, e);
        } catch (ParseException e) {
            throw new ParseGoneWrongException("Failed to parse query for shard " + shardId, e);
        }
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

    private Document toLuceneDocument(SearchDocument doc) {
        Document luceneDoc = new Document();
        // id field: stored, not tokenized
        luceneDoc.add(new StringField(FIELD_ID, doc.getId(), Field.Store.YES));

        // Combine all fields into a single "content" field so query-node's
        // MultiFieldQueryParser with "content" still works.
        StringBuilder sb = new StringBuilder();
        doc.getFields().forEach((k, v) -> {
            if (v != null && !v.isEmpty()) {
                if (!k.equals(FIELD_ID)) { // avoid double-adding id if it's in the map
                    sb.append(v).append(' ');
                }
            }
        });
        if (!sb.isEmpty()) {
            luceneDoc.add(new TextField(FIELD_CONTENT, sb.toString(), Field.Store.NO));
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