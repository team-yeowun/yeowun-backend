-- =====================================================================
-- 로컬 더미 보강 시드 (스냅샷 시드 직후 실행) — 전시 도메인 ERD(V34) 스키마 버전
-- 목적: 부팅 시 외부 API(구글 영업시간·AI 장르·상세) 호출 없이도
--       화면에 보강 데이터가 "다 채워진" 상태로 보이게 임의 값으로 초기화.
-- 실행: scripts/reset-local-exhibition-data.sh 가 스냅샷 시드 다음에 자동 실행.
-- 값 표식: 더미임을 알 수 있게 description에 [로컬 더미] 프리픽스, provider는 MOCK/UNKNOWN.
-- 멱등: 재실행해도 안전 (부재 행만 생성, NULL 값만 채움).
-- =====================================================================
-- 한글 값이 있으므로 클라이언트 캐릭터셋을 명시한다 (mysqldump 출력과 동일한 이유).
-- 없으면 docker exec 파이프 경로에서 latin1로 해석돼 더미 한글이 깨진다.
SET NAMES utf8mb4;

-- 1) 모든 전시장에 임의 영업시간 — 3패턴 로테이션, provider=MOCK (장소당 1행 = 유료 dedup 구조 그대로)
INSERT IGNORE INTO place_hours (exhibition_place_id, formatted, status, provider, attempt_count, next_attempt_at)
SELECT t.id,
       CASE MOD(t.rn, 3)
         WHEN 0 THEN '화~일 10:00~18:00 (월요일 휴관)'
         WHEN 1 THEN '매일 09:30~17:30'
         ELSE        '수~월 11:00~19:00 (화요일 휴관)'
       END,
       'SUCCEEDED', 'MOCK', 1, NULL
FROM (SELECT id, ROW_NUMBER() OVER (ORDER BY id) AS rn FROM exhibition_place) t;

-- 2) 장르 공백 보강 — provider=UNKNOWN(V21 백필 선례)
INSERT INTO exhibition_genre (exhibition_id, genre_keyword, provider, model, classified_at)
SELECT e.id,
       ELT(1 + MOD(e.id, 5), '회화', '미디어아트', '사진', '조각', '디자인'),
       'UNKNOWN', NULL, NOW(6)
FROM exhibitions e
WHERE NOT EXISTS (SELECT 1 FROM exhibition_genre g WHERE g.exhibition_id = e.id);

-- 3) 상세 미동기화 전시에 더미 detail 행 생성 (행 존재 = 동기화됨 의미라, 로컬 표시 목적으로만)
INSERT INTO exhibition_detail (exhibition_id, price, description, img_url, synced_at)
SELECT e.id,
       ELT(1 + MOD(e.id, 3), '무료', '성인 5,000원', '성인 10,000원 / 학생 5,000원'),
       CONCAT('[로컬 더미] ', e.title, ' — 실제 소개문은 상세 동기화 시 채워집니다.'),
       NULL, NOW(6)
FROM exhibitions e
WHERE NOT EXISTS (SELECT 1 FROM exhibition_detail d WHERE d.exhibition_id = e.id);

-- 4) 기존 detail 행의 결측 값 채움
UPDATE exhibition_detail d
JOIN exhibitions e ON e.id = d.exhibition_id
SET d.description = CONCAT('[로컬 더미] ', e.title, ' — 실제 소개문은 상세 동기화 시 채워집니다.')
WHERE d.description IS NULL;

UPDATE exhibition_detail
SET price = ELT(1 + MOD(exhibition_id, 3), '무료', '성인 5,000원', '성인 10,000원 / 학생 5,000원')
WHERE price IS NULL;

-- 5) 비정규화 복제본 굳히기 (V49) — 반드시 <b>마지막</b>이다. 위 4)까지 price가 확정된 뒤에 판정해야 한다.
--
-- 왜 여기 있는가: 시드는 mysqldump INSERT라 컬럼을 명시 열거하고, exhibitions INSERT에는
-- region·is_free가 없다(313행 리터럴을 다시 뜨지 않는 한 넣을 수 없다). 그런데 시더는 ApplicationRunner라
-- <b>Flyway 이후</b>에 돈다 — 즉 완전히 새 DB에서는 V49 백필이 빈 테이블에 적용되고(0행), 그 뒤에 시드가
-- region=NULL·is_free=0인 313행을 넣는다. 그러면 지역·무료 필터가 <b>0건을 돌려주면서 스캔 시간은 그대로</b>
-- 나오는 가짜 상태가 된다(부하 측정에서는 가짜 개선으로 읽힌다 — loadtest/seed/amplify.sh가 증폭 단계에서
-- 막은 것과 같은 함정이 시드 단계에 남아 있었다).
--
-- 규칙은 V49__denormalize_exhibition_region_and_free.sql과 <b>같아야 한다</b>. 갈리면 시드로 만든 로컬 DB와
-- 실제 적재 경로가 다른 판정을 쓰게 된다. (도메인 코드 Exhibition.isFreePrice와의 일치는
-- ExhibitionFreeRuleTest가 실데이터 가격 57종으로 고정한다.)
UPDATE exhibitions e
JOIN exhibition_place p ON p.id = e.exhibition_place_id
SET e.region = p.region
WHERE e.region IS NULL;

UPDATE exhibitions e
JOIN exhibition_detail d ON d.exhibition_id = e.id
SET e.is_free = 1
WHERE d.price IS NOT NULL
  AND TRIM(d.price) <> ''
  AND NOT REGEXP_LIKE(d.price, '[1-9][0-9,]*[[:space:]]*원')
  AND (d.price LIKE '%무료%' OR REGEXP_LIKE(REGEXP_REPLACE(d.price, '[^0-9]', ''), '^0+$'));
