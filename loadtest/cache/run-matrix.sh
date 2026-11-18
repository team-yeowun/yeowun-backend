#!/usr/bin/env bash
# 볼륨 하나에 대해 (VU 단계 × off/on × 반복) 행렬을 돈다. 순서는 반복마다 off/on을 뒤집는다(ABBA).
#
#   VUS_LIST="10 50" REPS=3 WARMUP=30 DURATION=120 ./loadtest/cache/run-matrix.sh <라벨>
set -euo pipefail
cd "$(dirname "$0")/../.."
LABEL="${1:?usage: run-matrix.sh <label>}"
ROOT="loadtest/cache/results/$(date +%Y%m%d-%H%M)-${LABEL}"
mkdir -p "$ROOT"
echo "[matrix] → $ROOT  (VUS_LIST=${VUS_LIST:-10 50} REPS=${REPS:-3} WARMUP=${WARMUP:-30} DURATION=${DURATION:-120})"
for vu in ${VUS_LIST:-10 50}; do
  for rep in $(seq 1 "${REPS:-3}"); do
    if (( rep % 2 == 1 )); then order="off on"; else order="on off"; fi
    for mode in $order; do
      MODE=$mode VUS=$vu LABEL="$LABEL" ./loadtest/cache/run-one.sh "$ROOT/vu${vu}-r${rep}-${mode}" 2>&1 | tail -1
      tail -4 "$ROOT/vu${vu}-r${rep}-${mode}/k6-stdout.txt" | head -3
    done
  done
done
echo "[matrix] 끝 $(date +%H:%M:%S)"
