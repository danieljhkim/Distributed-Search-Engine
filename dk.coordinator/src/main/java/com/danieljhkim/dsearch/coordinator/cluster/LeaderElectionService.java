package com.danieljhkim.dsearch.coordinator.cluster;

import java.util.Optional;

public class LeaderElectionService {
    private String leaderNodeId;

    public void electLeader(String nodeId) {
        leaderNodeId = nodeId;
    }

    public Optional<String> getLeader() {
        return Optional.ofNullable(leaderNodeId);
    }

    public void removeLeader() {
        leaderNodeId = null;
    }
}
