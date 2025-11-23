package com.dk.search.gateway.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;


@Configuration
public class NodePropertiesConfig {

    @Bean
    @ConfigurationProperties(prefix = "query-node.client")
    public NodeProperties queryNodeClientProperties() {
        return new NodeProperties();
    }

    @Bean
    @ConfigurationProperties(prefix = "index-node.client")
    public NodeProperties indexNodeClientProperties() {
        return new NodeProperties();
    }

    @Setter
    @Getter
    public static class NodeProperties {
        private String host;
        private List<Integer> ports;
    }
}