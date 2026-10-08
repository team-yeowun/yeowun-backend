#!/usr/bin/env bash
# 캐시 on/off 한 런: 앱 재시작 → Redis 비움 → k6(워밍업 + 측정) → 지표 스냅샷 → 앱 종료.
#
#   MODE=on|off VUS=10 WARMUP=30 DURATION=120 LABEL=v313 ./loadtest/cache/run-one.sh <outdir>
#
# 원칙
#  · 앱은 호스트 JVM 한 대(운영과 같은 -Xmx320m, Hikari 기본 10). MySQL·Redis는 yeowun-cachebench 스택(13306·16379).
#  · 외부를 부르는 모든 스케줄러·동기화는 끈다(수집·릴레이·조회수 반영·리마인드 백필). 외부 API 키도 비운다.
#  · 랭킹 재계산(5분 cron)도 끈다 — 요청 경로와 무관한 배치가 측정 구간에 끼어 DB를 치는 잡음을 없앤다(기동 직후 1회는 그대로 돈다).
#  · 매 런 Redis를 FLUSHALL — on 런은 빈 캐시에서 시작해 워밍업 구간(통계 제외)이 Cache-Aside로 채운다(주기 워밍 없음).
#  · MySQL은 런 사이에 재시작하지 않는다(버퍼풀은 양쪽 모두 워밍업 구간에서 데워진 상태로 측정).
set -euo pipefail
cd "$(dirname "$0")/../.."

OUT="${1:?usage: run-one.sh <outdir>}"
MODE="${MODE:?MODE=on|off}"
VUS="${VUS:-10}"
WARMUP="${WARMUP:-30}"
DURATION="${DURATION:-120}"
PORT="${PORT:-18080}"
BASE="http://127.0.0.1:${PORT}"
JAVA_BIN="${JAVA_BIN:-/Users/jins/Library/Java/JavaVirtualMachines/temurin-21.0.7/Contents/Home/bin/java}"
JVM_OPTS="${JVM_OPTS:--Xms320m -Xmx320m}"
JAR="build/libs/backend-0.0.1-SNAPSHOT.jar"
PIDFILE="loadtest/cache/.app.pid"
mkdir -p "$OUT"

case "$MODE" in on) CACHE_ENABLED=true ;; off) CACHE_ENABLED=false ;; *) echo "MODE must be on|off"; exit 2 ;; esac

MYSQL=(docker exec -i cachebench-mysql mysql -uroot -pverysecret --default-character-set=utf8mb4 mydatabase)
log() { echo "[$(date +%H:%M:%S) ${MODE} vu${VUS}] $*"; }

stop_app() {
  [ -f "$PIDFILE" ] || return 0
  local pid; pid=$(cat "$PIDFILE")
  kill "$pid" 2>/dev/null || true
  for _ in $(seq 1 40); do kill -0 "$pid" 2>/dev/null || break; sleep 0.5; done
  kill -9 "$pid" 2>/dev/null || true
  rm -f "$PIDFILE"
}

start_app() {
  env -i PATH="$PATH" HOME="$HOME" TZ=Asia/Seoul \
    SERVER_PORT="$PORT" \
    SPRING_DATASOURCE_URL="jdbc:mysql://127.0.0.1:13306/mydatabase?connectionTimeZone=Asia/Seoul&forceConnectionTimeZoneToSession=true" \
    SPRING_DATASOURCE_USERNAME=myuser SPRING_DATASOURCE_PASSWORD=secret \
    SPRING_DATA_REDIS_HOST=127.0.0.1 SPRING_DATA_REDIS_PORT=16379 \
    SPRING_DOCKER_COMPOSE_ENABLED=false \
    LOCAL_SEED_ENABLED=true \
    CULTURE_API_KEY=disabled-for-bench \
    EXHIBITION_SYNC_CRON=- \
    OUTBOX_PURGE_CRON=- \
    OUTBOX_POLL_INTERVAL_MS=2000000000 \
    REMIND_SUMMARY_BACKFILL_ENABLED=false \
    INGESTION_V2_ENABLED=false \
    GENRE_CLASSIFIER=mock PLACE_HOURS_PROVIDER=mock \
    AI_API_KEY= ANTHROPIC_API_KEY= GEMINI_API_KEY= OPENAPI_KEY= GOOGLE_MAPS_API_KEY= \
    nohup "$JAVA_BIN" $JVM_OPTS \
      -Dapp.cache.enabled="$CACHE_ENABLED" \
      -Dapp.exhibition.ranking.rebuild-cron=- \
      -Dapp.exhibition.view-count.flush-cron=- \
      -jar "$JAR" > "$OUT/app.log" 2>&1 &
  echo $! > "$PIDFILE"
  for i in $(seq 1 180); do
    if curl -sf --max-time 2 "$BASE/actuator/health" >/dev/null 2>&1; then log "앱 준비(${i}x0.5s)"; return 0; fi
    kill -0 "$(cat "$PIDFILE")" 2>/dev/null || { log "앱 프로세스 종료됨 — $OUT/app.log"; exit 1; }
    sleep 0.5
  done
  log "앱 기동 실패 — $OUT/app.log"; stop_app; exit 1
}

status_snap() {  # MySQL 전역 카운터(측정 구간 델타로 요청당 쿼리 수를 낸다)
  "${MYSQL[@]}" -N -B -e "SHOW GLOBAL STATUS WHERE Variable_name IN
    ('Questions','Com_select','Com_commit','Com_rollback','Com_set_option','Com_begin','Innodb_rows_read','Innodb_buffer_pool_read_requests','Innodb_buffer_pool_reads','Threads_connected')" \
    2>/dev/null > "$OUT/mysql-status-$1.tsv" || true
}
prom_snap() { curl -s --max-time 10 "$BASE/actuator/prometheus" > "$OUT/prom-$1.txt" 2>/dev/null || true; }

sampler() {  # 측정 구간 동안 MySQL·Redis 컨테이너 CPU와 앱 JVM CPU를 샘플링
  local app_pid; app_pid=$(cat "$PIDFILE")
  local end=$(( $(date +%s) + DURATION ))
  echo "ts,name,cpu_perc,mem" > "$OUT/docker-stats.csv"
  echo "ts,app_cpu_perc,app_rss_kb" > "$OUT/app-cpu.csv"
  while (( $(date +%s) < end )); do
    local ts; ts=$(date +%s)
    docker stats --no-stream --format '{{.Name}},{{.CPUPerc}},{{.MemUsage}}' cachebench-mysql cachebench-redis 2>/dev/null \
      | sed "s/^/${ts},/" >> "$OUT/docker-stats.csv" || true
    ps -o %cpu=,rss= -p "$app_pid" 2>/dev/null | awk -v ts="$ts" '{print ts","$1","$2}' >> "$OUT/app-cpu.csv" || true
  done
}

# ── 1) 앱 재시작 + Redis 비움 ───────────────────────────────────────────────
stop_app
docker exec cachebench-redis redis-cli FLUSHALL >/dev/null
start_app
docker exec cachebench-redis redis-cli FLUSHALL >/dev/null   # 기동 중 들어간 키도 비움(시작 상태 통일)

# ── 2) k6 — 워밍업(WARMUP초, 통계 제외) 뒤 측정(DURATION초) ─────────────────
log "k6 시작(워밍업 ${WARMUP}s + 측정 ${DURATION}s)"
k6 run --quiet -e BASE="$BASE" -e VUS="$VUS" -e WARMUP="$WARMUP" -e DURATION="$DURATION" \
  -e OUT="$OUT/k6-summary.json" -e LABEL="${LABEL:-} ${MODE} vu${VUS}" \
  loadtest/k6/cache-browse.js > "$OUT/k6-stdout.txt" 2>&1 &
K6_PID=$!
echo "$K6_PID" > loadtest/cache/.k6.pid

sleep "$WARMUP"
status_snap start; prom_snap start
sampler &
SAMPLER_PID=$!
wait "$K6_PID" || log "k6 비정상 종료(코드 $?) — k6-stdout.txt 확인"
rm -f loadtest/cache/.k6.pid
status_snap end; prom_snap end
kill "$SAMPLER_PID" 2>/dev/null || true; wait "$SAMPLER_PID" 2>/dev/null || true

# ── 3) 메타 + 앱 종료 ─────────────────────────────────────────────────────
cat > "$OUT/meta.json" <<JSON
{"mode":"${MODE}","cache_enabled":${CACHE_ENABLED},"vus":${VUS},"warmup_s":${WARMUP},"duration_s":${DURATION},
 "label":"${LABEL:-}","jvm_opts":"${JVM_OPTS}","git":"$(git rev-parse --short HEAD)$(git diff --quiet HEAD -- src || echo +dirty)",
 "exhibitions":$("${MYSQL[@]}" -N -B -e 'SELECT COUNT(*) FROM exhibitions' 2>/dev/null),
 "started_by":"run-one.sh","finished_at":"$(date +%Y-%m-%dT%H:%M:%S%z)"}
JSON
stop_app
log "완료 → $OUT"
