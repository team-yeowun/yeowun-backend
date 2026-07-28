-- 전시 검색 기록(최근 검색어). 회원 전용 — 비로그인은 기록하지 않는다.
--
-- 설계 결정:
--   · (user_id, keyword) UK — 같은 검색어를 다시 치면 새 행이 아니라 searched_at만 갱신해 위로 올라온다.
--     중복을 허용하면 "최근 10개"가 같은 단어로 채워져 목록이 무의미해진다.
--   · hard delete — 사용자가 "지웠다"고 기대하는 데이터이고 감사 대상이 아니다. soft delete면 UK 때문에
--     재검색 시 되살리는 분기가 필요해지는데, 그 복잡도를 살 이유가 없다(그래서 BaseEntity를 쓰지 않는다).
--   · user_id는 논리 참조(FK ❌ — 경계 넘는 참조는 ID로, 프로젝트 컨벤션).

create table search_history (
    id bigint not null auto_increment,
    user_id bigint not null,
    keyword varchar(100) not null,
    searched_at datetime(6) not null,
    primary key (id)
) engine=InnoDB;

-- 같은 사용자의 같은 검색어는 한 행(upsert 기준키).
create unique index uk_search_history_user_keyword on search_history (user_id, keyword);

-- 최근순 조회·초과분 정리가 모두 이 순서를 탄다.
create index idx_search_history_user_searched on search_history (user_id, searched_at desc);
