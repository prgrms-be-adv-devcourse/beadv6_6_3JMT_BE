#!/usr/bin/env bash

set -euo pipefail

if ! command -v ruby >/dev/null 2>&1; then
  sudo apt-get update
  sudo apt-get install -y ruby
fi

command -v ruby >/dev/null
ruby -e 'require "json"; require "yaml"; require "open3"'
ruby --version

echo "Self-hosted runner Ruby provisioning passed."
