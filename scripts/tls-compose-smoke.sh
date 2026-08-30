#!/usr/bin/env bash

set -euo pipefail

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)
tls_root=$(mktemp -d "${TMPDIR:-/tmp}/dsearch-compose-tls.XXXXXX")
compose_started=false

cleanup() {
  if [[ "$compose_started" == "true" ]]; then
    DSEARCH_TLS_DIR="$tls_root" docker compose -p dsearch-tls-smoke -f "$repo_root/docker-compose.yml" down --volumes --remove-orphans
  fi
  rm -rf "$tls_root"
}
trap cleanup EXIT

command -v docker >/dev/null
command -v openssl >/dev/null
command -v curl >/dev/null
docker info >/dev/null

openssl req -x509 -newkey rsa:2048 -sha256 -nodes -days 7 \
  -subj "/CN=dsearch-compose-ca" \
  -keyout "$tls_root/ca.key" \
  -out "$tls_root/ca.crt" >/dev/null 2>&1

generate_identity() {
  local directory=$1
  local dns_name=$2
  local spiffe_uri=$3
  local service_dir="$tls_root/$directory"
  local extension_file="$tls_root/$directory.ext"
  mkdir -p "$service_dir"
  openssl req -new -newkey rsa:2048 -sha256 -nodes \
    -subj "/CN=$dns_name" \
    -keyout "$service_dir/tls.key" \
    -out "$tls_root/$directory.csr" >/dev/null 2>&1
  {
    echo "subjectAltName=DNS:$dns_name,URI:$spiffe_uri"
    echo "extendedKeyUsage=serverAuth,clientAuth"
    echo "keyUsage=critical,digitalSignature,keyEncipherment"
  } >"$extension_file"
  openssl x509 -req -sha256 -days 7 \
    -in "$tls_root/$directory.csr" \
    -CA "$tls_root/ca.crt" \
    -CAkey "$tls_root/ca.key" \
    -CAserial "$tls_root/ca.srl" \
    -CAcreateserial \
    -extfile "$extension_file" \
    -out "$service_dir/tls.crt" >/dev/null 2>&1
  cp "$tls_root/ca.crt" "$service_dir/ca.crt"
  chmod 600 "$service_dir/tls.key"
}

generate_identity coordinator dsearch-coordinator spiffe://dsearch/node/coordinator/c0
generate_identity gateway dsearch-gateway spiffe://dsearch/admin/gw0
generate_identity query-node-0 dsearch-query-0 spiffe://dsearch/node/query/qn0
generate_identity index-node-0 dsearch-index-0 spiffe://dsearch/node/index/in0

export DSEARCH_TLS_DIR="$tls_root"
docker compose -p dsearch-tls-smoke -f "$repo_root/docker-compose.yml" config --quiet
docker compose -p dsearch-tls-smoke -f "$repo_root/docker-compose.yml" up --build --detach
compose_started=true

deadline=$((SECONDS + 180))
while ((SECONDS < deadline)); do
  if curl --fail --silent --show-error http://localhost:19080/readyz >/dev/null; then
    break
  fi
  sleep 2
done
curl --fail --silent --show-error http://localhost:19080/readyz >/dev/null

expected_services=$'coordinator\ngateway\nindex-node-0\nquery-node-0'
running_services=$(docker compose -p dsearch-tls-smoke -f "$repo_root/docker-compose.yml" ps --services --status running | sort)
[[ "$running_services" == "$expected_services" ]]

for unpublished_endpoint in \
  "coordinator 7000" \
  "coordinator 8080" \
  "gateway 8180" \
  "query-node-0 50052" \
  "query-node-0 8081" \
  "index-node-0 6000" \
  "index-node-0 8090"; do
  read -r service port <<<"$unpublished_endpoint"
  [[ -z "$(docker compose -p dsearch-tls-smoke -f "$repo_root/docker-compose.yml" port "$service" "$port")" ]]
done
