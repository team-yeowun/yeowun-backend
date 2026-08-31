# 관측 스크립트 공용 값. 컨테이너 이름은 compose 의 container_name 그대로다.
MASTER=${MASTER_CONTAINER:-outboxlock-mysql-master}
REPLICA=${REPLICA_CONTAINER:-outboxlock-mysql-replica}
REDIS=${REDIS_CONTAINER:-outboxlock-redis}
ROOT_PW=${MYSQL_ROOT_PASSWORD:-verysecret}
DB=${MYSQL_DATABASE:-mydatabase}
APP_PORTS=${APP_PORTS:-"18091 18092"}
STREAM=${STREAM_KEY:-ingestion.db}
