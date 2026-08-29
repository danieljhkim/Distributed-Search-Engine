package com.danieljhkim.dsearch.common.config;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.grpc.ManagedChannel;
import org.junit.jupiter.api.Test;

class GrpcChannelConfigTest {

    @Test
    void indexAndQueryChannelsUseConfiguredDefaultsAndCanBeClosed() {
        ManagedChannel index = GrpcChannelConfig.getIndexChannel();
        ManagedChannel query = GrpcChannelConfig.getQueryChannel();
        try {
            assertNotNull(index);
            assertNotNull(query);
            assertFalse(index.isShutdown());
            assertFalse(query.isShutdown());
        } finally {
            index.shutdownNow();
            query.shutdownNow();
        }
    }
}
