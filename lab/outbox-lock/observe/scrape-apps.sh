#!/usr/bin/env bash
# 앱 계기판 한 표본. 사용: scrape-apps.sh <출력 디렉터리>
set -euo pipefail
. "$(dirname "$0")/env.sh"
OUT=${1:?출력 디렉터리}
ts=$(date +%s)
index=1
for port in $APP_PORTS; do
  mkdir -p "${OUT}/app${index}"
  curl -s --max-time 5 "http://localhost:${port}/actuator/prometheus" > "${OUT}/app${index}/prom-${ts}.txt" || true
  index=$(( index + 1 ))
done
