#!/usr/bin/env bash
# 변형 하나를 처음부터 끝까지 돌리고 원시값을 봉인한다.
#
#   사용:  ./run.sh <variant> [regular|short|smoke]
#          ./run.sh s5            # 변형 파일이 정한 모드로
#          ./run.sh s5 smoke      # 100행 · 30초 · 틱 2초(형식·계기판 확인용)
#
#   절차:  리셋 → 복제 확인 → 두 앱 동시 기동 → before 진위 확인 → 적재 시작
#          → 10초 간격 표본 → (S5) 마커 삭제 주입 → 상한 도달 → 종료 계기판 → 봉인
#
#   원시값은 문제 폴더의 03_measurer_raw/<variant>/ 로 간다.
set -euo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "${HERE}/../.." && pwd)
# 경로에 공백이 있어 문자열 변수로 두면 인자가 쪼개진다.
compose() { docker compose -f "${HERE}/compose.lab.yaml" "$@"; }
PROBLEM_DIR=${PROBLEM_DIR:-"${REPO}/docs/전시수집_파이프라인_v2문서/AI-Driven-Development/problem/01-분산 환경 스케줄러 중복 실행 제어"}

VARIANT=${1:?variant 이름(variants/ 아래 파일명)}
MODE_OVERRIDE=${2:-}

VARIANT_FILE="${HERE}/variants/${VARIANT}.env"
[ -f "$VARIANT_FILE" ] || { echo "변형 파일이 없습니다: ${VARIANT_FILE}" >&2; exit 1; }

# ── 변형 값 읽기 ────────────────────────────────────────────────────────────
# env 파일은 docker 가 읽는 형식(따옴표 없음)이라 값에 공백·& 가 그대로 있다.
# 셸로 들여올 때만 값을 따옴표로 감싼다 - 파일 자체를 따옴표로 바꾸면 컨테이너가 따옴표째 읽는다.
load_env() { eval "$(grep -Ev '^[[:space:]]*(#|$)' "$1" | sed 's/^\([A-Za-z_][A-Za-z0-9_]*\)=\(.*\)$/\1="\2"/')"; }
set -a; load_env "${HERE}/variants/_base.env"; load_env "$VARIANT_FILE"; set +a
MODE=${MODE_OVERRIDE:-${LAB_MODE:-regular}}
INSTANCES=${LAB_INSTANCES:-2}

# 적재는 한 번에(실험 범위 결정): 행 전량을 먼저 넣고 두 앱을 그 뒤에 띄워 "쌓인 것을 비우는" 시간을 잰다.
#   TMAX_SECONDS 는 앱 기동 뒤 관측 상한. 계기판이 IDLE_SAMPLES 표본(10초 간격) 연속으로 멈추면 소진으로 보고 먼저 끝낸다.
case "$MODE" in
  regular) SEED_MINUTES=1; ROWS_PER_MINUTE=${ROWS_PER_MINUTE:-1000000}; TMAX_SECONDS=${TMAX_SECONDS:-1800}; SEED_CHUNKS=${SEED_CHUNKS:-100} ;;
  short)   SEED_MINUTES=1; ROWS_PER_MINUTE=${ROWS_PER_MINUTE:-100000}; TMAX_SECONDS=${TMAX_SECONDS:-600}; SEED_CHUNKS=${SEED_CHUNKS:-10} ;;
  smoke)   SEED_MINUTES=1; ROWS_PER_MINUTE=${SMOKE_ROWS:-100}; TMAX_SECONDS=${TMAX_SECONDS:-30}
           INGESTION_DISPATCH_INTERVAL_MS=2000; export INGESTION_DISPATCH_INTERVAL_MS
           SEED_CHUNKS=1; export SEED_CHUNKS ;;
  *) echo "모드는 regular|short|smoke" >&2; exit 1 ;;
esac
export SEED_CHUNKS
SEED_BURST=1; export SEED_BURST
IDLE_SAMPLES=${IDLE_SAMPLES:-3}

RAW_ROOT=${RAW_ROOT:-"${PROBLEM_DIR}/_workspace/03_measurer_raw"}
if [ "$MODE" = "smoke" ]; then RAW_ROOT="${RAW_ROOT}/_smoke"; fi
OUT="${RAW_ROOT}/${VARIANT}"
ATT="${OUT}/attachments"
FINALIZE_ONLY=${FINALIZE_ONLY:-0}   # 1 이면 측정은 건너뛰고 이미 남은 attachments 로 봉인만 다시 한다
if [ "$FINALIZE_ONLY" != "1" ]; then rm -rf "$OUT"; fi
mkdir -p "$ATT/app1" "$ATT/app2"

. "${HERE}/observe/env.sh"
mysql_master() { docker exec -i -e MYSQL_PWD="$ROOT_PW" "$MASTER" mysql -uroot --silent --skip-column-names "$DB" -e "$1"; }
redis_cli() { docker exec -i "$REDIS" redis-cli "$@" | tr -d '\r'; }
now_iso() { date +%Y-%m-%dT%H:%M:%S; }

echo "== ${VARIANT} (mode=${MODE} instances=${INSTANCES} strategy=${INGESTION_CLAIM_STRATEGY} batch=${INGESTION_DISPATCH_BATCH_SIZE} read=${INGESTION_OUTBOX_READ})"

# ── 변형 env 를 컨테이너가 읽을 한 장으로 합친다 ─────────────────────────────
{ cat "${HERE}/variants/_base.env"; echo; cat "$VARIANT_FILE"; echo
  echo "INGESTION_DISPATCH_INTERVAL_MS=${INGESTION_DISPATCH_INTERVAL_MS}"
} | grep -Ev '^\s*(#|$)' > "${HERE}/.runtime.env"

if [ "$FINALIZE_ONLY" != "1" ]; then
# ── 1. 인프라 기동 ──────────────────────────────────────────────────────────
compose up -d mysql-master mysql-replica redis
echo "-- 인프라 healthy 대기"
for _ in $(seq 1 80); do
  ready=$(docker inspect -f '{{.State.Health.Status}}' "$MASTER" "$REPLICA" "$REDIS" 2>/dev/null | grep -c healthy || true)
  if [ "$ready" = "3" ]; then break; fi
  sleep 3
done
[ "$(docker inspect -f '{{.State.Health.Status}}' "$MASTER")" = "healthy" ] || { echo "MySQL 원본이 뜨지 않았습니다." >&2; exit 1; }
[ "$(docker inspect -f '{{.State.Health.Status}}' "$REPLICA")" = "healthy" ] || { echo "MySQL 복제본이 뜨지 않았습니다." >&2; exit 1; }

# ── 2. 복제 확인(없으면 건다) ───────────────────────────────────────────────
io=$(docker exec -i -e MYSQL_PWD="$ROOT_PW" "$REPLICA" mysql -uroot -e "SHOW REPLICA STATUS\G" 2>/dev/null \
      | awk -F': ' '/Replica_IO_Running/{print $2}' | tr -d ' ')
if [ "$io" != "Yes" ]; then
  echo "-- 복제 설정"
  "${HERE}/mysql/init-replication.sh"
fi

# ── 3. 리셋 ────────────────────────────────────────────────────────────────
echo "-- 리셋"
compose stop app1 app2 nginx >/dev/null 2>&1 || true
# MySQL 을 다시 띄워 서버 수명 누적값(Innodb_row_lock_time_max 등 - FLUSH STATUS 로 안 지워진다)을 0 에서 시작한다.
compose restart mysql-master mysql-replica >/dev/null 2>&1
for _ in $(seq 1 60); do
  ready=$(docker inspect -f '{{.State.Health.Status}}' "$MASTER" "$REPLICA" 2>/dev/null | grep -c healthy || true)
  [ "$ready" = "2" ] && break
  sleep 3
done
for _ in $(seq 1 30); do
  io=$(docker exec -i -e MYSQL_PWD="$ROOT_PW" "$REPLICA" mysql -uroot -e "SHOW REPLICA STATUS\G" 2>/dev/null \
        | awk -F': ' '/Replica_IO_Running/{print $2}' | tr -d ' ')
  [ "$io" = "Yes" ] && break
  sleep 2
done
[ "$io" = "Yes" ] || { echo "재기동 뒤 복제가 붙지 않았습니다." >&2; exit 1; }
mysql_master "TRUNCATE TABLE ingestion_outbox" 2>/dev/null || echo "   (테이블 없음 - 첫 기동에서 Flyway 가 만든다)"
redis_cli FLUSHALL >/dev/null

# ── 5. before 진위 확인 (앱 기동 뒤 7-1 에서 호출) ────────────────────────
before_check() {
{
  printf '# 컨테이너 환경변수 원문 (%s)\n' "$(now_iso)"
  for app in $APPS; do
    printf '\n== %s\n' "$app"
    docker exec "outboxlock-${app}" sh -c 'cat /proc/1/environ | tr "\0" "\n"' | grep -E '^(INGESTION_|APP_DATASOURCE|SPRING_DATASOURCE_URL|JAVA_OPTS)' | sort
  done
  printf '\n# 앱이 실제로 쓰는 값(actuator)\n'
  for port in $PORTS; do
    printf '\n== :%s\n' "$port"
    curl -s --max-time 5 "http://localhost:${port}/actuator/prometheus" | grep -E '^ingestion_outbox_(claim|marker)_total' || true
  done
  printf '\n# 앱 기동 시각\n started=%s up=%s\n' "$STARTED_AT" "$APPS_UP_AT"
} > "${ATT}/before-check.txt"
}

# ── 7. 적재(한 번에) → 앱 기동 → 관측 ────────────────────────────────────
SEED_STARTED=$(date +%s)
echo "-- 적재 ${ROWS_PER_MINUTE}행 (한 번에, ${SEED_CHUNKS}덩어리)"
"${HERE}/seed/seed-loop.sh" "$SEED_MINUTES" "$ROWS_PER_MINUTE" "${ATT}/loader.jsonl"
SEED_ENDED=$(date +%s)
SEED_SECONDS=$(( SEED_ENDED - SEED_STARTED ))

# ── 7-0. 시작 계기판 (적재가 끝난 뒤, 앱 기동 전 - 델타에 적재 부하가 섞이지 않게) ──────────────────────────────────────────────────────────
"${HERE}/observe/mysql-status.sh" master "${ATT}/mysql-master-status-start.txt"
"${HERE}/observe/mysql-status.sh" replica "${ATT}/mysql-replica-status-start.txt"
"${HERE}/observe/redis-info.sh" "${ATT}/redis-start"
MYSQL_VERSION=$(mysql_master "SELECT VERSION()")
REDIS_VERSION=$(redis_cli INFO server | awk -F: '/^redis_version/{print $2}')
COMMIT=$(cd "$REPO" && git rev-parse --short HEAD)

# ── 7-1. 두 앱 동시 기동 (적재가 끝난 뒤 - 쌓인 행 전량이 첫 틱의 대상) ──────────────────────────────────────────────────────
APPS="app1 app2"; PORTS="18091 18092"
if [ "$INSTANCES" = "1" ]; then APPS="app1"; PORTS="18091"; fi
export APP_PORTS="$PORTS"
STARTED_AT=$(now_iso)
compose up -d $APPS
echo "-- 앱 기동 대기 (${APPS})"
for port in $PORTS; do
  for _ in $(seq 1 90); do
    if curl -s --max-time 3 "http://localhost:${port}/actuator/health" | grep -q '"status":"UP"'; then break; fi
    sleep 2
  done
  curl -s --max-time 3 "http://localhost:${port}/actuator/health" | grep -q '"status":"UP"' \
    || { echo "앱(${port})이 UP 이 되지 않았습니다." >&2; compose logs --tail 100 $APPS; exit 1; }
done
APPS_UP_AT=$(now_iso)

# 앱 이름이 풀려야 nginx 가 뜬다(upstream 을 기동 시점에 해석한다). 앱 두 대일 때만 올린다.
if [ "$INSTANCES" = "2" ]; then compose up -d nginx; fi


# before 진위 확인은 앱이 뜬 뒤에만 가능하다(5절이 여기서 실행된다).
before_check
RUN_STARTED=$(date +%s)

if [ "${LAB_INJECT_MARKER_LOSS:-false}" = "true" ] && [ "$MODE" = "regular" ]; then
  # 소진이 한창일 때(앱 기동 90초 뒤부터 60초) 마커를 지운다.
  ( sleep 90; "${HERE}/inject-marker-loss.sh" 60 "${ATT}/deleted-keys.txt" ) &
  INJECT_PID=$!
fi

# 계기판이 멈추면 소진으로 본다: 두 앱의 선점 행수+발행 성공+마커 카운터 합이 IDLE_SAMPLES 표본 연속 같으면 종료.
# 진행 중인 트랜잭션도 활동이다: 상한 없는 틱은 100만 행을 XADD 한 뒤 UPDATE 를 flush 하는 동안 카운터가 멈춘다.
#   그 구간을 소진으로 오판하지 않게 innodb_trx 의 잠근 행·수정 행 합을 활동에 더한다(아웃박스 테이블을 읽지 않는 싼 조회).
#   표본마다 원문을 trx-samples.txt 에 남겨 보고서가 시간선을 설명할 수 있게 한다.
trx_activity() {
  local now; now=$(date +%s)
  docker exec -i -e MYSQL_PWD="$ROOT_PW" "$MASTER" mysql -uroot --skip-column-names -e \
    "SELECT '${now}', trx_id, trx_state, trx_started, trx_rows_locked, trx_rows_modified, LEFT(REPLACE(IFNULL(trx_query,''),'\n',' '),80) FROM information_schema.innodb_trx" \
    >> "${ATT}/trx-samples.txt" 2>/dev/null || true
  # 락 대기 중이거나 쿼리를 실행 중인 트랜잭션이 하나라도 있으면 "지금 활동 중"으로 본다(값을 현재 시각으로 바꿔 매 표본이 다르게).
  docker exec -i -e MYSQL_PWD="$ROOT_PW" "$MASTER" mysql -uroot --skip-column-names -e \
    "SELECT COALESCE(SUM(trx_rows_locked + trx_rows_modified),0) + COUNT(*) + IF(EXISTS(SELECT 1 FROM information_schema.innodb_trx WHERE trx_state='LOCK WAIT' OR trx_query IS NOT NULL), UNIX_TIMESTAMP(), 0) FROM information_schema.innodb_trx" 2>/dev/null || echo 0
}
activity() {
  local total=0 f v
  for dir in "${ATT}"/app*; do
    f=$(ls -1 "$dir"/prom-*.txt 2>/dev/null | tail -1 || true); [ -n "$f" ] || continue
    v=$(awk '/^ingestion_outbox_(claim_rows_total|publish_total|marker_total)\{/ {s += $NF} END {printf "%.0f", s + 0}' "$f")
    total=$(( total + v ))
  done
  v=$(trx_activity | tail -1 | tr -d '[:space:]'); total=$(( total + ${v:-0} ))
  echo "$total"
}
DEADLINE=$(( RUN_STARTED + TMAX_SECONDS ))
SAMPLES=0; idle=0; last_activity=-1; LAST_ACTIVE_AT=$RUN_STARTED
while [ "$(date +%s)" -lt "$DEADLINE" ]; do
  "${HERE}/observe/scrape-apps.sh" "$ATT"
  "${HERE}/observe/replica-status.sh" "${ATT}/replica-lag.txt"
  "${HERE}/observe/docker-stats.sh" "${ATT}/docker-stats.csv"
  { printf '%s xlen=%s dbsize=%s\n' "$(date +%s)" "$(redis_cli XLEN "$STREAM")" "$(redis_cli DBSIZE)"; } >> "${ATT}/redis-samples.txt"
  SAMPLES=$(( SAMPLES + 1 ))
  cur=$(activity)
  if [ "$cur" = "$last_activity" ]; then idle=$(( idle + 1 )); else idle=0; LAST_ACTIVE_AT=$(date +%s); fi
  last_activity=$cur
  # 첫 틱이 돌기 전(기동 직후 90초)과 주입 중에는 멈춤으로 보지 않는다.
  if [ "$idle" -ge "$IDLE_SAMPLES" ] && [ $(( $(date +%s) - RUN_STARTED )) -ge 90 ] \
     && { [ -z "${INJECT_PID:-}" ] || ! kill -0 "$INJECT_PID" 2>/dev/null; }; then
    echo "-- 계기판 정지 ${IDLE_SAMPLES}표본 연속 → 소진으로 종료"; break
  fi
  sleep 10
done
if [ -n "${INJECT_PID:-}" ]; then wait "$INJECT_PID" 2>/dev/null || true; fi
"${HERE}/observe/scrape-apps.sh" "$ATT"
SAMPLES=$(( SAMPLES + 1 ))
RUN_ENDED=$(date +%s)
DRAIN_SECONDS=$(( LAST_ACTIVE_AT - RUN_STARTED ))
else
  # 봉인만 다시: 측정 당시 값은 attachments 에서 복원한다.
  APPS="app1 app2"; PORTS="18091 18092"
  if [ "$INSTANCES" = "1" ]; then APPS="app1"; PORTS="18091"; fi
  export APP_PORTS="$PORTS"
  STARTED_AT=$(awk '/^ started=/{sub(/^ started=/,""); print $1}' "${ATT}/before-check.txt")
  APPS_UP_AT=$(awk '/^ started=/{for(i=1;i<=NF;i++) if($i ~ /^up=/){sub(/^up=/,"",$i); print $i}}' "${ATT}/before-check.txt")
  MYSQL_VERSION=$(mysql_master "SELECT VERSION()")
  REDIS_VERSION=$(redis_cli INFO server | awk -F: '/^redis_version/{print $2}')
  COMMIT=$(cd "$REPO" && git rev-parse --short HEAD)
  RUN_STARTED=$(python3 -c "import sys,datetime;print(int(datetime.datetime.fromisoformat(sys.argv[1]).timestamp()))" "$APPS_UP_AT")
  SEED_SECONDS=$(python3 -c "import json,sys,datetime;l=[json.loads(x) for x in open(sys.argv[1]) if x.strip()];f=datetime.datetime.fromisoformat;print(int((f(l[-1]['ended_at'])-f(l[0]['started_at'])).total_seconds()))" "${ATT}/loader.jsonl")
  # 소진 시각 = 앱 계기판(선점 행수+발행 성공+마커) 합이 마지막으로 바뀐 표본 시각 - 앱 기동 시각
  DRAIN_SECONDS=$(python3 - "$ATT" "$RUN_STARTED" <<'PY'
import sys,glob,re,os
att,started=sys.argv[1],int(sys.argv[2])
by_ts={}
for f in glob.glob(os.path.join(att,'app*','prom-*.txt')):
    ts=int(re.search(r'prom-(\d+)\.txt',f).group(1)); tot=0.0
    for line in open(f):
        if re.match(r'^ingestion_outbox_(claim_rows_total|publish_total|marker_total)\{',line):
            tot+=float(line.rsplit(None,1)[1])
    by_ts[ts]=by_ts.get(ts,0.0)+tot
last=None; prev=None
for ts in sorted(by_ts):
    if prev is None or by_ts[ts]!=prev: last=ts
    prev=by_ts[ts]
print(last-started if last else -1)
PY
)
  RUN_ENDED=$(ls -1 "${ATT}"/app1/prom-*.txt | sed 's/.*prom-\([0-9]*\)\.txt/\1/' | sort -n | tail -1)
  SAMPLES=$(ls -1 "${ATT}"/app1/prom-*.txt | wc -l | tr -d ' ')
  echo "-- 봉인만 다시 (RUN_STARTED=${RUN_STARTED} RUN_ENDED=${RUN_ENDED} SAMPLES=${SAMPLES})"
fi

# ── 8. 종료 계기판 (여기서부터는 부하가 끝난 뒤라 COUNT 를 돌려도 된다) ──────
if [ "$FINALIZE_ONLY" != "1" ] || [ ! -f "${ATT}/mysql-master-status-end.txt" ]; then
"${HERE}/observe/mysql-status.sh" master "${ATT}/mysql-master-status-end.txt"
"${HERE}/observe/mysql-status.sh" replica "${ATT}/mysql-replica-status-end.txt"
"${HERE}/observe/redis-info.sh" "${ATT}/redis-end"
"${HERE}/observe/innodb-status.sh" "${ATT}/innodb-status-end.txt"
compose logs --no-color --tail 4000 $APPS > "${ATT}/app-logs.txt" 2>&1 || true

mysql_master "SELECT status, COUNT(*) FROM ingestion_outbox GROUP BY status" > "${ATT}/outbox-status-count.txt"
docker exec -i -e MYSQL_PWD="$ROOT_PW" "$MASTER" mysql -uroot "$DB" \
  -e "SELECT aggregate_type, event_type, COUNT(*) FROM ingestion_outbox GROUP BY 1,2" > "${ATT}/outbox-shape.txt"
fi

"${HERE}/observe/dump-stream.sh" "${ATT}/stream-aggregate-ids.txt" "${ATT}/stream-dump-sample.txt"
DUP_LINE=$("${HERE}/observe/duplicate-count.sh" "${ATT}/stream-aggregate-ids.txt")
# 중복 id 목록(주입 구간 대조용)과 원본 목록은 압축해 남긴다 - 100만 줄이면 원문이 수십 MB다.
sort "${ATT}/stream-aggregate-ids.txt" | uniq -d > "${ATT}/stream-duplicate-ids.txt"
# 중복 aggregateId 의 행 id 를 테이블에서 직접 구한다(auto-increment 는 적재 순서와 1:1 이 아니라 산술로 못 만든다).
#   마커 키 outbox:<id> 와의 대조(주입 구간 증명)는 이 파일로 한다. 런이 끝난 뒤라 조회가 허용된다.
if [ -s "${ATT}/stream-duplicate-ids.txt" ]; then
  awk 'NF {printf "ROW(\047%s\047),", $1}' "${ATT}/stream-duplicate-ids.txt" | sed 's/,$//' > "${HERE}/.dup-values.sql"
  { printf 'SELECT id, aggregate_id FROM ingestion_outbox WHERE aggregate_id IN (SELECT a FROM (VALUES '
    cat "${HERE}/.dup-values.sql"; printf ') AS v(a)) ORDER BY id;'; } \
    | docker exec -i -e MYSQL_PWD="$ROOT_PW" "$MASTER" mysql -uroot --skip-column-names "$DB" > "${ATT}/stream-duplicate-rows.txt" 2>&1 || true
  rm -f "${HERE}/.dup-values.sql"
fi
gzip -f "${ATT}/stream-aggregate-ids.txt"
STREAM_TOTAL=$(echo "$DUP_LINE" | sed 's/.*total=\([0-9]*\).*/\1/')
STREAM_UNIQUE=$(echo "$DUP_LINE" | sed 's/.*unique=\([0-9]*\).*/\1/')
DUPLICATES=$(echo "$DUP_LINE" | sed 's/.*duplicates=\([0-9]*\).*/\1/')

TOTAL_ROWS=$(mysql_master "SELECT COUNT(*) FROM ingestion_outbox")
SENT_ROWS=$(mysql_master "SELECT COUNT(*) FROM ingestion_outbox WHERE status='SENT'")
PENDING_ROWS=$(mysql_master "SELECT COUNT(*) FROM ingestion_outbox WHERE status='PENDING'")
FAILED_ROWS=$(mysql_master "SELECT COUNT(*) FROM ingestion_outbox WHERE status='FAILED'")

sum_metric() { # 마지막 표본에서 메트릭 하나를 앱 전체 합으로
  local pattern=$1 total=0 value
  for dir in "${ATT}"/app*; do
    local last; last=$(ls -1 "$dir"/prom-*.txt 2>/dev/null | tail -1 || true)
    [ -n "$last" ] || continue
    value=$(awk -v p="$pattern" '$0 ~ p {sum += $NF} END {printf "%.0f", sum + 0}' "$last")
    total=$(( total + value ))
  done
  echo "$total"
}
PUBLISH_SUCCESS=$(sum_metric '^ingestion_outbox_publish_total\{.*result="success"')
PUBLISH_FAILURE=$(sum_metric '^ingestion_outbox_publish_total\{.*result="failure"')
CLAIM_CALLS=$(sum_metric '^ingestion_outbox_claim_total\{')
CLAIM_ROWS=$(sum_metric '^ingestion_outbox_claim_rows_total\{')
MARKER_ACQUIRED=$(sum_metric '^ingestion_outbox_marker_total\{.*result="acquired"')
MARKER_SKIPPED=$(sum_metric '^ingestion_outbox_marker_total\{.*result="skipped"')

delta() { # SHOW GLOBAL STATUS 두 장의 차
  local name=$1
  local s e
  s=$(awk -v n="$name" '$1 == n {print $2}' "${ATT}/mysql-master-status-start.txt")
  e=$(awk -v n="$name" '$1 == n {print $2}' "${ATT}/mysql-master-status-end.txt")
  echo $(( ${e:-0} - ${s:-0} ))
}
LOCK_WAITS=$(delta Innodb_row_lock_waits)
LOCK_TIME=$(delta Innodb_row_lock_time)
LOCK_TIME_MAX=$(awk '$1 == "Innodb_row_lock_time_max" {print $2}' "${ATT}/mysql-master-status-end.txt")
COM_SELECT=$(delta Com_select)
ROWS_READ=$(delta Innodb_rows_read)
COM_COMMIT=$(delta Com_commit)
COM_ROLLBACK=$(delta Com_rollback)

MEASURED_AT=$(now_iso)
ELAPSED=$(( RUN_ENDED - RUN_STARTED ))
DRAIN_BASIS=${DRAIN_SECONDS:--1}; if [ "$DRAIN_BASIS" -le 0 ]; then DRAIN_BASIS=$ELAPSED; fi
SENT_PER_MINUTE=$(awk -v s="$SENT_ROWS" -v e="$DRAIN_BASIS" 'BEGIN { if (e > 0) printf "%.1f", s * 60 / e; else printf "0.0" }')

# ── 9. 봉인 ────────────────────────────────────────────────────────────────
jq -n \
  --arg step "$VARIANT" --arg mode "$MODE" \
  --argjson duplicates "$DUPLICATES" --argjson stream_total "$STREAM_TOTAL" --argjson stream_unique "$STREAM_UNIQUE" \
  --argjson publish_success "$PUBLISH_SUCCESS" --argjson publish_failure "$PUBLISH_FAILURE" \
  --argjson claim_calls "$CLAIM_CALLS" --argjson claim_rows "$CLAIM_ROWS" \
  --argjson marker_acquired "$MARKER_ACQUIRED" --argjson marker_skipped "$MARKER_SKIPPED" \
  --argjson rows "$TOTAL_ROWS" --argjson sent "$SENT_ROWS" --argjson pending "$PENDING_ROWS" --argjson failed "$FAILED_ROWS" \
  --argjson lock_waits "$LOCK_WAITS" --argjson lock_time_ms "$LOCK_TIME" --argjson lock_time_max_ms "${LOCK_TIME_MAX:-0}" \
  --argjson com_select "$COM_SELECT" --argjson rows_read "$ROWS_READ" \
  --argjson com_commit "$COM_COMMIT" --argjson com_rollback "$COM_ROLLBACK" \
  --argjson elapsed "$ELAPSED" --arg sent_per_minute "$SENT_PER_MINUTE" \
  --argjson seed_seconds "${SEED_SECONDS:-0}" --argjson drain_seconds "${DRAIN_SECONDS:--1}" \
  --argjson samples "$SAMPLES" \
  --arg strategy "$INGESTION_CLAIM_STRATEGY" --arg outbox_read "$INGESTION_OUTBOX_READ" \
  --argjson batch "$INGESTION_DISPATCH_BATCH_SIZE" --argjson tick "$INGESTION_DISPATCH_INTERVAL_MS" \
  --arg instances "L3:app=${INSTANCES}" --arg mysql "$MYSQL_VERSION" --arg redis "$REDIS_VERSION" \
  --arg commit "$COMMIT" --arg measured_at "$MEASURED_AT" --arg started "$STARTED_AT" --arg apps_up "$APPS_UP_AT" \
  --argjson seed_minutes "$SEED_MINUTES" --argjson rows_per_minute "$ROWS_PER_MINUTE" --argjson tmax "$TMAX_SECONDS" \
  '{
    step: $step,
    variant: "after",
    metric: "stream_duplicate_count",
    unit: "건",
    aggregation: { duplicates: $duplicates, stream_total: $stream_total, stream_unique: $stream_unique },
    runs: [ { i: 1, value: $duplicates, failed: false } ],
    warmup_excluded: 0,
    measurements: {
      duplicates_by_stream: $duplicates,
      duplicates_by_counter: ($publish_success - $sent),
      publish_success: $publish_success,
      publish_failure: $publish_failure,
      claim_calls: $claim_calls,
      claim_rows: $claim_rows,
      marker_acquired: $marker_acquired,
      marker_skipped: $marker_skipped,
      outbox_rows: $rows, outbox_sent: $sent, outbox_pending: $pending, outbox_failed: $failed,
      sent_per_minute: ($sent_per_minute | tonumber),
      innodb_row_lock_waits_delta: $lock_waits,
      innodb_row_lock_time_ms_delta: $lock_time_ms,
      innodb_row_lock_time_max_ms: $lock_time_max_ms,
      com_select_delta: $com_select,
      innodb_rows_read_delta: $rows_read,
      com_commit_delta: $com_commit,
      com_rollback_delta: $com_rollback,
      elapsed_seconds: $elapsed,
      seed_seconds: $seed_seconds,
      drain_seconds: $drain_seconds
    },
    condition: {
      mode: $mode,
      seed_mode: "burst(전량을 먼저 넣고 앱을 띄운다)",
      rows: ($seed_minutes * $rows_per_minute),
      rows_per_minute: $rows_per_minute,
      seed_minutes: $seed_minutes,
      tmax_seconds: $tmax,
      batch: $batch,
      strategy: $strategy,
      outbox_read: $outbox_read,
      instances: $instances,
      tick_interval_ms: $tick,
      consume_enabled: false,
      jvm: "-Xmx2g",
      trim_cron: "-", cleanup_cron: "-", collect_cron: "-",
      core_schedulers: "disabled(sync·relay·purge·cache-warm·view-count-flush·remind-backfill / watchdog 24h)",
      stream_max_length: 3000000,
      outbox_pending_gauge: false,
      scrape_samples: $samples,
      mysql: $mysql, redis: $redis,
      commit: $commit, measured_at: $measured_at,
      app_started_at: $started, apps_up_at: $apps_up,
      before_check: "attachments/before-check.txt"
    },
    attachments: [
      "attachments/before-check.txt",
      "attachments/mysql-master-status-start.txt", "attachments/mysql-master-status-end.txt",
      "attachments/mysql-replica-status-start.txt", "attachments/mysql-replica-status-end.txt",
      "attachments/redis-start-commandstats.txt", "attachments/redis-end-commandstats.txt",
      "attachments/redis-start-keys.txt", "attachments/redis-end-keys.txt",
      "attachments/innodb-status-end.txt", "attachments/docker-stats.csv",
      "attachments/loader.jsonl", "attachments/replica-lag.txt",
      "attachments/outbox-status-count.txt", "attachments/outbox-shape.txt",
      "attachments/stream-aggregate-ids.txt.gz", "attachments/stream-duplicate-ids.txt",
      "attachments/stream-dump-sample.txt", "attachments/app-logs.txt", "attachments/trx-samples.txt"
    ]
  }' > "${OUT}/run-summary.json"

cat > "${OUT}/run-summary.md" <<MD
# ${VARIANT} — ${INGESTION_CLAIM_STRATEGY} · batch=${INGESTION_DISPATCH_BATCH_SIZE} · read=${INGESTION_OUTBOX_READ} · 앱 ${INSTANCES}대

측정 ${MEASURED_AT} · 커밋 \`${COMMIT}\` · 모드 ${MODE}(${ROWS_PER_MINUTE}행 한 번에 적재 후 앱 기동, 관측 상한 ${TMAX_SECONDS}s · 정지 ${IDLE_SAMPLES}표본)

| 지표 | 값 |
|---|---|
| 중복(주 지표, 스트림 aggregateId) | ${DUPLICATES} |
| 대기열 항목 / 고유 aggregateId | ${STREAM_TOTAL} / ${STREAM_UNIQUE} |
| 중복(보조, Σ발행성공 − SENT) | $(( PUBLISH_SUCCESS - SENT_ROWS )) |
| 아웃박스 행 (총 / SENT / PENDING / FAILED) | ${TOTAL_ROWS} / ${SENT_ROWS} / ${PENDING_ROWS} / ${FAILED_ROWS} |
| 분당 SENT | ${SENT_PER_MINUTE} |
| 선점 호출 / 선점 행수 | ${CLAIM_CALLS} / ${CLAIM_ROWS} |
| 마커 획득 / 건너뜀 | ${MARKER_ACQUIRED} / ${MARKER_SKIPPED} |
| 락 대기 횟수 / 누적(ms) / 최대(ms) | ${LOCK_WAITS} / ${LOCK_TIME} / ${LOCK_TIME_MAX} |
| master Com_select / Innodb_rows_read | ${COM_SELECT} / ${ROWS_READ} |
| Com_commit / Com_rollback | ${COM_COMMIT} / ${COM_ROLLBACK} |
| 적재(초) / 소진(초, 계기판 마지막 변화까지) / 관측 경과(초) / 표본 수 | ${SEED_SECONDS:-0} / ${DRAIN_SECONDS:--1} / ${ELAPSED} / ${SAMPLES} |

조건: MySQL ${MYSQL_VERSION} · Redis ${REDIS_VERSION} · 컨슈머 OFF · 틱 ${INGESTION_DISPATCH_INTERVAL_MS}ms ·
트리밍·정리·수집 회차 비활성 · 미발행 행 계기 OFF · 스크랩 ${SAMPLES}회(10초 간격).
원문은 \`attachments/\`.
MD

echo "== 봉인: ${OUT}"
echo "   ${DUP_LINE}"
echo "   sent=${SENT_ROWS} pending=${PENDING_ROWS} failed=${FAILED_ROWS} lock_waits=${LOCK_WAITS}"
compose stop app1 app2 >/dev/null 2>&1 || true
