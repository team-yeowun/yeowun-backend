#!/usr/bin/env bash
# Redis 계기판. 사용: redis-info.sh <출력 접두>  →  <접두>-commandstats.txt / -memory.txt / -keys.txt
set -euo pipefail
. "$(dirname "$0")/env.sh"
PREFIX=${1:?출력 접두}
docker exec -i "$REDIS" redis-cli INFO commandstats > "${PREFIX}-commandstats.txt"
docker exec -i "$REDIS" redis-cli INFO memory > "${PREFIX}-memory.txt"
{
  printf 'dbsize %s\n' "$(docker exec -i "$REDIS" redis-cli DBSIZE | tr -d '\r')"
  printf 'xlen.%s %s\n' "$STREAM" "$(docker exec -i "$REDIS" redis-cli XLEN "$STREAM" | tr -d '\r')"
  printf 'marker_keys %s\n' "$(docker exec -i "$REDIS" redis-cli --scan --pattern 'outbox:*' | wc -l | tr -d ' ')"
  printf 'job_lock_keys %s\n' "$(docker exec -i "$REDIS" redis-cli --scan --pattern 'lock:*' | wc -l | tr -d ' ')"
} > "${PREFIX}-keys.txt"
