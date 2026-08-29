package com.danieljhkim.dsearch.gateway.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mockStatic;

import com.danieljhkim.dsearch.common.cluster.NodeGroupManager;
import com.danieljhkim.dsearch.common.config.AppConfig;
import com.danieljhkim.dsearch.common.config.ConfigLoader;
import com.danieljhkim.dsearch.common.grpc.NodeClientManager;
import com.danieljhkim.dsearch.common.shard.ShardStateStore;
import com.danieljhkim.dsearch.proto.cluster.ClusterServiceGrpc;
import com.danieljhkim.dsearch.proto.index.IndexServiceGrpc;
import com.danieljhkim.dsearch.proto.query.QueryServiceGrpc;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.boot.web.client.RestTemplateBuilder;

class GatewayConfigurationTest {

    private final GatewayConfig gatewayConfig = new GatewayConfig();

    @Test
    void createsGatewayBeansFromTheLoadedConfigurationAndClosesTheirChannels() {
        AppConfig appConfig = gatewayConfig.appConfig();
        appConfig.getServiceDiscovery().setEnabled(false);
        NodeGroupManager groupManager = gatewayConfig.nodeGroupManager(appConfig);
        NodeClientManager<ClusterServiceGrpc.ClusterServiceBlockingStub> coordinators =
                gatewayConfig.clusterNodeClientManager(groupManager);
        NodeClientManager<QueryServiceGrpc.QueryServiceBlockingStub> queries =
                gatewayConfig.queryNodeClientManager(groupManager, coordinators);
        NodeClientManager<IndexServiceGrpc.IndexServiceBlockingStub> indexes =
                gatewayConfig.indexNodeClientManager(groupManager, coordinators);

        try {
            assertThat(appConfig).isNotNull();
            assertThat(queries).isNotNull();
            assertThat(indexes).isNotNull();
        } finally {
            indexes.shutdown();
            queries.shutdown();
            coordinators.shutdown();
        }
    }

    @Test
    void surfacesConfigurationLoadFailures() throws IOException {
        try (MockedStatic<ConfigLoader> loader = mockStatic(ConfigLoader.class)) {
            loader.when(ConfigLoader::load).thenThrow(new IOException("malformed configuration"));

            assertThatThrownBy(gatewayConfig::appConfig)
                    .isInstanceOf(RuntimeException.class)
                    .hasMessage("Failed to load application configuration")
                    .hasCauseInstanceOf(IOException.class);
        }
    }

    @Test
    void createsHttpClientAndShardStateBeansWithExpectedDefaults() {
        assertThat(new HttpClientConfig().restTemplate(new RestTemplateBuilder()))
                .isNotNull();

        ShardStateStore store = new ShardStateConfig().shardStateStore();
        assertThat(store).isNotNull();
    }

    @Test
    void serverPropertiesRemainBindable() {
        GatewayConfig.ServerProperties properties = gatewayConfig.serverProperties();
        properties.setHost("127.0.0.1");
        properties.setPort(8080);

        assertThat(properties.getHost()).isEqualTo("127.0.0.1");
        assertThat(properties.getPort()).isEqualTo(8080);
    }
}
