package com.danieljhkim.dsearch.common.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.danieljhkim.dsearch.common.config.AppConfig;
import com.danieljhkim.dsearch.common.enums.RoutingStrategy;
import com.danieljhkim.dsearch.proto.cluster.GetClusterInfoResponse;
import com.danieljhkim.dsearch.proto.cluster.NodeInfo;
import com.danieljhkim.dsearch.proto.cluster.NodeRole;
import java.util.List;
import org.junit.jupiter.api.Test;

class NodeGroupTest {

    @Test
    void configGroupsCloneNodesAndRemainIndependent() {
        AppConfig.NodeConfig node = node("node-1", 5001);
        AppConfig.NodeGroupConfig config = new AppConfig.NodeGroupConfig();
        config.setComponentLabel("index");
        config.setRoutingStrategy(RoutingStrategy.LEAST_LOADED);
        config.setNodes(List.of(node));

        NodeGroup group = NodeGroup.fromConfig(NodeRole.NODE_ROLE_INDEX, config);
        assertEquals(
                List.of("node-1"),
                group.getAllNodes().stream().map(NodeGroup.NodeInfo::getNodeId).toList());
        AppConfig.NodeGroupConfig clone = group.toNodeGroupConfigClone();
        clone.getNodes().getFirst().setHost("other-host");
        assertEquals("localhost", group.getNode("node-1").getHost());
        group.addOrUpdateNode(new NodeGroup.NodeInfo("node-2", "localhost", 5002, 5102, "INDEX", true, List.of("p0")));
        assertEquals(List.of("p0"), group.getNode("node-2").getPartitions());
        group.removeNode("node-1");
        assertEquals(1, group.getAllNodes().size());
    }

    @Test
    void responseGroupsMapRoutingAndRejectInvalidInputs() {
        GetClusterInfoResponse response = GetClusterInfoResponse.newBuilder()
                .setComponentLabel("query")
                .setRoutingStrategy(RoutingStrategy.ROUND_ROBIN.name())
                .addNodes(NodeInfo.newBuilder()
                        .setNodeId("q0")
                        .setHost("localhost")
                        .setPort(6000)
                        .setHealthPort(6100)
                        .setRole(NodeRole.NODE_ROLE_QUERY)
                        .build())
                .build();
        NodeGroup group = NodeGroup.fromResponse(response, NodeRole.NODE_ROLE_QUERY);
        assertEquals(RoutingStrategy.ROUND_ROBIN, group.getRoutingStrategy());
        assertEquals("NODE_ROLE_QUERY", group.getNode("q0").getRole());
        assertThrows(NullPointerException.class, () -> NodeGroup.fromConfig(NodeRole.NODE_ROLE_INDEX, null));
        assertThrows(NullPointerException.class, () -> NodeGroup.fromResponse(null, NodeRole.NODE_ROLE_INDEX));
        assertThrows(NullPointerException.class, () -> new NodeGroup.NodeInfo(null, "host", 1, 2, "INDEX", true));
    }

    private static AppConfig.NodeConfig node(String id, int port) {
        AppConfig.NodeConfig node = new AppConfig.NodeConfig();
        node.setId(id);
        node.setHost("localhost");
        node.setPort(port);
        node.setHealthPort(port + 1000);
        return node;
    }
}
