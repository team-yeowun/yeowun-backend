package modi.backend.ingestionv2.common.outbox;

import modi.backend.ingestionv2.common.event.IngestionEventType;

/**
 * 아웃박스에 행이 적재됐다는 사실 - 도메인 트랜잭션이 커밋된 뒤에만 의미가 있다.
 *
 * <ul>
 *   <li>싣는 것은 좌표 둘뿐 - 깨우기는 "무엇을" 보내는지 몰라도 된다. 미발행 행을 다시 조회하는 것이 발송기의 일이다</li>
 *   <li>수신자는 {@link OutboxDispatchWaker} 하나 - AFTER_COMMIT 에서만 듣는다</li>
 * </ul>
 */
public record OutboxAppended(IngestionEventType type, String aggregateId) {
}
