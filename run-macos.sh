#!/usr/bin/env bash
# Temporal Order Processing POC — macOS runner
#
# Usage:
#   ./run-macos.sh              — install deps, start stack, run happy path test
#   ./run-macos.sh --fail       — same but payment fails → saga compensation runs
#   ./run-macos.sh --stop       — stop worker + Docker stack + Colima
#   ./run-macos.sh --status     — show running processes and log paths
#
# Dependencies installed automatically:
#   Homebrew, SDKMAN, Java 21 (via SDKMAN), Maven (via SDKMAN),
#   Colima, Docker CLI, Docker Compose plugin, Temporal CLI

set -euo pipefail

# ── Config ────────────────────────────────────────────────────────────────────
JAVA_VERSION="21.0.5-tem"          # Temurin 21 via SDKMAN
JAR="target/temporal-order-poc-1.0-SNAPSHOT.jar"
TEMPORAL_PORT=7233
UI_PORT=8080
WORKER_LOG="/tmp/temporal-worker.log"
COLIMA_CPUS=2
COLIMA_MEMORY=4   # GiB — Temporal + Postgres need ~2 GiB

# ── Colours ───────────────────────────────────────────────────────────────────
RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; CYAN='\033[0;36m'; NC='\033[0m'
info()    { echo -e "${CYAN}[INFO]${NC}  $*"; }
success() { echo -e "${GREEN}[OK]${NC}    $*"; }
warn()    { echo -e "${YELLOW}[WARN]${NC}  $*"; }
error()   { echo -e "${RED}[ERROR]${NC} $*"; exit 1; }

# ── Homebrew ──────────────────────────────────────────────────────────────────
install_homebrew() {
  if ! command -v brew &>/dev/null; then
    info "Installing Homebrew..."
    /bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)"
  fi
  # Apple Silicon path
  if [[ -f /opt/homebrew/bin/brew ]]; then
    eval "$(/opt/homebrew/bin/brew shellenv)"
    grep -q "homebrew" "${HOME}/.zprofile" 2>/dev/null || \
      echo 'eval "$(/opt/homebrew/bin/brew shellenv)"' >> "${HOME}/.zprofile"
  fi
  success "Homebrew $(brew --version | head -1)"
}

# ── SDKMAN helpers ────────────────────────────────────────────────────────────
# SDKMAN's scripts use unguarded variables (ZSH_VERSION, SDKMAN_OFFLINE_MODE,
# etc.) throughout — not just in the init script. Keep -u disabled for the
# entire SDKMAN session and restore it only after all sdk commands finish.
sdkman_init() {
  export SDKMAN_DIR="${HOME}/.sdkman"
  set +u
  # shellcheck disable=SC1091
  source "${SDKMAN_DIR}/bin/sdkman-init.sh"
  # -u stays off; caller must call sdkman_done when finished
}

sdkman_done() {
  set -u
}

install_sdkman() {
  if [[ ! -f "${HOME}/.sdkman/bin/sdkman-init.sh" ]]; then
    info "Installing SDKMAN..."
    curl -s "https://get.sdkman.io" | bash
  fi
  sdkman_init
  local ver
  ver=$(sdk version 2>/dev/null | grep -oE '[0-9]+\.[0-9]+\.[0-9]+' | head -1)
  sdkman_done
  success "SDKMAN ${ver}"
}

# ── Java 21 via SDKMAN ────────────────────────────────────────────────────────
install_java() {
  sdkman_init
  if ! sdk list java 2>/dev/null | grep -q "${JAVA_VERSION}.*installed\|${JAVA_VERSION}.*current"; then
    info "Installing Java ${JAVA_VERSION} (Temurin) via SDKMAN..."
    sdk install java "${JAVA_VERSION}" < /dev/null
  fi
  sdk use java "${JAVA_VERSION}" < /dev/null
  sdkman_done
  success "Java $(java -version 2>&1 | head -1)"
}

# ── Maven via SDKMAN ──────────────────────────────────────────────────────────
install_maven() {
  sdkman_init
  if ! command -v mvn &>/dev/null; then
    info "Installing Maven via SDKMAN..."
    sdk install maven < /dev/null
  fi
  sdkman_done
  success "Maven $(mvn -version 2>&1 | head -1)"
}

# ── Colima + Docker CLI + Compose plugin ─────────────────────────────────────
install_colima_docker() {
  if ! command -v colima &>/dev/null; then
    info "Installing Colima..."
    brew install colima
  fi
  if ! command -v docker &>/dev/null; then
    info "Installing Docker CLI..."
    brew install docker
  fi
  # Docker Compose as a CLI plugin
  if ! docker compose version &>/dev/null 2>&1; then
    info "Installing Docker Compose plugin..."
    brew install docker-compose
    mkdir -p "${HOME}/.docker/cli-plugins"
    ln -sfn "$(brew --prefix docker-compose)/bin/docker-compose" \
        "${HOME}/.docker/cli-plugins/docker-compose"
  fi
  success "Colima  $(colima version 2>/dev/null | head -1)"
  success "Docker  $(docker --version)"
  success "Compose $(docker compose version)"
}

# ── Temporal CLI ──────────────────────────────────────────────────────────────
install_temporal_cli() {
  if ! command -v temporal &>/dev/null; then
    info "Installing Temporal CLI via Homebrew..."
    brew install temporal
  fi
  success "Temporal CLI $(temporal --version 2>&1 | head -1)"
}

# ── Start Colima ──────────────────────────────────────────────────────────────
start_colima() {
  if colima status 2>/dev/null | grep -q "Running"; then
    success "Colima already running"
  else
    info "Starting Colima (${COLIMA_CPUS} CPUs, ${COLIMA_MEMORY}GiB RAM)..."
    colima start --cpu "$COLIMA_CPUS" --memory "$COLIMA_MEMORY" --disk 20
    success "Colima started"
  fi
  export DOCKER_HOST="unix://${HOME}/.colima/default/docker.sock"
}

# ── Start Docker Compose stack ────────────────────────────────────────────────
start_docker_stack() {
  export DOCKER_HOST="unix://${HOME}/.colima/default/docker.sock"

  if docker compose ps --services --filter "status=running" 2>/dev/null | grep -q "temporal-ui"; then
    success "Docker stack already running"
    return
  fi

  info "Starting Docker stack (PostgreSQL + Temporal server + UI)..."
  docker compose up -d

  info "Waiting for Temporal UI on port ${UI_PORT}..."
  local attempts=0 max=60
  until curl -sf "http://localhost:${UI_PORT}" &>/dev/null; do
    ((attempts++))
    [[ $attempts -ge $max ]] && error "Temporal UI did not start within ${max}s. Run: docker compose logs"
    sleep 2
  done
  success "Temporal stack ready | UI → http://localhost:${UI_PORT}"
}

# ── Build ─────────────────────────────────────────────────────────────────────
build_if_needed() {
  if [[ ! -f "$JAR" ]]; then
    info "JAR not found — building project..."
    mvn package -q -DskipTests
    success "Build complete → $JAR"
  else
    info "JAR already built. Run 'mvn package -q -DskipTests' to rebuild."
  fi
}

# ── Stop everything ───────────────────────────────────────────────────────────
stop_all() {
  info "Stopping worker..."
  pkill -f "com.example.order.Worker" 2>/dev/null && success "Worker stopped" || warn "Worker was not running"

  export DOCKER_HOST="unix://${HOME}/.colima/default/docker.sock"
  info "Stopping Docker stack..."
  docker compose down && success "Docker stack stopped" || warn "Docker stack was not running"

  info "Stopping Colima..."
  colima stop && success "Colima stopped" || warn "Colima was not running"
}

# ── Status ────────────────────────────────────────────────────────────────────
show_status() {
  echo ""
  echo -e "${CYAN}=== Colima ===${NC}"
  colima status 2>/dev/null || echo "  not running"

  echo ""
  echo -e "${CYAN}=== Docker containers ===${NC}"
  export DOCKER_HOST="unix://${HOME}/.colima/default/docker.sock"
  docker compose ps 2>/dev/null || echo "  stack not running"

  echo ""
  echo -e "${CYAN}=== Worker ===${NC}"
  if pgrep -f "com.example.order.Worker" &>/dev/null; then
    echo -e "  ${GREEN}RUNNING${NC} (PID $(pgrep -f 'com.example.order.Worker'))"
  else
    echo -e "  ${RED}STOPPED${NC}"
  fi

  echo ""
  echo -e "${CYAN}=== Logs ===${NC}"
  echo "  Worker:       $WORKER_LOG"
  echo "  Docker stack: docker compose logs"
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

# ── Main ──────────────────────────────────────────────────────────────────────
echo ""
echo -e "${CYAN}╔══════════════════════════════════════════╗${NC}"
echo -e "${CYAN}║   Temporal Order Processing POC          ║${NC}"
echo -e "${CYAN}║   macOS  ·  Colima  ·  SDKMAN            ║${NC}"
echo -e "${CYAN}╚══════════════════════════════════════════╝${NC}"
echo ""

install_homebrew
install_sdkman   # also calls sdkman_source, making sdk/java available
install_java
install_maven
install_colima_docker
install_temporal_cli

start_colima
export DOCKER_HOST="unix://${HOME}/.colima/default/docker.sock"
start_docker_stack
build_if_needed

# Always restart worker to pick up FAIL_PAYMENT flag
pkill -f "com.example.order.Worker" 2>/dev/null || true
sleep 1

info "Starting Order Worker..."
if [[ "$FAIL_PAYMENT" == "true" ]]; then
  warn "FAIL_AT_PAYMENT=true — payment will fail, saga compensation will run"
  nohup env FAIL_AT_PAYMENT=true java -cp "$JAR" com.example.order.Worker \
    > "$WORKER_LOG" 2>&1 &
else
  nohup java -cp "$JAR" com.example.order.Worker \
    > "$WORKER_LOG" 2>&1 &
fi
sleep 3
grep -q "Worker started" "$WORKER_LOG" || error "Worker failed to start. Check: $WORKER_LOG"
success "Worker started | polling task queue: order-processing"

# Run test workflow
echo ""
echo -e "${CYAN}=== Running test workflow ===${NC}"
java -cp "$JAR" com.example.order.Starter

echo ""
echo -e "${CYAN}=== Worker activity log ===${NC}"
grep -E "Validating|Reserving|Charging|Shipping|Sending|COMPENSATION|ERROR" "$WORKER_LOG" | tail -20

echo ""
success "Done. Stack and worker are still running in the background."
echo -e "  Temporal UI  → ${GREEN}http://localhost:${UI_PORT}${NC}"
echo -e "  Stop all     → ${YELLOW}./run-macos.sh --stop${NC}"
echo -e "  Status       → ${YELLOW}./run-macos.sh --status${NC}"
echo ""
