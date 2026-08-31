#!/usr/bin/env bash
# 중복 집계(주 지표). 사용: duplicate-count.sh <aggregateId 목록 파일>
# 출력: total=<대기열 항목 수> unique=<고유 aggregateId 수> duplicates=<total-unique>
set -euo pipefail
IDS=${1:?aggregateId 목록}
total=$(wc -l < "$IDS" | tr -d ' ')
unique=$(sort -u "$IDS" | wc -l | tr -d ' ')
printf 'total=%s unique=%s duplicates=%s\n' "$total" "$unique" "$(( total - unique ))"
