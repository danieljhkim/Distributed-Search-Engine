#!/bin/bash

set -e

############################################
# CONFIG
############################################

BASE_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )/.."

INDEX_NODE_JAR="$BASE_DIR/dk.index-node/target/dk.index-node-1.0-SNAPSHOT.jar"
QUERY_NODE_JAR="$BASE_DIR/dk.query-node/target/dk.query-node-1.0-SNAPSHOT.jar"
GATEWAY_JAR="$BASE_DIR/dk.gateway/target/dk.gateway-1.0-SNAPSHOT.jar"

LOG_DIR="$BASE_DIR/logs"
DATA_DIR="$BASE_DIR/data"

JAVA_OPTS="--add-modules jdk.incubator.vector"

mkdir -p "$LOG_DIR"
mkdir -p "$DATA_DIR/index-node"
mkdir -p "$DATA_DIR/query-node"

############################################
# FUNCTIONS
############################################

start_index_node() {
  echo "Starting Index Node..."
  export INDEX_NODE_PORT=5000
  export INDEX_NODE_BASE_DIR="$DATA_DIR/index-node"
  export INDEX_NODE_HEALTH_PORT=5100

  nohup java $JAVA_OPTS -jar "$INDEX_NODE_JAR" \
    > "$LOG_DIR/index-node.log" 2>&1 &

  INDEX_PID=$!
  echo "Index Node started (PID: $INDEX_PID)"
}

start_query_node() {
  echo "Starting Query Node..."
  export QUERY_NODE_PORT=6000
  export QUERY_NODE_HEALTH_PORT=6100

  nohup java -jar "$QUERY_NODE_JAR" \
    > "$LOG_DIR/query-node.log" 2>&1 &

  QUERY_PID=$!
  echo "Query Node started (PID: $QUERY_PID)"
}

start_gateway() {
  echo "Starting Gateway..."
  export INDEX_NODE_HOST="localhost"
  export QUERY_NODE_HOST="localhost"
  export INDEX_NODE_PORT=5000
  export QUERY_NODE_PORT=6000

  nohup java -jar "$GATEWAY_JAR" \
    > "$LOG_DIR/gateway.log" 2>&1 &

  GATEWAY_PID=$!
  echo "Gateway started (PID: $GATEWAY_PID)"
}

stop_cluster() {
  echo "Stopping all cluster processes..."

  pkill -f dk.index-node-1.0-SNAPSHOT.jar || true
  pkill -f dk.query-node-1.0-SNAPSHOT.jar || true
  pkill -f dk.gateway-1.0-SNAPSHOT.jar || true

  echo "Cluster stopped."
}


############################################
# ENTRYPOINT
############################################

if [[ "$1" == "stop" ]]; then
  stop_cluster
  exit 0
fi

echo "====================================="
echo " Spinning up Distributed Search Engine"
echo "====================================="

start_index_node
sleep 1

start_query_node
sleep 1

start_gateway
sleep 1

echo ""
echo "====================================="
echo "Cluster is running!"
echo "-------------------------------------"
echo "Index Node : localhost:5000"
echo "Query Node : localhost:6000"
echo "Gateway    : http://localhost:8080"
echo "Logs       : $LOG_DIR"
echo "Stop       : ./run_cluster.sh stop"
echo "====================================="