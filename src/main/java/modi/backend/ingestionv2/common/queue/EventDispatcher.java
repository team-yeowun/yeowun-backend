package modi.backend.ingestionv2.common.queue;

import modi.backend.ingestionv2.common.event.OutboxPayload;

/**
 * 사실을 대기열로 내보내는 포트.
 *
 * <ul>
 *   <li>인자는 이벤트 데이터 하나 - 배달 계층의 행 번호를 이벤트에 섞지 않음</li>
 *   <li>구현체 교체가 대기열 교체 - 아웃박스와 도메인과 소비 코드는 그대로</li>
 * </ul>
 */
public interface EventDispatcher {

	void dispatch(OutboxPayload payload);
}
