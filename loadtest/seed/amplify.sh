#!/usr/bin/env bash
# 전시 데이터 증폭 — 원본 313전시/229전시장 한 벌을 "세대" 단위로 복제한다.
#
#   ./amplify.sh <목표_전시행수> [배치크기]
#
# 원칙
#  · 원본(lt_base.max_id 이하)은 절대 건드리지 않는다. 증폭분은 전부 그 위 id.
#  · 오름차순 증분 — 이미 있는 세대는 건너뛰고 모자란 세대만 채운다(재실행 안전).
#  · 배치마다 커밋한다. 한 트랜잭션에 수백만 행을 넣으면 undo/redo가 터진다(스래싱 방지).
#  · 분포는 원본을 그대로 복제해 유지한다. 의도적 이탈은 our_view_count 하나뿐
#    (원본이 거의 전부 0이라 그대로 두면 인기순 정렬이 id desc로 퇴화한다).
set -euo pipefail

TARGET_ROWS="${1:?usage: amplify.sh <target_rows> [batch_generations]}"
BATCH="${2:-100}"

DB=mydatabase
MYSQL=(docker exec -i modi-mysql mysql -uroot -pverysecret --default-character-set=utf8mb4 "$DB")

q() { "${MYSQL[@]}" -N -B -e "$1" 2>/dev/null; }

BASE_EXH=$(q "SELECT max_id FROM lt_base WHERE t='exhibitions'")
PER_GEN=$(q "SELECT COUNT(*) FROM exhibitions WHERE id <= ${BASE_EXH}")
CUR=$(q "SELECT COUNT(*) FROM exhibitions")
DONE_GEN=$(q "SELECT COALESCE(MAX(CAST(SUBSTRING_INDEX(SUBSTRING_INDEX(external_id,'-',2),'-',-1) AS UNSIGNED)),0)
              FROM exhibitions WHERE external_id LIKE 'LT-%'")

NEED_GEN=$(( (TARGET_ROWS - PER_GEN + PER_GEN - 1) / PER_GEN ))
(( NEED_GEN < 0 )) && NEED_GEN=0

# V49(지역·무료 비정규화) 대응 — 증폭은 컬럼을 <b>명시적으로</b> 나열해 복제하므로, 새 컬럼을 여기 넣지 않으면
# 증폭분 전체가 region=NULL·is_free=0으로 들어간다. 그러면 지역·무료 필터가 0건을 돌려주면서
# 스캔 시간은 그대로 나와, "빨라진 것처럼 보이지만 실은 아무것도 안 센" 가짜 측정이 된다.
# (앱 부팅 전에 증폭이 돌기 때문에 V49가 아직 적용되지 않은 DB도 있을 수 있어 컬럼 존재를 확인한다.)
DENORM_COLS=""; DENORM_VALS=""
if [ "$(q "SELECT COUNT(*) FROM information_schema.columns
           WHERE table_schema='${DB}' AND table_name='exhibitions' AND column_name='region'")" = "1" ]; then
  DENORM_COLS=", region, is_free"
  DENORM_VALS=", e.region, e.is_free"
  echo "[amplify] V49 비정규화 컬럼 감지 — region·is_free도 함께 복제한다"
else
  echo "[amplify] V49 이전 스키마 — region·is_free 복제 생략(부팅 시 Flyway 백필이 전량을 채운다)"
fi

echo "[amplify] 원본 ${PER_GEN}행/세대 · 현재 ${CUR}행(세대 ${DONE_GEN}) → 목표 ${TARGET_ROWS}행(세대 ${NEED_GEN})"

if (( DONE_GEN >= NEED_GEN )); then
  echo "[amplify] 이미 목표 이상 — 건너뜀"
  exit 0
fi

START_TS=$(date +%s)
from=$(( DONE_GEN + 1 ))

while (( from <= NEED_GEN )); do
  to=$(( from + BATCH - 1 ))
  (( to > NEED_GEN )) && to=$NEED_GEN

  "${MYSQL[@]}" <<SQL 2>/dev/null
SET NAMES utf8mb4;
SET SESSION unique_checks = 0;          -- 키는 생성 규칙상 유일하다(중복 검사 비용 제거)
SET SESSION foreign_key_checks = 0;

-- 1) 전시장: place_key(UK)에 세대 접미사. 좌표는 결정적 지터로 흩는다.
INSERT INTO exhibition_place
  (place_key, name, region, sigungu, gps_x, gps_y, address, phone, place_url, created_at, updated_at)
SELECT CONCAT(p.place_key, '#', s.n), p.name, p.region, p.sigungu,
       p.gps_x + ((s.n * 37 % 41) - 20) / 1000,
       p.gps_y + ((s.n * 53 % 41) - 20) / 1000,
       p.address, p.phone, p.place_url, p.created_at, p.updated_at
FROM exhibition_place p
CROSS JOIN lt_seq s
WHERE p.id <= (SELECT max_id FROM lt_base WHERE t='exhibition_place')
  AND s.n BETWEEN ${from} AND ${to};

-- 2) 전시: 원본 한글 제목을 그대로 재사용한다(합성 문자열은 like '%키워드%' 매칭률을 왜곡).
--    날짜는 결정적 ±15일 지터, 조회수만 의도적 이탈(로그정규 근사).
INSERT INTO exhibitions
  (type, external_id, owner_id, title, exhibition_place_id, start_date, end_date,
   category, format, poster_url, detail_url, service_name, our_view_count,
   created_at, updated_at, deleted_at${DENORM_COLS})
SELECT e.type,
       CONCAT('LT-', s.n, '-', e.id),
       e.owner_id,
       e.title,
       np.id,
       -- 센티널(날짜 미상)은 지터를 먹이지 않는다. V47이 NULL을 '1000-01-01'/'9999-12-31'로 굳혔는데,
       -- 여기에 ±15일을 더하면 ① '9999-12-31' + 15일이 DATE 범위를 넘어 NULL이 되고
       -- ② 값이 센티널과 달라져 "미상"이라는 의미 자체가 사라진다(배너 제외·게터 마스킹이 안 먹는다).
       CASE WHEN e.start_date = '1000-01-01' THEN e.start_date
            ELSE e.start_date + INTERVAL (((e.id * 31 + s.n * 17) % 31) - 15) DAY END,
       CASE WHEN e.end_date = '9999-12-31' THEN e.end_date
            ELSE e.end_date + INTERVAL (((e.id * 31 + s.n * 17) % 31) - 15) DAY END,
       e.category, e.format, e.poster_url, e.detail_url, e.service_name,
       FLOOR(POW(((e.id * 131 + s.n * 7919) % 997) / 997.0, 3) * 1200),
       e.created_at, e.updated_at, NULL${DENORM_VALS}
FROM exhibitions e
JOIN exhibition_place op ON op.id = e.exhibition_place_id
JOIN lt_seq s ON s.n BETWEEN ${from} AND ${to}
JOIN exhibition_place np ON np.place_key = CONCAT(op.place_key, '#', s.n)
WHERE e.id <= (SELECT max_id FROM lt_base WHERE t='exhibitions');

-- 3) 상세(1:1). 가격·설명을 그대로 복제해 무료 비율과 행 크기를 유지한다.
INSERT INTO exhibition_detail (exhibition_id, price, description, img_url, synced_at)
SELECT ne.id, d.price, d.description, d.img_url, d.synced_at
FROM exhibition_detail d
JOIN lt_seq s ON s.n BETWEEN ${from} AND ${to}
JOIN exhibitions ne ON ne.external_id = CONCAT('LT-', s.n, '-', d.exhibition_id)
WHERE d.exhibition_id <= (SELECT max_id FROM lt_base WHERE t='exhibitions');

-- 4) 장르(상세 조회 경로가 읽는다).
INSERT INTO exhibition_genre (exhibition_id, genre_keyword, provider, model, classified_at)
SELECT ne.id, g.genre_keyword, g.provider, g.model, g.classified_at
FROM exhibition_genre g
JOIN lt_seq s ON s.n BETWEEN ${from} AND ${to}
JOIN exhibitions ne ON ne.external_id = CONCAT('LT-', s.n, '-', g.exhibition_id)
WHERE g.exhibition_id <= (SELECT max_id FROM lt_base WHERE t='exhibitions');
SQL

  now=$(q "SELECT COUNT(*) FROM exhibitions")
  el=$(( $(date +%s) - START_TS ))
  echo "[amplify]   세대 ${from}~${to} 완료 · exhibitions=${now} · ${el}s"
  from=$(( to + 1 ))
done

echo "[amplify] 완료 — exhibitions=$(q 'SELECT COUNT(*) FROM exhibitions') (${SECONDS}s)"
