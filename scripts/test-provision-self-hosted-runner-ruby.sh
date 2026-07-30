#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PROVISIONER="${ROOT_DIR}/scripts/provision-self-hosted-runner-ruby.sh"

fail() {
  echo "Ruby provisioner test failed: $1" >&2
  exit 1
}

run_provisioner() {
  local fake_bin="$1"
  local log_file="$2"

  TEST_BIN="${fake_bin}" \
  TEST_LOG="${log_file}" \
  PATH="${fake_bin}" \
    /bin/bash "${PROVISIONER}"
}

make_fake_sudo() {
  local fake_bin="$1"

  mkdir -p "${fake_bin}"
  cat > "${fake_bin}/sudo" <<'SCRIPT'
#!/bin/bash
set -euo pipefail

printf '%s\n' "$*" >> "${TEST_LOG}"

if [[ "$*" == "apt-get install -y ruby" ]]; then
  /bin/cat > "${TEST_BIN}/ruby" <<'RUBY'
#!/bin/bash
set -euo pipefail

case "${1:-}" in
  -e)
    exit 0
    ;;
  --version)
    echo "ruby 3.2.0p0"
    ;;
  *)
    exit 1
    ;;
esac
RUBY
  /bin/chmod +x "${TEST_BIN}/ruby"
fi
SCRIPT
  chmod +x "${fake_bin}/sudo"
}

test_installs_ruby_when_missing() {
  local fixture_dir
  local fake_bin
  local log_file

  fixture_dir="$(mktemp -d)"
  fake_bin="${fixture_dir}/bin"
  log_file="${fixture_dir}/sudo.log"
  trap 'rm -rf "${fixture_dir}"' RETURN

  make_fake_sudo "${fake_bin}"
  run_provisioner "${fake_bin}" "${log_file}"

  grep -Fxq "apt-get update" "${log_file}" ||
    fail "missing apt-get update"
  grep -Fxq "apt-get install -y ruby" "${log_file}" ||
    fail "missing Ruby package installation"
}

test_skips_package_install_when_ruby_exists() {
  local fixture_dir
  local fake_bin
  local log_file

  fixture_dir="$(mktemp -d)"
  fake_bin="${fixture_dir}/bin"
  log_file="${fixture_dir}/sudo.log"
  trap 'rm -rf "${fixture_dir}"' RETURN

  mkdir -p "${fake_bin}"
  cat > "${fake_bin}/ruby" <<'RUBY'
#!/bin/bash
set -euo pipefail

case "${1:-}" in
  -e)
    exit 0
    ;;
  --version)
    echo "ruby 3.2.0p0"
    ;;
  *)
    exit 1
    ;;
esac
RUBY
  chmod +x "${fake_bin}/ruby"
  cat > "${fake_bin}/sudo" <<'SUDO'
#!/bin/bash
exit 99
SUDO
  chmod +x "${fake_bin}/sudo"

  run_provisioner "${fake_bin}" "${log_file}"
  [[ ! -e "${log_file}" ]] ||
    fail "sudo must not run when Ruby is already installed"
}

test_fails_when_ruby_standard_libraries_are_unavailable() {
  local fixture_dir
  local fake_bin
  local log_file

  fixture_dir="$(mktemp -d)"
  fake_bin="${fixture_dir}/bin"
  log_file="${fixture_dir}/sudo.log"
  trap 'rm -rf "${fixture_dir}"' RETURN

  mkdir -p "${fake_bin}"
  cat > "${fake_bin}/ruby" <<'RUBY'
#!/bin/bash
set -euo pipefail

if [[ "${1:-}" == "-e" ]]; then
  exit 1
fi

echo "ruby 3.2.0p0"
RUBY
  chmod +x "${fake_bin}/ruby"

  if run_provisioner "${fake_bin}" "${log_file}"; then
    fail "missing Ruby libraries must fail provisioning"
  fi
}

[[ -f "${PROVISIONER}" ]] ||
  fail "missing scripts/provision-self-hosted-runner-ruby.sh"

test_installs_ruby_when_missing
test_skips_package_install_when_ruby_exists
test_fails_when_ruby_standard_libraries_are_unavailable

echo "Self-hosted runner Ruby provisioner tests passed."
