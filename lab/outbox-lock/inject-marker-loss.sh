#!/usr/bin/env bash
# 락이 사라진 상태를 주입한다. 사용: inject-marker-loss.sh <초> <삭제 키 기록 파일>
#
# 표현 주의: 이것은 Redis 마스터 장애를 재현한 것이 아니다. 실험 무대의 Redis 는 단일 노드라
# 마스터 장애도 비동기 복제 지연도 일어나지 않는다. 여기서 하는 일은 "행 마커가 사라진 상태"를
# 직접 만드는 것뿐이고, 결과 보고는 딱 그 범위로만 쓴다.
#
# 삭제한 키와 시각을 전량 남겨 주입 구간과 비주입 구간을 사후에 가를 수 있게 한다.
set -euo pipefail
. "$(dirname "$0")/observe/env.sh"

SECONDS_TO_RUN=${1:?주입 시간(초)}
OUT=${2:?삭제 키 기록 파일}
INTERVAL=${INJECT_INTERVAL_MS:-200}

deadline=$(( $(date +%s) + SECONDS_TO_RUN ))
printf '# started_at %s\n' "$(date +%Y-%m-%dT%H:%M:%S)" >> "$OUT"
while [ "$(date +%s)" -lt "$deadline" ]; do
  now=$(date +%Y-%m-%dT%H:%M:%S)
  docker exec -i "$REDIS" sh -c "redis-cli --scan --pattern 'outbox:*' | head -n 2000 | tee /tmp/keys.txt | xargs -r redis-cli DEL >/dev/null; cat /tmp/keys.txt" \
    | sed "s/^/${now} /" >> "$OUT" || true
  perl -e "select(undef,undef,undef,${INTERVAL}/1000)" 2>/dev/null || sleep 1
done
printf '# ended_at %s\n' "$(date +%Y-%m-%dT%H:%M:%S)" >> "$OUT"
