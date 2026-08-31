#!/usr/bin/env bash
# 대기열 상태 한 표본. 사용: stream-status.sh <출력 파일(append)> [상세를 볼 컨슈머 이름...]
#
# 남기는 것은 넷이다.
#   XLEN        스트림별 항목 수(발행 총량의 독립 대조)
#   XINFO GROUPS  그룹의 entries-read·lag
#   XPENDING <stream> <group>   요약(미처리 수·컨슈머별 분포)
#   XPENDING <stream> <group> - + 100 <consumer>   인자로 받은 컨슈머의 미처리 원문
#
# LAB_REDIS_PROFILE=cluster 면 첫 노드에 -c 로 붙어 MOVED 를 따라간다. 단일 노드를 치면 클러스터 런에서
#   빈 값을 읽고 그것을 사실로 적게 된다.
set -euo pipefail
. "$(dirname "$0")/env.sh"

OUT=${1:?출력 파일}
shift || true
CONSUMERS="$*"   # 배열을 쓰지 않는다 - bash 3.2 는 set -u 에서 빈 배열 전개가 깨진다

GROUP=${INGESTION_CONSUMER_GROUP:-ingestion-v2}
STREAMS=${LAB_STREAMS:-"ingestion.culture ingestion.ai ingestion.google ingestion.db"}
CLUSTER_MODE=${LAB_REDIS_PROFILE:-single}
CLUSTER_NODES=${LAB_REDIS_NODES:-"outboxlock-redis-c1 outboxlock-redis-c2 outboxlock-redis-c3"}

rcli() {
  if [ "$CLUSTER_MODE" = "cluster" ]; then
    docker exec -i "$(echo "$CLUSTER_NODES" | awk '{print $1}')" redis-cli -c "$@"
  else
    docker exec -i "$REDIS" redis-cli "$@"
  fi | tr -d '\r'
}

{
  printf '\n===== %s (%s) =====\n' "$(date +%s)" "$(date +%Y-%m-%dT%H:%M:%S)"
  for stream in $STREAMS; do
    printf -- '-- %s xlen=%s\n' "$stream" "$(rcli XLEN "$stream")"
    printf '   XINFO GROUPS:\n'
    rcli XINFO GROUPS "$stream" | sed 's/^/     /'
    printf '   XPENDING(summary):\n'
    rcli XPENDING "$stream" "$GROUP" | sed 's/^/     /'
    printf '   XINFO CONSUMERS:\n'
    rcli XINFO CONSUMERS "$stream" "$GROUP" | sed 's/^/     /'
  done
  for consumer in $CONSUMERS; do
    printf -- '-- XPENDING %s %s - + 100 %s\n' "$STREAM" "$GROUP" "$consumer"
    rcli XPENDING "$STREAM" "$GROUP" - + 100 "$consumer" | sed 's/^/     /'
  done
} >> "$OUT" 2>&1
