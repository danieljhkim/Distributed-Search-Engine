#!/bin/bash

set -e

############################################
# CONFIG
############################################

BASE_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )/.."

COORDINATOR_JAR="$BASE_DIR/coordinator/target/coordinator-1.0-SNAPSHOT.jar"
INDEX_NODE_JAR="$BASE_DIR/index-node/target/index-node-1.0-SNAPSHOT.jar"
QUERY_NODE_JAR="$BASE_DIR/query-node/target/query-node-1.0-SNAPSHOT.jar"
GATEWAY_JAR="$BASE_DIR/gateway/target/gateway-1.0-SNAPSHOT.jar"

LOG_DIR="$BASE_DIR/logs"
DATA_DIR="$BASE_DIR/data"

mkdir -p "$LOG_DIR"
mkdir -p "$DATA_DIR"

# Number of nodes
N_INDEX_NODES=${N_INDEX_NODES:-2}
N_QUERY_NODES=${N_QUERY_NODES:-2}

# Base ports
COORDINATOR_PORT=${COORDINATOR_PORT:-7000}
INDEX_BASE_PORT=${INDEX_BASE_PORT:-5000}
QUERY_BASE_PORT=${QUERY_BASE_PORT:-6000}
GATEWAY_PORT=${GATEWAY_PORT:-8080}

# Shards (your coordinator can decide how to assign)
NUM_SHARDS=${NUM_SHARDS:-4}

############################################
# FUNCTIONS
############################################

start_coordinator() {
  echo "Starting Coordinator..."
  export COORDINATOR_PORT
  export NUM_SHARDS

  nohup java -jar "$COORDINATOR_JAR" \
    > "$LOG_DIR/coordinator.log" 2>&1 &

  COORDINATOR_PID=$!
  echo "Coordinator started (PID: $COORDINATOR_PID, port: $COORDINATOR_PORT)"
}

start_index_nodes() {
  echo "Starting $N_INDEX_NODES Index Node(s)..."

  for ((i=0; i< N_INDEX_NODES; i++)); do
    local port=$((INDEX_BASE_PORT + i))
    local node_id="index-$i"
    local node_data_dir="$DATA_DIR/index-node-$i"
    mkdir -p "$node_data_dir"

    export INDEX_NODE_PORT="$port"
    export INDEX_NODE_BASE_DIR="$node_data_dir"
    export NODE_ID="$node_id"
    export NODE_ROLE="INDEX"
    export COORDINATOR_HOST="localhost"
    export COORDINATOR_PORT

    nohup java -jar "$INDEX_NODE_JAR" \
      > "$LOG_DIR/index-node-$i.log" 2>&1 &

    echo "Index Node #$i started (NODE_ID=$node_id, PORT=$port, DATA=$node_data_dir)"
  done
}

start_query_nodes() {
  echo "Starting $N_QUERY_NODES Query Node(s)..."

  for ((i=0; i< N_QUERY_NODES; i++)); do
    local port=$((QUERY_BASE_PORT + i))
    local node_id="query-$i"
    local node_data_dir="$DATA_DIR/query-node-$i"
    mkdir -p "$node_data_dir"

    export QUERY_NODE_PORT="$port"
    export QUERY_NODE_BASE_DIR="$node_data_dir"
    export NODE_ID="$node_id"
    export NODE_ROLE="QUERY"
    export COORDINATOR_HOST="localhost"
    export COORDINATOR_PORT

    nohup java -jar "$QUERY_NODE_JAR" \
      > "$LOG_DIR/query-node-$i.log" 2>&1 &

    echo "Query Node #$i started (NODE_ID=$node_id, PORT=$port, DATA=$node_data_dir)"
  done
}

start_gateway() {
  echo "Starting Gateway..."

  # Gateway talks to coordinator; your Java code can call ClusterService to get shard map
  export COORDINATOR_HOST="localhost"
  export COORDINATOR_PORT
  export SERVER_PORT="$GATEWAY_PORT"   # Spring Boot

  # For backward-compat if you still use direct node envs:
  export INDEX_NODE_HOST="localhost"
  export INDEX_NODE_PORT="$INDEX_BASE_PORT"
  export QUERY_NODE_HOST="localhost"
  export QUERY_NODE_PORT="$QUERY_BASE_PORT"

  nohup java -jar "$GATEWAY_JAR" \
    > "$LOG_DIR/gateway.log" 2>&1 &

  echo "Gateway started (PORT: $GATEWAY_PORT)"
}

stop_cluster() {
  echo "Stopping all cluster processes..."

  pkill -f "coordinator-1.0-SNAPSHOT.jar" || true
  pkill -f "index-node-1.0-SNAPSHOT.jar" || true
  pkill -f "query-node-1.0-SNAPSHOT.jar" || true
  pkill -f "gateway-1.0-SNAPSHOT.jar" || true

  echo "Cluster stopped."
}

############################################
# ENTRYPOINT
############################################

if [[ "$1" == "stop" ]]; then
  stop_cluster
  exit 0
fi

echo "================================================="
echo " Spinning up Distributed Search Engine (multi-node)"
echo "================================================="
echo "Coordinator : localhost:${COORDINATOR_PORT}"
echo "Index Nodes : ${N_INDEX_NODES} (ports ${INDEX_BASE_PORT}..$((INDEX_BASE_PORT + N_INDEX_NODES - 1)))"
echo "Query Nodes : ${N_QUERY_NODES} (ports ${QUERY_BASE_PORT}..$((QUERY_BASE_PORT + N_QUERY_NODES - 1)))"
echo "Gateway     : http://localhost:${GATEWAY_PORT}"
echo "Shards      : ${NUM_SHARDS}"
echo "================================================="

start_coordinator
sleep 1

start_index_nodes
sleep 1

start_query_nodes
sleep 1

start_gateway
sleep 1

echo ""
echo "================================================="
echo "Cluster is running!"
echo "Logs  : $LOG_DIR"
echo "Data  : $DATA_DIR"
echo "Stop  : ./run_cluster_multi.sh stop"
echo "================================================="