-- Consumer Group은 ingestion-v2 하나라 subscriber_key는 모든 행에서 같은 값이었다.
-- Inbox 멱등 키를 event_id 하나로 줄인다. 새 UNIQUE를 걸고 옛 키·컬럼을 같은 ALTER에서 뺀다.
-- 새 event_id는 UUID v7이라 생성 순서대로 인덱스 끝에 들어간다(코드 변경이며 스키마는 같은 varchar(36)).

alter table ingestion_inbox
    add unique key uk_ingestion_inbox_event (event_id),
    drop key uk_ingestion_inbox_subscriber_event,
    drop column subscriber_key;
