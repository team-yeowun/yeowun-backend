-- 수집 파이프라인 객체 모델 개편(docs/ingestion-object-model.md, 결정 D1~D5) — 5부작 한 파일.
--
-- 1부. 진행 상태 리네임+슬림(D1·§5-2): exhibition_draft → exhibition_progress.
--      데이터 컬럼(목록분 13·상세분 6·장르분 3)은 전부 스냅샷 원장으로 이관되고, 여기는 마커만 남는다.
--      place_key(전시장 축 조인·게이트 재료)·place_outcome(대시보드 새/기존)이 신설된다.
-- 2부. 장르 원장 신설(genre_snapshot) — 스냅샷 패밀리를 "각 스텝의 데이터 원장"으로 확장. 기존 draft의
--      장르분을 백필해 데이터를 잃지 않는다.
-- 3부. 목록 원장에 detail_url 보강 — 원장 승격(§5-1)으로 어셈블이 목록분에서 복원해야 하는 필드.
-- 4부. 공용 감사(D3): external_api_call_log에 source(기능 축) 추가 — 기존 행은 전부 수집이 남긴 것이라
--      INGESTION으로 백필.
-- 5부. 슬림·폐기(D4·D5): ingestion_run 집계 축소, 영업시간 재검증 폐기로 PLACE_HOURS_STALE 행 삭제
--      (enum 값이 코드에서 사라져 남기면 @Enumerated(STRING) 로드가 깨진다).

-- ── 1부. 진행 상태 리네임 ────────────────────────────────────────────────────────
rename table exhibition_draft to exhibition_progress;

alter table exhibition_progress
    add column place_key varchar(500) null after status,
    add column place_outcome varchar(20) null after genre_classified_at;

-- place_key 백필 — PlaceKey.of와 같은 정규화(트림 + 연속 공백 1칸). 빈 값은 null(게이트 차단 유지).
update exhibition_progress
set place_key = nullif(trim(regexp_replace(place_name, '[[:space:]]+', ' ')), '')
where place_name is not null;

-- ── 2부. 장르 원장 신설 + 백필(draft 장르분을 잃지 않는다) ─────────────────────────
create table genre_snapshot (
    id bigint not null auto_increment,
    external_id varchar(100) not null,
    genre_keyword varchar(50) not null,
    genre_provider varchar(20) not null,
    genre_model varchar(100) null,
    classified_at datetime(6) not null,
    primary key (id)
) engine=InnoDB;

create unique index uk_genre_snapshot_external on genre_snapshot (external_id);

insert into genre_snapshot (external_id, genre_keyword, genre_provider, genre_model, classified_at)
select external_id, genre_keyword, genre_provider, genre_model, coalesce(genre_classified_at, updated_at)
from exhibition_progress
where genre_keyword is not null;

-- ── 1부 계속. 데이터 컬럼 삭제(원장 이관 완료 후) ─────────────────────────────────
alter table exhibition_progress
    drop column title,
    drop column place_name,
    drop column region,
    drop column sigungu,
    drop column gps_x,
    drop column gps_y,
    drop column start_date,
    drop column end_date,
    drop column category,
    drop column poster_url,
    drop column detail_url,
    drop column service_name,
    drop column realm_name,
    drop column price,
    drop column description,
    drop column img_url,
    drop column place_addr,
    drop column place_phone,
    drop column place_url,
    drop column genre_keyword,
    drop column genre_provider,
    drop column genre_model;

-- 전시장 축(PLACE_STAGED 소비)의 시드 해소·신규/기존 마크 조회 축.
create index idx_exhibition_progress_place_key on exhibition_progress (place_key);

-- ── 3부. 목록 원장 보강 ────────────────────────────────────────────────────────
alter table culture_list_snapshot add column detail_url varchar(1000) null;

-- ── 4부. 공용 감사(D3) — source 컬럼 ─────────────────────────────────────────────
alter table external_api_call_log add column source varchar(20) null after id;
update external_api_call_log set source = 'INGESTION' where source is null;
alter table external_api_call_log modify column source varchar(20) not null;

-- ── 5부. 슬림·폐기(D4·D5) ──────────────────────────────────────────────────────
alter table ingestion_run
    drop column total_count,
    drop column completed,
    drop column skipped,
    drop column deferred;

-- 재검증 이벤트 폐기(D4) — enum 값 제거로 남은 행이 있으면 엔티티 로드가 깨진다.
delete from exhibition_outbox where message_type = 'PLACE_HOURS_STALE';
