#!/usr/bin/env bash
# Temporal Order Processing POC — macOS runner
#
# Usage:
#   ./run-macos.sh           — install deps, start stack, run all 4 test scenarios
#   ./run-macos.sh --stop    — stop worker + Docker stack + Colima
#   ./run-macos.sh --status  — show running processes and log paths
#
# Dependencies installed automatically:
#   Homebrew, SDKMAN, Java 21 (via SDKMAN), Maven (via SDKMAN),
#   Colima, Docker CLI, Docker Compose plugin, Temporal CLI

set -euo pipefail

# ── Config ────────────────────────────────────────────────────────────────────
JAVA_VERSION="21.0.5-tem"          # Temurin 21 via SDKMAN
TEMPORAL_CLI_VERSION="1.6.2"       # https://github.com/temporalio/cli/releases
JAR="target/temporal-order-poc-1.0-SNAPSHOT.jar"
TEMPORAL_PORT=7233
UI_PORT=8080
WORKER_LOG="/tmp/temporal-worker.log"
COLIMA_CPUS=2
COLIMA_MEMORY=4   # GiB — Temporal + Postgres need ~2 GiB

# ── Colours ───────────────────────────────────────────────────────────────────
RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; CYAN='\033[0;36m'
BOLD='\033[1m'; NC='\033[0m'
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
  local installed_ver=""
  if command -v temporal &>/dev/null; then
    installed_ver=$(temporal --version 2>&1 | grep -oE '[0-9]+\.[0-9]+\.[0-9]+' | head -1)
  fi

  if [[ "$installed_ver" != "$TEMPORAL_CLI_VERSION" ]]; then
    info "Installing Temporal CLI v${TEMPORAL_CLI_VERSION} via Homebrew..."
    # Unlink any existing version first to avoid conflicts
    brew unlink temporal 2>/dev/null || true
    brew install temporal
    # If brew ships a different version, fall back to the official install script
    local brew_ver
    brew_ver=$(temporal --version 2>&1 | grep -oE '[0-9]+\.[0-9]+\.[0-9]+' | head -1)
    if [[ "$brew_ver" != "$TEMPORAL_CLI_VERSION" ]]; then
      warn "Homebrew has v${brew_ver}, installing v${TEMPORAL_CLI_VERSION} via install script..."
      curl -sSf https://temporal.download/cli.sh | TEMPORAL_CLI_VERSION="$TEMPORAL_CLI_VERSION" sh
      export PATH="${HOME}/.temporalio/bin:$PATH"
      grep -q ".temporalio/bin" "${HOME}/.zshrc" 2>/dev/null || \
        echo 'export PATH="${HOME}/.temporalio/bin:$PATH"' >> "${HOME}/.zshrc"
    fi
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
  local needs_build=false
  if [[ ! -f "$JAR" ]]; then
    needs_build=true
  elif find src -name "*.java" -newer "$JAR" | grep -q .; then
    info "Source files changed — rebuilding..."
    needs_build=true
  fi

  if [[ "$needs_build" == "true" ]]; then
    mvn package -q -DskipTests
    success "Build complete → $JAR"
  else
    info "JAR is up to date."
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

# ── Worker lifecycle ──────────────────────────────────────────────────────────
start_worker() {
  local mode="${1:-NONE}"
  local prov_fail="${2:-NONE}"
  pkill -f "com.example.order.Worker" 2>/dev/null || true
  sleep 1
  info "Starting worker with FAILURE_MODE=${mode} PROVISIONING_FAIL_AT=${prov_fail}..."
  nohup env FAILURE_MODE="$mode" PROVISIONING_FAIL_AT="$prov_fail" \
    java -cp "$JAR" com.example.order.Worker > "$WORKER_LOG" 2>&1 &
  sleep 3
  grep -q "Worker started" "$WORKER_LOG" \
    || error "Worker failed to start. Check: $WORKER_LOG"
  success "Worker started | FAILURE_MODE=${mode} PROVISIONING_FAIL_AT=${prov_fail}"
}

stop_worker() {
  pkill -f "com.example.order.Worker" 2>/dev/null || true
  sleep 1
}

# ── Run one scenario group ────────────────────────────────────────────────────
SUITE_FAILURES=0
run_scenario_group() {
  local mode="$1"
  local prov_fail="${2:-NONE}"
  echo ""
  echo -e "${BOLD}${YELLOW}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
  echo -e "${BOLD}${YELLOW}  Worker mode: FAILURE_MODE=${mode} PROVISIONING_FAIL_AT=${prov_fail}${NC}"
  echo -e "${BOLD}${YELLOW}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
  start_worker "$mode" "$prov_fail"
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
echo -e "${CYAN}${BOLD}║   macOS  ·  Colima  ·  SDKMAN            ║${NC}"
echo -e "${CYAN}${BOLD}╚══════════════════════════════════════════╝${NC}"
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

# Run all 10 scenario groups
run_scenario_group "NONE"
run_scenario_group "INVALID_ORDER"
run_scenario_group "PAYMENT_FAILURE"
run_scenario_group "SHIPPING_FAILURE"
run_scenario_group "PARENT_CHILD"
run_scenario_group "BATCH"
# CSP change provisioning scenarios
run_scenario_group "CSP_HAPPY_PATH"
run_scenario_group "CSP_VALIDATE_FAIL" "VALIDATE"
run_scenario_group "CSP_HLR_ERROR"
run_scenario_group "CSP_HLR_TIMEOUT"

stop_worker

echo ""
echo -e "${BOLD}${CYAN}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
echo -e "${BOLD}${CYAN}  Suite Summary${NC}"
echo -e "${BOLD}${CYAN}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
if [[ "$SUITE_FAILURES" -eq 0 ]]; then
  echo -e "  ${GREEN}${BOLD}All 10 scenarios passed.${NC}"
else
  echo -e "  ${RED}${BOLD}${SUITE_FAILURES} scenario(s) failed. Check worker logs: ${WORKER_LOG}${NC}"
fi
echo ""
echo -e "  Temporal UI  → ${GREEN}http://localhost:${UI_PORT}${NC}"
echo -e "  Stop all     → ${YELLOW}./run-macos.sh --stop${NC}"
echo -e "  Status       → ${YELLOW}./run-macos.sh --status${NC}"
echo ""
exit "$SUITE_FAILURES"
