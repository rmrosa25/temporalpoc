#!/usr/bin/env bash
# Starts the Temporal dev server, the order worker, and optionally runs a test workflow.
#
# Usage:
#   ./run.sh              — start server + worker (happy path test)
#   ./run.sh --fail       — start server + worker (payment failure / saga compensation test)
#   ./run.sh --stop       — stop all background processes
#   ./run.sh --status     — show what is currently running
#
# Requirements: Java 21+, Maven, Temporal CLI
# Install missing deps: sudo apt-get install -y openjdk-21-jdk-headless maven
#                       curl -sSf https://temporal.download/cli.sh | sh

set -euo pipefail

TEMPORAL_BIN="${HOME}/.temporalio/bin/temporal"
JAR="target/temporal-order-poc-1.0-SNAPSHOT.jar"
TEMPORAL_PORT=7233
UI_PORT=8080
SERVER_LOG="/tmp/temporal-server.log"
WORKER_LOG="/tmp/temporal-worker.log"

# ── Colours ──────────────────────────────────────────────────────────────────
RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; CYAN='\033[0;36m'; NC='\033[0m'
info()    { echo -e "${CYAN}[INFO]${NC}  $*"; }
success() { echo -e "${GREEN}[OK]${NC}    $*"; }
warn()    { echo -e "${YELLOW}[WARN]${NC}  $*"; }
error()   { echo -e "${RED}[ERROR]${NC} $*"; exit 1; }

# ── Helpers ───────────────────────────────────────────────────────────────────
check_deps() {
  java -version &>/dev/null  || error "Java not found. Run: sudo apt-get install -y openjdk-21-jdk-headless"
  mvn  -version &>/dev/null  || error "Maven not found. Run: sudo apt-get install -y maven"
  [[ -x "$TEMPORAL_BIN" ]]   || error "Temporal CLI not found. Run: curl -sSf https://temporal.download/cli.sh | sh"
}

build_if_needed() {
  if [[ ! -f "$JAR" ]]; then
    info "JAR not found — building project..."
    mvn package -q -DskipTests
    success "Build complete"
  else
    info "JAR already built, skipping Maven build (run 'mvn package -q -DskipTests' to rebuild)"
  fi
}

wait_for_port() {
  local port=$1 label=$2 attempts=0 max=30
  info "Waiting for $label on port $port..."
  until curl -sf "http://localhost:${port}" &>/dev/null; do
    ((attempts++))
    [[ $attempts -ge $max ]] && error "$label did not start within ${max}s. Check log: $SERVER_LOG"
    sleep 1
  done
  success "$label is ready"
}

stop_all() {
  info "Stopping Temporal server and worker..."
  pkill -f "temporal server start-dev" 2>/dev/null && success "Temporal server stopped" || warn "Temporal server was not running"
  pkill -f "com.example.order.Worker"  2>/dev/null && success "Worker stopped"          || warn "Worker was not running"
}

show_status() {
  echo ""
  echo -e "${CYAN}=== Process Status ===${NC}"
  pgrep -a -f "temporal server start-dev" && echo -e "${GREEN}  Temporal server: RUNNING${NC}" || echo -e "${RED}  Temporal server: STOPPED${NC}"
  pgrep -a -f "com.example.order.Worker"  && echo -e "${GREEN}  Worker:          RUNNING${NC}" || echo -e "${RED}  Worker:          STOPPED${NC}"
  echo ""
  echo -e "${CYAN}=== Logs ===${NC}"
  echo "  Server: $SERVER_LOG"
  echo "  Worker: $WORKER_LOG"
  echo ""
}

# ── Argument handling ─────────────────────────────────────────────────────────
FAIL_PAYMENT=false
case "${1:-}" in
  --stop)   stop_all;    exit 0 ;;
  --status) show_status; exit 0 ;;
  --fail)   FAIL_PAYMENT=true ;;
  "")       ;;
  *) echo "Usage: $0 [--fail | --stop | --status]"; exit 1 ;;
esac

# ── Main flow ─────────────────────────────────────────────────────────────────
echo ""
echo -e "${CYAN}╔══════════════════════════════════════════╗${NC}"
echo -e "${CYAN}║   Temporal Order Processing POC          ║${NC}"
echo -e "${CYAN}╚══════════════════════════════════════════╝${NC}"
echo ""

check_deps
build_if_needed

# Start Temporal dev server (skip if already running)
if curl -sf "http://localhost:${UI_PORT}" &>/dev/null; then
  TEMPORAL_PID=$(pgrep -f "temporal server start-dev" || echo "unknown")
  success "Temporal server already running (PID=${TEMPORAL_PID}) | UI → http://localhost:${UI_PORT}"
else
  pkill -f "temporal server start-dev" 2>/dev/null || true
  sleep 1
  info "Starting Temporal dev server (port ${TEMPORAL_PORT}, UI port ${UI_PORT})..."
  nohup "$TEMPORAL_BIN" server start-dev \
    --port "$TEMPORAL_PORT" \
    --ui-port "$UI_PORT" \
    > "$SERVER_LOG" 2>&1 &
  TEMPORAL_PID=$!
  wait_for_port "$UI_PORT" "Temporal UI"
  success "Temporal server PID=${TEMPORAL_PID} | UI → http://localhost:${UI_PORT}"
fi

# Always restart worker to pick up FAIL_PAYMENT flag
pkill -f "com.example.order.Worker" 2>/dev/null || true
sleep 1

# Start worker
info "Starting Order Worker..."
if [[ "$FAIL_PAYMENT" == "true" ]]; then
  warn "FAIL_AT_PAYMENT=true — payment will fail, saga compensation will run"
  nohup env FAIL_AT_PAYMENT=true java -cp "$JAR" com.example.order.Worker > "$WORKER_LOG" 2>&1 &
else
  nohup java -cp "$JAR" com.example.order.Worker > "$WORKER_LOG" 2>&1 &
fi
WORKER_PID=$!
sleep 3
grep -q "Worker started" "$WORKER_LOG" || error "Worker failed to start. Check: $WORKER_LOG"
success "Worker PID=${WORKER_PID} | polling task queue: order-processing"

# Run a test workflow
echo ""
echo -e "${CYAN}=== Running test workflow ===${NC}"
java -cp "$JAR" com.example.order.Starter

echo ""
echo -e "${CYAN}=== Worker activity log ===${NC}"
grep -E "Validating|Reserving|Charging|Shipping|Sending|COMPENSATION|ERROR" "$WORKER_LOG" | tail -20

echo ""
success "Done. Server and worker are still running in the background."
echo -e "  Temporal UI  → ${GREEN}http://localhost:${UI_PORT}${NC}"
echo -e "  Stop all     → ${YELLOW}./run.sh --stop${NC}"
echo -e "  Status       → ${YELLOW}./run.sh --status${NC}"
echo ""
