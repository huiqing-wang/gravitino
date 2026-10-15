#!/bin/bash
#
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#  http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.
#
# Metalake-level Gravitino-to-Gravitino federation (方案2).
#
#   1. Create a LOCAL metalake carrying federation.* properties that point to a
#      remote Gravitino metalake.
#   2. Run FederationSyncCli to mirror every remote catalog into the local
#      metalake as a proxy catalog (provider=gravitino).
#
# Usage:
#   federation-metalake.sh [sync|unsync]
#
# Configure via environment variables (or edit the defaults below):
#   GRAVITINO_HOME        Gravitino distribution root (holds catalogs/gravitino/libs)
#   LOCAL_URI             Local Gravitino server URI      (default http://localhost:8090)
#   LOCAL_METALAKE        Local metalake name to create   (default region_b_federation)
#   REMOTE_URI            Remote Gravitino server URI     (required)
#   REMOTE_METALAKE       Remote metalake name            (required)
#   AUTH_TYPE             simple | basic                  (default simple)
#   AUTH_USERNAME         Remote auth username            (optional)
#   AUTH_PASSWORD         Remote auth password            (optional)
#   FORCE                 true to force-drop on unsync    (default false)

set -euo pipefail

# ----------------------------- configuration --------------------------------
# GRAVITINO_HOME: explicit env wins; otherwise fall back to the built
# distribution/package-all inside the source tree.
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../../.." && pwd)"
if [[ -z "${GRAVITINO_HOME:-}" ]]; then
  if [[ -d "${REPO_ROOT}/distribution/package-all" ]]; then
    GRAVITINO_HOME="${REPO_ROOT}/distribution/package-all"
  else
    GRAVITINO_HOME="${REPO_ROOT}"
  fi
fi
LOCAL_URI="${LOCAL_URI:-http://localhost:8090}"
LOCAL_METALAKE="${LOCAL_METALAKE:-region_b_federation}"
REMOTE_URI="${REMOTE_URI:-}"
REMOTE_METALAKE="${REMOTE_METALAKE:-}"
AUTH_TYPE="${AUTH_TYPE:-simple}"
AUTH_USERNAME="${AUTH_USERNAME:-}"
AUTH_PASSWORD="${AUTH_PASSWORD:-}"
FORCE="${FORCE:-false}"

ACTION="${1:-sync}"

CATALOG_LIBS="${GRAVITINO_HOME}/catalogs/gravitino/libs"
SERVER_LIBS="${GRAVITINO_HOME}/libs"
CLI_CLASS="org.apache.gravitino.catalog.gravitino.FederationSyncCli"

# ------------------------------- helpers ------------------------------------
log()  { echo "[federation] $*"; }
fail() { echo "[federation] ERROR: $*" >&2; exit 1; }

require_config() {
  [[ -n "${REMOTE_URI}" ]]      || fail "REMOTE_URI is not set"
  [[ -n "${REMOTE_METALAKE}" ]] || fail "REMOTE_METALAKE is not set"
  [[ -d "${CATALOG_LIBS}" ]]    || fail "catalog libs dir not found: ${CATALOG_LIBS} (set GRAVITINO_HOME)"
}

# Build the JSON properties block for the local metalake.
build_metalake_properties() {
  local props
  props="\"federation.type\":\"gravitino\","
  props+="\"federation.uri\":\"${REMOTE_URI}\","
  props+="\"federation.metalake\":\"${REMOTE_METALAKE}\","
  props+="\"federation.auth.type\":\"${AUTH_TYPE}\""
  if [[ -n "${AUTH_USERNAME}" ]]; then
    props+=",\"federation.auth.username\":\"${AUTH_USERNAME}\""
  fi
  if [[ -n "${AUTH_PASSWORD}" ]]; then
    props+=",\"federation.auth.password\":\"${AUTH_PASSWORD}\""
  fi
  echo "${props}"
}

# Create the local federated metalake (idempotent: ignores "already exists").
create_local_metalake() {
  local payload props http_code
  props="$(build_metalake_properties)"
  payload="{\"name\":\"${LOCAL_METALAKE}\",\"comment\":\"Federated view of ${REMOTE_METALAKE}\",\"properties\":{${props}}}"

  log "Creating local metalake '${LOCAL_METALAKE}' on ${LOCAL_URI} -> remote ${REMOTE_METALAKE}@${REMOTE_URI}"
  http_code="$(curl -s -o /tmp/federation_ml_resp.$$ -w '%{http_code}' \
      -X POST "${LOCAL_URI}/api/metalakes" \
      -H 'Content-Type: application/json' \
      -d "${payload}")"

  case "${http_code}" in
    200) log "Local metalake created." ;;
    409) log "Local metalake '${LOCAL_METALAKE}' already exists, skipping create." ;;
    *)   cat /tmp/federation_ml_resp.$$ >&2; rm -f /tmp/federation_ml_resp.$$
         fail "create metalake failed with HTTP ${http_code}" ;;
  esac
  rm -f /tmp/federation_ml_resp.$$
}

run_cli() {
  local sub="$1"; shift
  java -cp "${CATALOG_LIBS}/*:${SERVER_LIBS}/*" "${CLI_CLASS}" "${sub}" \
    --metalake "${LOCAL_METALAKE}" --local-uri "${LOCAL_URI}" "$@"
}

# -------------------------------- actions -----------------------------------
do_sync() {
  require_config
  create_local_metalake
  log "Syncing remote catalogs into local metalake..."
  run_cli sync
  log "Done."
}

do_unsync() {
  require_config
  log "Removing federated proxy catalogs from '${LOCAL_METALAKE}'..."
  local extra=()
  [[ "${FORCE}" == "true" ]] && extra+=(--force)
  run_cli unsync ${extra[@]+"${extra[@]}"}
  log "Done."
}

case "${ACTION}" in
  sync)   do_sync ;;
  unsync) do_unsync ;;
  *)      fail "unknown action '${ACTION}' (expected: sync | unsync)" ;;
esac
