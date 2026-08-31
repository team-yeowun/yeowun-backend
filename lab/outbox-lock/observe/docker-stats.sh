#!/usr/bin/env bash
# 컨테이너 부하 한 표본. 사용: docker-stats.sh <csv 경로(append)>
set -euo pipefail
OUT=${1:?csv 경로}
ts=$(date +%s)
docker stats --no-stream --format '{{.Name}},{{.CPUPerc}},{{.MemUsage}}' \
  | sed "s/^/${ts},/" >> "$OUT"
