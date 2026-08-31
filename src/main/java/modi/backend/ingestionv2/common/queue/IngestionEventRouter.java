package modi.backend.ingestionv2.common.queue;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Component;

import modi.backend.ingestionv2.common.event.IngestionEventType;

/**
 * 이벤트를 맡을 핸들러를 고르는 라우터.
 *
 * <ul>
 *   <li>구현체 목록을 주입받을 뿐 어떤 도메인이 있는지 모름</li>
 *   <li>선택 기준은 구현체가 스스로 선언한 supports</li>
 *   <li>맡는 곳이 없으면 비어 있는 값 - 소비자 없는 이벤트를 예외로 만들지 않는다</li>
 * </ul>
 */
@Component
public class IngestionEventRouter {

	private final List<IngestionEventHandler> handlers;

	public IngestionEventRouter(List<IngestionEventHandler> handlers) {
		this.handlers = List.copyOf(handlers);
	}

	public Optional<IngestionEventHandler> route(IngestionEventType type) {
		return handlers.stream()
				.filter(handler -> handler.supports(type))
				.findFirst();
	}
}
