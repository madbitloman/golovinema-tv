#!/bin/bash
set -euo pipefail
tv_root="$(cd "$(dirname "$0")/.." && pwd)"
exec bash "$tv_root/scripts/install-tv-test.sh" "${1:-${TV_HOST:-}}"
