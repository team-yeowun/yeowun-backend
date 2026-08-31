#!/usr/bin/env bash
# SHOW GLOBAL STATUS 전량 덤프. 사용: mysql-status.sh <master|replica> <출력 파일>
set -euo pipefail
. "$(dirname "$0")/env.sh"
TARGET=${1:?master 또는 replica}
OUT=${2:?출력 파일}
case "$TARGET" in
  master) container=$MASTER ;;
  replica) container=$REPLICA ;;
  *) echo "master 또는 replica" >&2; exit 1 ;;
esac
docker exec -i -e MYSQL_PWD="$ROOT_PW" "$container" mysql -uroot -e "SHOW GLOBAL STATUS" > "$OUT"
