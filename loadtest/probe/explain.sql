-- 측정 경로의 실행 계획. Hibernate가 만드는 SQL을 재현한 것이라 verbatim은 아니지만
-- 조건·정렬·서브쿼리·인덱스 힌트 구조는 앱과 같다(인덱스 선택을 보는 데 충분).
--
-- ⚠ 앱과 맞춰야 하는 두 가지 — 어긋나면 "앱이 더 이상 발행하지 않는 쿼리"를 계측하게 된다:
--   1) 술어(V49): 지역은 exhibition_place 서브쿼리가 아니라 exhibitions.region,
--      무료는 exhibition_detail LIKE '%무료%'가 아니라 exhibitions.is_free.
--   2) 목록의 SELECT 목록과 인덱스 힌트: 앱은 <b>전 컬럼</b>을 읽고, 힌트는
--      <b>지역이 정확히 1개일 때만</b> 붙인다(V51 복합 인덱스 — ExhibitionQueryRepositoryImpl).
--      비지역·다중지역 목록에는 힌트가 <b>없다</b>(1M에서 붙일 이유가 측정되지 않았고, 다중지역은 해롭다).
--      · SELECT 목록을 e.id로 좁히면 계획이 달라진다 — 1M 실측 0.283ms → 6,824ms(24,100배).
--        정렬 인덱스가 "커버링"이 되면서 전 인덱스 주사로 전환한다. 앱 계획을 보려면 반드시 e.*를 쓸 것.
--      · 지역 목록이 지목하는 인덱스(정렬축별):
--          최신순·거리순 -> idx_exhibitions_region_start_id
--          종료순        -> idx_exhibitions_region_end_id
--          인기순        -> idx_exhibitions_region_views_id
-- 별칭은 e로 쓴다(앱은 e1_0). MySQL 테이블 힌트는 그 쿼리 자신의 별칭을 보므로 결과는 같다.
SET NAMES utf8mb4;

SELECT '=== S1 최신순 (필터 없음, ongoingOn=today) ===' AS ``;
EXPLAIN SELECT e.* FROM exhibitions e
 WHERE e.deleted_at IS NULL AND e.type='CATALOG'
   AND e.start_date <= CURDATE()
   AND e.end_date >= CURDATE()
 ORDER BY e.start_date DESC, e.id DESC LIMIT 21;

SELECT '=== S2 종료순 ===' AS ``;
EXPLAIN SELECT e.* FROM exhibitions e
 WHERE e.deleted_at IS NULL AND e.type='CATALOG'
   AND e.start_date <= CURDATE()
   AND e.end_date >= CURDATE()
 ORDER BY e.end_date ASC, e.id ASC LIMIT 21;

SELECT '=== S2-대조군 종료순, 힌트 없음 (V50 커버링 인덱스가 계획을 흔드는지) ===' AS ``;
EXPLAIN SELECT e.* FROM exhibitions e
 WHERE e.deleted_at IS NULL AND e.type='CATALOG'
   AND e.start_date <= CURDATE()
   AND e.end_date >= CURDATE()
 ORDER BY e.end_date ASC, e.id ASC LIMIT 21;

SELECT '=== S3 인기순 (idx_view_count 선두 일치) ===' AS ``;
EXPLAIN SELECT e.* FROM exhibitions e
 WHERE e.deleted_at IS NULL AND e.type='CATALOG'
   AND e.start_date <= CURDATE()
   AND e.end_date >= CURDATE()
 ORDER BY e.our_view_count DESC, e.id DESC LIMIT 21;

SELECT '=== X8 무료 × 종료순 (V49: is_free 컬럼) ===' AS ``;
EXPLAIN SELECT e.* FROM exhibitions e
 WHERE e.deleted_at IS NULL AND e.type='CATALOG'
   AND e.start_date <= CURDATE()
   AND e.end_date >= CURDATE()
   AND e.is_free = 1
 ORDER BY e.end_date ASC, e.id ASC LIMIT 21;

-- ── 지역 목록: 여기가 STEP 5c의 핵심이다 ────────────────────────────────────────
-- V49가 region을 exhibitions로 옮긴 뒤 region은 정렬 인덱스 스캔의 "잔여 필터"가 됐다. 비용은 지역 크기가
-- 아니라 <b>"오늘로부터 그 지역의 최근 진행 전시까지의 날짜 간격"</b>에 비례한다(진행 중 0건이면 상한이 없다).
-- 그래서 서울(간격 0일)은 빨랐고 제주(14일)·충남(진행중 0건)은 느렸다 — 선택도로는 설명되지 않는다
-- (전북과 제주는 비중이 똑같이 2.88%인데 1M에서 걸은 행 수가 254 vs 64,026이었다).
-- V51 복합 인덱스가 region을 등치 선두로 두어 인덱스 엔트리 21개로 끝낸다. 아래는 전부 region_* 여야 한다.

SELECT '=== F1 지역=서울 × 최신순 (간격 0일) ===' AS ``;
EXPLAIN SELECT e.* FROM exhibitions e USE INDEX (idx_exhibitions_region_start_id)
 WHERE e.deleted_at IS NULL AND e.type='CATALOG'
   AND e.start_date <= CURDATE()
   AND e.end_date >= CURDATE()
   AND e.region IN ('SEOUL')
 ORDER BY e.start_date DESC, e.id DESC LIMIT 21;

SELECT '=== F2 지역=제주 × 최신순 (간격 14일 — STEP 5에서 후퇴했던 그 경로) ===' AS ``;
EXPLAIN ANALYZE SELECT e.* FROM exhibitions e USE INDEX (idx_exhibitions_region_start_id)
 WHERE e.deleted_at IS NULL AND e.type='CATALOG'
   AND e.start_date <= CURDATE()
   AND e.end_date >= CURDATE()
   AND e.region IN ('JEJU')
 ORDER BY e.start_date DESC, e.id DESC LIMIT 21;

SELECT '=== F2-대조군 제주 · 힌트 없음 (옵티마이저는 V51 인덱스를 스스로 못 고른다) ===' AS ``;
-- 1M 실측: 힌트가 없으면 정렬 인덱스를 골라 64,026행을 걷는다(807ms). 지목이 필요한 근거가 이 줄이다.
-- 이 대조군이 언젠가 region_* 로 바뀌면 힌트를 지울 수 있다.
EXPLAIN ANALYZE SELECT e.* FROM exhibitions e
 WHERE e.deleted_at IS NULL AND e.type='CATALOG'
   AND e.start_date <= CURDATE()
   AND e.end_date >= CURDATE()
   AND e.region IN ('JEJU')
 ORDER BY e.start_date DESC, e.id DESC LIMIT 21;

SELECT '=== F2-최악 지역=충남 × 인기순 (진행 중 0건 — 상한 없는 걷기의 최악) ===' AS ``;
-- 힌트 없이 our_view_count 인덱스를 걸으면 1M에서 50,505ms였다. V51 인덱스는 상한이 지역 크기다.
EXPLAIN ANALYZE SELECT e.* FROM exhibitions e USE INDEX (idx_exhibitions_region_views_id)
 WHERE e.deleted_at IS NULL AND e.type='CATALOG'
   AND e.start_date <= CURDATE()
   AND e.end_date >= CURDATE()
   AND e.region IN ('CHUNGNAM')
 ORDER BY e.our_view_count DESC, e.id DESC LIMIT 21;

SELECT '=== F-다중지역 서울,경기 · 힌트 없음 (앱도 안 붙인다 — 붙이면 1,339ms) ===' AS ``;
EXPLAIN SELECT e.* FROM exhibitions e
 WHERE e.deleted_at IS NULL AND e.type='CATALOG'
   AND e.start_date <= CURDATE()
   AND e.end_date >= CURDATE()
   AND e.region IN ('SEOUL','GYEONGGI')
 ORDER BY e.start_date DESC, e.id DESC LIMIT 21;

SELECT '=== K1 검색 (like %k% + 전시장명 서브쿼리, notEndedOn) ===' AS ``;
EXPLAIN SELECT e.* FROM exhibitions e
 WHERE e.deleted_at IS NULL AND e.type='CATALOG'
   AND e.end_date >= CURDATE()
   AND (LOWER(e.title) LIKE '%전시%'
        OR e.exhibition_place_id IN (SELECT p.id FROM exhibition_place p WHERE LOWER(p.name) LIKE '%전시%'))
 ORDER BY e.start_date DESC, e.id DESC LIMIT 21;

SELECT '=== D100 깊은 커서 (키셋 경계) ===' AS ``;
EXPLAIN SELECT e.* FROM exhibitions e
 WHERE e.deleted_at IS NULL AND e.type='CATALOG'
   AND e.start_date <= CURDATE()
   AND e.end_date >= CURDATE()
   AND (e.start_date < '2026-01-01' OR (e.start_date = '2026-01-01' AND e.id < 999999))
 ORDER BY e.start_date DESC, e.id DESC LIMIT 21;

SELECT '=== H4 배너 (진행 중 + 조회수 정렬 limit 3, 센티널 제외) ===' AS ``;
-- 배너는 Spring Data @Query라 힌트를 걸 통로가 없다.
-- 커버링 인덱스가 이 계획을 흔드는지 여기서 본다(500k에서는 흔들지 않았다: 0.51ms, view_count 유지).
EXPLAIN SELECT e.* FROM exhibitions e
 WHERE e.type='CATALOG' AND e.deleted_at IS NULL
   AND e.start_date <= CURDATE() AND e.end_date >= CURDATE()
   AND e.start_date <> '1000-01-01' AND e.end_date <> '9999-12-31'
 ORDER BY e.our_view_count DESC LIMIT 3;

SELECT '=== C0 무필터 count (V50 커버링 인덱스를 타야 한다: Using index) ===' AS ``;
EXPLAIN SELECT COUNT(e.id) FROM exhibitions e
 WHERE e.deleted_at IS NULL AND e.type='CATALOG'
   AND e.start_date <= CURDATE() AND e.end_date >= CURDATE();

SELECT '=== C2 지역 count ===' AS ``;
EXPLAIN SELECT COUNT(e.id) FROM exhibitions e
 WHERE e.deleted_at IS NULL AND e.type='CATALOG'
   AND e.start_date <= CURDATE() AND e.end_date >= CURDATE()
   AND e.region IN ('SEOUL');

SELECT '=== C1 무료 count ===' AS ``;
EXPLAIN SELECT COUNT(e.id) FROM exhibitions e
 WHERE e.deleted_at IS NULL AND e.type='CATALOG'
   AND e.start_date <= CURDATE() AND e.end_date >= CURDATE()
   AND e.is_free = 1;

SELECT '=== C4 키워드 count (커버링 인덱스가 못 돕는 경로 — 선행 와일드카드) ===' AS ``;
EXPLAIN SELECT COUNT(e.id) FROM exhibitions e
 WHERE e.deleted_at IS NULL AND e.type='CATALOG'
   AND e.end_date >= CURDATE()
   AND (LOWER(e.title) LIKE '%전시%'
        OR e.exhibition_place_id IN (SELECT p.id FROM exhibition_place p WHERE LOWER(p.name) LIKE '%전시%'));

SELECT '=== ANALYZE 실측 (S2 좁은 투영 vs 전 컬럼 · C0) ===' AS ``;
-- 좁은 투영 함정의 상시 감시. 아래 두 EXPLAIN ANALYZE는 <b>같은 술어·같은 정렬</b>인데 SELECT 목록만 다르다.
-- 1M 실측 e.id 6,824ms vs e.* 0.283ms(24,100배) — 정렬 인덱스가 "커버링"이 되면 전 인덱스를 주사한다.
-- e.id 쪽이 훨씬 느린 것이 <b>정상</b>이고, 앱은 반드시 e.* 모양이어야 한다. 이 대비가 뒤집히면 투영이 좁아진 것이다.
EXPLAIN ANALYZE SELECT e.id FROM exhibitions e
 WHERE e.deleted_at IS NULL AND e.type='CATALOG'
   AND e.start_date <= CURDATE()
   AND e.end_date >= CURDATE()
 ORDER BY e.end_date ASC, e.id ASC LIMIT 21;

EXPLAIN ANALYZE SELECT e.* FROM exhibitions e
 WHERE e.deleted_at IS NULL AND e.type='CATALOG'
   AND e.start_date <= CURDATE()
   AND e.end_date >= CURDATE()
 ORDER BY e.end_date ASC, e.id ASC LIMIT 21;

EXPLAIN ANALYZE SELECT COUNT(e.id) FROM exhibitions e
 WHERE e.deleted_at IS NULL AND e.type='CATALOG'
   AND e.start_date <= CURDATE() AND e.end_date >= CURDATE();

SELECT '=== 비정규화 복제본 위생 검사 (0건 매칭 함정 방지) ===' AS ``;
-- region이 대부분 NULL이거나 is_free 합이 0이면 그 런의 지역·무료 숫자는 전부 무효다
-- (필터가 0건인데 스캔 시간은 그대로 나온다 — charset 함정과 같은 종류).
SELECT COUNT(*) AS rows_total,
       SUM(region IS NOT NULL) AS region_filled,
       SUM(is_free) AS is_free_rows
  FROM exhibitions;

SELECT '=== 인덱스 위생 검사 (V48·V50·V51이 실제로 있는지) ===' AS ``;
-- 지목한 이름이 없으면 MySQL이 ERROR 1176으로 죽는다. 이름이 다르면 위 계획 전부가 무의미하다.
SELECT index_name, GROUP_CONCAT(column_name ORDER BY seq_in_index) AS cols
  FROM information_schema.statistics
 WHERE table_schema = DATABASE() AND table_name = 'exhibitions'
 GROUP BY index_name ORDER BY index_name;
