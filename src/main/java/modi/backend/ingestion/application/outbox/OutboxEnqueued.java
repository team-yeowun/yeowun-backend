package modi.backend.ingestion.application.outbox;

import modi.backend.ingestion.domain.outbox.IngestionEventType;

/**
 * 아웃박스에 메시지가 적재됐음을 알리는 스프링 이벤트 — <b>디스패치 글루</b>다(ADR-10 "이벤트=글루, 테이블=엔진").
 *
 * <p>enqueue 트랜잭션이 커밋된 뒤({@code AFTER_COMMIT}) 릴레이가 이를 받아 드레인을 앞당긴다(폴링 주기 대기 제거).
 * 스프링 이벤트는 인메모리라 비durable하다 — 크래시로 유실돼도 릴레이의 스케줄 폴링(durable 엔진)이 같은 메시지를
 * 테이블에서 다시 집으므로 <b>이 이벤트의 유실은 지연일 뿐 손실이 아니다</b>. durability를 이벤트에 싣지 마라.
 *
 * @param messageType 적재된 메시지 종류(관측·로그용 — 릴레이는 종류와 무관하게 도래분 전체를 드레인한다)
 */
public record OutboxEnqueued(IngestionEventType messageType) {
}
