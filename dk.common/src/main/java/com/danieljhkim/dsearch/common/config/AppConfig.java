package com.danieljhkim.dsearch.common.config;

import com.danieljhkim.dsearch.common.enums.FieldType;
import com.danieljhkim.dsearch.common.enums.RoutingStrategy;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
public class AppConfig {
    private ServiceDiscoveryConfig serviceDiscovery;
    private NodeGroupConfig indexNodes;
    private NodeGroupConfig queryNodes;
    private NodeGroupConfig coordinatorNodes;
    private NodeGroupConfig gatewayNodes;
    private MlConfig ml;
    private RequestLimitsConfig requestLimits;
    private List<FieldConfig> fieldConfigs;

    @Override
    public String toString() {
        return "AppConfig{" + "indexNodes=" + indexNodes + ", queryNodes=" + queryNodes + ", ml=" + ml + '}';
    }

    @Setter
    @Getter
    public static class ServiceDiscoveryConfig {
        private boolean enabled = false;
        private int refreshIntervalSeconds = 30;
    }

    @Setter
    @Getter
    public static class NodeGroupConfig {
        private List<NodeConfig> nodes;
        private RoutingStrategy routingStrategy;
        private String componentLabel;
        private int replicationFactor = 1;

        @Override
        public String toString() {
            return "NodeGroupConfig{" + "nodes="
                    + nodes + ", routingStrategy="
                    + routingStrategy + ", componentLabel='"
                    + componentLabel + '}';
        }
    }

    @Setter
    @Getter
    public static class NodeConfig {
        private String id;
        private String host;
        private int port;
        private int healthPort;
        private String role;

        @Override
        public String toString() {
            return "NodeConfig{" + "id='"
                    + id + '\'' + ", host='"
                    + host + '\'' + ", port="
                    + port + '\'' + ", healthPort="
                    + healthPort + '}';
        }
    }

    @Setter
    @Getter
    public static class MlConfig {
        private ModelsConfig models;

        @Override
        public String toString() {
            return "MlConfig{" + "models=" + models + '}';
        }
    }

    @Setter
    @Getter
    public static class ModelsConfig {
        private TextEmbeddingConfig textEmbedding;

        @Override
        public String toString() {
            return "ModelsConfig{" + "textEmbedding=" + textEmbedding + '}';
        }
    }

    @Setter
    @Getter
    public static class TextEmbeddingConfig {
        private String url;
        private String engine;

        @Override
        public String toString() {
            return "TextEmbeddingConfig{" + "url='" + url + '\'' + ", engine='" + engine + '\'' + '}';
        }
    }

    @Setter
    @Getter
    public static class RequestLimitsConfig {
        private int maxSize = 1000;
        private int maxQueryLength = 1024;

        @Override
        public String toString() {
            return "RequestLimitsConfig{" + "maxSize=" + maxSize + ", maxQueryLength=" + maxQueryLength + '}';
        }
    }

    /**
     * Configuration for a document field specifying its type and capabilities.
     */
    @Setter
    @Getter
    public static class FieldConfig {
        private String name;
        private FieldType type = FieldType.STRING;
        private boolean filterable = false;
        private boolean sortable = false;
        private boolean facetable = false;
        private boolean highlightable = false;

        @Override
        public String toString() {
            return "FieldConfig{" + "name='"
                    + name + '\'' + ", type="
                    + type + ", filterable="
                    + filterable + ", sortable="
                    + sortable + ", facetable="
                    + facetable + ", highlightable="
                    + highlightable + '}';
        }
    }
}
