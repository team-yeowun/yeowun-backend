#!/usr/bin/env bash
# 볼륨 하나를 끝까지 도는 오케스트레이터.
#
#   ./loadtest/run.sh <목표_전시행수> <라벨> [VU]
#
# 절차: 증폭 → ANALYZE → MySQL 재시작 → 앱 재시작 → pools → 워밍업 → prom(시작)
#       → R1 → R2 → R3 → R4 → R5 → EXPLAIN → prom(종료)
#
# 배치 원칙
#  · 앱은 호스트에서 돈다(java -jar). MySQL만 Docker VM(2 vCPU) 안에 있어 앱과 CPU를 다투지 않는다
#    → 측정값이 "쿼리 비용"에 가깝게 남는다.
#  · 버퍼풀은 적재 중에만 크게 쓰고(속도), 측정 전 MySQL 재시작으로 128M(기본)로 되돌린다.
set -euo pipefail
cd "$(dirname "$0")/.."

TARGET="${1:?usage: run.sh <target_rows> <label> [vus]}"
LABEL="${2:?}"
VUS="${3:-4}"

BASE_URL="${BASE:-http://localhost:8080}"
SEED_VAL="${SEED:-20260728}"
JAR="build/libs/$(ls build/libs 2>/dev/null | grep -v plain | head -1)"
SHA=$(git rev-parse --short=7 HEAD)
STAMP=$(date +%Y%m%d-%H%M)
OUTDIR="loadtest/results/${STAMP}-${LABEL}-${SHA}"
mkdir -p "$OUTDIR"

MYSQL=(docker exec -i modi-mysql mysql -uroot -pverysecret --default-character-set=utf8mb4 mydatabase)
mq() { "${MYSQL[@]}" -N -B -e "$1" 2>/dev/null; }

log() { echo "[run:${LABEL}] $*"; }

# ── 앱 제어 (호스트 JVM) ────────────────────────────────────────────────────
APP_PID_FILE=/tmp/loadtest-app.pid
stop_app() {
  [ -f "$APP_PID_FILE" ] || return 0
  kill "$(cat "$APP_PID_FILE")" 2>/dev/null || true
  for _ in $(seq 1 60); do kill -0 "$(cat "$APP_PID_FILE")" 2>/dev/null || break; sleep 1; done
  rm -f "$APP_PID_FILE"
}
start_app() {
  log "앱 기동..."
  LOCAL_SEED_ENABLED=true \
  CULTURE_API_KEY= \
  EXHIBITION_SYNC_CRON=- \
  OUTBOX_PURGE_CRON=- \
  OUTBOX_POLL_INTERVAL_MS=2000000000 \
  REMIND_SUMMARY_BACKFILL_ENABLED=false \
  SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE=50 \
  SPRING_DATASOURCE_HIKARI_MINIMUM_IDLE=10 \
    nohup java -Xmx1g -Xms1g -jar "$JAR" > "$OUTDIR/app.log" 2>&1 &
  echo $! > "$APP_PID_FILE"
  for i in $(seq 1 120); do
    curl -sf "$BASE_URL/actuator/health" >/dev/null 2>&1 && { log "앱 준비 완료(${i}s)"; return 0; }
    sleep 1
  done
  log "앱 기동 실패 — $OUTDIR/app.log 확인"; exit 1
}

# --max-time 필수: 거리순 OOM 뒤 앱이 죽어가면 actuator가 응답하지 않아 curl이 영구히 매달린다
# (실제로 여기서 1시간 26분을 잃었다 — 타임아웃 없이 외부를 부르지 않는다).
prom() { curl -s --max-time 30 "$BASE_URL/actuator/prometheus" > "$OUTDIR/prom-$1.txt" 2>/dev/null || true; }

# ── 1) 증폭 ────────────────────────────────────────────────────────────────
CUR=$(mq "SELECT COUNT(*) FROM exhibitions")
if (( CUR < TARGET )); then
  log "증폭 ${CUR} → ${TARGET} (적재 중에만 버퍼풀 2G)"
  stop_app
  mq "SET GLOBAL innodb_buffer_pool_size = 2147483648" || true
  ./loadtest/seed/amplify.sh "$TARGET" "${BATCH_GEN:-100}"
else
  log "증폭 불필요 (현재 ${CUR}행)"
fi

# ── 2) ANALYZE ─────────────────────────────────────────────────────────────
log "ANALYZE TABLE"
"${MYSQL[@]}" < loadtest/seed/analyze.sql >/dev/null 2>&1

# ── 3) MySQL 재시작 — 버퍼풀 128M 복귀 + 콜드 상태 통일 ────────────────────
log "MySQL 재시작(버퍼풀 128M 복귀 · 콜드)"
stop_app
docker restart modi-mysql >/dev/null
for i in $(seq 1 90); do docker exec modi-mysql mysqladmin ping -h localhost -uroot -pverysecret >/dev/null 2>&1 && break; sleep 1; done
BP=$(mq "SELECT @@innodb_buffer_pool_size")
log "버퍼풀 = $((BP/1024/1024))M"

# ── 4) 앱 재시작 ───────────────────────────────────────────────────────────
start_app

# ── 5) pools ───────────────────────────────────────────────────────────────
log "pools 생성"
BASE="$BASE_URL" ./loadtest/seed/pools.sh "$OUTDIR/pools.json"
cp "$OUTDIR/pools.json" loadtest/k6/pools.json

# ── 6) 워밍업 — 측정과 다른 시드. JIT·커넥션풀만 데운다 ────────────────────
log "워밍업"
k6 run --quiet -e RUN=r1 -e VUS=1 -e R1N=20 -e SEED=$((SEED_VAL + 999)) \
  -e POOLS=./pools.json -e OUT=/dev/null loadtest/k6/main.js >/dev/null 2>&1 || true

# ── 7) 측정 ────────────────────────────────────────────────────────────────
prom start
docker stats --no-stream --format '{{.Name}} {{.CPUPerc}} {{.MemUsage}}' > "$OUTDIR/docker-stats-start.log" 2>/dev/null || true

for R in r1 r2 r3 r4; do
  log "런 ${R} 시작"
  ( cd loadtest/k6 && k6 run \
      -e RUN=$R -e VUS="$VUS" -e SEED="$SEED_VAL" -e POOLS=./pools.json \
      -e T1="${T1:-2000}" -e T2="${T2:-400}" -e R1N="${R1N:-150}" \
      -e V1N="${V1N:-2000}" -e SCN="${SCN:-300}" \
      -e OUT="../../$OUTDIR/k6-$R.json" -e MAX_DURATION="${MAX_DURATION:-90m}" \
      main.js ) 2>&1 | tail -3
done

if [ "${RUN_DISTANCE:-1}" = "1" ]; then
  log "런 r5(거리순 격리) 시작"
  ( cd loadtest/k6 && k6 run \
      -e SEED="$SEED_VAL" -e POOLS=./pools.json -e N="${DISTN:-50}" \
      -e OUT="../../$OUTDIR/k6-r5.json" -e MAX_DURATION=30m \
      distance.js ) 2>&1 | tail -3 || log "r5 중단(정상 결과일 수 있음)"
fi

docker stats --no-stream --format '{{.Name}} {{.CPUPerc}} {{.MemUsage}}' > "$OUTDIR/docker-stats-end.log" 2>/dev/null || true
prom end

# ── 8) EXPLAIN ─────────────────────────────────────────────────────────────
log "EXPLAIN"
"${MYSQL[@]}" -t < loadtest/probe/explain.sql > "$OUTDIR/explain.txt" 2>&1 || true

# ── 9) meta.json ───────────────────────────────────────────────────────────
cat > "$OUTDIR/meta.json" <<JSON
{
  "label": "${LABEL}",
  "volume": $(mq "SELECT COUNT(*) FROM exhibitions"),
  "git": { "sha": "${SHA}", "branch": "$(git rev-parse --abbrev-ref HEAD)" },
  "mysql": {
    "version": "$(mq 'SELECT @@version')",
    "buffer_pool_mb": $((BP/1024/1024)),
    "flush_method": "$(mq 'SELECT @@innodb_flush_method')",
    "max_connections": $(mq 'SELECT @@max_connections')
  },
  "hikari": { "maximum_pool_size": 50, "minimum_idle": 10 },
  "k6": { "version": "$(k6 version | head -1)", "vus": ${VUS}, "seed": ${SEED_VAL} },
  "host": { "cpu_cores": $(sysctl -n hw.ncpu), "mem_bytes": $(sysctl -n hw.memsize),
            "docker_cpus": $(docker info --format '{{.NCPU}}'), "docker_mem": $(docker info --format '{{.MemTotal}}') },
  "rows": {
    "exhibitions": $(mq "SELECT COUNT(*) FROM exhibitions"),
    "exhibition_place": $(mq "SELECT COUNT(*) FROM exhibition_place"),
    "exhibition_detail": $(mq "SELECT COUNT(*) FROM exhibition_detail"),
    "exhibition_genre": $(mq "SELECT COUNT(*) FROM exhibition_genre")
  }
}
JSON
"${MYSQL[@]}" -t -e "SHOW INDEX FROM exhibitions; SHOW INDEX FROM exhibition_place; SHOW INDEX FROM exhibition_detail" \
  > "$OUTDIR/indexes.txt" 2>/dev/null || true

log "완료 → $OUTDIR"
