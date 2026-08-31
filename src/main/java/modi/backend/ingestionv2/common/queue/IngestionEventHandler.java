package modi.backend.ingestionv2.common.queue;

import modi.backend.ingestionv2.common.event.IngestionEventType;

/**
 * 이벤트를 처리하는 쪽이 구현하는 포트.
 *
 * <ul>
 *   <li>공용 계층이 선언하고 각 도메인의 interfaces가 구현 - 화살표는 항상 도메인에서 공용 계층으로</li>
 *   <li>supports로 자기가 맡을 이벤트를 스스로 선언 - 공용 계층은 배정표를 갖지 않음</li>
 *   <li>인자는 원천 키 하나 - 구현체가 그 키로 최신 상태를 다시 읽는다</li>
 * </ul>
 */
public interface IngestionEventHandler {

	boolean supports(IngestionEventType type);

	void handle(String vendorKey);
}
