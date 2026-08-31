package modi.backend.ingestionv2.common.outbox;

import modi.backend.ingestionv2.common.event.IngestionEventType;

/**
 * 도메인이 사실을 적재하는 유일한 창구.
 *
 * <ul>
 *   <li>도메인 트랜잭션에 합류(REQUIRED) - 상태 변경과 같은 커밋에 들어감</li>
 *   <li>인자는 종류와 애그리거트 식별자(원천 키) 둘뿐 - payload 조립은 배달 계층의 일</li>
 *   <li>도메인은 이 인터페이스 타입으로 주입받는다(구현 클래스 참조 금지)</li>
 * </ul>
 */
public interface OutboxAppender {

	void append(IngestionEventType type, String aggregateId);
}
