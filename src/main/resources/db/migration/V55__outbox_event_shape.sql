-- 아웃박스를 일반적인 트랜잭셔널 아웃박스 모양(outbox_event)으로 바꾼다.
--
--   aggregate_type + aggregate_id  어느 애그리거트의 사실인지(대기열 라우팅과 파티션 키의 근거)
--   payload                        이벤트 데이터 JSON. 컨슈머는 원장 대신 이 값을 읽는다
--   status                         PENDING(미발행) → SENT(발행 완료) / FAILED(발행 실패, 재시도 상한 소진)
--   retry_count                    발행 시도 횟수
--
-- 종결 확인(CONFIRMED)과 재발송 배치는 폐지한다. 발행 완료 뒤의 처리 확인은 스트림 미처리 목록(PEL)과 회수 배치가 맡는다.
-- 적용 순서: 파이프라인 정지 → 미발행 행 0 확인 → 이 마이그레이션 → 새 코드 배포. 미처리 행은 새 컬럼 제약(not null)에 걸리므로 먼저 비운다.

delete from ingestion_outbox;

drop index idx_ingestion_outbox_status_id on ingestion_outbox;
drop index idx_ingestion_outbox_status_sent_at on ingestion_outbox;
drop index idx_ingestion_outbox_type_key_status on ingestion_outbox;

alter table ingestion_outbox
    drop column vendor_key,
    drop column attempts,
    drop column last_error,
    add column aggregate_type varchar(100) not null after id,
    add column aggregate_id varchar(100) not null after aggregate_type,
    modify column event_type varchar(100) not null,
    add column payload text not null after event_type,
    modify column status varchar(20) not null default 'PENDING',
    add column retry_count int not null default 0 after status,
    modify column created_at datetime(6) not null default current_timestamp(6);

-- 미발행 선점(status = 'PENDING' ORDER BY created_at ... FOR UPDATE SKIP LOCKED)과
-- 정리 배치(status = 'SENT' AND created_at < ?), 관리자 실패 목록(status = 'FAILED')이 전부 이 인덱스를 탄다.
create index idx_ingestion_outbox_status_created on ingestion_outbox (status, created_at);
