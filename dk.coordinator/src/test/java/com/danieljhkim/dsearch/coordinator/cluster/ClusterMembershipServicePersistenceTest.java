package com.danieljhkim.dsearch.coordinator.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.danieljhkim.dsearch.common.cluster.NodeGroup;
import com.danieljhkim.dsearch.common.config.AppConfig;
import com.danieljhkim.dsearch.common.enums.RoutingStrategy;
import com.danieljhkim.dsearch.proto.cluster.NodeRole;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ClusterMembershipServicePersistenceTest {

    @TempDir
    Path tempDir;

    @Test
    void recreatedCoordinatorRetainsTopologyEpochAndMonotonicVersion() {
        Path stateFile = tempDir.resolve("coordinator-topology.properties");
        ClusterMembershipService first = new ClusterMembershipService(config(), stateFile, java.time.Clock.systemUTC());
        first.registerNode(node("index-0"), NodeRole.NODE_ROLE_INDEX);
        String epoch = first.getTopologyEpoch();
        long registeredVersion = first.getTopologyVersion();

        assertTrue(Files.exists(stateFile));
        assertTrue(Files.exists(backupFile(stateFile)));

        ClusterMembershipService recreated =
                new ClusterMembershipService(config(), stateFile, java.time.Clock.systemUTC());

        assertEquals(epoch, recreated.getTopologyEpoch());
        assertEquals(registeredVersion, recreated.getTopologyVersion());
        assertNotNull(recreated.getIndexGroup().getNode("index-0"));

        recreated.registerNode(node("index-1"), NodeRole.NODE_ROLE_INDEX);
        assertTrue(recreated.getTopologyVersion() > registeredVersion);
    }

    @Test
    void legacyStateWithoutFormatVersionRetainsTopology() throws IOException {
        Path stateFile = tempDir.resolve("coordinator-topology.properties");
        ClusterMembershipService beforeMigration =
                new ClusterMembershipService(config(), stateFile, java.time.Clock.systemUTC());
        beforeMigration.registerNode(node("index-0"), NodeRole.NODE_ROLE_INDEX);
        String epoch = beforeMigration.getTopologyEpoch();
        long version = beforeMigration.getTopologyVersion();

        Properties legacyProperties = new Properties();
        try (var input = Files.newInputStream(stateFile)) {
            legacyProperties.load(input);
        }
        legacyProperties.remove("state.format.version");
        assertFalse(legacyProperties.containsKey("state.format.version"));
        try (var output = Files.newOutputStream(stateFile)) {
            legacyProperties.store(output, "legacy coordinator topology");
        }

        ClusterMembershipService recovered =
                new ClusterMembershipService(config(), stateFile, java.time.Clock.systemUTC());

        assertEquals(epoch, recovered.getTopologyEpoch());
        assertEquals(version, recovered.getTopologyVersion());
        NodeGroup.NodeInfo recoveredNode = recovered.getIndexGroup().getNode("index-0");
        assertNotNull(recoveredNode);
        assertEquals("localhost", recoveredNode.getHost());
        assertEquals(5000, recoveredNode.getPort());
        assertEquals(5100, recoveredNode.getHealthPort());
        assertTrue(recoveredNode.isHealthy());
    }

    @Test
    void truncatedAuthoritativeStateFailsInsteadOfResettingTopology() throws IOException {
        Path stateFile = durableStateFile();
        Files.writeString(stateFile, "state.format.version=1\n");

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> new ClusterMembershipService(config(), stateFile, java.time.Clock.systemUTC()));

        assertEquals("Failed to recover authoritative coordinator state from " + stateFile, exception.getMessage());
        assertTrue(exception.getCause().getMessage().contains("Coordinator state is missing topology.epoch"));
        assertTrue(Files.exists(backupFile(stateFile)));
    }

    @Test
    void incompatibleAuthoritativeStateFailsInsteadOfResettingTopology() throws IOException {
        Path stateFile = durableStateFile();
        Files.writeString(stateFile, "state.format.version=999\n");

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> new ClusterMembershipService(config(), stateFile, java.time.Clock.systemUTC()));

        assertEquals("Failed to recover authoritative coordinator state from " + stateFile, exception.getMessage());
        assertTrue(exception.getCause().getMessage().contains("incompatible with supported version"));
    }

    @Test
    void backupCanRestoreAStoppedCoordinatorAfterStateCorruption() throws IOException {
        Path stateFile = durableStateFile();
        Path backup = backupFile(stateFile);
        ClusterMembershipService beforeRestore =
                new ClusterMembershipService(config(), stateFile, java.time.Clock.systemUTC());
        String epoch = beforeRestore.getTopologyEpoch();
        long version = beforeRestore.getTopologyVersion();

        Files.writeString(stateFile, "corrupt state\n");
        assertThrows(
                IllegalStateException.class,
                () -> new ClusterMembershipService(config(), stateFile, java.time.Clock.systemUTC()));

        Path restoreFile = stateFile.resolveSibling(stateFile.getFileName() + ".restore");
        Files.copy(backup, restoreFile, StandardCopyOption.REPLACE_EXISTING);
        Files.move(restoreFile, stateFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);

        ClusterMembershipService restored =
                new ClusterMembershipService(config(), stateFile, java.time.Clock.systemUTC());
        assertEquals(epoch, restored.getTopologyEpoch());
        assertEquals(version, restored.getTopologyVersion());
        assertNotNull(restored.getIndexGroup().getNode("index-0"));
    }

    private Path durableStateFile() {
        Path stateFile = tempDir.resolve("coordinator-topology.properties");
        ClusterMembershipService membership =
                new ClusterMembershipService(config(), stateFile, java.time.Clock.systemUTC());
        membership.registerNode(node("index-0"), NodeRole.NODE_ROLE_INDEX);
        return stateFile;
    }

    private static Path backupFile(Path stateFile) {
        return stateFile.resolveSibling(stateFile.getFileName() + ".bak");
    }

    private static NodeGroup.NodeInfo node(String nodeId) {
        return new NodeGroup.NodeInfo(nodeId, "localhost", 5000, 5100, "NODE_ROLE_INDEX", true);
    }

    private static AppConfig config() {
        AppConfig config = new AppConfig();
        AppConfig.ServiceDiscoveryConfig discovery = new AppConfig.ServiceDiscoveryConfig();
        discovery.setEnabled(true);
        discovery.setNodeExpirySeconds(30);
        config.setServiceDiscovery(discovery);
        config.setIndexNodes(nodeGroup("index-nodes"));
        config.setQueryNodes(nodeGroup("query-nodes"));
        config.setCoordinatorNodes(nodeGroup("coordinator-nodes"));
        return config;
    }

    private static AppConfig.NodeGroupConfig nodeGroup(String label) {
        AppConfig.NodeGroupConfig group = new AppConfig.NodeGroupConfig();
        group.setComponentLabel(label);
        group.setRoutingStrategy(RoutingStrategy.ROUND_ROBIN);
        group.setNodes(List.of());
        return group;
    }
}
