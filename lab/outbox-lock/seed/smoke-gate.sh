#!/usr/bin/env bash
# 적재 형식 게이트 - 첫 런 앞에 한 번. 100행을 넣고 앱 한 틱이 그것을 온전히 내보내는지 본다.
#
# 이 게이트를 통과하지 못한 채 100만 행을 적재하면 런 전체가 무효가 된다(payload 한 글자 차이로
# 전 행이 발행 실패한다). 확인 항목:
#   ① 컬럼·enum 값이 스키마 그대로인가            (aggregate_type/event_type 집계)
#   ② 발행 실패 카운터가 0 인가                    (payload 해석 성공)
#   ③ 대기열 길이 = 적재 행수인가                  (XLEN)
#   ④ SENT 행수 = 적재 행수인가
set -euo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
ROWS=${SMOKE_ROWS:-100}
MASTER=${MASTER_CONTAINER:-outboxlock-mysql-master}
REDIS=${REDIS_CONTAINER:-outboxlock-redis}
ROOT_PW=${MYSQL_ROOT_PASSWORD:-verysecret}
DB=${MYSQL_DATABASE:-mydatabase}

mysql_q() { docker exec -i -e MYSQL_PWD="$ROOT_PW" "$MASTER" mysql -uroot --silent --skip-column-names "$DB" -e "$1"; }

echo "== 적재 ${ROWS}행"
SEED_CHUNKS=1 "$HERE/seed-loop.sh" 1 "$ROWS" /dev/null >/dev/null

echo "== ① 컬럼·enum 값"
docker exec -i -e MYSQL_PWD="$ROOT_PW" "$MASTER" mysql -uroot "$DB" \
  -e "SELECT aggregate_type, event_type, COUNT(*) FROM ingestion_outbox GROUP BY 1, 2"

echo "== 한 틱을 기다린다"
sleep "${SMOKE_WAIT_SECONDS:-15}"

sent=$(mysql_q "SELECT COUNT(*) FROM ingestion_outbox WHERE status = 'SENT'")
pending=$(mysql_q "SELECT COUNT(*) FROM ingestion_outbox WHERE status = 'PENDING'")
xlen=$(docker exec -i "$REDIS" redis-cli XLEN ingestion.db | tr -d '\r')
failures=0
for port in 18091 18092; do
  value=$(curl -s "http://localhost:${port}/actuator/prometheus" \
    | awk -F' ' '/^ingestion_outbox_publish_total\{.*result="failure".*\}/ {sum += $2} END {printf "%d", sum}')
  failures=$(( failures + value ))
done

echo "sent=${sent} pending=${pending} xlen=${xlen} publish_failures=${failures}"
[ "$sent" = "$ROWS" ] || { echo "FAIL: SENT 행수가 적재 행수와 다릅니다." >&2; exit 1; }
[ "$xlen" = "$ROWS" ] || { echo "FAIL: 대기열 길이가 적재 행수와 다릅니다." >&2; exit 1; }
[ "$failures" -eq 0 ] || { echo "FAIL: 발행 실패가 있습니다(payload 형식 의심)." >&2; exit 1; }
echo "PASS"
