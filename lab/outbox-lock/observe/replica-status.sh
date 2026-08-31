#!/usr/bin/env bash
# 복제 지연 한 표본. 사용: replica-status.sh <출력 파일(append)>
set -euo pipefail
. "$(dirname "$0")/env.sh"
OUT=${1:?출력 파일}
{
  printf '=== %s\n' "$(date +%Y-%m-%dT%H:%M:%S)"
  docker exec -i -e MYSQL_PWD="$ROOT_PW" "$REPLICA" mysql -uroot -e "SHOW REPLICA STATUS\G" 2>/dev/null \
    | grep -E 'Seconds_Behind_Source|Replica_(IO|SQL)_Running:|Last_(IO|SQL)_Error' || true
} >> "$OUT"
