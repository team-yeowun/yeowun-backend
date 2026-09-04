-- 존재만 남던 회차 마크를 복구 가능한 실행권으로 확장한다.
-- 기존 행은 이미 끝난 회차이므로 COMPLETED로 이관한다.
alter table ingestion_collect_batch_mark
    add column status varchar(20) null after batch_date,
    add column claim_token varchar(36) null after status,
    add column lease_until datetime(6) null after claimed_at,
    add column completed_at datetime(6) null after lease_until,
    add column last_error varchar(1000) null after completed_at;

update ingestion_collect_batch_mark
set status = 'COMPLETED', completed_at = claimed_at;

alter table ingestion_collect_batch_mark
    modify column status varchar(20) not null;
