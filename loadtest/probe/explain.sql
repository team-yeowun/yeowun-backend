-- 측정 경로의 실행 계획. Hibernate가 만드는 SQL을 재현한 것이라 verbatim은 아니지만
-- 조건·정렬·서브쿼리 구조는 ExhibitionSpecifications와 같다(인덱스 선택을 보는 데 충분).
SET NAMES utf8mb4;
SELECT '=== S1 최신순 (필터 없음, ongoingOn=today) ===' AS ``;
EXPLAIN SELECT e.* FROM exhibitions e
 WHERE e.deleted_at IS NULL AND e.type='CATALOG'
   AND (e.start_date IS NULL OR e.start_date <= CURDATE())
   AND (e.end_date IS NULL OR e.end_date >= CURDATE())
 ORDER BY e.start_date DESC, e.id DESC LIMIT 21;

SELECT '=== S2 종료순 (end_date는 idx_dates의 2번째 컬럼) ===' AS ``;
EXPLAIN SELECT e.* FROM exhibitions e
 WHERE e.deleted_at IS NULL AND e.type='CATALOG'
   AND (e.start_date IS NULL OR e.start_date <= CURDATE())
   AND (e.end_date IS NULL OR e.end_date >= CURDATE())
 ORDER BY e.end_date ASC, e.id ASC LIMIT 21;

SELECT '=== S3 인기순 (idx_view_count 선두 일치 · 대조군) ===' AS ``;
EXPLAIN SELECT e.* FROM exhibitions e
 WHERE e.deleted_at IS NULL AND e.type='CATALOG'
   AND (e.start_date IS NULL OR e.start_date <= CURDATE())
   AND (e.end_date IS NULL OR e.end_date >= CURDATE())
 ORDER BY e.our_view_count DESC, e.id DESC LIMIT 21;

SELECT '=== X8 무료 × 종료순 (detail 서브쿼리 + filesort) ===' AS ``;
EXPLAIN SELECT e.* FROM exhibitions e
 WHERE e.deleted_at IS NULL AND e.type='CATALOG'
   AND (e.start_date IS NULL OR e.start_date <= CURDATE())
   AND (e.end_date IS NULL OR e.end_date >= CURDATE())
   AND e.id IN (SELECT d.exhibition_id FROM exhibition_detail d
                 WHERE LOWER(d.price) LIKE '%무료%' OR d.price='0원')
 ORDER BY e.end_date ASC, e.id ASC LIMIT 21;

SELECT '=== X8 count (홈이 매번 부르는 그 count) ===' AS ``;
EXPLAIN SELECT COUNT(e.id) FROM exhibitions e
 WHERE e.deleted_at IS NULL AND e.type='CATALOG'
   AND (e.start_date IS NULL OR e.start_date <= CURDATE())
   AND (e.end_date IS NULL OR e.end_date >= CURDATE())
   AND e.id IN (SELECT d.exhibition_id FROM exhibition_detail d
                 WHERE LOWER(d.price) LIKE '%무료%' OR d.price='0원');

SELECT '=== F1 지역=서울 (exhibition_place.region 인덱스 없음) ===' AS ``;
EXPLAIN SELECT e.* FROM exhibitions e
 WHERE e.deleted_at IS NULL AND e.type='CATALOG'
   AND (e.start_date IS NULL OR e.start_date <= CURDATE())
   AND (e.end_date IS NULL OR e.end_date >= CURDATE())
   AND e.exhibition_place_id IN (SELECT p.id FROM exhibition_place p WHERE p.region='SEOUL')
 ORDER BY e.start_date DESC, e.id DESC LIMIT 21;

SELECT '=== K1 검색 (like %k% + 전시장명 서브쿼리, notEndedOn) ===' AS ``;
EXPLAIN SELECT e.* FROM exhibitions e
 WHERE e.deleted_at IS NULL AND e.type='CATALOG'
   AND (e.end_date IS NULL OR e.end_date >= CURDATE())
   AND (LOWER(e.title) LIKE '%전시%'
        OR e.exhibition_place_id IN (SELECT p.id FROM exhibition_place p WHERE LOWER(p.name) LIKE '%전시%'))
 ORDER BY e.start_date DESC, e.id DESC LIMIT 21;

SELECT '=== D100 깊은 커서 (키셋 경계) ===' AS ``;
EXPLAIN SELECT e.* FROM exhibitions e
 WHERE e.deleted_at IS NULL AND e.type='CATALOG'
   AND (e.start_date IS NULL OR e.start_date <= CURDATE())
   AND (e.end_date IS NULL OR e.end_date >= CURDATE())
   AND (e.start_date < '2026-01-01' OR (e.start_date = '2026-01-01' AND e.id < 999999))
 ORDER BY e.start_date DESC, e.id DESC LIMIT 21;

SELECT '=== H4 배너 (진행 중 + 조회수 정렬 limit 3) ===' AS ``;
EXPLAIN SELECT e.* FROM exhibitions e
 WHERE e.type='CATALOG' AND e.deleted_at IS NULL
   AND e.start_date <= CURDATE() AND e.end_date >= CURDATE()
 ORDER BY e.our_view_count DESC LIMIT 3;

SELECT '=== ANALYZE 실측 (S2 · X8) ===' AS ``;
EXPLAIN ANALYZE SELECT e.id FROM exhibitions e
 WHERE e.deleted_at IS NULL AND e.type='CATALOG'
   AND (e.start_date IS NULL OR e.start_date <= CURDATE())
   AND (e.end_date IS NULL OR e.end_date >= CURDATE())
 ORDER BY e.end_date ASC, e.id ASC LIMIT 21;

EXPLAIN ANALYZE SELECT e.id FROM exhibitions e
 WHERE e.deleted_at IS NULL AND e.type='CATALOG'
   AND (e.start_date IS NULL OR e.start_date <= CURDATE())
   AND (e.end_date IS NULL OR e.end_date >= CURDATE())
   AND e.id IN (SELECT d.exhibition_id FROM exhibition_detail d
                 WHERE LOWER(d.price) LIKE '%무료%' OR d.price='0원')
 ORDER BY e.end_date ASC, e.id ASC LIMIT 21;
