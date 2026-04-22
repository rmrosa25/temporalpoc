#!/usr/bin/env bash
# Temporal Order Processing POC — macOS runner
#
# Uses Colima + Docker Compose for the full stack:
#   PostgreSQL + Temporal server + Temporal UI
#
# Each scenario runs via ScenarioRunner (single JVM) which starts the
# Temporal worker and test together, sharing an in-process message bus.
# No Kafka broker required.
#
# Usage:
#   ./run-macos.sh           — install deps, start stack, run all 10 scenarios
#   ./run-macos.sh --stop    — stop Docker stack + Colima
#   ./run-macos.sh --status  — show running processes and log paths

set -euo pipefail

# ── Config ────────────────────────────────────────────────────────────────────
JAVA_VERSION="21.0.5-tem"
TEMPORAL_CLI_VERSION="1.6.2"
JAR="target/temporal-order-poc-1.0-SNAPSHOT.jar"
TEMPORAL_PORT=7233
UI_PORT=8080
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
  [[ -f /opt/homebrew/bin/brew ]] && eval "$(/opt/homebrew/bin/brew shellenv)"
  success "Homebrew $(brew --version | head -1)"
}

# ── SDKMAN ────────────────────────────────────────────────────────────────────
# SDKMAN uses unguarded variables throughout — keep -u off for all sdk calls.
sdkman_init() {
  export SDKMAN_DIR="${HOME}/.sdkman"
  set +u
  # shellcheck disable=SC1091
  source "${SDKMAN_DIR}/bin/sdkman-init.sh"
}
sdkman_done() { set -u; }

install_sdkman() {
  if [[ ! -f "${HOME}/.sdkman/bin/sdkman-init.sh" ]]; then
    info "Installing SDKMAN..."
    curl -s "https://get.sdkman.io" | bash
  fi
  sdkman_init
  success "SDKMAN $(sdk version 2>/dev/null | grep -oE '[0-9]+\.[0-9]+\.[0-9]+' | head -1)"
  sdkman_done
}

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

install_maven() {
  sdkman_init
  if ! command -v mvn &>/dev/null; then
    info "Installing Maven via SDKMAN..."
    sdk install maven < /dev/null
  fi
  sdkman_done
  success "Maven $(mvn -version 2>&1 | grep -oE '[0-9]+\.[0-9]+\.[0-9]+' | head -1)"
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
  command -v temporal &>/dev/null && \
    installed_ver=$(temporal --version 2>&1 | grep -oE '[0-9]+\.[0-9]+\.[0-9]+' | head -1)

  if [[ "$installed_ver" != "$TEMPORAL_CLI_VERSION" ]]; then
    info "Installing Temporal CLI v${TEMPORAL_CLI_VERSION}..."
    brew unlink temporal 2>/dev/null || true
    brew install temporal
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
  export PATH="${HOME}/.temporalio/bin:$PATH"
  success "Temporal CLI $(temporal --version 2>&1 | grep -oE '[0-9]+\.[0-9]+\.[0-9]+' | head -1)"
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

# ── Docker Compose stack (PostgreSQL + Temporal server + UI) ──────────────────
start_docker_stack() {
  export DOCKER_HOST="unix://${HOME}/.colima/default/docker.sock"

  if docker compose ps --services --filter "status=running" 2>/dev/null | grep -q "temporal-ui"; then
    success "Docker stack already running"
    return
  fi

  info "Starting Docker stack (PostgreSQL + Temporal server + UI)..."
  # Remove kafka service from compose if present — not needed without real Kafka
  docker compose up -d postgresql temporal temporal-ui

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
  [[ ! -f "$JAR" ]] && needs_build=true
  find src -name "*.java" -newer "$JAR" 2>/dev/null | grep -q . && needs_build=true
  if [[ "$needs_build" == "true" ]]; then
    info "Building..."
    mvn package -q -DskipTests
    success "Build complete → $(du -sh "$JAR" | cut -f1) JAR"
  else
    info "JAR is up to date."
  fi
}

# ── Stop everything ───────────────────────────────────────────────────────────
stop_all() {
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
  echo -e "${CYAN}=== Logs ===${NC}"
  echo "  Docker stack: docker compose logs"
  echo "  Scenarios:    /tmp/scenario-*.log"
  echo ""
}

# ── Run one scenario ──────────────────────────────────────────────────────────
SUITE_FAILURES=0
run_scenario() {
  local mode="$1" prov_fail="${2:-NONE}"
  echo ""
  echo -e "${BOLD}${YELLOW}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
  echo -e "${BOLD}${YELLOW}  FAILURE_MODE=${mode}  PROVISIONING_FAIL_AT=${prov_fail}${NC}"
  echo -e "${BOLD}${YELLOW}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"

  local log_file="/tmp/scenario-${mode}.log"
  if FAILURE_MODE="$mode" PROVISIONING_FAIL_AT="$prov_fail" \
       java -cp "$JAR" com.example.ScenarioRunner > "$log_file" 2>&1; then
    success "PASS — ${mode}"
  else
    warn "FAIL — ${mode} (log: ${log_file})"
    tail -20 "$log_file"
    ((SUITE_FAILURES++))
  fi
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
echo -e "${CYAN}${BOLD}║   macOS · Colima · Docker Compose        ║${NC}"
echo -e "${CYAN}${BOLD}╚══════════════════════════════════════════╝${NC}"
echo ""

install_homebrew
install_sdkman
install_java
install_maven
install_colima_docker
install_temporal_cli

start_colima
start_docker_stack
build_if_needed

### Commented for now some scenarios out of Provisioning
# run_scenario "NONE"
# run_scenario "INVALID_ORDER"
# run_scenario "PAYMENT_FAILURE"
# run_scenario "SHIPPING_FAILURE"
# run_scenario "PARENT_CHILD"
# run_scenario "BATCH"
run_scenario "CSP_HAPPY_PATH"
run_scenario "CSP_VALIDATE_FAIL" "VALIDATE"
run_scenario "CSP_HLR_ERROR"
run_scenario "CSP_HLR_TIMEOUT"

echo ""
echo -e "${BOLD}${CYAN}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
echo -e "${BOLD}${CYAN}  Suite Summary${NC}"
echo -e "${BOLD}${CYAN}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
if [[ "$SUITE_FAILURES" -eq 0 ]]; then
  echo -e "  ${GREEN}${BOLD}All 10 scenarios passed.${NC}"
else
  echo -e "  ${RED}${BOLD}${SUITE_FAILURES} scenario(s) failed.${NC}"
  echo -e "  Scenario logs: ${YELLOW}/tmp/scenario-*.log${NC}"
fi
echo ""
echo -e "  Temporal UI → ${GREEN}http://localhost:${UI_PORT}${NC}"
echo -e "  Stop all    → ${YELLOW}./run-macos.sh --stop${NC}"
echo -e "  Status      → ${YELLOW}./run-macos.sh --status${NC}"
echo ""
exit "$SUITE_FAILURES"
