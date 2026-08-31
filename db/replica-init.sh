#!/usr/bin/env bash
# 읽기 복제본의 첫 기동 시딩 + GTID 복제 연결. mysql 공식 이미지의 초기화 훅
# (/docker-entrypoint-initdb.d)이라 "복제본 볼륨이 비어 있는 첫 기동"에만 실행된다.
#
# 원본에 이미 데이터가 있어도 되는 이유
#   - 복제를 이력 처음부터 재생하지 않는다. 지금 시점의 원본을 덤프(--single-transaction)로 통째로 붓고,
#     덤프가 함께 실어 오는 gtid_purged(덤프 시점까지의 GTID)를 바닥으로 삼아 "그 이후"만 흘려받는다.
#   - 복제본 자체 초기화(빈 DB·계정 생성)가 남긴 GTID는 RESET으로 비워야 덤프의 gtid_purged 주입이 된다.
#
# 마지막의 read_only는 SET PERSIST — 초기화용 임시 서버가 내려가고 본 서버가 떠도 유지된다.
set -euo pipefail

MASTER_HOST=mysql
ROOT_PW="${MYSQL_ROOT_PASSWORD}"

master() { mysql -h "$MASTER_HOST" -uroot -p"$ROOT_PW" "$@"; }
local_sql() { mysql -uroot -p"$ROOT_PW" "$@"; }

echo "[replica-init] 원본(${MASTER_HOST}) 접속 대기..."
for i in $(seq 1 60); do
  if master -e 'SELECT 1' >/dev/null 2>&1; then break; fi
  if [ "$i" = "60" ]; then echo "[replica-init] 원본에 접속하지 못했습니다" >&2; exit 1; fi
  sleep 2
done

# 복제 계정 — 원본에 멱등 생성(원본 볼륨은 기존 것이라 원본의 init 훅은 다시 돌지 않는다).
master -e "CREATE USER IF NOT EXISTS 'repl'@'%' IDENTIFIED BY 'replsecret';
           GRANT REPLICATION SLAVE ON *.* TO 'repl'@'%';
           FLUSH PRIVILEGES;"

echo "[replica-init] 원본 덤프 시딩 시작..."
mysqldump -h "$MASTER_HOST" -uroot -p"$ROOT_PW" \
  --databases mydatabase \
  --single-transaction --triggers --routines --events \
  --set-gtid-purged=ON > /tmp/replica-seed.sql

# 복제본 자체 초기화가 남긴 GTID 이력을 비워야 덤프의 SET gtid_purged가 통과한다.
local_sql -e 'RESET BINARY LOGS AND GTIDS;'
local_sql < /tmp/replica-seed.sql
rm -f /tmp/replica-seed.sql

# caching_sha2_password + 비TLS 조합이라 공개키를 받아 와야 접속이 된다(GET_SOURCE_PUBLIC_KEY=1).
local_sql -e "CHANGE REPLICATION SOURCE TO
                SOURCE_HOST='${MASTER_HOST}', SOURCE_PORT=3306,
                SOURCE_USER='repl', SOURCE_PASSWORD='replsecret',
                SOURCE_AUTO_POSITION=1, GET_SOURCE_PUBLIC_KEY=1;
              START REPLICA;"

# 읽기 전용 잠금 — 복제 적용 스레드는 면제라 복제는 계속 흐르고, 앱의 실수 쓰기만 막힌다.
# PERSIST라 본 서버 재기동 후에도 유지된다. (cnf에 박으면 이 초기화 자체가 막혀서 안 된다.)
local_sql -e 'SET PERSIST read_only=ON; SET PERSIST super_read_only=ON;'

echo "[replica-init] 완료 — 덤프 시딩 + GTID 복제 연결 + read_only 잠금"
