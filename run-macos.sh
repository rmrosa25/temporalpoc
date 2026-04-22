#!/usr/bin/env bash
# Temporal Order Processing POC — macOS runner
#
# Uses Temporal CLI dev server (no Docker required).
# Each scenario runs in a single JVM (ScenarioRunner) that starts the
# Temporal worker and the test together, sharing an in-process message bus.
#
# Usage:
#   ./run-macos.sh           — install deps, build, run all 10 scenarios
#   ./run-macos.sh --stop    — stop Temporal dev server
#   ./run-macos.sh --status  — show running processes

set -euo pipefail

# ── Config ────────────────────────────────────────────────────────────────────
JAVA_VERSION="21.0.5-tem"
TEMPORAL_CLI_VERSION="1.6.2"
JAR="target/temporal-order-poc-1.0-SNAPSHOT.jar"
TEMPORAL_PORT=7233
UI_PORT=8233
TEMPORAL_LOG="/tmp/temporal-server.log"

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

# ── Temporal CLI ──────────────────────────────────────────────────────────────
install_temporal_cli() {
  local installed_ver=""
  command -v temporal &>/dev/null && \
    installed_ver=$(temporal --version 2>&1 | grep -oE '[0-9]+\.[0-9]+\.[0-9]+' | head -1)

  if [[ "$installed_ver" != "$TEMPORAL_CLI_VERSION" ]]; then
    info "Installing Temporal CLI v${TEMPORAL_CLI_VERSION}..."
    curl -sSf https://temporal.download/cli.sh | TEMPORAL_CLI_VERSION="$TEMPORAL_CLI_VERSION" sh
    export PATH="${HOME}/.temporalio/bin:$PATH"
    grep -q ".temporalio/bin" "${HOME}/.zshrc" 2>/dev/null || \
      echo 'export PATH="${HOME}/.temporalio/bin:$PATH"' >> "${HOME}/.zshrc"
  fi
  export PATH="${HOME}/.temporalio/bin:$PATH"
  success "Temporal CLI $(temporal --version 2>&1 | grep -oE '[0-9]+\.[0-9]+\.[0-9]+' | head -1)"
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

# ── Temporal dev server ───────────────────────────────────────────────────────
start_temporal() {
  export PATH="${HOME}/.temporalio/bin:$PATH"
  if nc -z localhost "$TEMPORAL_PORT" 2>/dev/null; then
    success "Temporal dev server already running on :${TEMPORAL_PORT}"
    return
  fi
  info "Starting Temporal dev server (port=${TEMPORAL_PORT}, UI=${UI_PORT})..."
  temporal server start-dev \
    --port "$TEMPORAL_PORT" \
    --ui-port "$UI_PORT" \
    --headless \
    > "$TEMPORAL_LOG" 2>&1 &
  local attempts=0
  until nc -z localhost "$TEMPORAL_PORT" 2>/dev/null; do
    ((attempts++))
    [[ $attempts -ge 30 ]] && error "Temporal dev server did not start. Check: $TEMPORAL_LOG"
    sleep 1
  done
  success "Temporal dev server ready | UI → http://localhost:${UI_PORT}"
}

stop_temporal() {
  pkill -f "temporal server start-dev" 2>/dev/null || true
  sleep 1
  success "Temporal dev server stopped"
}

show_status() {
  echo ""
  echo -e "${CYAN}=== Temporal dev server ===${NC}"
  pgrep -f "temporal server start-dev" &>/dev/null \
    && echo -e "  ${GREEN}RUNNING${NC} (PID $(pgrep -f 'temporal server start-dev'))" \
    || echo -e "  ${RED}STOPPED${NC}"
  echo ""
  echo -e "${CYAN}=== Logs ===${NC}"
  echo "  Temporal: $TEMPORAL_LOG"
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
       java -cp "$JAR" com.example.order.ScenarioRunner > "$log_file" 2>&1; then
    success "PASS — ${mode}"
  else
    warn "FAIL — ${mode} (log: ${log_file})"
    tail -20 "$log_file"
    ((SUITE_FAILURES++))
  fi
}

# ── Argument handling ─────────────────────────────────────────────────────────
case "${1:-}" in
  --stop)   stop_temporal; exit 0 ;;
  --status) show_status;   exit 0 ;;
  "")       ;;
  *) echo "Usage: $0 [--stop | --status]"; exit 1 ;;
esac

# ── Main ──────────────────────────────────────────────────────────────────────
echo ""
echo -e "${CYAN}${BOLD}╔══════════════════════════════════════════╗${NC}"
echo -e "${CYAN}${BOLD}║   Temporal Order Processing POC          ║${NC}"
echo -e "${CYAN}${BOLD}║   macOS · Temporal CLI · In-Process Bus  ║${NC}"
echo -e "${CYAN}${BOLD}╚══════════════════════════════════════════╝${NC}"
echo ""

install_homebrew
install_sdkman
install_java
install_maven
install_temporal_cli
build_if_needed
start_temporal

run_scenario "NONE"
run_scenario "INVALID_ORDER"
run_scenario "PAYMENT_FAILURE"
run_scenario "SHIPPING_FAILURE"
run_scenario "PARENT_CHILD"
run_scenario "BATCH"
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
echo -e "  Stop server → ${YELLOW}./run-macos.sh --stop${NC}"
echo ""
exit "$SUITE_FAILURES"
