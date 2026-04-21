#!/usr/bin/env bash
# Temporal Order Processing POC — Linux/Gitpod runner
#
# Usage:
#   ./run.sh           — install deps, start server, run all 4 test scenarios
#   ./run.sh --stop    — stop server and any running worker
#   ./run.sh --status  — show running processes and log paths
#
# Dependencies installed automatically: Java 21, Maven, Temporal CLI

set -euo pipefail

# ── Config ────────────────────────────────────────────────────────────────────
TEMPORAL_CLI_VERSION="1.6.2"
TEMPORAL_BIN="${HOME}/.temporalio/bin/temporal"
JAR="target/temporal-order-poc-1.0-SNAPSHOT.jar"
TEMPORAL_PORT=7233
UI_PORT=8080
SERVER_LOG="/tmp/temporal-server.log"
WORKER_LOG="/tmp/temporal-worker.log"

# ── Colours ───────────────────────────────────────────────────────────────────
RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; CYAN='\033[0;36m'
BOLD='\033[1m'; NC='\033[0m'
info()    { echo -e "${CYAN}[INFO]${NC}  $*"; }
success() { echo -e "${GREEN}[OK]${NC}    $*"; }
warn()    { echo -e "${YELLOW}[WARN]${NC}  $*"; }
error()   { echo -e "${RED}[ERROR]${NC} $*"; exit 1; }

# ── Dependency installation ───────────────────────────────────────────────────
check_deps() {
  java -version &>/dev/null || {
    info "Installing Java 21..."
    sudo apt-get update -qq && sudo apt-get install -y --no-install-recommends openjdk-21-jdk-headless
  }
  success "Java $(java -version 2>&1 | head -1)"

  mvn -version &>/dev/null || {
    info "Installing Maven..."
    sudo apt-get install -y --no-install-recommends maven
  }
  success "Maven $(mvn -version 2>&1 | head -1)"

  local installed_ver=""
  [[ -x "$TEMPORAL_BIN" ]] && installed_ver=$("$TEMPORAL_BIN" --version 2>&1 | grep -oE '[0-9]+\.[0-9]+\.[0-9]+' | head -1)
  if [[ "$installed_ver" != "$TEMPORAL_CLI_VERSION" ]]; then
    info "Installing Temporal CLI v${TEMPORAL_CLI_VERSION}..."
    curl -sSf https://temporal.download/cli.sh | TEMPORAL_CLI_VERSION="$TEMPORAL_CLI_VERSION" sh
    export PATH="${HOME}/.temporalio/bin:$PATH"
  fi
  success "Temporal CLI $("$TEMPORAL_BIN" --version 2>&1 | head -1)"
}

build_if_needed() {
  local needs_build=false
  if [[ ! -f "$JAR" ]]; then
    needs_build=true
  elif find src -name "*.java" -newer "$JAR" | grep -q .; then
    info "Source files changed — rebuilding..."
    needs_build=true
  fi

  if [[ "$needs_build" == "true" ]]; then
    mvn package -q -DskipTests
    success "Build complete"
  else
    info "JAR is up to date."
  fi
}

wait_for_port() {
  local port=$1 label=$2 attempts=0 max=40
  info "Waiting for $label on port $port..."
  until curl -sf "http://localhost:${port}" &>/dev/null; do
    ((attempts++))
    [[ $attempts -ge $max ]] && error "$label did not start within ${max}s. Check: $SERVER_LOG"
    sleep 1
  done
  success "$label is ready"
}

# ── Worker lifecycle ──────────────────────────────────────────────────────────
start_worker() {
  local mode="${1:-NONE}"
  pkill -f "com.example.order.Worker" 2>/dev/null || true
  sleep 1
  info "Starting worker with FAILURE_MODE=${mode}..."
  nohup env FAILURE_MODE="$mode" java -cp "$JAR" com.example.order.Worker \
    > "$WORKER_LOG" 2>&1 &
  sleep 3
  grep -q "Worker started" "$WORKER_LOG" \
    || error "Worker failed to start (FAILURE_MODE=${mode}). Check: $WORKER_LOG"
  success "Worker started | FAILURE_MODE=${mode}"
}

stop_worker() {
  pkill -f "com.example.order.Worker" 2>/dev/null || true
  sleep 1
}

# ── Temporal server ───────────────────────────────────────────────────────────
ensure_server() {
  if curl -sf "http://localhost:${UI_PORT}" &>/dev/null; then
    success "Temporal server already running | UI → http://localhost:${UI_PORT}"
  else
    pkill -f "temporal server start-dev" 2>/dev/null || true
    sleep 1
    info "Starting Temporal dev server (gRPC :${TEMPORAL_PORT}, UI :${UI_PORT})..."
    nohup "$TEMPORAL_BIN" server start-dev \
      --port "$TEMPORAL_PORT" \
      --ui-port "$UI_PORT" \
      > "$SERVER_LOG" 2>&1 &
    wait_for_port "$UI_PORT" "Temporal UI"
    success "Temporal server started | UI → http://localhost:${UI_PORT}"
  fi
}

# ── Stop / Status ─────────────────────────────────────────────────────────────
stop_all() {
  pkill -f "com.example.order.Worker"  2>/dev/null && success "Worker stopped"          || warn "Worker was not running"
  pkill -f "temporal server start-dev" 2>/dev/null && success "Temporal server stopped" || warn "Temporal server was not running"
}

show_status() {
  echo ""
  echo -e "${CYAN}=== Process Status ===${NC}"
  pgrep -f "temporal server start-dev" &>/dev/null \
    && echo -e "  ${GREEN}Temporal server: RUNNING${NC} (PID $(pgrep -f 'temporal server start-dev'))" \
    || echo -e "  ${RED}Temporal server: STOPPED${NC}"
  pgrep -f "com.example.order.Worker" &>/dev/null \
    && echo -e "  ${GREEN}Worker: RUNNING${NC} (PID $(pgrep -f 'com.example.order.Worker'))" \
    || echo -e "  ${RED}Worker: STOPPED${NC}"
  echo ""
  echo -e "${CYAN}=== Logs ===${NC}"
  echo "  Server: $SERVER_LOG"
  echo "  Worker: $WORKER_LOG"
  echo ""
}

# ── Run one scenario group ────────────────────────────────────────────────────
# Restarts the worker with the given FAILURE_MODE, runs TestRunner, captures exit code.
SUITE_FAILURES=0
run_scenario_group() {
  local mode="$1"
  echo ""
  echo -e "${BOLD}${YELLOW}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
  echo -e "${BOLD}${YELLOW}  Worker mode: FAILURE_MODE=${mode}${NC}"
  echo -e "${BOLD}${YELLOW}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
  start_worker "$mode"
  FAILURE_MODE="$mode" java -cp "$JAR" com.example.order.TestRunner || ((SUITE_FAILURES++))
  stop_worker
}

# ── Argument handling ─────────────────────────────────────────────────────────
case "${1:-}" in
  --stop)   stop_all;    exit 0 ;;
  --status) show_status; exit 0 ;;
  "")       ;;
  *) echo "Usage: $0 [--stop | --status]"; exit 1 ;;
esac

# ── Main ──────────────────────────────────────────────────────────────────────
echo ""
echo -e "${CYAN}${BOLD}╔══════════════════════════════════════════╗${NC}"
echo -e "${CYAN}${BOLD}║   Temporal Order Processing POC          ║${NC}"
echo -e "${CYAN}${BOLD}╚══════════════════════════════════════════╝${NC}"
echo ""

check_deps
build_if_needed
ensure_server

# Run all 6 scenario groups, one per FAILURE_MODE
run_scenario_group "NONE"
run_scenario_group "INVALID_ORDER"
run_scenario_group "PAYMENT_FAILURE"
run_scenario_group "SHIPPING_FAILURE"
run_scenario_group "PARENT_CHILD"
run_scenario_group "BATCH"

stop_worker

echo ""
echo -e "${BOLD}${CYAN}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
echo -e "${BOLD}${CYAN}  Suite Summary${NC}"
echo -e "${BOLD}${CYAN}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
if [[ "$SUITE_FAILURES" -eq 0 ]]; then
  echo -e "  ${GREEN}${BOLD}All 6 scenarios passed.${NC}"
else
  echo -e "  ${RED}${BOLD}${SUITE_FAILURES} scenario(s) failed. Check worker logs: ${WORKER_LOG}${NC}"
fi
echo ""
echo -e "  Temporal UI  → ${GREEN}http://localhost:${UI_PORT}${NC}"
echo -e "  Stop server  → ${YELLOW}./run.sh --stop${NC}"
echo -e "  Status       → ${YELLOW}./run.sh --status${NC}"
echo ""
exit "$SUITE_FAILURES"
