package com.danieljhkim.dsearch.common.schema;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Objects;

public final class IndexSchemaStore {

    public static final String SCHEMA_FILE_NAME = "dsearch-schema.json";

    private final ObjectMapper mapper;

    public IndexSchemaStore() {
        this(new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT));
    }

    public IndexSchemaStore(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    public Path schemaFile(Path indexDirectory) {
        return Objects.requireNonNull(indexDirectory, "indexDirectory").resolve(SCHEMA_FILE_NAME);
    }

    public IndexSchema load(Path indexDirectory) throws IOException {
        Path file = schemaFile(indexDirectory);
        if (!Files.exists(file)) {
            return null;
        }
        return mapper.readValue(Files.readAllBytes(file), IndexSchema.class);
    }

    public void save(Path indexDirectory, IndexSchema schema) throws IOException {
        Objects.requireNonNull(schema, "schema");
        Files.createDirectories(indexDirectory);
        Path file = schemaFile(indexDirectory);
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        byte[] bytes = mapper.writeValueAsBytes(schema);
        Files.write(tmp, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    public static void copyTo(Path sourceDirectory, Path destinationDirectory) throws IOException {
        Path source = sourceDirectory.resolve(SCHEMA_FILE_NAME);
        if (!Files.exists(source)) {
            return;
        }
        Files.createDirectories(destinationDirectory);
        Files.copy(
                source,
                destinationDirectory.resolve(SCHEMA_FILE_NAME),
                StandardCopyOption.REPLACE_EXISTING);
    }
}
