package com.danieljhkim.dsearch.common.shard;


import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import lombok.Getter;
import lombok.Setter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ShardStateStore {

    private final Path file;
    private final ObjectMapper mapper;

    public ShardStateStore(Path file) {
        this.file = file;
        this.mapper = new ObjectMapper()
                .enable(SerializationFeature.INDENT_OUTPUT);
    }

    public ShardDocSnapshot load() throws IOException {
        if (!Files.exists(file)) {
            return new ShardDocSnapshot(); // empty
        }
        return mapper.readValue(Files.readAllBytes(file), ShardDocSnapshot.class);
    }

    public void save(ShardDocSnapshot snapshot) throws IOException {
        snapshot.setGeneratedAt(Instant.now().toString());
        // write atomically: temp -> move
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        byte[] bytes = mapper.writeValueAsBytes(snapshot);
        Files.createDirectories(file.getParent());
        Files.write(tmp, bytes, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING);
        Files.move(tmp, file,
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE);
    }

    @Setter
    @Getter
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ShardDocSnapshot {
        private String generatedAt;
        private List<NodeEntry> nodes = new ArrayList<>();

    }

    @Setter
    @Getter
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class NodeEntry {
        private String nodeId;
        private Map<String, Long> shards = new HashMap<>();

    }
}