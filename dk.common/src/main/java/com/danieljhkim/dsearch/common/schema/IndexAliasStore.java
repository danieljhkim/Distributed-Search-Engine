package com.danieljhkim.dsearch.common.schema;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

public final class IndexAliasStore {

    public static final String ALIAS_FILE_NAME = "dsearch-aliases.json";

    private final Path file;
    private final ObjectMapper mapper;
    private final AtomicReference<IndexAliasTable> snapshot = new AtomicReference<>(new IndexAliasTable());

    public IndexAliasStore(Path baseDir) {
        this(baseDir, new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT));
    }

    public IndexAliasStore(Path baseDir, ObjectMapper mapper) {
        this.file = Objects.requireNonNull(baseDir, "baseDir").resolve(ALIAS_FILE_NAME);
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    public IndexAliasTable load() throws IOException {
        if (!Files.exists(file)) {
            IndexAliasTable empty = new IndexAliasTable();
            snapshot.set(empty);
            return empty.copy();
        }
        IndexAliasTable table = mapper.readValue(Files.readAllBytes(file), IndexAliasTable.class);
        if (table.getAliases() == null) {
            table.setAliases(new java.util.LinkedHashMap<>());
        }
        if (table.getReindexJobs() == null) {
            table.setReindexJobs(new java.util.LinkedHashMap<>());
        }
        snapshot.set(table);
        return table.copy();
    }

    public synchronized void save(IndexAliasTable table) throws IOException {
        Objects.requireNonNull(table, "table");
        IndexAliasTable durable = table.copy();
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        byte[] bytes = mapper.writeValueAsBytes(durable);
        Files.write(tmp, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        snapshot.set(durable);
    }

    public IndexAliasTable current() {
        return snapshot.get().copy();
    }

    public String resolve(String aliasOrIndex) {
        if (aliasOrIndex == null || aliasOrIndex.isBlank()) {
            return aliasOrIndex;
        }
        IndexAlias alias = snapshot.get().getAliases().get(aliasOrIndex);
        return alias == null ? aliasOrIndex : alias.getIndexName();
    }

    public IndexAlias getAlias(String name) {
        return snapshot.get().getAliases().get(name);
    }

    public synchronized IndexAlias ensureIdentityAlias(String name) throws IOException {
        IndexAliasTable table = current();
        IndexAlias existing = table.getAliases().get(name);
        if (existing != null) {
            return existing;
        }
        IndexAlias created = new IndexAlias(name, name, null, 1);
        table.getAliases().put(name, created);
        save(table);
        return created;
    }

    public synchronized IndexAlias putAlias(String alias, String indexName, String previousIndexName, int generation)
            throws IOException {
        IndexAliasTable table = current();
        IndexAlias mapping = new IndexAlias(alias, indexName, previousIndexName, generation);
        table.getAliases().put(alias, mapping);
        save(table);
        return mapping;
    }

    public synchronized IndexAlias swap(String alias, String targetIndex) throws IOException {
        Objects.requireNonNull(alias, "alias");
        Objects.requireNonNull(targetIndex, "targetIndex");
        IndexAliasTable table = current();
        IndexAlias current = table.getAliases().get(alias);
        String previous = current == null ? alias : current.getIndexName();
        if (previous.equals(targetIndex)) {
            if (current == null) {
                current = new IndexAlias(alias, targetIndex, null, 1);
                table.getAliases().put(alias, current);
                save(table);
            }
            return current;
        }
        int generation = current == null ? 2 : current.getGeneration() + 1;
        IndexAlias swapped = new IndexAlias(alias, targetIndex, previous, generation);
        table.getAliases().put(alias, swapped);
        save(table);
        return swapped;
    }

    public synchronized IndexAlias rollback(String alias) throws IOException {
        IndexAliasTable table = current();
        IndexAlias current = table.getAliases().get(alias);
        if (current == null || current.getPreviousIndexName() == null || current.getPreviousIndexName().isBlank()) {
            throw new IllegalArgumentException("No previous alias target to roll back for '" + alias + "'");
        }
        String restored = current.getPreviousIndexName();
        String rolledFrom = current.getIndexName();
        IndexAlias rolledBack = new IndexAlias(alias, restored, rolledFrom, current.getGeneration() + 1);
        table.getAliases().put(alias, rolledBack);
        save(table);
        return rolledBack;
    }

    public synchronized void saveJob(ReindexJob job) throws IOException {
        IndexAliasTable table = current();
        table.getReindexJobs().put(job.getJobId(), job);
        save(table);
    }
}
