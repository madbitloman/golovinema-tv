#!/bin/bash
set -euo pipefail
script_dir="$(cd "$(dirname "$0")" && pwd)"
exec bash "$script_dir/install-cinema.sh" "${1:-${TV_HOST:-}}"
