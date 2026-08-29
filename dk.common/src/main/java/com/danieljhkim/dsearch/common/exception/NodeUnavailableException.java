package com.danieljhkim.dsearch.common.exception;

/**
 * Raised when the node that authoritatively owns a document cannot be reached.
 *
 * <p>Document mutations are never rerouted to another node in this case: a
 * second node would create a competing copy of the same logical document.
 */
public class NodeUnavailableException extends ServiceException {

    private final String nodeId;

    public NodeUnavailableException(String nodeId, String message) {
        super(message);
        this.nodeId = nodeId;
    }

    public String getNodeId() {
        return nodeId;
    }
}
