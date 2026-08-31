#!/usr/bin/env bash
# GTID 비동기 복제를 건다. 원본·복제본이 healthy 가 된 뒤 호스트에서 한 번 실행한다.
#
# 초기화 시점의 GTID 부터 시작하는 이유
#   두 컨테이너는 같은 환경변수로 각자 초기화되어 mydatabase·myuser 를 이미 갖고 있다. 복제를 처음부터
#   재생시키면 원본의 초기화 트랜잭션(CREATE DATABASE·CREATE USER)이 복제본에서 "이미 있음"으로 죽는다.
#   그래서 복제본의 GTID 이력을 비우고 원본의 현재 위치를 purged 로 박아 "지금부터" 흘러오게 한다.
#   이 실험에서 복제본이 받아야 하는 것은 앱의 Flyway DDL 과 적재 이후의 변경뿐이다.
set -euo pipefail

MASTER=${MASTER_CONTAINER:-outboxlock-mysql-master}
REPLICA=${REPLICA_CONTAINER:-outboxlock-mysql-replica}
ROOT_PW=${MYSQL_ROOT_PASSWORD:-verysecret}

master_sql() { docker exec -i -e MYSQL_PWD="$ROOT_PW" "$MASTER" mysql -uroot --silent --skip-column-names -e "$1"; }
replica_sql() { docker exec -i -e MYSQL_PWD="$ROOT_PW" "$REPLICA" mysql -uroot -e "$1"; }

master_sql "CREATE USER IF NOT EXISTS 'repl'@'%' IDENTIFIED BY 'replsecret';
           GRANT REPLICATION SLAVE ON *.* TO 'repl'@'%';
           FLUSH PRIVILEGES;" >/dev/null

GTID=$(master_sql "SELECT @@GLOBAL.gtid_executed;" | tr -d '\n')
echo "master gtid_executed = ${GTID}"

# caching_sha2_password + 비TLS 라 공개키를 받아 와야 접속이 된다(GET_SOURCE_PUBLIC_KEY=1).
replica_sql "STOP REPLICA;
            RESET REPLICA ALL;
            RESET BINARY LOGS AND GTIDS;
            SET GLOBAL gtid_purged = '${GTID}';
            CHANGE REPLICATION SOURCE TO
              SOURCE_HOST='mysql-master', SOURCE_PORT=3306,
              SOURCE_USER='repl', SOURCE_PASSWORD='replsecret',
              SOURCE_AUTO_POSITION=1, GET_SOURCE_PUBLIC_KEY=1;
            START REPLICA;" >/dev/null

# 복제본을 읽기 전용으로 잠근다. 복제 적용 스레드는 이 제약에서 면제되므로 복제는 계속 흐른다.
# 앱이 복제본에 쓰기(또는 잠금 조회)를 시도하면 여기서 거부되고, 그 거부 원문이 읽기 복제 분산 논증의 증거다.
replica_sql "SET GLOBAL read_only = ON; SET GLOBAL super_read_only = ON;" >/dev/null

for _ in $(seq 1 30); do
  status=$(docker exec -i -e MYSQL_PWD="$ROOT_PW" "$REPLICA" mysql -uroot -e "SHOW REPLICA STATUS\G" 2>/dev/null || true)
  io=$(echo "$status" | awk -F': ' '/Replica_IO_Running/{print $2}' | tr -d ' ')
  sql=$(echo "$status" | awk -F': ' '/Replica_SQL_Running:/{print $2}' | tr -d ' ')
  if [ "$io" = "Yes" ] && [ "$sql" = "Yes" ]; then
    echo "replication running (IO=${io} SQL=${sql})"
    exit 0
  fi
  sleep 2
done

echo "복제가 시작되지 않았습니다. SHOW REPLICA STATUS 를 확인하십시오." >&2
docker exec -i -e MYSQL_PWD="$ROOT_PW" "$REPLICA" mysql -uroot -e "SHOW REPLICA STATUS\G" >&2
exit 1
