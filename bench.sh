#!/usr/bin/env bash
# Starts SQL Server, runs a quick 1M-row check, then the benchmark. Console output is kept in results/.
# Any arguments go to the benchmark itself, e.g. ./bench.sh --batch-size=100000
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p results

docker compose up -d

./gradlew run --args="--rows=1000000 --iterations=1" 2>&1 | tee results/quick.log

if [ $# -gt 0 ]; then
  ./gradlew run --args="$*" 2>&1 | tee results/full.log
else
  ./gradlew run 2>&1 | tee results/full.log
fi
