#!/usr/bin/env bash
# 락 대기 구조 증거 한 장. 사용: innodb-status.sh <출력 파일>
set -euo pipefail
. "$(dirname "$0")/env.sh"
OUT=${1:?출력 파일}
{
  docker exec -i -e MYSQL_PWD="$ROOT_PW" "$MASTER" mysql -uroot -e "SHOW ENGINE INNODB STATUS\G"
  printf '\n=== data_lock_waits\n'
  docker exec -i -e MYSQL_PWD="$ROOT_PW" "$MASTER" mysql -uroot -e "SELECT * FROM performance_schema.data_lock_waits LIMIT 20\G"
} > "$OUT" 2>&1 || true
