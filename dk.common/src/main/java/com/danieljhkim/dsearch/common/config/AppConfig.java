package com.danieljhkim.dsearch.common.config;


import lombok.Getter;
import lombok.Setter;

import java.util.List;


@Setter
@Getter
public class AppConfig {

    private ClusterConfig cluster;
    private NodeClientWrapper queryNode;
    private NodeClientWrapper indexNode;
    private MlConfig ml;


    @Override
    public String toString() {
        return "AppConfig{" +
                "cluster=" + cluster +
                ", queryNode=" + queryNode +
                ", indexNode=" + indexNode +
                ", ml=" + ml +
                '}';
    }

    @Setter
    @Getter
    public static class NodeProperties {
        private String host;
        private List<Integer> ports;

        @Override
        public String toString() {
            return "NodeProperties{" +
                    "host='" + host + '\'' +
                    ", ports=" + ports +
                    '}';
        }
    }

    @Setter
    @Getter
    public static class ClusterConfig {
        private List<IndexNodeConfig> indexNodes;

        @Override
        public String toString() {
            return "ClusterConfig{" +
                    "indexNodes=" + indexNodes +
                    '}';
        }
    }

    @Setter
    @Getter
    public static class IndexNodeConfig {
        private String id;
        private String host;
        private int port;

        @Override
        public String toString() {
            return "IndexShardConfig{" +
                    "id=" + id +
                    ", host='" + host + '\'' +
                    ", port=" + port +
                    '}';
        }
    }

    @Setter
    @Getter
    public static class NodeClientWrapper {
        private NodeProperties client;

        @Override
        public String toString() {
            return "NodeClientWrapper{" +
                    "client=" + client +
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
        private String id;
        private String url;
        private String engine;

        @Override
        public String toString() {
            return "TextEmbeddingConfig{" +
                    "id='" + id + '\'' +
                    ", url='" + url + '\'' +
                    ", engine='" + engine + '\'' +
                    '}';
        }
    }
}