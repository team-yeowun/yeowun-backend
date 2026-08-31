-- 재설계(docs/전시수집_파이프라인_v2문서/AI-Driven-Development/docs/)에 맞춰 V52·V53을 고친다.
--
-- 원장 4종은 데이터를 보존한다(유료 API로 사 온 값이라 다시 만들 수 없다).
-- 진행 상태 3층과 아웃박스의 미처리 행은 재설계에서 읽을 곳이 없어 버린다.
--
-- 적용 순서: 파이프라인 정지(app.ingestion.v2.enabled=false) → 미처리 아웃박스 0 확인 → 이 마이그레이션 → 새 코드 배포 → 재기동.
-- 순서를 지켜야 하는 이유: 아웃박스를 먼저 비우지 않으면 옛 행이 새 컬럼 제약(vendor_key not null)에 걸리고,
-- 진행 상태 3층을 먼저 지우면 아웃박스의 job_execution_id가 가리키던 대상이 사라진 채로 남는다.

-- ── 1. 아웃박스를 비우고 새 모양으로 ──────────────────────────────────────────
-- 옛 행은 job_execution_id로 대상을 가리키는데 그 테이블이 사라지므로 살릴 수 없다.
-- 옛 이벤트 어휘(STEP_READY·DRAFT_READY·STAGED_DONE)가 새 어휘 일곱 종과 일대일로 대응하지도 않는다.
delete from ingestion_outbox;

-- 처리 시각 기준 정리 인덱스는 컬럼과 함께 사라지므로 먼저 명시적으로 지운다
-- (다중 컬럼 인덱스는 컬럼만 빠지고 (status) 단독 인덱스로 남아 idx_..._status_id와 겹친다).
drop index idx_ingestion_outbox_cleanup on ingestion_outbox;

alter table ingestion_outbox
    drop column job_execution_id,
    drop column step,
    drop column error_summary,
    drop column processed_at,
    drop column updated_at,
    add column vendor_key varchar(100) not null after event_type,
    add column sent_at datetime(6) null,
    add column last_error varchar(500) null;

-- 발송 선점 조회. status = 'PENDING' ORDER BY id LIMIT ? FOR UPDATE SKIP LOCKED
-- 정리 배치의 status = 'CONFIRMED' AND created_at < ? ORDER BY id 도 이 인덱스를 탄다(적재 순서 = id 순서).
alter table ingestion_outbox rename index idx_ingestion_outbox_claim to idx_ingestion_outbox_status_id;

-- 정합성 배치(재발송) 선점. status = 'SENT' AND sent_at < ? ORDER BY sent_at
create index idx_ingestion_outbox_status_sent_at on ingestion_outbox (status, sent_at);

-- 종결 확인. 같은 사실의 SENT 행 중 가장 오래된 하나.
create index idx_ingestion_outbox_type_key_status on ingestion_outbox (event_type, vendor_key, status);

-- ── 2. 진행 상태 3층 폐기 ─────────────────────────────────────────────────────
-- 진행 상태는 이제 각 도메인의 애그리거트가 소유한다.
drop table ingestion_step_execution;
drop table ingestion_job_execution;
drop table ingestion_job_instance;

-- ── 3. 원장 4종 ───────────────────────────────────────────────────────────────
-- job_execution_id는 가리키던 테이블이 사라졌으므로 뗀다. V53이 옮겨 둔 vendor_key 유일 키는 그대로 쓴다.
-- (컬럼을 지우면 그 컬럼만 담은 idx_ingestion_*_snapshot_job 인덱스도 함께 사라진다.)

-- 목록 원장: 관측 시각 어휘를 observed_at으로 맞춘다(첫 관측만 남는 테이블이라 created_at보다 정확하다).
alter table ingestion_culture_list_snapshot
    drop column job_execution_id,
    change column created_at observed_at datetime(6) not null;
alter table ingestion_culture_list_snapshot
    rename index uk_ingestion_list_snapshot_vendor to uk_ingestion_culture_list_snapshot_vendor_key;

alter table ingestion_culture_detail_snapshot drop column job_execution_id;
alter table ingestion_culture_detail_snapshot
    rename index uk_ingestion_detail_snapshot_vendor to uk_ingestion_culture_detail_snapshot_vendor_key;

-- 장르 원장은 컬럼 이름을 재설계 어휘로 바꾼다.
-- vendor는 "성공한 벤더"이고, 하위 진행의 last_attempt_vendor와 짝을 이룬다.
alter table ingestion_genre_snapshot
    drop column job_execution_id,
    change column genre_provider vendor varchar(20) not null,
    change column genre_model model varchar(100) null;
alter table ingestion_genre_snapshot
    rename index uk_ingestion_genre_snapshot_vendor to uk_ingestion_genre_snapshot_vendor_key;
-- 공급자별 분류 품질·폴백 추이 조회.
create index idx_ingestion_genre_snapshot_vendor_created on ingestion_genre_snapshot (vendor, created_at);

-- 구글 원장에도 벤더를 남긴다. 지금은 값이 하나뿐이지만 다른 지도 서비스로 바꿀 여지를 둔다.
alter table ingestion_google_place_snapshot
    drop column job_execution_id,
    add column vendor varchar(20) not null default 'GOOGLE' after vendor_key;
alter table ingestion_google_place_snapshot
    rename index uk_ingestion_google_snapshot_vendor to uk_ingestion_google_place_snapshot_vendor_key;

-- ── 4. 문자셋과 콜레이션 통일 ─────────────────────────────────────────────────
-- 점검·스테이징이 원장 3종을 vendor_key로 조인하므로 콜레이션이 갈리면 조인이 실행되지 않는다.
alter table ingestion_outbox convert to character set utf8mb4 collate utf8mb4_0900_ai_ci;
alter table ingestion_culture_list_snapshot convert to character set utf8mb4 collate utf8mb4_0900_ai_ci;
alter table ingestion_culture_detail_snapshot convert to character set utf8mb4 collate utf8mb4_0900_ai_ci;
alter table ingestion_genre_snapshot convert to character set utf8mb4 collate utf8mb4_0900_ai_ci;
alter table ingestion_google_place_snapshot convert to character set utf8mb4 collate utf8mb4_0900_ai_ci;

-- ── 5. 재설계가 새로 쓰는 테이블 ──────────────────────────────────────────────

-- 5-1. 수집(collect) ---------------------------------------------------------

-- 회차 선점 마크. 행 1개 = 이 회차를 어느 인스턴스가 선점했다는 사실.
-- 상태 컬럼 없음(선점 여부는 행의 존재로 충분).
create table ingestion_collect_batch_mark (
    id bigint not null auto_increment,
    batch_date date not null,
    claimed_at datetime(6) not null,
    primary key (id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci;

-- 회차당 1회 실행의 물리적 근거. 분산 락 인프라를 대신한다.
create unique index uk_ingestion_collect_batch_mark_batch_date on ingestion_collect_batch_mark (batch_date);

-- 수집 애그리거트. 행 1개 = 전시 1건이 이 회차의 처리 대상으로 확정된 사실.
-- 클래스는 CollectedExhibition(Collection은 java.util과 단순 이름 충돌).
create table ingestion_collection (
    id bigint not null auto_increment,
    vendor_key varchar(100) not null,
    batch_date date not null,
    status varchar(20) not null,
    collected_at datetime(6) not null,
    primary key (id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci;

-- 재실행 멱등의 최종 가드. 애플리케이션 조회가 놓쳐도 여기서 막힌다.
create unique index uk_ingestion_collection_vendor_key on ingestion_collection (vendor_key);
-- 회차별 확정 건수 집계와 "이 회차에 태운 전시" 조회가 이 순서를 탄다.
create index idx_ingestion_collection_batch on ingestion_collection (batch_date, id);

-- 5-2. 보강(enrich) ----------------------------------------------------------

create table ingestion_enrichment_detail (
    id bigint not null auto_increment,
    vendor_key varchar(100) not null,
    status varchar(20) not null,
    attempts int not null default 0,
    last_attempt_vendor varchar(50) null,
    last_error varchar(500) null,
    completed_at datetime(6) null,
    primary key (id),
    unique key uk_ingestion_enrichment_detail_vendor_key (vendor_key),
    key idx_ingestion_enrichment_detail_status_completed (status, completed_at)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci;

create table ingestion_enrichment_genre (
    id bigint not null auto_increment,
    vendor_key varchar(100) not null,
    status varchar(20) not null,
    attempts int not null default 0,
    last_attempt_vendor varchar(50) null,
    last_error varchar(500) null,
    fallback_used tinyint(1) not null default 0,
    completed_at datetime(6) null,
    primary key (id),
    unique key uk_ingestion_enrichment_genre_vendor_key (vendor_key),
    key idx_ingestion_enrichment_genre_status_completed (status, completed_at),
    key idx_ingestion_enrichment_genre_fallback (fallback_used, status)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci;

create table ingestion_enrichment_hours (
    id bigint not null auto_increment,
    vendor_key varchar(100) not null,
    status varchar(20) not null,
    attempts int not null default 0,
    last_attempt_vendor varchar(50) null,
    last_error varchar(500) null,
    completed_at datetime(6) null,
    primary key (id),
    unique key uk_ingestion_enrichment_hours_vendor_key (vendor_key),
    key idx_ingestion_enrichment_hours_status_completed (status, completed_at)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci;

-- 보강 애그리거트 루트. 하위 셋을 조인 컬럼으로 소유한다(합성 관계라 UNIQUE로 1대1을 못박는다).
create table ingestion_enrichment (
    id bigint not null auto_increment,
    vendor_key varchar(100) not null,
    status varchar(20) not null,
    detail_id bigint not null,
    genre_id bigint not null,
    hours_id bigint not null,
    created_at datetime(6) not null,
    completed_at datetime(6) null,
    primary key (id),
    unique key uk_ingestion_enrichment_vendor_key (vendor_key),
    unique key uk_ingestion_enrichment_detail_id (detail_id),
    unique key uk_ingestion_enrichment_genre_id (genre_id),
    unique key uk_ingestion_enrichment_hours_id (hours_id),
    key idx_ingestion_enrichment_status (status)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci;

-- 5-3. 점검(inspect) ---------------------------------------------------------

-- 점검 애그리거트. 마지막 결론 1행만 보유(사유는 enum 이름을 콤마로 이은 표시값).
create table ingestion_inspection (
    id bigint not null auto_increment,
    vendor_key varchar(100) not null,
    status varchar(20) not null,
    reject_reasons varchar(300) null,
    notes varchar(300) null,
    inspected_at datetime(6) not null,
    primary key (id),
    unique key uk_ingestion_inspection_vendor_key (vendor_key),
    key idx_ingestion_inspection_status_inspected_at (status, inspected_at)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci;

-- 5-4. 스테이징(stage) -------------------------------------------------------

create table ingestion_staging (
    id bigint not null auto_increment,
    vendor_key varchar(100) not null,
    status varchar(20) not null,
    staged_exhibition_id bigint null,
    attempts int not null default 0,
    last_error varchar(500) null,
    created_at datetime(6) not null,
    updated_at datetime(6) not null,
    staged_at datetime(6) null,
    primary key (id),
    unique key uk_ingestion_staging_vendor_key (vendor_key),
    key idx_ingestion_staging_status_updated_at (status, updated_at)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci;

-- 5-5. 공용 배달 계층(common) -------------------------------------------------

-- 반복 실패로 정상 흐름에서 걷어낸 항목. 행 1개 = 격리 1건.
-- 시도 횟수 컬럼 없음(그 숫자는 도메인 애그리거트가 소유한다).
create table ingestion_dead_letter (
    id bigint not null auto_increment,
    event_type varchar(30) null,
    vendor_key varchar(100) null,
    stream_key varchar(64) not null,
    record_id varchar(64) not null,
    last_error varchar(1000) null,
    created_at datetime(6) not null,
    redriven_at datetime(6) null,
    primary key (id),
    key idx_ingestion_dead_letter_redriven_at_id (redriven_at, id),
    key idx_ingestion_dead_letter_created_at (created_at),
    key idx_ingestion_dead_letter_vendor_key (vendor_key)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci;
