package com.danieljhkim.dsearch.gateway.config;

import com.danieljhkim.dsearch.common.shard.ShardStateStore;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ShardStateConfig {

    @Bean
    public ShardStateStore shardStateStore() {
        Path stateFile = Paths.get("data/gateway/shard-doc-counts.json").toAbsolutePath();
        return new ShardStateStore(stateFile);
    }
}
