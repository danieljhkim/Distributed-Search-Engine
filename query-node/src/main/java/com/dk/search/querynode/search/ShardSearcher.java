package com.dk.search.querynode.search;

import lombok.Getter;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.NIOFSDirectory;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class ShardSearcher implements Closeable {

    @Getter
    private final int shardId;
    private final Path indexPath;
    private final Directory directory;
    @Getter
    private final Analyzer analyzer;
    private IndexReader reader;
    @Getter
    private IndexSearcher searcher;

    public ShardSearcher(int shardId, Path baseDir) throws IOException {
        this.shardId = shardId;
        this.indexPath = baseDir.resolve("shard-" + shardId);

        if (!Files.exists(indexPath)) {
            throw new IOException("Index directory does not exist for shard " + shardId + ": " + indexPath);
        }

        this.directory = NIOFSDirectory.open(indexPath);
        this.analyzer = new StandardAnalyzer();
        this.reader = DirectoryReader.open(directory);
        this.searcher = new IndexSearcher(reader);
    }

    /**
     * If index is updated by a writer, you can refresh the reader.
     * For now, we keep it simple and ignore NRT/refresh logic.
     */
    public synchronized void maybeRefresh() throws IOException {
        IndexReader newReader = DirectoryReader.openIfChanged((DirectoryReader) reader);
        if (newReader != null) {
            reader.close();
            reader = newReader;
            searcher = new IndexSearcher(reader);
        }
    }

    @Override
    public void close() throws IOException {
        reader.close();
        directory.close();
        analyzer.close();
    }
}