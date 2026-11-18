-- 인기순을 조회수만이 아니라 인기 점수(북마크 수 × 0.7 + 조회수 × 0.3)로 세운다(2026-11-18, exhibition-list-lab 실험 반영).
--
-- 두 수는 전시 행에 두고, 앱이 원장 쓰기와 같은 트랜잭션에서 DB 안에서 바로 더한다(count = count + 1).
--   our_view_count: 상세 조회마다 +1(예전엔 Redis에 모았다가 6시간마다 반영했는데, 그 누산기를 걷어냈다).
--   bookmark_count: 북마크 행이 실제로 등록/해제될 때만 ±1. 원장은 exhibition_bookmarks라 언제든 다시 셀 수 있다.
--   popularity_score: 앱이 쓰지 않는다. MySQL이 두 수에서 계산하는 생성 컬럼이라 수와 어긋날 수 없다.
--
-- VIRTUAL로 둔 이유: STORED 생성 컬럼을 더하면 테이블을 통째로 복사하고(ALGORITHM=COPY) 그동안 전시 쓰기가 기다린다.
-- VIRTUAL은 메타데이터만 바꿔 바로 끝나고(INSTANT), 값은 인덱스에 저장되므로 인기순 읽기 계획은 같다(랩에서 확인).
alter table exhibitions
    add column bookmark_count   bigint         not null default 0,
    add column popularity_score decimal(22, 1) as (bookmark_count * 0.7 + our_view_count * 0.3) virtual not null;

-- 기존 북마크를 한 번 세어 채운다. 조회수(our_view_count)는 이미 행에 있다.
update exhibitions e
  join (select exhibition_id, count(*) as c
          from exhibition_bookmarks
         where deleted_at is null
         group by exhibition_id) b on b.exhibition_id = e.id
   set e.bookmark_count = b.c;

-- 인기순은 popularity_score DESC, id DESC로만 읽는다. 인덱스도 같은 방향으로 만들어 역순 스캔 없이 정순으로 읽다가
-- LIMIT에서 멈춘다. id는 동점 타이브레이커이자 커서 경계다. 지역 1개 목록은 V51처럼 지역을 앞에 둔 인덱스를 힌트로 지목한다.
-- 한 ALTER에 담아 둘 중 하나만 생기는 상태가 없게 한다(온라인으로 만들어진다: INPLACE, LOCK=NONE).
--
-- 조회수만 보던 옛 인덱스(idx_exhibitions_view_count, idx_exhibitions_region_views_id)는 여기서 지우지 않는다.
-- 롤링 배포 중에는 아직 옛 앱이 한 대 떠 있고, 그 앱은 지역 1개 인기순에 idx_exhibitions_region_views_id를
-- USE INDEX로 지목한다. 이름이 없으면 ERROR 1176으로 실패하므로, 이 배포가 끝난 뒤의 마이그레이션에서 지운다.
alter table exhibitions
    add index idx_exhibitions_popularity (popularity_score desc, id desc),
    add index idx_exhibitions_region_popularity (region, popularity_score desc, id desc);
