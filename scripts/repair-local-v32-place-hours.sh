#!/usr/bin/env bash
# V32(제약 강화) 실패 복구 — 로컬 전용. 운영·CI는 해당 없음(빈 DB에서 시작하므로 중복이 생기지 않는다).
#
# 증상:
#   FlywaySqlScriptException: V32__enforce_place_constraints.sql
#   Duplicate entry 'NNN' for key 'place_hours.uk_place_hours_exhibition_place_id'
#
# 원인:
#   place_hours.place_key 에 두 세대 형식이 섞여 있다 — 구버전은 주소 기준("부산광역시 남구 우암로 84-1 부산문화재단"),
#   현행은 이름 기준(PlaceKey.of = 정규화한 이름, "부산문화재단"). V31 백필이 둘 다 같은 exhibition_place_id 로
#   매핑하면서 장소당 2행이 되고, V32의 UK(장소당 1행)에 걸린다. 오래 굴린 로컬 DB에서만 발생한다.
#
# 이 스크립트가 하는 일(순서 중요):
#   1) 중복 정리 — 정준 키(exhibition_place.place_key = 이름)와 일치하는 행을 남기고 나머지를 지운다.
#   2) V32가 이미 만든 인덱스 제거 — MySQL DDL은 트랜잭션이 아니라 실패 전까지가 적용된 채 남는다.
#      CREATE INDEX 는 멱등이 아니라, 지우지 않으면 재실행이 line 10 에서 "Duplicate key name" 으로 또 죽는다.
#   3) 실패 이력 제거 — flyway repair 와 같은 효과(플러그인 없이 직접 지운다).
# 이후 앱을 다시 띄우면 V32가 처음부터 재실행되어 정상 완료된다.
#
# ※ V32 자체는 고치지 않는다 — 이미 develop·main 에 머지돼 운영에 적용 완료라, 수정하면 체크섬이 깨진다.
set -euo pipefail
cd "$(dirname "$0")/.."

MYSQL='docker exec -i modi-mysql sh -c mysql\ -uroot\ -p"$MYSQL_ROOT_PASSWORD"\ --default-character-set=utf8mb4\ mydatabase'

echo "[repair] 1/3 place_hours 중복 정리 — 지워질 행:"
docker exec -i modi-mysql sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" --default-character-set=utf8mb4 -t mydatabase' <<'SQL'
SELECT h.id, h.exhibition_place_id, h.place_key, p.place_key AS canonical
FROM place_hours h JOIN exhibition_place p ON p.id = h.exhibition_place_id
WHERE h.id NOT IN (
    SELECT keep_id FROM (
        SELECT COALESCE(MIN(CASE WHEN h2.place_key = p2.place_key THEN h2.id END), MIN(h2.id)) AS keep_id
        FROM place_hours h2 JOIN exhibition_place p2 ON p2.id = h2.exhibition_place_id
        GROUP BY h2.exhibition_place_id) t);
SQL

docker exec -i modi-mysql sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" --default-character-set=utf8mb4 mydatabase' <<'SQL'
DELETE h FROM place_hours h
WHERE h.id NOT IN (
    SELECT keep_id FROM (
        SELECT COALESCE(MIN(CASE WHEN h2.place_key = p2.place_key THEN h2.id END), MIN(h2.id)) AS keep_id
        FROM place_hours h2 JOIN exhibition_place p2 ON p2.id = h2.exhibition_place_id
        GROUP BY h2.exhibition_place_id) t);
SQL

echo "[repair] 2/3 V32가 남긴 인덱스 제거(재실행 가능하게)"
docker exec -i modi-mysql sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" mydatabase' <<'SQL'
SET @s = IF((SELECT COUNT(*) FROM information_schema.statistics
             WHERE table_schema = DATABASE() AND table_name = 'exhibitions'
               AND index_name = 'idx_exhibitions_exhibition_place_id') > 0,
            'DROP INDEX idx_exhibitions_exhibition_place_id ON exhibitions',
            'SELECT "already absent"');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SQL

echo "[repair] 3/3 Flyway 실패 이력 제거(= flyway repair)"
docker exec -i modi-mysql sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" mydatabase' <<'SQL'
DELETE FROM flyway_schema_history WHERE version = '32' AND success = 0;
SQL

echo "[repair] 완료. 검증:"
docker exec -i modi-mysql sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" --default-character-set=utf8mb4 -t mydatabase' <<'SQL'
SELECT COUNT(*) AS '남은 중복(0이어야 정상)' FROM (
    SELECT exhibition_place_id FROM place_hours GROUP BY exhibition_place_id HAVING COUNT(*) > 1) d;
SELECT version, success FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 3;
SQL
echo "[repair] 이제 앱을 다시 띄우면 V32가 재실행되어 완료된다."
