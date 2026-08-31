-- 격리 테이블을 표준 DLQ 컬럼 구성(payload·error_message·stack_trace·failed_step·retry_count·failed_at·status)으로 맞춘다.
-- 컬럼 이름은 아웃박스(V55)의 어휘를 따른다: 좌표는 aggregate_type·aggregate_id·event_type, 본문은 payload, 횟수는 retry_count.
--
--   status       PENDING(관리자 미처리) → REPLAYED(아웃박스로 되돌려 보냄) / IGNORED(처리하지 않기로 함)
--   retry_count  격리 전까지 처리를 시도한 횟수. 상한 소진 격리는 max-attempts, 해석 불가 격리는 0
--   failed_step  오류가 난 지점(핸들러 이름 또는 DECODE)
--   stack_trace  애플리케이션이 남긴 상세 오류. 요약은 error_message
--
-- redriven_at(시각으로 상태를 대신하던 컬럼)은 status + resolved_at 으로 바뀐다.

delete from ingestion_dead_letter;

drop index idx_ingestion_dead_letter_redriven_at_id on ingestion_dead_letter;
drop index idx_ingestion_dead_letter_created_at on ingestion_dead_letter;

alter table ingestion_dead_letter
    drop column redriven_at,
    change column last_error error_message varchar(1000) null,
    change column created_at failed_at datetime(6) not null,
    add column stack_trace text null after error_message,
    add column failed_step varchar(100) null after stack_trace,
    add column retry_count int not null default 0 after failed_step,
    add column status varchar(20) not null default 'PENDING' after failed_at,
    add column resolved_at datetime(6) null after status;

-- 관리자 목록(status = 'PENDING' ORDER BY failed_at)과 알람 집계(status·failed_at 범위)가 이 인덱스를 탄다. 아웃박스 (status, created_at)과 같은 모양.
create index idx_ingestion_dead_letter_status_failed_at on ingestion_dead_letter (status, failed_at);
