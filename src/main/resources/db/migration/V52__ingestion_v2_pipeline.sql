-- 수집 파이프라인 V2 슬라이스 테이블 (docs/전시수집_파이프라인_v2문서/AI-Driven-Development/04-ERD.md).
--
-- 구성: 진행 상태 3층 미러(instance·execution·step) + 아웃박스 + 원장 4종.
-- V1(exhibition_progress·exhibition_outbox·culture_*_snapshot)은 그대로 두고 병행 운영한다 —
-- 그래서 V2 원장도 ingestion_ 프리픽스로 갈라 같은 테이블에 두 스키마가 겹치지 않게 한다.
-- BATCH_ 프리픽스는 Spring Batch 실물 테이블과 충돌하므로 쓰지 않는다.
--
-- 진실의 위치: 데이터 완비 = 원장 행 존재 / 실행 사실·병렬 진행 = step_execution / 요약 = job_execution.
-- FK 제약은 두지 않는다(논리 참조만) — 참조 무결성은 애플리케이션이 검증한다.

-- ── 1. 진행 상태 3층 ──────────────────────────────────────────────────────────
-- 멱등 앵커: 상태 컬럼 없는 불변 행. UNIQUE가 같은 배치의 중복 투입을 물리적으로 막는다.
create table ingestion_job_instance (
    id bigint not null auto_increment,
    job_name varchar(100) not null,
    batch_date date not null,
    vendor_key varchar(100) not null,
    created_at datetime(6) not null,
    primary key (id)
) engine=InnoDB;

create unique index uk_ingestion_job_instance on ingestion_job_instance (job_name, batch_date, vendor_key);

-- 요약(페이즈)의 소유자. current_step은 진행 표시가 아니라 FAILED 시 실패 지점 전용이다.
create table ingestion_job_execution (
    id bigint not null auto_increment,
    job_instance_id bigint not null,
    status varchar(20) not null,
    current_step varchar(20) null,
    failure_code varchar(200) null,
    staged_exhibition_id bigint null,
    created_at datetime(6) not null,
    updated_at datetime(6) not null,
    primary key (id)
) engine=InnoDB;

create index idx_ingestion_job_execution_status on ingestion_job_execution (status);
create index idx_ingestion_job_execution_instance on ingestion_job_execution (job_instance_id);

-- 시도 1회 = 행 1개. 병렬 진행의 진실이자 fan-in 판정(완료 집합)의 조회 근거.
create table ingestion_step_execution (
    id bigint not null auto_increment,
    job_execution_id bigint not null,
    step varchar(20) not null,
    execution_no int not null,
    status varchar(20) not null,
    error_summary varchar(500) null,
    step_context json null,
    started_at datetime(6) not null,
    completed_at datetime(6) null,
    primary key (id)
) engine=InnoDB;

create unique index uk_ingestion_step_execution on ingestion_step_execution (job_execution_id, step, execution_no);
-- fan-in 완료 집합 조회용 커버링 인덱스 — 완료 tx당 1회 도는 쿼리.
create index idx_ingestion_step_execution_fanin on ingestion_step_execution (job_execution_id, status, step);

-- ── 2. 아웃박스 ───────────────────────────────────────────────────────────────
-- 발행자의 사실. payload 없음(값은 원장) · attempts가 재시도 판정의 유일한 곳.
create table ingestion_outbox (
    id bigint not null auto_increment,
    job_execution_id bigint not null,
    event_type varchar(30) not null,
    step varchar(20) null,
    status varchar(30) not null,
    attempts int not null default 0,
    error_summary varchar(500) null,
    created_at datetime(6) not null,
    updated_at datetime(6) not null,
    processed_at datetime(6) null,
    primary key (id)
) engine=InnoDB;

-- 클레임(FOR UPDATE SKIP LOCKED) 스캔 · 폴링 재스캔용.
create index idx_ingestion_outbox_claim on ingestion_outbox (status, id);
create index idx_ingestion_outbox_job on ingestion_outbox (job_execution_id);
-- 주간 정리 배치(PROCESSED 경과분) 조회용.
create index idx_ingestion_outbox_cleanup on ingestion_outbox (status, processed_at);

-- ── 3. 원장 4종 ───────────────────────────────────────────────────────────────
-- 벤더 응답 구조 필드 verbatim. 정제(타입 변환·평문 추출)는 어셈블 몫이라 전부 문자열로 담는다.
create table ingestion_culture_list_snapshot (
    id bigint not null auto_increment,
    job_execution_id bigint not null,
    vendor_key varchar(100) not null,
    title varchar(500) null,
    start_date varchar(20) null,
    end_date varchar(20) null,
    place varchar(300) null,
    realm_name varchar(100) null,
    area varchar(100) null,
    sigungu varchar(100) null,
    thumbnail varchar(1000) null,
    gps_x varchar(50) null,
    gps_y varchar(50) null,
    service_name varchar(200) null,
    detail_url varchar(1000) null,
    created_at datetime(6) not null,
    primary key (id)
) engine=InnoDB;

create unique index uk_ingestion_list_snapshot_job on ingestion_culture_list_snapshot (job_execution_id);

create table ingestion_culture_detail_snapshot (
    id bigint not null auto_increment,
    job_execution_id bigint not null,
    vendor_key varchar(100) not null,
    title varchar(500) null,
    start_date varchar(20) null,
    end_date varchar(20) null,
    place varchar(300) null,
    realm_name varchar(100) null,
    area varchar(100) null,
    sigungu varchar(100) null,
    gps_x varchar(50) null,
    gps_y varchar(50) null,
    price text null,
    contents mediumtext null,
    url varchar(1000) null,
    phone varchar(100) null,
    img_url varchar(1000) null,
    absent tinyint(1) not null default 0,
    created_at datetime(6) not null,
    primary key (id)
) engine=InnoDB;

create unique index uk_ingestion_detail_snapshot_job on ingestion_culture_detail_snapshot (job_execution_id);

-- 유일하게 정제 결과를 담는 원장 — 폴백이 일어났으면 provider가 그 사실을 남긴다.
create table ingestion_genre_snapshot (
    id bigint not null auto_increment,
    job_execution_id bigint not null,
    vendor_key varchar(100) not null,
    genre_keyword varchar(50) not null,
    genre_provider varchar(20) not null,
    genre_model varchar(100) null,
    created_at datetime(6) not null,
    primary key (id)
) engine=InnoDB;

create unique index uk_ingestion_genre_snapshot_job on ingestion_genre_snapshot (job_execution_id);

-- 깊은 중첩(영업시간)만 JSON으로 구조 보존.
create table ingestion_google_place_snapshot (
    id bigint not null auto_increment,
    job_execution_id bigint not null,
    vendor_key varchar(100) not null,
    place_id varchar(200) null,
    display_name varchar(300) null,
    formatted_address varchar(500) null,
    regular_opening_hours json null,
    absent tinyint(1) not null default 0,
    created_at datetime(6) not null,
    primary key (id)
) engine=InnoDB;

create unique index uk_ingestion_google_snapshot_job on ingestion_google_place_snapshot (job_execution_id);
