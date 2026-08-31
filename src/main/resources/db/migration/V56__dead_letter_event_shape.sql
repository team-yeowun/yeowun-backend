-- 격리 테이블을 아웃박스(V55, outbox_event 모양)와 같은 어휘로 맞춘다.
--
--   aggregate_type + aggregate_id + event_type  아웃박스와 같은 좌표. 해석 불가 레코드는 셋 다 비어 있다
--   payload                                     걷어낸 스트림 레코드의 payload 원문. 해석에 실패해도 원문은 남긴다
--
-- 격리 행은 되돌려 보낼 좌표만 갖고 시도 횟수는 갖지 않는다(도메인 애그리거트 소유) — 그 원칙은 그대로다.
-- 아웃박스와 달리 payload가 NULL 허용인 이유: 해석 불가 레코드는 payload 필드 자체가 없을 수 있다.

delete from ingestion_dead_letter;

drop index idx_ingestion_dead_letter_vendor_key on ingestion_dead_letter;

alter table ingestion_dead_letter
    drop column vendor_key,
    add column aggregate_type varchar(100) null after id,
    add column aggregate_id varchar(100) null after aggregate_type,
    modify column event_type varchar(100) null,
    add column payload text null after event_type;

alter table ingestion_dead_letter
    modify column event_type varchar(100) null after aggregate_id;

-- 단건 추적. "이 전시가 왜 안 올라왔나"를 거꾸로 찾아 들어가는 경로.
create index idx_ingestion_dead_letter_aggregate_id on ingestion_dead_letter (aggregate_id);
