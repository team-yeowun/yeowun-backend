#!/usr/bin/env bash
# Redis Cluster 계기판. 사용: cluster-status.sh <출력 접두> [KEYSLOT 을 볼 키...]
#   →  <접두>-info.txt   CLUSTER INFO · CLUSTER NODES(전문)
#      <접두>-keyslot.txt  키별 CLUSTER KEYSLOT 과 그 슬롯을 가진 노드
#      <접두>-nodes.txt   노드별 DBSIZE · 스트림별 XLEN · 접두별 키 수
#
# 노드별 값은 -c 없이 각 노드에 직접 묻는다. 남의 슬롯이면 MOVED 가 그대로 찍히는데, 그 출력 자체가
#   "이 키는 저 노드 것"이라는 배치의 증거다.
set -euo pipefail
. "$(dirname "$0")/env.sh"

PREFIX=${1:?출력 접두}
shift || true
KEYS="$*"   # 배열을 쓰지 않는다 - bash 3.2 는 set -u 에서 빈 배열 전개가 깨진다

STREAMS=${LAB_STREAMS:-"ingestion.culture ingestion.ai ingestion.google ingestion.db"}
CLUSTER_NODES=${LAB_REDIS_NODES:-"outboxlock-redis-c1 outboxlock-redis-c2 outboxlock-redis-c3"}
FIRST=$(echo "$CLUSTER_NODES" | awk '{print $1}')

node_cli() { docker exec -i "$1" redis-cli "${@:2}" | tr -d '\r'; }

{
  printf '# %s\n\n== CLUSTER INFO (%s)\n' "$(date +%Y-%m-%dT%H:%M:%S)" "$FIRST"
  node_cli "$FIRST" CLUSTER INFO
  printf '\n== CLUSTER NODES\n'
  node_cli "$FIRST" CLUSTER NODES
} > "${PREFIX}-info.txt" 2>&1

{
  printf '# 키 -> 슬롯 -> 소유 노드 (%s)\n' "$(date +%Y-%m-%dT%H:%M:%S)"
  printf '# 슬롯 배치는 키 이름이 정한다. 몰리든 흩어지든 이름을 바꿔 맞추지 않는다.\n\n'
  for key in $STREAMS $KEYS; do
    slot=$(docker exec -i "$FIRST" redis-cli CLUSTER KEYSLOT "$key" | tr -d '\r')
    owner=$(docker exec -i "$FIRST" redis-cli CLUSTER NODES | tr -d '\r' \
      | awk -v s="$slot" '$0 ~ /master/ { for (i = 9; i <= NF; i++) { split($i, r, "-");
          if (r[2] == "" ) r[2] = r[1]; if (s + 0 >= r[1] + 0 && s + 0 <= r[2] + 0) print $2 } }')
    printf '%-28s slot=%-6s owner=%s\n' "$key" "$slot" "${owner:-?}"
  done
} > "${PREFIX}-keyslot.txt" 2>&1

{
  printf '# 노드별 상태 (%s)\n' "$(date +%Y-%m-%dT%H:%M:%S)"
  for node in $CLUSTER_NODES; do
    printf '\n== %s\n' "$node"
    printf 'dbsize %s\n' "$(node_cli "$node" DBSIZE)"
    for stream in $STREAMS; do
      printf 'xlen.%s %s\n' "$stream" "$(node_cli "$node" XLEN "$stream")"
    done
    printf 'marker_keys %s\n' "$(node_cli "$node" --scan --pattern 'outbox:*' | wc -l | tr -d ' ')"
    printf 'job_lock_keys %s\n' "$(node_cli "$node" --scan --pattern 'lock:*' | wc -l | tr -d ' ')"
    printf 'lab_keys %s\n' "$(node_cli "$node" --scan --pattern 'lab:*' | wc -l | tr -d ' ')"
  done
} > "${PREFIX}-nodes.txt" 2>&1
