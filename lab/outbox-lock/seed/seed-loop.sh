#!/usr/bin/env bash
# 아웃박스 적재기 - 분당 목표 행수를 열 덩어리로 나눠 넣는다.
#
# 사용:  seed-loop.sh <분> <분당 행수> <출력 jsonl 경로>
#
# 한 분을 한 번의 INSERT 로 넣으면 그 순간에만 부하가 몰려 "분당 10만 행이 계속 들어오는" 무대가 되지 않는다.
# 열 덩어리로 나누고 벽시계에 맞춰 페이스를 잡는다. 덩어리마다 시작·종료·행수를 jsonl 로 남긴다.
#
# 컬럼은 V55__outbox_event_shape.sql 스키마 그대로다.
#   aggregate_type = 'COLLECTION'   (IngestionEventType.COLLECTED.aggregateType())
#   event_type     = 'COLLECTED'    (배정 스트림 ingestion.db 하나 - XLEN 이 발행 총량의 독립 대조가 된다)
#   aggregate_id   = lab-<분>-<덩어리>-<행번호>  (런 전체에서 유일 - 중복 집계의 기준)
#   payload        = OutboxPayload.toJson() 과 같은 형식(OutboxPayloadFormatTest 가 고정)
#   status/retry_count/created_at 은 DB 기본값 자리에 명시값으로 넣는다
#
# payload 의 occurredAt 은 덩어리 단위 NOW(6) 이고 created_at 은 행 삽입 시각이다 - 둘은 같지 않다.
set -euo pipefail

MINUTES=${1:?분 수}
ROWS_PER_MINUTE=${2:?분당 행수}
OUT=${3:?jsonl 경로}

MASTER=${MASTER_CONTAINER:-outboxlock-mysql-master}
ROOT_PW=${MYSQL_ROOT_PASSWORD:-verysecret}
DB=${MYSQL_DATABASE:-mydatabase}
CHUNKS=${SEED_CHUNKS:-10}

CHUNK_ROWS=$(( ROWS_PER_MINUTE / CHUNKS ))
[ "$CHUNK_ROWS" -gt 0 ] || CHUNK_ROWS=$ROWS_PER_MINUTE
CHUNK_SECONDS=$(( 60 / CHUNKS ))
[ "$CHUNK_SECONDS" -gt 0 ] || CHUNK_SECONDS=1

insert_chunk() {
  local minute=$1 chunk=$2 rows=$3
  docker exec -i -e MYSQL_PWD="$ROOT_PW" "$MASTER" mysql -uroot "$DB" <<SQL
SET SESSION cte_max_recursion_depth = 1000000;
INSERT INTO ingestion_outbox
  (aggregate_type, aggregate_id, event_type, payload, status, retry_count, created_at)
WITH RECURSIVE seq(n) AS (
  SELECT 1 UNION ALL SELECT n + 1 FROM seq WHERE n < ${rows}
)
SELECT
  'COLLECTION',
  CONCAT('lab-${minute}-${chunk}-', n),
  'COLLECTED',
  CONCAT('{"aggregateType":"COLLECTION","aggregateId":"lab-${minute}-${chunk}-', n,
         '","eventType":"COLLECTED","occurredAt":"', DATE_FORMAT(NOW(6), '%Y-%m-%dT%H:%i:%s.%f'), '"}'),
  'PENDING',
  0,
  NOW(6)
FROM seq;
SQL
}

: > "$OUT"
for minute in $(seq 1 "$MINUTES"); do
  minute_started=$(date +%s)
  for chunk in $(seq 1 "$CHUNKS"); do
    chunk_started=$(date +%s.%N)
    insert_chunk "$minute" "$chunk" "$CHUNK_ROWS"
    chunk_ended=$(date +%s.%N)
    printf '{"minute":%d,"chunk":%d,"rows":%d,"started_at":"%s","ended_at":"%s"}\n' \
      "$minute" "$chunk" "$CHUNK_ROWS" \
      "$(date -r "${chunk_started%.*}" +%Y-%m-%dT%H:%M:%S)" \
      "$(date -r "${chunk_ended%.*}" +%Y-%m-%dT%H:%M:%S)" >> "$OUT"
    # SEED_BURST=1 이면 페이스를 잡지 않고 덩어리를 연달아 넣는다(적재를 한 번에).
    if [ "${SEED_BURST:-0}" != "1" ]; then
      target=$(( minute_started + chunk * CHUNK_SECONDS ))
      now=$(date +%s)
      if [ "$now" -lt "$target" ]; then sleep $(( target - now )); fi
    fi
  done
done
