-- V61(인기 점수) 되돌리기. Flyway 위치(db/migration) 밖에 두어 자동으로 실행되지 않는다.
--
-- 순서:
--   1) 앱을 V61 이전 버전으로 먼저 되돌린다(새 앱은 popularity_score를 매핑하고 있어 컬럼이 없으면 기동하지 못한다).
--   2) DB에 어디까지 반영됐는지 본다(flyway_schema_history의 version 61 success, exhibitions의 컬럼과 인덱스).
--   3) 반영된 단계만 아래에서 골라 mysql 클라이언트로 직접 실행한다.
--
-- 되돌리면 V61 이후에 쌓인 북마크 수와 점수는 사라진다. 조회수(our_view_count)는 V61 전부터 있던 컬럼이라 남는다.

-- 인덱스부터 지운다. 생성 컬럼만 지우면 인덱스가 (id) 또는 (region, id)로 남는다.
alter table exhibitions
    drop index idx_exhibitions_popularity,
    drop index idx_exhibitions_region_popularity;

-- 두 수를 참조하는 생성 컬럼을 먼저 지운다(bookmark_count를 먼저 지우면 ERROR 3108).
alter table exhibitions drop column popularity_score;
alter table exhibitions drop column bookmark_count;

-- 기록을 지워야 다음 배포에서 V61이 다시 적용된다.
delete from flyway_schema_history where version = '61';
