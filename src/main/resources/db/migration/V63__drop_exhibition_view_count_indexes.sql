-- 조회수만 보던 옛 인기순 인덱스 두 개를 지운다(V62의 후속).
--
-- 인기순은 V62부터 인기 점수 인덱스(idx_exhibitions_popularity, idx_exhibitions_region_popularity)로 읽는다.
-- 옛 인덱스는 읽는 쿼리가 없는데, 상세 조회마다 our_view_count가 +1 되면서 쓰기마다 두 인덱스도 함께 갱신됐다.
-- V62에서 지우지 않은 이유: 롤링 배포 중 옛 앱이 지역 1개 인기순에 idx_exhibitions_region_views_id를
-- USE INDEX로 지목해서, 없으면 ERROR 1176이었다. V62 배포가 끝나 그 앱이 사라졌으므로 이제 지운다.
-- 지금 떠 있는 앱(V62)은 이 두 이름을 지목하지 않는다.
--
-- 한 ALTER에 담아 둘 중 하나만 지워진 상태가 없게 한다(인덱스 삭제는 메타데이터 변경이라 바로 끝난다).
-- 되돌리기: db/rollback/U63__drop_exhibition_view_count_indexes.sql
alter table exhibitions
    drop index idx_exhibitions_view_count,
    drop index idx_exhibitions_region_views_id;
