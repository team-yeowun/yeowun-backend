#!/usr/bin/env bash
# 대기열에 실제로 실린 것을 훑어 aggregateId 목록을 만든다.
#   사용: dump-stream.sh <id 목록 파일> [원문 표본 파일]
#
# 중복의 주 지표가 여기서 나온다. 카운터(발행 성공 합) − SENT 행수는 틱 트랜잭션이 롤백된 경우
# 롤백된 발행까지 중복으로 세므로(XADD 는 되돌아가지 않는다) 주 지표로 쓸 수 없다.
# 스트림에 실린 payload 의 aggregateId 를 세면 "무엇이 몇 번 나갔는가"가 그대로 나온다.
#
# 100만 항목의 XRANGE 원문은 수백 MB라 통째로 남기지 않는다. 남기는 것은 두 가지다.
#   ① aggregateId 목록 전량(집계의 입력, 뒤에서 gzip)
#   ② 첫 페이지 원문 표본(레코드 형식이 실제로 그러했다는 증거)
#
# 런이 끝난 뒤 한 번만 돌린다 - 스트림을 훑는 동안 Redis 가 바쁘다.
set -euo pipefail
. "$(dirname "$0")/env.sh"
OUT=${1:?id 목록 파일}
SAMPLE=${2:-}
PAGE=${STREAM_DUMP_PAGE:-10000}

: > "$OUT"
[ -n "$SAMPLE" ] && : > "$SAMPLE"
cursor='-'
first=1
while :; do
  page=$(docker exec -i "$REDIS" redis-cli XRANGE "$STREAM" "$cursor" '+' COUNT "$PAGE")
  [ -n "$page" ] || break
  if [ -n "$SAMPLE" ] && [ "$first" = "1" ]; then
    # head 는 300줄 뒤 파이프를 닫아 printf 가 SIGPIPE(141)로 죽는다 - awk 는 끝까지 읽는다.
    printf '%s\n' "$page" | awk 'NR <= 300' > "$SAMPLE"
    first=0
  fi
  printf '%s\n' "$page" | grep -o '"aggregateId":"[^"]*"' | sed 's/.*"aggregateId":"//; s/"$//' >> "$OUT" || true
  last=$(printf '%s\n' "$page" | grep -Eo '^[0-9]+-[0-9]+$' | tail -1 || true)
  [ -n "$last" ] || break
  cursor="(${last}"
done
