package com.danieljhkim.dsearch.gateway.config;

import java.nio.file.Path;
import java.nio.file.Paths;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.danieljhkim.dsearch.common.shard.ShardStateStore;

@Configuration
public class ShardStateConfig {

	@Bean
	public ShardStateStore shardStateStore() {
		Path stateFile = Paths.get("data/gateway/shard-doc-counts.json").toAbsolutePath();
		return new ShardStateStore(stateFile);
	}
}