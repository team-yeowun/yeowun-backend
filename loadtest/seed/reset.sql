-- 증폭분만 삭제하고 원본(313전시/229전시장)은 보존한다. 멱등.
-- ⚠ 100만에서 되돌리면 300만 행 삭제라 오래 걸린다 — 실험은 오름차순 증분으로 돌려 이 파일을 거의 쓰지 않는다.
SET NAMES utf8mb4;
SET SESSION foreign_key_checks = 0;

DELETE FROM exhibition_genre  WHERE id > (SELECT max_id FROM lt_base WHERE t='exhibition_genre');
DELETE FROM exhibition_detail WHERE id > (SELECT max_id FROM lt_base WHERE t='exhibition_detail');
DELETE FROM exhibitions       WHERE id > (SELECT max_id FROM lt_base WHERE t='exhibitions');
DELETE FROM exhibition_place  WHERE id > (SELECT max_id FROM lt_base WHERE t='exhibition_place');

SELECT (SELECT COUNT(*) FROM exhibitions) exhibitions,
       (SELECT COUNT(*) FROM exhibition_place) place,
       (SELECT COUNT(*) FROM exhibition_detail) detail,
       (SELECT COUNT(*) FROM exhibition_genre) genre;
