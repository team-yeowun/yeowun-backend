-- V63(옛 조회수 인덱스 삭제) 되돌리기. Flyway 위치(db/migration) 밖에 두어 자동으로 실행되지 않는다.
-- V63 이전 앱(V62까지)도 이 인덱스를 쓰지 않으므로, 되돌릴 일은 V62까지 되돌릴 때(U62 전)뿐이다.
-- 순서: U63 → U62. mysql 클라이언트로 직접 실행한다.
alter table exhibitions
    add index idx_exhibitions_view_count (our_view_count),
    add index idx_exhibitions_region_views_id (region, our_view_count, id);

-- 기록을 지워야 다음 배포에서 V63이 다시 적용된다.
delete from flyway_schema_history where version = '63';
