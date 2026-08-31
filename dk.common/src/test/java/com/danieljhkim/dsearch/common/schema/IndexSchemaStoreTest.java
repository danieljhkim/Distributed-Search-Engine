package com.danieljhkim.dsearch.common.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.danieljhkim.dsearch.common.enums.FieldType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IndexSchemaStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void schemaRoundTripsBesideTheIndexAndCopiesForSnapshotRestore() throws Exception {
        IndexSchema schema = IndexSchema.current(
                AnalyzerConfig.standard(),
                List.of(new FieldSchema("title", FieldType.STRING, true, true, false, true, "standard")),
                EmbeddingModelIdentity.of("djl://model", "PyTorch", 384));
        IndexSchemaStore store = new IndexSchemaStore();
        Path indexDir = tempDir.resolve("shard-catalog");
        store.save(indexDir, schema);

        assertTrue(Files.exists(indexDir.resolve(IndexSchemaStore.SCHEMA_FILE_NAME)));
        IndexSchema loaded = store.load(indexDir);
        assertEquals(schema, loaded);

        Path snapshotDir = tempDir.resolve("snapshot").resolve("shard-catalog");
        IndexSchemaStore.copyTo(indexDir, snapshotDir);
        assertEquals(schema, store.load(snapshotDir));
    }
}
