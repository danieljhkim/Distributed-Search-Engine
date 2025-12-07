package com.danieljhkim.dsearch.common.config;

import com.danieljhkim.dsearch.common.enums.RoutingStrategy;
import lombok.Getter;
import lombok.Setter;

import java.util.List;


@Setter
@Getter
public class AppConfig {

    private NodeGroupConfig indexNodes;
    private NodeGroupConfig queryNodes;
    private MlConfig ml;

    @Override
    public String toString() {
        return "AppConfig{" +
                "indexNodes=" + indexNodes +
                ", queryNodes=" + queryNodes +
                ", ml=" + ml +
                '}';
    }


    @Setter
    @Getter
    public static class NodeGroupConfig {
        private List<NodeConfig> nodes;
        private RoutingStrategy routingStrategy = RoutingStrategy.LEAST_LOADED;

        @Override
        public String toString() {
            return "NodeGroupConfig{" +
                    "nodes=" + nodes +
                    ", routingStrategy=" + routingStrategy +
                    '}';
        }
    }

    @Setter
    @Getter
    public static class NodeConfig {
        private String id;
        private String host;
        private int port;
        private int healthPort;

        @Override
        public String toString() {
            return "NodeConfig{" +
                    "id='" + id + '\'' +
                    ", host='" + host + '\'' +
                    ", port=" + port + '\'' +
                    ", healthPort=" + healthPort +
                    '}';
        }
    }

    @Setter
    @Getter
    public static class MlConfig {
        private ModelsConfig models;

        @Override
        public String toString() {
            return "MlConfig{" +
                    "models=" + models +
                    '}';
        }
    }

    @Setter
    @Getter
    public static class ModelsConfig {
        private TextEmbeddingConfig textEmbedding;

        @Override
        public String toString() {
            return "ModelsConfig{" +
                    "textEmbedding=" + textEmbedding +
                    '}';
        }
    }

    @Setter
    @Getter
    public static class TextEmbeddingConfig {
        private String url;
        private String engine;

        @Override
        public String toString() {
            return "TextEmbeddingConfig{" +
                    "url='" + url + '\'' +
                    ", engine='" + engine + '\'' +
                    '}';
        }
    }
}