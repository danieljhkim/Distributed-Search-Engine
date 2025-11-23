package com.dk.search.indexnode.index;

import com.dk.search.common.exception.IndexOperationException;
import com.dk.search.common.exception.ParseGoneWrongException;
import com.dk.search.common.model.SearchDocument;
import com.dk.search.common.model.SearchHit;
import com.dk.search.common.model.SearchResult;
import lombok.Getter;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.*;
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
    private static final java.util.logging.Logger LOGGER = java.util.logging.Logger.getLogger(ShardIndex.class.getName());
    private static final String[] DEFAULT_SEARCH_FIELDS = new String[]{"title", "body", "content"};

    @Getter
    private final String shardId;
    private final Path indexPath;
    private final Directory directory;
    private final Analyzer analyzer;
    private final IndexWriter indexWriter;
    private final IndexSearcher searcher;

    public ShardIndex(String shardId, Path baseDir) {
        try {
            this.shardId = shardId;
            this.indexPath = baseDir.resolve("shard-" + shardId);
            Files.createDirectories(indexPath);
            this.directory = FSDirectory.open(indexPath);
            this.analyzer = new StandardAnalyzer();
            IndexWriterConfig config = new IndexWriterConfig(analyzer);
            this.indexWriter = new IndexWriter(directory, config);
            IndexReader reader = DirectoryReader.open(directory);
            this.searcher = new IndexSearcher(reader);

        } catch (IOException e) {
            throw new RuntimeException("Failed to initialize ShardIndex for shard " + shardId, e);
        }
    }

    public synchronized void index(SearchDocument doc) throws IOException {
        Document luceneDoc = toLuceneDocument(doc);
        // upsert by id (same as query-node expects)
        indexWriter.updateDocument(new Term(FIELD_ID, doc.getId()), luceneDoc);
    }

    public synchronized void delete(String docId) throws IOException {
        indexWriter.deleteDocuments(new Term(FIELD_ID, docId));
    }

    public SearchResult search(String queryString, int limit, int from) {
        try {
            // TODO: later support fielded search by passing fields from SearchRequest
            MultiFieldQueryParser parser = new MultiFieldQueryParser(DEFAULT_SEARCH_FIELDS, analyzer);
            Query luceneQuery = parser.parse(queryString);
            TopDocs topDocs = searcher.search(luceneQuery, limit + from);
            ScoreDoc[] scoreDocs = topDocs.scoreDocs;
            int end = Math.min(scoreDocs.length, from + limit);
            List<ScoreDoc> pageHits = Arrays.asList(scoreDocs).subList(from, end);
            List<SearchHit> hits = new ArrayList<>(pageHits.size());

            for (ScoreDoc sd : pageHits) {
                Document doc = searcher.doc(sd.doc);
                String docId = doc.get("id");
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

    public synchronized void commit() throws IOException {
        indexWriter.commit();
    }

    private Document toLuceneDocument(SearchDocument doc) {
        Document luceneDoc = new Document();
        // id field: stored, not tokenized
        luceneDoc.add(new StringField(FIELD_ID, doc.getId(), Field.Store.YES));
        // combine all fields into a single "content" field so query-node's MultiFieldQueryParser on "content" works
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
    public void close() throws IOException {
        indexWriter.close();
        directory.close();
        analyzer.close();
    }
}