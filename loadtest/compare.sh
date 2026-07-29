#!/usr/bin/env bash
# 여러 run 디렉토리의 k6 산출물을 볼륨별 표로 묶는다.
#   ./loadtest/compare.sh [results_dir ...]     (생략 시 loadtest/results/* 전부)
set -euo pipefail
cd "$(dirname "$0")/.."
DIRS=("$@")
[ ${#DIRS[@]} -eq 0 ] && DIRS=(loadtest/results/*/)
exec python3 loadtest/summarize.py "${DIRS[@]}"
