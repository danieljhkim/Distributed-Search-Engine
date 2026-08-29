package com.danieljhkim.dsearch.common.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.danieljhkim.dsearch.common.enums.FieldType;
import java.util.List;
import org.junit.jupiter.api.Test;

class AppConfigTest {

    @Test
    void nestedConfigurationDefaultsAndStringRepresentationsAreUseful() {
        AppConfig.ServiceDiscoveryConfig discovery = new AppConfig.ServiceDiscoveryConfig();
        assertEquals(30, discovery.getRefreshIntervalSeconds());
        assertEquals(30, discovery.getNodeExpirySeconds());
        assertEquals(30, discovery.getMaxStalenessSeconds());

        AppConfig.IndexingConfig indexing = new AppConfig.IndexingConfig();
        assertEquals(100, indexing.getMaxBufferedOpsPerShard());
        assertEquals(5, indexing.getMaxFlushIntervalSeconds());
        AppConfig.TextEmbeddingConfig embedding = new AppConfig.TextEmbeddingConfig();
        assertEquals(1, embedding.getPredictorPoolSize());
        assertEquals(false, embedding.isPredictorPerCall());
        AppConfig.FieldConfig field = new AppConfig.FieldConfig();
        field.setName("price");
        field.setType(FieldType.DOUBLE);
        field.setSortable(true);
        assertEquals("price", field.getName());
        assertEquals(FieldType.DOUBLE, field.getType());
        assertEquals(true, field.isSortable());

        AppConfig.NodeConfig node = new AppConfig.NodeConfig();
        node.setId("n0");
        node.setHost("localhost");
        node.setPort(5000);
        node.setHealthPort(5100);
        assertEquals("n0", node.getId());
        assertEquals(5000, node.getPort());
        assertEquals("NodeConfig{id='n0', host='localhost', port=5000', healthPort=5100}", node.toString());

        AppConfig config = new AppConfig();
        config.setIndexing(indexing);
        config.setMl(new AppConfig.MlConfig());
        config.setFieldConfigs(List.of(field));
        assertEquals("AppConfig{indexNodes=null, queryNodes=null, ml=" + config.getMl() + "}", config.toString());
    }
}
