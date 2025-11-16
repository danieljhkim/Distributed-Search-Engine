package com.dk.search.indexnode.index;

import com.dk.search.common.model.SearchDocument;
import lombok.Getter;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.TextField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.store.NIOFSDirectory;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class ShardIndex implements Closeable {

    public static final String FIELD_ID = "id";
    public static final String FIELD_CONTENT = "content";

    @Getter
    private final int shardId;
    private final Path indexPath;
    private final Directory directory;
    private final Analyzer analyzer;
    private final IndexWriter indexWriter;

    public ShardIndex(int shardId, Path baseDir) {
        try {
            this.shardId = shardId;
            this.indexPath = baseDir.resolve("shard-" + shardId);

            Files.createDirectories(indexPath);
            this.directory = FSDirectory.open(indexPath);
            this.analyzer = new StandardAnalyzer();

            IndexWriterConfig config = new IndexWriterConfig(analyzer);
            this.indexWriter = new IndexWriter(directory, config);
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

        // Optional: also index each field separately for future field-specific queries
        // doc.getFields().forEach((name, value) -> {
        //     if (value != null && !value.isEmpty()) {
        //         luceneDoc.add(new TextField(name, value, Field.Store.NO));
        //     }
        // });

        return luceneDoc;
    }

    @Override
    public void close() throws IOException {
        indexWriter.close();
        directory.close();
        analyzer.close();
    }
}