package com.dk.dsearch.common.config;


import lombok.Getter;
import lombok.Setter;

import java.util.List;


@Setter
@Getter
public class AppConfig {

    private ClusterConfig cluster;
    private NodeClientWrapper queryNode;
    private NodeClientWrapper indexNode;


    @Override
    public String toString() {
        return "AppConfig{" +
                "cluster=" + cluster +
                ", queryNode=" + queryNode +
                ", indexNode=" + indexNode +
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
}