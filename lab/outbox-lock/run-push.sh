#!/usr/bin/env bash
# 푸시 무대(Redis Streams 소비) 변형 하나를 처음부터 끝까지 돌리고 원시값을 봉인한다.
#
#   사용:  nohup ./run-push.sh <variant> [regular|short|smoke] > /tmp/<variant>.log 2>&1 &
#          ./run-push.sh push-2 smoke      # 100행 · 틱 2초(형식·계기판 확인용)
#
#   ⚠ 10분을 넘길 런은 반드시 nohup 으로 띄운다. 포그라운드 실행 ❌ - 명령 실행이 10분에서 끊기면 런만 남고
#     봉인이 없는 원시가 된다. 완료는 run-summary.md 가 생겼는지로 확인한다.
#     실행 중에 이 스크립트를 편집하지 말 것 - bash 가 파일을 이어 읽어 도중에 다른 코드를 실행한다.
#
#   run.sh 와의 관계: 이 파일은 run.sh 의 복제본이다. run.sh 는 01 실험의 봉인 자산이라 고치지 않았고,
#     리셋·적재·봉인이 함수가 아니라 인라인 절이라 재사용이 곧 복제였다. 두 파일은 여기서부터 갈라진다.
#     갈라진 지점은 셋이다.
#       ① 소진 판정 - 01 은 발송 카운터를 보지만 여기서는 소비 카운터가 IDLE_SAMPLES 표본 멈추고
#          XPENDING 이 0 이어야 소진이다(발행은 T+1분경 끝나므로 01 의 판정식은 조기 종료한다)
#       ② app3 훅 - LAB_APP3_START_SECONDS 에 합류시키고 LAB_APP3_KILL_SECONDS 에 강제 종료한다
#       ③ 클러스터 - LAB_REDIS_PROFILE=cluster 면 리셋·표본·봉인이 단일 노드가 아니라 노드 셋을 친다
#
#   절차:  리셋 → 복제 확인 → (클러스터면 노드 셋 기동·구성) → 적재 → 앱 기동 → before 진위 확인
#          → 10초 간격 표본(+app3 훅) → 소진 → 종료 계기판 → 봉인
set -euo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "${HERE}/../.." && pwd)
# 경로에 공백이 있어 문자열 변수로 두면 인자가 쪼개진다.
compose() { docker compose -f "${HERE}/compose.lab.yaml" "$@"; }
PROBLEM_DIR=${PROBLEM_DIR:-"${REPO}/docs/전시수집_파이프라인_v2문서/AI-Driven-Development/problem/02-DB 폴링에서 Redis Streams 푸시로 전환"}

VARIANT=${1:?variant 이름(variants/ 아래 파일명)}
MODE_OVERRIDE=${2:-}

VARIANT_FILE="${HERE}/variants/${VARIANT}.env"
[ -f "$VARIANT_FILE" ] || { echo "변형 파일이 없습니다: ${VARIANT_FILE}" >&2; exit 1; }

# ── 변형 값 읽기 ────────────────────────────────────────────────────────────
# env 파일은 docker 가 읽는 형식(따옴표 없음)이라 값에 공백·& 가 그대로 있다.
# 셸로 들여올 때만 값을 따옴표로 감싼다 - 파일 자체를 따옴표로 바꾸면 컨테이너가 따옴표째 읽는다.
load_env() { eval "$(grep -Ev '^[[:space:]]*(#|$)' "$1" | sed 's/^\([A-Za-z_][A-Za-z0-9_]*\)=\(.*\)$/\1="\2"/')"; }
set -a; load_env "${HERE}/variants/_base.env"; load_env "$VARIANT_FILE"; set +a
MODE=${MODE_OVERRIDE:-${LAB_MODE:-short}}
INSTANCES=${LAB_INSTANCES:-2}

CLUSTER_MODE=${LAB_REDIS_PROFILE:-single}
CLUSTER_NODES=${LAB_REDIS_NODES:-"outboxlock-redis-c1 outboxlock-redis-c2 outboxlock-redis-c3"}
FIRST_NODE=$(echo "$CLUSTER_NODES" | awk '{print $1}')
GROUP=${INGESTION_CONSUMER_GROUP:-ingestion-v2}
LAB_STREAMS=${LAB_STREAMS:-"ingestion.culture ingestion.ai ingestion.google ingestion.db"}
export LAB_REDIS_PROFILE="$CLUSTER_MODE" LAB_REDIS_NODES="$CLUSTER_NODES" LAB_STREAMS INGESTION_CONSUMER_GROUP="$GROUP"

# 적재는 한 번에: 행 전량을 먼저 넣고 앱을 그 뒤에 띄워 "쌓인 것을 비우는" 시간을 잰다.
#   TMAX_SECONDS 는 앱 기동 뒤 관측 상한. 소비 계기판이 IDLE_SAMPLES 표본 연속으로 멈추고 XPENDING 이 0 이면 먼저 끝낸다.
case "$MODE" in
  regular) SEED_MINUTES=1; ROWS_PER_MINUTE=${ROWS_PER_MINUTE:-1000000}; TMAX_SECONDS=${TMAX_SECONDS:-1800}; SEED_CHUNKS=${SEED_CHUNKS:-100}; MIN_RUN_SECONDS=90 ;;
  short)   SEED_MINUTES=1; ROWS_PER_MINUTE=${ROWS_PER_MINUTE:-100000}; TMAX_SECONDS=${TMAX_SECONDS:-1200}; SEED_CHUNKS=${SEED_CHUNKS:-10}; MIN_RUN_SECONDS=90 ;;
  smoke)   SEED_MINUTES=1; ROWS_PER_MINUTE=${SMOKE_ROWS:-100}; TMAX_SECONDS=${SMOKE_TMAX_SECONDS:-240}
           INGESTION_DISPATCH_INTERVAL_MS=2000; export INGESTION_DISPATCH_INTERVAL_MS
           SEED_CHUNKS=1; MIN_RUN_SECONDS=${SMOKE_MIN_RUN_SECONDS:-30} ;;
  *) echo "모드는 regular|short|smoke" >&2; exit 1 ;;
esac
export SEED_CHUNKS
SEED_BURST=1; export SEED_BURST
IDLE_SAMPLES=${IDLE_SAMPLES:-3}
APP3_START=${LAB_APP3_START_SECONDS:-0}
APP3_KILL=${LAB_APP3_KILL_SECONDS:-0}

RAW_ROOT=${RAW_ROOT:-"${PROBLEM_DIR}/_workspace/03_measurer_raw"}
if [ "$MODE" = "smoke" ]; then RAW_ROOT="${RAW_ROOT}/_smoke"; fi
OUT="${RAW_ROOT}/${VARIANT}"
ATT="${OUT}/attachments"
FINALIZE_ONLY=${FINALIZE_ONLY:-0}   # 1 이면 측정은 건너뛰고 이미 남은 attachments 로 봉인만 다시 한다
app3_started=0; app3_killed=0; APP3_START_AT=0; APP3_KILL_AT=0; APP3_LAST_SCRAPE_AT=0
if [ "$FINALIZE_ONLY" != "1" ]; then rm -rf "$OUT"; fi
mkdir -p "$ATT/app1" "$ATT/app2"

. "${HERE}/observe/env.sh"
mysql_master() { docker exec -i -e MYSQL_PWD="$ROOT_PW" "$MASTER" mysql -uroot --silent --skip-column-names "$DB" -e "$1"; }
now_iso() { date +%Y-%m-%dT%H:%M:%S; }

# 클러스터면 첫 노드에 -c 로 붙어 MOVED 를 따라간다. 단일 노드를 치면 클러스터 런에서 빈 값을 읽고 그것을 사실로 적게 된다.
redis_cli() {
  if [ "$CLUSTER_MODE" = "cluster" ]; then
    docker exec -i "$FIRST_NODE" redis-cli -c "$@"
  else
    docker exec -i "$REDIS" redis-cli "$@"
  fi | tr -d '\r'
}
redis_flush() {
  if [ "$CLUSTER_MODE" = "cluster" ]; then
    for node in $CLUSTER_NODES; do docker exec -i "$node" redis-cli FLUSHALL >/dev/null; done
    # 단일 노드도 함께 비운다. 앱 depends_on 때문에 클러스터 런에서도 뜨는데, 앞 런의 잔여가 남아 있으면
    #   "이 런에서 아무도 쓰지 않았다"는 것을 종료 시점 DBSIZE 로 보일 수 없다.
    docker exec -i "$REDIS" redis-cli FLUSHALL >/dev/null
  else
    docker exec -i "$REDIS" redis-cli FLUSHALL >/dev/null
  fi
}

echo "== ${VARIANT} (mode=${MODE} instances=${INSTANCES} redis=${CLUSTER_MODE} handler=${INGESTION_CONSUME_HANDLER:-REAL} latency=${INGESTION_STUB_LATENCY_MS:-0}ms batch=${INGESTION_DISPATCH_BATCH_SIZE})"

# ── 변형 env 를 컨테이너가 읽을 한 장으로 합친다 ─────────────────────────────
# PROBLEM_DIR·RAW_ROOT 는 호스트 경로라 컨테이너에 넘기지 않는다(값에 ${REPO} 와 공백이 들어 있다).
{ cat "${HERE}/variants/_base.env"; echo; cat "$VARIANT_FILE"; echo
  echo "INGESTION_DISPATCH_INTERVAL_MS=${INGESTION_DISPATCH_INTERVAL_MS}"
} | grep -Ev '^\s*(#|$)' | grep -Ev '^(PROBLEM_DIR|RAW_ROOT)=' > "${HERE}/.runtime.env"

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

# ── 1-1. 클러스터 노드 기동·구성 ────────────────────────────────────────────
if [ "$CLUSTER_MODE" = "cluster" ]; then
  echo "-- Redis Cluster 노드 기동"
  compose --profile cluster up -d redis-c1 redis-c2 redis-c3
  for _ in $(seq 1 40); do
    ready=$(docker inspect -f '{{.State.Health.Status}}' $CLUSTER_NODES 2>/dev/null | grep -c healthy || true)
    [ "$ready" = "3" ] && break
    sleep 3
  done
  compose --profile cluster up -d redis-cluster-init
  echo "-- 슬롯 배정 대기"
  for _ in $(seq 1 40); do
    state=$(docker exec -i "$FIRST_NODE" redis-cli CLUSTER INFO 2>/dev/null | tr -d '\r' | awk -F: '/^cluster_state/{print $2}')
    [ "$state" = "ok" ] && break
    sleep 3
  done
  [ "$state" = "ok" ] || { echo "클러스터가 구성되지 않았습니다(cluster_state=${state:-?})." >&2; compose logs --tail 50 redis-cluster-init; exit 1; }
fi

# ── 2. 복제 확인(없으면 건다) ───────────────────────────────────────────────
io=$(docker exec -i -e MYSQL_PWD="$ROOT_PW" "$REPLICA" mysql -uroot -e "SHOW REPLICA STATUS\G" 2>/dev/null \
      | awk -F': ' '/Replica_IO_Running/{print $2}' | tr -d ' ')
if [ "$io" != "Yes" ]; then
  echo "-- 복제 설정"
  "${HERE}/mysql/init-replication.sh"
fi

# ── 3. 리셋 ────────────────────────────────────────────────────────────────
echo "-- 리셋"
compose stop app1 app2 app3 nginx >/dev/null 2>&1 || true
docker rm -f outboxlock-app3 >/dev/null 2>&1 || true
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
# 외부 API 호출 감사표를 비운다. "이 런에서 유료 호출이 0건"이라는 증거가 앞 런의 행에 묻히지 않게.
mysql_master "TRUNCATE TABLE external_api_call_log" 2>/dev/null || echo "   (감사표 없음)"
redis_flush

# ── 4. before 진위 확인 (앱 기동 뒤 6-1 에서 호출) ─────────────────────────
before_check() {
{
  printf '# 컨테이너 환경변수 원문 (%s)\n' "$(now_iso)"
  for app in $APPS; do
    printf '\n== %s\n' "$app"
    docker exec "outboxlock-${app}" sh -c 'cat /proc/1/environ | tr "\0" "\n"' | grep -E '^(INGESTION_|SPRING_DATA_REDIS|APP_DATASOURCE|SPRING_DATASOURCE_URL|JAVA_OPTS)' | sort
  done
  printf '\n# ① 소비 핸들러 스위치 - STUB 이어야 유료 호출 경로의 빈이 등록되지 않는다\n'
  for app in $APPS; do
    printf ' %s %s\n' "$app" \
      "$(docker exec "outboxlock-${app}" sh -c 'cat /proc/1/environ | tr "\0" "\n"' | grep '^INGESTION_CONSUME_HANDLER=' || echo 'INGESTION_CONSUME_HANDLER=(없음)')"
  done
  printf '\n# 앱 기동 시각\n started=%s up=%s\n' "$STARTED_AT" "$APPS_UP_AT"
} > "${ATT}/before-check.txt"
}

# ── 5. 적재(한 번에) ───────────────────────────────────────────────────────
SEED_STARTED=$(date +%s)
echo "-- 적재 ${ROWS_PER_MINUTE}행 (한 번에, ${SEED_CHUNKS}덩어리, ${SEED_EVENT_TYPE:-COLLECTED})"
"${HERE}/seed/seed-loop.sh" "$SEED_MINUTES" "$ROWS_PER_MINUTE" "${ATT}/loader.jsonl"
SEED_ENDED=$(date +%s)
SEED_SECONDS=$(( SEED_ENDED - SEED_STARTED ))

# ── 6. 시작 계기판 (적재가 끝난 뒤, 앱 기동 전 - 델타에 적재 부하가 섞이지 않게) ──
"${HERE}/observe/mysql-status.sh" master "${ATT}/mysql-master-status-start.txt"
"${HERE}/observe/mysql-status.sh" replica "${ATT}/mysql-replica-status-start.txt"
if [ "$CLUSTER_MODE" = "cluster" ]; then
  for node in $CLUSTER_NODES; do
    REDIS_CONTAINER="$node" "${HERE}/observe/redis-info.sh" "${ATT}/redis-start-${node##*-}"
  done
else
  "${HERE}/observe/redis-info.sh" "${ATT}/redis-start"
fi
MYSQL_VERSION=$(mysql_master "SELECT VERSION()")
REDIS_VERSION=$(redis_cli INFO server | awk -F: '/^redis_version/{print $2}')
COMMIT=$(cd "$REPO" && git rev-parse --short HEAD)

# ── 6-1. 앱 기동 (적재가 끝난 뒤 - 쌓인 행 전량이 첫 틱의 대상) ─────────────
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

before_check
# T0 = RUN_STARTED. app3 훅의 기준 시각이고 구간 경계도 여기서부터 센다.
RUN_STARTED=$(date +%s)

# ── 7. 표본 ────────────────────────────────────────────────────────────────
# 소비 계기판. 마지막 표본에서 앱별 소비 카운터를 합한다.
#   app3 는 강제 종료되므로 그 뒤로 표본이 없다 - 마지막 파일(=kill 직전 표본)이 그대로 남아 합에 들어간다.
consume_sum() {
  local total=0 dir last value
  for dir in "${ATT}"/app*; do
    last=$(ls -1 "$dir"/prom-*.txt 2>/dev/null | tail -1 || true); [ -n "$last" ] || continue
    value=$(awk '/^ingestion_consume_seconds_count\{/ {s += $NF} END {printf "%.0f", s + 0}' "$last")
    total=$(( total + value ))
  done
  echo "$total"
}
xpending_total() {
  local total=0 stream value
  for stream in $LAB_STREAMS; do
    value=$(redis_cli XPENDING "$stream" "$GROUP" 2>/dev/null | head -1)
    case "$value" in ''|*[!0-9]*) value=0 ;; esac
    total=$(( total + value ))
  done
  echo "$total"
}

DEADLINE=$(( RUN_STARTED + TMAX_SECONDS ))
SAMPLES=0; idle=0; last_consume=-1; LAST_ACTIVE_AT=$RUN_STARTED
: > "${ATT}/app3-events.txt"
while [ "$(date +%s)" -lt "$DEADLINE" ]; do
  elapsed=$(( $(date +%s) - RUN_STARTED ))

  # app3 합류. 이미지는 이미 빌드돼 있어야 한다 - 여기서 빌드를 타면 합류가 수십 초 늦는다.
  if [ "$APP3_START" -gt 0 ] && [ "$app3_started" = "0" ] && [ "$elapsed" -ge "$APP3_START" ]; then
    mkdir -p "${ATT}/app3"
    compose up -d app3 >/dev/null 2>&1 || compose up -d app3
    APP3_START_AT=$(date +%s); app3_started=1
    export APP_PORTS="18091 18092 18093"
    printf '%s app3_start elapsed=%s\n' "$APP3_START_AT" "$elapsed" >> "${ATT}/app3-events.txt"
    echo "-- app3 합류 요청 (T+${elapsed}s)"
  fi

  # app3 강제 종료. SIGTERM 이면 구독이 정리돼 미처리 목록이 남지 않는다 - 회수 시연이 성립하려면 KILL 이어야 한다.
  if [ "$APP3_KILL" -gt 0 ] && [ "$app3_started" = "1" ] && [ "$app3_killed" = "0" ] && [ "$elapsed" -ge "$APP3_KILL" ]; then
    "${HERE}/observe/scrape-apps.sh" "$ATT"          # kill 직전 마지막 표본(소비 합의 app3 몫이 이 파일이다)
    APP3_LAST_SCRAPE_AT=$(ls -1 "${ATT}"/app3/prom-*.txt 2>/dev/null | sed 's/.*prom-\([0-9]*\)\.txt/\1/' | sort -n | tail -1 || true)
    docker kill -s KILL outboxlock-app3 >/dev/null 2>&1 || true
    APP3_KILL_AT=$(date +%s); app3_killed=1
    export APP_PORTS="18091 18092"
    printf '%s app3_kill elapsed=%s last_scrape=%s\n' "$APP3_KILL_AT" "$elapsed" "${APP3_LAST_SCRAPE_AT:-0}" >> "${ATT}/app3-events.txt"
    {
      printf '# kill 직후 app3 미처리 목록 원문 (%s)\n' "$(date +%s)"
      for consumer in app3-culture-0 app3-culture-1; do
        printf '\n== XPENDING %s %s - + 100 %s\n' "$STREAM" "$GROUP" "$consumer"
        redis_cli XPENDING "$STREAM" "$GROUP" - + 100 "$consumer" || true
      done
    } > "${ATT}/app3-pending.txt" 2>&1
    echo "-- app3 강제 종료 (T+${elapsed}s)"
  fi

  "${HERE}/observe/scrape-apps.sh" "$ATT"
  "${HERE}/observe/replica-status.sh" "${ATT}/replica-lag.txt"
  "${HERE}/observe/docker-stats.sh" "${ATT}/docker-stats.csv"
  # 대기열 덤프는 표본마다 docker exec 를 열여섯 번 부른다 - 그만큼 표본 간격이 벌어지므로 세 표본에 한 번만.
  if [ $(( SAMPLES % 3 )) -eq 0 ]; then "${HERE}/observe/stream-status.sh" "${ATT}/stream-samples.txt"; fi
  pending=$(xpending_total)
  cur=$(consume_sum)
  { printf '%s xlen=%s consume=%s xpending=%s\n' "$(date +%s)" "$(redis_cli XLEN "$STREAM")" "$cur" "$pending"; } >> "${ATT}/redis-samples.txt"
  SAMPLES=$(( SAMPLES + 1 ))

  if [ "$cur" = "$last_consume" ]; then idle=$(( idle + 1 )); else idle=0; LAST_ACTIVE_AT=$(date +%s); fi
  last_consume=$cur
  # 소진 = 소비 카운터가 멈췄고(3표본) 미처리도 0. 회수 대기 구간은 카운터가 멈추지만 XPENDING 이 남아 있어 걸러진다.
  # app3 훅이 남아 있으면 아직 소진이 아니다 - 훅을 못 밟고 끝나면 시연 자체가 없다.
  if [ "$idle" -ge "$IDLE_SAMPLES" ] && [ "$pending" -eq 0 ] \
     && [ $(( $(date +%s) - RUN_STARTED )) -ge "$MIN_RUN_SECONDS" ] \
     && { [ "$APP3_START" -eq 0 ] || [ "$app3_started" = "1" ]; } \
     && { [ "$APP3_KILL" -eq 0 ] || [ "$app3_killed" = "1" ]; }; then
    echo "-- 소비 계기판 정지 ${IDLE_SAMPLES}표본 + XPENDING 0 → 소진으로 종료"; break
  fi
  sleep 10
done
"${HERE}/observe/scrape-apps.sh" "$ATT"
SAMPLES=$(( SAMPLES + 1 ))
RUN_ENDED=$(date +%s)
DRAIN_SECONDS=$(( LAST_ACTIVE_AT - RUN_STARTED ))
else
  # 봉인만 다시: 측정 당시 값은 attachments 에서 복원한다.
  APPS="app1 app2"; PORTS="18091 18092"
  export APP_PORTS="$PORTS"
  STARTED_AT=$(awk '/^ started=/{sub(/^ started=/,""); print $1}' "${ATT}/before-check.txt")
  APPS_UP_AT=$(awk '/^ started=/{for(i=1;i<=NF;i++) if($i ~ /^up=/){sub(/^up=/,"",$i); print $i}}' "${ATT}/before-check.txt")
  MYSQL_VERSION=$(mysql_master "SELECT VERSION()")
  REDIS_VERSION=$(redis_cli INFO server | awk -F: '/^redis_version/{print $2}')
  COMMIT=$(cd "$REPO" && git rev-parse --short HEAD)
  RUN_STARTED=$(python3 -c "import sys,datetime;print(int(datetime.datetime.fromisoformat(sys.argv[1]).timestamp()))" "$APPS_UP_AT")
  SEED_SECONDS=$(python3 -c "import json,sys,datetime;l=[json.loads(x) for x in open(sys.argv[1]) if x.strip()];f=datetime.datetime.fromisoformat;print(int((f(l[-1]['ended_at'])-f(l[0]['started_at'])).total_seconds()))" "${ATT}/loader.jsonl")
  APP3_START_AT=$(awk '/app3_start/{print $1}' "${ATT}/app3-events.txt" 2>/dev/null | tail -1 || true); APP3_START_AT=${APP3_START_AT:-0}
  APP3_KILL_AT=$(awk '/app3_kill/{print $1}' "${ATT}/app3-events.txt" 2>/dev/null | tail -1 || true); APP3_KILL_AT=${APP3_KILL_AT:-0}
  APP3_LAST_SCRAPE_AT=$(ls -1 "${ATT}"/app3/prom-*.txt 2>/dev/null | sed 's/.*prom-\([0-9]*\)\.txt/\1/' | sort -n | tail -1 || true); APP3_LAST_SCRAPE_AT=${APP3_LAST_SCRAPE_AT:-0}
  RUN_ENDED=$(ls -1 "${ATT}"/app1/prom-*.txt | sed 's/.*prom-\([0-9]*\)\.txt/\1/' | sort -n | tail -1)
  SAMPLES=$(ls -1 "${ATT}"/app1/prom-*.txt | wc -l | tr -d ' ')
  DRAIN_SECONDS=-1
  echo "-- 봉인만 다시 (RUN_STARTED=${RUN_STARTED} RUN_ENDED=${RUN_ENDED} SAMPLES=${SAMPLES})"
fi

# ── 8. 종료 계기판 (부하가 끝난 뒤라 COUNT 를 돌려도 된다) ──────────────────
if [ "$FINALIZE_ONLY" != "1" ] || [ ! -f "${ATT}/mysql-master-status-end.txt" ]; then
"${HERE}/observe/mysql-status.sh" master "${ATT}/mysql-master-status-end.txt"
"${HERE}/observe/mysql-status.sh" replica "${ATT}/mysql-replica-status-end.txt"
"${HERE}/observe/innodb-status.sh" "${ATT}/innodb-status-end.txt"
if [ "$CLUSTER_MODE" = "cluster" ]; then
  for node in $CLUSTER_NODES; do
    REDIS_CONTAINER="$node" "${HERE}/observe/redis-info.sh" "${ATT}/redis-end-${node##*-}"
  done
  # 마커 키 표본(슬롯 배치를 볼 대상). 성공해도 해제하지 않고 TTL 에 맡기는 키라 런 직후에는 남아 있다.
  MARKER_KEYS=$(mysql_master "SELECT CONCAT('outbox:', id) FROM ingestion_outbox LIMIT 5" | tr '\n' ' ')
  "${HERE}/observe/cluster-status.sh" "${ATT}/cluster-end" lab:consumed:ids lock:stream-reclaim $MARKER_KEYS
  # V-03 증명: 단일 노드는 클러스터 런에서 아무도 읽지 않는다 - 비어 있다는 사실을 원문으로 남긴다.
  {
    printf '# 단일 노드(%s)는 클러스터 런에서 쓰이지 않는다 (%s)\n' "$REDIS" "$(now_iso)"
    printf 'dbsize %s\n' "$(docker exec -i "$REDIS" redis-cli DBSIZE | tr -d '\r')"
    printf 'xlen.%s %s\n' "$STREAM" "$(docker exec -i "$REDIS" redis-cli XLEN "$STREAM" | tr -d '\r')"
    printf 'cluster_enabled %s\n' "$(docker exec -i "$REDIS" redis-cli INFO cluster | tr -d '\r' | awk -F: '/^cluster_enabled/{print $2}')"
  } > "${ATT}/redis-single-node-check.txt" 2>&1
else
  "${HERE}/observe/redis-info.sh" "${ATT}/redis-end"
fi
LOG_APPS="$APPS"
if docker inspect outboxlock-app3 >/dev/null 2>&1; then LOG_APPS="$APPS app3"; fi
compose logs --no-color --tail 6000 $LOG_APPS > "${ATT}/app-logs.txt" 2>&1 || true

mysql_master "SELECT status, COUNT(*) FROM ingestion_outbox GROUP BY status" > "${ATT}/outbox-status-count.txt"
docker exec -i -e MYSQL_PWD="$ROOT_PW" "$MASTER" mysql -uroot "$DB" \
  -e "SELECT aggregate_type, event_type, COUNT(*) FROM ingestion_outbox GROUP BY 1,2" > "${ATT}/outbox-shape.txt"
fi

"${HERE}/observe/stream-status.sh" "${ATT}/stream-status-end.txt" app1-culture-0 app1-culture-1 app2-culture-0 app2-culture-1 app3-culture-0 app3-culture-1

# ── 9. 집계 ────────────────────────────────────────────────────────────────
metric_sum() {
  local pattern=$1 total=0 dir last value
  for dir in "${ATT}"/app*; do
    last=$(ls -1 "$dir"/prom-*.txt 2>/dev/null | tail -1 || true); [ -n "$last" ] || continue
    value=$(awk -v p="$pattern" '$0 ~ p {sum += $NF} END {printf "%.0f", sum + 0}' "$last")
    total=$(( total + value ))
  done
  echo "$total"
}
metric_by_app() {
  local pattern=$1 out="" dir last value
  for dir in "${ATT}"/app*; do
    last=$(ls -1 "$dir"/prom-*.txt 2>/dev/null | tail -1 || true); [ -n "$last" ] || continue
    value=$(awk -v p="$pattern" '$0 ~ p {sum += $NF} END {printf "%.0f", sum + 0}' "$last")
    out="${out}$(basename "$dir")=${value} "
  done
  echo "$out"
}
CONSUME_SUCCESS=$(metric_sum '^ingestion_consume_seconds_count\{.*result="success"')
CONSUME_TOTAL=$(metric_sum '^ingestion_consume_seconds_count\{')
CONSUME_BY_APP=$(metric_by_app '^ingestion_consume_seconds_count\{.*result="success"')
RECLAIM_CLAIMED=$(metric_sum '^ingestion_stream_reclaim_total\{.*result="claimed"')
RECLAIM_SKIPPED=$(metric_sum '^ingestion_stream_reclaim_total\{.*result="skipped"')
PUBLISH_SUCCESS=$(metric_sum '^ingestion_outbox_publish_total\{.*result="success"')
PUBLISH_FAILURE=$(metric_sum '^ingestion_outbox_publish_total\{.*result="failure"')
CLAIM_CALLS=$(metric_sum '^ingestion_outbox_claim_total\{')
CLAIM_ROWS=$(metric_sum '^ingestion_outbox_claim_rows_total\{')
MARKER_ACQUIRED=$(metric_sum '^ingestion_outbox_marker_total\{.*result="acquired"')
MARKER_SKIPPED=$(metric_sum '^ingestion_outbox_marker_total\{.*result="skipped"')

CONSUMED_UNIQUE=$(redis_cli SCARD lab:consumed:ids); CONSUMED_UNIQUE=${CONSUMED_UNIQUE:-0}
CONSUMED_DUP=$(redis_cli GET lab:consumed:dup); CONSUMED_DUP=${CONSUMED_DUP:-0}
CONSUMED_COUNT=$(redis_cli GET lab:consumed:count); CONSUMED_COUNT=${CONSUMED_COUNT:-0}
XLEN_STREAM=$(redis_cli XLEN "$STREAM"); XLEN_STREAM=${XLEN_STREAM:-0}
XPENDING_END=$(xpending_total)
APP3_PENDING=$(grep -cxE 'app3-culture-[01]' "${ATT}/app3-pending.txt" 2>/dev/null || true)
APP3_PENDING=${APP3_PENDING:-0}

TOTAL_ROWS=$(mysql_master "SELECT COUNT(*) FROM ingestion_outbox")
SENT_ROWS=$(mysql_master "SELECT COUNT(*) FROM ingestion_outbox WHERE status='SENT'")
PENDING_ROWS=$(mysql_master "SELECT COUNT(*) FROM ingestion_outbox WHERE status='PENDING'")
FAILED_ROWS=$(mysql_master "SELECT COUNT(*) FROM ingestion_outbox WHERE status='FAILED'")
EXTERNAL_CALLS=$(mysql_master "SELECT COUNT(*) FROM external_api_call_log WHERE source='INGESTION'" 2>/dev/null || echo 0)

# before 진위 확인의 나머지 두 줄은 런이 끝나야 답이 나온다.
{
  printf '\n# ② 외부 API 호출 감사표 - 유료 호출 0건의 직접 증거\n'
  printf " SELECT COUNT(*) FROM external_api_call_log WHERE source='INGESTION' = %s\n" "$EXTERNAL_CALLS"
  printf '\n# ③ 소비 카운터에 붙은 event_type 태그(DETAIL_READY 하나여야 한다)\n'
  for dir in "${ATT}"/app*; do
    last=$(ls -1 "$dir"/prom-*.txt 2>/dev/null | tail -1 || true); [ -n "$last" ] || continue
    printf ' %s %s\n' "$(basename "$dir")" \
      "$(grep '^ingestion_consume_seconds_count{' "$last" | sed 's/.*event_type="\([^"]*\)".*/\1/' | sort -u | tr '\n' ',' )"
  done
} >> "${ATT}/before-check.txt"

delta() {
  local name=$1 s e
  s=$(awk -v n="$name" '$1 == n {print $2}' "${ATT}/mysql-master-status-start.txt")
  e=$(awk -v n="$name" '$1 == n {print $2}' "${ATT}/mysql-master-status-end.txt")
  echo $(( ${e:-0} - ${s:-0} ))
}
COM_SELECT=$(delta Com_select)
ROWS_READ=$(delta Innodb_rows_read)
COM_COMMIT=$(delta Com_commit)
LOCK_WAITS=$(delta Innodb_row_lock_waits)

MEASURED_AT=$(now_iso)
ELAPSED=$(( RUN_ENDED - RUN_STARTED ))

# ── 10. 봉인 ───────────────────────────────────────────────────────────────
export ATT OUT VARIANT MODE INSTANCES CLUSTER_MODE CLUSTER_NODES STREAM GROUP \
  RUN_STARTED RUN_ENDED ELAPSED DRAIN_SECONDS SEED_SECONDS SAMPLES IDLE_SAMPLES TMAX_SECONDS \
  APP3_START_AT APP3_KILL_AT APP3_LAST_SCRAPE_AT APP3_PENDING APP3_START APP3_KILL \
  CONSUME_SUCCESS CONSUME_TOTAL CONSUME_BY_APP RECLAIM_CLAIMED RECLAIM_SKIPPED \
  PUBLISH_SUCCESS PUBLISH_FAILURE CLAIM_CALLS CLAIM_ROWS MARKER_ACQUIRED MARKER_SKIPPED \
  CONSUMED_UNIQUE CONSUMED_DUP CONSUMED_COUNT XLEN_STREAM XPENDING_END \
  TOTAL_ROWS SENT_ROWS PENDING_ROWS FAILED_ROWS EXTERNAL_CALLS \
  COM_SELECT ROWS_READ COM_COMMIT LOCK_WAITS \
  MYSQL_VERSION REDIS_VERSION COMMIT MEASURED_AT STARTED_AT APPS_UP_AT \
  ROWS_PER_MINUTE SEED_MINUTES
python3 "${HERE}/seal-push.py"

echo "== 봉인: ${OUT}"
echo "   consume_success=${CONSUME_SUCCESS} unique=${CONSUMED_UNIQUE} dup=${CONSUMED_DUP} xpending=${XPENDING_END} xlen=${XLEN_STREAM}"
echo "   outbox sent=${SENT_ROWS} pending=${PENDING_ROWS} failed=${FAILED_ROWS} external_calls=${EXTERNAL_CALLS}"
compose stop app1 app2 >/dev/null 2>&1 || true
