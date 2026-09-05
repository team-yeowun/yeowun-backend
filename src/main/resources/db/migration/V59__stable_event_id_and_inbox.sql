-- 새 Outbox는 생성 시 고정 event_id를 발급하고, subscriber별 Inbox가 처리권과 종결 여부를 판정한다.
-- 기존 Outbox·DLQ·Redis 레코드는 eventId가 없으므로 호환 배포 동안 null을 허용한다.
-- 대량 기존 행을 한 번에 갱신하지 않아 Flyway 배포 시 장시간 잠금과 payload 재작성을 피한다.

alter table ingestion_outbox
    add column event_id varchar(36) null after id;

alter table ingestion_dead_letter
    add column event_id varchar(36) null after id;

create table ingestion_inbox (
    id bigint not null auto_increment,
    subscriber_key varchar(100) not null,
    event_id varchar(36) not null,
    status varchar(20) not null,
    claim_token varchar(36) null,
    started_at datetime(6) not null,
    lease_until datetime(6) null,
    completed_at datetime(6) null,
    last_error varchar(1000) null,
    primary key (id),
    unique key uk_ingestion_inbox_subscriber_event (subscriber_key, event_id),
    key idx_ingestion_inbox_status_lease (status, lease_until),
    key idx_ingestion_inbox_status_completed (status, completed_at, id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci;
