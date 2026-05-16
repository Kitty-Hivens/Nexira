#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/_common.sh"
puppet_curl GET /elements | jq .
